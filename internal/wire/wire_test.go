package wire

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"unicode/utf8"

	"golang.org/x/crypto/hkdf"
)

var update = flag.Bool("update", false, "rewrite testdata/vectors.json")

// RFC 5869 test case 1 guards the HKDF primitive we build everything on.
func TestHKDFRFC5869(t *testing.T) {
	ikm, _ := hex.DecodeString("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
	salt, _ := hex.DecodeString("000102030405060708090a0b0c")
	info, _ := hex.DecodeString("f0f1f2f3f4f5f6f7f8f9")
	wantPRK := "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"
	wantOKM := "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"

	prk := hkdf.Extract(sha256.New, ikm, salt)
	if hex.EncodeToString(prk) != wantPRK {
		t.Fatalf("prk = %x", prk)
	}
	okm := make([]byte, 42)
	expand(prk, info, okm)
	if hex.EncodeToString(okm) != wantOKM {
		t.Fatalf("okm = %x", okm)
	}
}

func TestLinkRoundTrip(t *testing.T) {
	s, err := NewShare()
	if err != nil {
		t.Fatal(err)
	}
	link := s.Link("latchway.app")
	host, got, err := ParseLink(link)
	if err != nil {
		t.Fatal(err)
	}
	if host != "latchway.app" || got != s {
		t.Fatalf("round trip mismatch: %q %+v", host, got)
	}
	host2, got2, err := ParseLink(s.DeepLink("latchway.app"))
	if err != nil || host2 != "latchway.app" || got2 != s {
		t.Fatalf("deep link round trip failed: %v", err)
	}
	for _, bad := range []string{
		"https://latchway.app/s/" + s.IDString(), // no secret
		"https://latchway.app/x/" + s.IDString() + "#" + s.SecretString(),
		"http://latchway.app/s/" + s.IDString() + "#" + s.SecretString(), // plain http
		"https://latchway.app/s/short#" + s.SecretString(),
		"latchway.app/s/" + s.IDString() + "#" + s.SecretString(), // no scheme
	} {
		if _, _, err := ParseLink(bad); err == nil {
			t.Errorf("expected error for %q", bad)
		}
	}
}

func TestProofAndSessionKeys(t *testing.T) {
	s, _ := NewShare()
	k := DeriveKeys(s.ID[:], s.Secret[:], "")
	kp := DeriveKeys(s.ID[:], s.Secret[:], "hunter2")
	if k == kp {
		t.Fatal("password must change the keys")
	}
	// NFC normalisation: "é" composed vs decomposed derive the same keys.
	a := DeriveKeys(s.ID[:], s.Secret[:], "café")
	b := DeriveKeys(s.ID[:], s.Secret[:], "café")
	if a != b {
		t.Fatal("NFC normalisation missing")
	}

	hn, _ := NewNonce()
	jn, _ := NewNonce()
	p := Proof(k.Auth[:], ProtocolVersion, false, hn, jn)
	if !VerifyProof(k.Auth[:], ProtocolVersion, false, hn, jn, p) {
		t.Fatal("proof should verify")
	}
	if VerifyProof(kp.Auth[:], ProtocolVersion, false, hn, jn, p) {
		t.Fatal("proof must fail under a different password")
	}
	if VerifyProof(k.Auth[:], ProtocolVersion, false, jn, hn, p) {
		t.Fatal("proof must be bound to nonce order")
	}
	if VerifyProof(k.Auth[:], ProtocolVersion, true, hn, jn, p) {
		t.Fatal("proof must be bound to the pw flag")
	}
	if VerifyProof(k.Auth[:], 2, false, hn, jn, p) {
		t.Fatal("proof must be bound to the version")
	}

	s1 := DeriveSession(k.Root[:], hn, jn)
	s2 := DeriveSession(k.Root[:], hn, jn)
	if s1 != s2 {
		t.Fatal("session derivation must be deterministic")
	}
	hn2, _ := NewNonce()
	if DeriveSession(k.Root[:], hn2, jn) == s1 {
		t.Fatal("different nonces must give different session keys")
	}
}

func TestEnvelopeOrdering(t *testing.T) {
	key := bytes.Repeat([]byte{7}, 32)
	sealer := NewSealer(key, HostToJoiner)
	opener := NewOpener(key, HostToJoiner)
	wrongDir := NewOpener(key, JoinerToHost)

	c0 := sealer.Seal([]byte("zero"))
	c1 := sealer.Seal([]byte("one"))

	if _, err := wrongDir.Open(c0); err == nil {
		t.Fatal("wrong direction must fail")
	}
	if _, err := opener.Open(c1); err == nil {
		t.Fatal("skipping a message must fail")
	}
	pt, err := opener.Open(c0)
	if err != nil || string(pt) != "zero" {
		t.Fatalf("open c0: %v %q", err, pt)
	}
	if _, err := opener.Open(c0); err == nil {
		t.Fatal("replay must fail")
	}
	pt, err = opener.Open(c1)
	if err != nil || string(pt) != "one" {
		t.Fatalf("open c1: %v %q", err, pt)
	}
	// Tampering.
	raw, _ := B64.DecodeString(sealer.Seal([]byte("two")))
	raw[len(raw)-1] ^= 1
	if _, err := opener.Open(B64.EncodeToString(raw)); err == nil {
		t.Fatal("tampered envelope must fail")
	}
}

func TestChunkFraming(t *testing.T) {
	key := bytes.Repeat([]byte{9}, 32)
	sealer := NewChunkSealer(key)
	opener := NewChunkOpener(key)

	full := bytes.Repeat([]byte{0xAB}, ChunkSize)
	tail := []byte("tail")

	f0, _ := sealer.Seal(0, false, full)
	f1, _ := sealer.Seal(1, true, tail)
	if len(f0) != MaxFrameLen {
		t.Fatalf("full frame len %d", len(f0))
	}

	if _, _, err := opener.Open(f1); err == nil {
		t.Fatal("out-of-order frame must fail")
	}
	pt, last, err := opener.Open(f0)
	if err != nil || last || !bytes.Equal(pt, full) {
		t.Fatalf("frame 0: %v last=%v", err, last)
	}
	if _, _, err := opener.Open(f0); err == nil {
		t.Fatal("replayed frame must fail")
	}
	pt, last, err = opener.Open(f1)
	if err != nil || !last || !bytes.Equal(pt, tail) {
		t.Fatalf("frame 1: %v last=%v", err, last)
	}
	if _, _, err := opener.Open(f1); err == nil {
		t.Fatal("frames after last must fail")
	}

	// A short non-final chunk is rejected (truncation attempt).
	s2 := NewChunkSealer(key)
	o2 := NewChunkOpener(key)
	short, _ := s2.Seal(0, false, []byte("short"))
	if _, _, err := o2.Open(short); err == nil {
		t.Fatal("short non-final chunk must fail")
	}

	// Flag flip: mark a non-final frame as final → AAD mismatch.
	s3 := NewChunkSealer(key)
	o3 := NewChunkOpener(key)
	f, _ := s3.Seal(0, false, full)
	f[4] = FlagLast
	if _, _, err := o3.Open(f); err == nil {
		t.Fatal("flag tampering must fail")
	}

	// Empty file: a single empty final chunk.
	s4 := NewChunkSealer(key)
	o4 := NewChunkOpener(key)
	e, _ := s4.Seal(0, true, nil)
	pt, last, err = o4.Open(e)
	if err != nil || !last || len(pt) != 0 {
		t.Fatalf("empty final chunk: %v", err)
	}
}

func TestShareIDCanonical(t *testing.T) {
	// 0xFF... decodes fine but its canonical spelling ends in "w"; the
	// spelling ending in "x" has a stray padding bit set.
	canon := B64.EncodeToString(bytes.Repeat([]byte{0xff}, ShareIDLen))
	if !CanonicalShareID(canon) {
		t.Fatalf("%q should be canonical", canon)
	}
	alt := canon[:21] + "x"
	if b, err := B64.DecodeString(alt); err != nil || !bytes.Equal(b, bytes.Repeat([]byte{0xff}, ShareIDLen)) {
		t.Fatalf("test setup: %q should decode to the same bytes (err=%v)", alt, err)
	}
	if CanonicalShareID(alt) {
		t.Fatalf("non-canonical id %q accepted", alt)
	}
}

func TestPadMeta(t *testing.T) {
	for _, name := range []string{"a", strings.Repeat("é", 300), "vacation.mp4"} {
		b, err := PadMeta(Msg{T: TMeta, Name: name, Size: Int64(1 << 40), Mime: "video/mp4", Chunk: ChunkSize, From: "Gabe", Approval: Bool(true)})
		if err != nil {
			t.Fatal(err)
		}
		if len(b) != MetaSize {
			t.Fatalf("len %d", len(b))
		}
		m, err := Decode(b)
		if err != nil || m.T != TMeta || m.Chunk != ChunkSize {
			t.Fatalf("decode: %v", err)
		}
		if !utf8.ValidString(m.Name) {
			t.Fatal("truncated name must stay valid UTF-8")
		}
	}
}

func TestMsgEncoding(t *testing.T) {
	m := Msg{T: TMeta, Name: "a.bin", Size: Int64(5), Chunk: ChunkSize, Approval: Bool(false)}
	b := m.Encode()
	if !strings.Contains(string(b), `"approval":false`) {
		t.Fatalf("explicit false must be encoded: %s", b)
	}
	d, err := Decode(b)
	if err != nil || d.T != TMeta || *d.Size != 5 {
		t.Fatalf("decode: %v %+v", err, d)
	}
	if _, err := Decode([]byte(`{"x":1}`)); err == nil {
		t.Fatal("type is required")
	}
}

// Vectors is the cross-implementation fixture shape. Every value is hex or
// base64url exactly as the other implementations will compare it.
type Vectors struct {
	Note        string `json:"note"`
	ShareID     string `json:"share_id_b64"`
	Secret      string `json:"secret_b64"`
	Link        string `json:"link"`
	Password    string `json:"password"`
	HostNonce   string `json:"host_nonce_hex"`
	JoinerNonce string `json:"joiner_nonce_hex"`

	NoPassword struct {
		KAuth string `json:"k_auth_hex"`
		KRoot string `json:"k_root_hex"`
		Proof string `json:"proof_b64"`
		SSig  string `json:"s_sig_hex"`
		SFile string `json:"s_file_hex"`
	} `json:"no_password"`

	WithPassword struct {
		KAuth string `json:"k_auth_hex"`
		KRoot string `json:"k_root_hex"`
		Proof string `json:"proof_b64"`
		SSig  string `json:"s_sig_hex"`
		SFile string `json:"s_file_hex"`
	} `json:"with_password"`

	Envelope struct {
		Key        string   `json:"key_hex"`
		Direction  int      `json:"direction"`
		Plaintexts []string `json:"plaintexts"`
		Sealed     []string `json:"sealed_b64"`
	} `json:"envelope"`

	Meta struct {
		Name   string `json:"name"`
		Size   int64  `json:"size"`
		Mime   string `json:"mime"`
		From   string `json:"from"`
		Padded string `json:"padded_json"`
	} `json:"meta"`

	Chunks struct {
		Key    string `json:"key_hex"`
		Plain0 string `json:"plain0_hex"`
		Frame0 string `json:"frame0_hex"`
		Plain1 string `json:"plain1_hex"`
		Frame1 string `json:"frame1_hex"`
		Empty  string `json:"empty_final_frame_hex"`
	} `json:"chunks"`
}

func fixedBytes(seed byte, n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = byte(int(seed) + i)
	}
	return b
}

func buildVectors() Vectors {
	var v Vectors
	v.Note = "Generated by `go test ./internal/wire -update`. Every implementation must reproduce these exactly."
	shareID := fixedBytes(0x10, ShareIDLen)
	secret := fixedBytes(0x40, SecretLen)
	hn := fixedBytes(0xA0, NonceLen)
	jn := fixedBytes(0xC0, NonceLen)
	v.ShareID = B64.EncodeToString(shareID)
	v.Secret = B64.EncodeToString(secret)
	var s Share
	copy(s.ID[:], shareID)
	copy(s.Secret[:], secret)
	v.Link = s.Link("latchway.app")
	v.Password = "correct horse battery staple"
	v.HostNonce = hex.EncodeToString(hn)
	v.JoinerNonce = hex.EncodeToString(jn)

	fill := func(dst *struct {
		KAuth string `json:"k_auth_hex"`
		KRoot string `json:"k_root_hex"`
		Proof string `json:"proof_b64"`
		SSig  string `json:"s_sig_hex"`
		SFile string `json:"s_file_hex"`
	}, pw string) {
		k := DeriveKeys(shareID, secret, pw)
		dst.KAuth = hex.EncodeToString(k.Auth[:])
		dst.KRoot = hex.EncodeToString(k.Root[:])
		dst.Proof = B64.EncodeToString(Proof(k.Auth[:], ProtocolVersion, pw != "", hn, jn))
		sk := DeriveSession(k.Root[:], hn, jn)
		dst.SSig = hex.EncodeToString(sk.Sig[:])
		dst.SFile = hex.EncodeToString(sk.File[:])
	}
	fill(&v.NoPassword, "")
	fill(&v.WithPassword, v.Password)

	envKey := fixedBytes(0x01, KeyLen)
	v.Envelope.Key = hex.EncodeToString(envKey)
	v.Envelope.Direction = int(JoinerToHost)
	sealer := NewSealer(envKey, JoinerToHost)
	v.Envelope.Plaintexts = []string{`{"t":"go"}`, `{"t":"ice-done"}`, `{"t":"offer","sdp":"v=0\r\n","start":0}`}
	for _, p := range v.Envelope.Plaintexts {
		v.Envelope.Sealed = append(v.Envelope.Sealed, sealer.Seal([]byte(p)))
	}

	v.Meta.Name = "vacation.mp4"
	v.Meta.Size = 2_400_000_000
	v.Meta.Mime = "video/mp4"
	v.Meta.From = "Gabe"
	padded, err := PadMeta(Msg{T: TMeta, Name: v.Meta.Name, Size: Int64(v.Meta.Size), Mime: v.Meta.Mime, Chunk: ChunkSize, From: v.Meta.From, Approval: Bool(false)})
	if err != nil {
		panic(err)
	}
	v.Meta.Padded = string(padded)

	fileKey := fixedBytes(0x80, KeyLen)
	v.Chunks.Key = hex.EncodeToString(fileKey)
	cs := NewChunkSealer(fileKey)
	p0 := make([]byte, ChunkSize)
	for i := range p0 {
		p0[i] = byte(i * 7)
	}
	p1 := []byte("the last chunk may be short")
	f0, _ := cs.Seal(0, false, p0)
	f1, _ := cs.Seal(1, true, p1)
	v.Chunks.Plain0 = hex.EncodeToString(p0)
	v.Chunks.Frame0 = hex.EncodeToString(f0)
	v.Chunks.Plain1 = hex.EncodeToString(p1)
	v.Chunks.Frame1 = hex.EncodeToString(f1)
	e, _ := NewChunkSealer(fileKey).Seal(0, true, nil)
	v.Chunks.Empty = hex.EncodeToString(e)
	return v
}

func TestVectors(t *testing.T) {
	path := filepath.Join("..", "..", "testdata", "vectors.json")
	got := buildVectors()
	if *update {
		b, _ := json.MarshalIndent(got, "", "  ")
		if err := os.WriteFile(path, append(b, '\n'), 0o644); err != nil {
			t.Fatal(err)
		}
		t.Log("vectors written")
		return
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read vectors: %v (run with -update to create)", err)
	}
	var want Vectors
	if err := json.Unmarshal(raw, &want); err != nil {
		t.Fatal(err)
	}
	gb, _ := json.Marshal(got)
	wb, _ := json.Marshal(want)
	if !bytes.Equal(gb, wb) {
		t.Fatalf("vectors drifted from testdata/vectors.json; run -update only if the protocol intentionally changed")
	}

	// And the opposite direction: decrypt the fixture with fresh state.
	envKey, _ := hex.DecodeString(want.Envelope.Key)
	op := NewOpener(envKey, Direction(want.Envelope.Direction))
	for i, c := range want.Envelope.Sealed {
		pt, err := op.Open(c)
		if err != nil || string(pt) != want.Envelope.Plaintexts[i] {
			t.Fatalf("envelope %d: %v", i, err)
		}
	}
	fileKey, _ := hex.DecodeString(want.Chunks.Key)
	co := NewChunkOpener(fileKey)
	f0, _ := hex.DecodeString(want.Chunks.Frame0)
	f1, _ := hex.DecodeString(want.Chunks.Frame1)
	if _, last, err := co.Open(f0); err != nil || last {
		t.Fatalf("frame0: %v", err)
	}
	if pt, last, err := co.Open(f1); err != nil || !last || hex.EncodeToString(pt) != want.Chunks.Plain1 {
		t.Fatalf("frame1: %v", err)
	}
}
