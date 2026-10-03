// Package peer implements the Latchway session protocol (PROTOCOL.md §4) on
// top of pion/webrtc: a Host that serves a file to joiners, and a Receive
// function that fetches it. The Android app mirrors this logic on libwebrtc.
package peer

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/pion/webrtc/v4"

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

	progressEvery = 1 << 20
)

// SessionError carries a protocol error code from the other side or the
// rendezvous, so UIs can map it to a message.
type SessionError struct{ Code string }

func (e *SessionError) Error() string { return "session: " + e.Code }

// IsCode reports whether err is a SessionError with the given code.
func IsCode(err error, code string) bool {
	var se *SessionError
	return errors.As(err, &se) && se.Code == code
}

func newAPI() *webrtc.API {
	se := webrtc.SettingEngine{}
	se.SetSCTPMaxMessageSize(sctpMaxMessage)
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

func contextErr(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	return nil
}
