# Publishing Latchway on Google Play

A checklist for the listing, written against Play Console as of late 2026.
Everything here follows from what the app does and does not do; keep it in
sync with `web/privacy.html`.

## Before the first upload

- **Developer account**: one-time $25 registration. New personal accounts
  must run a closed test with at least 12 testers for 14 days before
  production access is granted; plan for that.
- **App signing**: let Play manage the signing key (Play App Signing). Keep
  your *upload* key in a password manager; losing it is recoverable, losing
  the Play signing key is not. The SHA-256 of the Play signing certificate
  goes into the rendezvous' `ASSETLINKS_FINGERPRINTS` (see HOSTING.md §A.4).
- **Application id**: `app.latchway`. It can never change after the first
  upload.
- **Format**: upload an Android App Bundle (`.aab`), not an APK. Play
  delivers per-device splits, which matters because the WebRTC native
  library is large.

## Data safety form

Answer from the app's actual behaviour:

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | n/a (nothing collected); the transfer itself is end-to-end encrypted |
| Do you provide a way for users to request that their data is deleted? | n/a (nothing is stored server-side) |

Rationale to keep on file: the rendezvous handles share identifiers and IP
addresses transiently to establish a connection and retains nothing; this
is "ephemeral processing" under Play's definitions, not collection. Files
move device to device and never reach a server operated by the developer.

## Permissions and declarations

| Permission | Why | Play form |
|---|---|---|
| `INTERNET` | transfers | none |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | keep a share or download alive with the screen off | **Foreground service declaration**: type *dataSync*, purpose "transferring a user-selected file between devices while the app is in the background"; attach a short screen recording of a share running |
| `POST_NOTIFICATIONS` | progress and approval prompts | none |
| `ACCESS_NETWORK_STATE` | reconnect the rendezvous when the network changes | none |

No storage permission: files come in via `ACTION_OPEN_DOCUMENT` /
`ACTION_SEND` and go out via `ACTION_CREATE_DOCUMENT`. Do not add
`MANAGE_EXTERNAL_STORAGE`; it triggers a review that this app cannot pass
and does not need.

Android 15 limits *dataSync* foreground services to six hours per day; the
app must handle `onTimeout()` by stopping shares gracefully and telling the
user.

## Listing content

- **Category**: Tools.
- **Short description** (80 chars max): *Send a big file straight from your
  phone to a friend's with a link. Encrypted.*
- **Privacy policy URL**: `https://latchway.app/privacy`
- **Contact email**: a monitored address; it is shown publicly.
- **Content rating**: complete the IARC questionnaire; a file-transfer
  utility with no user-generated content shared publicly rates *Everyone*.
- **Ads**: declare "no ads".
- **Target audience**: 18 and over, or 13+; do not opt into the Families
  programme.
- **Screenshots**: at least two phone screenshots; show the link screen with
  a QR code, the approval prompt, and a receive in progress.
- **Open source**: mention the repository in the full description. It is a
  trust signal reviewers and users respond to.

## Release checklist

1. Tag the release in git; CI attaches the APK to a GitHub Release for
   sideloaders and F-Droid.
2. Upload the `.aab` to the closed testing track; verify App Links open the
   app directly on a fresh device.
3. Confirm `https://latchway.app/.well-known/assetlinks.json` lists the Play
   signing certificate fingerprint.
4. Promote to production with a staged rollout (10% → 50% → 100%).

## F-Droid (optional, later)

The app is GPL-3.0 and has no proprietary dependencies, so it qualifies.
F-Droid builds from source; the prebuilt WebRTC AAR counts as a binary
dependency and must either be built from source in the recipe or replaced
by a reproducible build. Decide this before investing in the submission.
