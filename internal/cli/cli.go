// Package cli holds the small pieces the two command-line tools share:
// terminal prompts, size formatting and a one-line status display.
package cli

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"

	"golang.org/x/term"

	"github.com/gabeazar/latchway/internal/peer"
)

// EnvOr returns the environment variable or a default.
func EnvOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

// Fail prints an error the way the tools report failures and returns the
// exit status to use.
func Fail(err error) int {
	fmt.Fprintf(os.Stderr, "latchway: %v\n", err)
	return 1
}

// AskPassword prompts on the terminal without echo. With confirm set it
// asks twice and insists the answers match. Without a terminal it reads one
// line from stdin.
func AskPassword(prompt string, confirm bool) (string, error) {
	fd := int(os.Stdin.Fd())
	if !term.IsTerminal(fd) {
		line, err := bufio.NewReader(os.Stdin).ReadString('\n')
		if err != nil && !errors.Is(err, io.EOF) {
			return "", err
		}
		return strings.TrimRight(line, "\r\n"), nil
	}
	for {
		fmt.Fprint(os.Stderr, prompt)
		b, err := term.ReadPassword(fd)
		fmt.Fprintln(os.Stderr)
		if err != nil {
			return "", err
		}
		if !confirm {
			return string(b), nil
		}
		fmt.Fprint(os.Stderr, "Again, to be sure: ")
		b2, err := term.ReadPassword(fd)
		fmt.Fprintln(os.Stderr)
		if err != nil {
			return "", err
		}
		if string(b) == string(b2) {
			return string(b), nil
		}
		fmt.Fprintln(os.Stderr, "They don't match; try again.")
	}
}

// Bytes formats a size for humans. Negative means unknown.
func Bytes(n int64) string {
	if n < 0 {
		return "unknown size"
	}
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(n)/float64(div), "KMGTPE"[exp])
}

// Status renders transfer events as a single updating line on a terminal,
// or as plain lines when the output is not one.
type Status struct {
	w      io.Writer
	quiet  bool
	tty    bool
	mu     sync.Mutex
	paused bool
	line   string
	start  time.Time
	last   time.Time
}

// NewStatus creates a status display writing to w.
func NewStatus(w io.Writer, quiet bool) *Status {
	tty := false
	if f, ok := w.(*os.File); ok {
		tty = term.IsTerminal(int(f.Fd()))
	}
	return &Status{w: w, quiet: quiet, tty: tty}
}

func (s *Status) set(line string) {
	if s.quiet {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.paused {
		return
	}
	if s.tty {
		fmt.Fprintf(s.w, "\r\033[2K%s", line)
		s.line = line
		return
	}
	// Non-interactive: print only when the text changes, at most twice a
	// second for progress.
	if line == s.line {
		return
	}
	now := time.Now()
	if strings.HasPrefix(line, "Sending") || strings.HasPrefix(line, "Receiving") {
		if now.Sub(s.last) < 500*time.Millisecond {
			return
		}
	}
	s.last = now
	s.line = line
	fmt.Fprintln(s.w, line)
}

func (s *Status) println(msg string) {
	if s.quiet {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.tty && s.line != "" {
		fmt.Fprint(s.w, "\r\033[2K")
		s.line = ""
	}
	fmt.Fprintln(s.w, msg)
}

// Pause clears the line so a prompt can be shown; Resume lets updates
// continue.
func (s *Status) Pause() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.tty && s.line != "" {
		fmt.Fprint(s.w, "\r\033[2K")
		s.line = ""
	}
	s.paused = true
}

// Resume re-enables updates after Pause.
func (s *Status) Resume() {
	s.mu.Lock()
	s.paused = false
	s.mu.Unlock()
}

// Finish ends the current line, if any.
func (s *Status) Finish() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.tty && s.line != "" {
		fmt.Fprintln(s.w)
		s.line = ""
	}
}

func (s *Status) progress(verb string, e peer.Event) string {
	if s.start.IsZero() {
		s.start = time.Now()
	}
	elapsed := time.Since(s.start).Seconds()
	rate := ""
	if elapsed > 0.5 {
		rate = fmt.Sprintf(" at %s/s", Bytes(int64(float64(e.Bytes)/elapsed)))
	}
	if e.Total > 0 {
		pct := float64(e.Bytes) * 100 / float64(e.Total)
		return fmt.Sprintf("%s %s of %s (%.0f%%)%s", verb, Bytes(e.Bytes), Bytes(e.Total), pct, rate)
	}
	return fmt.Sprintf("%s %s%s", verb, Bytes(e.Bytes), rate)
}

// HostEvent is an OnEvent callback for peer.Host.
func (s *Status) HostEvent(e peer.Event) {
	switch e.Kind {
	case peer.EvRegistered:
		if e.Err != nil {
			s.println(fmt.Sprintf("Rendezvous connection lost (%v); retrying…", e.Err))
		} else {
			s.set("Waiting for someone to open the link…")
		}
	case peer.EvJoined:
		s.set("Someone opened the link; checking the key…")
	case peer.EvAuthFailed:
		s.println("A receiver tried a wrong password.")
	case peer.EvApprovalWait:
	case peer.EvDenied:
		s.println("Declined (" + e.Code + ").")
	case peer.EvConnecting:
		s.set("Connecting to the receiver…")
	case peer.EvConnected:
		s.start = time.Now()
		s.set("Connected; sending…")
	case peer.EvProgress:
		s.set(s.progress("Sending", e))
	case peer.EvDone:
		s.println(fmt.Sprintf("Sent %s in %s.", Bytes(e.Bytes), time.Since(s.start).Round(time.Second)))
		s.set("Waiting for someone to open the link…")
	case peer.EvFailed:
		s.println(fmt.Sprintf("Transfer failed: %v", e.Err))
		s.set("Waiting for someone to open the link…")
	case peer.EvLeft:
		s.println("The receiver left before the transfer finished.")
		s.set("Waiting for someone to open the link…")
	case peer.EvStopped:
	}
}

// ReceiveEvent is an OnEvent callback for peer.Receive.
func (s *Status) ReceiveEvent(e peer.Event) {
	switch e.Kind {
	case peer.EvJoined:
		s.set("Connected to the rendezvous; waiting for the sender…")
	case peer.EvConnecting:
		s.set("Connecting to the sender…")
	case peer.EvConnected:
		s.start = time.Now()
		s.set("Connected; receiving…")
	case peer.EvProgress:
		s.set(s.progress("Receiving", e))
	case peer.EvDone:
		s.println(fmt.Sprintf("Received %s in %s.", Bytes(e.Bytes), time.Since(s.start).Round(time.Second)))
	}
}
