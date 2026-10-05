# Latchway: notes for whoever (or whatever) works on this next

Latchway sends a file directly between two phones over a WebRTC data
channel. A small rendezvous server relays only the encrypted handshake; file
bytes never touch a server. Made by Gabe Azar, GPL-3.0.

Start here, in order: this file, `docs/PROTOCOL.md` (the contract),
`docs/ROADMAP.md` (what's next), `README.md` (status table).

## Conventions that matter

- **Credit Gabe, not the tool.** Commits are authored as
  `Gabe Azar <gabeazar94@gmail.com>` (already in `.git/config` after
  `git config user.name/user.email`). Do not add AI co-author or
  "generated with" trailers to commits or PRs; Gabe asked for this.
- **The spec is the source of truth.** If code and `docs/PROTOCOL.md`
  disagree, the code is wrong. If the spec is wrong, fix the spec in the
  same change and say so. Any new implementation of the key schedule or
  framing must reproduce `testdata/vectors.json` byte for byte. Regenerate
  vectors (`go test ./internal/wire -update`) only for a deliberate protocol
  change.
- **Keep the rendezvous dumb.** It routes opaque strings. It must never
  parse `d`, persist a message, log `d`, or log share ids. Both
  implementations (Worker in `relay/`, Go in `internal/server`) must behave
  identically; the same test suite runs against both.
- **No telemetry, no third-party scripts, no new permissions** without a
  discussion. Pages keep the strict CSP and `Referrer-Policy: no-referrer`.
  The CSP forbids inline `style=` attributes and inline scripts; all
  styling goes in `web/style.css`, behaviour in `web/share.js`.
- **Brand assets are SVG first.** Edit `brand/*.svg`, run
  `npm install --no-save @resvg/resvg-js && node brand/render.mjs`, commit
  the PNGs it writes into `web/`. Palette and type are in `brand/README.md`.
  Review pages with headless Edge/Chrome before shipping; the Chrome
  extension is not needed.
- **Both rendezvous implementations and the spec move together.** A
  behaviour change touches `relay/src/index.js`, `internal/server/server.go`,
  `docs/PROTOCOL.md` and `relay/test/rendezvous.test.mjs` in one commit.

## Layout

```
docs/PROTOCOL.md          wire protocol, key schedule, threat model
docs/ROADMAP.md           milestones; browser-receiver design
docs/HOSTING.md           deploying the rendezvous (Cloudflare free plan or Docker)
docs/PLAY_STORE.md        listing checklist, data-safety answers, permissions
relay/                    Cloudflare Worker + Durable Object (plain JS, no build step)
relay/test/               behaviour suite; runs against ANY rendezvous via RELAY_URL
web/                      static pages for latchway.app (also embedded in the Go binary)
brand/                    SVG sources of the mark, app icon, social card; render.mjs → web/*.png
internal/wire             keys, proofs, envelopes, padded meta, chunk frames (Go)
internal/rendezvous       WebSocket client for hosts and joiners (Go)
internal/server           self-hostable rendezvous (Go)
internal/peer             WebRTC session layer: Host (host.go), Receive (joiner.go), e2e tests
internal/cli              prompts, size formatting, status line for the two tools
cmd/latchway-send         CLI sender; cmd/latchway-receive  CLI receiver; cmd/cli_test.go drives both
cmd/latchway-rendezvous   server binary (optional Let's Encrypt)
testdata/vectors.json     cross-implementation fixtures
hack/local.sh             only for networks that block proxy.golang.org (see below)
.github/workflows         relay (Worker tests), go (build/vet/test/behaviour/image), deploy-relay
```

## State

Done and green in CI: protocol spec; Worker rendezvous; Go rendezvous
server and client; wire layer with RFC 5869 and cross-implementation
vectors; web pages (landing, `/s/<id>`, privacy, 404); docs; Dockerfile and
GHCR image; an independent security review whose findings were all
addressed (registration keys, STUN-only joiners, bound proofs, padded meta,
per-IP caps, Origin checks, canonical ids, a cleanup race).

Also done (Milestone 1, 2026-10-04): `internal/peer` host and joiner
sessions on pion, the `latchway-send` / `latchway-receive` CLIs, and the
end-to-end tests (1 GiB with matching SHA-256 through the Go server and
through the Worker, `bad_auth` on a wrong password, `host_gone` on revoke,
`denied`, download limits). Measured on one laptop over loopback: about
50 MiB/s in-process, 39 MiB/s between the two CLIs.

**Android app (Milestone 2)**: first cut in `android/` (2026-10-05),
builds green in `android.yml`, which uploads the debug APK as an
artifact; `android/README.md` explains the structure. `android/core` is
pure JVM and passes the vectors. Signed with the committed
`android/debug.keystore` (public by design) so builds install over each
other and App Links verify for sideloads. Not yet run on a device: that
is the next step, phone-to-phone and phone-to-CLI. The laptop has no
Android SDK (ask Gabe before accepting the SDK license); a portable JDK
17 lives under `%LOCALAPPDATA%\Programs`. **Browser receiver (Milestone
3)** is not written; `docs/ROADMAP.md` has the design.

**Deployed** (2026-10-04): the Worker is live at `https://latchway.app`
(and `www`), deployed with `npx wrangler deploy` from a logged-in laptop;
the behaviour suite passes against it. **TURN is configured** (2026-10-05):
the Realtime subscription was added to the account, the TURN key
`latchway` exists, and `TURN_KEY_ID` / `TURN_KEY_API_TOKEN` are Worker
secrets; hosts receive TURN credentials in `join`. The wrangler OAuth token
cannot manage TURN keys (no Realtime scope), so anything else TURN-related
goes through the dashboard.

**TURN spend cap**: `TURN_BUDGET_GB` (900) withholds TURN once the month's
egress reaches it (`relay/src/budget.js`, `internal/server/budget.go`,
docs/HOSTING.md §3b). It needs the `CF_ANALYTICS_TOKEN` secret (API token,
Account Analytics: Read). With the budget set and no token, TURN is
withheld on purpose. Gabe wants never to exceed the free 1,000 GB.

Later: a Play Console account, and the Play signing certificate
fingerprint for `ASSETLINKS_FINGERPRINTS` in `relay/wrangler.toml`.

## Build and test

```sh
go build ./... && go vet ./... && go test -race -short ./...

# the two 1 GiB acceptance transfers (LATCHWAY_E2E_SIZE=<bytes> to shrink)
go test -run 'TestLargeFile|TestCLIRoundTrip' ./internal/peer ./cmd

# the transfer suite through another rendezvous (Worker, or the live site)
RELAY_URL=http://127.0.0.1:8788 go test ./internal/peer

# behaviour suite against the Go server
go run ./cmd/latchway-rendezvous --listen 127.0.0.1:8787 &
RELAY_URL=http://127.0.0.1:8787 node --test relay/test/*.test.mjs

# behaviour suite against the Worker
(cd relay && npm install && npx wrangler dev --port 8788 &)
RELAY_URL=http://127.0.0.1:8788 node --test relay/test/*.test.mjs
```

Node 22+ (built-in WebSocket client), Go 1.24, wrangler 4. `go.sum` is
committed; a CI step regenerates it if deps change.

`hack/local.sh` exists because the original development environment could
not reach proxy.golang.org or golang.org/x vanity imports; it builds an
alternate modfile that fetches everything from GitHub mirrors. On a normal
machine ignore it entirely.

## CI

- `relay.yml`: Worker behaviour tests under `wrangler dev`.
- `go.yml`: build, vet, race tests, behaviour suite against the Go server,
  then publishes `ghcr.io/gabeazar/latchway-rendezvous` on `main`.
- `deploy-relay.yml`: `wrangler deploy` on pushes touching `relay/` or
  `web/`; exits early until `CLOUDFLARE_API_TOKEN` and
  `CLOUDFLARE_ACCOUNT_ID` secrets exist. Run manually with "Set up TURN"
  ticked once to create the TURN key and store it as Worker secrets.
- Failures post the log tail as a **commit comment** (`.github/actions/report-log`)
  so results can be read without downloading artifacts.

## Pitfalls already paid for

- **pion** caps SCTP messages at 65 535 bytes by default; our frames are
  64 KiB + 21 bytes. `internal/peer/peer.go` sets
  `SettingEngine.SetSCTPMaxMessageSize(262144)` to match libwebrtc. Any new
  pion peer must use `newAPI()` from that file.
- **coder/websocket** `Conn.Close` blocks up to 5 s waiting for the peer's
  close frame. Never call it inline from another connection's handler
  (`conn.fail` runs it in a goroutine).
- **workerd (local wrangler dev)**: a text frame sent to a hibernatable
  WebSocket that is then closed before the 101 response is returned can be
  dropped. That's why join rejections are plain HTTP 404/429 before the
  upgrade, in both servers and the spec.
- `WebSocket.OPEN` is not guaranteed to exist in workerd; use the numeric
  `1`.
- Node's test runner wants file globs (`node --test test/*.test.mjs`), not a
  directory.
- `web/.assetsignore` keeps `embed.go` out of the Worker's static assets.
- **pion excludes loopback candidates** by default (as libwebrtc does).
  The e2e tests set `LATCHWAY_ICE_LOOPBACK=1`, which `newAPI()` honours,
  so they pass on a machine with no network. The test rendezvous hands
  out an empty ICE list so no STUN round trip happens either.
- **Once the data channel is open, ignore the rendezvous.** A joiner's
  socket is capped at 30 min; a long transfer outlives it and the host
  receives `leave` mid-stream. Both sessions only act on signaling-socket
  events before `dc.OnOpen` (PROTOCOL.md §4.4).
- **`done` can be lost to the joiner's own close.** The joiner waits up to
  5 s for the host to close after sending `done` (spec says SHOULD).
- `go get x/term@latest` bumps the `go` directive to a toolchain CI does
  not have. Keep `golang.org/x/term v0.32.0` and `x/sys v0.33.0`, which
  match Go 1.24; `GOTOOLCHAIN=local` makes such mistakes fail loudly.
- `-race` needs cgo; on a Windows laptop without gcc run the tests without
  it and let CI (Linux) do the race run.
- Durable Objects on the Workers Free plan must be SQLite-backed
  (`new_sqlite_classes`); the DO's in-memory state is lost on hibernation,
  so per-socket state lives in `serializeAttachment` and tags.
- Cloudflare TURN credentials come from
  `POST https://rtc.live.cloudflare.com/v1/turn/keys/{id}/credentials/generate-ice-servers`
  and return `{iceServers: [...]}`; the first 1,000 GB/month are free.
- Joiners must only ever receive STUN servers from the rendezvous; TURN
  travels inside the host's encrypted `go` message (security review
  finding 3).

## Residual notes from the security review

Informational items left as-is, deliberately: the rendezvous client allows
`http://` for local testing only; PBKDF2 (not Argon2) because every target
has it natively; the `latchway://` scheme fallback is interceptable by a
rogue app (documented; verified App Links are the primary path). The
review's full text is in the session that produced it, not in the repo;
its conclusions are reflected in PROTOCOL.md §1, §3.1, §3.2, §4.1 and §6.
