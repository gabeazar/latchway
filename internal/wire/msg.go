package wire

import (
	"encoding/json"
	"errors"
)

// Msg is the single JSON shape used for every rendezvous, signaling and
// data-channel control message. Only `t` is always present; which other
// fields are meaningful depends on the type (see PROTOCOL.md §3–4).
type Msg struct {
	T string `json:"t"`

	// Rendezvous layer.
	SID  string            `json:"sid,omitempty"`
	D    string            `json:"d,omitempty"`
	ICE  []ICEServer       `json:"ice,omitempty"`
	Code string            `json:"code,omitempty"`

	// Session layer (plaintext hello/auth).
	V  int    `json:"v,omitempty"`
	PW *bool  `json:"pw,omitempty"`
	N  string `json:"n,omitempty"`
	P  string `json:"p,omitempty"`

	// Encrypted envelope.
	C string `json:"c,omitempty"`

	// Encrypted session messages.
	Name     string `json:"name,omitempty"`
	Size     *int64 `json:"size,omitempty"`
	Mime     string `json:"mime,omitempty"`
	Chunk    int    `json:"chunk,omitempty"`
	From     string `json:"from,omitempty"`
	Approval *bool  `json:"approval,omitempty"`
	SDP      string `json:"sdp,omitempty"`
	Start    *uint32 `json:"start,omitempty"`
	Cand     string `json:"cand,omitempty"`
	Mid      string `json:"mid,omitempty"`
	MLine    *uint16 `json:"mline,omitempty"`

	// Data-channel control.
	Bytes  *int64 `json:"bytes,omitempty"`
	Reason string `json:"reason,omitempty"`
}

// ICEServer mirrors the WebRTC RTCIceServer dictionary.
type ICEServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username,omitempty"`
	Credential string   `json:"credential,omitempty"`
}

// Message type names.
const (
	// Rendezvous.
	TReady  = "ready"
	TJoined = "joined"
	TJoin   = "join"
	TSig    = "sig"
	TLeave  = "leave"
	TError  = "error"
	TPing   = "ping"
	TPong   = "pong"

	// Session, plaintext.
	THello = "hello"
	TAuth  = "auth"
	TEnc   = "enc"

	// Session, encrypted.
	TMeta    = "meta"
	TGo      = "go"
	TOffer   = "offer"
	TAnswer  = "answer"
	TICE     = "ice"
	TICEDone = "ice-done"

	// Data channel.
	TProgress = "progress"
	TDone     = "done"
	TAbort    = "abort"
)

// Error codes.
const (
	ErrNotFound    = "not_found"
	ErrHostGone    = "host_gone"
	ErrReplaced    = "replaced"
	ErrClosed      = "closed"
	ErrBadAuth     = "bad_auth"
	ErrDenied      = "denied"
	ErrExpired     = "expired"
	ErrBusy        = "busy"
	ErrRateLimited = "rate_limited"
	ErrTimeout     = "timeout"
	ErrProtocol    = "protocol"
	ErrInternal    = "internal"
)

// Encode marshals a message. It never fails for well-formed structs.
func (m Msg) Encode() []byte {
	b, err := json.Marshal(m)
	if err != nil {
		panic("msg encode: " + err.Error())
	}
	return b
}

// Decode parses a message and requires a type.
func Decode(b []byte) (Msg, error) {
	var m Msg
	if err := json.Unmarshal(b, &m); err != nil {
		return m, err
	}
	if m.T == "" {
		return m, errors.New("message has no type")
	}
	return m, nil
}

// Bool is a helper for optional booleans.
func Bool(v bool) *bool { return &v }

// Int64 is a helper for optional integers.
func Int64(v int64) *int64 { return &v }

// U32 is a helper for optional chunk indices.
func U32(v uint32) *uint32 { return &v }

// U16 is a helper for optional m-line indices.
func U16(v uint16) *uint16 { return &v }
