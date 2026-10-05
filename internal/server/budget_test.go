package server

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestDecide(t *testing.T) {
	now := time.Date(2026, 10, 17, 12, 0, 0, 0, time.UTC)
	budget := int64(900 * GB)
	cases := []struct {
		name  string
		b     int64
		r     *usageReading
		allow bool
	}{
		{"no budget", 0, nil, true},
		{"unknown", budget, nil, false},
		{"stale", budget, &usageReading{Bytes: 0, At: now.Add(-budgetStale - time.Second)}, false},
		{"spent", budget, &usageReading{Bytes: budget, At: now}, false},
		{"within", budget, &usageReading{Bytes: budget - 1, At: now}, true},
		{"previous month", GB, &usageReading{Bytes: 5 * GB, At: now.AddDate(0, -1, 0)}, true},
	}
	for _, c := range cases {
		if got, reason := decide(c.b, c.r, now); got != c.allow {
			t.Errorf("%s: allow=%v (%s)", c.name, got, reason)
		}
	}
}

func TestUsageQueryWindow(t *testing.T) {
	now := time.Date(2026, 10, 17, 12, 0, 0, 0, time.UTC)
	q := usageQuery("abc", now)
	if q.Variables["from"] != "2026-10-01T00:00:00Z" || q.Variables["to"] != "2026-10-17T12:00:00Z" {
		t.Fatalf("window %v", q.Variables)
	}
}

func TestParseUsage(t *testing.T) {
	var r graphqlResponse
	json.Unmarshal([]byte(`{"data":{"viewer":{"accounts":[{"callsTurnUsageAdaptiveGroups":[{"sum":{"egressBytes":1500}},{"sum":{"egressBytes":500}}]}]}}}`), &r)
	if n, err := parseUsage(r); err != nil || n != 2000 {
		t.Fatalf("sum: %d %v", n, err)
	}
	var e graphqlResponse
	json.Unmarshal([]byte(`{"errors":[{"message":"authentication error"}]}`), &e)
	if _, err := parseUsage(e); err == nil {
		t.Fatal("errors must fail")
	}
	var empty graphqlResponse
	json.Unmarshal([]byte(`{"data":{"viewer":{"accounts":[{"callsTurnUsageAdaptiveGroups":[]}]}}}`), &empty)
	if n, err := parseUsage(empty); err != nil || n != 0 {
		t.Fatalf("no groups: %d %v", n, err)
	}
}

// The provider must withhold TURN once the analytics answer crosses the
// budget, and keep serving STUN meanwhile.
func TestBudgetGatesTURN(t *testing.T) {
	var egress atomic.Int64
	var calls atomic.Int32
	analytics := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer tok" {
			http.Error(w, "unauthorized", 401)
			return
		}
		var req graphqlRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Variables["account"] != "acct" {
			http.Error(w, "bad request", 400)
			return
		}
		fmt.Fprintf(w, `{"data":{"viewer":{"accounts":[{"callsTurnUsageAdaptiveGroups":[{"sum":{"egressBytes":%d}}]}]}}}`, egress.Load())
	}))
	defer analytics.Close()

	clock := time.Date(2026, 10, 17, 12, 0, 0, 0, time.UTC)
	b := &TURNBudget{BudgetBytes: 10 * GB, AccountID: "acct", Token: "tok", Endpoint: analytics.URL, Now: func() time.Time { return clock }}

	if !b.Allow(context.Background()) {
		t.Fatal("under budget must allow")
	}
	if calls.Load() != 1 {
		t.Fatalf("expected one analytics call, got %d", calls.Load())
	}
	// Within the refresh interval the reading is reused.
	b.Allow(context.Background())
	if calls.Load() != 1 {
		t.Fatalf("reading must be cached, got %d calls", calls.Load())
	}
	// Usage climbs past the budget; the next refresh notices.
	egress.Store(11 * GB)
	clock = clock.Add(budgetRefreshEvery + time.Minute)
	if b.Allow(context.Background()) {
		t.Fatal("over budget must withhold")
	}
	// A new month resets even before a refresh succeeds.
	clock = time.Date(2026, 11, 1, 0, 1, 0, 0, time.UTC)
	analytics.Close() // refresh fails; the old reading is from last month
	if !b.Allow(context.Background()) {
		t.Fatal("new month must allow")
	}
}
