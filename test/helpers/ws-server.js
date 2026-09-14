"use strict";
/*
 * A minimal RFC 6455 server, for testing lib/roon-core.js's real transport.
 *
 * Node has a WebSocket CLIENT and no server, and the Roon session runs over
 * one. Without something to connect to, `webSocketConnect` is the one part of
 * the Roon support that could only be asserted about rather than run — and it
 * contains a trap worth running: Node hands binary frames over as a **Blob**
 * unless binaryType is set to "arraybuffer", and a Blob read as a Buffer
 * yields nothing. Every MOO frame would fail to parse and the Core would look
 * like it had accepted the connection and then gone quiet.
 *
 * So: just enough of the protocol to speak to one client. Handshake, binary
 * and text frames, ping/pong, close. No extensions, no fragmentation beyond
 * what the client actually sends, no TLS. If this ever needs more than that,
 * it is being asked to do the wrong job.
 */

const http = require("node:http");
const crypto = require("node:crypto");

const GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

const OP_TEXT = 0x1;
const OP_BINARY = 0x2;
const OP_CLOSE = 0x8;
const OP_PING = 0x9;
const OP_PONG = 0xa;

/** One unmasked server frame. */
function frame(opcode, payload) {
  const body = payload || Buffer.alloc(0);
  let head;
  if (body.length < 126) {
    head = Buffer.from([0x80 | opcode, body.length]);
  } else if (body.length < 65536) {
    head = Buffer.alloc(4);
    head[0] = 0x80 | opcode;
    head[1] = 126;
    head.writeUInt16BE(body.length, 2);
  } else {
    head = Buffer.alloc(10);
    head[0] = 0x80 | opcode;
    head[1] = 127;
    head.writeBigUInt64BE(BigInt(body.length), 2);
  }
  return Buffer.concat([head, body]);
}

/**
 * Start a server on an ephemeral loopback port.
 *
 * @param {object} handlers
 * @param {Function} [handlers.onOpen] (conn) => void
 * @param {Function} [handlers.onFrame] (buf, conn, isBinary) => void
 * @param {Function} [handlers.onClose] (conn) => void
 * @returns {Promise<{url:string, port:number, close:Function, connections:Array}>}
 */
function startWsServer(handlers) {
  const h = handlers || {};
  const connections = [];
  const server = http.createServer((req, res) => { res.writeHead(400); res.end(); });

  server.on("upgrade", (req, socket) => {
    const key = req.headers["sec-websocket-key"];
    if (!key) { socket.destroy(); return; }
    const accept = crypto.createHash("sha1").update(key + GUID).digest("base64");
    socket.write(
      "HTTP/1.1 101 Switching Protocols\r\n" +
      "Upgrade: websocket\r\n" +
      "Connection: Upgrade\r\n" +
      "Sec-WebSocket-Accept: " + accept + "\r\n\r\n");

    const conn = {
      path: req.url,
      socket,
      sendBinary(buf) { socket.write(frame(OP_BINARY, buf)); },
      sendText(text) { socket.write(frame(OP_TEXT, Buffer.from(text, "utf8"))); },
      closeNow() { socket.write(frame(OP_CLOSE, Buffer.alloc(0))); socket.end(); },
      destroy() { socket.destroy(); },
    };
    connections.push(conn);

    let buffer = Buffer.alloc(0);
    socket.on("data", (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      for (;;) {
        if (buffer.length < 2) return;
        const opcode = buffer[0] & 0x0f;
        const masked = (buffer[1] & 0x80) !== 0;
        let len = buffer[1] & 0x7f;
        let pos = 2;
        if (len === 126) {
          if (buffer.length < pos + 2) return;
          len = buffer.readUInt16BE(pos);
          pos += 2;
        } else if (len === 127) {
          if (buffer.length < pos + 8) return;
          len = Number(buffer.readBigUInt64BE(pos));
          pos += 8;
        }
        let mask = null;
        if (masked) {
          if (buffer.length < pos + 4) return;
          mask = buffer.subarray(pos, pos + 4);
          pos += 4;
        }
        if (buffer.length < pos + len) return;
        const payload = Buffer.from(buffer.subarray(pos, pos + len));
        if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        buffer = buffer.subarray(pos + len);

        if (opcode === OP_CLOSE) {
          socket.end(frame(OP_CLOSE, Buffer.alloc(0)));
          if (h.onClose) h.onClose(conn);
          return;
        }
        if (opcode === OP_PING) { socket.write(frame(OP_PONG, payload)); continue; }
        if (opcode === OP_PONG) continue;
        if (h.onFrame) h.onFrame(payload, conn, opcode === OP_BINARY);
      }
    });
    socket.on("error", () => { /* a client hanging up mid-frame is not an error here */ });
    socket.on("close", () => { if (h.onClose) h.onClose(conn); });

    if (h.onOpen) h.onOpen(conn);
  });

  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const port = server.address().port;
      resolve({
        port,
        url: "ws://127.0.0.1:" + port,
        connections,
        close: () => new Promise((done) => {
          for (const c of connections) { try { c.destroy(); } catch (e) { void e; } }
          server.close(done);
        }),
      });
    });
  });
}

module.exports = { startWsServer };
