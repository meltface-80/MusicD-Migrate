package com.musicd.migrate.roon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pairing state machine, against a scripted Core.
 *
 * WHY THIS FILE EXISTS. `RoonCore` had no tests at all on this side —
 * RoonClientTest covers the browse walk and RoonProtoTest the frames, but
 * nothing drove the connect/register/approve sequence. The JavaScript half has
 * had `test/unit/roon-core.test.js` from the start, so the two halves were not
 * equally covered, and a bug that lived in BOTH went unnoticed: `start()`
 * returned immediately whenever `running` was already set, and nothing cleared
 * that but `stop()`. One interrupted attempt and "Find my Roon Core" was dead
 * for the life of the process.
 *
 * There is no Roon Core here, so the socket and the discovery are seams and
 * this drives scripted frames through them — the same approach as the
 * JavaScript tests, and the reason both can be written without a Core.
 */
class RoonCoreTest {

    /** The pairing token store, in memory. */
    private class Mem : RoonMemory {
        val tokens = HashMap<String, String>()
        var last: Pair<String, Int>? = null
        override fun tokenFor(coreId: String): String? = tokens[coreId]
        override fun saveToken(coreId: String, token: String) { tokens[coreId] = token }
        override fun lastCore(): Pair<String, Int>? = last
        override fun saveLastCore(host: String, port: Int) { last = host to port }
        override fun forgetLastCore() { last = null }
    }

    /**
     * A Core that answers `info` and `register`, and can be connected to MORE
     * THAN ONCE — which is the whole point. A fake that cannot reconnect is
     * how "press the button again" stayed broken behind a green suite.
     */
    private class FakeCore(private val silentRegister: Boolean = false) : RoonSocketFactory {
        val connects = AtomicInteger(0)
        @Volatile var handlers: RoonSocketHandlers? = null
        @Volatile var closed: String? = null
        @Volatile var url: String? = null
        val sent = java.util.Collections.synchronizedList(ArrayList<RoonProto.Message>())

        private val sock = object : RoonSocket {
            override fun send(frame: ByteArray) {
                val msg = RoonProto.parse(frame) ?: error("not a MOO frame")
                sent.add(msg)
                if (msg.verb != RoonProto.REQUEST) return
                val id = msg.requestId.toIntOrNull() ?: return
                val reply: Pair<String, String>? = when {
                    msg.name == "info" -> "Success" to
                        """{"core_id":"core-1","display_name":"Study Mac"}"""
                    msg.name == "register" && silentRegister -> null
                    msg.name == "register" -> "Registered" to
                        """{"core_id":"core-1","display_name":"Study Mac","token":"tok-77"}"""
                    else -> "InvalidRequest" to "{}"
                }
                if (reply == null) return
                handlers?.onFrame(RoonProto.encode(RoonProto.COMPLETE, reply.first, id,
                    reply.second.toByteArray(Charsets.UTF_8)))
            }

            override fun close(reason: String) {
                if (closed != null) return
                closed = reason
                handlers?.onClose(reason)
            }
        }

        override fun connect(url: String, handlers: RoonSocketHandlers): RoonSocket {
            this.url = url
            this.handlers = handlers
            this.closed = null      // a fresh connection is not a closed one
            connects.incrementAndGet()
            handlers.onOpen()
            return sock
        }

        fun drop(reason: String) = sock.close(reason)
    }

    private fun core(fake: FakeCore, mem: Mem = Mem()): RoonCore =
        RoonCore(memory = mem, sockets = fake,
            discovery = object : RoonDiscovery {
                override fun discover(
                    timeoutMs: Long, log: (String) -> Unit
                ): List<RoonProto.FoundCore> =
                    listOf(RoonProto.FoundCore("10.0.0.5", 9330, "core-1", "Study Mac"))
            })

    @Test fun `pairing registers and reaches paired`() {
        val fake = FakeCore()
        val mem = Mem()
        val c = core(fake, mem)
        c.start()
        assertTrue("it settled", c.awaitSettled(5_000))
        assertEquals(RoonStage.PAIRED, c.status.stage)
        assertEquals("Study Mac", c.status.coreName)
        assertEquals("the token is persisted, so approval happens once per Core ever",
            "tok-77", mem.tokenFor("core-1"))
    }

    @Test fun `pressing Find my Roon Core again after a dead attempt really tries again`() {
        // "0.3.0 fails to connect to Roon even after enabling again in Roon
        // extensions." start() returned immediately whenever `running` was
        // already true, and NOTHING cleared it but stop(), which only the
        // server's own shutdown calls. So the first attempt to be interrupted
        // -- the process frozen while the user was in Roon clicking Enable, a
        // Core that went away, a dropped socket -- left the app unable to try
        // again for the rest of its life, silently.
        val fake = FakeCore()
        val c = core(fake)
        c.start()
        assertTrue(c.awaitSettled(5_000))
        assertEquals(RoonStage.PAIRED, c.status.stage)

        fake.drop("the process was frozen while you were in Roon")
        Thread.sleep(200)
        assertTrue("the session is gone", !c.isPaired)

        val before = fake.connects.get()
        c.start()
        assertTrue("it settled again", c.awaitSettled(5_000))
        assertTrue("the button opened a new connection: $before -> ${fake.connects.get()}",
            fake.connects.get() > before)
        assertEquals("and it paired again", RoonStage.PAIRED, c.status.stage)
    }

    @Test fun `pressing it while a live pairing waits for approval does not restart it`() {
        // The idempotence worth keeping: a double tap must not tear down a
        // live socket that is waiting for the user to click Enable in Roon,
        // because the approval comes back on THAT socket.
        val fake = FakeCore(silentRegister = true)
        val c = core(fake)
        c.start()
        Thread.sleep(400)
        assertEquals(RoonStage.AWAITING_APPROVAL, c.status.stage)

        val before = fake.connects.get()
        c.start()
        Thread.sleep(300)
        // The socket ITSELF is the assertion, and it has to be: the net thread
        // is parked inside register() waiting for a reply that never comes, so
        // a queued reconnect would not run within any window a test can wait
        // for. Whether the live socket was closed under the user is observable
        // immediately, and is the property that matters — Roon's approval
        // comes back on this socket and nothing else.
        assertEquals("the live pairing socket was left open", null, fake.closed)
        assertEquals("and nothing reconnected", before, fake.connects.get())
        assertEquals(RoonStage.AWAITING_APPROVAL, c.status.stage)
    }
}
