// Behavioural tests for the rendezvous, run against either implementation:
//
//   RELAY_URL=http://127.0.0.1:8787 node --test test/*.test.mjs
//
// They exercise PROTOCOL.md §3 end to end over real WebSockets using Node's
// built-in client (Node 22+), with no WebRTC involved.

import { test } from "node:test";
import assert from "node:assert/strict";

const BASE = (process.env.RELAY_URL || "http://127.0.0.1:8787").replace(/\/$/, "");
const WS_BASE = BASE.replace(/^http/, "ws");

function randomId() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Buffer.from(bytes).toString("base64url");
}

function randomReg() {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return Buffer.from(bytes).toString("base64url");
}

// Small helper wrapping a WebSocket in an async message queue.
function connect(path, headers) {
  const ws = new WebSocket(WS_BASE + path, headers ? { headers } : undefined);
  const queue = [];
  const waiters = [];
  const push = (item) => {
    const w = waiters.shift();
    if (w) w.resolve(item);
    else queue.push(item);
  };
  ws.addEventListener("message", (ev) => push({ msg: JSON.parse(ev.data) }));
  ws.addEventListener("close", (ev) => push({ close: { code: ev.code, reason: ev.reason } }));
  ws.addEventListener("error", () => {});
  const opened = new Promise((resolve, reject) => {
    ws.addEventListener("open", () => resolve(), { once: true });
    ws.addEventListener("error", (e) => reject(e), { once: true });
  });
  return {
    ws,
    opened,
    send: (obj) => ws.send(JSON.stringify(obj)),
    close: () => ws.close(1000, "test"),
    next(timeoutMs = 5000) {
      if (queue.length) return Promise.resolve(queue.shift());
      return new Promise((resolve, reject) => {
        const t = setTimeout(() => reject(new Error("timeout waiting for message on " + path)), timeoutMs);
        waiters.push({ resolve: (v) => { clearTimeout(t); resolve(v); } });
      });
    },
    async nextMsg(timeoutMs) {
      const item = await this.next(timeoutMs);
      if (!item.msg) throw new Error("expected message, got close " + JSON.stringify(item.close));
      return item.msg;
    },
    async nextClose(timeoutMs) {
      for (;;) {
        const item = await this.next(timeoutMs);
        if (item.close) return item.close;
      }
    },
  };
}

// Registers a host for `id` with registration key `reg` and waits for ready.
async function host(id, reg) {
  const h = connect("/v1/host?share=" + id);
  await h.opened;
  h.send({ t: "register", reg });
  const first = await h.nextMsg();
  assert.deepEqual(first, { t: "ready" });
  return h;
}

test("healthz and security headers", async () => {
  const res = await fetch(BASE + "/healthz");
  assert.equal(res.status, 200);
  assert.equal(await res.text(), "ok");
  assert.equal(res.headers.get("referrer-policy"), "no-referrer");
  assert.match(res.headers.get("content-security-policy"), /default-src 'none'/);
  assert.equal(res.headers.get("x-content-type-options"), "nosniff");
});

test("share page is served for any well-formed id and never for malformed ones", async () => {
  const ok = await fetch(BASE + "/s/" + randomId());
  assert.equal(ok.status, 200);
  assert.match(ok.headers.get("content-type"), /text\/html/);
  const body = await ok.text();
  assert.match(body, /Open in Latchway/);
  assert.match(body, /share\.js/);

  const bad = await fetch(BASE + "/s/not-a-real-id");
  assert.equal(bad.status, 404);
});

test("non-canonical share ids are rejected everywhere", async () => {
  // 0xFF×16 is "__________________ _w"; flipping the last char to "x" keeps
  // the same bytes with a stray padding bit.
  const canon = Buffer.alloc(16, 0xff).toString("base64url");
  const alt = canon.slice(0, 21) + "x";
  assert.notEqual(alt, canon);
  assert.equal(Buffer.from(alt, "base64url").toString("hex"), "ff".repeat(16));
  assert.equal((await fetch(BASE + "/s/" + alt)).status, 404);
  assert.equal((await fetch(BASE + "/v1/status/" + alt)).status, 404);
  assert.equal((await fetch(BASE + "/v1/host?share=" + alt)).status, 400);
});

test("assetlinks is an empty list until fingerprints are configured", async () => {
  const res = await fetch(BASE + "/.well-known/assetlinks.json");
  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-type"), /application\/json/);
  const body = await res.json();
  assert.ok(Array.isArray(body));
});

test("status reports inactive shares and rejects bad ids", async () => {
  const res = await fetch(BASE + "/v1/status/" + randomId());
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { active: false });
  const bad = await fetch(BASE + "/v1/status/short");
  assert.equal(bad.status, 404);
});

test("join without a host gets not_found and is closed", async () => {
  const j = connect("/v1/join/" + randomId());
  await j.opened;
  const msg = await j.nextMsg();
  assert.deepEqual(msg, { t: "error", code: "not_found" });
  const close = await j.nextClose();
  assert.equal(close.code, 1008);
});

test("a host must register first; a bad registration is a protocol error", async () => {
  const id = randomId();
  const h = connect("/v1/host?share=" + id);
  await h.opened;
  h.send({ t: "sig", sid: "x", d: "y" });
  assert.deepEqual(await h.nextMsg(), { t: "error", code: "protocol" });
  await h.nextClose();

  const h2 = connect("/v1/host?share=" + id);
  await h2.opened;
  h2.send({ t: "register", reg: "tooshort" });
  assert.deepEqual(await h2.nextMsg(), { t: "error", code: "protocol" });
  await h2.nextClose();

  // An unregistered host does not make the share active.
  const h3 = connect("/v1/host?share=" + id);
  await h3.opened;
  assert.deepEqual(await (await fetch(BASE + "/v1/status/" + id)).json(), { active: false });
  h3.close();
});

test("host registration, join, bidirectional sig relay, leave", async () => {
  const id = randomId();
  const reg = randomReg();
  const h = await host(id, reg);

  const st = await (await fetch(BASE + "/v1/status/" + id)).json();
  assert.deepEqual(st, { active: true });

  const joiner = connect("/v1/join/" + id);
  await joiner.opened;
  const joined = await joiner.nextMsg();
  assert.equal(joined.t, "joined");
  assert.match(joined.sid, /^[A-Za-z0-9_-]{16}$/);
  // Joiners receive STUN only, never credentials.
  assert.ok(Array.isArray(joined.ice) && joined.ice.length >= 1);
  for (const s of joined.ice) {
    assert.ok(s.urls.every((u) => u.startsWith("stun:")), "joiner ice must be stun only");
    assert.equal(s.username, undefined);
    assert.equal(s.credential, undefined);
  }

  const join = await h.nextMsg();
  assert.equal(join.t, "join");
  assert.equal(join.sid, joined.sid);
  assert.ok(Array.isArray(join.ice) && join.ice.length >= 1);

  // joiner -> host (joiner needn't include sid; server adds it)
  joiner.send({ t: "sig", d: "hello-from-joiner" });
  assert.deepEqual(await h.nextMsg(), { t: "sig", sid: joined.sid, d: "hello-from-joiner" });

  // host -> joiner
  h.send({ t: "sig", sid: joined.sid, d: "hello-from-host" });
  assert.deepEqual(await joiner.nextMsg(), { t: "sig", sid: joined.sid, d: "hello-from-host" });

  // keepalive
  h.send({ t: "ping" });
  assert.deepEqual(await h.nextMsg(), { t: "pong" });

  // host sends sig to unknown sid: told to leave
  h.send({ t: "sig", sid: "nope", d: "x" });
  assert.deepEqual(await h.nextMsg(), { t: "leave", sid: "nope" });

  // host drops the joiner
  h.send({ t: "leave", sid: joined.sid });
  assert.deepEqual(await joiner.nextMsg(), { t: "error", code: "closed" });
  const close = await joiner.nextClose();
  assert.equal(close.code, 1008);

  // the host is told once the joiner socket is gone
  assert.deepEqual(await h.nextMsg(), { t: "leave", sid: joined.sid });

  h.close();
});

test("joiner disconnect notifies host; host disconnect closes joiners with host_gone", async () => {
  const id = randomId();
  const h = await host(id, randomReg());

  const j1 = connect("/v1/join/" + id);
  await j1.opened;
  const joined1 = await j1.nextMsg();
  await h.nextMsg(); // join

  j1.close();
  assert.deepEqual(await h.nextMsg(), { t: "leave", sid: joined1.sid });

  const j2 = connect("/v1/join/" + id);
  await j2.opened;
  await j2.nextMsg();
  await h.nextMsg();

  h.close();
  assert.deepEqual(await j2.nextMsg(), { t: "error", code: "host_gone" });
  await j2.nextClose();
});

test("the same registration key replaces the host; a different one is forbidden", async () => {
  const id = randomId();
  const reg = randomReg();
  const h1 = await host(id, reg);

  // Someone who merely knows the share id cannot take it over...
  const imp = connect("/v1/host?share=" + id);
  await imp.opened;
  imp.send({ t: "register", reg: randomReg() });
  assert.deepEqual(await imp.nextMsg(), { t: "error", code: "forbidden" });
  await imp.nextClose();
  // ...and the real host is unaffected.
  h1.send({ t: "ping" });
  assert.deepEqual(await h1.nextMsg(), { t: "pong" });

  // The originating device can re-register (changed network, say).
  const h2 = await host(id, reg);
  assert.deepEqual(await h1.nextMsg(), { t: "error", code: "replaced" });
  await h1.nextClose();

  // The share is still active through h2.
  const j = connect("/v1/join/" + id);
  await j.opened;
  assert.equal((await j.nextMsg()).t, "joined");
  assert.equal((await h2.nextMsg()).t, "join");
  j.close();
  await h2.nextMsg(); // leave

  // The binding outlives the host: with nobody live, a stranger still
  // cannot claim the id.
  h2.close();
  await new Promise((r) => setTimeout(r, 200));
  const imp2 = connect("/v1/host?share=" + id);
  await imp2.opened;
  imp2.send({ t: "register", reg: randomReg() });
  assert.deepEqual(await imp2.nextMsg(), { t: "error", code: "forbidden" });
  await imp2.nextClose();

  // But the owner can come back.
  const h3 = await host(id, reg);
  h3.close();
});

test("oversized payloads and malformed frames are rejected", async () => {
  const id = randomId();
  const h = await host(id, randomReg());

  const j = connect("/v1/join/" + id);
  await j.opened;
  await j.nextMsg();
  await h.nextMsg();

  j.send({ t: "sig", d: "x".repeat(16 * 1024 + 1) });
  assert.deepEqual(await j.nextMsg(), { t: "error", code: "rate_limited" });
  await j.nextClose();
  assert.equal((await h.nextMsg()).t, "leave");

  const j2 = connect("/v1/join/" + id);
  await j2.opened;
  await j2.nextMsg();
  await h.nextMsg();
  j2.ws.send("this is not json");
  assert.deepEqual(await j2.nextMsg(), { t: "error", code: "protocol" });
  const close = await j2.nextClose();
  assert.equal(close.code, 1002);
  await h.nextMsg(); // leave

  h.close();
});

test("per-IP joiner cap yields busy", async () => {
  const id = randomId();
  const h = await host(id, randomReg());
  const joiners = [];
  for (let i = 0; i < 4; i++) {
    const j = connect("/v1/join/" + id);
    await j.opened;
    assert.equal((await j.nextMsg()).t, "joined");
    await h.nextMsg();
    joiners.push(j);
  }
  const extra = connect("/v1/join/" + id);
  await extra.opened;
  assert.deepEqual(await extra.nextMsg(), { t: "error", code: "busy" });
  await extra.nextClose();
  for (const j of joiners) j.close();
  h.close();
});

test("a foreign Origin header is refused", async () => {
  const id = randomId();
  const h = await host(id, randomReg());
  const j = connect("/v1/join/" + id, { Origin: "https://evil.example" });
  let failed = false;
  try {
    await j.opened;
  } catch {
    failed = true;
  }
  if (!failed) {
    // Some runtimes surface the refused upgrade as an immediate close.
    const item = await j.next();
    assert.ok(item.close, "expected the connection to be refused");
  }
  h.close();
});

test("bad share ids and non-websocket requests are refused at the edge", async () => {
  const r1 = await fetch(BASE + "/v1/host?share=tooshort");
  assert.equal(r1.status, 400);
  const r2 = await fetch(BASE + "/v1/host?share=" + randomId());
  assert.equal(r2.status, 426);
  const r3 = await fetch(BASE + "/v1/join/" + randomId());
  assert.equal(r3.status, 426);
  const r4 = await fetch(BASE + "/v1/nothing");
  assert.equal(r4.status, 404);
  const r5 = await fetch(BASE + "/healthz", { method: "POST" });
  assert.equal(r5.status, 405);
});
