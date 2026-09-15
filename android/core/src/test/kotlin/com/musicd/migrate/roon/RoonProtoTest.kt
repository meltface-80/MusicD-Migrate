package com.musicd.migrate.roon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The two wire formats, tested without a Roon Core.
 *
 * The twin of test/unit/roon-proto.test.js. There is no Core in CI and there
 * never will be, so this is the only part of the Roon support that can be
 * checked completely — which is why the codecs were separated out. The failure
 * these guard against is not a crash: a u16 read little-endian, or a body
 * taken one byte short, makes a Core look absent or a library look empty, and
 * "Roon found nothing" is indistinguishable from "Roon is not running".
 */
class RoonProtoTest {

    /** Build a reply packet the way a Core does, so the parser can be checked. */
    private fun soodReply(props: List<Pair<String, String?>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("SOOD".toByteArray())
        out.write(2); out.write('R'.code)
        for ((name, value) in props) {
            val n = name.toByteArray(Charsets.UTF_8)
            out.write(n.size)
            out.write(n)
            if (value == null) {
                out.write(0xFF); out.write(0xFF)
            } else {
                val v = value.toByteArray(Charsets.UTF_8)
                out.write((v.size shr 8) and 0xFF)
                out.write(v.size and 0xFF)
                out.write(v)
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ SOOD

    @Test fun `a SOOD query names the Roon service id and nothing else`() {
        val q = RoonProto.buildSoodQuery("abc")
        assertEquals("SOOD", String(q, 0, 4))
        assertEquals(2, q[4].toInt())
        assertEquals('Q', q[5].toInt().toChar())

        // Decoded, not merely searched for. A query whose length fields are
        // written the wrong way round still CONTAINS the service id, and is
        // still answered by no Core whatsoever — which reads as "there is no
        // Roon on this network" and sends the user looking at their Wi-Fi.
        val packet = RoonProto.parseSoodPacket(q)!!
        assertEquals('Q', packet.kind)
        assertEquals(setOf("_tid", "query_service_id"), packet.props.keys)
        assertEquals(RoonProto.ROON_SERVICE_ID, packet.props["query_service_id"])
        assertEquals("abc", packet.props["_tid"])
    }

    @Test fun `a long value in a query survives its own length field`() {
        // 300 bytes: 0x012C little-endian is 0x2C01, and the property walk
        // then runs off the end of the packet.
        val packet = RoonProto.parseSoodPacket(RoonProto.buildSoodQuery("t".repeat(300)))
        assertNotNull("the query must be parseable by the same walker", packet)
        assertEquals(300, packet!!.props["_tid"]!!.length)
    }

    @Test fun `a SOOD reply is parsed, including a null value`() {
        val props = RoonProto.parseSoodReply(soodReply(listOf(
            "unique_id" to "1f2e", "http_port" to "9330", "name" to "Study", "tid" to null)))!!
        assertEquals("1f2e", props["unique_id"])
        assertEquals("9330", props["http_port"])
        assertEquals("Study", props["name"])
        assertTrue("0xFFFF is null, not an empty string", props.containsKey("tid"))
        assertNull(props["tid"])
    }

    @Test fun `an empty string value is not the same as a null one`() {
        val props = RoonProto.parseSoodReply(soodReply(listOf(
            "unique_id" to "x", "http_port" to "1", "name" to "")))!!
        assertEquals("", props["name"])
    }

    @Test fun `a two-byte length is read big-endian`() {
        val long = "n".repeat(300)
        val props = RoonProto.parseSoodReply(soodReply(listOf(
            "unique_id" to "x", "http_port" to "1", "name" to long)))!!
        assertEquals(300, props["name"]!!.length)
    }

    @Test fun `packets that are not SOOD replies are ignored rather than thrown on`() {
        assertNull(RoonProto.parseSoodReply("hello world".toByteArray()))
        assertNull(RoonProto.parseSoodReply(ByteArray(0)))
        assertNull(RoonProto.parseSoodReply(null))
        assertNull("a query is not a reply",
            RoonProto.parseSoodReply(RoonProto.buildSoodQuery("t")))
        val good = soodReply(listOf("unique_id" to "abcdef", "http_port" to "9330"))
        assertNull(RoonProto.parseSoodReply(good.copyOfRange(0, good.size - 3)))
    }

    @Test fun `a Core is only usable if it said where to connect`() {
        val withAddr = RoonProto.soodCore(
            mapOf("unique_id" to "u", "http_port" to "9330", "_replyaddr" to "10.0.0.5"), null)
        assertEquals(RoonProto.FoundCore("10.0.0.5", 9330, "u", ""), withAddr)

        assertEquals("10.0.0.9", RoonProto.soodCore(
            mapOf("unique_id" to "u", "http_port" to "9330"), "10.0.0.9")!!.host)

        assertNull("no port means nowhere to connect",
            RoonProto.soodCore(mapOf("unique_id" to "u"), "10.0.0.9"))
        assertNull(RoonProto.soodCore(mapOf("http_port" to "9330"), "10.0.0.9"))
        assertNull(RoonProto.soodCore(mapOf("unique_id" to "u", "http_port" to "0"), "10.0.0.9"))
        assertNull(RoonProto.soodCore(null, "10.0.0.9"))
    }

    // ------------------------------------------------------------------- MOO

    @Test fun `a MOO request round-trips`() {
        val frame = RoonProto.encodeRequest("com.roonlabs.browse:1", "load", 7,
            """{"hierarchy":"albums","offset":0}""")
        val msg = RoonProto.parse(frame)!!
        assertEquals("REQUEST", msg.verb)
        assertEquals("com.roonlabs.browse:1", msg.service)
        assertEquals("load", msg.name)
        assertEquals("7", msg.requestId)
        assertEquals("""{"hierarchy":"albums","offset":0}""", msg.bodyText)
    }

    @Test fun `a response carries a result name and no service`() {
        val frame = RoonProto.encode("COMPLETE", "Registered", 1,
            """{"core_id":"c1","token":"t"}""".toByteArray())
        val msg = RoonProto.parse(frame)!!
        assertEquals("COMPLETE", msg.verb)
        assertNull("only a REQUEST names a service", msg.service)
        assertEquals("Registered", msg.name)
    }

    @Test fun `a bodiless message parses, and its body is null rather than empty`() {
        val msg = RoonProto.parse(RoonProto.encode("COMPLETE", "Success", 3, null))!!
        assertEquals("Success", msg.name)
        assertNull(msg.body)
        assertNull(msg.bodyText)
    }

    @Test fun `a body containing a blank line is read whole`() {
        // The reason this parser counts bytes instead of splitting on "\n\n".
        // An album title with a newline in it would otherwise truncate the
        // message and the whole page of albums would be lost as unparseable.
        val body = "{\"title\":\"one\n\ntwo\"}".toByteArray(Charsets.UTF_8)
        val msg = RoonProto.parse(RoonProto.encode("COMPLETE", "Success", 4, body))!!
        assertEquals("{\"title\":\"one\n\ntwo\"}", msg.bodyText)
    }

    @Test fun `a body is measured in bytes, not characters`() {
        // "é" is two bytes. A Content-Length written as a character count
        // would cut the JSON short, and every non-ASCII album title in a page
        // would break it.
        val text = """{"title":"Café Bleu — Émilie"}"""
        val body = text.toByteArray(Charsets.UTF_8)
        val frame = RoonProto.encode("COMPLETE", "Success", 5, body)
        assertTrue(String(frame, Charsets.UTF_8).contains("Content-Length: ${body.size}"))
        assertEquals(text, RoonProto.parse(frame)!!.bodyText)
    }

    @Test fun `headers other than the two that matter are kept`() {
        val msg = RoonProto.parse(
            "MOO/1 COMPLETE Success\nRequest-Id: 2\nLogging: async\n\n".toByteArray())!!
        assertEquals("async", msg.headers["Logging"])
        assertEquals("2", msg.requestId)
    }

    @Test fun `a truncated frame is not a message`() {
        val frame = RoonProto.encode("COMPLETE", "Success", 6, """{"a":1}""".toByteArray())
        assertNull("a short body must not be handed on as a good message",
            RoonProto.parse(frame.copyOfRange(0, frame.size - 3)))
        assertNull(RoonProto.parse("MOO/1 COMPLETE Success\n".toByteArray()))
        assertNull("no Request-Id: nothing to correlate it with",
            RoonProto.parse("MOO/1 COMPLETE Success\n\n".toByteArray()))
    }

    @Test fun `something that is not MOO at all is ignored`() {
        assertNull(RoonProto.parse("GET / HTTP/1.1\n\n".toByteArray()))
        assertNull(RoonProto.parse(ByteArray(0)))
        assertNull(RoonProto.parse(null))
        assertNull(RoonProto.parse("MOO/1 COMPLETE\n\n".toByteArray()))
    }

    /**
     * The two implementations produce the same bytes.
     *
     * Not a formality: index.js and the APK talk to the same Core with the
     * same protocol, and a difference here is a feature that works in Docker
     * and silently does not on a phone. The JavaScript side's own fixtures are
     * in test/unit/roon-proto.test.js; these are the bytes it produces.
     */
    @Test fun `the SOOD query bytes match the JavaScript ones`() {
        // Pinned as hex, against what lib/roon-proto.js produces for the same
        // transaction id. Two implementations of one packet format, and a
        // Core answers neither if they disagree.
        assertEquals(
            "534f4f440251045f74696400036162631071756572795f73" +
            "6572766963655f6964002430303732303732342d35313433" +
            "2d346139622d616261632d306535306362613637346262",
            RoonProto.buildSoodQuery("abc").joinToString("") { "%02x".format(it) })
    }

    @Test fun `the frames match the ones lib roon-proto js produces`() {
        assertEquals(
            "MOO/1 REQUEST com.roonlabs.registry:1/info\nRequest-Id: 0\n\n",
            String(RoonProto.encodeRequest("com.roonlabs.registry:1", "info", 0, null),
                   Charsets.UTF_8))
        assertEquals(
            "MOO/1 COMPLETE Success\nRequest-Id: 4\nContent-Length: 7\n" +
                "Content-Type: application/json\n\n{\"a\":1}",
            String(RoonProto.encode("COMPLETE", "Success", 4, """{"a":1}""".toByteArray()),
                   Charsets.UTF_8))
    }
}
