"use strict";
/*
 * The real transport, over a real socket.
 *
 * Everything else about Roon is tested behind the socket seam, which leaves
 * exactly one piece that a fake can only assume about: webSocketConnect. It
 * is short, and it contains the single easiest mistake in the whole feature —
 * Node hands binary frames over as a Blob unless binaryType says otherwise,
 * and a Blob read as a Buffer is empty. Every MOO frame would fail to parse,
 * and a Core that accepted the connection and then said nothing is exactly
 * what "Roon is broken" looks like.
 *
 * So this drives a whole pairing handshake against a hand-rolled RFC 6455
 * server on loopback. No Roon Core, but a real WebSocket.
 */
const test = require("node:test");
const assert = require("node:assert");
const P = require("../../lib/roon-proto");
const { RoonCore, STAGE, webSocketConnect, memoryStore } = require("../../lib/roon-core");
const { startWsServer } = require("../helpers/ws-server");

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Wait for a condition, or fail the test rather than hang the suite. */
async function until(what, fn, ms) {
  const deadline = Date.now() + (ms || 3000);
  while (Date.now() < deadline) {
    if (fn()) return;
    await sleep(10);
  }
  assert.fail("timed out waiting for " + what);
}

test("a pairing handshake completes over a real WebSocket", async () => {
  const seen = [];
  const server = await startWsServer({
    onFrame(buf, conn, isBinary) {
      const msg = P.parseMoo(buf);
      // A frame that does not parse here is the Blob bug seen from the other
      // side: the client sent something, and it was not a MOO message.
      assert.ok(msg, "the client must put parseable MOO frames on the wire");
      assert.ok(isBinary, "MOO is carried in binary frames");
      seen.push(msg.name);
      const answer = msg.name === "info"
        ? { name: "Success", body: { core_id: "real-1", display_name: "Loopback Core" } }
        : msg.name === "register"
          ? { name: "Registered",
              body: { core_id: "real-1", display_name: "Loopback Core", token: "tok-real" } }
          : { name: "Success", body: { action: "list", list: { count: 7 } } };
      conn.sendBinary(P.encodeMoo(P.COMPLETE, answer.name, Number(msg.requestId),
        Buffer.from(JSON.stringify(answer.body), "utf8")));
    },
  });

  const store = memoryStore();
  store.saveLastCore("127.0.0.1", server.port);
  const core = new RoonCore({ store, connect: webSocketConnect, schedule: () => null });

  try {
    core.start();
    await core.waitUntilPaired(5000);

    assert.strictEqual(core.status.stage, STAGE.PAIRED);
    assert.strictEqual(core.status.coreName, "Loopback Core");
    assert.strictEqual(store.tokenFor("real-1"), "tok-real");
    assert.deepStrictEqual(seen, ["info", "register"]);
    assert.strictEqual(server.connections[0].path, "/api",
      "the extension API lives at /api, not at the root");

    // And a real round-trip afterwards, so the receive path is exercised for
    // something other than the handshake.
    const body = await core.browse({ hierarchy: "albums", pop_all: true });
    assert.strictEqual(body.list.count, 7);
  } finally {
    core.stop();
    await server.close();
  }
});

test("a Core that hangs up is noticed rather than waited on", async () => {
  const server = await startWsServer({
    onFrame(buf, conn) {
      // Answers `info`, then drops the connection instead of registering.
      const msg = P.parseMoo(buf);
      if (msg.name === "info") {
        conn.sendBinary(P.encodeMoo(P.COMPLETE, "Success", Number(msg.requestId),
          Buffer.from(JSON.stringify({ core_id: "c" }), "utf8")));
      } else {
        conn.destroy();
      }
    },
  });

  const store = memoryStore();
  store.saveLastCore("127.0.0.1", server.port);
  const delays = [];
  const core = new RoonCore({ store, connect: webSocketConnect,
    schedule: (fn, ms) => { delays.push(ms); return null; } });

  try {
    core.start();
    await until("the drop to be reported", () => core.status.stage === STAGE.ERROR, 5000);
    assert.strictEqual(core.isPaired, false);
    assert.ok(delays.length >= 1, "a dropped Core is retried, not given up on");
  } finally {
    core.stop();
    await server.close();
  }
});

test("nothing listening is an error, not a hang", async () => {
  // Port 1 on loopback: the connection is refused immediately.
  const store = memoryStore();
  store.saveLastCore("127.0.0.1", 1);
  const core = new RoonCore({ store, connect: webSocketConnect, schedule: () => null });
  try {
    core.start();
    await assert.rejects(() => core.waitUntilPaired(5000), /connection failed|Lost the connection/);
  } finally {
    core.stop();
  }
});
