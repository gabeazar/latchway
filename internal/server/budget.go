package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"time"
)

// TURN usage budget, the same rules as relay/src/budget.js: Cloudflare
// bills Realtime TURN on egress bytes per calendar month. The provider sums
// the month's egress through the GraphQL analytics API and, once past the
// budget, hands hosts STUN only until the month rolls over.

const (
	// GB is Cloudflare's decimal gigabyte.
	GB = 1_000_000_000

	budgetStale        = 48 * time.Hour   // a reading older than this withholds TURN
	budgetRefreshEvery = 30 * time.Minute // how often a reading is renewed
	budgetRetryAfter   = 5 * time.Minute  // backoff after a failed refresh

	defaultAnalyticsEndpoint = "https://api.cloudflare.com/client/v4/graphql"
)

// usageReading is one month-to-date measurement.
type usageReading struct {
	Bytes int64
	At    time.Time
}

// TURNBudget decides whether TURN credentials may be issued.
type TURNBudget struct {
	// BudgetBytes is the monthly cap; 0 disables the budget.
	BudgetBytes int64
	// AccountID and Token authenticate the analytics query. Endpoint
	// overrides the GraphQL URL (tests).
	AccountID string
	Token     string
	Endpoint  string
	Logf      func(format string, args ...any)
	// Now is the clock (tests).
	Now func() time.Time

	mu          sync.Mutex
	reading     *usageReading
	lastAttempt time.Time
}

func monthStart(t time.Time) time.Time {
	t = t.UTC()
	return time.Date(t.Year(), t.Month(), 1, 0, 0, 0, 0, time.UTC)
}

// decide applies the budget rules to a reading.
func decide(budget int64, r *usageReading, now time.Time) (allow bool, reason string) {
	if budget <= 0 {
		return true, "no budget configured"
	}
	if r == nil {
		return false, "usage unknown"
	}
	if r.At.Before(monthStart(now)) {
		return true, "reading from a previous month; usage reset"
	}
	if now.Sub(r.At) > budgetStale {
		return false, "usage reading is stale"
	}
	if r.Bytes >= budget {
		return false, "monthly TURN budget reached"
	}
	return true, "within budget"
}

func (b *TURNBudget) now() time.Time {
	if b.Now != nil {
		return b.Now()
	}
	return time.Now()
}

func (b *TURNBudget) logf(format string, args ...any) {
	if b.Logf != nil {
		b.Logf(format, args...)
	}
}

// Allow reports whether TURN may be issued now, refreshing the reading
// first when it is missing or older than budgetRefreshEvery.
func (b *TURNBudget) Allow(ctx context.Context) bool {
	if b == nil || b.BudgetBytes <= 0 {
		return true
	}
	now := b.now()
	b.mu.Lock()
	defer b.mu.Unlock()
	if (b.reading == nil || now.Sub(b.reading.At) > budgetRefreshEvery) && now.Sub(b.lastAttempt) > budgetRetryAfter {
		b.lastAttempt = now
		if r, err := b.fetch(ctx, now); err != nil {
			b.logf("turn usage refresh failed: %v", err)
		} else {
			b.reading = r
			if r.Bytes >= b.BudgetBytes {
				b.logf("turn budget reached: %.1f GB of %.0f GB this month", float64(r.Bytes)/GB, float64(b.BudgetBytes)/GB)
			}
		}
	}
	allow, reason := decide(b.BudgetBytes, b.reading, now)
	if !allow {
		b.logf("turn withheld: %s", reason)
	}
	return allow
}

// fetch runs the GraphQL query.
func (b *TURNBudget) fetch(ctx context.Context, now time.Time) (*usageReading, error) {
	if b.Token == "" || b.AccountID == "" {
		return nil, errors.New("TURN budget is set but the analytics token or account id is missing")
	}
	endpoint := b.Endpoint
	if endpoint == "" {
		endpoint = defaultAnalyticsEndpoint
	}
	body, _ := json.Marshal(usageQuery(b.AccountID, now))
	rctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(rctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+b.Token)
	req.Header.Set("Content-Type", "application/json")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	var out graphqlResponse
	if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
		return nil, fmt.Errorf("analytics: %w", err)
	}
	n, err := parseUsage(out)
	if err != nil {
		return nil, err
	}
	return &usageReading{Bytes: n, At: now}, nil
}

type graphqlRequest struct {
	Query     string         `json:"query"`
	Variables map[string]any `json:"variables"`
}

func usageQuery(account string, now time.Time) graphqlRequest {
	return graphqlRequest{
		Query: `query TurnUsage($account: string!, $from: Time!, $to: Time!) {
  viewer {
    accounts(filter: { accountTag: $account }) {
      callsTurnUsageAdaptiveGroups(
        filter: { datetimeMinute_gt: $from, datetimeMinute_lt: $to }
        limit: 1
      ) {
        sum { egressBytes }
      }
    }
  }
}`,
		Variables: map[string]any{
			"account": account,
			"from":    monthStart(now).Format(time.RFC3339),
			"to":      now.UTC().Format(time.RFC3339),
		},
	}
}

type graphqlResponse struct {
	Errors []struct {
		Message string `json:"message"`
	} `json:"errors"`
	Data struct {
		Viewer struct {
			Accounts []struct {
				Groups []struct {
					Sum struct {
						EgressBytes float64 `json:"egressBytes"`
					} `json:"sum"`
				} `json:"callsTurnUsageAdaptiveGroups"`
			} `json:"accounts"`
		} `json:"viewer"`
	} `json:"data"`
}

func parseUsage(r graphqlResponse) (int64, error) {
	if len(r.Errors) > 0 {
		return 0, fmt.Errorf("analytics error: %s", r.Errors[0].Message)
	}
	if len(r.Data.Viewer.Accounts) == 0 {
		return 0, errors.New("analytics: no account in response")
	}
	var total int64
	for _, g := range r.Data.Viewer.Accounts[0].Groups {
		if g.Sum.EgressBytes > 0 {
			total += int64(g.Sum.EgressBytes)
		}
	}
	return total, nil
}
