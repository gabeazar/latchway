# Roadmap

What exists, what's next, and how the next pieces are meant to fit. Status
of the existing pieces is in the README.

## Milestone 1: transfer sessions (Go)

`internal/peer`: the host side (serves a share: hello, proof check,
padded meta, approval, answer, chunk streaming with flow control, download
counting) and the joiner side (joins a link: proof, meta, offer, chunk
verification, progress, done), exactly as PROTOCOL.md §4 describes, on
pion/webrtc. Then `cmd/latchway-send` and `cmd/latchway-receive` as thin
CLIs around them, and an end-to-end test that runs both against
`internal/server` over loopback and compares file hashes. The Worker gets
the same test under `wrangler dev` in CI.

Acceptance: a 1 GB file crosses between the two CLIs through either
rendezvous with matching SHA-256, a wrong password yields `bad_auth`, a
revoked share yields `host_gone`, and the vectors in `testdata/` pass.

## Milestone 2: Android app

Kotlin + Jetpack Compose, libwebrtc via the `io.github.webrtc-sdk:android`
artifact. Send flow (share sheet or picker → options → link and QR),
receive flow (App Link or pasted link → file card → `ACTION_CREATE_DOCUMENT`
→ progress), foreground services for both, settings (rendezvous URL,
display name, relay-only, defaults), about page. A pure-JVM `core` module
mirrors `internal/wire` and is unit-tested against `testdata/vectors.json`.
Permissions: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
`POST_NOTIFICATIONS`, `ACCESS_NETWORK_STATE`. See `docs/PLAY_STORE.md`.

## Milestone 3: receiving in the browser, no app needed

The reason WebRTC was chosen over a plain relay: browsers already speak it.
The page at `/s/<shareId>` grows from "Open in Latchway" into a complete
receiver, so an iPhone or a laptop can take a file with nothing installed.

How it works, in terms of pieces that already exist:

- **Same protocol, same keys.** Everything in PROTOCOL.md §2 is available
  in WebCrypto: PBKDF2-HMAC-SHA256, HKDF-SHA256, HMAC-SHA256 and AES-256-GCM
  are all `SubtleCrypto` primitives. The JavaScript implementation must
  reproduce `testdata/vectors.json`; a Node test (`node --test`) does that
  with the same WebCrypto API browsers use.
- **Same rendezvous.** The page connects to `wss://<host>/v1/join/<id>` from
  the same origin. Both rendezvous implementations already admit same-origin
  `Origin` headers, send STUN only to joiners, and the host forwards TURN
  inside the encrypted `go` message.
- **Same session.** `RTCPeerConnection` with the negotiated data channel
  (`{negotiated: true, id: 0}`), the page as offerer. The chunk frames
  arrive as `ArrayBuffer` messages and are verified and decrypted one by one.
- **Saving the file** is the only browser-specific part, and it decides the
  size limits:

  | Browser | Method | Practical limit |
  |---|---|---|
  | Chrome, Edge (desktop) | File System Access API: `showSaveFilePicker()` → writable stream, decrypted chunks written straight to disk | none |
  | Chrome, Firefox (Android), Firefox (desktop) | Service worker streaming download: the page pipes decrypted chunks into a `ReadableStream` the worker serves as a download response, so the browser's own download manager writes it | none |
  | Safari (macOS, iOS, and every iOS browser) | In-memory assembly into a Blob, then an `<a download>` click | about 1–2 GB, depending on device memory |

  The page detects which path it has and tells the receiver before they
  start when a file is too large for the Safari path.
- **What it must not do:** load anything from a third party, send the
  fragment anywhere, or keep the key in storage. The existing CSP already
  enforces the first.

Sender-side nothing changes: a phone serving a share cannot tell whether
the joiner is the app or a page.

## Milestone 4: polish

- Relay-only toggle (force TURN) so a sender's IP is never revealed to the
  other party.
- Resume: the `start` field in the offer is reserved for it.
- Multiple files per share (a zip built on the fly, or a manifest with one
  session per file).
- Optional Tor routing of the phone's rendezvous connection via Orbot.
- F-Droid listing once the WebRTC dependency can be built reproducibly.
