# Security policy

Latchway is a small project with a large promise: that the people running
its servers cannot read what you send. Reports that challenge that promise
are the most valuable thing anyone can contribute.

## Reporting a vulnerability

Please do not open a public issue for security problems. Instead, use
GitHub's private vulnerability reporting on this repository
(**Security** → **Report a vulnerability**), which reaches the maintainer
directly. You will get an acknowledgement within a few days.

Include what you found, how to reproduce it, and what you think the impact
is. Proof-of-concept code is welcome. If the issue is in a dependency
(pion, libwebrtc, Cloudflare Workers), say so and we'll coordinate.

## What is in scope

- The protocol in `docs/PROTOCOL.md` and any gap between it and the code.
- The Android app, the Go tools, the Cloudflare Worker and the Go
  rendezvous server in this repository.
- The web pages served from `latchway.app`.

## What we consider a vulnerability

- Anyone other than the sender and the holder of the link learning the file
  contents, name or size, including the rendezvous or TURN operator.
- Downloading a file without the complete link, or without the password
  when one is set.
- Impersonating a sender to someone holding a valid link.
- Making a sender's phone transfer to a party it did not approve, when
  approval is on.
- Denial-of-service against the rendezvous beyond what the documented
  limits accept.

## What we don't

- Someone who has the complete link downloading the file: the link *is* the
  authorization, by design.
- Both phones learning each other's IP address on a direct connection,
  unless relay-only mode was enabled.
- The rendezvous seeing share identifiers and IP addresses. This is
  documented in the privacy policy and is inherent to introducing two
  devices.

## Disclosure

Fixes are released as soon as they are ready, with a note in the release
describing the issue once a fix is available. Credit is given to reporters
who want it.
