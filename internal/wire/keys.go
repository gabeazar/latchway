// Package wire implements the Latchway v1 key schedule, authentication proof,
// encrypted signaling envelope and chunk framing exactly as specified in
// docs/PROTOCOL.md. The Android app and the browser receiver carry
// independent implementations of the same rules; testdata/vectors.json keeps
// them honest.
package wire

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net/url"
	"regexp"
	"strings"
	"unicode/utf8"

	"golang.org/x/crypto/hkdf"
	"golang.org/x/crypto/pbkdf2"
	"golang.org/x/text/unicode/norm"
)

const (
	// ProtocolVersion is sent in the hello message.
	ProtocolVersion = 1

	// ChunkSize is the plaintext size of every chunk but the last.
	ChunkSize = 64 * 1024

	ShareIDLen = 16
	SecretLen  = 32
	NonceLen   = 16
	KeyLen     = 32

	// PBKDF2Iterations is deliberately high: the password is an online
	// second factor, verified by the sender's phone, and we want offline
	// guessing to hurt.
	PBKDF2Iterations = 600_000

	// MaxSignalPayload bounds the `d` field of a rendezvous message.
	MaxSignalPayload = 16 * 1024
)

var (
	labelPW      = []byte("latchway/v1/pw")
	labelExtract = []byte("latchway/v1")
	labelAuth    = []byte("latchway/v1/auth")
	labelRoot    = []byte("latchway/v1/root")
	labelProof   = []byte("latchway/v1/proof")
	labelSession = []byte("latchway/v1/session")
	labelSSig    = []byte("latchway/v1/s/sig")
	labelSFile   = []byte("latchway/v1/s/file")
	aadSig       = []byte("latchway/v1/sig")
	aadChunk     = []byte("latchway/v1/chunk")
)

// B64 is the base64url-without-padding alphabet used everywhere in Latchway.
var B64 = base64.RawURLEncoding

// Share is a share identity: a public id and the secret that only the link
// carries.
type Share struct {
	ID     [ShareIDLen]byte
	Secret [SecretLen]byte
}

// NewShare draws a fresh share identity from the system CSPRNG.
func NewShare() (Share, error) {
	var s Share
	if _, err := io.ReadFull(rand.Reader, s.ID[:]); err != nil {
		return s, err
	}
	if _, err := io.ReadFull(rand.Reader, s.Secret[:]); err != nil {
		return s, err
	}
	return s, nil
}

// IDString returns the share id as it appears in links.
func (s Share) IDString() string { return B64.EncodeToString(s.ID[:]) }

// SecretString returns the secret as it appears in link fragments.
func (s Share) SecretString() string { return B64.EncodeToString(s.Secret[:]) }

// Link builds the https link for a rendezvous host such as "latchway.app".
func (s Share) Link(host string) string {
	return "https://" + host + "/s/" + s.IDString() + "#" + s.SecretString()
}

// DeepLink builds the custom-scheme form of the same link.
func (s Share) DeepLink(host string) string {
	return "latchway://" + host + "/s/" + s.IDString() + "#" + s.SecretString()
}

var shareIDRe = regexp.MustCompile(`^[A-Za-z0-9_-]{22}$`)
var secretRe = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)

// ParseShareID decodes a 22-character share id. Non-canonical spellings
// (trailing padding bits set) are rejected so that an id has exactly one
// string form.
func ParseShareID(s string) ([ShareIDLen]byte, error) {
	var id [ShareIDLen]byte
	if !shareIDRe.MatchString(s) {
		return id, errors.New("malformed share id")
	}
	b, err := B64.DecodeString(s)
	if err != nil || len(b) != ShareIDLen || B64.EncodeToString(b) != s {
		return id, errors.New("malformed share id")
	}
	copy(id[:], b)
	return id, nil
}

// CanonicalShareID reports whether s is a well-formed, canonical share id.
func CanonicalShareID(s string) bool {
	_, err := ParseShareID(s)
	return err == nil
}

// RegKeyLen is the size of a share's registration key (PROTOCOL.md §3.1).
const RegKeyLen = 32

// NewRegKey draws a registration key. It lives only on the sender's device.
func NewRegKey() ([]byte, error) {
	k := make([]byte, RegKeyLen)
	if _, err := io.ReadFull(rand.Reader, k); err != nil {
		return nil, err
	}
	return k, nil
}

// ParseLink accepts both link forms and returns the rendezvous host and the
// share identity. It never logs or echoes the secret.
func ParseLink(raw string) (host string, share Share, err error) {
	raw = strings.TrimSpace(raw)
	u, err := url.Parse(raw)
	if err != nil {
		return "", share, errors.New("not a link")
	}
	if u.Scheme != "https" && u.Scheme != "latchway" {
		return "", share, errors.New("not a Latchway link")
	}
	if u.Host == "" {
		return "", share, errors.New("link has no host")
	}
	parts := strings.Split(strings.Trim(u.Path, "/"), "/")
	if len(parts) != 2 || parts[0] != "s" {
		return "", share, errors.New("not a Latchway link")
	}
	share.ID, err = ParseShareID(parts[1])
	if err != nil {
		return "", share, err
	}
	frag := u.Fragment
	if !secretRe.MatchString(frag) {
		return "", share, errors.New("link is missing its key (the part after #)")
	}
	sec, err := B64.DecodeString(frag)
	if err != nil || len(sec) != SecretLen {
		return "", share, errors.New("link key is malformed")
	}
	copy(share.Secret[:], sec)
	return u.Host, share, nil
}

// Keys are the long-term keys derived from a link (and optional password).
type Keys struct {
	Auth [KeyLen]byte
	Root [KeyLen]byte
}

// DeriveKeys implements PROTOCOL.md §2. password may be empty.
func DeriveKeys(shareID []byte, secret []byte, password string) Keys {
	pwk := make([]byte, KeyLen)
	if password != "" {
		normalized := norm.NFC.String(password)
		salt := append(append([]byte{}, labelPW...), shareID...)
		pwk = pbkdf2.Key([]byte(normalized), salt, PBKDF2Iterations, KeyLen, sha256.New)
	}
	ikm := append(append([]byte{}, secret...), pwk...)
	salt := append(append([]byte{}, labelExtract...), shareID...)
	prk := hkdf.Extract(sha256.New, ikm, salt)

	var k Keys
	expand(prk, labelAuth, k.Auth[:])
	expand(prk, labelRoot, k.Root[:])
	return k
}

// SessionKeys are fresh per session, derived from Root and both nonces.
type SessionKeys struct {
	Sig  [KeyLen]byte
	File [KeyLen]byte
}

// DeriveSession implements the per-session step of PROTOCOL.md §2.
func DeriveSession(root []byte, hostNonce, joinerNonce []byte) SessionKeys {
	salt := append(append(append([]byte{}, labelSession...), hostNonce...), joinerNonce...)
	sprk := hkdf.Extract(sha256.New, root, salt)
	var s SessionKeys
	expand(sprk, labelSSig, s.Sig[:])
	expand(sprk, labelSFile, s.File[:])
	return s
}

func expand(prk, info, out []byte) {
	if _, err := io.ReadFull(hkdf.Expand(sha256.New, prk, info), out); err != nil {
		panic("hkdf expand: " + err.Error())
	}
}

// Proof computes the joiner's proof of link (and password) possession:
// HMAC-SHA256(K_auth, "latchway/v1/proof" || v || pw || hostNonce || joinerNonce).
// Binding the hello parameters stops a relay from flipping them unnoticed.
func Proof(auth []byte, version int, password bool, hostNonce, joinerNonce []byte) []byte {
	m := hmac.New(sha256.New, auth)
	m.Write(labelProof)
	pw := byte(0)
	if password {
		pw = 1
	}
	m.Write([]byte{byte(version), pw})
	m.Write(hostNonce)
	m.Write(joinerNonce)
	return m.Sum(nil)
}

// VerifyProof compares in constant time.
func VerifyProof(auth []byte, version int, password bool, hostNonce, joinerNonce, proof []byte) bool {
	want := Proof(auth, version, password, hostNonce, joinerNonce)
	return subtle.ConstantTimeCompare(want, proof) == 1
}

// MetaSize is the exact serialised size of every meta message, so that the
// ciphertext length reveals nothing about the file (PROTOCOL.md §4.3).
const MetaSize = 1024

// MaxNameBytes bounds the file name carried in meta.
const MaxNameBytes = 255

// PadMeta serialises a meta message to exactly MetaSize bytes by filling
// Pad with spaces. Name is truncated to MaxNameBytes (on a rune boundary)
// first; From is truncated harder if needed.
func PadMeta(m Msg) ([]byte, error) {
	m.Name = truncateUTF8(m.Name, MaxNameBytes)
	m.From = truncateUTF8(m.From, 64)
	m.Mime = truncateUTF8(m.Mime, 128)
	m.Pad = ""
	base := len(m.Encode())
	// Encoding with an empty Pad omits the field; account for `,"pad":""`.
	overhead := len(`,"pad":""`)
	need := MetaSize - base - overhead
	if need < 0 {
		return nil, errors.New("meta does not fit in the fixed size")
	}
	m.Pad = strings.Repeat(" ", need)
	out := m.Encode()
	if len(out) != MetaSize {
		return nil, fmt.Errorf("meta padding produced %d bytes", len(out))
	}
	return out, nil
}

func truncateUTF8(s string, max int) string {
	if len(s) <= max {
		return s
	}
	cut := max
	for cut > 0 && !utf8.RuneStart(s[cut]) {
		cut--
	}
	return s[:cut]
}

// NewNonce draws a 16-byte nonce.
func NewNonce() ([]byte, error) {
	n := make([]byte, NonceLen)
	if _, err := io.ReadFull(rand.Reader, n); err != nil {
		return nil, err
	}
	return n, nil
}

// Direction of an encrypted signaling message.
type Direction byte

const (
	HostToJoiner Direction = 0x00
	JoinerToHost Direction = 0x01
)

// Sealer encrypts outgoing signaling messages with a strictly increasing
// counter.
type Sealer struct {
	aead    cipher.AEAD
	dir     Direction
	counter uint64
}

// Opener decrypts incoming signaling messages and rejects replays and gaps.
type Opener struct {
	aead     cipher.AEAD
	dir      Direction
	expected uint64
}

func newAEAD(key []byte) cipher.AEAD {
	block, err := aes.NewCipher(key)
	if err != nil {
		panic("aes: " + err.Error())
	}
	g, err := cipher.NewGCM(block)
	if err != nil {
		panic("gcm: " + err.Error())
	}
	return g
}

// NewSealer creates the sender half for one direction.
func NewSealer(sigKey []byte, dir Direction) *Sealer {
	return &Sealer{aead: newAEAD(sigKey), dir: dir}
}

// NewOpener creates the receiver half for one direction.
func NewOpener(sigKey []byte, dir Direction) *Opener {
	return &Opener{aead: newAEAD(sigKey), dir: dir}
}

func sigNonce(dir Direction, counter uint64) []byte {
	n := make([]byte, 12)
	n[0] = byte(dir)
	putU64(n[4:], counter)
	return n
}

// Seal returns the base64url ciphertext for one plaintext message.
func (s *Sealer) Seal(plaintext []byte) string {
	nonce := sigNonce(s.dir, s.counter)
	s.counter++
	return B64.EncodeToString(s.aead.Seal(nil, nonce, plaintext, aadSig))
}

// Counter returns how many messages have been sealed so far.
func (s *Sealer) Counter() uint64 { return s.counter }

// Open decrypts the next message. A failure is fatal for the session: the
// caller must abort, because either the peer is an impostor or the stream
// was tampered with.
func (o *Opener) Open(c string) ([]byte, error) {
	ct, err := B64.DecodeString(c)
	if err != nil {
		return nil, errors.New("envelope is not base64url")
	}
	nonce := sigNonce(o.dir, o.expected)
	pt, err := o.aead.Open(nil, nonce, ct, aadSig)
	if err != nil {
		return nil, errors.New("envelope failed authentication")
	}
	o.expected++
	return pt, nil
}

// FrameHeaderLen is the size of the chunk header (index + flags).
const FrameHeaderLen = 5

// FlagLast marks the final chunk.
const FlagLast byte = 0x01

// MaxFrameLen bounds a chunk frame: header + plaintext + GCM tag.
const MaxFrameLen = FrameHeaderLen + ChunkSize + 16

// ChunkSealer encrypts file chunks with S_file.
type ChunkSealer struct{ aead cipher.AEAD }

// ChunkOpener decrypts file chunks and enforces strict ordering.
type ChunkOpener struct {
	aead cipher.AEAD
	next uint32
	done bool
}

// NewChunkSealer creates a sealer for one session.
func NewChunkSealer(fileKey []byte) *ChunkSealer { return &ChunkSealer{aead: newAEAD(fileKey)} }

// NewChunkOpener creates an opener for one session.
func NewChunkOpener(fileKey []byte) *ChunkOpener { return &ChunkOpener{aead: newAEAD(fileKey)} }

func chunkNonce(index uint32) []byte {
	n := make([]byte, 12)
	putU32(n[8:], index)
	return n
}

func chunkAAD(index uint32, flags byte) []byte {
	aad := make([]byte, len(aadChunk)+5)
	copy(aad, aadChunk)
	putU32(aad[len(aadChunk):], index)
	aad[len(aadChunk)+4] = flags
	return aad
}

// Seal builds a complete frame for chunk `index`.
func (s *ChunkSealer) Seal(index uint32, last bool, plaintext []byte) ([]byte, error) {
	if len(plaintext) > ChunkSize {
		return nil, fmt.Errorf("chunk too large: %d", len(plaintext))
	}
	var flags byte
	if last {
		flags |= FlagLast
	}
	frame := make([]byte, FrameHeaderLen, MaxFrameLen)
	putU32(frame[0:], index)
	frame[4] = flags
	return s.aead.Seal(frame, chunkNonce(index), plaintext, chunkAAD(index, flags)), nil
}

// Open verifies and decrypts the next frame. It returns the plaintext and
// whether this was the last chunk.
func (o *ChunkOpener) Open(frame []byte) (plaintext []byte, last bool, err error) {
	if o.done {
		return nil, false, errors.New("frame after last chunk")
	}
	if len(frame) < FrameHeaderLen+16 || len(frame) > MaxFrameLen {
		return nil, false, errors.New("frame has impossible length")
	}
	index := getU32(frame[0:])
	flags := frame[4]
	if index != o.next {
		return nil, false, fmt.Errorf("chunk %d out of order (expected %d)", index, o.next)
	}
	if flags&^FlagLast != 0 {
		return nil, false, errors.New("unknown chunk flags")
	}
	pt, err := o.aead.Open(nil, chunkNonce(index), frame[FrameHeaderLen:], chunkAAD(index, flags))
	if err != nil {
		return nil, false, errors.New("chunk failed authentication")
	}
	last = flags&FlagLast != 0
	if !last && len(pt) != ChunkSize {
		return nil, false, errors.New("short chunk before the last one")
	}
	o.next++
	o.done = last
	return pt, last, nil
}

// Next returns the index the opener expects next.
func (o *ChunkOpener) Next() uint32 { return o.next }

func putU32(b []byte, v uint32) {
	b[0] = byte(v >> 24)
	b[1] = byte(v >> 16)
	b[2] = byte(v >> 8)
	b[3] = byte(v)
}

func getU32(b []byte) uint32 {
	return uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])
}

func putU64(b []byte, v uint64) {
	for i := 7; i >= 0; i-- {
		b[i] = byte(v)
		v >>= 8
	}
}
