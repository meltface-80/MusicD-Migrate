"use strict";
/*
 * roon-core.js — finding a Roon Core, pairing with it, and keeping the MOO
 * session up.
 *
 * WHAT THIS IS FOR
 *
 * A Roon library is the only source in this app that is not a web service
 * behind an OAuth sign-in. It is a program on the user's own network, found by
 * UDP broadcast, and the "sign in" is the user ticking a box in Roon →
 * Settings → Extensions. That approval produces a token which is persisted per
 * Core id, so it happens exactly once.
 *
 *   discover -> ws://host:port/api -> registry:1/info -> registry:1/register
 *   -> (the user enables the extension, once) -> "Registered" + token -> paired
 *
 * EVERY DEPENDENCY IS INJECTABLE, AND THAT IS NOT DECORATION
 *
 * There is no Roon Core in CI, in the Docker build, or in this container. So
 * the socket, the discovery and the token store are all seams: the tests drive
 * a scripted Core through a fake socket and assert on the exact frames sent.
 * What CANNOT be tested here is a real Core's behaviour, and the honest
 * statement is in the README next to the Roon instructions rather than only in
 * a commit message.
 *
 * Ported from the Kotlin in meltface-80/Android-Random-Remote, which has run
 * against a real Core; the APK port goes back the other way, so the two are
 * meant to stay readable side by side.
 */

const dgram = require("node:dgram");
const os = require("node:os");
const P = require("./roon-proto");

/** Where a pairing attempt has got to. The UI shows `detail` verbatim. */
const STAGE = {
  IDLE: "idle",
  DISCOVERING: "discovering",
  CONNECTING: "connecting",
  AWAITING_APPROVAL: "awaiting-approval",
  PAIRED: "paired",
  ERROR: "error",
};

/** How long to listen for Cores before giving up on a discovery round. */
const DISCOVERY_MS = 8000;
/** The query is re-sent this often: the first burst is easily lost on Wi-Fi. */
const DISCOVERY_RESEND_MS = 1500;

const BACKOFF_START_MS = 1000;
const BACKOFF_MAX_MS = 30000;

/**
 * A stuck-call backstop, not a performance budget — a slow but working Core
 * must not be broken by it. Registration is exempt: see register().
 */
const CALL_TIMEOUT_MS = 90000;

/** A Roon refusal, or a Core that stopped answering. */
class RoonError extends Error {
  constructor(message, roonName) {
    super(message);
    this.name = "RoonError";
    this.roonName = roonName || null;
  }
}

/** Not paired yet. Distinct from a refusal so the UI can say which. */
class NotPairedError extends RoonError {
  constructor(message) { super(message || "Not paired with a Roon Core"); }
}

// ----------------------------------------------------------------- discovery

/**
 * Every subnet broadcast address this host can see.
 *
 * Multicast alone is not enough: consumer access points drop it often enough
 * that a multicast-only discovery finds nothing on exactly the networks people
 * run Roon on. Both are sent, which costs two datagrams.
 */
function broadcastTargets() {
  const out = [];
  const ifaces = os.networkInterfaces();
  for (const name of Object.keys(ifaces)) {
    for (const addr of ifaces[name] || []) {
      if (addr.family !== "IPv4" || addr.internal) continue;
      if (!addr.address || !addr.netmask) continue;
      const a = addr.address.split(".").map(Number);
      const m = addr.netmask.split(".").map(Number);
      if (a.length !== 4 || m.length !== 4 || a.concat(m).some(isNaN)) continue;
      out.push(a.map((o, i) => (o & m[i]) | (~m[i] & 0xff)).join("."));
    }
  }
  return out;
}

/**
 * Broadcast a SOOD query and report every Core that answers.
 *
 * Resolves when [timeoutMs] is up, with the list. Each Core is also handed to
 * [onFound] as it arrives, so a caller that wants the first one need not wait
 * out the whole window.
 *
 * @param {object} [o]
 * @param {string[]} [o.targets] addresses to send to. Overridable so the tests
 *   can point discovery at a fake Core on loopback and exercise the real
 *   socket rather than a mock of one.
 */
function discoverCores(o) {
  const opts = o || {};
  const timeoutMs = opts.timeoutMs === undefined ? DISCOVERY_MS : opts.timeoutMs;
  const port = opts.port || P.SOOD_PORT;
  const onFound = opts.onFound || (() => {});
  const log = opts.log || (() => {});

  return new Promise((resolve) => {
    const socket = dgram.createSocket({ type: "udp4", reuseAddr: true });
    const found = [];
    const seen = new Set();
    let resend = null;
    let deadline = null;
    let done = false;

    const finish = () => {
      if (done) return;
      done = true;
      if (resend) clearInterval(resend);
      if (deadline) clearTimeout(deadline);
      try { socket.close(); } catch (e) { log("close failed: " + e.message); }
      resolve(found);
    };

    socket.on("error", (e) => {
      // A bind failure is worth reporting but not throwing: the caller's next
      // step is the same either way — no Core, ask for an address by hand.
      log("discovery socket failed: " + e.message);
      finish();
    });

    socket.on("message", (buf, rinfo) => {
      const props = P.parseSoodReply(buf);
      if (!props) return;
      const core = P.soodCore(props, rinfo && rinfo.address);
      if (!core || seen.has(core.uniqueId)) return;
      seen.add(core.uniqueId);
      found.push(core);
      log("found core " + core.uniqueId + " at " + core.host + ":" + core.port);
      try { onFound(core); } catch (e) { log("onFound threw: " + e.message); }
    });

    socket.bind(() => {
      const targets = opts.targets && opts.targets.length ? opts.targets
        : [P.SOOD_MULTICAST, "255.255.255.255"].concat(broadcastTargets());
      try {
        socket.setBroadcast(true);
        socket.setMulticastTTL(32);
      } catch (e) {
        log("socket options refused: " + e.message);
      }
      const blast = () => {
        const query = P.buildSoodQuery();
        for (const target of targets) {
          socket.send(query, port, target, (e) => {
            // One unreachable interface must not end the round: a laptop with
            // a docker0 bridge has several, and most of them are not it.
            if (e) log("send to " + target + " failed: " + e.message);
          });
        }
      };
      blast();
      resend = setInterval(blast, DISCOVERY_RESEND_MS);
      if (resend.unref) resend.unref();
      deadline = setTimeout(finish, timeoutMs);
      if (deadline.unref) deadline.unref();
    });
  });
}

// -------------------------------------------------------------- the socket

/**
 * The default transport: a WebSocket to ws://host:port/api.
 *
 * `binaryType = "arraybuffer"` is load-bearing. Node's WebSocket hands binary
 * frames over as a **Blob** by default, and a Blob is not a Buffer: reading it
 * as one yields nothing, every MOO frame fails to parse, and the Core looks
 * like it accepted the connection and then said nothing at all.
 *
 * @param {string} url
 * @param {{onOpen:Function, onFrame:Function, onClose:Function}} handlers
 */
function webSocketConnect(url, handlers) {
  if (typeof WebSocket !== "function") {
    throw new RoonError("This Node build has no WebSocket; Roon needs Node 22 or newer.");
  }
  const ws = new WebSocket(url);
  ws.binaryType = "arraybuffer";
  let closed = false;
  const closeOnce = (reason) => {
    if (closed) return;
    closed = true;
    handlers.onClose(reason);
  };
  ws.addEventListener("open", () => handlers.onOpen());
  ws.addEventListener("message", (ev) => {
    const d = ev.data;
    if (typeof d === "string") handlers.onFrame(Buffer.from(d, "utf8"));
    else if (d instanceof ArrayBuffer) handlers.onFrame(Buffer.from(d));
    else if (d && d.byteLength !== undefined) handlers.onFrame(Buffer.from(d.buffer || d));
  });
  ws.addEventListener("error", () => closeOnce("connection failed"));
  ws.addEventListener("close", (ev) =>
    closeOnce((ev && ev.reason) || "disconnected"));
  return {
    send(buf) { ws.send(buf); },
    close(reason) {
      try { ws.close(1000, reason || "closing"); } catch (e) { void e; }
      closeOnce(reason || "closing");
    },
  };
}

// ------------------------------------------------------------- MOO session

/**
 * One MOO session: request ids, pending handlers, and the inbound ping.
 *
 * A handler is registered per request id and removed only on COMPLETE,
 * because a CONTINUE keeps the id open — that is how every Roon subscription
 * streams, and removing on CONTINUE would drop everything after the first
 * update.
 */
class MooSession {
  /**
   * @param {{send:Function, close:Function}} socket
   * @param {object} [o]
   * @param {Function} [o.onCoreRequest] answer a request the Core made of us;
   *   return {name, body} or null to refuse as InvalidRequest.
   */
  constructor(socket, o) {
    const opts = o || {};
    this.socket = socket;
    this._log = opts.log || (() => {});
    this._onCoreRequest = opts.onCoreRequest || null;
    this._next = 0;
    this._handlers = new Map();
    this._dead = false;
  }

  get isOpen() { return !this._dead; }

  /**
   * Fire a request and register [onReply] for every response on its id.
   *
   * The handler is called with null when the session dies, so a blocked
   * caller always wakes. Without that a request in flight when the Core goes
   * away would sit until its deadline — and registration has no deadline.
   *
   * @returns {number} the request id, for a later unsubscribe
   */
  send(service, method, body, onReply) {
    if (this._dead) throw new NotPairedError("The Roon connection is closed");
    const id = this._next++;
    if (onReply) this._handlers.set(String(id), onReply);
    const frame = P.encodeRequest(service, method, id, body);
    try {
      this.socket.send(frame);
    } catch (e) {
      this._handlers.delete(String(id));
      throw new RoonError("Could not send " + service + "/" + method + ": " + e.message);
    }
    return id;
  }

  /**
   * Send a request and wait for the first response.
   *
   * @param {object} [opts]
   * @param {?string} [opts.expect] the result name that means success
   *   ("Success", "Registered"). Anything else rejects carrying Roon's OWN
   *   name, which is the most useful thing there is to tell the user. Pass
   *   null to accept whatever comes back.
   * @param {number} [opts.timeoutMs] 0 for no deadline — see register().
   */
  call(service, method, body, opts) {
    const o = opts || {};
    const expect = o.expect === undefined ? "Success" : o.expect;
    const timeoutMs = o.timeoutMs === undefined ? CALL_TIMEOUT_MS : o.timeoutMs;

    return new Promise((resolve, reject) => {
      let settled = false;
      let timer = null;
      const done = (fn, arg) => {
        if (settled) return;
        settled = true;
        if (timer) clearTimeout(timer);
        fn(arg);
      };

      let id;
      try {
        id = this.send(service, method, body, (msg) => {
          if (!msg) {
            done(reject, new RoonError(
              "Lost the connection to Roon during " + service + "/" + method));
            return;
          }
          if (expect !== null && msg.name !== expect) {
            const detail = msg.bodyText ? ": " + msg.bodyText.slice(0, 300) : "";
            done(reject, new RoonError(
              "Roon answered " + msg.name + " to " + service + "/" + method + detail,
              msg.name));
            return;
          }
          done(resolve, msg);
        });
      } catch (e) {
        done(reject, e);
        return;
      }

      // Deliberately NOT unref'd: a call in flight is real work, and a
      // process that exited from under one would look like Roon had answered.
      // The timer is cleared the moment the call settles, including when the
      // session dies, so it never outlives what it is guarding.
      if (timeoutMs > 0) {
        timer = setTimeout(() => {
          this.forget(id);
          done(reject, new RoonError(
            "Roon did not answer " + service + "/" + method + " in time"));
        }, timeoutMs);
      }
    });
  }

  /** Drop the handler for a request id, after unsubscribing or timing out. */
  forget(id) { this._handlers.delete(String(id)); }

  /** Hand one received frame to the session. Not a MOO frame: ignored. */
  receive(buf) {
    const msg = P.parseMoo(buf);
    if (!msg) {
      this._log("unparseable frame of " + (buf ? buf.length : 0) + " bytes");
      return;
    }

    if (msg.verb === P.REQUEST) {
      // The Core calls into the services we advertise. Ping is the one that
      // must be answered or the Core drops us as unresponsive.
      if (msg.service === P.SERVICES.PING && msg.name === "ping") {
        this._reply(P.COMPLETE, "Success", msg.requestId, null);
        return;
      }
      let answer = null;
      if (this._onCoreRequest) {
        try {
          answer = this._onCoreRequest(msg);
        } catch (e) {
          this._log(msg.service + "/" + msg.name + " threw: " + e.message);
          answer = null;
        }
      }
      if (answer) this._reply(answer.verb || P.COMPLETE, answer.name, msg.requestId, answer.body);
      else this._reply(P.COMPLETE, "InvalidRequest", msg.requestId, null);
      return;
    }

    const handler = this._handlers.get(msg.requestId);
    if (!handler) {
      this._log("unmatched " + msg.verb + " " + msg.name + " id=" + msg.requestId);
      return;
    }
    if (msg.verb === P.COMPLETE) this._handlers.delete(msg.requestId);
    try {
      handler(msg);
    } catch (e) {
      this._log("handler for " + msg.name + " threw: " + e.message);
    }
  }

  _reply(verb, name, requestId, body) {
    const id = parseInt(requestId, 10);
    if (!Number.isFinite(id)) return;
    try {
      this.socket.send(P.encodeMoo(verb, name, id, body || null));
    } catch (e) {
      this._log("reply failed: " + e.message);
    }
  }

  /**
   * The session is over. Wakes every pending handler with null so no caller is
   * left on a promise that can never settle.
   */
  die(reason) {
    if (this._dead) return;
    this._dead = true;
    const handlers = Array.from(this._handlers.values());
    this._handlers.clear();
    for (const h of handlers) {
      try { h(null); } catch (e) { this._log("pending wake threw: " + e.message); }
    }
    this._log("session over: " + (reason || "closed"));
  }
}

// ------------------------------------------------------------- session keys

/**
 * Browse session keys, checked in and out.
 *
 * Roon keeps server-side state per `multi_session_key` for as long as the
 * extension stays connected, so keys are POOLED rather than minted per
 * operation: the Core then holds as many sessions as the peak number of
 * simultaneous operations, not as many as have ever run — which over a ten
 * thousand album scan is the difference between three and ten thousand. Reuse
 * is safe because every operation starts by re-navigating with pop_all, which
 * discards whatever the last one left on that key.
 */
class SessionPool {
  constructor() { this._free = []; this._seq = 0; }
  acquire() { return this._free.pop() || "mdm_s" + (++this._seq); }
  release(key) { this._free.push(key); }
}

/** A token store that forgets on restart. The real one is in lib/store.js. */
function memoryStore() {
  const tokens = new Map();
  let last = null;
  return {
    tokenFor: (coreId) => tokens.get(coreId) || null,
    saveToken: (coreId, token) => { tokens.set(coreId, token); },
    lastCore: () => last,
    saveLastCore: (host, port) => { last = { host, port }; },
    forgetLastCore: () => { last = null; },
  };
}

/** How the extension introduces itself in Roon → Settings → Extensions. */
const EXTENSION = {
  extension_id: "com.musicd.migrate",
  display_name: "MusicD Migrate",
  // Always overridden by the caller with the real package version. A number
  // here would go stale the first time one of them forgot to pass it.
  display_version: "dev",
  publisher: "Music Duck",
  email: "",
  website: "https://github.com/meltface-80/MusicD-Migrate",
};

// ----------------------------------------------------------------- the Core

class RoonCore {
  /**
   * @param {object} [o]
   * @param {object} [o.extension] the registration block above
   * @param {object} [o.store] tokenFor/saveToken/lastCore/saveLastCore
   * @param {Function} [o.connect] (url, handlers) => {send, close}
   * @param {Function} [o.discover] ({timeoutMs, onFound}) => Promise<core[]>
   * @param {Function} [o.schedule] (fn, ms) => handle, for the reconnect delay
   */
  constructor(o) {
    const opts = o || {};
    this.extension = Object.assign({}, EXTENSION, opts.extension || {});
    this.store = opts.store || memoryStore();
    this._connect = opts.connect || webSocketConnect;
    this._discover = opts.discover || discoverCores;
    this._log = opts.log || (() => {});
    this._schedule = opts.schedule || ((fn, ms) => {
      const t = setTimeout(fn, ms);
      if (t.unref) t.unref();
      return t;
    });

    this._running = false;
    this._socket = null;
    this._session = null;
    this._backoff = BACKOFF_START_MS;
    this._pool = new SessionPool();
    this._waiters = [];
    this._pendingError = null;

    this.host = null;
    this.port = 0;
    this.coreId = null;
    this.coreName = null;
    this._stage = STAGE.IDLE;
    this._detail = "";
  }

  get status() {
    return {
      stage: this._stage,
      detail: this._detail,
      coreId: this.coreId,
      coreName: this.coreName,
      host: this.host,
      port: this.port,
      paired: this.isPaired,
    };
  }

  get isPaired() {
    return this._stage === STAGE.PAIRED && !!this._session && this._session.isOpen;
  }

  _publish(stage, detail) {
    this._stage = stage;
    this._detail = detail || "";
    this._log(stage + ": " + this._detail);
    if (stage === STAGE.PAIRED || stage === STAGE.ERROR) {
      const waiters = this._waiters;
      this._waiters = [];
      for (const w of waiters) w(stage === STAGE.PAIRED ? null : new RoonError(this._detail));
    }
  }

  /**
   * Resolves when the Core is paired, rejects when a round fails.
   *
   * Used by the API to turn "the user has not ticked the box yet" into a poll
   * rather than a hung request.
   */
  waitUntilPaired(timeoutMs) {
    if (this.isPaired) return Promise.resolve();
    return new Promise((resolve, reject) => {
      let settled = false;
      let timer = null;
      const waiter = (err) => {
        if (settled) return;
        settled = true;
        if (timer) clearTimeout(timer);
        if (err) reject(err); else resolve();
      };
      this._waiters.push(waiter);
      if (timeoutMs > 0) {
        timer = setTimeout(() => waiter(new RoonError(
          "Still waiting for Roon: " + (this._detail || this._stage))), timeoutMs);
      }
    });
  }

  /** Begin pairing. Idempotent: a second call while running does nothing. */
  start() {
    if (this._running) return;
    this._running = true;
    this._connectOrDiscover();
  }

  stop() {
    this._running = false;
    const s = this._socket;
    this._socket = null;
    if (this._session) this._session.die("stopping");
    this._session = null;
    if (s) { try { s.close("stopping"); } catch (e) { void e; } }
    this._publish(STAGE.IDLE, "Stopped");
  }

  /** Connect to an address the user typed, instead of discovering. */
  connectTo(host, port) {
    this._running = true;
    if (this._session) this._session.die("manual reconnect");
    this.host = host;
    this.port = Number(port) || 0;
    this.store.saveLastCore(this.host, this.port);
    this._backoff = BACKOFF_START_MS;
    this._openSocket(this.host, this.port);
  }

  /** Forget the remembered address and look again. */
  rediscover() {
    this.store.forgetLastCore();
    this.host = null;
    this.port = 0;
    if (this._session) this._session.die("rediscover");
    this._backoff = BACKOFF_START_MS;
    this._running = true;
    this._connectOrDiscover();
  }

  async _connectOrDiscover() {
    if (!this._running) return;
    const saved = this.store.lastCore();
    if (saved && saved.host && saved.port > 0) {
      this.host = saved.host;
      this.port = saved.port;
      this._openSocket(saved.host, saved.port);
      return;
    }

    this._publish(STAGE.DISCOVERING, "Looking for a Roon Core on this network");
    let cores = [];
    try {
      cores = await this._discover({ timeoutMs: DISCOVERY_MS, log: this._log });
    } catch (e) {
      this._log("discovery failed: " + e.message);
    }
    if (!this._running) return;
    if (!cores.length) {
      this._publish(STAGE.ERROR, "No Roon Core found. Check this machine is on the " +
        "same network as the Core, or enter its address by hand.");
      this._schedule(() => this._connectOrDiscover(), 10000);
      return;
    }
    const core = cores[0];
    this.host = core.host;
    this.port = core.port;
    this.coreId = core.uniqueId || this.coreId;
    this.coreName = core.displayName || this.coreName;
    this.store.saveLastCore(core.host, core.port);
    this._openSocket(core.host, core.port);
  }

  _openSocket(host, port) {
    if (!this._running) return;
    this._publish(STAGE.CONNECTING, "Connecting to " + host + ":" + port);
    let socket;
    try {
      socket = this._connect("ws://" + host + ":" + port + "/api", {
        onOpen: () => { this._register().catch((e) => this._registerFailed(e)); },
        onFrame: (buf) => { if (this._session) this._session.receive(buf); },
        onClose: (reason) => this._onClosed(reason),
      });
    } catch (e) {
      this._publish(STAGE.ERROR, e.message);
      this._scheduleReconnect();
      return;
    }
    this._socket = socket;
    this._session = new MooSession(socket, { log: this._log });
  }

  _onClosed(reason) {
    if (this._session) this._session.die(reason);
    this._session = null;
    this._socket = null;
    if (!this._running) return;
    // A registration REFUSAL closes the socket itself, and Roon's own word for
    // the refusal is far more useful than "lost the connection" — which is
    // what the user would otherwise be shown for a permission problem they
    // could actually fix.
    const refusal = this._pendingError;
    this._pendingError = null;
    this._publish(STAGE.ERROR, refusal || ("Lost the connection to Roon: " + reason));
    this._scheduleReconnect();
  }

  _scheduleReconnect() {
    if (!this._running) return;
    const delay = this._backoff;
    this._backoff = Math.min(this._backoff * 2, BACKOFF_MAX_MS);
    this._schedule(() => {
      if (!this._running) return;
      if (this.host && this.port > 0) this._openSocket(this.host, this.port);
      else this._connectOrDiscover();
    }, delay);
  }

  _registerFailed(e) {
    const message = (e && e.message) || "Registration failed";
    this._log("registration failed: " + message);
    const socket = this._socket;
    if (socket && this._session && this._session.isOpen) {
      // A refusal rather than a drop: nothing will call _onClosed for us, so
      // close the socket and let the one close path report and retry. The
      // message is carried across so the refusal is what the user sees.
      this._pendingError = message;
      try { socket.close("registration failed"); } catch (err) { void err; }
      return;
    }
    // The socket had already gone, which means _onClosed has reported it and
    // scheduled the retry. Reporting again would replace the reason with a
    // vaguer one.
  }

  async _register() {
    const session = this._session;
    if (!session) return;
    this._backoff = BACKOFF_START_MS;

    // `info` is answered with the Core's own name before anything is approved,
    // which is what lets the UI say WHICH Core is waiting to be enabled.
    const info = await session.call(P.SERVICES.REGISTRY, "info", undefined, { expect: null });
    const infoBody = P.mooJson(info) || {};
    if (infoBody.core_id) this.coreId = String(infoBody.core_id);
    if (infoBody.display_name) this.coreName = String(infoBody.display_name);

    const reginfo = Object.assign({}, this.extension, {
      required_services: [P.SERVICES.BROWSE],
      optional_services: [],
      provided_services: [P.SERVICES.PING],
    });
    const token = this.coreId ? this.store.tokenFor(this.coreId) : null;
    if (token) reginfo.token = token;

    this._publish(STAGE.AWAITING_APPROVAL, token
      ? "Reconnecting to " + (this.coreName || "Roon")
      : "Enable “" + this.extension.display_name + "” in Roon → " +
        "Settings → Extensions to let it read your library");

    // NO DEADLINE, deliberately. Roon answers "Registered" only once the user
    // has enabled the extension, and on a first pair that is however long it
    // takes them to walk to the Roon window. A 90-second deadline here would
    // fail the pair, close the socket and start over — repeatedly, while the
    // user is looking at the very screen they were asked to look at. The
    // socket closing is what fails this call instead.
    const registered = await session.call(P.SERVICES.REGISTRY, "register", reginfo,
      { expect: "Registered", timeoutMs: 0 });
    const body = P.mooJson(registered) || {};
    if (body.core_id) this.coreId = String(body.core_id);
    if (body.display_name) this.coreName = String(body.display_name);
    // Persisted so approval happens exactly once per Core, ever.
    if (body.token && this.coreId) this.store.saveToken(this.coreId, String(body.token));

    this._publish(STAGE.PAIRED, "Paired with " + (this.coreName || "Roon"));
  }

  // ------------------------------------------------------------- browse api

  /**
   * The session, or a refusal that says which of the two things is wrong.
   *
   * PAIRED is required, not merely an open socket. Before registration
   * completes the Core answers nothing useful, and sending a browse anyway
   * would turn "you have not enabled the extension yet" — which the user can
   * fix in five seconds — into a Roon protocol error, or worse into an empty
   * album list that reads as a library with nothing in it.
   */
  _session_() {
    if (this._stage !== STAGE.PAIRED || !this._session || !this._session.isOpen) {
      throw new NotPairedError(this._stage === STAGE.AWAITING_APPROVAL
        ? "Waiting for MusicD Migrate to be enabled in Roon \u2192 Settings \u2192 Extensions"
        : "Not paired with a Roon Core");
    }
    return this._session;
  }

  async browse(opts) {
    const reply = await this._session_().call(P.SERVICES.BROWSE, "browse", opts);
    const body = P.mooJson(reply);
    if (!body) throw new RoonError("Roon returned an empty browse response");
    return body;
  }

  async load(opts) {
    const reply = await this._session_().call(P.SERVICES.BROWSE, "load", opts);
    const body = P.mooJson(reply);
    if (!body) throw new RoonError("Roon returned an empty load response");
    return body;
  }

  /** Run fn with a pooled browse session key. See SessionPool. */
  async withSession(fn) {
    const key = this._pool.acquire();
    try {
      return await fn(key);
    } finally {
      this._pool.release(key);
    }
  }
}

module.exports = {
  RoonCore, MooSession, SessionPool, RoonError, NotPairedError,
  STAGE, EXTENSION, discoverCores, webSocketConnect, broadcastTargets,
  memoryStore, CALL_TIMEOUT_MS, DISCOVERY_MS,
};
