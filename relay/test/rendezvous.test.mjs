// Behavioural tests for the rendezvous Worker, run against `wrangler dev`.
//
//   RELAY_URL=http://127.0.0.1:8787 node --test test/
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

// Small helper wrapping a WebSocket in an async message queue.
function connect(path) {
  const ws = new WebSocket(WS_BASE + path);
  const queue = [];
  const waiters = [];
  let closed = null;
  const push = (item) => {
    const w = waiters.shift();
    if (w) w.resolve(item);
    else queue.push(item);
  };
  ws.addEventListener("message", (ev) => push({ msg: JSON.parse(ev.data) }));
  ws.addEventListener("close", (ev) => {
    closed = { code: ev.code, reason: ev.reason };
    push({ close: closed });
  });
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

test("host registration, join, bidirectional sig relay, leave", async () => {
  const id = randomId();
  const host = connect("/v1/host?share=" + id);
  await host.opened;
  assert.deepEqual(await host.nextMsg(), { t: "ready" });

  const st = await (await fetch(BASE + "/v1/status/" + id)).json();
  assert.deepEqual(st, { active: true });

  const joiner = connect("/v1/join/" + id);
  await joiner.opened;
  const joined = await joiner.nextMsg();
  assert.equal(joined.t, "joined");
  assert.match(joined.sid, /^[A-Za-z0-9_-]{16}$/);
  assert.ok(Array.isArray(joined.ice) && joined.ice.length >= 1);
  assert.ok(joined.ice[0].urls.some((u) => u.startsWith("stun:")));

  const join = await host.nextMsg();
  assert.equal(join.t, "join");
  assert.equal(join.sid, joined.sid);
  assert.ok(Array.isArray(join.ice));

  // joiner -> host (joiner needn't include sid; server adds it)
  joiner.send({ t: "sig", d: "hello-from-joiner" });
  const toHost = await host.nextMsg();
  assert.deepEqual(toHost, { t: "sig", sid: joined.sid, d: "hello-from-joiner" });

  // host -> joiner
  host.send({ t: "sig", sid: joined.sid, d: "hello-from-host" });
  const toJoiner = await joiner.nextMsg();
  assert.deepEqual(toJoiner, { t: "sig", sid: joined.sid, d: "hello-from-host" });

  // keepalive auto-response
  host.send({ t: "ping" });
  assert.deepEqual(await host.nextMsg(), { t: "pong" });

  // host sends sig to unknown sid: told to leave
  host.send({ t: "sig", sid: "nope", d: "x" });
  assert.deepEqual(await host.nextMsg(), { t: "leave", sid: "nope" });

  // host drops the joiner
  host.send({ t: "leave", sid: joined.sid });
  assert.deepEqual(await joiner.nextMsg(), { t: "error", code: "closed" });
  const close = await joiner.nextClose();
  assert.equal(close.code, 1008);

  // the host is told once the joiner socket is gone
  const leave = await host.nextMsg();
  assert.deepEqual(leave, { t: "leave", sid: joined.sid });

  host.close();
});

test("joiner disconnect notifies host; host disconnect closes joiners with host_gone", async () => {
  const id = randomId();
  const host = connect("/v1/host?share=" + id);
  await host.opened;
  await host.nextMsg();

  const j1 = connect("/v1/join/" + id);
  await j1.opened;
  const joined1 = await j1.nextMsg();
  await host.nextMsg(); // join

  j1.close();
  assert.deepEqual(await host.nextMsg(), { t: "leave", sid: joined1.sid });

  const j2 = connect("/v1/join/" + id);
  await j2.opened;
  await j2.nextMsg();
  await host.nextMsg();

  host.close();
  assert.deepEqual(await j2.nextMsg(), { t: "error", code: "host_gone" });
  await j2.nextClose();
});

test("a second host replaces the first", async () => {
  const id = randomId();
  const h1 = connect("/v1/host?share=" + id);
  await h1.opened;
  await h1.nextMsg();
  const h2 = connect("/v1/host?share=" + id);
  await h2.opened;
  assert.deepEqual(await h2.nextMsg(), { t: "ready" });
  assert.deepEqual(await h1.nextMsg(), { t: "error", code: "replaced" });
  await h1.nextClose();

  // the share is still active through h2
  const j = connect("/v1/join/" + id);
  await j.opened;
  assert.equal((await j.nextMsg()).t, "joined");
  assert.equal((await h2.nextMsg()).t, "join");
  j.close();
  h2.close();
});

test("oversized payloads and malformed frames are rejected", async () => {
  const id = randomId();
  const host = connect("/v1/host?share=" + id);
  await host.opened;
  await host.nextMsg();

  const j = connect("/v1/join/" + id);
  await j.opened;
  await j.nextMsg();
  await host.nextMsg();

  j.send({ t: "sig", d: "x".repeat(16 * 1024 + 1) });
  assert.deepEqual(await j.nextMsg(), { t: "error", code: "rate_limited" });
  await j.nextClose();
  assert.equal((await host.nextMsg()).t, "leave");

  const j2 = connect("/v1/join/" + id);
  await j2.opened;
  await j2.nextMsg();
  await host.nextMsg();
  j2.ws.send("this is not json");
  assert.deepEqual(await j2.nextMsg(), { t: "error", code: "protocol" });
  const close = await j2.nextClose();
  assert.equal(close.code, 1002);

  host.close();
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
