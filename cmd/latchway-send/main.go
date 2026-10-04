// latchway-send serves one file from this machine until it has been
// downloaded, then exits. It prints the link on stdout and everything else
// on stderr, so `link=$(latchway-send file)` works in scripts.
//
//	latchway-send photo.jpg
//	latchway-send --password --downloads 3 --expire 2h backup.tar
package main

import (
	"bufio"
	"context"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/gabeazar/latchway/internal/cli"
	"github.com/gabeazar/latchway/internal/peer"
	"github.com/gabeazar/latchway/internal/rendezvous"
	"github.com/gabeazar/latchway/internal/wire"
)

func main() {
	os.Exit(run())
}

func run() int {
	fs := flag.NewFlagSet("latchway-send", flag.ContinueOnError)
	fs.SetOutput(os.Stderr)
	rz := fs.String("rendezvous", cli.EnvOr("LATCHWAY_RENDEZVOUS", "latchway.app"), "rendezvous server (host name or URL)")
	token := fs.String("token", os.Getenv("LATCHWAY_HOST_TOKEN"), "bearer token, if the rendezvous is private")
	password := fs.Bool("password", false, "protect the link with a password (prompted, or LATCHWAY_PASSWORD)")
	approve := fs.Bool("approve", false, "ask before each download")
	downloads := fs.Int("downloads", 1, "stop after this many completed downloads (0: keep serving)")
	expire := fs.Duration("expire", 0, "stop after this long, e.g. 90m (0: never)")
	from := fs.String("from", "", "name shown to the receiver")
	relayOnly := fs.Bool("relay-only", false, "always relay through TURN; never reveal this machine's address")
	name := fs.String("name", "", "file name to announce (default: the file's own)")
	quiet := fs.Bool("quiet", false, "print only the link")
	fs.Usage = func() {
		fmt.Fprintf(os.Stderr, "usage: latchway-send [flags] <file>\n\nServes <file> straight to whoever opens the link, encrypted end to end.\n\n")
		fs.PrintDefaults()
	}
	if err := fs.Parse(os.Args[1:]); err != nil {
		return 2
	}
	if fs.NArg() != 1 {
		fs.Usage()
		return 2
	}
	path := fs.Arg(0)

	base, err := rendezvous.BaseURL(*rz)
	if err != nil {
		return cli.Fail(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		return cli.Fail(err)
	}
	if info.IsDir() {
		return cli.Fail(fmt.Errorf("%s is a directory; Latchway sends one file at a time", path))
	}
	if *name == "" {
		*name = filepath.Base(path)
	}

	pw := ""
	if *password {
		pw = os.Getenv("LATCHWAY_PASSWORD")
		if pw == "" {
			pw, err = cli.AskPassword("Password for the link: ", true)
			if err != nil {
				return cli.Fail(err)
			}
		}
		if pw == "" {
			return cli.Fail(fmt.Errorf("empty password; drop --password to share without one"))
		}
	}

	share, err := wire.NewShare()
	if err != nil {
		return cli.Fail(err)
	}
	regKey, err := wire.NewRegKey()
	if err != nil {
		return cli.Fail(err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	status := cli.NewStatus(os.Stderr, *quiet)
	opts := peer.HostOptions{
		Rendezvous:   base,
		Share:        share,
		RegKey:       regKey,
		Token:        *token,
		Password:     pw,
		Meta:         peer.FileMeta{Name: *name, Size: info.Size(), From: *from},
		Open:         func() (io.ReadCloser, error) { return os.Open(path) },
		MaxDownloads: *downloads,
		RelayOnly:    *relayOnly,
		OnEvent:      status.HostEvent,
	}
	if *expire > 0 {
		opts.Expires = time.Now().Add(*expire)
	}
	if *approve {
		opts.Approve = askApproval(ctx, status)
	}
	if !*quiet {
		fmt.Fprintf(os.Stderr, "Deriving keys…\n")
	}
	host, err := peer.NewHost(opts)
	if err != nil {
		return cli.Fail(err)
	}

	fmt.Fprintln(os.Stdout, host.Link())
	if !*quiet {
		fmt.Fprintf(os.Stderr, "\nSharing %s (%s)", *name, cli.Bytes(info.Size()))
		if pw != "" {
			fmt.Fprint(os.Stderr, ", password protected")
		}
		if *downloads == 1 {
			fmt.Fprint(os.Stderr, ", one download")
		} else if *downloads > 1 {
			fmt.Fprintf(os.Stderr, ", %d downloads", *downloads)
		}
		if *expire > 0 {
			fmt.Fprintf(os.Stderr, ", for %s", expire.Round(time.Second))
		}
		fmt.Fprint(os.Stderr, ". Press Ctrl-C to stop.\n")
	}

	err = host.Run(ctx)
	status.Finish()
	switch {
	case err == nil:
		if !*quiet {
			fmt.Fprintf(os.Stderr, "Done: %d download(s) completed.\n", host.Completed())
		}
		return 0
	case ctx.Err() != nil:
		if !*quiet {
			fmt.Fprintf(os.Stderr, "Stopped; the link is no longer valid.\n")
		}
		return 0
	case peer.IsCode(err, wire.ErrExpired):
		if !*quiet {
			fmt.Fprintf(os.Stderr, "The share expired after %d download(s).\n", host.Completed())
		}
		return 0
	default:
		return cli.Fail(err)
	}
}

// askApproval reads y/n from the terminal for each receiver. It answers no
// when stdin is not interactive.
func askApproval(ctx context.Context, status *cli.Status) func(context.Context, string) bool {
	in := bufio.NewReader(os.Stdin)
	return func(sctx context.Context, sid string) bool {
		status.Pause()
		defer status.Resume()
		fmt.Fprintf(os.Stderr, "\nSomeone wants the file. Send it? [y/N] ")
		answer := make(chan string, 1)
		go func() {
			line, _ := in.ReadString('\n')
			answer <- strings.ToLower(strings.TrimSpace(line))
		}()
		select {
		case a := <-answer:
			return a == "y" || a == "yes"
		case <-sctx.Done():
			return false
		case <-ctx.Done():
			return false
		}
	}
}
