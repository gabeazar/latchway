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

The link does not reveal whether a password is set.

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
Authorization: Bearer <token>        (only if the server requires one)
```

Server → host on success: `{"t":"ready"}`.

A new registration for a `shareId` that already has a live host **replaces**
it; the old connection receives `{"t":"error","code":"replaced"}` and is
closed. This lets a phone that changed networks resume its share with the
same link. (It is safe: anyone who registers without the secret cannot
produce valid encrypted messages, so joiners detect the impostor in §4.)

The host keeps this connection open for the lifetime of the share and
sends WebSocket pings at least every 30 s.

### 3.2 Joiner connection

```
GET wss://<host>/v1/join/<shareId>
```

If no host is registered: `{"t":"error","code":"not_found"}` and the socket
is closed. Otherwise:

- server → joiner: `{"t":"joined","sid":"<b64url 12 bytes>","ice":[…]}`
- server → host:   `{"t":"join","sid":"<same sid>","ice":[…]}`

`ice` is an array of RTCIceServer objects (`{"urls":[…],"username":…,
"credential":…}`). When the server has TURN credentials configured it
includes short-lived TURN entries; otherwise STUN only.

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

Exceeding a limit yields `{"t":"error","code":"rate_limited"}` (or
`"busy"` for the joiner cap) and a close. The server MUST NOT persist any
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
keys.

Joiner → host:

```json
{"t":"auth","n":"<b64url joinerNonce 16 bytes>","p":"<b64url proof>"}
proof = HMAC-SHA256(K_auth, "latchway/v1/proof" || hostNonce || joinerNonce)
```

The host compares in constant time. On failure it replies
`{"t":"error","code":"bad_auth"}` (plaintext) and sends `leave` for the
session. Hosts MUST throttle: after 10 failed proofs within a minute the
share rejects all joiners for 60 s. Because the proof is bound to both
nonces it cannot be replayed.

The joiner authenticates the host implicitly: only a party knowing
`K_root` can produce the encrypted messages that follow. A joiner that
receives an undecryptable message MUST abort with "this link is not valid".

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
| host → joiner  | `{"t":"meta","name":"…","size":123,"mime":"…","chunk":65536,"from":"Gabe","approval":true}` `size` is -1 when unknown. `from` is optional. `approval` means the joiner should show "waiting for the sender to approve". |
| host → joiner  | `{"t":"go"}` — the host is ready to answer an offer (sent immediately when no approval is required).     |
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
reliable` and label `"latchway"`. Frames larger than 65 KiB MUST NOT be sent
(libwebrtc and browsers cap SCTP messages at 256 KiB by default; 64 KiB
chunks are safe everywhere).

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

`not_found`, `host_gone`, `replaced`, `closed`, `bad_auth`, `denied`,
`expired`, `busy`, `rate_limited`, `protocol`, `internal`.

## 6. What each party learns

| Party       | Learns                                                        | Never learns                         |
|-------------|---------------------------------------------------------------|--------------------------------------|
| Rendezvous  | `shareId`, both IP addresses, timing, size of handshake blobs  | secret, keys, file name, size, bytes |
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
