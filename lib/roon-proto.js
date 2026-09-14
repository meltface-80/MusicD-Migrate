"use strict";
/*
 * roon-proto.js — Roon's two wire formats, and nothing else.
 *
 * SOOD is how a Core is found: a UDP query multicast to 239.255.90.90:9003,
 * answered by every Core on the segment with its id and the port its
 * extension API listens on.
 *
 * MOO is how it is then talked to: an HTTP-like RPC framing carried in binary
 * WebSocket frames on ws://<core>:<port>/api.
 *
 * Both are byte formats with no official specification outside RoonLabs' own
 * `node-roon-api`, so they are implemented here as pure functions over
 * buffers — no sockets, no state, no clock. That is the point: this is the
 * layer that is easiest to get subtly wrong (a u16 written little-endian, a
 * body read one byte short) and the only layer that can be tested completely
 * without a Roon Core in the room. Everything that needs a Core lives in
 * lib/roon-core.js behind a seam.
 *
 * Ported from the Kotlin in meltface-80/Android-Random-Remote, which has been
 * run against a real Core. The Kotlin port back the other way (the APK) is
 * expected to keep the same shape, so keep the two readable side by side.
 */

/** Roon's discovery multicast group and port. */
const SOOD_PORT = 9003;
const SOOD_MULTICAST = "239.255.90.90";

/** The service id every Roon Core answers a SOOD query for. */
const ROON_SERVICE_ID = "00720724-5143-4a9b-abac-0e50cba674bb";

/** MOO verbs. REQUEST goes in either direction. */
const REQUEST = "REQUEST";
const COMPLETE = "COMPLETE";
const CONTINUE = "CONTINUE";

/** The Roon services this app calls, and the one it answers. */
const SERVICES = {
  REGISTRY: "com.roonlabs.registry:1",
  BROWSE: "com.roonlabs.browse:1",
  IMAGE: "com.roonlabs.image:1",
  PING: "com.roonlabs.ping:1",
  TRANSPORT: "com.roonlabs.transport:2",
};

// --------------------------------------------------------------------- SOOD

/*
 * Packet layout, from sood.js in node-roon-api:
 *
 *     "SOOD" | 0x02 | 'Q' or 'R' | property*
 *     property := name_len:u8 | name | value_len:u16be | value
 *
 * A value length of 0xFFFF means null — which is NOT the same as a value of
 * length zero, and conflating the two would turn "this Core did not say" into
 * "this Core said nothing", a difference that matters for `name`.
 */

function soodProp(name, value) {
  const n = Buffer.from(name, "utf8");
  const v = Buffer.from(value, "utf8");
  const head = Buffer.alloc(1 + n.length + 2);
  head.writeUInt8(n.length & 0xff, 0);
  n.copy(head, 1);
  head.writeUInt16BE(v.length, 1 + n.length);
  return Buffer.concat([head, v]);
}

/**
 * A SOOD query. `_tid` is echoed back by the Core; it is not used to correlate
 * anything here, because every reply is wanted regardless of which burst it
 * answers.
 */
function buildSoodQuery(tid) {
  return Buffer.concat([
    Buffer.from("SOOD", "utf8"),
    Buffer.from([2, "Q".charCodeAt(0)]),
    soodProp("_tid", String(tid || Math.random().toString(16).slice(2))),
    soodProp("query_service_id", ROON_SERVICE_ID),
  ]);
}

/**
 * A SOOD packet of either kind, or null if this is not one.
 *
 * Null rather than a throw: a UDP socket listening on a broadcast port will be
 * handed other protocols' packets, and those are not errors.
 *
 * Queries and replies share the property encoding, and both kinds are parsed
 * here on purpose — it is the only way to check that the QUERY writer got its
 * lengths right, and a query with a mis-written length is answered by no Core
 * at all. That failure looks exactly like "there is no Roon on this network".
 *
 * @returns {{kind: string, props: Object.<string, (string|null)>}|null}
 */
function parseSoodPacket(buf) {
  if (!buf || buf.length < 6) return null;
  if (buf.toString("utf8", 0, 4) !== "SOOD") return null;
  if (buf[4] !== 2) return null;
  const kind = String.fromCharCode(buf[5]);
  if (kind !== "Q" && kind !== "R") return null;

  const props = Object.create(null);
  let pos = 6;
  while (pos < buf.length) {
    const nameLen = buf[pos++];
    if (nameLen === 0 || pos + nameLen > buf.length) return null;
    const name = buf.toString("utf8", pos, pos + nameLen);
    pos += nameLen;
    if (pos + 2 > buf.length) return null;
    const valLen = buf.readUInt16BE(pos);
    pos += 2;
    if (valLen === 0xffff) {
      props[name] = null;
    } else if (valLen === 0) {
      props[name] = "";
    } else if (pos + valLen > buf.length) {
      return null;
    } else {
      props[name] = buf.toString("utf8", pos, pos + valLen);
      pos += valLen;
    }
  }
  return { kind, props };
}

/**
 * The properties of a SOOD REPLY, or null for anything else — including this
 * app's own query, which arrives back on a broadcast socket.
 */
function parseSoodReply(buf) {
  const packet = parseSoodPacket(buf);
  return packet && packet.kind === "R" ? packet.props : null;
}

/**
 * What a reply has to carry to be worth connecting to, or null.
 *
 * `http_port` is the extension API's port and is not optional: without it
 * there is nowhere to connect, so a reply missing it is not a usable Core
 * however well-formed it was.
 */
function soodCore(props, fallbackHost) {
  if (!props) return null;
  const uniqueId = props.unique_id;
  const port = Number(props.http_port);
  const host = props._replyaddr || fallbackHost;
  if (!uniqueId || !Number.isInteger(port) || port <= 0 || !host) return null;
  return { host: String(host), port, uniqueId: String(uniqueId),
           displayName: props.name || props.display_name || "" };
}

// ---------------------------------------------------------------------- MOO

/*
 * Each WebSocket binary frame carries one message, LF line endings:
 *
 *     MOO/1 REQUEST com.roonlabs.browse:1/load
 *     Request-Id: 7
 *     Content-Length: 63
 *     Content-Type: application/json
 *
 *     {"hierarchy":"albums","offset":0,"count":100}
 *
 * Request-Id correlates the two directions. COMPLETE is the last word on an
 * id; CONTINUE keeps it open, which is how a subscription streams. That
 * distinction is load-bearing: a handler removed on CONTINUE would drop every
 * update after the first.
 */

/**
 * @param {string} verb REQUEST | COMPLETE | CONTINUE
 * @param {string} line "service/method" for a request, a result name
 *   ("Success", "Registered", "Changed") otherwise
 * @param {number} requestId
 * @param {Buffer|null} body
 */
function encodeMoo(verb, line, requestId, body, contentType) {
  let header = "MOO/1 " + verb + " " + line + "\n" +
    "Request-Id: " + requestId + "\n";
  if (body) {
    header += "Content-Length: " + body.length + "\n" +
      "Content-Type: " + (contentType || "application/json") + "\n";
  }
  header += "\n";
  const head = Buffer.from(header, "utf8");
  return body ? Buffer.concat([head, body]) : head;
}

/** Encode a request whose body is a JSON object (or nothing). */
function encodeRequest(service, method, requestId, bodyObj) {
  const body = bodyObj === undefined || bodyObj === null
    ? null : Buffer.from(JSON.stringify(bodyObj), "utf8");
  return encodeMoo(REQUEST, service + "/" + method, requestId, body);
}

/**
 * Parse one MOO frame, or null if it is not one.
 *
 * Deliberately byte-oriented rather than a split on "\n\n": the BODY is
 * arbitrary bytes of exactly Content-Length, and a body containing a blank
 * line would break a string split. That is not hypothetical — album titles
 * arrive in here.
 *
 * @returns {{verb:string, service:?string, name:string, requestId:string,
 *            headers:Object, body:?Buffer, bodyText:?string}|null}
 */
function parseMoo(buf) {
  if (!buf || buf.length === 0) return null;

  let verb = null;
  let service = null;
  let name = null;
  let requestId = null;
  let contentLength = null;
  const headers = Object.create(null);

  let start = 0;
  let i = 0;
  let inHeaders = false;

  while (i < buf.length) {
    if (buf[i] !== 0x0a) { i++; continue; }
    const line = buf.toString("utf8", start, i);

    if (!inHeaders) {
      if (!line.startsWith("MOO/")) return null;
      const sp1 = line.indexOf(" ");
      if (sp1 < 0) return null;
      const sp2 = line.indexOf(" ", sp1 + 1);
      if (sp2 < 0) return null;
      verb = line.slice(sp1 + 1, sp2);
      const rest = line.slice(sp2 + 1);
      if (verb === REQUEST) {
        const slash = rest.indexOf("/");
        if (slash < 0) return null;
        service = rest.slice(0, slash);
        name = rest.slice(slash + 1);
      } else {
        name = rest;
      }
      inHeaders = true;
    } else if (line === "") {
      // The blank line ends the headers; the body follows verbatim.
      if (requestId === null) return null;
      let body = null;
      if (contentLength !== null && contentLength > 0) {
        const bodyStart = i + 1;
        if (bodyStart + contentLength > buf.length) return null;
        body = buf.subarray(bodyStart, bodyStart + contentLength);
      }
      return { verb, service, name: name || "", requestId, headers, body,
               bodyText: body ? body.toString("utf8") : null };
    } else {
      const colon = line.indexOf(":");
      if (colon < 0) return null;
      const key = line.slice(0, colon);
      const value = line.slice(colon + 1).replace(/^\s+/, "");
      if (key === "Request-Id") requestId = value;
      else if (key === "Content-Length") {
        const n = parseInt(value, 10);
        contentLength = Number.isFinite(n) ? n : null;
      } else headers[key] = value;
    }

    i++;
    start = i;
  }
  // Ran out of bytes before the blank line: a truncated frame, not a message.
  return null;
}

/** The JSON body of a message, or null. Never throws on a malformed body. */
function mooJson(msg) {
  if (!msg || !msg.bodyText) return null;
  try { return JSON.parse(msg.bodyText); } catch (e) { return null; }
}

module.exports = {
  SOOD_PORT, SOOD_MULTICAST, ROON_SERVICE_ID, SERVICES,
  REQUEST, COMPLETE, CONTINUE,
  buildSoodQuery, parseSoodPacket, parseSoodReply, soodCore,
  encodeMoo, encodeRequest, parseMoo, mooJson,
};
