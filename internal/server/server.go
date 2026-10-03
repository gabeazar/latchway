// Package server is the self-hostable rendezvous: the same protocol as the
// Cloudflare Worker in relay/, as a single Go binary. It relays encrypted
// handshakes, hands out ICE servers and serves the static pages. It stores
// nothing and logs no identifiers.
package server

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/json"
	"io"
	"io/fs"
	"log"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/gabeazar/latchway/internal/wire"
)

// Limits from PROTOCOL.md §3.4.
const (
	maxPayloadChars = 16 * 1024
	maxFrameBytes   = 20 * 1024
	maxSigsPerDir   = 256
	maxJoiners      = 16
	joinerIdle      = 120 * time.Second
	joinerMax       = 30 * time.Minute
	sweepEvery      = 30 * time.Second
)

var shareIDRe = regexp.MustCompile(`^[A-Za-z0-9_-]{22}$`)

// Options configure a Server.
type Options struct {
	// HostToken, when set, must be presented by hosts (Authorization: Bearer
	// or ?token=). Joiners never need it.
	HostToken string
	// ICE returns the ICE servers handed to both sides of a new session.
	ICE func(ctx context.Context) []wire.ICEServer
	// Assets holds the static site (web/). May be nil.
	Assets fs.FS
	// AndroidPackage and Fingerprints feed /.well-known/assetlinks.json.
	AndroidPackage string
	Fingerprints   []string
	// Logf receives operational log lines (counts, never identifiers).
	Logf func(format string, args ...any)
}

type role int

const (
	roleHost role = iota
	roleJoiner
)

type conn struct {
	ws      *websocket.Conn
	role    role
	sid     string
	at      time.Time
	last    time.Time
	nh, nj  int
	writeMu sync.Mutex
	closed  chan struct{}
	once    sync.Once
}

type share struct {
	host    *conn
	joiners map[string]*conn
}

// Server implements http.Handler.
type Server struct {
	opts   Options
	mu     sync.Mutex
	shares map[string]*share
	mux    *http.ServeMux
	static http.Handler
}

// New creates a server and starts its sweeper.
func New(opts Options) *Server {
	if opts.Logf == nil {
		opts.Logf = log.Printf
	}
	if opts.ICE == nil {
		opts.ICE = func(context.Context) []wire.ICEServer {
			return []wire.ICEServer{{URLs: []string{"stun:stun.cloudflare.com:3478"}}}
		}
	}
	s := &Server{opts: opts, shares: map[string]*share{}, mux: http.NewServeMux()}
	if opts.Assets != nil {
		s.static = http.FileServer(http.FS(opts.Assets))
	}
	s.mux.HandleFunc("/healthz", s.handleHealth)
	s.mux.HandleFunc("/.well-known/assetlinks.json", s.handleAssetLinks)
	s.mux.HandleFunc("/v1/host", s.handleHost)
	s.mux.HandleFunc("/v1/join/", s.handleJoin)
	s.mux.HandleFunc("/v1/status/", s.handleStatus)
	s.mux.HandleFunc("/v1/", func(w http.ResponseWriter, r *http.Request) { http.Error(w, "not found", 404) })
	s.mux.HandleFunc("/s/", s.handleSharePage)
	s.mux.HandleFunc("/", s.handleStatic)
	go s.sweeper()
	return s
}

var securityHeaders = map[string]string{
	"Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
		"connect-src 'self'; font-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'none'; " +
		"frame-ancestors 'none'; upgrade-insecure-requests",
	"Referrer-Policy":              "no-referrer",
	"X-Content-Type-Options":       "nosniff",
	"X-Frame-Options":              "DENY",
	"Permissions-Policy":           "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
	"Cross-Origin-Opener-Policy":   "same-origin",
	"Cross-Origin-Resource-Policy": "same-origin",
}

// ServeHTTP adds security headers and dispatches.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	h := w.Header()
	for k, v := range securityHeaders {
		h.Set(k, v)
	}
	if r.TLS != nil {
		h.Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
	}
	s.mux.ServeHTTP(w, r)
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	io.WriteString(w, "ok")
}

func (s *Server) handleAssetLinks(w http.ResponseWriter, r *http.Request) {
	type target struct {
		Namespace    string   `json:"namespace"`
		PackageName  string   `json:"package_name"`
		Fingerprints []string `json:"sha256_cert_fingerprints"`
	}
	type entry struct {
		Relation []string `json:"relation"`
		Target   target   `json:"target"`
	}
	out := []entry{}
	if s.opts.AndroidPackage != "" && len(s.opts.Fingerprints) > 0 {
		out = append(out, entry{
			Relation: []string{"delegate_permission/common.handle_all_urls"},
			Target:   target{Namespace: "android_app", PackageName: s.opts.AndroidPackage, Fingerprints: s.opts.Fingerprints},
		})
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "public, max-age=3600")
	json.NewEncoder(w).Encode(out)
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/v1/status/")
	if !shareIDRe.MatchString(id) {
		http.Error(w, "not found", 404)
		return
	}
	s.mu.Lock()
	sh := s.shares[id]
	active := sh != nil && sh.host != nil
	s.mu.Unlock()
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(map[string]bool{"active": active})
}

func (s *Server) handleSharePage(w http.ResponseWriter, r *http.Request) {
	rest := strings.Trim(strings.TrimPrefix(r.URL.Path, "/s/"), "/")
	if !shareIDRe.MatchString(rest) || s.opts.Assets == nil {
		s.notFound(w, r)
		return
	}
	s.serveFile(w, r, "s.html", "public, max-age=300")
}

func (s *Server) handleStatic(w http.ResponseWriter, r *http.Request) {
	if s.static == nil {
		s.notFound(w, r)
		return
	}
	p := strings.Trim(r.URL.Path, "/")
	if p == "" {
		p = "index.html"
	}
	if f, err := s.opts.Assets.Open(p); err == nil {
		f.Close()
		s.serveFile(w, r, p, "public, max-age=3600")
		return
	}
	// "auto trailing slash" style: /privacy → privacy.html
	if f, err := s.opts.Assets.Open(p + ".html"); err == nil {
		f.Close()
		s.serveFile(w, r, p+".html", "public, max-age=3600")
		return
	}
	s.notFound(w, r)
}

func (s *Server) serveFile(w http.ResponseWriter, r *http.Request, name, cache string) {
	data, err := fs.ReadFile(s.opts.Assets, name)
	if err != nil {
		s.notFound(w, r)
		return
	}
	w.Header().Set("Cache-Control", cache)
	http.ServeContent(w, r, name, time.Time{}, bytes.NewReader(data))
}

func (s *Server) notFound(w http.ResponseWriter, r *http.Request) {
	if s.opts.Assets != nil {
		if data, err := fs.ReadFile(s.opts.Assets, "404.html"); err == nil {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			w.WriteHeader(404)
			w.Write(data)
			return
		}
	}
	http.Error(w, "not found", 404)
}

// --- WebSocket endpoints ---------------------------------------------------

func (s *Server) accept(w http.ResponseWriter, r *http.Request) (*websocket.Conn, bool) {
	if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
		http.Error(w, "expected websocket", http.StatusUpgradeRequired)
		return nil, false
	}
	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		// No cookies or ambient credentials are involved, and native apps
		// send no Origin header; the protocol authenticates itself.
		InsecureSkipVerify: true,
	})
	if err != nil {
		return nil, false
	}
	ws.SetReadLimit(maxFrameBytes)
	return ws, true
}

func (s *Server) handleHost(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("share")
	if !shareIDRe.MatchString(id) {
		http.Error(w, "bad share id", 400)
		return
	}
	if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
		http.Error(w, "expected websocket", http.StatusUpgradeRequired)
		return
	}
	if s.opts.HostToken != "" {
		tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if tok == "" || tok == r.Header.Get("Authorization") {
			tok = r.URL.Query().Get("token")
		}
		if subtle.ConstantTimeCompare([]byte(tok), []byte(s.opts.HostToken)) != 1 {
			http.Error(w, "unauthorized", 401)
			return
		}
	}
	ws, ok := s.accept(w, r)
	if !ok {
		return
	}
	c := &conn{ws: ws, role: roleHost, at: time.Now(), last: time.Now(), closed: make(chan struct{})}

	s.mu.Lock()
	sh := s.shares[id]
	if sh == nil {
		sh = &share{joiners: map[string]*conn{}}
		s.shares[id] = sh
	}
	old := sh.host
	oldJoiners := sh.joiners
	sh.host = c
	sh.joiners = map[string]*conn{}
	s.mu.Unlock()

	if old != nil {
		old.fail(wire.ErrReplaced)
	}
	for _, j := range oldJoiners {
		j.fail(wire.ErrHostGone)
	}
	c.send(wire.Msg{T: wire.TReady})

	ctx := r.Context()
	for {
		m, err := c.read(ctx)
		if err != nil {
			break
		}
		switch m.T {
		case wire.TPing:
			c.send(wire.Msg{T: wire.TPong})
		case wire.TSig:
			if m.SID == "" || len(m.D) > maxPayloadChars {
				c.fail(wire.ErrProtocol)
				continue
			}
			s.mu.Lock()
			j := sh.joiners[m.SID]
			if j != nil {
				j.nh++
				j.last = time.Now()
			}
			current := sh.host == c
			s.mu.Unlock()
			if !current {
				continue
			}
			if j == nil {
				c.send(wire.Msg{T: wire.TLeave, SID: m.SID})
				continue
			}
			if j.nh > maxSigsPerDir {
				j.fail(wire.ErrRateLimited)
				continue
			}
			j.send(wire.Msg{T: wire.TSig, SID: m.SID, D: m.D})
		case wire.TLeave:
			s.mu.Lock()
			j := sh.joiners[m.SID]
			s.mu.Unlock()
			if j != nil {
				j.fail(wire.ErrClosed)
			}
		}
	}

	// Host gone.
	s.mu.Lock()
	var joiners []*conn
	if sh.host == c {
		sh.host = nil
		for _, j := range sh.joiners {
			joiners = append(joiners, j)
		}
		sh.joiners = map[string]*conn{}
		delete(s.shares, id)
	}
	s.mu.Unlock()
	for _, j := range joiners {
		j.fail(wire.ErrHostGone)
	}
	c.close()
}

func (s *Server) handleJoin(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/v1/join/")
	if !shareIDRe.MatchString(id) {
		http.Error(w, "not found", 404)
		return
	}
	ws, ok := s.accept(w, r)
	if !ok {
		return
	}
	c := &conn{ws: ws, role: roleJoiner, at: time.Now(), last: time.Now(), closed: make(chan struct{})}

	s.mu.Lock()
	sh := s.shares[id]
	if sh == nil || sh.host == nil {
		s.mu.Unlock()
		c.fail(wire.ErrNotFound)
		return
	}
	if len(sh.joiners) >= maxJoiners {
		s.mu.Unlock()
		c.fail(wire.ErrBusy)
		return
	}
	sid := newSID()
	c.sid = sid
	sh.joiners[sid] = c
	host := sh.host
	s.mu.Unlock()

	ice := s.opts.ICE(r.Context())
	c.send(wire.Msg{T: wire.TJoined, SID: sid, ICE: ice})
	host.send(wire.Msg{T: wire.TJoin, SID: sid, ICE: ice})

	ctx := r.Context()
	for {
		m, err := c.read(ctx)
		if err != nil {
			break
		}
		switch m.T {
		case wire.TPing:
			c.send(wire.Msg{T: wire.TPong})
		case wire.TSig:
			if len(m.D) > maxPayloadChars {
				c.fail(wire.ErrRateLimited)
				continue
			}
			s.mu.Lock()
			c.nj++
			c.last = time.Now()
			h := sh.host
			s.mu.Unlock()
			if c.nj > maxSigsPerDir {
				c.fail(wire.ErrRateLimited)
				continue
			}
			if h == nil {
				c.fail(wire.ErrHostGone)
				continue
			}
			h.send(wire.Msg{T: wire.TSig, SID: sid, D: m.D})
		}
	}

	s.mu.Lock()
	var h *conn
	if sh.joiners[sid] == c {
		delete(sh.joiners, sid)
		h = sh.host
	}
	if sh.host == nil && len(sh.joiners) == 0 {
		delete(s.shares, id)
	}
	s.mu.Unlock()
	if h != nil {
		h.send(wire.Msg{T: wire.TLeave, SID: sid})
	}
	c.close()
}

func (s *Server) sweeper() {
	t := time.NewTicker(sweepEvery)
	defer t.Stop()
	for range t.C {
		now := time.Now()
		var stale []*conn
		s.mu.Lock()
		for _, sh := range s.shares {
			for _, j := range sh.joiners {
				if now.Sub(j.last) > joinerIdle || now.Sub(j.at) > joinerMax {
					stale = append(stale, j)
				}
			}
		}
		s.mu.Unlock()
		for _, j := range stale {
			j.fail(wire.ErrTimeout)
		}
	}
}

// --- conn helpers -----------------------------------------------------------

func (c *conn) read(ctx context.Context) (wire.Msg, error) {
	for {
		typ, data, err := c.ws.Read(ctx)
		if err != nil {
			return wire.Msg{}, err
		}
		if typ != websocket.MessageText {
			c.fail(wire.ErrProtocol)
			return wire.Msg{}, io.EOF
		}
		m, err := wire.Decode(data)
		if err != nil {
			c.fail(wire.ErrProtocol)
			return wire.Msg{}, io.EOF
		}
		return m, nil
	}
}

func (c *conn) send(m wire.Msg) {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = c.ws.Write(ctx, websocket.MessageText, m.Encode())
}

func (c *conn) fail(code string) {
	c.send(wire.Msg{T: wire.TError, Code: code})
	status := websocket.StatusPolicyViolation
	if code == wire.ErrProtocol {
		status = websocket.StatusProtocolError
	}
	if code == wire.ErrReplaced || code == wire.ErrHostGone {
		status = websocket.StatusNormalClosure
	}
	c.once.Do(func() {
		close(c.closed)
		_ = c.ws.Close(status, code)
	})
}

func (c *conn) close() {
	c.once.Do(func() {
		close(c.closed)
		_ = c.ws.Close(websocket.StatusNormalClosure, "bye")
	})
}

func newSID() string {
	b := make([]byte, 12)
	rand.Read(b)
	return wire.B64.EncodeToString(b)
}
