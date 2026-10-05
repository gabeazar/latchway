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

## A. Cloudflare Worker

Two ways to get the Worker deployed. Both end in the same place: every push
to `main` redeploys automatically, and the domain, certificate and Durable
Object are created for you.

### Option 1: GitHub deploys it (recommended)

GitHub Actions runs `wrangler deploy` with a scoped Cloudflare API token.
Everything afterwards, including TURN setup, is automated. You do two
things once, both from a phone:

**Create the API token** in the Cloudflare dashboard: profile menu →
**My Profile** → **API Tokens** → **Create Token** → use the
**Edit Cloudflare Workers** template, then add two permissions before
continuing:

| Permission | Why |
|---|---|
| Account → **Realtime** (Cloudflare Calls) → **Edit** | lets the workflow create the TURN key |
| Zone → **DNS** → **Edit**, zone `latchway.app` | lets the deploy attach the custom domain |

Under *Account Resources* pick your account; under *Zone Resources* pick
`latchway.app`. Create, and copy the token (shown once).

**Add two secrets** on GitHub: repository → **Settings** → **Secrets and
variables** → **Actions** → **New repository secret**:

| Name | Value |
|---|---|
| `CLOUDFLARE_API_TOKEN` | the token |
| `CLOUDFLARE_ACCOUNT_ID` | the account id (Workers & Pages overview, right-hand column, or the long hex string in the dashboard URL) |

That's it. The next push, or **Actions → deploy-relay → Run workflow**,
deploys to `https://latchway.app`. Run it once with **Set up TURN**
ticked: the workflow creates a TURN key in your account and stores its id
and token as Worker secrets, without ever writing them to GitHub.

### Option 2: Cloudflare pulls from the repository

No API token anywhere; Cloudflare's own build service watches the repo.
TURN then has to be configured by hand (step 3 below).

1. Dashboard → **Workers & Pages** → **Create** → **Import a repository**,
   connect GitHub, pick `latchway`.
2. Build settings:

   | Setting | Value |
   |---|---|
   | Worker name | `latchway` (must match `name` in `relay/wrangler.toml`) |
   | Git branch | `main` |
   | Root directory | `relay` |
   | Build command | *(leave empty)* |
   | Deploy command | `npx wrangler deploy` |

3. **Save and Deploy**.

### 2. The domain

`relay/wrangler.toml` declares `latchway.app` and `www.latchway.app` as
custom domains. Because the domain is registered in the same Cloudflare
account, the deploy creates the DNS records and certificate. Nothing to
click. The Worker also answers at `https://latchway.<subdomain>.workers.dev`.

For a different domain, edit the two `pattern` values in
`relay/wrangler.toml` and push.

### 3. TURN fallback, by hand (only if you chose Option 2)

Without TURN, the roughly one-in-ten connections that can't be punched
through both NATs fail instead of falling back to a relay.

1. Dashboard → **Realtime** → **TURN** → **Create**. Name it "latchway".
   Copy the **Key ID** and the **API Token**; the token is shown once.
2. Dashboard → **Workers & Pages** → **latchway** → **Settings** →
   **Variables and Secrets** → **Add**:

   | Name | Type | Value |
   |---|---|---|
   | `TURN_KEY_ID` | Secret | the key id |
   | `TURN_KEY_API_TOKEN` | Secret | the API token |

3. **Deploy** (the button next to the variables).

Either way, the Worker then issues 12-hour TURN credentials to
authenticated senders and refreshes them hourly. Monitor usage under
Realtime → TURN → Analytics; the first 1,000 GB each month are free, then
$0.05/GB.

### 3b. Cap what TURN can cost

`TURN_BUDGET_GB` in `relay/wrangler.toml` (900 by default) is a monthly
egress cap. Twice an hour, and whenever a share needs a fresh answer, the
Worker sums the month's TURN egress through Cloudflare's GraphQL
analytics API. Once the sum reaches the cap, senders get STUN only until
the first of the next month: transfers that can find a direct path keep
working, the paid relay is simply withheld, and nothing is billed. The
reading is trusted for 48 hours; if the analytics query keeps failing
longer than that, TURN is withheld too, so a broken token fails safe.

It needs one more secret, an API token that can read analytics:

1. Dashboard → profile → **My Profile** → **API Tokens** → **Create Token**
   → **Create Custom Token**. Permission: **Account** → **Account
   Analytics** → **Read**, scoped to your account. Nothing else.
2. From `relay/`: `npx wrangler secret put CF_ANALYTICS_TOKEN`, paste it.
3. Check `CF_ACCOUNT_ID` in `relay/wrangler.toml` is your account id, and
   deploy.

With a budget set but no token, TURN is withheld rather than risk an
unseen overrun; the Worker logs say so. Set `TURN_BUDGET_GB = ""` to turn
the cap off. For a warning before the cap is reached, add a Cloudflare
notification (Notifications → **Add** → *Usage Based Billing*).

### 4. Make https links open the app directly (App Links)

Android opens `https://latchway.app/s/…` links straight in the app only if
the domain vouches for the app's signing key.

1. Get the SHA-256 fingerprint of the signing certificate. For a Play
   Store build it's under Play Console → **Test and release** → **App
   integrity** → **App signing key certificate**. For a local build:
   `keytool -list -v -keystore <keystore> | grep SHA256`.
2. Put the fingerprint(s), comma-separated in the `AA:BB:…` form, into
   `ASSETLINKS_FINGERPRINTS` in `relay/wrangler.toml` and push. Certificate
   fingerprints are public information; committing them is fine.
3. Check `https://latchway.app/.well-known/assetlinks.json` shows the
   fingerprint.

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
variables (`LATCHWAY_TURN_KEY_ID`, `LATCHWAY_TURN_KEY_API_TOKEN`), plus
the same spend cap as the Worker (`LATCHWAY_TURN_BUDGET_GB`,
`LATCHWAY_CF_ACCOUNT_ID`, `LATCHWAY_CF_ANALYTICS_TOKEN`; see §3b above),
or run [coturn](https://github.com/coturn/coturn) and pass it in:

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
