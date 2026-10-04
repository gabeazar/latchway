// Package cmd_test drives the two built binaries through a rendezvous over
// loopback: the acceptance test for ROADMAP Milestone 1. It needs the Go
// toolchain to build them and is skipped with -short. LATCHWAY_E2E_SIZE
// overrides the file size (default 1 GiB); RELAY_URL points it at another
// rendezvous instead of the in-process Go server.
package cmd_test

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/gabeazar/latchway/internal/server"
	"github.com/gabeazar/latchway/internal/wire"
)

func build(t *testing.T, dir, pkg string) string {
	t.Helper()
	exe := filepath.Join(dir, filepath.Base(pkg))
	if runtime.GOOS == "windows" {
		exe += ".exe"
	}
	goBin := filepath.Join(runtime.GOROOT(), "bin", "go")
	cmd := exec.Command(goBin, "build", "-o", exe, "./"+pkg)
	cmd.Dir = ".."
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("build %s: %v\n%s", pkg, err, out)
	}
	return exe
}

func TestCLIRoundTrip(t *testing.T) {
	if testing.Short() {
		t.Skip("CLI round trip skipped with -short")
	}
	size := int64(1 << 30)
	if v := os.Getenv("LATCHWAY_E2E_SIZE"); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil {
			t.Fatal(err)
		}
		size = n
	}

	relay := os.Getenv("RELAY_URL")
	if relay == "" {
		srv := server.New(server.Options{
			ICE:  func(context.Context) []wire.ICEServer { return []wire.ICEServer{} },
			Logf: func(string, ...any) {},
		})
		ts := httptest.NewServer(srv)
		defer ts.Close()
		relay = ts.URL
	}

	bin := t.TempDir()
	send := build(t, bin, "cmd/latchway-send")
	recv := build(t, bin, "cmd/latchway-receive")

	work := t.TempDir()
	src := filepath.Join(work, "big.bin")
	want := writePattern(t, src, size)
	outDir := filepath.Join(work, "out")
	if err := os.Mkdir(outDir, 0o755); err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
	defer cancel()
	env := append(os.Environ(), "LATCHWAY_ICE_LOOPBACK=1", "LATCHWAY_PASSWORD=open sesame")

	sender := exec.CommandContext(ctx, send, "--rendezvous", relay, "--password", "--downloads", "1", "--from", "cli test", src)
	sender.Env = env
	var sendErr bytes.Buffer
	sender.Stderr = &sendErr
	stdout, err := sender.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := sender.Start(); err != nil {
		t.Fatal(err)
	}
	link, err := bufio.NewReader(stdout).ReadString('\n')
	if err != nil {
		t.Fatalf("no link from sender: %v\n%s", err, sendErr.String())
	}
	link = strings.TrimSpace(link)
	if _, _, err := wire.ParseLink(link); err != nil {
		t.Fatalf("sender printed %q: %v", link, err)
	}

	// Wrong password first: the receiver must report it and exit non-zero.
	bad := exec.CommandContext(ctx, recv, "--rendezvous", relay, "--out", outDir, "--password", link)
	bad.Env = append(os.Environ(), "LATCHWAY_ICE_LOOPBACK=1", "LATCHWAY_PASSWORD=nope")
	out, err := bad.CombinedOutput()
	if err == nil || !strings.Contains(string(out), "wrong password") {
		t.Fatalf("wrong password: err=%v output=%s", err, out)
	}

	receiver := exec.CommandContext(ctx, recv, "--rendezvous", relay, "--out", outDir, "--password", link)
	receiver.Env = env
	start := time.Now()
	out, err = receiver.CombinedOutput()
	if err != nil {
		t.Fatalf("receiver: %v\n%s\nsender stderr:\n%s", err, out, sendErr.String())
	}
	elapsed := time.Since(start)
	if err := sender.Wait(); err != nil {
		t.Fatalf("sender: %v\n%s", err, sendErr.String())
	}

	dest := filepath.Join(outDir, "big.bin")
	if got := hashFile(t, dest); got != want {
		t.Fatalf("hash mismatch: %s != %s", got, want)
	}
	if _, err := os.Stat(dest + ".part"); err == nil {
		t.Fatal("partial file left behind")
	}
	t.Logf("%d bytes through the CLIs in %s (%.1f MiB/s)", size, elapsed.Round(time.Millisecond), float64(size)/elapsed.Seconds()/(1<<20))
}

func writePattern(t *testing.T, path string, size int64) string {
	t.Helper()
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	h := sha256.New()
	w := io.MultiWriter(f, h)
	block := make([]byte, 1<<20)
	for i := range block {
		block[i] = byte(i*13 + i>>9)
	}
	for written := int64(0); written < size; {
		n := int64(len(block))
		if size-written < n {
			n = size - written
		}
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
	return hex.EncodeToString(h.Sum(nil))
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
