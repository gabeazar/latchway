package peer

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/url"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/webrtc/v4"

	"github.com/gabeazar/latchway/internal/rendezvous"
	"github.com/gabeazar/latchway/internal/wire"
)

// HostOptions configure a Host.
type HostOptions struct {
	// Rendezvous is the server to register at (see rendezvous.BaseURL).
	Rendezvous *url.URL
	// Share is the identity behind the link. RegKey is the registration key
	// kept on this device for the share's lifetime (PROTOCOL.md §3.1).
	Share  wire.Share
	RegKey []byte
	// Token is the optional Bearer token a private rendezvous requires.
	Token string
	// Password, when non-empty, is folded into the keys and required of
	// every receiver.
	Password string
	// Meta describes the file; Open yields a fresh reader for each transfer.
	Meta FileMeta
	Open func() (io.ReadCloser, error)
	// Approve, when set, is asked before each transfer; the receiver is told
	// to wait meanwhile. Returning false sends `denied`.
	Approve func(ctx context.Context, sid string) bool
	// MaxDownloads stops the host after that many completed transfers
	// (0: unlimited). MaxConcurrent bounds simultaneous transfers (0:
	// unlimited); extra receivers get `busy`.
	MaxDownloads  int
	MaxConcurrent int
	// Expires, when set, ends the share at that time.
	Expires time.Time
	// RelayOnly forces every candidate through TURN so the receiver never
	// learns this device's address.
	RelayOnly bool
	// OnEvent receives progress reports. May be nil.
	OnEvent func(Event)
}

// Host serves one share: it keeps a registration at the rendezvous and runs
// a session for every joiner, exactly as PROTOCOL.md §4 describes from the
// sender's side.
type Host struct {
	opts HostOptions
	keys wire.Keys
	api  *webrtc.API

	mu        sync.Mutex
	sessions  map[string]*hostSession
	completed int
	active    int
	failures  []time.Time
	stopped   bool
	stop      chan struct{}
	wg        sync.WaitGroup
}

// NewHost validates the options and derives the long-term keys, which can
// take a moment when a password is set (PBKDF2).
func NewHost(opts HostOptions) (*Host, error) {
	if opts.Rendezvous == nil {
		return nil, errors.New("rendezvous address required")
	}
	if len(opts.RegKey) != wire.RegKeyLen {
		return nil, errors.New("registration key must be 32 bytes")
	}
	if opts.Open == nil {
		return nil, errors.New("Open is required")
	}
	if opts.Meta.Name == "" {
		return nil, errors.New("file name required")
	}
	if opts.Meta.Mime == "" {
		opts.Meta.Mime = mimeOf(opts.Meta.Name)
	}
	h := &Host{
		opts:     opts,
		keys:     wire.DeriveKeys(opts.Share.ID[:], opts.Share.Secret[:], opts.Password),
		api:      newAPI(),
		sessions: map[string]*hostSession{},
		stop:     make(chan struct{}),
	}
	return h, nil
}

// Link returns the share's https link for the configured rendezvous host.
func (h *Host) Link() string { return h.opts.Share.Link(h.opts.Rendezvous.Host) }

// Completed reports how many transfers have finished with `done`.
func (h *Host) Completed() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.completed
}

func (h *Host) event(e Event) {
	if h.opts.OnEvent != nil {
		h.opts.OnEvent(e)
	}
}

// Run registers the share and serves it until ctx is cancelled, the share
// expires, MaxDownloads is reached, or the rendezvous refuses the
// registration. A dropped rendezvous connection is re-established with
// backoff; transfers already running over WebRTC are unaffected.
func (h *Host) Run(ctx context.Context) error {
	defer h.event(Event{Kind: EvStopped})
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	if !h.opts.Expires.IsZero() {
		var expCancel context.CancelFunc
		ctx, expCancel = context.WithDeadline(ctx, h.opts.Expires)
		defer expCancel()
	}

	backoff := time.Second
	var result error
	for {
		conn, err := rendezvous.DialHost(ctx, h.opts.Rendezvous, h.opts.Share.IDString(), h.opts.RegKey, h.opts.Token)
		if err != nil {
			err = codeOf(err)
			if ctx.Err() != nil || IsCode(err, wire.ErrForbidden) || IsCode(err, wire.ErrBusy) {
				result = err
				break
			}
			h.event(Event{Kind: EvRegistered, Err: err})
			if !sleep(ctx, backoff) {
				break
			}
			backoff = min(backoff*2, 30*time.Second)
			continue
		}
		backoff = time.Second
		h.event(Event{Kind: EvRegistered})
		err = h.serve(ctx, conn)
		conn.Close()
		if ctx.Err() != nil || err == nil {
			break
		}
		if IsCode(err, wire.ErrReplaced) || IsCode(err, wire.ErrForbidden) {
			result = err
			break
		}
		// Transport failure: sessions still signaling cannot continue, since
		// their sids die with the connection.
		h.abortSignaling(err)
		h.event(Event{Kind: EvRegistered, Err: err})
		if !sleep(ctx, backoff) {
			break
		}
		backoff = min(backoff*2, 30*time.Second)
	}

	// Let transfers that already have a data channel finish, unless the
	// caller cancelled us.
	if ctx.Err() == nil {
		h.waitTransfers(ctx)
	}
	cancel()
	h.wg.Wait()
	if result == nil && errors.Is(ctx.Err(), context.DeadlineExceeded) && !h.opts.Expires.IsZero() {
		return &SessionError{Code: wire.ErrExpired}
	}
	return result
}

// serve runs one rendezvous connection until it fails or the host is done.
func (h *Host) serve(ctx context.Context, conn *rendezvous.Conn) error {
	msgs := make(chan inbound, 16)
	rctx, rcancel := context.WithCancel(ctx)
	defer rcancel()
	go readLoop(rctx, conn, msgs)

	send := func(m wire.Msg) error { return conn.Send(ctx, m) }

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-h.stop:
			return nil
		case it := <-msgs:
			if it.err != nil {
				return it.err
			}
			m := it.msg
			switch m.T {
			case wire.TJoin:
				h.startSession(ctx, m, send)
			case wire.TSig:
				h.mu.Lock()
				s := h.sessions[m.SID]
				h.mu.Unlock()
				if s == nil {
					_ = send(wire.Msg{T: wire.TLeave, SID: m.SID})
					continue
				}
				pm, err := decodePayload(m.D)
				if err != nil {
					s.deliver(wire.Msg{T: wire.TError, Code: wire.ErrProtocol})
					continue
				}
				s.deliver(pm)
			case wire.TLeave:
				h.mu.Lock()
				s := h.sessions[m.SID]
				h.mu.Unlock()
				if s != nil {
					s.deliver(wire.Msg{T: wire.TLeave})
				}
			}
		}
	}
}

func (h *Host) startSession(ctx context.Context, join wire.Msg, send func(wire.Msg) error) {
	if join.SID == "" {
		return
	}
	s := &hostSession{
		h:    h,
		sid:  join.SID,
		ice:  join.ICE,
		in:   make(chan wire.Msg, sessionQueue),
		send: send,
	}
	s.ctx, s.cancel = context.WithCancel(ctx)
	h.mu.Lock()
	h.sessions[s.sid] = s
	h.mu.Unlock()
	h.event(Event{Kind: EvJoined, SID: s.sid})
	h.wg.Add(1)
	go func() {
		defer h.wg.Done()
		defer s.cancel()
		s.run()
		h.mu.Lock()
		if h.sessions[s.sid] == s {
			delete(h.sessions, s.sid)
		}
		h.mu.Unlock()
	}()
}

// abortSignaling cancels sessions that still depend on the rendezvous.
func (h *Host) abortSignaling(err error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, s := range h.sessions {
		if !s.transferring.Load() {
			s.cancel()
		}
	}
}

// waitTransfers blocks until no transfer is active.
func (h *Host) waitTransfers(ctx context.Context) {
	t := time.NewTicker(100 * time.Millisecond)
	defer t.Stop()
	for {
		h.mu.Lock()
		n := h.active
		h.mu.Unlock()
		if n == 0 {
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// throttle implements the online guessing limit: once throttleAfter proofs
// have failed within throttleWindow, every further proof is answered no
// sooner than throttleDelay after it arrived.
func (h *Host) throttle(ctx context.Context, received time.Time) {
	h.mu.Lock()
	h.pruneFailures(received)
	throttled := len(h.failures) >= throttleAfter
	h.mu.Unlock()
	if throttled {
		sleep(ctx, throttleDelay-time.Since(received))
	}
}

func (h *Host) recordFailure(now time.Time) {
	h.mu.Lock()
	h.pruneFailures(now)
	h.failures = append(h.failures, now)
	h.mu.Unlock()
}

func (h *Host) pruneFailures(now time.Time) {
	keep := h.failures[:0]
	for _, t := range h.failures {
		if now.Sub(t) < throttleWindow {
			keep = append(keep, t)
		}
	}
	h.failures = keep
}

// admit decides whether a proven joiner may transfer now. It returns an
// error code (expired, busy) or "" after reserving an active slot.
func (h *Host) admit() string {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.stopped {
		return wire.ErrExpired
	}
	if h.opts.MaxDownloads > 0 && h.completed+h.active >= h.opts.MaxDownloads {
		if h.active > 0 {
			// Someone else is mid-way through the last allowed download.
			return wire.ErrBusy
		}
		return wire.ErrExpired
	}
	if h.opts.MaxConcurrent > 0 && h.active >= h.opts.MaxConcurrent {
		return wire.ErrBusy
	}
	h.active++
	return ""
}

// release gives an active slot back; completed says whether the transfer
// finished with `done`.
func (h *Host) release(completed bool) {
	h.mu.Lock()
	h.active--
	if completed {
		h.completed++
		if h.opts.MaxDownloads > 0 && h.completed >= h.opts.MaxDownloads && !h.stopped {
			h.stopped = true
			close(h.stop)
		}
	}
	h.mu.Unlock()
}

// --- one session -------------------------------------------------------------

type hostSession struct {
	h      *Host
	sid    string
	ice    []wire.ICEServer
	in     chan wire.Msg
	send   func(wire.Msg) error
	ctx    context.Context
	cancel context.CancelFunc

	transferring atomic.Bool
}

var errJoinerGone = errors.New("joiner left")

// deliver queues a decoded session message; a flooding peer is dropped.
func (s *hostSession) deliver(m wire.Msg) {
	select {
	case s.in <- m:
	default:
		s.cancel()
	}
}

func (s *hostSession) sig(m wire.Msg) error {
	return s.send(wire.Msg{T: wire.TSig, SID: s.sid, D: string(m.Encode())})
}

func (s *hostSession) sigRaw(d []byte) error {
	return s.send(wire.Msg{T: wire.TSig, SID: s.sid, D: string(d)})
}

func (s *hostSession) leave() { _ = s.send(wire.Msg{T: wire.TLeave, SID: s.sid}) }

// next waits for the next session message.
func (s *hostSession) next(timeout time.Duration) (wire.Msg, error) {
	t := time.NewTimer(timeout)
	defer t.Stop()
	select {
	case m := <-s.in:
		if m.T == wire.TLeave {
			return m, errJoinerGone
		}
		return m, nil
	case <-t.C:
		return wire.Msg{}, &SessionError{Code: wire.ErrTimeout}
	case <-s.ctx.Done():
		return wire.Msg{}, s.ctx.Err()
	}
}

func (s *hostSession) run() {
	err := s.handshakeAndTransfer()
	switch {
	case err == nil:
	case errors.Is(err, errJoinerGone):
		s.h.event(Event{Kind: EvLeft, SID: s.sid})
	case s.ctx.Err() != nil && errors.Is(err, s.ctx.Err()):
		// Host shutting down; nothing to report per session.
	default:
		s.h.event(Event{Kind: EvFailed, SID: s.sid, Err: err})
	}
}

func (s *hostSession) handshakeAndTransfer() error {
	h := s.h
	pw := h.opts.Password != ""

	// 1. hello (plaintext).
	hostNonce, err := wire.NewNonce()
	if err != nil {
		return err
	}
	if err := s.sig(wire.Msg{T: wire.THello, V: wire.ProtocolVersion, PW: wire.Bool(pw), N: wire.B64.EncodeToString(hostNonce)}); err != nil {
		return err
	}

	// 2. proof (plaintext).
	m, err := s.next(authTimeout)
	if err != nil {
		return err
	}
	received := time.Now()
	if m.T != wire.TAuth {
		_ = s.sig(wire.Msg{T: wire.TError, Code: wire.ErrProtocol})
		s.leave()
		return &SessionError{Code: wire.ErrProtocol}
	}
	joinerNonce, err := wire.B64.DecodeString(m.N)
	proof, err2 := wire.B64.DecodeString(m.P)
	if err != nil || err2 != nil || len(joinerNonce) != wire.NonceLen {
		_ = s.sig(wire.Msg{T: wire.TError, Code: wire.ErrProtocol})
		s.leave()
		return &SessionError{Code: wire.ErrProtocol}
	}
	h.throttle(s.ctx, received)
	if !wire.VerifyProof(h.keys.Auth[:], wire.ProtocolVersion, pw, hostNonce, joinerNonce, proof) {
		h.recordFailure(time.Now())
		h.event(Event{Kind: EvAuthFailed, SID: s.sid})
		_ = s.sig(wire.Msg{T: wire.TError, Code: wire.ErrBadAuth})
		s.leave()
		return &SessionError{Code: wire.ErrBadAuth}
	}

	// 3. session keys and envelope.
	sk := wire.DeriveSession(h.keys.Root[:], hostNonce, joinerNonce)
	sealer := wire.NewSealer(sk.Sig[:], wire.HostToJoiner)
	opener := wire.NewOpener(sk.Sig[:], wire.JoinerToHost)
	enc := func(m wire.Msg) error { return s.sig(wire.Msg{T: wire.TEnc, C: sealer.Seal(m.Encode())}) }
	encErr := func(code string) error {
		_ = enc(wire.Msg{T: wire.TError, Code: code})
		s.leave()
		h.event(Event{Kind: EvDenied, SID: s.sid, Code: code})
		return &SessionError{Code: code}
	}
	// nextEnc waits for the next encrypted message, ignoring plaintext.
	nextEnc := func(timeout time.Duration) (wire.Msg, error) {
		deadline := time.Now().Add(timeout)
		for {
			m, err := s.next(time.Until(deadline))
			if err != nil {
				return m, err
			}
			if m.T != wire.TEnc {
				continue
			}
			pt, err := opener.Open(m.C)
			if err != nil {
				return m, &SessionError{Code: wire.ErrProtocol}
			}
			pm, err := wire.Decode(pt)
			if err != nil {
				return m, &SessionError{Code: wire.ErrProtocol}
			}
			return pm, nil
		}
	}

	// 4. meta, padded to a fixed size.
	approval := h.opts.Approve != nil
	padded, err := wire.PadMeta(wire.Msg{
		T: wire.TMeta, Name: h.opts.Meta.Name, Size: wire.Int64(h.opts.Meta.Size), Mime: h.opts.Meta.Mime,
		Chunk: wire.ChunkSize, From: h.opts.Meta.From, Approval: wire.Bool(approval),
	})
	if err != nil {
		return err
	}
	if err := s.sigRaw(wire.Msg{T: wire.TEnc, C: sealer.Seal(padded)}.Encode()); err != nil {
		return err
	}

	// 5. approval and admission.
	if approval {
		h.event(Event{Kind: EvApprovalWait, SID: s.sid})
		if !h.opts.Approve(s.ctx, s.sid) {
			return encErr(wire.ErrDenied)
		}
	}
	if code := h.admit(); code != "" {
		return encErr(code)
	}
	completed := false
	defer func() { h.release(completed) }()

	// 6. go, carrying the host's full ICE list (TURN included).
	if err := enc(wire.Msg{T: wire.TGo, ICE: s.ice}); err != nil {
		return err
	}

	// 7. offer / answer.
	var offer wire.Msg
	for {
		m, err := nextEnc(offerTimeout)
		if err != nil {
			return err
		}
		if m.T == wire.TError {
			return &SessionError{Code: m.Code}
		}
		if m.T == wire.TOffer {
			offer = m
			break
		}
		return &SessionError{Code: wire.ErrProtocol}
	}
	h.event(Event{Kind: EvConnecting, SID: s.sid})

	pc, dc, err := peerConnection(h.api, s.ice, h.opts.RelayOnly)
	if err != nil {
		return err
	}
	defer pc.Close()
	events := make(chan dcEvent, 256)
	done := make(chan struct{})
	defer close(done)
	wireEvents(pc, dc, events, done)
	low := make(chan struct{}, 1)
	dc.SetBufferedAmountLowThreshold(lowWater)
	dc.OnBufferedAmountLow(func() {
		select {
		case low <- struct{}{}:
		default:
		}
	})

	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: offer.SDP}); err != nil {
		return encErr(wire.ErrProtocol)
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		return err
	}
	if err := pc.SetLocalDescription(answer); err != nil {
		return err
	}
	if err := enc(wire.Msg{T: wire.TAnswer, SDP: pc.LocalDescription().SDP}); err != nil {
		return err
	}

	// 8. trickle ICE until the channel opens, then stream.
	streamErr := make(chan error, 1)
	var streaming bool
	var sent atomic.Int64
	hs := time.NewTimer(handshakeTimeout)
	defer hs.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return s.ctx.Err()
		case <-hs.C:
			if !streaming {
				return &SessionError{Code: wire.ErrTimeout}
			}
		case m := <-s.in:
			switch m.T {
			case wire.TLeave:
				// The rendezvous socket no longer matters once the channel
				// is open; before that, its loss ends the session.
				if !streaming {
					return errJoinerGone
				}
			case wire.TError:
				if !streaming {
					return &SessionError{Code: m.Code}
				}
			case wire.TEnc:
				pt, err := opener.Open(m.C)
				if err != nil {
					if streaming {
						continue
					}
					return &SessionError{Code: wire.ErrProtocol}
				}
				pm, err := wire.Decode(pt)
				if err != nil {
					continue
				}
				switch pm.T {
				case wire.TICE:
					_ = pc.AddICECandidate(candidateInit(pm))
				case wire.TICEDone:
				case wire.TError:
					if !streaming {
						return &SessionError{Code: pm.Code}
					}
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
				case webrtc.PeerConnectionStateFailed, webrtc.PeerConnectionStateClosed:
					return &SessionError{Code: wire.ErrProtocol}
				}
			case evOpen:
				if streaming {
					continue
				}
				streaming = true
				s.transferring.Store(true)
				h.event(Event{Kind: EvConnected, SID: s.sid})
				r, err := h.opts.Open()
				if err != nil {
					_ = dc.SendText(string(wire.Msg{T: wire.TAbort, Reason: "cannot open file"}.Encode()))
					return err
				}
				go func() {
					defer r.Close()
					streamErr <- s.stream(dc, wire.NewChunkSealer(sk.File[:]), r, low, &sent)
				}()
			case evMessage:
				if !e.text {
					continue
				}
				cm, err := wire.Decode(e.data)
				if err != nil {
					continue
				}
				switch cm.T {
				case wire.TProgress:
					if cm.Bytes != nil {
						h.event(Event{Kind: EvProgress, SID: s.sid, Bytes: *cm.Bytes, Total: h.opts.Meta.Size})
					}
				case wire.TDone:
					completed = true
					h.event(Event{Kind: EvDone, SID: s.sid, Bytes: sent.Load()})
					return nil
				case wire.TAbort:
					return fmt.Errorf("receiver aborted: %s", cm.Reason)
				}
			}
		case err := <-streamErr:
			if err != nil {
				_ = dc.SendText(string(wire.Msg{T: wire.TAbort, Reason: "read error"}.Encode()))
				return err
			}
			// All chunks sent; wait for `done`.
		}
	}
}

// stream seals the file into chunk frames with flow control.
func (s *hostSession) stream(dc *webrtc.DataChannel, sealer *wire.ChunkSealer, r io.Reader, low <-chan struct{}, sent *atomic.Int64) error {
	buf := make([]byte, wire.ChunkSize)
	size := s.h.opts.Meta.Size
	var total int64
	var index uint32
	for {
		n, err := io.ReadFull(r, buf)
		last := false
		switch {
		case err == nil:
		case errors.Is(err, io.ErrUnexpectedEOF), errors.Is(err, io.EOF):
			last = true
		default:
			return err
		}
		total += int64(n)
		if size >= 0 {
			if total > size {
				return errors.New("file grew while sending")
			}
			if total == size {
				last = true
			} else if last {
				return errors.New("file shrank while sending")
			}
		}
		frame, err := sealer.Seal(index, last, buf[:n])
		if err != nil {
			return err
		}
		if dc.BufferedAmount() > highWater {
			if err := waitBufferedLow(s.ctx, dc, low); err != nil {
				return err
			}
		}
		if err := dc.Send(frame); err != nil {
			return err
		}
		sent.Store(total)
		index++
		if last {
			return nil
		}
		if index == 0 {
			return errors.New("chunk index overflow")
		}
	}
}

func sleep(ctx context.Context, d time.Duration) bool {
	if d <= 0 {
		return ctx.Err() == nil
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return true
	case <-ctx.Done():
		return false
	}
}
