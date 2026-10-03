// Rendezvous server for Latchway, as a Cloudflare Worker.
//
// Role: relay the end-to-end-encrypted WebRTC handshake between one sender
// ("host") and up to 16 receivers ("joiners") per share, and hand out
// short-lived TURN credentials to authenticated hosts. This code never sees
// a file, a file name, or a key; it routes opaque strings. See
// docs/PROTOCOL.md §3.
//
// Plain JavaScript on purpose: no build step, nothing to audit but this file.

import { DurableObject } from "cloudflare:workers";

const SHARE_ID_RE = /^[A-Za-z0-9_-]{22}$/;
const B64URL_RE = /^[A-Za-z0-9_-]+$/;
const SID_BYTES = 12;
// WebSocket.readyState value for an open socket (the named constant is not
// guaranteed to exist on every runtime's WebSocket class).
const WS_OPEN = 1;

// Normative limits from PROTOCOL.md §3.4, plus local abuse controls.
const MAX_PAYLOAD_CHARS = 16 * 1024;
const MAX_FRAME_CHARS = 20 * 1024;
const MAX_SIGS_PER_DIRECTION = 256;
const MAX_JOINERS = 16;
const MAX_JOINERS_PER_IP = 4;
const JOINER_IDLE_MS = 120_000;
const JOINER_MAX_MS = 30 * 60_000;
const HOST_IDLE_MS = 90_000;
const REGISTER_TIMEOUT_MS = 10_000;
const SWEEP_INTERVAL_MS = 30_000;
const REG_BINDING_MS = 30 * 24 * 60 * 60_000;

// TURN credentials: fetched at most hourly per share, valid for twelve hours
// so that a long transfer never outlives them. Failures are retried after a
// minute rather than cached for the hour.
const TURN_TTL_SECONDS = 12 * 3600;
const TURN_CACHE_MS = 60 * 60_000;
const TURN_FAIL_CACHE_MS = 60_000;
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
      if (!canonicalShareId(shareId)) return text("bad share id", 400);
      if (!isWebSocketUpgrade(request)) return text("expected websocket", 426);
      if (!originOk(request, url)) return text("forbidden origin", 403);
      if (env.HOST_TOKEN && !hostTokenOk(request, env.HOST_TOKEN)) {
        return text("unauthorized", 401);
      }
      return shareStub(env, shareId).fetch(forward(request, "/host"));
    }

    let m = path.match(/^\/v1\/join\/([A-Za-z0-9_-]{22})$/);
    if (m) {
      if (!canonicalShareId(m[1])) return text("not found", 404);
      if (!isWebSocketUpgrade(request)) return text("expected websocket", 426);
      if (!originOk(request, url)) return text("forbidden origin", 403);
      return shareStub(env, m[1]).fetch(forward(request, "/join"));
    }

    m = path.match(/^\/v1\/status\/([A-Za-z0-9_-]{22})$/);
    if (m) {
      if (!canonicalShareId(m[1])) return text("not found", 404);
      const res = await shareStub(env, m[1]).fetch(forward(request, "/status"));
      return withHeaders(res, { "Cache-Control": "no-store" });
    }

    if (path.startsWith("/v1/")) return text("not found", 404);

    // Share landing page: /s/<shareId>. The fragment (#secret) never reaches
    // us; the browser keeps it. We serve the same static page for every id.
    m = path.match(/^\/s\/([A-Za-z0-9_-]{22})\/?$/);
    if (m && canonicalShareId(m[1])) {
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
// (notably the WebSocket upgrade headers) intact, and tells the object which
// client address the connection came from.
function forward(request, doPath) {
  const url = new URL(request.url);
  url.pathname = doPath;
  url.search = "";
  const req = new Request(url.toString(), request);
  const ip = request.headers.get("cf-connecting-ip") || "";
  const headers = new Headers(req.headers);
  headers.set("x-latchway-ip", ip);
  headers.delete("authorization");
  return new Request(req, { headers });
}

function isWebSocketUpgrade(request) {
  return (request.headers.get("Upgrade") || "").toLowerCase() === "websocket";
}

// Native clients send no Origin; browsers must come from this very origin.
function originOk(request, url) {
  const origin = request.headers.get("Origin");
  if (!origin) return true;
  return origin.toLowerCase() === url.origin.toLowerCase();
}

function hostTokenOk(request, expected) {
  const auth = request.headers.get("Authorization") || "";
  const bearer = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  return bearer.length > 0 && constantTimeEqual(bearer, expected);
}

function constantTimeEqual(a, b) {
  const ea = new TextEncoder().encode(a);
  const eb = new TextEncoder().encode(b);
  if (ea.length !== eb.length) return false;
  let diff = 0;
  for (let i = 0; i < ea.length; i++) diff |= ea[i] ^ eb[i];
  return diff === 0;
}

// A share id has exactly one spelling: decoding and re-encoding must give
// the same 22 characters (PROTOCOL.md §1).
function canonicalShareId(s) {
  if (!SHARE_ID_RE.test(s)) return false;
  const bytes = b64urlDecode(s);
  return bytes !== null && bytes.length === 16 && b64url(bytes) === s;
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
    this.iceCache = null; // { at, servers }
    this.icePromise = null; // in-flight fetch, shared by concurrent joiners
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/status") {
      return json({ active: this.host() !== null }, 200);
    }
    if (!isWebSocketUpgrade(request)) return text("expected websocket", 426);
    const ip = request.headers.get("x-latchway-ip") || "";
    if (url.pathname === "/host") return this.acceptHost(ip);
    if (url.pathname === "/join") return this.acceptJoiner(ip);
    return text("not found", 404);
  }

  // --- connection setup ----------------------------------------------------

  // A host is accepted but counts for nothing until its register message
  // has been checked against the share's registration key (§3.1).
  async acceptHost(ip) {
    const [client, server] = Object.values(new WebSocketPair());
    const now = Date.now();
    this.ctx.acceptWebSocket(server, ["host"]);
    server.serializeAttachment({ role: "host", registered: false, at: now, last: now, ip });
    await this.ensureAlarm(now);
    return new Response(null, { status: 101, webSocket: client });
  }

  async register(ws, att, msg) {
    if (att.registered) return; // duplicate register: ignore
    if (typeof msg.reg !== "string" || !B64URL_RE.test(msg.reg)) {
      return this.fail(ws, "protocol");
    }
    const regBytes = b64urlDecode(msg.reg);
    if (!regBytes || regBytes.length !== 32) return this.fail(ws, "protocol");

    const hash = hex(await crypto.subtle.digest("SHA-256", regBytes));
    const now = Date.now();
    const stored = await this.ctx.storage.get("reg");
    if (stored && now - stored.at < REG_BINDING_MS && !constantTimeEqual(stored.hash, hash)) {
      return this.fail(ws, "forbidden");
    }
    await this.ctx.storage.put("reg", { hash, at: now });

    // Replace any live host, and drop its joiners: their sessions belonged
    // to a connection that no longer exists.
    for (const old of this.ctx.getWebSockets("host")) {
      if (old === ws) continue;
      const oa = attachment(old);
      if (oa && oa.registered) {
        sendJson(old, { t: "error", code: "replaced" });
        safeClose(old, 1000, "replaced");
      }
    }
    for (const j of this.ctx.getWebSockets("joiner")) {
      sendJson(j, { t: "error", code: "host_gone" });
      safeClose(j, 1000, "host_gone");
    }

    att.registered = true;
    att.at = now;
    att.last = now;
    ws.serializeAttachment(att);
    sendJson(ws, { t: "ready" });
  }

  // Rejections are plain HTTP responses: nothing is upgraded, so there is
  // no half-open socket to tear down, and native clients can read the code.
  async acceptJoiner(ip) {
    if (this.host() === null) return text("not_found", 404);
    if (this.joinerCapReached(ip)) return text("busy", 429);

    // The TURN fetch below may yield; re-check the caps afterwards so a
    // burst of simultaneous joins cannot slip past them.
    const ice = await this.iceServers();
    if (this.host() === null) return text("not_found", 404);
    if (this.joinerCapReached(ip)) return text("busy", 429);

    const [client, server] = Object.values(new WebSocketPair());
    const sid = b64url(crypto.getRandomValues(new Uint8Array(SID_BYTES)));
    const now = Date.now();
    this.ctx.acceptWebSocket(server, ["joiner", "sid:" + sid]);
    server.serializeAttachment({ role: "joiner", sid, at: now, last: now, nj: 0, nh: 0, ip });

    // Joiners get STUN only; TURN credentials travel to the joiner inside
    // the host's encrypted `go` message once the proof has been checked.
    sendJson(server, { t: "joined", sid, ice: STUN_ONLY });
    sendJson(this.host(), { t: "join", sid, ice });

    await this.ensureAlarm(now);
    return new Response(null, { status: 101, webSocket: client });
  }

  joinerCapReached(ip) {
    const joiners = this.ctx.getWebSockets("joiner").filter((j) => j.readyState === WS_OPEN);
    if (joiners.length >= MAX_JOINERS) return true;
    if (!ip) return false;
    let same = 0;
    for (const j of joiners) {
      const a = attachment(j);
      if (a && a.ip === ip) same += 1;
    }
    return same >= MAX_JOINERS_PER_IP;
  }

  async ensureAlarm(now) {
    if ((await this.ctx.storage.getAlarm()) === null) {
      await this.ctx.storage.setAlarm(now + SWEEP_INTERVAL_MS);
    }
  }

  // --- message routing -----------------------------------------------------

  async webSocketMessage(ws, message) {
    const att = attachment(ws);
    if (!att) return;

    if (typeof message !== "string") return this.fail(ws, "protocol");
    if (message.length > MAX_FRAME_CHARS) return this.fail(ws, "rate_limited");
    let msg;
    try {
      msg = JSON.parse(message);
    } catch {
      return this.fail(ws, "protocol");
    }
    if (!msg || typeof msg.t !== "string") return this.fail(ws, "protocol");

    att.last = Date.now();
    if (att.role === "host") {
      if (!att.registered) {
        if (msg.t !== "register") return this.fail(ws, "protocol");
        return this.register(ws, att, msg);
      }
      ws.serializeAttachment(att);
      return this.fromHost(ws, msg);
    }
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
    if (msg.t !== "sig") {
      joinerWs.serializeAttachment(att);
      return;
    }
    if (typeof msg.d !== "string") return this.fail(joinerWs, "protocol");
    if (msg.d.length > MAX_PAYLOAD_CHARS) return this.fail(joinerWs, "rate_limited");

    att.nj += 1;
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
      if (!att.registered) return;
      // Only tear down joiners if no other live host remains (a replacement
      // closes the old host, and we already handled its joiners).
      if (this.host(ws) === null) {
        for (const j of this.ctx.getWebSockets("joiner")) {
          sendJson(j, { t: "error", code: "host_gone" });
          setTimeout(() => safeClose(j, 1000, "host_gone"), 50);
        }
      }
    } else if (att.role === "joiner") {
      const host = this.host();
      if (host) sendJson(host, { t: "leave", sid: att.sid });
    }
  }

  // Periodic sweep: unregistered hosts that never registered, joiners that
  // stalled or overstayed, hosts that fell silent.
  async alarm() {
    const now = Date.now();
    let remaining = 0;
    for (const h of this.ctx.getWebSockets("host")) {
      const a = attachment(h);
      if (!a) continue;
      if (!a.registered) {
        if (now - a.at > REGISTER_TIMEOUT_MS) this.fail(h, "protocol");
        else remaining += 1;
        continue;
      }
      const auto = this.ctx.getWebSocketAutoResponseTimestamp(h);
      const last = Math.max(a.last, auto ? auto.getTime() : 0);
      if (now - last > HOST_IDLE_MS) this.fail(h, "timeout");
      else remaining += 1;
    }
    for (const j of this.ctx.getWebSockets("joiner")) {
      const a = attachment(j);
      if (!a) continue;
      if (now - a.last > JOINER_IDLE_MS || now - a.at > JOINER_MAX_MS) this.fail(j, "timeout");
      else remaining += 1;
    }
    if (remaining > 0) await this.ctx.storage.setAlarm(now + SWEEP_INTERVAL_MS);
  }

  // --- helpers -------------------------------------------------------------

  // The registered, open host socket (most recently registered wins),
  // optionally ignoring one socket that is on its way out.
  host(except) {
    let best = null;
    let bestAt = -1;
    for (const ws of this.ctx.getWebSockets("host")) {
      if (ws === except || ws.readyState !== WS_OPEN) continue;
      const a = attachment(ws);
      if (!a || !a.registered) continue;
      if (a.at > bestAt) {
        best = ws;
        bestAt = a.at;
      }
    }
    return best;
  }

  joiner(sid) {
    if (typeof sid !== "string" || sid.length > 64) return null;
    for (const ws of this.ctx.getWebSockets("sid:" + sid)) {
      if (ws.readyState === WS_OPEN) return ws;
    }
    return null;
  }

  fail(ws, code) {
    sendJson(ws, { t: "error", code });
    safeClose(ws, code === "protocol" ? 1002 : 1008, code);
  }

  iceServers() {
    const now = Date.now();
    if (this.iceCache && now - this.iceCache.at < this.iceCache.ttl) {
      return Promise.resolve(this.iceCache.servers);
    }
    if (!this.icePromise) {
      this.icePromise = this.fetchIceServers()
        .then((result) => {
          this.iceCache = { at: Date.now(), servers: result.servers, ttl: result.ok ? TURN_CACHE_MS : TURN_FAIL_CACHE_MS };
          return result.servers;
        })
        .finally(() => {
          this.icePromise = null;
        });
    }
    return this.icePromise;
  }

  async fetchIceServers() {
    let servers = STUN_ONLY;
    let ok = true;
    const keyId = this.env.TURN_KEY_ID;
    const token = this.env.TURN_KEY_API_TOKEN;
    if (keyId && token) {
      ok = false;
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
          if (Array.isArray(s) && s.length > 0) {
            servers = s;
            ok = true;
          } else if (s && Array.isArray(s.urls)) {
            servers = [s];
            ok = true;
          }
        } else {
          console.warn("turn credential request failed", res.status);
        }
      } catch (e) {
        console.warn("turn credential request error", String(e));
      }
    }
    const extra = parseExtraIce(this.env.EXTRA_ICE_SERVERS);
    if (extra.length > 0) servers = servers.concat(extra);
    return { servers, ok };
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
    if (ws && ws.readyState === WS_OPEN) ws.send(JSON.stringify(obj));
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

function b64urlDecode(s) {
  if (!B64URL_RE.test(s)) return null;
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (s.length % 4)) % 4);
  try {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

function hex(buf) {
  return Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, "0")).join("");
}
