package com.musicd.migrate.roon

import com.musicd.migrate.Store
import com.musicd.migrate.strOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/*
 * RoonCore.kt — finding a Roon Core, pairing with it, keeping the session up.
 *
 * The Kotlin twin of lib/roon-core.js. Same states, same messages, same
 * decisions — including the ones that look odd until you know why:
 * registration has no deadline, a browse before pairing is refused rather than
 * sent, and an empty browse response is an error rather than an empty list.
 * Read the JavaScript alongside this; a difference between them is a bug in
 * one of them, because the same page drives both.
 *
 * EVERY DEPENDENCY IS A SEAM. The socket, the discovery and the token store
 * are all injectable, because there is no Roon Core in CI and there is none in
 * the container this was written in. The tests drive a scripted Core and
 * assert on the exact frames.
 */

/** A Roon refusal, or a Core that stopped answering. */
open class RoonException(message: String, val roonName: String? = null) :
    RuntimeException(message)

/** Not paired yet. Distinct from a refusal so the UI can say which. */
class NotPairedException(message: String) : RoonException(message)

/** Where a pairing attempt has got to. The UI shows `detail` verbatim. */
object RoonStage {
    const val IDLE = "idle"
    const val DISCOVERING = "discovering"
    const val CONNECTING = "connecting"
    const val AWAITING_APPROVAL = "awaiting-approval"
    const val PAIRED = "paired"
    const val ERROR = "error"
}

/** Somewhere to keep the pairing token and the last address. */
interface RoonMemory {
    fun tokenFor(coreId: String): String?
    fun saveToken(coreId: String, token: String)
    fun lastCore(): Pair<String, Int>?
    fun saveLastCore(host: String, port: Int)
    fun forgetLastCore()
}

/** The app's Store, seen as RoonMemory. */
class StoreRoonMemory(private val store: Store) : RoonMemory {
    override fun tokenFor(coreId: String): String? = store.setting("roon.token.$coreId")
    override fun saveToken(coreId: String, token: String) =
        store.putSetting("roon.token.$coreId", token)
    override fun lastCore(): Pair<String, Int>? {
        val host = store.setting("roon.lastHost") ?: return null
        val port = store.setting("roon.lastPort")?.toIntOrNull() ?: return null
        return if (host.isEmpty() || port <= 0) null else host to port
    }
    override fun saveLastCore(host: String, port: Int) {
        store.putSetting("roon.lastHost", host)
        store.putSetting("roon.lastPort", port.toString())
    }
    override fun forgetLastCore() {
        store.deleteSetting("roon.lastHost")
        store.deleteSetting("roon.lastPort")
    }
}

/** How the extension introduces itself in Roon → Settings → Extensions. */
data class RoonExtension(
    val extensionId: String = "com.musicd.migrate",
    val displayName: String = "MusicD Migrate",
    /** Always overridden with the real build version; see MigrateApi. */
    val version: String = "dev",
    val publisher: String = "Music Duck",
    val email: String = "",
    val website: String = "https://github.com/meltface-80/MusicD-Migrate"
)

data class RoonStatus(
    val stage: String,
    val detail: String = "",
    val coreId: String? = null,
    val coreName: String? = null,
    val host: String? = null,
    val port: Int = 0,
    val paired: Boolean = false
)

/** Android needs a WifiManager.MulticastLock for SOOD replies to arrive. */
interface MulticastLock {
    fun acquire()
    fun release()
    companion object {
        val NONE = object : MulticastLock {
            override fun acquire() {}
            override fun release() {}
        }
    }
}

// ------------------------------------------------------------------ discovery

interface RoonDiscovery {
    /** Blocks for up to [timeoutMs], returning every Core that answered. */
    fun discover(timeoutMs: Long, log: (String) -> Unit): List<RoonProto.FoundCore>
}

/**
 * SOOD discovery over a real UDP socket.
 *
 * Multicast alone is not enough: consumer access points drop it often enough
 * that a multicast-only discovery finds nothing on exactly the networks people
 * run Roon on. Broadcast goes out too, which costs a datagram.
 *
 * @param targets overridable so a test can point discovery at a fake Core on
 *   loopback and exercise the real socket rather than a mock of one.
 */
class SoodDiscovery(
    private val port: Int = RoonProto.SOOD_PORT,
    private val targets: List<String>? = null,
    private val multicastLock: MulticastLock = MulticastLock.NONE
) : RoonDiscovery {

    override fun discover(timeoutMs: Long, log: (String) -> Unit): List<RoonProto.FoundCore> {
        val found = LinkedHashMap<String, RoonProto.FoundCore>()
        multicastLock.acquire()
        val socket: DatagramSocket = try {
            MulticastSocket().also {
                it.timeToLive = 32
                it.broadcast = true
            }
        } catch (e: Exception) {
            log("discovery socket failed: ${e.message}")
            multicastLock.release()
            return emptyList()
        }
        try {
            socket.soTimeout = 400
            val addresses = (targets ?: (listOf(RoonProto.SOOD_MULTICAST, "255.255.255.255") +
                broadcastTargets(log))).mapNotNull {
                runCatching { InetAddress.getByName(it) }.getOrNull()
            }
            val query = RoonProto.buildSoodQuery()
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            val buf = ByteArray(2048)

            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now >= nextSend) {
                    for (target in addresses) {
                        // One unreachable interface must not end the round: a
                        // machine with a docker bridge has several, and most
                        // of them are not it.
                        runCatching { socket.send(DatagramPacket(query, query.size, target, port)) }
                            .onFailure { log("send to $target failed: ${it.message}") }
                    }
                    nextSend = now + 1500
                }
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val props = RoonProto.parseSoodReply(packet.data, packet.length) ?: continue
                val core = RoonProto.soodCore(props, packet.address?.hostAddress) ?: continue
                if (found.containsKey(core.uniqueId)) continue
                found[core.uniqueId] = core
                log("found core ${core.uniqueId} at ${core.host}:${core.port}")
            }
        } finally {
            runCatching { socket.close() }
            multicastLock.release()
        }
        return found.values.toList()
    }

    private fun broadcastTargets(log: (String) -> Unit): List<String> {
        val out = ArrayList<String>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.interfaceAddresses) {
                    addr.broadcast?.hostAddress?.let { out += it }
                }
            }
        } catch (e: Exception) {
            log("enumerating interfaces failed: ${e.message}")
        }
        return out
    }
}

// ---------------------------------------------------------------- the session

/**
 * One MOO session: request ids, pending handlers, and the inbound ping.
 *
 * A handler is registered per request id and removed only on COMPLETE, because
 * a CONTINUE keeps the id open — that is how every Roon subscription streams,
 * and removing on CONTINUE would drop everything after the first update.
 */
class MooSession(
    private val socket: RoonSocket,
    private val log: (String) -> Unit = {}
) {
    companion object {
        /**
         * A stuck-call backstop, not a performance budget: a slow but working
         * Core must not be broken by it. Registration is exempt — see
         * RoonCore.register.
         */
        const val CALL_TIMEOUT_MS = 90_000L
    }

    private val next = AtomicInteger(0)
    private val handlers = ConcurrentHashMap<String, (RoonProto.Message?) -> Unit>()
    private val dead = AtomicBoolean(false)

    val isOpen: Boolean get() = !dead.get()

    /**
     * Fire a request and register [onReply] for every response on its id.
     *
     * The handler is called with null when the session dies, so a blocked
     * caller always wakes. Without that a request in flight when the Core goes
     * away would sit until its deadline — and registration has no deadline.
     */
    fun send(
        service: String, method: String, body: JSONObject?,
        onReply: ((RoonProto.Message?) -> Unit)? = null
    ): Int {
        if (dead.get()) throw NotPairedException("The Roon connection is closed")
        val id = next.getAndIncrement()
        if (onReply != null) handlers[id.toString()] = onReply
        val frame = RoonProto.encodeRequest(service, method, id, body?.toString())
        try {
            socket.send(frame)
        } catch (e: Exception) {
            handlers.remove(id.toString())
            throw RoonException("Could not send $service/$method: ${e.message}")
        }
        return id
    }

    /**
     * Send a request and block until the first response arrives.
     *
     * @param expect the Roon result name that means success ("Success",
     *   "Registered"). Anything else throws carrying Roon's OWN name, which is
     *   the most useful thing there is to tell the user. Null accepts anything.
     * @param timeoutMs 0 for no deadline — see RoonCore.register.
     */
    fun call(
        service: String, method: String, body: JSONObject? = null,
        expect: String? = "Success", timeoutMs: Long = CALL_TIMEOUT_MS
    ): RoonProto.Message {
        val latch = CountDownLatch(1)
        val reply = AtomicReference<RoonProto.Message?>(null)
        val got = AtomicBoolean(false)
        val id = send(service, method, body) { msg ->
            if (got.compareAndSet(false, true)) {
                reply.set(msg)
                latch.countDown()
            }
        }
        if (timeoutMs > 0) {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                forget(id)
                throw RoonException("Roon did not answer $service/$method in time")
            }
        } else {
            latch.await()
        }
        val msg = reply.get()
            ?: throw RoonException("Lost the connection to Roon during $service/$method")
        if (expect != null && msg.name != expect) {
            val detail = msg.bodyText?.let { ": " + it.take(300) } ?: ""
            throw RoonException("Roon answered ${msg.name} to $service/$method$detail", msg.name)
        }
        return msg
    }

    fun forget(id: Int) { handlers.remove(id.toString()) }

    /** Hand one received frame to the session. Not a MOO frame: ignored. */
    fun receive(frame: ByteArray) {
        val msg = RoonProto.parse(frame)
        if (msg == null) {
            log("unparseable frame of ${frame.size} bytes")
            return
        }
        if (msg.verb == RoonProto.REQUEST) {
            // The Core calls into the services we advertise. Ping is the one
            // that must be answered or the Core drops us as unresponsive.
            if (msg.service == RoonProto.PING && msg.name == "ping") {
                reply(RoonProto.COMPLETE, "Success", msg.requestId)
            } else {
                // Everything else is refused rather than ignored: silence
                // leaves the Core waiting on a request for ever.
                reply(RoonProto.COMPLETE, "InvalidRequest", msg.requestId)
            }
            return
        }
        val handler = handlers[msg.requestId]
        if (handler == null) {
            log("unmatched ${msg.verb} ${msg.name} id=${msg.requestId}")
            return
        }
        if (msg.verb == RoonProto.COMPLETE) handlers.remove(msg.requestId)
        runCatching { handler(msg) }
            .onFailure { log("handler for ${msg.name} threw: ${it.message}") }
    }

    private fun reply(verb: String, name: String, requestId: String) {
        val id = requestId.toIntOrNull() ?: return
        runCatching { socket.send(RoonProto.encode(verb, name, id, null)) }
            .onFailure { log("reply failed: ${it.message}") }
    }

    /**
     * The session is over. Wakes every pending handler with null so no caller
     * is left on a latch that never counts down.
     */
    fun die(reason: String) {
        if (dead.getAndSet(true)) return
        val snapshot = handlers.keys.toList()
        for (key in snapshot) {
            val h = handlers.remove(key) ?: continue
            runCatching { h(null) }.onFailure { log("pending wake threw: ${it.message}") }
        }
        log("session over: $reason")
    }
}

/**
 * Browse session keys, checked in and out.
 *
 * Roon keeps server-side state per `multi_session_key` for as long as the
 * extension stays connected, so keys are POOLED rather than minted per
 * operation: over a ten thousand album scan that is the difference between
 * three sessions on the user's Core and ten thousand. Reuse is safe because
 * every operation starts by re-navigating with pop_all.
 */
class SessionPool {
    private val free = ArrayDeque<String>()
    private var seq = 0

    @Synchronized fun acquire(): String = free.removeLastOrNull() ?: "mdm_s${++seq}"
    @Synchronized fun release(key: String) { free.addLast(key) }
}

// ------------------------------------------------------------------ the Core

/** What RoonClient needs of a Core. A seam, so the walk can be tested alone. */
interface BrowseApi {
    fun browse(opts: JSONObject): JSONObject
    fun load(opts: JSONObject): JSONObject
    fun <T> withSession(fn: (String) -> T): T
    val coreId: String?
    val coreName: String?
}

class RoonCore(
    private val memory: RoonMemory,
    private val sockets: RoonSocketFactory = RawWebSocketFactory(),
    private val discovery: RoonDiscovery = SoodDiscovery(),
    private val extension: RoonExtension = RoonExtension(),
    private val log: (String) -> Unit = {},
    /**
     * Told on every stage change.
     *
     * Exists so the app module can protect the process while a pairing is in
     * flight — see MigrateApi.roonConnecting. A callback rather than the page
     * polling the stage, because the window that matters is the instant the
     * user switches to Roon to click Enable, and a poll can miss it.
     */
    private val onStage: (String) -> Unit = {}
) : BrowseApi {

    private companion object {
        const val DISCOVERY_MS = 8_000L
        const val BACKOFF_START_MS = 1_000L
        const val BACKOFF_MAX_MS = 30_000L
        const val REDISCOVER_MS = 10_000L
    }

    private val net = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "roon-net").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val socket = AtomicReference<RoonSocket?>(null)
    private val session = AtomicReference<MooSession?>(null)
    private val pool = SessionPool()
    private val pairedLatch = AtomicReference(CountDownLatch(1))

    @Volatile private var backoffMs = BACKOFF_START_MS
    @Volatile private var pendingError: String? = null
    @Volatile private var host: String? = null
    @Volatile private var port: Int = 0
    @Volatile private var currentCoreId: String? = null
    @Volatile private var currentCoreName: String? = null
    @Volatile private var stage: String = RoonStage.IDLE
    @Volatile private var detail: String = ""

    override val coreId: String? get() = currentCoreId
    override val coreName: String? get() = currentCoreName

    val isPaired: Boolean
        get() = stage == RoonStage.PAIRED && session.get()?.isOpen == true

    val status: RoonStatus
        get() = RoonStatus(stage, detail, currentCoreId, currentCoreName, host, port, isPaired)

    private fun publish(next: String, why: String) {
        stage = next
        detail = why
        log("$next: $why")
        if (next == RoonStage.PAIRED || next == RoonStage.ERROR) pairedLatch.get().countDown()
        // A listener that throws must not take the pairing down with it.
        runCatching { onStage(next) }
    }

    private fun onNet(body: () -> Unit) {
        if (!running.get()) return
        runCatching { net.execute(body) }
    }

    /**
     * Begin pairing — or start a dead attempt over, which is what the button
     * means.
     *
     * This used to be `if (!running.compareAndSet(false, true)) return`, and
     * NOTHING cleared `running` but [stop], which only the server's own
     * shutdown calls. So the first attempt to be interrupted left the app
     * unable to try again for the rest of its life: every later press of
     * "Find my Roon Core" did literally nothing, silently. Reported as "fails
     * to connect to Roon even after enabling again in Roon extensions", and
     * it is the reason that state was unrecoverable rather than merely slow.
     *
     * A LIVE session is still left alone, and that part matters: pairing waits
     * for the user to click Enable in Roon and the approval comes back on
     * that very socket, so a double tap must not pull it out from under them.
     * Anything else — no session, or a closed one — is reconnected.
     *
     * Kept in step with start() in lib/roon-core.js by hand.
     */
    fun start() {
        val wasRunning = !running.compareAndSet(false, true)
        if (wasRunning) {
            if (session.get()?.isOpen == true) return
            socket.getAndSet(null)?.let { runCatching { it.close("retrying") } }
        }
        pairedLatch.set(CountDownLatch(1))
        backoffMs = BACKOFF_START_MS
        onNet { connectOrDiscover() }
    }

    fun stop() {
        running.set(false)
        session.getAndSet(null)?.die("stopping")
        socket.getAndSet(null)?.let { runCatching { it.close("stopping") } }
        publish(RoonStage.IDLE, "Stopped")
    }

    /** Connect to an address the user typed, instead of discovering. */
    fun connectTo(hostName: String, portNumber: Int) {
        running.set(true)
        pairedLatch.set(CountDownLatch(1))
        session.getAndSet(null)?.die("manual reconnect")
        host = hostName
        port = portNumber
        memory.saveLastCore(hostName, portNumber)
        backoffMs = BACKOFF_START_MS
        onNet { openSocket(hostName, portNumber) }
    }

    /** Forget the remembered address and look again. */
    fun rediscover() {
        running.set(true)
        pairedLatch.set(CountDownLatch(1))
        memory.forgetLastCore()
        host = null
        port = 0
        session.getAndSet(null)?.die("rediscover")
        backoffMs = BACKOFF_START_MS
        onNet { connectOrDiscover() }
    }

    /** Waits for the next pairing attempt to settle. Mostly for the tests. */
    fun awaitSettled(timeoutMs: Long): Boolean =
        pairedLatch.get().await(timeoutMs, TimeUnit.MILLISECONDS)

    private fun connectOrDiscover() {
        if (!running.get()) return
        val saved = memory.lastCore()
        if (saved != null) {
            host = saved.first
            port = saved.second
            openSocket(saved.first, saved.second)
            return
        }
        publish(RoonStage.DISCOVERING, "Looking for a Roon Core on this network")
        val cores = runCatching { discovery.discover(DISCOVERY_MS, log) }
            .onFailure { log("discovery failed: ${it.message}") }
            .getOrDefault(emptyList())
        if (!running.get()) return
        if (cores.isEmpty()) {
            publish(RoonStage.ERROR, "No Roon Core found. Check this machine is on the " +
                "same network as the Core, or enter its address by hand.")
            net.schedule({ onNet { connectOrDiscover() } }, REDISCOVER_MS, TimeUnit.MILLISECONDS)
            return
        }
        val core = cores.first()
        host = core.host
        port = core.port
        currentCoreId = core.uniqueId.ifEmpty { currentCoreId }
        currentCoreName = core.displayName.ifEmpty { currentCoreName }
        memory.saveLastCore(core.host, core.port)
        openSocket(core.host, core.port)
    }

    private fun openSocket(h: String, p: Int) {
        if (!running.get()) return
        publish(RoonStage.CONNECTING, "Connecting to $h:$p")
        try {
            val s = sockets.connect("ws://$h:$p/api", object : RoonSocketHandlers {
                override fun onOpen() { onNet { register() } }
                override fun onFrame(frame: ByteArray) { session.get()?.receive(frame) }
                override fun onClose(reason: String) { onNet { onClosed(reason) } }
            })
            socket.set(s)
            session.set(MooSession(s, log))
        } catch (e: Exception) {
            publish(RoonStage.ERROR, e.message ?: "Could not connect")
            scheduleReconnect()
        }
    }

    private fun onClosed(reason: String) {
        session.getAndSet(null)?.die(reason)
        socket.set(null)
        if (!running.get()) return
        // A registration REFUSAL closes the socket itself, and Roon's own word
        // for the refusal is far more useful than "lost the connection" —
        // which is what the user would otherwise be shown for a permission
        // problem they could actually fix.
        val refusal = pendingError
        pendingError = null
        publish(RoonStage.ERROR, refusal ?: "Lost the connection to Roon: $reason")
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
        net.schedule({
            onNet {
                val h = host
                if (h != null && port > 0) openSocket(h, port) else connectOrDiscover()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun register() {
        val s = session.get() ?: return
        backoffMs = BACKOFF_START_MS
        try {
            // `info` is answered with the Core's own name before anything is
            // approved, which is what lets the UI say WHICH Core is waiting.
            val info = s.call(RoonProto.REGISTRY, "info", null, expect = null)
            info.bodyText?.let { JSONObject(it) }?.let { body ->
                body.strOrNull("core_id")?.let { currentCoreId = it }
                body.strOrNull("display_name")?.let { currentCoreName = it }
            }

            val token = currentCoreId?.let { memory.tokenFor(it) }
            publish(RoonStage.AWAITING_APPROVAL, if (token != null)
                "Reconnecting to ${currentCoreName ?: "Roon"}"
            else "Enable “${extension.displayName}” in Roon → Settings → " +
                "Extensions to let it read your library")

            val reginfo = JSONObject()
                .put("extension_id", extension.extensionId)
                .put("display_name", extension.displayName)
                .put("display_version", extension.version)
                .put("publisher", extension.publisher)
                .put("email", extension.email)
                .put("website", extension.website)
                .put("required_services", JSONArray().put(RoonProto.BROWSE))
                .put("optional_services", JSONArray())
                // Advertising a service means answering it: Roon subscribes as
                // soon as it sees one listed. Ping is the only one answered.
                .put("provided_services", JSONArray().put(RoonProto.PING))
            if (token != null) reginfo.put("token", token)

            // NO DEADLINE, deliberately. Roon answers "Registered" only once
            // the user has enabled the extension, and on a first pair that is
            // however long it takes them to walk to the Roon window. A
            // 90-second deadline would fail the pair, close the socket and
            // start over — repeatedly, while the user is looking at the very
            // screen they were asked to look at. The socket closing is what
            // fails this call instead.
            val registered = s.call(RoonProto.REGISTRY, "register", reginfo,
                expect = "Registered", timeoutMs = 0)
            val body = registered.bodyText?.let { JSONObject(it) }
            body?.strOrNull("core_id")?.let { currentCoreId = it }
            body?.strOrNull("display_name")?.let { currentCoreName = it }
            val newToken = body?.strOrNull("token")
            // Persisted so approval happens exactly once per Core, ever.
            if (newToken != null) currentCoreId?.let { memory.saveToken(it, newToken) }

            publish(RoonStage.PAIRED, "Paired with ${currentCoreName ?: "Roon"}")
        } catch (e: Exception) {
            val message = e.message ?: "Registration failed"
            log("registration failed: $message")
            val sock = socket.get()
            if (sock != null && session.get()?.isOpen == true) {
                // A refusal rather than a drop: nothing will call onClosed for
                // us, so close the socket and let the one close path report
                // and retry, carrying the message across.
                pendingError = message
                runCatching { sock.close("registration failed") }
            }
            // If the socket had already gone, onClosed has reported it and
            // scheduled the retry; reporting again would replace the reason
            // with a vaguer one.
        }
    }

    // --------------------------------------------------------------- browsing

    /**
     * The session, or a refusal that says which of the two things is wrong.
     *
     * PAIRED is required, not merely an open socket. Before registration
     * completes the Core answers nothing useful, and sending a browse anyway
     * would turn "you have not enabled the extension yet" — fixable in five
     * seconds — into a protocol error, or worse into an empty album list that
     * reads as a library with nothing in it.
     */
    private fun live(): MooSession {
        val s = session.get()
        if (stage != RoonStage.PAIRED || s == null || !s.isOpen) {
            throw NotPairedException(if (stage == RoonStage.AWAITING_APPROVAL)
                "Waiting for MusicD Migrate to be enabled in Roon → Settings → Extensions"
            else "Not paired with a Roon Core")
        }
        return s
    }

    override fun browse(opts: JSONObject): JSONObject {
        val reply = live().call(RoonProto.BROWSE, "browse", opts)
        return reply.bodyText?.let { JSONObject(it) }
            ?: throw RoonException("Roon returned an empty browse response")
    }

    override fun load(opts: JSONObject): JSONObject {
        val reply = live().call(RoonProto.BROWSE, "load", opts)
        return reply.bodyText?.let { JSONObject(it) }
            ?: throw RoonException("Roon returned an empty load response")
    }

    override fun <T> withSession(fn: (String) -> T): T {
        val key = pool.acquire()
        try {
            return fn(key)
        } finally {
            pool.release(key)
        }
    }
}
