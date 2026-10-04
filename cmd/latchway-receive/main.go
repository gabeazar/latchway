// latchway-receive downloads the file behind a Latchway link into the
// current directory (or --out), straight from the sender's device.
//
//	latchway-receive 'https://latchway.app/s/<id>#<secret>'
//	latchway-receive --out ~/Downloads --password 'https://…'
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"

	"github.com/gabeazar/latchway/internal/cli"
	"github.com/gabeazar/latchway/internal/peer"
	"github.com/gabeazar/latchway/internal/rendezvous"
	"github.com/gabeazar/latchway/internal/wire"
)

func main() {
	os.Exit(run())
}

func run() int {
	fs := flag.NewFlagSet("latchway-receive", flag.ContinueOnError)
	fs.SetOutput(os.Stderr)
	out := fs.String("out", ".", "destination directory or file path (\"-\" for stdout)")
	rz := fs.String("rendezvous", os.Getenv("LATCHWAY_RENDEZVOUS"), "override the rendezvous named in the link (local testing)")
	password := fs.Bool("password", false, "the link has a password (prompted, or LATCHWAY_PASSWORD)")
	force := fs.Bool("force", false, "overwrite an existing file")
	relayOnly := fs.Bool("relay-only", false, "always relay through TURN; never reveal this machine's address")
	quiet := fs.Bool("quiet", false, "no progress output")
	fs.Usage = func() {
		fmt.Fprintf(os.Stderr, "usage: latchway-receive [flags] <link>\n\nDownloads the file behind a Latchway link, straight from the sender.\n\n")
		fs.PrintDefaults()
	}
	if err := fs.Parse(os.Args[1:]); err != nil {
		return 2
	}
	if fs.NArg() != 1 {
		fs.Usage()
		return 2
	}

	host, share, err := wire.ParseLink(fs.Arg(0))
	if err != nil {
		return cli.Fail(err)
	}
	opts := peer.ReceiveOptions{Share: share, Host: host, RelayOnly: *relayOnly}
	if *rz != "" {
		opts.Rendezvous, err = rendezvous.BaseURL(*rz)
		if err != nil {
			return cli.Fail(err)
		}
	}
	if *password {
		opts.Password = os.Getenv("LATCHWAY_PASSWORD")
	}
	opts.AskPassword = func(context.Context) (string, error) {
		if p := os.Getenv("LATCHWAY_PASSWORD"); p != "" {
			return p, nil
		}
		return cli.AskPassword("This link has a password: ", false)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	status := cli.NewStatus(os.Stderr, *quiet)
	opts.OnEvent = status.ReceiveEvent

	var final, partial string
	toStdout := *out == "-"
	opts.Accept = func(ctx context.Context, meta peer.FileMeta, approval bool) (io.WriteCloser, error) {
		if !*quiet {
			who := ""
			if meta.From != "" {
				who = " from " + meta.From
			}
			fmt.Fprintf(os.Stderr, "Receiving %s (%s)%s\n", meta.Name, cli.Bytes(meta.Size), who)
			if approval {
				fmt.Fprintf(os.Stderr, "Waiting for the sender to approve…\n")
			}
		}
		if toStdout {
			return nopCloser{os.Stdout}, nil
		}
		final, err = destination(*out, meta.Name)
		if err != nil {
			return nil, err
		}
		if _, err := os.Stat(final); err == nil && !*force {
			return nil, fmt.Errorf("%s exists; use --force to overwrite", final)
		}
		partial = final + ".part"
		return os.OpenFile(partial, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	}

	res, err := peer.Receive(ctx, opts)
	status.Finish()
	if err != nil {
		if partial != "" {
			os.Remove(partial)
		}
		return cli.Fail(explain(err))
	}
	if !toStdout {
		if err := os.Rename(partial, final); err != nil {
			return cli.Fail(err)
		}
		if !*quiet {
			fmt.Fprintf(os.Stderr, "Saved %s (%s)\n", final, cli.Bytes(res.Bytes))
		}
	}
	return 0
}

// destination resolves --out plus the announced name into a path, keeping
// the name to a single safe component.
func destination(out, name string) (string, error) {
	name = sanitize(name)
	if info, err := os.Stat(out); err == nil && info.IsDir() {
		return filepath.Join(out, name), nil
	}
	if strings.HasSuffix(out, string(os.PathSeparator)) || strings.HasSuffix(out, "/") {
		if err := os.MkdirAll(out, 0o755); err != nil {
			return "", err
		}
		return filepath.Join(out, name), nil
	}
	return out, nil
}

// sanitize keeps only the last path component of a sender-chosen name and
// refuses to let it climb out of the destination.
func sanitize(name string) string {
	name = strings.ReplaceAll(name, "\\", "/")
	name = name[strings.LastIndex(name, "/")+1:]
	name = strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f || strings.ContainsRune(`<>:"|?*`, r) {
			return '_'
		}
		return r
	}, name)
	name = strings.TrimSpace(name)
	if name == "" || name == "." || name == ".." {
		return "download"
	}
	return name
}

func explain(err error) error {
	switch {
	case errors.Is(err, peer.ErrInvalidLink):
		return errors.New("this link is not valid: the key after # does not match the file being served")
	case peer.IsCode(err, wire.ErrBadAuth):
		return errors.New("wrong password")
	case peer.IsCode(err, wire.ErrNotFound):
		return errors.New("the sender is not online; Latchway needs to be running on their device")
	case peer.IsCode(err, wire.ErrHostGone):
		return errors.New("the sender went offline before the transfer finished")
	case peer.IsCode(err, wire.ErrDenied):
		return errors.New("the sender declined")
	case peer.IsCode(err, wire.ErrExpired):
		return errors.New("this link has expired")
	case peer.IsCode(err, wire.ErrBusy):
		return errors.New("the sender is busy with another download; try again shortly")
	case peer.IsCode(err, wire.ErrTimeout):
		return errors.New("timed out waiting for the sender")
	}
	return err
}

type nopCloser struct{ io.Writer }

func (nopCloser) Close() error { return nil }
