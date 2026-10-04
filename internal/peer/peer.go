// Package peer implements the Latchway session protocol (PROTOCOL.md §4) on
// top of pion/webrtc: a Host that serves a file to joiners, and a Receive
// function that fetches it. The Android app mirrors this logic on libwebrtc.
package peer

import (
	"context"
	"errors"
	"fmt"
	"mime"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/pion/ice/v4"
	"github.com/pion/webrtc/v4"

	"github.com/gabeazar/latchway/internal/rendezvous"
	"github.com/gabeazar/latchway/internal/wire"
)

// FileMeta describes the offered file. Size is -1 when unknown.
type FileMeta struct {
	Name string
	Size int64
	Mime string
	From string
}

const (
	// Flow control thresholds (PROTOCOL.md §4.4).
	highWater = 1 << 20
	lowWater  = 256 << 10

	// The largest SCTP message we advertise and accept. libwebrtc's default
	// is 256 KiB; matching it lets 64 KiB chunk frames flow in both
	// directions with any peer.
	sctpMaxMessage = 256 * 1024

	dataChannelLabel = "latchway"

	handshakeTimeout = 90 * time.Second
	authTimeout      = 60 * time.Second
	offerTimeout     = 10 * time.Minute // the receiver may take a while to choose a destination
	closeGrace       = 5 * time.Second  // how long the joiner waits for the host to close after `done`

	progressEvery = 1 << 20

	// Online guessing throttle (PROTOCOL.md §4.1).
	throttleAfter  = 5
	throttleWindow = 10 * time.Minute
	throttleDelay  = 10 * time.Second

	// Bound on queued signaling messages per session before we give up on
	// a peer that floods us.
	sessionQueue = 64
)

// SessionError carries a protocol error code from the other side or the
// rendezvous, so UIs can map it to a message.
type SessionError struct{ Code string }

func (e *SessionError) Error() string { return "session: " + e.Code }

// ErrInvalidLink is returned by Receive when the host's first encrypted
// message does not decrypt: the link (or password) is not the one the
// sender is serving.
var ErrInvalidLink = errors.New("this link is not valid")

// IsCode reports whether err is a SessionError (or rendezvous error) with
// the given code.
func IsCode(err error, code string) bool {
	var se *SessionError
	if errors.As(err, &se) {
		return se.Code == code
	}
	var re *rendezvous.ServerError
	return errors.As(err, &re) && re.Code == code
}

// codeOf converts rendezvous errors into SessionErrors so callers only have
// to know one type.
func codeOf(err error) error {
	var re *rendezvous.ServerError
	if errors.As(err, &re) {
		return &SessionError{Code: re.Code}
	}
	return err
}

func newAPI() *webrtc.API {
	se := webrtc.SettingEngine{}
	se.SetSCTPMaxMessageSize(sctpMaxMessage)
	// mDNS candidates add latency and nothing else for a two-party transfer.
	se.SetICEMulticastDNSMode(ice.MulticastDNSModeDisabled)
	// Loopback candidates are excluded by default, as in libwebrtc. Tests
	// that run both peers on one machine without a network enable them.
	if os.Getenv("LATCHWAY_ICE_LOOPBACK") == "1" {
		se.SetIncludeLoopbackCandidate(true)
	}
	return webrtc.NewAPI(webrtc.WithSettingEngine(se))
}

func peerConnection(api *webrtc.API, servers []wire.ICEServer, relayOnly bool) (*webrtc.PeerConnection, *webrtc.DataChannel, error) {
	cfg := webrtc.Configuration{}
	for _, s := range servers {
		cfg.ICEServers = append(cfg.ICEServers, webrtc.ICEServer{
			URLs:       s.URLs,
			Username:   s.Username,
			Credential: s.Credential,
		})
	}
	if relayOnly {
		cfg.ICETransportPolicy = webrtc.ICETransportPolicyRelay
	}
	pc, err := api.NewPeerConnection(cfg)
	if err != nil {
		return nil, nil, fmt.Errorf("create peer connection: %w", err)
	}
	negotiated := true
	var id uint16 = 0
	ordered := true
	dc, err := pc.CreateDataChannel(dataChannelLabel, &webrtc.DataChannelInit{
		Negotiated: &negotiated,
		ID:         &id,
		Ordered:    &ordered,
	})
	if err != nil {
		pc.Close()
		return nil, nil, fmt.Errorf("create data channel: %w", err)
	}
	return pc, dc, nil
}

// candidateMsg converts a local ICE candidate into the signaling message.
func candidateMsg(c *webrtc.ICECandidate) wire.Msg {
	j := c.ToJSON()
	m := wire.Msg{T: wire.TICE, Cand: j.Candidate}
	if j.SDPMid != nil {
		m.Mid = *j.SDPMid
	}
	if j.SDPMLineIndex != nil {
		m.MLine = wire.U16(*j.SDPMLineIndex)
	}
	return m
}

func candidateInit(m wire.Msg) webrtc.ICECandidateInit {
	init := webrtc.ICECandidateInit{Candidate: m.Cand}
	if m.Mid != "" {
		mid := m.Mid
		init.SDPMid = &mid
	}
	if m.MLine != nil {
		idx := *m.MLine
		init.SDPMLineIndex = &idx
	}
	return init
}

// dcEvent is how data-channel and connection callbacks reach a session's
// single goroutine.
type dcEvent struct {
	kind  evKind
	data  []byte
	text  bool
	state webrtc.PeerConnectionState
	cand  *webrtc.ICECandidate // nil means gathering finished
}

type evKind int

const (
	evOpen evKind = iota + 1
	evMessage
	evState
	evCandidate
)

func wireEvents(pc *webrtc.PeerConnection, dc *webrtc.DataChannel, ch chan<- dcEvent, done <-chan struct{}) {
	push := func(e dcEvent) {
		select {
		case ch <- e:
		case <-done:
		}
	}
	pc.OnConnectionStateChange(func(s webrtc.PeerConnectionState) { push(dcEvent{kind: evState, state: s}) })
	pc.OnICECandidate(func(c *webrtc.ICECandidate) { push(dcEvent{kind: evCandidate, cand: c}) })
	dc.OnOpen(func() { push(dcEvent{kind: evOpen}) })
	dc.OnMessage(func(m webrtc.DataChannelMessage) {
		data := make([]byte, len(m.Data))
		copy(data, m.Data)
		push(dcEvent{kind: evMessage, data: data, text: m.IsString})
	})
}

// EventKind classifies progress reports from a Host or Receive.
type EventKind int

// Event kinds, in roughly the order a transfer produces them.
const (
	EvRegistered   EventKind = iota + 1 // host: registered at the rendezvous (Err set when a retry is pending)
	EvJoined                            // a joiner connected; SID set
	EvAuthFailed                        // joiner sent a bad proof; SID set
	EvApprovalWait                      // waiting on the human
	EvDenied                            // the host refused; Code set
	EvConnecting                        // WebRTC handshake in progress
	EvConnected                         // data channel open
	EvProgress                          // Bytes set (and Total when known)
	EvDone                              // transfer complete; Bytes set
	EvFailed                            // transfer failed; Err set
	EvLeft                              // the joiner went away before finishing
	EvStopped                           // the host finished serving
)

// Event is a progress report delivered to OnEvent callbacks. Callbacks run
// on session goroutines and must return quickly.
type Event struct {
	Kind  EventKind
	SID   string
	Bytes int64
	Total int64
	Code  string
	Err   error
}

func (k EventKind) String() string {
	switch k {
	case EvRegistered:
		return "registered"
	case EvJoined:
		return "joined"
	case EvAuthFailed:
		return "auth_failed"
	case EvApprovalWait:
		return "approval_wait"
	case EvDenied:
		return "denied"
	case EvConnecting:
		return "connecting"
	case EvConnected:
		return "connected"
	case EvProgress:
		return "progress"
	case EvDone:
		return "done"
	case EvFailed:
		return "failed"
	case EvLeft:
		return "left"
	case EvStopped:
		return "stopped"
	}
	return fmt.Sprintf("event(%d)", int(k))
}

// inbound is one item from the rendezvous connection: a message, or the
// error that ended it. Both travel on one channel so that a message the
// server sent just before closing (say, an encrypted `denied` followed by
// `error closed`) is always seen before the close.
type inbound struct {
	msg wire.Msg
	err error
}

// readLoop pumps rendezvous messages into out until the connection fails;
// the error (rendezvous error code, or transport failure) is the last item.
func readLoop(ctx context.Context, conn *rendezvous.Conn, out chan<- inbound) {
	for {
		m, err := conn.Recv(ctx)
		if err != nil {
			select {
			case out <- inbound{err: codeOf(err)}:
			case <-ctx.Done():
			}
			return
		}
		select {
		case out <- inbound{msg: m}:
		case <-ctx.Done():
			return
		}
	}
}

// decodePayload parses the `d` field of a sig message.
func decodePayload(d string) (wire.Msg, error) {
	if len(d) > wire.MaxSignalPayload {
		return wire.Msg{}, errors.New("payload too large")
	}
	return wire.Decode([]byte(d))
}

// waitBufferedLow blocks until the channel's buffered amount has drained
// below lowWater, polling as a safety net in case the callback is missed.
func waitBufferedLow(ctx context.Context, dc *webrtc.DataChannel, low <-chan struct{}) error {
	t := time.NewTimer(100 * time.Millisecond)
	defer t.Stop()
	for dc.BufferedAmount() > lowWater {
		select {
		case <-low:
		case <-t.C:
			t.Reset(100 * time.Millisecond)
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	return nil
}

// mimeOf guesses a content type from a file name, falling back to the
// generic binary type. Only the extension is consulted, never the bytes.
func mimeOf(name string) string {
	if t := mime.TypeByExtension(filepath.Ext(name)); t != "" {
		if i := strings.IndexByte(t, ';'); i >= 0 {
			t = strings.TrimSpace(t[:i])
		}
		return t
	}
	return "application/octet-stream"
}
