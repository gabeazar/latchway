<p align="center">
  <img src="web/latch.svg" width="96" height="96" alt="">
</p>

<h1 align="center">Latchway</h1>

<p align="center">Send a big file from your phone straight to a friend's with a link.<br>
Encrypted end to end, no accounts, no cloud copy. Open source.</p>

---

Latchway turns a phone into the server for a single file. You pick a file,
get a link, and send the link any way you like. When your friend opens it,
their phone connects to yours and the file crosses directly between them.
Nothing is uploaded anywhere first, there is no size limit beyond what the
two phones can hold, and the only thing that can decrypt the file is the
link itself.

Made by [Gabe Azar](https://github.com/gabeazar). Licensed under the
[GPL-3.0](LICENSE).

## How it works

```
 your phone ──── encrypted handshake ────▶ latchway.app ◀──── friend's phone
       │                                  (rendezvous)             │
       └══════════ the file, encrypted, phone to phone ════════════┘
```

1. The app generates a random share id and a random 256-bit secret. The
   link is `https://latchway.app/s/<id>#<secret>`. Browsers never send the
   part after `#` to any server, so the secret stays between the two
   phones.
2. Your phone keeps one small connection open to the **rendezvous**, which
   exists only to introduce two phones that want to meet.
3. When the link is opened, both phones derive the same keys from the
   secret (and the optional password), prove to each other that they hold
   it, and exchange an **encrypted** WebRTC handshake through the
   rendezvous, which relays bytes it cannot read.
4. The file streams over a WebRTC data channel, directly between the
   phones, as 64 KiB chunks each sealed with AES-256-GCM. When two networks
   refuse a direct path, the encrypted stream falls back to a TURN relay.

The full specification, including the key schedule and the threat model, is
in [`docs/PROTOCOL.md`](docs/PROTOCOL.md). What each party can and cannot
see is spelled out in the [privacy policy](web/privacy.html).

### Controls the sender has

- **Password** on the link, folded into the encryption key, so the server
  can't check it and your phone does.
- **Approve each download** by hand before anything is sent.
- **Expiry** by time or by number of downloads.
- **Stop** at any moment; the link dies instantly.
- **Relay only**, to never reveal your IP address to the other side.

## Project status

This repository is being built in the open. Current state:

| Piece | Status |
|---|---|
| Protocol specification (`docs/PROTOCOL.md`) | Complete |
| Rendezvous server, Cloudflare Worker (`relay/`) | Complete, tested |
| Rendezvous server, self-hostable Go binary (`cmd/latchway-rendezvous`) | Complete, tested |
| Key schedule, proofs, envelopes, chunk framing in Go (`internal/wire`) | Complete, tested, with cross-implementation vectors |
| Web pages for `latchway.app` (`web/`) | Complete |
| Transfer sessions: sender and receiver over WebRTC (`internal/peer`) | Complete, tested end to end over loopback (1 GiB, wrong password, revocation) |
| Desktop command-line sender/receiver (`cmd/latchway-send`, `cmd/latchway-receive`) | Complete |
| Public rendezvous at `latchway.app` | Live |
| Android app (`android/`) | **Next** (`docs/ROADMAP.md` Milestone 2) |
| Receiving in the browser | Designed (`docs/ROADMAP.md` Milestone 3) |

The order of work from here is in [`docs/ROADMAP.md`](docs/ROADMAP.md).
The `wire` and `peer` packages plus
[`testdata/vectors.json`](testdata/vectors.json) give the Android and
browser implementations a tested reference to match. Contributions are
welcome; see the contributing notes below.

## Repository layout

```
docs/PROTOCOL.md         the wire protocol, single source of truth
docs/HOSTING.md          deploying the rendezvous (Cloudflare free plan, or Docker)
docs/ROADMAP.md          what comes next, including receiving in the browser
relay/                   Cloudflare Worker rendezvous (plain JavaScript)
web/                     static pages served at latchway.app
internal/wire            keys, proofs, encrypted envelopes, chunk frames (Go)
internal/rendezvous      WebSocket client for the rendezvous protocol (Go)
internal/server          self-hostable rendezvous server (Go)
internal/peer            WebRTC session layer: Host and Receive (Go)
internal/cli             prompts and status line shared by the two tools
cmd/latchway-send        command-line sender
cmd/latchway-receive     command-line receiver
cmd/latchway-rendezvous  rendezvous server binary
testdata/vectors.json    fixtures every implementation must reproduce
```

## Sending a file from the command line

```sh
go install github.com/gabeazar/latchway/cmd/latchway-send@latest
go install github.com/gabeazar/latchway/cmd/latchway-receive@latest

latchway-send holiday.mp4
# prints https://latchway.app/s/<id>#<secret> and waits for one download

latchway-receive 'https://latchway.app/s/<id>#<secret>'
# saves holiday.mp4 in the current directory
```

`latchway-send --password` protects the link with a password that the
receiver is asked for, `--approve` asks you before each download,
`--downloads 3` and `--expire 2h` limit the share, `--relay-only` hides
your address from the other side, and `--rendezvous` points both tools at
your own server. Quote the link: the `#` means something to most shells.

## Running the pieces

Rendezvous server, locally:

```sh
go run ./cmd/latchway-rendezvous --listen 127.0.0.1:8787
# then open http://127.0.0.1:8787/
```

Behaviour tests (they only need a URL, so they run against either
implementation):

```sh
RELAY_URL=http://127.0.0.1:8787 node --test relay/test/*.test.mjs
```

Go tests, including the RFC 5869 HKDF vectors, the cross-implementation
fixtures and real transfers over loopback (`-short` skips the two 1 GiB
ones):

```sh
go test -short ./...
go test -run 'TestLargeFile|TestCLIRoundTrip' ./internal/peer ./cmd
```

Cloudflare Worker, locally (needs `npm install` in `relay/`):

```sh
cd relay && npx wrangler dev
```

Deploying for real is described in [`docs/HOSTING.md`](docs/HOSTING.md).

## Contributing

- Read `docs/PROTOCOL.md` first. If code and spec disagree, the code is
  wrong; if the spec is wrong, change the spec in the same pull request.
- Any new implementation of the key schedule or framing must pass
  `testdata/vectors.json`. Regenerate the vectors only for a deliberate
  protocol change (`go test ./internal/wire -update`) and say so.
- Keep the rendezvous dumb. It routes opaque strings; it must never gain
  the ability to read or store anything about a transfer.
- No telemetry, no third-party scripts, no new permissions without a
  discussion first.

Security issues: see [SECURITY.md](SECURITY.md).
