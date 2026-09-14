"use strict";
/*
 * The two wire formats, tested without a Roon Core.
 *
 * There is no Core in CI and there never will be, so this is the only part of
 * the Roon support that can be checked completely — which is why the codecs
 * were separated out in the first place. The failure these guard against is
 * not a crash: a u16 read little-endian, or a body taken one byte short, makes
 * a Core look absent or a library look empty, and "Roon found nothing" is
 * indistinguishable from "Roon is not running".
 */
const test = require("node:test");
const assert = require("node:assert");
const P = require("../../lib/roon-proto");

// ------------------------------------------------------------------- SOOD

/** Build a reply packet the way a Core does, so the parser can be checked. */
function soodReply(props) {
  const parts = [Buffer.from("SOOD", "utf8"), Buffer.from([2, "R".charCodeAt(0)])];
  for (const [name, value] of Object.entries(props)) {
    const n = Buffer.from(name, "utf8");
    const head = Buffer.alloc(1 + n.length + 2);
    head.writeUInt8(n.length, 0);
    n.copy(head, 1);
    if (value === null) {
      head.writeUInt16BE(0xffff, 1 + n.length);
      parts.push(head);
    } else {
      const v = Buffer.from(String(value), "utf8");
      head.writeUInt16BE(v.length, 1 + n.length);
      parts.push(head, v);
    }
  }
  return Buffer.concat(parts);
}

test("a SOOD query names the Roon service id and nothing else", () => {
  const q = P.buildSoodQuery("abc");
  assert.strictEqual(q.toString("utf8", 0, 4), "SOOD");
  assert.strictEqual(q[4], 2, "protocol version");
  assert.strictEqual(String.fromCharCode(q[5]), "Q");

  // Decoded, not merely searched for. A query whose length fields are written
  // the wrong way round still CONTAINS the service id, and is still answered
  // by no Core whatsoever — which reads as "there is no Roon on this network"
  // and sends the user looking at their Wi-Fi.
  const packet = P.parseSoodPacket(q);
  assert.strictEqual(packet.kind, "Q");
  assert.deepStrictEqual(Object.keys(packet.props).sort(),
    ["_tid", "query_service_id"]);
  assert.strictEqual(packet.props.query_service_id, P.ROON_SERVICE_ID);
  assert.strictEqual(packet.props._tid, "abc");
});

test("a long value in a query survives its own length field", () => {
  // 300 bytes again, from the writing side this time: 0x012C little-endian is
  // 0x2C01, and the property walk then runs off the end of the packet.
  const packet = P.parseSoodPacket(P.buildSoodQuery("t".repeat(300)));
  assert.ok(packet, "the query must be parseable by the same walker");
  assert.strictEqual(packet.props._tid.length, 300);
});

test("a SOOD reply is parsed, including a null value", () => {
  const props = P.parseSoodReply(soodReply({
    unique_id: "1f2e", http_port: "9330", name: "Study", tid: null,
  }));
  assert.strictEqual(props.unique_id, "1f2e");
  assert.strictEqual(props.http_port, "9330");
  assert.strictEqual(props.name, "Study");
  assert.strictEqual(props.tid, null, "0xFFFF is null, not empty string");
});

test("an empty string value is not the same as a null one", () => {
  const props = P.parseSoodReply(soodReply({ unique_id: "x", http_port: "1", name: "" }));
  assert.strictEqual(props.name, "", "0 length is an empty value");
  assert.notStrictEqual(props.name, null);
});

test("a two-byte length is read big-endian", () => {
  // 300 bytes: 0x012C. Read the other way round it is 11265 and the parse
  // walks off the end, which is what a little-endian read looks like.
  const long = "n".repeat(300);
  const props = P.parseSoodReply(soodReply({ unique_id: "x", http_port: "1", name: long }));
  assert.strictEqual(props.name.length, 300);
});

test("packets that are not SOOD replies are ignored rather than thrown on", () => {
  assert.strictEqual(P.parseSoodReply(Buffer.from("hello world")), null);
  assert.strictEqual(P.parseSoodReply(Buffer.alloc(0)), null);
  assert.strictEqual(P.parseSoodReply(null), null);
  // Our own query, which arrives back on a broadcast socket.
  assert.strictEqual(P.parseSoodReply(P.buildSoodQuery("t")), null,
    "a query is not a reply");
  // Truncated mid-value.
  const good = soodReply({ unique_id: "abcdef", http_port: "9330" });
  assert.strictEqual(P.parseSoodReply(good.subarray(0, good.length - 3)), null);
});

test("a Core is only usable if it said where to connect", () => {
  const withAddr = P.soodCore({ unique_id: "u", http_port: "9330", _replyaddr: "10.0.0.5" });
  assert.deepStrictEqual(withAddr,
    { host: "10.0.0.5", port: 9330, uniqueId: "u", displayName: "" });

  // No _replyaddr: the packet's own source address is used.
  assert.strictEqual(P.soodCore({ unique_id: "u", http_port: "9330" }, "10.0.0.9").host,
    "10.0.0.9");

  assert.strictEqual(P.soodCore({ unique_id: "u" }, "10.0.0.9"), null,
    "no port means nowhere to connect");
  assert.strictEqual(P.soodCore({ http_port: "9330" }, "10.0.0.9"), null);
  assert.strictEqual(P.soodCore({ unique_id: "u", http_port: "0" }, "10.0.0.9"), null);
  assert.strictEqual(P.soodCore(null, "10.0.0.9"), null);
});

// -------------------------------------------------------------------- MOO

test("a MOO request round-trips", () => {
  const frame = P.encodeRequest("com.roonlabs.browse:1", "load", 7,
    { hierarchy: "albums", offset: 0, count: 100 });
  const msg = P.parseMoo(frame);
  assert.strictEqual(msg.verb, "REQUEST");
  assert.strictEqual(msg.service, "com.roonlabs.browse:1");
  assert.strictEqual(msg.name, "load");
  assert.strictEqual(msg.requestId, "7");
  assert.deepStrictEqual(P.mooJson(msg), { hierarchy: "albums", offset: 0, count: 100 });
});

test("a response carries a result name and no service", () => {
  const frame = P.encodeMoo("COMPLETE", "Registered", 1,
    Buffer.from('{"core_id":"c1","token":"t"}', "utf8"));
  const msg = P.parseMoo(frame);
  assert.strictEqual(msg.verb, "COMPLETE");
  assert.strictEqual(msg.service, null, "only a REQUEST names a service");
  assert.strictEqual(msg.name, "Registered");
  assert.strictEqual(P.mooJson(msg).token, "t");
});

test("a bodiless message parses, and its body is null rather than empty", () => {
  const msg = P.parseMoo(P.encodeMoo("COMPLETE", "Success", 3, null));
  assert.strictEqual(msg.name, "Success");
  assert.strictEqual(msg.body, null);
  assert.strictEqual(msg.bodyText, null);
  assert.strictEqual(P.mooJson(msg), null);
});

test("a body containing a blank line is read whole", () => {
  // The reason this parser counts bytes instead of splitting on "\n\n". An
  // album title with a newline in it would otherwise truncate the message and
  // the whole page of albums would be lost as unparseable.
  const body = Buffer.from(JSON.stringify({ title: "one\n\ntwo", n: 1 }), "utf8");
  const msg = P.parseMoo(P.encodeMoo("COMPLETE", "Success", 4, body));
  assert.deepStrictEqual(P.mooJson(msg), { title: "one\n\ntwo", n: 1 });
});

test("a body is measured in bytes, not characters", () => {
  // "é" is two bytes. Content-Length written as a character count would cut
  // the JSON short and every non-ASCII album title in a page would break it.
  const body = Buffer.from(JSON.stringify({ title: "Café Bleu — Émilie" }), "utf8");
  const frame = P.encodeMoo("COMPLETE", "Success", 5, body);
  assert.ok(frame.includes(Buffer.from("Content-Length: " + body.length)),
    "the header states the byte length");
  assert.strictEqual(P.mooJson(P.parseMoo(frame)).title, "Café Bleu — Émilie");
});

test("headers other than the two that matter are kept", () => {
  const frame = Buffer.from(
    "MOO/1 COMPLETE Success\nRequest-Id: 2\nLogging: async\n\n", "utf8");
  const msg = P.parseMoo(frame);
  assert.strictEqual(msg.headers.Logging, "async");
  assert.strictEqual(msg.requestId, "2");
});

test("a truncated frame is not a message", () => {
  const body = Buffer.from('{"a":1}', "utf8");
  const frame = P.encodeMoo("COMPLETE", "Success", 6, body);
  // Claims seven body bytes, carries four.
  assert.strictEqual(P.parseMoo(frame.subarray(0, frame.length - 3)), null,
    "a short body must not be handed on as a good message");
  // No blank line at all: still arriving.
  assert.strictEqual(P.parseMoo(Buffer.from("MOO/1 COMPLETE Success\n", "utf8")), null);
  // No Request-Id: nothing to correlate it with.
  assert.strictEqual(P.parseMoo(Buffer.from("MOO/1 COMPLETE Success\n\n", "utf8")), null);
});

test("something that is not MOO at all is ignored", () => {
  assert.strictEqual(P.parseMoo(Buffer.from("GET / HTTP/1.1\n\n", "utf8")), null);
  assert.strictEqual(P.parseMoo(Buffer.alloc(0)), null);
  assert.strictEqual(P.parseMoo(null), null);
  assert.strictEqual(P.parseMoo(Buffer.from("MOO/1 COMPLETE\n\n", "utf8")), null,
    "a header line with no result name");
});

test("a malformed JSON body is null, not a thrown parse error", () => {
  const msg = P.parseMoo(P.encodeMoo("COMPLETE", "Success", 8,
    Buffer.from("{not json", "utf8")));
  assert.strictEqual(msg.bodyText, "{not json");
  assert.strictEqual(P.mooJson(msg), null);
});
