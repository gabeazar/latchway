package server

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/gabeazar/latchway/internal/wire"
)

// ICEProvider assembles the ICE server list handed to peers: Cloudflare's
// free STUN by default, optional short-lived Cloudflare TURN credentials, and
// optional static servers (for example your own coturn).
type ICEProvider struct {
	TurnKeyID    string
	TurnAPIToken string
	Static       []wire.ICEServer
	TTL          time.Duration
	Cache        time.Duration

	mu       sync.Mutex
	cached   []wire.ICEServer
	cachedAt time.Time
}

const cloudflareTURNAPI = "https://rtc.live.cloudflare.com/v1/turn/keys/"

// Servers returns the current list, refreshing TURN credentials when the
// cached ones are older than Cache.
func (p *ICEProvider) Servers(ctx context.Context) []wire.ICEServer {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.cached != nil && time.Since(p.cachedAt) < p.cacheFor() {
		return p.cached
	}
	servers := []wire.ICEServer{{URLs: []string{"stun:stun.cloudflare.com:3478"}}}
	if p.TurnKeyID != "" && p.TurnAPIToken != "" {
		if turn := p.fetchTURN(ctx); len(turn) > 0 {
			servers = turn
		}
	}
	servers = append(servers, p.Static...)
	p.cached = servers
	p.cachedAt = time.Now()
	return servers
}

func (p *ICEProvider) cacheFor() time.Duration {
	if p.Cache > 0 {
		return p.Cache
	}
	return time.Hour
}

func (p *ICEProvider) fetchTURN(ctx context.Context) []wire.ICEServer {
	ttl := p.TTL
	if ttl == 0 {
		ttl = 2 * time.Hour
	}
	body, _ := json.Marshal(map[string]int{"ttl": int(ttl.Seconds())})
	rctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(rctx, http.MethodPost,
		cloudflareTURNAPI+url.PathEscape(p.TurnKeyID)+"/credentials/generate-ice-servers",
		bytes.NewReader(body))
	if err != nil {
		return nil
	}
	req.Header.Set("Authorization", "Bearer "+p.TurnAPIToken)
	req.Header.Set("Content-Type", "application/json")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil
	}
	defer res.Body.Close()
	if res.StatusCode < 200 || res.StatusCode > 299 {
		return nil
	}
	var out struct {
		ICEServers json.RawMessage `json:"iceServers"`
	}
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return nil
	}
	var list []wire.ICEServer
	if err := json.Unmarshal(out.ICEServers, &list); err == nil && len(list) > 0 {
		return list
	}
	var single wire.ICEServer
	if err := json.Unmarshal(out.ICEServers, &single); err == nil && len(single.URLs) > 0 {
		return []wire.ICEServer{single}
	}
	return nil
}
