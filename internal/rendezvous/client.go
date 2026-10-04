// Package rendezvous is the client side of the rendezvous protocol
// (PROTOCOL.md §3): one WebSocket per host registration or joiner session,
// JSON text frames, an application-level keepalive the server answers
// without waking.
package rendezvous

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"

	"github.com/gabeazar/latchway/internal/wire"
)

const (
	keepaliveInterval = 25 * time.Second
	readLimit         = 64 * 1024
)

// Conn is a connection to the rendezvous as either host or joiner.
type Conn struct {
	ws      *websocket.Conn
	writeMu sync.Mutex
	cancel  context.CancelFunc
}

// ServerError is an {"t":"error"} message from the rendezvous.
type ServerError struct{ Code string }

func (e *ServerError) Error() string { return "rendezvous: " + e.Code }

// BaseURL normalises user input such as "latchway.app", "https://latchway.app/"
// or "http://127.0.0.1:8787" into a URL the client can derive endpoints from.
func BaseURL(raw string) (*url.URL, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, errors.New("empty rendezvous address")
	}
	if !strings.Contains(raw, "://") {
		raw = "https://" + raw
	}
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return nil, fmt.Errorf("invalid rendezvous address %q", raw)
	}
	if u.Scheme != "https" && u.Scheme != "http" {
		return nil, fmt.Errorf("rendezvous address must be https (or http for local testing)")
	}
	u.Path = strings.TrimRight(u.Path, "/")
	u.RawQuery = ""
	u.Fragment = ""
	return u, nil
}

func wsURL(base *url.URL, path string, query url.Values) string {
	u := *base
	switch u.Scheme {
	case "https":
		u.Scheme = "wss"
	case "http":
		u.Scheme = "ws"
	}
	u.Path = base.Path + path
	u.RawQuery = query.Encode()
	return u.String()
}

// DialHost registers a share with its registration key (PROTOCOL.md §3.1)
// and returns once the server acknowledged it. A *ServerError with code
// "forbidden" means another device holds this share id.
func DialHost(ctx context.Context, base *url.URL, shareID string, regKey []byte, token string) (*Conn, error) {
	if len(regKey) != wire.RegKeyLen {
		return nil, errors.New("registration key must be 32 bytes")
	}
	q := url.Values{"share": {shareID}}
	h := http.Header{}
	if token != "" {
		h.Set("Authorization", "Bearer "+token)
	}
	c, err := dial(ctx, wsURL(base, "/v1/host", q), h)
	if err != nil {
		return nil, err
	}
	if err := c.Send(ctx, wire.Msg{T: wire.TRegister, Reg: wire.B64.EncodeToString(regKey)}); err != nil {
		c.Close()
		return nil, err
	}
	first, err := c.Recv(ctx)
	if err != nil {
		c.Close()
		return nil, err
	}
	if first.T != wire.TReady {
		c.Close()
		return nil, fmt.Errorf("unexpected first message %q", first.T)
	}
	return c, nil
}

// DialJoiner connects to a share and returns the joined message (sid, ICE).
// A refused upgrade (PROTOCOL.md §3.2) is reported as a *ServerError:
// "not_found" when no host is registered, "busy" when the joiner caps are
// reached.
func DialJoiner(ctx context.Context, base *url.URL, shareID string) (*Conn, wire.Msg, error) {
	c, err := dial(ctx, wsURL(base, "/v1/join/"+shareID, nil), nil)
	if err != nil {
		var se *statusError
		if errors.As(err, &se) {
			switch se.code {
			case http.StatusNotFound:
				return nil, wire.Msg{}, &ServerError{Code: wire.ErrNotFound}
			case http.StatusTooManyRequests:
				return nil, wire.Msg{}, &ServerError{Code: wire.ErrBusy}
			}
		}
		return nil, wire.Msg{}, err
	}
	first, err := c.Recv(ctx)
	if err != nil {
		c.Close()
		return nil, wire.Msg{}, err
	}
	if first.T != wire.TJoined {
		c.Close()
		return nil, wire.Msg{}, fmt.Errorf("unexpected first message %q", first.T)
	}
	return c, first, nil
}

// statusError records the HTTP status of a refused upgrade.
type statusError struct{ code int }

func (e *statusError) Error() string { return fmt.Sprintf("rendezvous refused the connection (HTTP %d)", e.code) }

func dial(ctx context.Context, u string, h http.Header) (*Conn, error) {
	dctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	ws, resp, err := websocket.Dial(dctx, u, &websocket.DialOptions{HTTPHeader: h})
	if err != nil {
		if resp != nil && resp.StatusCode != http.StatusSwitchingProtocols {
			return nil, &statusError{code: resp.StatusCode}
		}
		return nil, fmt.Errorf("connect to rendezvous: %w", err)
	}
	ws.SetReadLimit(readLimit)
	kctx, kcancel := context.WithCancel(context.Background())
	c := &Conn{ws: ws, cancel: kcancel}
	go c.keepalive(kctx)
	return c, nil
}

func (c *Conn) keepalive(ctx context.Context) {
	t := time.NewTicker(keepaliveInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			_ = c.Send(ctx, wire.Msg{T: wire.TPing})
		}
	}
}

// Send writes one message.
func (c *Conn) Send(ctx context.Context, m wire.Msg) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	wctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	return c.ws.Write(wctx, websocket.MessageText, m.Encode())
}

// Recv reads the next message, swallowing keepalive replies and turning
// server errors into *ServerError.
func (c *Conn) Recv(ctx context.Context) (wire.Msg, error) {
	for {
		typ, data, err := c.ws.Read(ctx)
		if err != nil {
			return wire.Msg{}, err
		}
		if typ != websocket.MessageText {
			continue
		}
		m, err := wire.Decode(data)
		if err != nil {
			return wire.Msg{}, fmt.Errorf("rendezvous sent malformed message: %w", err)
		}
		if m.T == wire.TPong {
			continue
		}
		if m.T == wire.TError {
			return m, &ServerError{Code: m.Code}
		}
		return m, nil
	}
}

// Close shuts the connection down.
func (c *Conn) Close() {
	c.cancel()
	_ = c.ws.Close(websocket.StatusNormalClosure, "bye")
}
