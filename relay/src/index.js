// Rendezvous server for Latchway, as a Cloudflare Worker.
//
// Role: relay the end-to-end-encrypted WebRTC handshake between one sender
// ("host") and up to 16 receivers ("joiners") per share, and hand out
// short-lived TURN credentials. This code never sees a file, a file name, or
// a key; it routes opaque strings. See docs/PROTOCOL.md §3.
//
// Plain JavaScript on purpose: no build step, nothing to audit but this file.

import { DurableObject } from "cloudflare:workers";

const SHARE_ID_RE = /^[A-Za-z0-9_-]{22}$/;
const SID_BYTES = 12;

// Normative limits from PROTOCOL.md §3.4.
const MAX_PAYLOAD_CHARS = 16 * 1024;
const MAX_FRAME_CHARS = 20 * 1024;
const MAX_SIGS_PER_DIRECTION = 256;
const MAX_JOINERS = 16;
const JOINER_IDLE_MS = 120_000;
const JOINER_MAX_MS = 30 * 60_000;
const SWEEP_INTERVAL_MS = 60_000;

// TURN credentials are fetched once per hour per share and valid for two.
const TURN_TTL_SECONDS = 2 * 3600;
const TURN_CACHE_MS = 60 * 60_000;
const STUN_ONLY = [{ urls: ["stun:stun.cloudflare.com:3478"] }];

const SECURITY_HEADERS = {
  "Content-Security-Policy":
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
    "connect-src 'self'; font-src 'self'; manifest-src 'self'; base-uri 'none'; " +
    "form-action 'none'; frame-ancestors 'none'; upgrade-insecure-requests",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
  "Cross-Origin-Opener-Policy": "same-origin",
  "Cross-Origin-Resource-Policy": "same-origin",
  "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
};

// ---------------------------------------------------------------------------
// Worker: HTTP routing
// ---------------------------------------------------------------------------

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method !== "GET" && request.method !== "HEAD") {
      return text("method not allowed", 405);
    }

    if (path === "/healthz") return text("ok", 200);

    if (path === "/.well-known/assetlinks.json") {
      return json(assetLinks(env), 200, { "Cache-Control": "public, max-age=3600" });
    }

    if (path === "/v1/host") {
      const shareId = url.searchParams.get("share") || "";
      if (!SHARE_ID_RE.test(shareId)) return text("bad share id", 400);
      if (!isWebSocketUpgrade(request)) return text("expected websocket", 426);
      if (env.HOST_TOKEN && !hostTokenOk(request, url, env.HOST_TOKEN)) {
        return text("unauthorized", 401);
      }
      return shareStub(env, shareId).fetch(forward(request, "/host"));
    }

    let m = path.match(/^\/v1\/join\/([A-Za-z0-9_-]{22})$/);
    if (m) {
      if (!isWebSocketUpgrade(request)) return text("expected websocket", 426);
      return shareStub(env, m[1]).fetch(forward(request, "/join"));
    }

    m = path.match(/^\/v1\/status\/([A-Za-z0-9_-]{22})$/);
    if (m) {
      const res = await shareStub(env, m[1]).fetch(forward(request, "/status"));
      return withHeaders(res, { "Cache-Control": "no-store" });
    }

    if (path.startsWith("/v1/")) return text("not found", 404);

    // Share landing page: /s/<shareId>. The fragment (#secret) never reaches
    // us; the browser keeps it. We serve the same static page for every id.
    m = path.match(/^\/s\/([A-Za-z0-9_-]{22})\/?$/);
    if (m) {
      return serveAsset(env, request, "/s.html", { "Cache-Control": "public, max-age=300" });
    }

    // Everything else is a static asset (landing page, CSS, icons).
    return serveAsset(env, request, null, { "Cache-Control": "public, max-age=3600" });
  },
};

function shareStub(env, shareId) {
  return env.SHARES.get(env.SHARES.idFromName(shareId));
}

// Re-targets the request at a Durable Object path while keeping headers
// (notably the WebSocket upgrade headers) intact.
function forward(request, doPath) {
  const url = new URL(request.url);
  url.pathname = doPath;
  url.search = "";
  return new Request(url.toString(), request);
}

function isWebSocketUpgrade(request) {
  return (request.headers.get("Upgrade") || "").toLowerCase() === "websocket";
}

function hostTokenOk(request, url, expected) {
  const auth = request.headers.get("Authorization") || "";
  const bearer = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  const candidate = bearer || url.searchParams.get("token") || "";
  return candidate.length > 0 && constantTimeEqual(candidate, expected);
}

function constantTimeEqual(a, b) {
  const ea = new TextEncoder().encode(a);
  const eb = new TextEncoder().encode(b);
  if (ea.length !== eb.length) return false;
  let diff = 0;
  for (let i = 0; i < ea.length; i++) diff |= ea[i] ^ eb[i];
  return diff === 0;
}

async function serveAsset(env, request, overridePath, extraHeaders) {
  let req = request;
  if (overridePath) {
    const url = new URL(request.url);
    url.pathname = overridePath;
    url.search = "";
    req = new Request(url.toString(), { method: request.method, headers: request.headers });
  }
  let res = await env.ASSETS.fetch(req);
  if (res.status === 404 && !overridePath) {
    const url = new URL(request.url);
    url.pathname = "/404.html";
    url.search = "";
    const nf = await env.ASSETS.fetch(new Request(url.toString(), { method: "GET" }));
    res = nf.ok
      ? new Response(nf.body, { status: 404, headers: nf.headers })
      : text("not found", 404);
  }
  return withHeaders(res, extraHeaders);
}

function withHeaders(res, extra) {
  const out = new Response(res.body, res);
  for (const [k, v] of Object.entries(SECURITY_HEADERS)) out.headers.set(k, v);
  for (const [k, v] of Object.entries(extra || {})) out.headers.set(k, v);
  return out;
}

function text(body, status, extra) {
  return withHeaders(
    new Response(body, { status, headers: { "Content-Type": "text/plain; charset=utf-8" } }),
    extra,
  );
}

function json(obj, status, extra) {
  return withHeaders(
    new Response(JSON.stringify(obj), {
      status,
      headers: { "Content-Type": "application/json; charset=utf-8" },
    }),
    extra,
  );
}

function assetLinks(env) {
  const fps = (env.ASSETLINKS_FINGERPRINTS || "")
    .split(",")
    .map((s) => s.trim().toUpperCase())
    .filter((s) => /^([0-9A-F]{2}:){31}[0-9A-F]{2}$/.test(s));
  if (fps.length === 0 || !env.ANDROID_PACKAGE) return [];
  return [
    {
      relation: ["delegate_permission/common.handle_all_urls"],
      target: {
        namespace: "android_app",
        package_name: env.ANDROID_PACKAGE,
        sha256_cert_fingerprints: fps,
      },
    },
  ];
}

// ---------------------------------------------------------------------------
// Durable Object: one per shareId
// ---------------------------------------------------------------------------

export class ShareObject extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    // Application-level keepalive answered without waking the object.
    this.ctx.setWebSocketAutoResponse(
      new WebSocketRequestResponsePair('{"t":"ping"}', '{"t":"pong"}'),
    );
    this.iceCache = null;
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/status") {
      return json({ active: this.host() !== null }, 200);
    }
    if (!isWebSocketUpgrade(request)) return text("expected websocket", 426);
    if (url.pathname === "/host") return this.acceptHost();
    if (url.pathname === "/join") return this.acceptJoiner();
    return text("not found", 404);
  }

  // --- connection setup ----------------------------------------------------

  acceptHost() {
    const [client, server] = Object.values(new WebSocketPair());

    // A new registration replaces a live one (PROTOCOL.md §3.1). Joiners of
    // the old host cannot continue with the new one, so they are closed too.
    for (const old of this.ctx.getWebSockets("host")) {
      sendJson(old, { t: "error", code: "replaced" });
      safeClose(old, 1000, "replaced");
    }
    for (const j of this.ctx.getWebSockets("joiner")) {
      sendJson(j, { t: "error", code: "host_gone" });
      safeClose(j, 1000, "host_gone");
    }

    this.ctx.acceptWebSocket(server, ["host"]);
    server.serializeAttachment({ role: "host", at: Date.now() });
    sendJson(server, { t: "ready" });
    return new Response(null, { status: 101, webSocket: client });
  }

  async acceptJoiner() {
    const [client, server] = Object.values(new WebSocketPair());
    const host = this.host();

    if (host === null) {
      return this.rejectJoiner(client, server, "not_found");
    }
    if (this.ctx.getWebSockets("joiner").length >= MAX_JOINERS) {
      return this.rejectJoiner(client, server, "busy");
    }

    const sid = b64url(crypto.getRandomValues(new Uint8Array(SID_BYTES)));
    const ice = await this.iceServers();
    const now = Date.now();

    this.ctx.acceptWebSocket(server, ["joiner", "sid:" + sid]);
    server.serializeAttachment({ role: "joiner", sid, at: now, last: now, nj: 0, nh: 0 });

    sendJson(server, { t: "joined", sid, ice });
    sendJson(host, { t: "join", sid, ice });

    if ((await this.ctx.storage.getAlarm()) === null) {
      await this.ctx.storage.setAlarm(now + SWEEP_INTERVAL_MS);
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  // Accept, deliver one error, close. Accepting first lets browser clients,
  // which cannot read HTTP error bodies on a failed upgrade, see the reason.
  rejectJoiner(client, server, code) {
    this.ctx.acceptWebSocket(server, ["rejected"]);
    server.serializeAttachment({ role: "rejected" });
    sendJson(server, { t: "error", code });
    safeClose(server, 1008, code);
    return new Response(null, { status: 101, webSocket: client });
  }

  // --- message routing -----------------------------------------------------

  async webSocketMessage(ws, message) {
    const att = attachment(ws);
    if (!att || att.role === "rejected") return;

    if (typeof message !== "string") {
      return this.fail(ws, "protocol");
    }
    if (message.length > MAX_FRAME_CHARS) {
      return this.fail(ws, "rate_limited");
    }
    let msg;
    try {
      msg = JSON.parse(message);
    } catch {
      return this.fail(ws, "protocol");
    }
    if (!msg || typeof msg.t !== "string") return this.fail(ws, "protocol");

    if (att.role === "host") return this.fromHost(ws, msg);
    if (att.role === "joiner") return this.fromJoiner(ws, att, msg);
  }

  fromHost(hostWs, msg) {
    if (msg.t === "sig") {
      if (typeof msg.sid !== "string" || typeof msg.d !== "string") {
        return this.fail(hostWs, "protocol");
      }
      if (msg.d.length > MAX_PAYLOAD_CHARS) return this.fail(hostWs, "rate_limited");
      const joiner = this.joiner(msg.sid);
      if (!joiner) {
        // Stale session: tell the host so it can clean up.
        sendJson(hostWs, { t: "leave", sid: msg.sid });
        return;
      }
      const ja = attachment(joiner);
      ja.nh += 1;
      ja.last = Date.now();
      if (ja.nh > MAX_SIGS_PER_DIRECTION) return this.fail(joiner, "rate_limited");
      joiner.serializeAttachment(ja);
      sendJson(joiner, { t: "sig", sid: msg.sid, d: msg.d });
      return;
    }
    if (msg.t === "leave") {
      if (typeof msg.sid !== "string") return;
      const joiner = this.joiner(msg.sid);
      if (joiner) this.fail(joiner, "closed");
      return;
    }
    // Unknown types are ignored so that future versions can add messages
    // without breaking older servers.
  }

  fromJoiner(joinerWs, att, msg) {
    if (msg.t !== "sig") return;
    if (typeof msg.d !== "string") return this.fail(joinerWs, "protocol");
    if (msg.d.length > MAX_PAYLOAD_CHARS) return this.fail(joinerWs, "rate_limited");

    att.nj += 1;
    att.last = Date.now();
    if (att.nj > MAX_SIGS_PER_DIRECTION) return this.fail(joinerWs, "rate_limited");
    joinerWs.serializeAttachment(att);

    const host = this.host();
    if (!host) return this.fail(joinerWs, "host_gone");
    sendJson(host, { t: "sig", sid: att.sid, d: msg.d });
  }

  async webSocketClose(ws, code, reason, wasClean) {
    this.onGone(ws);
    safeClose(ws, 1000, "bye");
  }

  async webSocketError(ws, error) {
    this.onGone(ws);
    safeClose(ws, 1011, "error");
  }

  onGone(ws) {
    const att = attachment(ws);
    if (!att) return;
    if (att.role === "host") {
      // Only tear down joiners if no other live host remains (a replacement
      // closes the old host, and we already handled its joiners).
      const live = this.ctx
        .getWebSockets("host")
        .filter((h) => h !== ws && h.readyState === WebSocket.OPEN);
      if (live.length === 0) {
        for (const j of this.ctx.getWebSockets("joiner")) {
          sendJson(j, { t: "error", code: "host_gone" });
          safeClose(j, 1000, "host_gone");
        }
      }
    } else if (att.role === "joiner") {
      const host = this.host();
      if (host) sendJson(host, { t: "leave", sid: att.sid });
    }
  }

  // Periodic sweep of joiners that stalled or overstayed.
  async alarm() {
    const now = Date.now();
    let remaining = 0;
    for (const j of this.ctx.getWebSockets("joiner")) {
      const a = attachment(j);
      if (!a) continue;
      if (now - a.last > JOINER_IDLE_MS || now - a.at > JOINER_MAX_MS) {
        this.fail(j, "timeout");
      } else {
        remaining += 1;
      }
    }
    if (remaining > 0) await this.ctx.storage.setAlarm(now + SWEEP_INTERVAL_MS);
  }

  // --- helpers -------------------------------------------------------------

  host() {
    let best = null;
    let bestAt = -1;
    for (const ws of this.ctx.getWebSockets("host")) {
      if (ws.readyState !== WebSocket.OPEN) continue;
      const a = attachment(ws);
      const at = a ? a.at : 0;
      if (at > bestAt) {
        best = ws;
        bestAt = at;
      }
    }
    return best;
  }

  joiner(sid) {
    if (typeof sid !== "string" || sid.length > 64) return null;
    for (const ws of this.ctx.getWebSockets("sid:" + sid)) {
      if (ws.readyState === WebSocket.OPEN) return ws;
    }
    return null;
  }

  fail(ws, code) {
    sendJson(ws, { t: "error", code });
    safeClose(ws, code === "protocol" ? 1002 : 1008, code);
  }

  async iceServers() {
    const now = Date.now();
    if (this.iceCache && now - this.iceCache.at < TURN_CACHE_MS) return this.iceCache.servers;

    let servers = STUN_ONLY;
    const keyId = this.env.TURN_KEY_ID;
    const token = this.env.TURN_KEY_API_TOKEN;
    if (keyId && token) {
      try {
        const res = await fetch(
          `https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(keyId)}/credentials/generate-ice-servers`,
          {
            method: "POST",
            headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
            body: JSON.stringify({ ttl: TURN_TTL_SECONDS }),
          },
        );
        if (res.ok) {
          const body = await res.json();
          const s = body && body.iceServers;
          if (Array.isArray(s) && s.length > 0) servers = s;
          else if (s && Array.isArray(s.urls)) servers = [s];
        } else {
          console.warn("turn credential request failed", res.status);
        }
      } catch (e) {
        console.warn("turn credential request error", String(e));
      }
    }

    const extra = parseExtraIce(this.env.EXTRA_ICE_SERVERS);
    if (extra.length > 0) servers = servers.concat(extra);

    this.iceCache = { at: now, servers };
    return servers;
  }
}

function parseExtraIce(raw) {
  if (!raw) return [];
  try {
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v.filter((s) => s && (Array.isArray(s.urls) || typeof s.urls === "string")) : [];
  } catch {
    return [];
  }
}

function attachment(ws) {
  try {
    return ws.deserializeAttachment();
  } catch {
    return null;
  }
}

function sendJson(ws, obj) {
  try {
    if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
  } catch {
    // The socket is going away; nothing useful to do.
  }
}

function safeClose(ws, code, reason) {
  try {
    ws.close(code, reason);
  } catch {
    // Already closed.
  }
}

function b64url(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
