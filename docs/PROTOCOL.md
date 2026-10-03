# Latchway Protocol v1

This document is the single source of truth for every Latchway implementation
(Android app, Go CLI, Cloudflare Worker and Go rendezvous servers, and the
future browser receiver). If code and this document disagree, the code is
wrong.

Latchway moves a file **directly between two devices** over a WebRTC data
channel. A small **rendezvous** server only relays the encrypted connection
handshake; it never carries file bytes. When a direct path cannot be
punched through two NATs, the data channel falls back to a TURN relay, which
sees only ciphertext.

```
 Sender (host)                 Rendezvous                 Receiver (joiner)
 ───────────────               ───────────                ─────────────────
 register share  ──── WS ────▶ Durable Object ◀──── WS ──── open link
                 ◀── join ────       │        ── joined ──▶
 hello           ────────────▶       │        ────────────▶
                 ◀────────────       │        ◀──────────── auth proof
 enc(meta, go)   ────────────▶       │        ────────────▶
                 ◀────────────       │        ◀──────────── enc(offer, ICE)
 enc(answer,ICE) ────────────▶       │        ────────────▶
                                     │
 ══════════════ WebRTC data channel (DTLS, direct or via TURN) ═════════════
 AES-GCM chunk 0, 1, 2 … last  ─────────────────────────────▶
                 ◀───────────────────────────────────────── progress, done
```

All multi-byte integers are big-endian. `b64url` means base64url **without
padding** (RFC 4648 §5). Strings are UTF-8. JSON messages are objects with a
string field `t` (type).

---

## 1. Identifiers and the link

| Name      | Bytes | Encoding      | Visibility                                 |
|-----------|-------|---------------|--------------------------------------------|
| `shareId` | 16    | b64url (22)   | Public. Seen by the rendezvous.            |
| `secret`  | 32    | b64url (43)   | Private. Lives only in the URL fragment.   |

Both are generated with a cryptographically secure RNG when a share is
created.

Link forms:

```
https://<rendezvous-host>/s/<shareId>#<secret>
latchway://<rendezvous-host>/s/<shareId>#<secret>
```

Browsers never send the fragment (`#…`) to any server, so the secret never
reaches the rendezvous or any web server. Implementations MUST NOT place
the secret anywhere other than the fragment, MUST NOT log it, and MUST set
`Referrer-Policy: no-referrer` on any page that serves a link.

The `https` form is the primary one: with Android App Links verified
against `/.well-known/assetlinks.json`, only the genuine app receives it.
The `latchway://` form is a fallback for devices where verification has
not happened; custom schemes are not verified by the OS, so a malicious app
claiming the scheme could intercept such a link. Pages offering the
fallback should say so.

`shareId` strings are compared byte for byte. Servers MUST reject an id that
does not re-encode to the same 22 characters after decoding (non-canonical
padding bits), so that one share cannot be addressed under two spellings.

The link does not reveal whether a password is set, although the sender's
`hello` message (§4.1) does, to anyone who connects.

## 2. Key derivation

```
pwk  = PBKDF2-HMAC-SHA256(NFC(password), salt = "latchway/v1/pw" || shareId, iter = 600000, len = 32)
       or 32 zero bytes when no password is set
ikm  = secret || pwk                                        (64 bytes)
prk  = HKDF-Extract(SHA-256, salt = "latchway/v1" || shareId, ikm)

K_auth = HKDF-Expand(prk, info = "latchway/v1/auth", 32)
K_root = HKDF-Expand(prk, info = "latchway/v1/root", 32)
```

`shareId` here means the raw 16 bytes. `NFC(password)` is the password
normalised to Unicode NFC before UTF-8 encoding; implementations MUST
normalise so that the same password typed on different keyboards derives
the same key.

PBKDF2 rather than a memory-hard KDF is a deliberate trade-off: it is
available natively on every target (javax.crypto, WebCrypto, Go) with no
extra dependency to audit. The password's protection against offline
guessing rests primarily on §3.1, which ensures a receiver's proof can only
ever reach the genuine sender, and on the online throttle in §4.1.

Per session (see §4), with `hostNonce` and `joinerNonce` of 16 bytes each:

```
sprk   = HKDF-Extract(SHA-256, salt = "latchway/v1/session" || hostNonce || joinerNonce, ikm = K_root)
S_sig  = HKDF-Expand(sprk, info = "latchway/v1/s/sig",  32)
S_file = HKDF-Expand(sprk, info = "latchway/v1/s/file", 32)
```

Every session therefore has fresh encryption keys even though the link is
reused, which keeps AES-GCM nonces unique by construction.

Test vectors for every step are in `testdata/vectors.json` and are checked
by the Go, Kotlin and JavaScript test suites.

## 3. Rendezvous protocol

Transport: WebSocket, text frames carrying JSON. The rendezvous is a dumb
router. It parses only `t` and `sid`; everything in `d` is opaque to it.

### 3.1 Host registration

```
GET wss://<host>/v1/host?share=<shareId>
Authorization: Bearer <token>        (only if the server requires one; header only, never a query parameter)
```

The host's first message is its registration:

```json
{"t":"register","reg":"<b64url 32 bytes>"}
```

`reg` is a **registration key**: 32 random bytes drawn by the sender's
device when the share is created and kept only on that device for the
share's lifetime. It is not derived from the link, so holding the link
gives nobody the ability to pose as the sender. The server stores
`SHA-256(reg)` against the `shareId` (never `reg` itself) and replies
`{"t":"ready"}`.

- A registration for a `shareId` whose stored hash matches **replaces** the
  live host, if any: the old connection receives
  `{"t":"error","code":"replaced"}` and is closed, and its joiners receive
  `host_gone`. This is how a phone that changed networks resumes its share.
- A registration whose hash does not match the stored one is refused with
  `{"t":"error","code":"forbidden"}`, whether or not a host is live.
- The binding expires 30 days after the most recent registration. A server
  that loses its bindings (the Go binary keeps them in memory) reopens
  those ids to whoever registers first; operators who care should keep the
  binary running or front it with the Worker.

Without this, anyone who learned a `shareId` (link-preview bots, browser
history, server logs) could register as the host and collect a receiver's
password proof for offline guessing, or simply knock the real host off.

Servers SHOULD rate-limit registrations per IP address.

The host keeps this connection open for the lifetime of the share and
sends the keepalive `{"t":"ping"}` at least every 30 s; the server answers
`{"t":"pong"}` (without waking a hibernated Durable Object). Joiners may
use the same keepalive.

### 3.2 Joiner connection

```
GET wss://<host>/v1/join/<shareId>
```

If no host is registered, the upgrade is refused with HTTP **404**; if the
joiner caps (§3.4) are reached, with HTTP **429**. No socket is opened in
either case. (Browsers cannot read the status of a failed upgrade; a web
client checks `/v1/status/<shareId>` first to tell the two apart.)
Otherwise the upgrade succeeds and:

- server → joiner: `{"t":"joined","sid":"<b64url 12 bytes>","ice":[…]}`
- server → host:   `{"t":"join","sid":"<same sid>","ice":[…]}`

`ice` is an array of RTCIceServer objects (`{"urls":[…],"username":…,
"credential":…}`). The joiner's list contains **STUN servers only**: a
joiner has proven nothing yet, and TURN credentials cost money. The host's
list additionally contains short-lived TURN credentials when the server has
them configured; the host passes them to the joiner inside the encrypted
`go` message (§4.3), after the proof.

Servers SHOULD require the WebSocket `Origin` header, when present, to match
their own origin, so that web pages cannot make visitors' browsers join
shares. Native clients send no `Origin`. Servers SHOULD also cap concurrent
joiners per IP address.

### 3.3 Relaying

Either side sends `{"t":"sig","sid":"…","d":"<string>"}`. The server
forwards it to the other party of that `sid` unchanged (it MAY add or
overwrite `sid` on messages from a joiner, who has only one session).

`{"t":"leave","sid":"…"}` from the host closes that joiner's socket with
`{"t":"error","code":"closed"}`. When a joiner disconnects the host receives
`{"t":"leave","sid":"…"}`. When the host disconnects, every joiner receives
`{"t":"error","code":"host_gone"}` and is closed.

### 3.4 Limits (normative minimums)

| Limit                                   | Value          |
|-----------------------------------------|----------------|
| `d` payload size                        | ≤ 16 KiB       |
| `sig` messages per session per direction| ≤ 256          |
| concurrent joiners per share            | ≤ 16           |
| joiner lifetime without a `sig`         | ≤ 120 s        |
| joiner lifetime total                   | ≤ 30 min       |

Exceeding a size or count limit yields `{"t":"error","code":"rate_limited"}`
and the lifetime limits yield `"timeout"`, each followed by a close; the
joiner caps are enforced before the upgrade (HTTP 429, above). Hosts that send nothing (not even the
keepalive) for 90 s MAY be disconnected. The server MUST NOT persist any
message, MUST NOT log `d`, and SHOULD NOT log `shareId`.

### 3.5 Other HTTP endpoints

| Path                               | Purpose                                      |
|------------------------------------|----------------------------------------------|
| `GET /s/<shareId>`                 | Landing page ("Open in Latchway" / get the app) |
| `GET /v1/status/<shareId>`         | `{"active":true|false}`                       |
| `GET /.well-known/assetlinks.json` | Android App Links verification                |
| `GET /healthz`                     | `200 ok`                                      |

## 4. Session protocol

Session messages travel inside the `d` field of `sig` messages as JSON
strings. The first two are plaintext; everything after is encrypted.

### 4.1 Hello and proof (plaintext)

Host → joiner, immediately after `join`:

```json
{"t":"hello","v":1,"pw":false,"n":"<b64url hostNonce 16 bytes>"}
```

`pw` tells the joiner whether to prompt for a password before deriving
keys. `hostNonce` MUST be freshly drawn for every session; the replay and
nonce-uniqueness arguments below depend on it.

Joiner → host:

```json
{"t":"auth","n":"<b64url joinerNonce 16 bytes>","p":"<b64url proof>"}
proof = HMAC-SHA256(K_auth, "latchway/v1/proof" || v(1 byte) || pw(1 byte: 0x00/0x01) || hostNonce || joinerNonce)
```

Binding `v` and `pw` into the proof means a relay that flips either field
causes a clean `bad_auth` rather than a misdiagnosed failure, and rules
out version downgrades once a v2 exists.

The host compares in constant time. On failure it replies
`{"t":"error","code":"bad_auth"}` (plaintext) and sends `leave` for the
session. Hosts MUST throttle online guessing without punishing other
receivers: after 5 failed proofs within 10 minutes, the host answers
further proofs for that share no sooner than 10 s after receiving them.
Because the proof is bound to both nonces it cannot be replayed.

The joiner authenticates the host implicitly: only a party knowing
`K_root` can produce the encrypted messages that follow, and §3.1 ensures
only the originating device can be registered as the host. A joiner that
receives an undecryptable message MUST abort with "this link is not valid".
After the joiner has received its first `enc` message, plaintext messages
on the session carry no meaning and MUST be ignored; before that, the only
meaningful plaintext message from the host is `error`.

### 4.2 Encrypted envelope

After a successful proof both sides derive `S_sig` and `S_file` (§2).
Every further signaling message is:

```json
{"t":"enc","c":"<b64url ciphertext>"}
ciphertext = AES-256-GCM(key = S_sig, nonce = dir(1) || 0x00×3 || counter(8), aad = "latchway/v1/sig", plaintext = JSON)
```

`dir` is `0x00` for host→joiner and `0x01` for joiner→host. Each direction
keeps its own `counter` starting at 0 and incrementing by exactly 1 per
message. Receivers MUST reject any message whose counter is not the next
expected value.

### 4.3 Encrypted messages

| Direction      | Message                                                                                                  |
|----------------|----------------------------------------------------------------------------------------------------------|
| host → joiner  | `{"t":"meta","name":"…","size":123,"mime":"…","chunk":65536,"from":"Gabe","approval":true,"pad":"…"}` `size` is -1 when unknown. `from` is optional. `approval` means the joiner should show "waiting for the sender to approve". `chunk` MUST be 65536 in v1; receivers reject other values. `pad` is spaces, sized so the serialised JSON is exactly 1024 bytes (names longer than fit are truncated to 255 bytes first), so the ciphertext length reveals nothing about the file name or size. |
| host → joiner  | `{"t":"go","ice":[…]}` — the host is ready to answer an offer (sent immediately when no approval is required). `ice` is the host's full ICE server list from `join`, TURN included; the joiner MUST use it in place of the STUN-only list from `joined`. |
| host → joiner  | `{"t":"error","code":"denied"|"expired"|"busy"}`                                                         |
| joiner → host  | `{"t":"offer","sdp":"…","start":0}` — `start` is the first chunk index wanted (0 in v1; reserved for resume). |
| host → joiner  | `{"t":"answer","sdp":"…"}`                                                                                |
| both           | `{"t":"ice","cand":"…","mid":"…","mline":0}` and `{"t":"ice-done"}`                                       |
| both           | `{"t":"error","code":"…"}`                                                                                |

The joiner is always the WebRTC **offerer**. The SDP (and therefore the DTLS
fingerprint) is authenticated by `S_sig`, so a hostile rendezvous cannot
substitute its own fingerprint and man-in-the-middle the data channel.

### 4.4 Data channel

Both sides create the channel with `negotiated: true, id: 0, ordered: true,
reliable` and label `"latchway"`. A frame is at most 65 557 bytes (header +
64 KiB + tag). Every implementation MUST advertise and accept SCTP messages
of at least 262 144 bytes (`a=max-message-size`), which is libwebrtc's and
the browsers' default; pion-based implementations must raise their default
of 65 536.

**Binary frames** (host → joiner) carry one encrypted chunk each:

```
index  u32   chunk index, starting at 0, strictly sequential
flags  u8    bit0 = last chunk
body   …     AES-256-GCM(key = S_file,
                          nonce = 0x00×8 || index(4),
                          aad   = "latchway/v1/chunk" || index(4) || flags(1),
                          plaintext = up to `chunk` bytes of the file)
```

Every chunk except the last is exactly `chunk` bytes of plaintext. The last
chunk may be shorter, including zero bytes for an empty file. The receiver
MUST reject a frame whose `index` is not the next expected one, MUST stop at
the first frame with `last`, and when `size` was known MUST verify the total
plaintext length equals `size`.

**Text frames** are JSON control messages:

| Direction      | Message                                   |
|----------------|-------------------------------------------|
| joiner → host  | `{"t":"progress","bytes":N}` about every MiB |
| joiner → host  | `{"t":"done"}` after the last chunk verified |
| both           | `{"t":"abort","reason":"…"}`              |

Flow control: the host waits while `bufferedAmount` exceeds 1 MiB and
resumes below 256 KiB. The host counts a download as completed only on
`done`, then closes the peer connection.

## 5. Error codes

`not_found`, `host_gone`, `replaced`, `forbidden`, `closed`, `bad_auth`,
`denied`, `expired`, `busy`, `rate_limited`, `timeout`, `protocol`,
`internal`.

## 6. What each party learns

| Party       | Learns                                                        | Never learns                         |
|-------------|---------------------------------------------------------------|--------------------------------------|
| Rendezvous  | `shareId`, both IP addresses, timing, whether a password is set, `SHA-256(reg)` | secret, keys, file name, size, bytes |
| TURN relay  | both IP addresses, traffic volume (fallback only)              | anything inside DTLS                 |
| Receiver    | the file; the sender's IP on a direct connection (see below)   | the sender's IP when TURN was used   |
| Sender      | the receiver's progress; the receiver's IP on a direct path    | —                                    |

Peer-to-peer means the two devices exchange IP addresses to connect
directly. A user who wants to hide their IP from the other party can enable
**relay only** mode, which forces every candidate through TURN, or use a
VPN. The default favours speed.

## 7. Why encrypt twice

DTLS already encrypts the data channel hop by hop. The Latchway layer on top
exists because (a) it binds the content to the link secret, so a rendezvous
that lies about SDP gains nothing, (b) it makes the password a real second
factor rather than a server-side check, and (c) the same chunk format will
be decrypted by WebCrypto in the browser receiver, so one key schedule
serves every mode. AES-GCM runs at hundreds of MB/s on phones; the cost is
negligible next to the network.
