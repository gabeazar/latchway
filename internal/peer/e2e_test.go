package peer

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/gabeazar/latchway/internal/rendezvous"
	"github.com/gabeazar/latchway/internal/server"
	"github.com/gabeazar/latchway/internal/wire"
)

// The end-to-end tests run a real host and a real joiner through a
// rendezvous over loopback: the in-process Go server by default, or any
// other implementation named by RELAY_URL (the Worker under wrangler dev in
// CI). LATCHWAY_E2E_SIZE overrides the large file's size in bytes.

func TestMain(m *testing.M) {
	os.Setenv("LATCHWAY_ICE_LOOPBACK", "1")
	os.Exit(m.Run())
}

func testRendezvous(t *testing.T) *url.URL {
	t.Helper()
	if raw := os.Getenv("RELAY_URL"); raw != "" {
		u, err := rendezvous.BaseURL(raw)
		if err != nil {
			t.Fatal(err)
		}
		return u
	}
	srv := server.New(server.Options{
		// No STUN: both peers are on this machine and tests must pass
		// without a network.
		ICE:  func(context.Context) []wire.ICEServer { return []wire.ICEServer{} },
		Logf: func(string, ...any) {},
	})
	ts := httptest.NewServer(srv)
	t.Cleanup(ts.Close)
	u, err := rendezvous.BaseURL(ts.URL)
	if err != nil {
		t.Fatal(err)
	}
	return u
}

// patternFile writes size bytes of a cheap deterministic pattern and
// returns the path and SHA-256.
func patternFile(t *testing.T, size int64) (string, string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "payload.bin")
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	h := sha256.New()
	w := io.MultiWriter(f, h)
	block := make([]byte, 1<<20)
	for i := range block {
		block[i] = byte(i*31 + i>>8)
	}
	for written := int64(0); written < size; {
		n := int64(len(block))
		if size-written < n {
			n = size - written
		}
		// Vary each block so a reordering bug cannot hide.
		block[0] = byte(written >> 20)
		block[1] = byte(written >> 28)
		if _, err := w.Write(block[:n]); err != nil {
			t.Fatal(err)
		}
		written += n
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
	return path, hex.EncodeToString(h.Sum(nil))
}

func hashFile(t *testing.T, path string) string {
	t.Helper()
	f, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(h.Sum(nil))
}

type hostFixture struct {
	host  *Host
	share wire.Share
	base  *url.URL
	done  chan error
}

func startHost(t *testing.T, base *url.URL, path string, mutate func(*HostOptions)) *hostFixture {
	t.Helper()
	share, err := wire.NewShare()
	if err != nil {
		t.Fatal(err)
	}
	reg, _ := wire.NewRegKey()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	opts := HostOptions{
		Rendezvous:   base,
		Share:        share,
		RegKey:       reg,
		Meta:         FileMeta{Name: filepath.Base(path), Size: info.Size(), From: "test"},
		Open:         func() (io.ReadCloser, error) { return os.Open(path) },
		MaxDownloads: 1,
	}
	if mutate != nil {
		mutate(&opts)
	}
	h, err := NewHost(opts)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	fx := &hostFixture{host: h, share: share, base: base, done: make(chan error, 1)}
	registered := make(chan struct{})
	var once sync.Once
	inner := opts.OnEvent
	h.opts.OnEvent = func(e Event) {
		if e.Kind == EvRegistered && e.Err == nil {
			once.Do(func() { close(registered) })
		}
		if inner != nil {
			inner(e)
		}
	}
	go func() { fx.done <- h.Run(ctx) }()
	select {
	case <-registered:
	case err := <-fx.done:
		t.Fatalf("host exited before registering: %v", err)
	case <-time.After(20 * time.Second):
		t.Fatal("host did not register")
	}
	return fx
}

func receiveTo(t *testing.T, fx *hostFixture, dir string, mutate func(*ReceiveOptions)) (string, Result, error) {
	t.Helper()
	var dest string
	opts := ReceiveOptions{
		Share:      fx.share,
		Host:       fx.base.Host,
		Rendezvous: fx.base,
		Accept: func(_ context.Context, meta FileMeta, _ bool) (io.WriteCloser, error) {
			dest = filepath.Join(dir, meta.Name)
			return os.Create(dest)
		},
	}
	if mutate != nil {
		mutate(&opts)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
	defer cancel()
	res, err := Receive(ctx, opts)
	return dest, res, err
}

func waitHost(t *testing.T, fx *hostFixture) error {
	t.Helper()
	select {
	case err := <-fx.done:
		return err
	case <-time.After(30 * time.Second):
		t.Fatal("host did not stop")
		return nil
	}
}

func TestSmallFileRoundTrip(t *testing.T) {
	base := testRendezvous(t)
	// Three full chunks and a partial one.
	src, want := patternFile(t, 3*wire.ChunkSize+12345)
	fx := startHost(t, base, src, nil)
	dest, res, err := receiveTo(t, fx, t.TempDir(), nil)
	if err != nil {
		t.Fatalf("receive: %v", err)
	}
	if got := hashFile(t, dest); got != want {
		t.Fatalf("hash mismatch: %s != %s", got, want)
	}
	if res.Meta.From != "test" || res.Bytes != 3*wire.ChunkSize+12345 {
		t.Fatalf("result %+v", res)
	}
	if err := waitHost(t, fx); err != nil {
		t.Fatalf("host: %v", err)
	}
	if fx.host.Completed() != 1 {
		t.Fatalf("completed = %d", fx.host.Completed())
	}
}

func TestEmptyAndExactMultiple(t *testing.T) {
	base := testRendezvous(t)
	for _, size := range []int64{0, 2 * wire.ChunkSize} {
		t.Run(strconv.FormatInt(size, 10), func(t *testing.T) {
			src, want := patternFile(t, size)
			fx := startHost(t, base, src, nil)
			dest, _, err := receiveTo(t, fx, t.TempDir(), nil)
			if err != nil {
				t.Fatalf("receive: %v", err)
			}
			if got := hashFile(t, dest); got != want {
				t.Fatalf("hash mismatch")
			}
			if err := waitHost(t, fx); err != nil {
				t.Fatalf("host: %v", err)
			}
		})
	}
}

func TestPasswordAndBadAuth(t *testing.T) {
	base := testRendezvous(t)
	src, want := patternFile(t, 70_000)
	var authFailed int
	var mu sync.Mutex
	fx := startHost(t, base, src, func(o *HostOptions) {
		o.Password = "correct horse"
		o.OnEvent = func(e Event) {
			if e.Kind == EvAuthFailed {
				mu.Lock()
				authFailed++
				mu.Unlock()
			}
		}
	})

	// Wrong password: the host answers bad_auth in plaintext and leaves.
	_, _, err := receiveTo(t, fx, t.TempDir(), func(o *ReceiveOptions) { o.Password = "wrong" })
	if !IsCode(err, wire.ErrBadAuth) {
		t.Fatalf("expected bad_auth, got %v", err)
	}
	// No password offered at all.
	_, _, err = receiveTo(t, fx, t.TempDir(), nil)
	if !IsCode(err, wire.ErrBadAuth) {
		t.Fatalf("expected bad_auth without a password, got %v", err)
	}
	mu.Lock()
	n := authFailed
	mu.Unlock()
	if n != 1 {
		t.Fatalf("host saw %d failed proofs, want 1", n)
	}

	// Right password, asked for interactively.
	asked := false
	dest, _, err := receiveTo(t, fx, t.TempDir(), func(o *ReceiveOptions) {
		o.AskPassword = func(context.Context) (string, error) { asked = true; return "correct horse", nil }
	})
	if err != nil {
		t.Fatalf("receive with password: %v", err)
	}
	if !asked {
		t.Fatal("AskPassword was not consulted")
	}
	if hashFile(t, dest) != want {
		t.Fatal("hash mismatch")
	}
	if err := waitHost(t, fx); err != nil {
		t.Fatalf("host: %v", err)
	}
}

func TestWrongSecretIsInvalidLink(t *testing.T) {
	base := testRendezvous(t)
	src, _ := patternFile(t, 10)
	fx := startHost(t, base, src, nil)
	_, _, err := receiveTo(t, fx, t.TempDir(), func(o *ReceiveOptions) {
		o.Share.Secret[0] ^= 1
	})
	// A wrong secret fails the proof, which the host reports as bad_auth.
	if !IsCode(err, wire.ErrBadAuth) {
		t.Fatalf("expected bad_auth, got %v", err)
	}
}

func TestRevokeYieldsHostGone(t *testing.T) {
	base := testRendezvous(t)
	src, _ := patternFile(t, 10)
	approvalAsked := make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	share, _ := wire.NewShare()
	reg, _ := wire.NewRegKey()
	h, err := NewHost(HostOptions{
		Rendezvous: base, Share: share, RegKey: reg,
		Meta: FileMeta{Name: "x", Size: 10},
		Open: func() (io.ReadCloser, error) { return os.Open(src) },
		Approve: func(ctx context.Context, sid string) bool {
			close(approvalAsked)
			<-ctx.Done() // the share is revoked while we "think"
			return false
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	hostDone := make(chan error, 1)
	go func() { hostDone <- h.Run(ctx) }()
	// Wait until the share is live.
	deadline := time.Now().Add(10 * time.Second)
	for {
		c, _, err := rendezvous.DialJoiner(context.Background(), base, share.IDString())
		if err == nil {
			c.Close()
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("share never became active: %v", err)
		}
		time.Sleep(50 * time.Millisecond)
	}

	recvErr := make(chan error, 1)
	go func() {
		_, err := Receive(context.Background(), ReceiveOptions{
			Share: share, Host: base.Host, Rendezvous: base,
			Accept: func(context.Context, FileMeta, bool) (io.WriteCloser, error) {
				return nopWriteCloser{io.Discard}, nil
			},
		})
		recvErr <- err
	}()
	select {
	case <-approvalAsked:
	case err := <-recvErr:
		t.Fatalf("receive ended early: %v", err)
	case <-time.After(20 * time.Second):
		t.Fatal("approval never requested")
	}
	cancel() // revoke
	select {
	case err := <-recvErr:
		if !IsCode(err, wire.ErrHostGone) {
			t.Fatalf("expected host_gone, got %v", err)
		}
	case <-time.After(20 * time.Second):
		t.Fatal("joiner was not told the host is gone")
	}
	<-hostDone

	// The link is dead: a new joiner is refused before the upgrade.
	_, _, err = rendezvous.DialJoiner(context.Background(), base, share.IDString())
	if !IsCode(err, wire.ErrNotFound) {
		t.Fatalf("expected not_found after revoke, got %v", err)
	}
}

func TestDeniedAndExpiredAfterMaxDownloads(t *testing.T) {
	base := testRendezvous(t)
	src, want := patternFile(t, 1000)
	decisions := []bool{false, true}
	fx := startHost(t, base, src, func(o *HostOptions) {
		o.Approve = func(context.Context, string) bool {
			d := decisions[0]
			decisions = decisions[1:]
			return d
		}
	})
	_, _, err := receiveTo(t, fx, t.TempDir(), nil)
	if !IsCode(err, wire.ErrDenied) {
		t.Fatalf("expected denied, got %v", err)
	}
	dest, _, err := receiveTo(t, fx, t.TempDir(), nil)
	if err != nil {
		t.Fatalf("second receive: %v", err)
	}
	if hashFile(t, dest) != want {
		t.Fatal("hash mismatch")
	}
	if err := waitHost(t, fx); err != nil {
		t.Fatalf("host: %v", err)
	}
	// With MaxDownloads reached the host has gone; the link is dead.
	_, _, err = receiveTo(t, fx, t.TempDir(), nil)
	if !IsCode(err, wire.ErrNotFound) {
		t.Fatalf("expected not_found, got %v", err)
	}
}

func TestLargeFile(t *testing.T) {
	if testing.Short() {
		t.Skip("large transfer skipped with -short")
	}
	size := int64(1 << 30)
	if v := os.Getenv("LATCHWAY_E2E_SIZE"); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil {
			t.Fatalf("LATCHWAY_E2E_SIZE: %v", err)
		}
		size = n
	}
	base := testRendezvous(t)
	src, want := patternFile(t, size)
	var progress int64
	fx := startHost(t, base, src, nil)
	start := time.Now()
	dest, res, err := receiveTo(t, fx, t.TempDir(), func(o *ReceiveOptions) {
		o.OnEvent = func(e Event) {
			if e.Kind == EvProgress {
				progress = e.Bytes
			}
		}
	})
	if err != nil {
		t.Fatalf("receive: %v", err)
	}
	elapsed := time.Since(start)
	if res.Bytes != size || progress != size {
		t.Fatalf("bytes %d progress %d want %d", res.Bytes, progress, size)
	}
	if got := hashFile(t, dest); got != want {
		t.Fatalf("hash mismatch: %s != %s", got, want)
	}
	if err := waitHost(t, fx); err != nil {
		t.Fatalf("host: %v", err)
	}
	t.Logf("%d bytes in %s (%.1f MiB/s)", size, elapsed.Round(time.Millisecond), float64(size)/elapsed.Seconds()/(1<<20))
}

func TestMimeOf(t *testing.T) {
	if got := mimeOf("a.PNG"); got != "image/png" {
		t.Fatalf("png: %q", got)
	}
	if got := mimeOf("noext"); got != "application/octet-stream" {
		t.Fatalf("noext: %q", got)
	}
}

type nopWriteCloser struct{ io.Writer }

func (nopWriteCloser) Close() error { return nil }

var _ = bytes.Equal
var _ = errors.New
