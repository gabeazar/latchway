# Hosting the rendezvous

Latchway's rendezvous server relays the encrypted connection handshake
between two phones and hands out TURN credentials. It never carries file
bytes. There are two interchangeable implementations of the same protocol:

| | Cloudflare Worker (`relay/`) | Go binary (`cmd/latchway-rendezvous`) |
|---|---|---|
| Cost | $0 on the Workers Free plan | Whatever your server costs |
| Maintenance | None; deploys itself on every push | You run updates |
| TURN fallback | Cloudflare Realtime TURN, 1,000 GB/month free | Cloudflare TURN (same keys) or your own coturn |
| Best for | The public `latchway.app` service | Private or air-gapped deployments |

Every Latchway app can point at any rendezvous from its settings, so running
your own is never required to use the app.

---

## A. Cloudflare Worker, from your phone or laptop

You need: a free Cloudflare account, this repository on GitHub, and
(optionally) a domain registered on Cloudflare. No command line.

### 1. Create the Worker from the repository

1. Open the Cloudflare dashboard → **Workers & Pages** → **Create**.
2. Choose **Import a repository** and connect your GitHub account when
   asked. Pick `latchway`.
3. Fill the build settings exactly like this:

   | Setting | Value |
   |---|---|
   | Worker name | `latchway` (must match `name` in `relay/wrangler.toml`) |
   | Git branch | `main` |
   | Root directory | `relay` |
   | Build command | *(leave empty)* |
   | Deploy command | `npx wrangler deploy` |

4. Select **Save and Deploy**. The first build takes about a minute. From
   now on every push to `main` redeploys automatically.

The Worker is immediately reachable at `https://latchway.<your-subdomain>.workers.dev`.

### 2. Attach the domain

`relay/wrangler.toml` already declares `latchway.app` and `www.latchway.app`
as custom domains. If the domain lives in the same Cloudflare account, the
deploy creates the DNS records and certificate for you. Nothing to click.

If you use a different domain, edit the two `pattern` values in
`relay/wrangler.toml` and push.

### 3. Turn on the TURN fallback (recommended)

Without this step, the roughly one-in-ten connections that can't be punched
through both NATs will fail instead of falling back to a relay.

1. Dashboard → **Realtime** → **TURN** → **Create**. Give the key a name
   ("latchway"). Copy the **Key ID** and the **API Token**; the token is
   shown once.
2. Dashboard → **Workers & Pages** → **latchway** → **Settings** →
   **Variables and Secrets** → **Add**:

   | Name | Type | Value |
   |---|---|---|
   | `TURN_KEY_ID` | Secret | the key id |
   | `TURN_KEY_API_TOKEN` | Secret | the API token |

3. **Deploy** (the button next to the variables) so the running Worker
   picks them up.

The Worker generates short-lived TURN credentials (2-hour TTL) per share
and caches them for an hour. Monitor usage under Realtime → TURN →
Analytics; the first 1,000 GB each month are free, then $0.05/GB.

### 4. Make https links open the app directly (App Links)

Android opens `https://latchway.app/s/…` links straight in the app only if
the domain vouches for the app's signing key.

1. Get the SHA-256 fingerprint of the signing certificate. For a Play
   Store build it's under Play Console → **Test and release** → **App
   integrity** → **App signing key certificate**. For a local build:
   `keytool -list -v -keystore <keystore> | grep SHA256`.
2. Dashboard → **latchway** → **Settings** → **Variables and Secrets** →
   edit `ASSETLINKS_FINGERPRINTS` → paste the fingerprint(s), comma-separated,
   in the `AA:BB:…` form.
3. **Deploy**. Check `https://latchway.app/.well-known/assetlinks.json`
   shows the fingerprint.

Without this, links still work: Android shows the browser page with an
**Open in Latchway** button.

### 5. Optional settings

| Variable | Meaning |
|---|---|
| `HOST_TOKEN` (secret) | Only senders presenting this token may register shares. Makes the rendezvous private; receivers never need it. |
| `EXTRA_ICE_SERVERS` | JSON array of extra ICE servers, for example your own coturn. |
| `ANDROID_PACKAGE` | Application id for assetlinks (`app.latchway`). |

### Limits on the free plan

The Worker and its Durable Objects run within the Workers Free plan
allowances (100,000 requests per day, plus Durable Object request and
duration allowances). A handshake costs a few dozen requests, so this is
room for thousands of transfers a day. If you ever approach the limits,
the dashboard shows usage under **Workers & Pages** → **latchway** →
**Metrics**, and the Paid plan is $5/month.

---

## B. Your own server with Docker

```sh
docker run -d --name latchway-rendezvous --restart unless-stopped \
  -p 80:80 -p 443:443 \
  -v latchway-certs:/certs \
  -e LATCHWAY_DOMAIN=files.example.org \
  -e LATCHWAY_ACME_EMAIL=you@example.org \
  ghcr.io/gabeazar/latchway-rendezvous:latest
```

That's a complete deployment: the binary obtains and renews a Let's Encrypt
certificate itself. Point the DNS `A`/`AAAA` record for `files.example.org`
at the server first.

Behind an existing reverse proxy (Caddy, nginx, Traefik), skip TLS in the
binary and listen on plain HTTP instead:

```sh
docker run -d --name latchway-rendezvous --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  -e LATCHWAY_LISTEN=:8080 \
  ghcr.io/gabeazar/latchway-rendezvous:latest
```

Your proxy must forward WebSocket upgrades for `/v1/*`.

### TURN for self-hosters

Either reuse Cloudflare's TURN service with the same two environment
variables (`LATCHWAY_TURN_KEY_ID`, `LATCHWAY_TURN_KEY_API_TOKEN`), or run
[coturn](https://github.com/coturn/coturn) and pass it in:

```sh
-e LATCHWAY_ICE_SERVERS='[{"urls":["turn:turn.example.org:3478"],"username":"u","credential":"p"}]'
```

### All options

Run `latchway-rendezvous --help`. Every flag has an environment-variable
twin prefixed `LATCHWAY_`.

### Building the binary yourself

```sh
go build ./cmd/latchway-rendezvous
```

Single static binary, no runtime dependencies. The static pages from `web/`
are embedded at build time.

---

## Pointing the app at your rendezvous

In Latchway: **Settings → Rendezvous server** → enter
`https://files.example.org`. Links you create will use that host, and
anyone who opens them connects through it. Receivers need no settings: the
host in the link tells their app where to go.
