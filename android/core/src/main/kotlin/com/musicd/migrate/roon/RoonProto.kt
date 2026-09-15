package com.musicd.migrate.roon

import java.io.ByteArrayOutputStream
import java.util.UUID

/*
 * RoonProto.kt — Roon's two wire formats, and nothing else.
 *
 * SOOD is how a Core is found: a UDP query multicast to 239.255.90.90:9003,
 * answered by every Core on the segment with its id and the port its extension
 * API listens on.
 *
 * MOO is how it is then talked to: an HTTP-like RPC framing carried in binary
 * WebSocket frames on ws://<core>:<port>/api.
 *
 * This is the Kotlin twin of lib/roon-proto.js, line for line where it can be.
 * The two halves of this app must agree about bytes on a wire, and the surest
 * way to keep two implementations agreeing is for them to be readable side by
 * side. Both were taken from the Kotlin in meltface-80/Android-Random-Remote,
 * which has been run against a real Core.
 */
object RoonProto {

    /** Roon's discovery multicast group and port. */
    const val SOOD_PORT = 9003
    const val SOOD_MULTICAST = "239.255.90.90"

    /** The service id every Roon Core answers a SOOD query for. */
    const val ROON_SERVICE_ID = "00720724-5143-4a9b-abac-0e50cba674bb"

    /** MOO verbs. REQUEST goes in either direction. */
    const val REQUEST = "REQUEST"
    const val COMPLETE = "COMPLETE"
    const val CONTINUE = "CONTINUE"

    const val REGISTRY = "com.roonlabs.registry:1"
    const val BROWSE = "com.roonlabs.browse:1"
    const val IMAGE = "com.roonlabs.image:1"
    const val PING = "com.roonlabs.ping:1"
    const val TRANSPORT = "com.roonlabs.transport:2"

    // ------------------------------------------------------------------ SOOD

    /*
     * Packet layout, from sood.js in node-roon-api:
     *
     *     "SOOD" | 0x02 | 'Q' or 'R' | property*
     *     property := name_len:u8 | name | value_len:u16be | value
     *
     * A value length of 0xFFFF means null — which is NOT the same as a value
     * of length zero, and conflating the two would turn "this Core did not
     * say" into "this Core said nothing".
     */

    private fun writeProp(out: ByteArrayOutputStream, name: String, value: String) {
        val n = name.toByteArray(Charsets.UTF_8)
        out.write(n.size and 0xFF)
        out.write(n)
        val v = value.toByteArray(Charsets.UTF_8)
        out.write((v.size shr 8) and 0xFF)
        out.write(v.size and 0xFF)
        out.write(v)
    }

    /**
     * A SOOD query. `tid` is echoed back by the Core; nothing here correlates
     * on it, because every reply is wanted whichever burst it answers.
     */
    fun buildSoodQuery(tid: String = UUID.randomUUID().toString()): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("SOOD".toByteArray(Charsets.UTF_8))
        out.write(2)
        out.write('Q'.code)
        writeProp(out, "_tid", tid)
        writeProp(out, "query_service_id", ROON_SERVICE_ID)
        return out.toByteArray()
    }

    data class SoodPacket(val kind: Char, val props: Map<String, String?>)

    /**
     * A SOOD packet of either kind, or null if this is not one.
     *
     * Null rather than a throw: a UDP socket listening on a broadcast port is
     * handed other protocols' packets, and those are not errors.
     *
     * Queries are parsed too, on purpose — it is the only way to check that
     * the QUERY writer got its lengths right, and a query with a mis-written
     * length is answered by no Core at all. That failure looks exactly like
     * "there is no Roon on this network".
     */
    fun parseSoodPacket(buf: ByteArray?, length: Int = buf?.size ?: 0): SoodPacket? {
        if (buf == null || length < 6) return null
        if (String(buf, 0, 4, Charsets.UTF_8) != "SOOD") return null
        if (buf[4].toInt() != 2) return null
        val kind = buf[5].toInt().toChar()
        if (kind != 'Q' && kind != 'R') return null

        val props = HashMap<String, String?>()
        var pos = 6
        while (pos < length) {
            val nameLen = buf[pos++].toInt() and 0xFF
            if (nameLen == 0 || pos + nameLen > length) return null
            val name = String(buf, pos, nameLen, Charsets.UTF_8)
            pos += nameLen
            if (pos + 2 > length) return null
            val valLen = ((buf[pos++].toInt() and 0xFF) shl 8) or (buf[pos++].toInt() and 0xFF)
            when {
                valLen == 0xFFFF -> props[name] = null
                valLen == 0 -> props[name] = ""
                pos + valLen > length -> return null
                else -> {
                    props[name] = String(buf, pos, valLen, Charsets.UTF_8)
                    pos += valLen
                }
            }
        }
        return SoodPacket(kind, props)
    }

    /** The properties of a SOOD REPLY, or null for anything else. */
    fun parseSoodReply(buf: ByteArray?, length: Int = buf?.size ?: 0): Map<String, String?>? {
        val packet = parseSoodPacket(buf, length)
        return if (packet != null && packet.kind == 'R') packet.props else null
    }

    data class FoundCore(
        val host: String,
        val port: Int,
        val uniqueId: String,
        val displayName: String = ""
    )

    /**
     * What a reply has to carry to be worth connecting to, or null.
     *
     * `http_port` is the extension API's port and is not optional: without it
     * there is nowhere to connect, so a reply missing it is not a usable Core
     * however well-formed it was.
     */
    fun soodCore(props: Map<String, String?>?, fallbackHost: String?): FoundCore? {
        if (props == null) return null
        val uniqueId = props["unique_id"] ?: return null
        val port = props["http_port"]?.toIntOrNull() ?: return null
        if (port <= 0) return null
        val host = props["_replyaddr"] ?: fallbackHost ?: return null
        if (host.isEmpty() || uniqueId.isEmpty()) return null
        return FoundCore(host, port, uniqueId, props["name"] ?: props["display_name"] ?: "")
    }

    // ------------------------------------------------------------------- MOO

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
     * Request-Id correlates the two directions. COMPLETE is the last word on
     * an id; CONTINUE keeps it open, which is how a subscription streams. That
     * distinction is load-bearing: a handler removed on CONTINUE would drop
     * every update after the first.
     */

    class Message(
        val verb: String,
        /** Only set for REQUEST, e.g. "com.roonlabs.ping:1". */
        val service: String?,
        /** Method for a REQUEST, result name ("Success", "Registered") otherwise. */
        val name: String,
        val requestId: String,
        val headers: Map<String, String>,
        val body: ByteArray?
    ) {
        val bodyText: String? get() = body?.toString(Charsets.UTF_8)
    }

    fun encode(
        verb: String,
        line: String,
        requestId: Int,
        body: ByteArray? = null,
        contentType: String = "application/json"
    ): ByteArray {
        val header = StringBuilder()
        header.append("MOO/1 ").append(verb).append(' ').append(line).append('\n')
        header.append("Request-Id: ").append(requestId).append('\n')
        if (body != null) {
            header.append("Content-Length: ").append(body.size).append('\n')
            header.append("Content-Type: ").append(contentType).append('\n')
        }
        header.append('\n')
        val out = ByteArrayOutputStream()
        out.write(header.toString().toByteArray(Charsets.UTF_8))
        if (body != null) out.write(body)
        return out.toByteArray()
    }

    /** Encode a request whose body is a JSON string (or nothing). */
    fun encodeRequest(service: String, method: String, requestId: Int, body: String?): ByteArray =
        encode(REQUEST, "$service/$method", requestId, body?.toByteArray(Charsets.UTF_8))

    /**
     * Parse one MOO frame, or null if it is not one.
     *
     * Deliberately byte-oriented rather than a split on "\n\n": the BODY is
     * arbitrary bytes of exactly Content-Length, and a body containing a blank
     * line would break a string split. That is not hypothetical — album titles
     * arrive in here.
     */
    fun parse(buf: ByteArray?): Message? {
        if (buf == null || buf.isEmpty()) return null

        var verb: String? = null
        var service: String? = null
        var name: String? = null
        var requestId: String? = null
        var contentLength: Int? = null
        val headers = HashMap<String, String>()

        var start = 0
        var i = 0
        var inHeaders = false

        while (i < buf.size) {
            if (buf[i].toInt() != 0x0A) { i++; continue }
            val line = String(buf, start, i - start, Charsets.UTF_8)

            if (!inHeaders) {
                if (!line.startsWith("MOO/")) return null
                val sp1 = line.indexOf(' ')
                if (sp1 < 0) return null
                val sp2 = line.indexOf(' ', sp1 + 1)
                if (sp2 < 0) return null
                verb = line.substring(sp1 + 1, sp2)
                val rest = line.substring(sp2 + 1)
                if (verb == REQUEST) {
                    val slash = rest.indexOf('/')
                    if (slash < 0) return null
                    service = rest.substring(0, slash)
                    name = rest.substring(slash + 1)
                } else {
                    name = rest
                }
                inHeaders = true
            } else if (line.isEmpty()) {
                // The blank line ends the headers; the body follows verbatim.
                if (requestId == null) return null
                var body: ByteArray? = null
                val len = contentLength
                if (len != null && len > 0) {
                    val bodyStart = i + 1
                    if (bodyStart + len > buf.size) return null
                    body = buf.copyOfRange(bodyStart, bodyStart + len)
                }
                return Message(verb!!, service, name ?: "", requestId, headers, body)
            } else {
                val colon = line.indexOf(':')
                if (colon < 0) return null
                val key = line.substring(0, colon)
                val value = line.substring(colon + 1).trimStart()
                when (key) {
                    "Request-Id" -> requestId = value
                    "Content-Length" -> contentLength = value.toIntOrNull()
                    else -> headers[key] = value
                }
            }

            i++
            start = i
        }
        // Ran out of bytes before the blank line: a truncated frame.
        return null
    }
}
