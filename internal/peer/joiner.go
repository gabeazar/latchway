package peer

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/url"
	"time"

	"github.com/pion/webrtc/v4"

	"github.com/gabeazar/latchway/internal/rendezvous"
	"github.com/gabeazar/latchway/internal/wire"
)

// ReceiveOptions configure one Receive call.
type ReceiveOptions struct {
	// Share and Host come from wire.ParseLink. Rendezvous, when set,
	// overrides the address derived from Host (for local testing over
	// plain http).
	Share      wire.Share
	Host       string
	Rendezvous *url.URL
	// Password is used when the sender set one. AskPassword is called
	// instead when Password is empty and the hello says one is needed.
	Password    string
	AskPassword func(ctx context.Context) (string, error)
	// Accept is called with the file's description once it is known and
	// returns where the bytes go. approval says the sender will be asked
	// before anything is sent. Returning an error declines the file.
	Accept func(ctx context.Context, meta FileMeta, approval bool) (io.WriteCloser, error)
	// RelayOnly forces every candidate through TURN.
	RelayOnly bool
	// OnEvent receives progress reports. May be nil.
	OnEvent func(Event)
}

// Result summarises a completed transfer.
type Result struct {
	Meta  FileMeta
	Bytes int64
}

// Receive joins a share and downloads its file, exactly as PROTOCOL.md §4
// describes from the receiver's side. Errors from the other side or the
// rendezvous are *SessionError values; an undecryptable first message is
// ErrInvalidLink.
func Receive(ctx context.Context, opts ReceiveOptions) (Result, error) {
	var res Result
	if opts.Accept == nil {
		return res, errors.New("Accept is required")
	}
	base := opts.Rendezvous
	if base == nil {
		var err error
		base, err = rendezvous.BaseURL("https://" + opts.Host)
		if err != nil {
			return res, err
		}
	}
	event := func(e Event) {
		if opts.OnEvent != nil {
			opts.OnEvent(e)
		}
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	conn, joined, err := rendezvous.DialJoiner(ctx, base, opts.Share.IDString())
	if err != nil {
		return res, codeOf(err)
	}
	defer conn.Close()
	sid := joined.SID
	event(Event{Kind: EvJoined, SID: sid})

	msgs := make(chan inbound, sessionQueue)
	go readLoop(ctx, conn, msgs)

	sig := func(m wire.Msg) error { return conn.Send(ctx, wire.Msg{T: wire.TSig, SID: sid, D: string(m.Encode())}) }
	// next returns the next decoded session message; rendezvous failures
	// surface as errors, after every message that preceded them.
	next := func(timeout time.Duration) (wire.Msg, error) {
		t := time.NewTimer(timeout)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return wire.Msg{}, ctx.Err()
			case <-t.C:
				return wire.Msg{}, &SessionError{Code: wire.ErrTimeout}
			case it := <-msgs:
				if it.err != nil {
					return wire.Msg{}, it.err
				}
				m := it.msg
				if m.T != wire.TSig {
					continue
				}
				pm, err := decodePayload(m.D)
				if err != nil {
					return wire.Msg{}, &SessionError{Code: wire.ErrProtocol}
				}
				return pm, nil
			}
		}
	}

	// 1. hello.
	hello, err := next(handshakeTimeout)
	if err != nil {
		return res, err
	}
	if hello.T == wire.TError {
		return res, &SessionError{Code: hello.Code}
	}
	if hello.T != wire.THello || hello.V != wire.ProtocolVersion || hello.PW == nil {
		return res, &SessionError{Code: wire.ErrProtocol}
	}
	hostNonce, err := wire.B64.DecodeString(hello.N)
	if err != nil || len(hostNonce) != wire.NonceLen {
		return res, &SessionError{Code: wire.ErrProtocol}
	}
	pw := *hello.PW
	password := opts.Password
	if pw && password == "" {
		if opts.AskPassword == nil {
			return res, &SessionError{Code: wire.ErrBadAuth}
		}
		password, err = opts.AskPassword(ctx)
		if err != nil {
			return res, err
		}
	}
	if !pw {
		password = ""
	}
	keys := wire.DeriveKeys(opts.Share.ID[:], opts.Share.Secret[:], password)

	// 2. proof.
	joinerNonce, err := wire.NewNonce()
	if err != nil {
		return res, err
	}
	proof := wire.Proof(keys.Auth[:], wire.ProtocolVersion, pw, hostNonce, joinerNonce)
	if err := sig(wire.Msg{T: wire.TAuth, N: wire.B64.EncodeToString(joinerNonce), P: wire.B64.EncodeToString(proof)}); err != nil {
		return res, err
	}

	// 3. session keys. Before the first encrypted message the only
	// meaningful plaintext from the host is `error`; afterwards plaintext is
	// ignored entirely.
	sk := wire.DeriveSession(keys.Root[:], hostNonce, joinerNonce)
	sealer := wire.NewSealer(sk.Sig[:], wire.JoinerToHost)
	opener := wire.NewOpener(sk.Sig[:], wire.HostToJoiner)
	enc := func(m wire.Msg) error { return sig(wire.Msg{T: wire.TEnc, C: sealer.Seal(m.Encode())}) }
	encrypted := false
	nextEnc := func(timeout time.Duration) (wire.Msg, error) {
		deadline := time.Now().Add(timeout)
		for {
			m, err := next(time.Until(deadline))
			if err != nil {
				return m, err
			}
			if m.T == wire.TError && !encrypted {
				return m, &SessionError{Code: m.Code}
			}
			if m.T != wire.TEnc {
				continue
			}
			pt, err := opener.Open(m.C)
			if err != nil {
				if !encrypted {
					return m, ErrInvalidLink
				}
				return m, &SessionError{Code: wire.ErrProtocol}
			}
			encrypted = true
			pm, err := wire.Decode(pt)
			if err != nil {
				return m, &SessionError{Code: wire.ErrProtocol}
			}
			return pm, nil
		}
	}

	// 4. meta.
	meta, err := nextEnc(handshakeTimeout)
	if err != nil {
		return res, err
	}
	if meta.T == wire.TError {
		return res, &SessionError{Code: meta.Code}
	}
	if meta.T != wire.TMeta || meta.Chunk != wire.ChunkSize || meta.Size == nil || meta.Name == "" {
		return res, &SessionError{Code: wire.ErrProtocol}
	}
	res.Meta = FileMeta{Name: meta.Name, Size: *meta.Size, Mime: meta.Mime, From: meta.From}
	approval := meta.Approval != nil && *meta.Approval
	w, err := opts.Accept(ctx, res.Meta, approval)
	if err != nil {
		_ = enc(wire.Msg{T: wire.TError, Code: wire.ErrClosed})
		return res, err
	}
	closed := false
	closeW := func() error {
		if closed {
			return nil
		}
		closed = true
		return w.Close()
	}
	defer closeW()
	if approval {
		event(Event{Kind: EvApprovalWait, SID: sid})
	}

	// 5. go.
	goMsg, err := nextEnc(offerTimeout)
	if err != nil {
		return res, err
	}
	if goMsg.T == wire.TError {
		event(Event{Kind: EvDenied, SID: sid, Code: goMsg.Code})
		return res, &SessionError{Code: goMsg.Code}
	}
	if goMsg.T != wire.TGo {
		return res, &SessionError{Code: wire.ErrProtocol}
	}
	event(Event{Kind: EvConnecting, SID: sid})

	// 6. offer; the joiner is always the offerer. The ICE list from `go`
	// replaces the STUN-only one from `joined`.
	pc, dc, err := peerConnection(newAPI(), goMsg.ICE, opts.RelayOnly)
	if err != nil {
		return res, err
	}
	defer pc.Close()
	events := make(chan dcEvent, 256)
	done := make(chan struct{})
	defer close(done)
	wireEvents(pc, dc, events, done)

	offer, err := pc.CreateOffer(nil)
	if err != nil {
		return res, err
	}
	if err := pc.SetLocalDescription(offer); err != nil {
		return res, err
	}
	if err := enc(wire.Msg{T: wire.TOffer, SDP: pc.LocalDescription().SDP, Start: wire.U32(0)}); err != nil {
		return res, err
	}

	// 7. answer, ICE, then chunks.
	chunks := wire.NewChunkOpener(sk.File[:])
	open := false
	finishing := false
	var received, lastReport int64
	hs := time.NewTimer(handshakeTimeout)
	defer hs.Stop()
	var grace <-chan time.Time
	finish := func() (Result, error) {
		res.Bytes = received
		event(Event{Kind: EvDone, SID: sid, Bytes: received, Total: res.Meta.Size})
		return res, nil
	}
	for {
		select {
		case <-ctx.Done():
			return res, ctx.Err()
		case <-hs.C:
			if !open {
				return res, &SessionError{Code: wire.ErrTimeout}
			}
		case <-grace:
			return finish()
		case it := <-msgs:
			if it.err != nil {
				// The rendezvous is irrelevant once the channel is open.
				if !open {
					return res, it.err
				}
				msgs = nil
				continue
			}
			m := it.msg
			if m.T != wire.TSig {
				continue
			}
			pm, err := decodePayload(m.D)
			if err != nil || pm.T != wire.TEnc {
				continue
			}
			pt, err := opener.Open(pm.C)
			if err != nil {
				if open {
					continue
				}
				return res, &SessionError{Code: wire.ErrProtocol}
			}
			sm, err := wire.Decode(pt)
			if err != nil {
				continue
			}
			switch sm.T {
			case wire.TAnswer:
				if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: sm.SDP}); err != nil {
					return res, &SessionError{Code: wire.ErrProtocol}
				}
			case wire.TICE:
				_ = pc.AddICECandidate(candidateInit(sm))
			case wire.TICEDone:
			case wire.TError:
				if !open {
					return res, &SessionError{Code: sm.Code}
				}
			}
		case e := <-events:
			switch e.kind {
			case evCandidate:
				if e.cand == nil {
					_ = enc(wire.Msg{T: wire.TICEDone})
				} else {
					_ = enc(candidateMsg(e.cand))
				}
			case evState:
				switch e.state {
				case webrtc.PeerConnectionStateFailed, webrtc.PeerConnectionStateClosed, webrtc.PeerConnectionStateDisconnected:
					if finishing {
						return finish()
					}
					return res, &SessionError{Code: wire.ErrProtocol}
				}
			case evOpen:
				open = true
				event(Event{Kind: EvConnected, SID: sid})
			case evMessage:
				if finishing {
					continue
				}
				if e.text {
					cm, err := wire.Decode(e.data)
					if err == nil && cm.T == wire.TAbort {
						return res, fmt.Errorf("sender aborted: %s", cm.Reason)
					}
					continue
				}
				pt, last, err := chunks.Open(e.data)
				if err != nil {
					_ = dc.SendText(string(wire.Msg{T: wire.TAbort, Reason: "bad chunk"}.Encode()))
					return res, fmt.Errorf("chunk %d: %w", chunks.Next(), err)
				}
				if res.Meta.Size >= 0 && received+int64(len(pt)) > res.Meta.Size {
					_ = dc.SendText(string(wire.Msg{T: wire.TAbort, Reason: "too much data"}.Encode()))
					return res, errors.New("received more than the announced size")
				}
				if len(pt) > 0 {
					if _, err := w.Write(pt); err != nil {
						_ = dc.SendText(string(wire.Msg{T: wire.TAbort, Reason: "write error"}.Encode()))
						return res, err
					}
				}
				received += int64(len(pt))
				if received-lastReport >= progressEvery || last {
					lastReport = received
					_ = dc.SendText(string(wire.Msg{T: wire.TProgress, Bytes: wire.Int64(received)}.Encode()))
					event(Event{Kind: EvProgress, SID: sid, Bytes: received, Total: res.Meta.Size})
				}
				if last {
					if res.Meta.Size >= 0 && received != res.Meta.Size {
						_ = dc.SendText(string(wire.Msg{T: wire.TAbort, Reason: "size mismatch"}.Encode()))
						return res, fmt.Errorf("received %d bytes, expected %d", received, res.Meta.Size)
					}
					if err := closeW(); err != nil {
						_ = dc.SendText(string(wire.Msg{T: wire.TAbort, Reason: "write error"}.Encode()))
						return res, err
					}
					if err := dc.SendText(string(wire.Msg{T: wire.TDone}.Encode())); err != nil {
						return res, err
					}
					// The host closes the connection on `done`; give it a
					// moment so the message is not lost to our own close.
					finishing = true
					grace = time.After(closeGrace)
				}
			}
		}
	}
}
