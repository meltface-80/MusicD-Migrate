"use strict";
/*
 * Pairing with a Roon Core, and the MOO session that follows.
 *
 * There is no Roon Core in CI or in this container, so everything here runs
 * against a SCRIPTED Core behind the socket seam — which is why the seam
 * exists. The assertions are on the exact frames sent, because that is the
 * part a real Core will judge: a registration missing `required_services` is
 * refused, and a ping left unanswered gets the extension dropped as
 * unresponsive, both of which read as "Roon does not work" rather than as a
 * bug here.
 *
 * Discovery is the exception: it is exercised over a REAL UDP socket against a
 * fake Core on loopback, because the packet layout and the socket options are
 * exactly what a mock would assume rather than check.
 */
const test = require("node:test");
const assert = require("node:assert");
const dgram = require("node:dgram");
const P = require("../../lib/roon-proto");
const { RoonCore, MooSession, SessionPool, NotPairedError, STAGE,
        discoverCores, memoryStore } = require("../../lib/roon-core");

const tick = () => new Promise((r) => setImmediate(r));
const settle = async (n) => { for (let i = 0; i < (n || 6); i++) await tick(); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * A Core that answers whatever `answer(msg, body)` says, over a fake socket.
 *
 * Replies are delivered asynchronously, as a socket's would be: a Core that
 * answered inside `send()` would hide any ordering bug in the session.
 */
/**
 * A scripted Roon Core.
 *
 * It can be connected to MORE THAN ONCE, and that is not a convenience: the
 * only reason "press Find my Roon Core again" could be broken for two
 * releases without a test noticing is that this fake could not reconnect, so
 * no test ever asked it to. A fake that cannot do what the real thing does is
 * how a bug hides behind a green suite.
 */
function scriptedCore(answer) {
  let handlers = null;
  let closed = null;
  const sent = [];

  const socket = {
    send(buf) {
      const msg = P.parseMoo(buf);
      assert.ok(msg, "everything put on the wire must be a valid MOO frame");
      sent.push(msg);
      if (msg.verb !== P.REQUEST) return;   // our own replies to the Core
      setImmediate(() => {
        if (closed) return;
        const a = answer(msg, P.mooJson(msg));
        if (!a) return;                      // deliberate silence
        handlers.onFrame(P.encodeMoo(a.verb || P.COMPLETE, a.name,
          Number(msg.requestId),
          a.body === undefined ? null : Buffer.from(JSON.stringify(a.body), "utf8")));
      });
    },
    close(reason) {
      if (closed) return;
      closed = reason || "closed";
      setImmediate(() => handlers.onClose(closed));
    },
  };

  return {
    sent,
    url: null,
    /** How many times something has connected. A retry has to make a new one. */
    connects: 0,
    get closedWith() { return closed; },
    connect(url, h) {
      handlers = h;
      this.url = url;
      this.connects++;
      closed = null;      // a fresh connection is not a closed one
      setImmediate(() => h.onOpen());
      return socket;
    },
    /** A request the CORE makes of us, such as a ping. */
    coreRequests(service, method, requestId, body) {
      handlers.onFrame(P.encodeRequest(service, method, requestId, body));
    },
    drop(reason) { socket.close(reason || "the Core went away"); },
    requests(name) { return sent.filter((m) => m.verb === P.REQUEST && m.name === name); },
  };
}

/** The usual Core: answers info, then Registered with a token. */
function happyCore(o) {
  const opts = o || {};
  return scriptedCore((msg, body) => {
    if (msg.name === "info") {
      return { name: "Success", body: { core_id: "core-1", display_name: "Study Mac" } };
    }
    if (msg.name === "register") {
      opts.sawToken = body && body.token;
      opts.sawRegistration = body;
      if (opts.silentRegister) return null;
      return { name: "Registered",
               body: { core_id: "core-1", display_name: "Study Mac", token: "tok-77" } };
    }
    if (msg.name === "browse") return { name: "Success", body: { action: "list", list: { count: 3 } } };
    if (msg.name === "load") return { name: "Success", body: { items: [], list: { count: 0 } } };
    return { name: "InvalidRequest" };
  });
}

function coreWith(fake, o) {
  return new RoonCore(Object.assign({
    connect: fake.connect.bind(fake),
    discover: async () => [{ host: "10.0.0.5", port: 9330, uniqueId: "core-1",
                             displayName: "Study Mac" }],
    schedule: () => null,     // no timers in tests unless one is asked for
  }, o || {}));
}

// ------------------------------------------------------------------ pairing

test("pairing asks who the Core is, then registers, then is paired", async () => {
  const fake = happyCore();
  const store = memoryStore();
  const core = coreWith(fake, { store });
  core.start();
  await settle(10);

  assert.strictEqual(fake.url, "ws://10.0.0.5:9330/api");
  assert.deepStrictEqual(fake.sent.filter((m) => m.verb === P.REQUEST).map((m) => m.name),
    ["info", "register"], "info first: it is what names the Core before approval");

  const reg = P.mooJson(fake.requests("register")[0]);
  assert.strictEqual(reg.extension_id, "com.musicd.migrate");
  assert.deepStrictEqual(reg.required_services, ["com.roonlabs.browse:1"],
    "browse is required; a Core that cannot browse is no use to a migration");
  assert.deepStrictEqual(reg.provided_services, ["com.roonlabs.ping:1"],
    "advertising a service means answering it, so advertise only ping");
  assert.strictEqual(reg.token, undefined, "no token on a first pair");

  assert.strictEqual(core.status.stage, STAGE.PAIRED);
  assert.strictEqual(core.status.coreName, "Study Mac");
  assert.strictEqual(core.isPaired, true);
  assert.strictEqual(store.tokenFor("core-1"), "tok-77",
    "the token is persisted, so approval happens once per Core ever");
});

test("the remembered token is presented and no approval is asked for again", async () => {
  const store = memoryStore();
  store.saveToken("core-1", "tok-77");
  const fake = happyCore();
  const core = coreWith(fake, { store });
  core.start();
  await settle(10);

  const reg = P.mooJson(fake.requests("register")[0]);
  assert.strictEqual(reg.token, "tok-77", "without this the user is asked to approve again");
  assert.ok(!/Enable/.test(core.status.detail),
    "a reconnect must not tell the user to go and tick a box they already ticked");
});

test("a remembered address skips discovery entirely", async () => {
  const store = memoryStore();
  store.saveLastCore("192.168.1.9", 9100);
  const fake = happyCore();
  let discoveries = 0;
  const core = coreWith(fake, { store, discover: async () => { discoveries++; return []; } });
  core.start();
  await settle(10);
  assert.strictEqual(discoveries, 0, "broadcasting for a Core we already know is waste");
  assert.strictEqual(fake.url, "ws://192.168.1.9:9100/api");
});

test("waiting for approval is a state, not an error or a hang", async () => {
  const opts = { silentRegister: true };
  const fake = happyCore(opts);
  const core = coreWith(fake, {});
  core.start();
  await settle(10);

  assert.strictEqual(core.status.stage, STAGE.AWAITING_APPROVAL);
  assert.match(core.status.detail, /Settings/,
    "the message has to say where to go, not just that something is pending");

  // Registration has NO deadline on purpose: a first pair takes as long as the
  // user takes to walk to the Roon window. A 90-second one would fail the
  // pair, close the socket and start over while they were doing it.
  await sleep(60);
  assert.strictEqual(core.status.stage, STAGE.AWAITING_APPROVAL,
    "silence from the Core is the user not having ticked the box yet");

  await assert.rejects(() => core.browse({ hierarchy: "albums" }),
    (e) => e instanceof NotPairedError && /enabled in Roon/.test(e.message),
    "and a read in the meantime says which of the two things is wrong");
});

test("a refused registration is reported and the socket is closed for a retry", async () => {
  const fake = scriptedCore((msg) => {
    if (msg.name === "info") return { name: "Success", body: { core_id: "c" } };
    return { name: "InvalidRequest", body: { message: "extension not permitted" } };
  });
  const delays = [];
  const core = coreWith(fake, { schedule: (fn, ms) => { delays.push(ms); return null; } });
  core.start();
  await settle(12);

  assert.strictEqual(core.status.stage, STAGE.ERROR);
  assert.match(core.status.detail, /InvalidRequest/,
    "Roon's own word for the refusal is the most useful thing to show");
  assert.ok(fake.closedWith, "the socket is closed so the reconnect is driven from one place");
  await settle(4);
  assert.deepStrictEqual(delays, [1000], "and a retry is scheduled");
});

// ------------------------------------------------------------- the session

test("a ping from the Core is answered, or the extension is dropped", async () => {
  const fake = happyCore();
  const core = coreWith(fake, {});
  core.start();
  await settle(10);

  fake.coreRequests(P.SERVICES.PING, "ping", 4242);
  await settle(4);

  const answer = fake.sent.find((m) => m.requestId === "4242");
  assert.ok(answer, "an unanswered ping gets this extension dropped as unresponsive");
  assert.strictEqual(answer.verb, P.COMPLETE);
  assert.strictEqual(answer.name, "Success");
});

test("a service we do not provide is refused rather than ignored", async () => {
  const fake = happyCore();
  const core = coreWith(fake, {});
  core.start();
  await settle(10);

  fake.coreRequests("com.roonlabs.settings:1", "get_settings", 99);
  await settle(4);

  const answer = fake.sent.find((m) => m.requestId === "99");
  assert.ok(answer, "silence leaves the Core waiting on a request forever");
  assert.strictEqual(answer.name, "InvalidRequest");
});

test("a CONTINUE keeps a request open and a COMPLETE closes it", () => {
  // This is how every Roon subscription streams. Removing the handler on a
  // CONTINUE would deliver the first update and silently drop the rest.
  const sent = [];
  const session = new MooSession({ send: (b) => sent.push(b), close: () => {} });
  const seen = [];
  const id = session.send("svc:1", "subscribe", { a: 1 }, (m) => seen.push(m && m.name));

  session.receive(P.encodeMoo(P.CONTINUE, "Changed", id, null));
  session.receive(P.encodeMoo(P.CONTINUE, "Changed", id, null));
  session.receive(P.encodeMoo(P.COMPLETE, "Unsubscribed", id, null));
  session.receive(P.encodeMoo(P.CONTINUE, "Changed", id, null));   // after the last word

  assert.deepStrictEqual(seen, ["Changed", "Changed", "Unsubscribed"]);
});

test("a call with no deadline waits, and one with a deadline gives up", async () => {
  const session = new MooSession({ send: () => {}, close: () => {} });

  let settled = false;
  const patient = session.call("svc:1", "slow", null, { timeoutMs: 0 });
  patient.catch(() => { settled = true; });
  await sleep(60);
  assert.strictEqual(settled, false, "timeoutMs 0 means wait — registration needs it");

  await assert.rejects(() => session.call("svc:1", "slow", null, { timeoutMs: 20 }),
    /did not answer/);

  // Settled deliberately: a promise left pending forever would drain the test
  // runner's event loop and cancel everything after it.
  session.die("end of test");
  await assert.rejects(() => patient, /Lost the connection/);
});

test("losing the connection wakes every pending call instead of hanging one", async () => {
  const session = new MooSession({ send: () => {}, close: () => {} });
  const a = session.call("svc:1", "one", null, { timeoutMs: 0 });
  const b = session.call("svc:1", "two", null, { timeoutMs: 0 });
  session.die("the Core went away");
  await assert.rejects(() => a, /Lost the connection/);
  await assert.rejects(() => b, /Lost the connection/);
});

test("a dropped connection is reported and retried with a widening backoff", async () => {
  const fake = happyCore();
  const delays = [];
  const core = coreWith(fake, { schedule: (fn, ms) => { delays.push(ms); return null; } });
  core.start();
  await settle(10);
  assert.strictEqual(core.isPaired, true);

  fake.drop("cable");
  await settle(4);
  assert.strictEqual(core.status.stage, STAGE.ERROR);
  assert.match(core.status.detail, /cable/);
  assert.strictEqual(core.isPaired, false);
  assert.deepStrictEqual(delays, [1000]);
});

test("pressing Find my Roon Core again after a dead attempt really tries again", async () => {
  // "0.3.0 fails to connect to Roon even after enabling again in Roon
  // extensions." start() returned immediately whenever `_running` was already
  // true, and NOTHING cleared that flag but stop(), which only the server's
  // own shutdown calls. So the first attempt to be interrupted — the process
  // frozen while the user was in Roon clicking Enable, a Core that went away,
  // a dropped socket — left the app unable to try again for the rest of its
  // life. Every later press of the button did literally nothing, silently.
  const fake = happyCore();
  const core = coreWith(fake, { store: memoryStore() });
  core.start();
  await settle(10);
  assert.strictEqual(core.isPaired, true);

  // The socket dies with no reconnect pending — `schedule` is a no-op here,
  // which is the same position a frozen process wakes up in.
  fake.drop("the process was frozen while you were in Roon");
  await settle(4);
  assert.strictEqual(core.isPaired, false);

  const before = fake.connects;
  core.start();
  await settle(10);
  assert.ok(fake.connects > before,
    "the button opened a new connection: " + before + " -> " + fake.connects);
  assert.strictEqual(core.isPaired, true, "and it paired again");
});

test("pressing it while a live pairing waits for approval does not restart it", async () => {
  // The idempotence worth keeping: a double tap must not tear down a live
  // socket that is waiting for the user to click Enable in Roon, because the
  // approval comes back on THAT socket.
  const opts = { silentRegister: true };
  const fake = happyCore(opts);
  const core = coreWith(fake, { store: memoryStore() });
  core.start();
  await settle(10);
  assert.strictEqual(core.status.stage, STAGE.AWAITING_APPROVAL);

  const before = fake.connects;
  core.start();
  await settle(6);
  // The socket ITSELF is the assertion: whether the live socket was closed
  // under the user is what matters, because Roon's approval comes back on
  // that socket and nowhere else. Mirrors RoonCoreTest.kt, where a queued
  // reconnect is not observable at all — the net thread is parked in
  // register() waiting for a reply that never comes.
  assert.strictEqual(fake.closedWith, null, "the live pairing socket was left open");
  assert.strictEqual(fake.connects, before, "and nothing reconnected");
  assert.strictEqual(core.status.stage, STAGE.AWAITING_APPROVAL);
});

test("stop() means stop: no reconnect is scheduled", async () => {
  const fake = happyCore();
  const delays = [];
  const core = coreWith(fake, { schedule: (fn, ms) => { delays.push(ms); return null; } });
  core.start();
  await settle(10);
  core.stop();
  await settle(4);
  assert.deepStrictEqual(delays, [], "a stopped client must not keep reaching for the network");
  assert.strictEqual(core.status.stage, STAGE.IDLE);
});

// -------------------------------------------------------------- browse api

test("browse and load go to the browse service and come back parsed", async () => {
  const fake = happyCore();
  const core = coreWith(fake, {});
  core.start();
  await settle(10);

  const body = await core.browse({ hierarchy: "albums", pop_all: true });
  assert.strictEqual(body.action, "list");
  const sentBrowse = fake.requests("browse")[0];
  assert.strictEqual(sentBrowse.service, "com.roonlabs.browse:1");
  assert.deepStrictEqual(P.mooJson(sentBrowse), { hierarchy: "albums", pop_all: true });

  const loaded = await core.load({ hierarchy: "albums", offset: 0, count: 100 });
  assert.deepStrictEqual(loaded.items, []);
});

test("a Roon refusal keeps Roon's own name for it", async () => {
  const fake = scriptedCore((msg) => {
    if (msg.name === "info") return { name: "Success", body: { core_id: "c" } };
    if (msg.name === "register") return { name: "Registered", body: { core_id: "c" } };
    return { name: "InvalidRequest", body: { message: "no such hierarchy" } };
  });
  const core = coreWith(fake, {});
  core.start();
  await settle(10);

  await assert.rejects(() => core.browse({ hierarchy: "nonsense" }),
    (e) => e.roonName === "InvalidRequest" && /no such hierarchy/.test(e.message));
});

test("an empty browse response is an error, not an empty list", async () => {
  // "Roon said nothing" and "Roon said there is nothing" are different, and
  // reporting the first as the second is how a whole library goes missing
  // quietly.
  const fake = scriptedCore((msg) => {
    if (msg.name === "info") return { name: "Success", body: { core_id: "c" } };
    if (msg.name === "register") return { name: "Registered", body: { core_id: "c" } };
    return { name: "Success" };                  // no body at all
  });
  const core = coreWith(fake, {});
  core.start();
  await settle(10);
  await assert.rejects(() => core.browse({ hierarchy: "albums" }), /empty browse response/);
});

test("browse session keys are pooled, not minted per operation", async () => {
  // Roon holds server-side state per key for as long as the extension is
  // connected. Over ten thousand albums, a key per album is ten thousand
  // sessions on the user's Core.
  const pool = new SessionPool();
  const a = pool.acquire();
  const b = pool.acquire();
  assert.notStrictEqual(a, b, "two operations at once must not share a session");
  pool.release(a);
  assert.strictEqual(pool.acquire(), a, "a returned key is reused");

  const core = coreWith(happyCore(), {});
  const keys = [];
  await core.withSession(async (k) => { keys.push(k); });
  await core.withSession(async (k) => { keys.push(k); });
  assert.strictEqual(keys[0], keys[1], "sequential operations reuse one key");

  // Released even when the body throws, or the pool leaks a key per failure.
  await assert.rejects(() => core.withSession(async () => { throw new Error("boom"); }));
  await core.withSession(async (k) => keys.push(k));
  assert.strictEqual(keys[2], keys[0]);
});

// --------------------------------------------------------------- discovery

test("discovery finds a Core over a real UDP socket", async () => {
  // A fake Core on loopback, answering the actual query bytes. A mocked
  // discovery would assume the very things that go wrong here: the packet
  // layout, and whether the reply is recognised at all.
  const fakeCore = dgram.createSocket({ type: "udp4", reuseAddr: true });
  const replies = [];
  await new Promise((resolve) => fakeCore.bind(0, "127.0.0.1", resolve));
  const port = fakeCore.address().port;

  fakeCore.on("message", (buf, rinfo) => {
    const packet = P.parseSoodPacket(buf);
    if (!packet || packet.kind !== "Q") return;
    if (packet.props.query_service_id !== P.ROON_SERVICE_ID) return;
    replies.push(packet.props._tid);
    const parts = [Buffer.from("SOOD", "utf8"), Buffer.from([2, "R".charCodeAt(0)])];
    for (const [k, v] of [["unique_id", "core-abc"], ["http_port", "9330"],
                          ["name", "Study Mac"]]) {
      const n = Buffer.from(k, "utf8");
      const val = Buffer.from(v, "utf8");
      const head = Buffer.alloc(1 + n.length + 2);
      head.writeUInt8(n.length, 0);
      n.copy(head, 1);
      head.writeUInt16BE(val.length, 1 + n.length);
      parts.push(head, val);
    }
    const reply = Buffer.concat(parts);
    fakeCore.send(reply, rinfo.port, rinfo.address);
  });

  try {
    const found = await discoverCores({ timeoutMs: 600, port, targets: ["127.0.0.1"] });
    assert.strictEqual(found.length, 1, "one Core, however many query bursts went out");
    assert.strictEqual(found[0].uniqueId, "core-abc");
    assert.strictEqual(found[0].port, 9330);
    assert.strictEqual(found[0].displayName, "Study Mac");
    assert.strictEqual(found[0].host, "127.0.0.1");
    assert.ok(replies.length >= 1, "the fake Core recognised our query");
  } finally {
    fakeCore.close();
  }
});

test("discovery that finds nothing says what to do about it", async () => {
  const found = await discoverCores({ timeoutMs: 200, port: 1, targets: ["127.0.0.1"] });
  assert.deepStrictEqual(found, []);

  const delays = [];
  const core = new RoonCore({
    connect: () => { throw new Error("should not connect"); },
    discover: async () => [],
    schedule: (fn, ms) => { delays.push(ms); return null; },
  });
  core.start();
  await settle(8);
  assert.strictEqual(core.status.stage, STAGE.ERROR);
  assert.match(core.status.detail, /address by hand/,
    "'not found' with no next step is where a user gives up");
  assert.deepStrictEqual(delays, [10000], "and it keeps looking");
});

test("an address typed by hand is used and remembered", async () => {
  const fake = happyCore();
  const store = memoryStore();
  const core = coreWith(fake, { store, discover: async () => {
    throw new Error("discovery must not run when an address was given");
  } });
  core.connectTo("192.168.1.50", 9330);
  await settle(10);
  assert.strictEqual(fake.url, "ws://192.168.1.50:9330/api");
  assert.deepStrictEqual(store.lastCore(), { host: "192.168.1.50", port: 9330 });
  assert.strictEqual(core.isPaired, true);
});

test("waitUntilPaired resolves on pairing and rejects on a failed round", async () => {
  const fake = happyCore();
  const core = coreWith(fake, {});
  const paired = core.waitUntilPaired(2000);
  core.start();
  await paired;
  assert.strictEqual(core.isPaired, true);

  const bad = new RoonCore({
    connect: () => { throw new Error("no route to host"); },
    discover: async () => [{ host: "h", port: 1, uniqueId: "u", displayName: "" }],
    schedule: () => null,
  });
  const waiting = bad.waitUntilPaired(2000);
  bad.start();
  await assert.rejects(() => waiting, /no route to host/);
});
