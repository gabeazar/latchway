# Latchway for Android

Kotlin, Jetpack Compose, libwebrtc (`io.github.webrtc-sdk:android`). Two
modules:

| Module | What | Tests |
|---|---|---|
| `core` | Pure JVM: key schedule, proofs, encrypted envelopes, chunk framing, link parsing, padded meta, the streaming ZIP used for multi-file shares | `./gradlew :core:test`; reproduces `../testdata/vectors.json` byte for byte |
| `app` | The app: rendezvous client (OkHttp), host and joiner sessions (ports of `internal/peer`), foreground services, Compose UI | `./gradlew :app:assembleDebug` |

## Build

Needs JDK 17 and the Android SDK (platform 36). From this directory:

```sh
./gradlew :core:test            # no SDK needed
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

CI (`.github/workflows/android.yml`) does the same on every push and
attaches the debug APK as an artifact.

## Signing

`debug.keystore` (password `android`, alias `latchway-debug`) is committed
on purpose: it signs the CI builds, so each APK installs over the last,
and its SHA-256 fingerprint is in `ASSETLINKS_FINGERPRINTS` so
`https://latchway.app/s/…` links open sideloaded builds directly. It
protects nothing. Play builds are signed by Play App Signing; that
certificate's fingerprint joins the list when it exists.

## How it is put together

- `peer/Host.kt` and `peer/Receiver.kt` follow `docs/PROTOCOL.md` §4 step
  by step, the same structure as the Go code, on coroutines and
  channels. `peer/WebRtc.kt` turns libwebrtc callbacks into one channel of
  events per connection; receiving blocks libwebrtc's thread when the
  consumer falls 256 frames behind, which is the back-pressure that keeps
  a slow disk from filling memory.
- `share/ShareManager` and `receive/ReceiveManager` own the running
  transfers, independent of any screen. `ShareService` and
  `ReceiveService` are dataSync foreground services that hold a wake lock
  and a Wi-Fi lock while anything runs, show progress, and stop
  themselves when nothing is left. Approval requests are notifications
  with Send / Don't send actions.
- Several files are sent as one ZIP built on the fly by
  `core/ZipStream`: stored data in DEFLATE blocks we write ourselves, so
  the total size is known before the first byte and CRCs trail each
  entry. The receiver can unpack it into a folder of their choosing.
- Files come in through the document picker and the share sheet and go
  out through `ACTION_CREATE_DOCUMENT`. No storage permission.
- Settings (DataStore): rendezvous, display name, relay-only, defaults.

## Local testing against a local rendezvous

`network_security_config.xml` allows plain HTTP to `10.0.2.2` (the host
machine from an emulator), `localhost` and `127.0.0.1`. Run
`go run ./cmd/latchway-rendezvous --listen 0.0.0.0:8787` and set the
rendezvous in Settings to `http://10.0.2.2:8787`. Links then point at
that host; the receiving side must use the same setting or the same
network.
