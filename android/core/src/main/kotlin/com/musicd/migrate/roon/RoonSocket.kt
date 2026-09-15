package com.musicd.migrate.roon

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/*
 * RoonSocket.kt — a WebSocket, hand-rolled, because :core has no dependencies.
 *
 * Http.kt says it plainly: this module depends on nothing, org.json included
 * (Android supplies that one). Pulling OkHttp in for one socket would put a
 * second HTTP stack in the APK and a second thing to keep current, so the
 * client side of RFC 6455 is written out here instead. It is not much: an
 * upgrade handshake, a frame codec, and a reader thread.
 *
 * Only what Roon's MOO transport actually uses:
 *   - text and binary frames, unfragmented, which is every MOO message
 *   - continuation frames on the read side, because a server may split one
 *   - ping/pong, answered so the Core does not time the connection out
 *   - close
 * No extensions, no compression, no TLS. A Roon Core is on the local network
 * and speaks plain ws://; there is no wss:// to support.
 *
 * MASKING IS NOT OPTIONAL. Every frame a CLIENT sends must be masked with four
 * random bytes, and a server that gets an unmasked frame is required to close
 * the connection — which reads exactly like "the Core refused us".
 */

/** The seam. RoonCore is handed one of these; the tests hand it a fake. */
interface RoonSocketFactory {
    fun connect(url: String, handlers: RoonSocketHandlers): RoonSocket
}

interface RoonSocket {
    fun send(frame: ByteArray)
    fun close(reason: String)
}

interface RoonSocketHandlers {
    fun onOpen()
    fun onFrame(frame: ByteArray)
    /** Terminal: the socket is gone and this object will not be reused. */
    fun onClose(reason: String)
}

private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

private const val OP_CONTINUATION = 0x0
private const val OP_TEXT = 0x1
private const val OP_BINARY = 0x2
private const val OP_CLOSE = 0x8
private const val OP_PING = 0x9
private const val OP_PONG = 0xA

/** The real thing: one thread per connection, reading until it dies. */
class RawWebSocketFactory(
    private val connectTimeoutMs: Int = 6000,
    private val log: (String) -> Unit = {}
) : RoonSocketFactory {

    override fun connect(url: String, handlers: RoonSocketHandlers): RoonSocket {
        val uri = URI(url)
        require(uri.scheme == "ws") { "Only ws:// is supported for a Roon Core" }
        val host = uri.host ?: throw IllegalArgumentException("No host in $url")
        val port = if (uri.port > 0) uri.port else 80
        val path = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath

        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        // No read timeout: a MOO session is idle for long stretches — a first
        // pair waits for the user to enable the extension — and a read timeout
        // would tear the connection down while nothing was wrong. The Core's
        // own pings are what prove it is still there.
        socket.soTimeout = 0

        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val keyB64 = Base64.getEncoder().encodeToString(key)
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append(':').append(port).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(keyB64).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        out.write(request.toByteArray(Charsets.UTF_8))
        out.flush()

        val accepted = readHandshake(input)
        val expect = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((keyB64 + GUID).toByteArray(Charsets.UTF_8)))
        if (accepted == null || !accepted.equals(expect, ignoreCase = false)) {
            runCatching { socket.close() }
            // Checked, not assumed: without it anything answering on that port
            // would be treated as a Roon Core and every frame would be
            // gibberish, which reads as a Core that connected and said nothing.
            throw RoonException("That address answered, but not as a Roon Core")
        }

        val conn = Connection(socket, out, input, handlers, log)
        handlers.onOpen()
        conn.startReader()
        return conn
    }

    /** The 101 response's Sec-WebSocket-Accept, or null if it was not a 101. */
    private fun readHandshake(input: InputStream): String? {
        val header = StringBuilder()
        var last4 = 0
        while (header.length < 8192) {
            val b = input.read()
            if (b < 0) return null
            header.append(b.toChar())
            last4 = ((last4 shl 8) or b) and -1
            if (header.length >= 4 && header.endsWith("\r\n\r\n")) break
        }
        val lines = header.toString().split("\r\n")
        if (lines.isEmpty() || !lines[0].contains(" 101")) return null
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            if (line.substring(0, colon).equals("Sec-WebSocket-Accept", true)) {
                return line.substring(colon + 1).trim()
            }
        }
        return null
    }

    private class Connection(
        private val socket: Socket,
        private val out: OutputStream,
        private val input: InputStream,
        private val handlers: RoonSocketHandlers,
        private val log: (String) -> Unit
    ) : RoonSocket {

        private val random = SecureRandom()
        @Volatile private var dead = false
        private val writeLock = Any()

        fun startReader() {
            Thread({ readLoop() }, "roon-ws").apply { isDaemon = true }.start()
        }

        override fun send(frame: ByteArray) {
            if (dead) throw RoonException("The Roon connection is closed")
            write(OP_BINARY, frame)
        }

        override fun close(reason: String) {
            if (dead) return
            runCatching { write(OP_CLOSE, ByteArray(0)) }
            terminate(reason)
        }

        private fun write(opcode: Int, payload: ByteArray) {
            synchronized(writeLock) {
                val head = java.io.ByteArrayOutputStream()
                head.write(0x80 or opcode)
                val mask = ByteArray(4).also { random.nextBytes(it) }
                when {
                    payload.size < 126 -> head.write(0x80 or payload.size)
                    payload.size < 65536 -> {
                        head.write(0x80 or 126)
                        head.write((payload.size shr 8) and 0xFF)
                        head.write(payload.size and 0xFF)
                    }
                    else -> {
                        head.write(0x80 or 127)
                        for (shift in 56 downTo 0 step 8) {
                            head.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                        }
                    }
                }
                head.write(mask)
                val masked = ByteArray(payload.size)
                for (i in payload.indices) masked[i] = (payload[i].toInt() xor
                    mask[i and 3].toInt()).toByte()
                head.write(masked)
                out.write(head.toByteArray())
                out.flush()
            }
        }

        private fun readLoop() {
            try {
                // A server MAY split one message across continuation frames.
                // Roon does not, but a message reassembled wrongly is a MOO
                // frame that fails to parse, which is invisible.
                var pending: java.io.ByteArrayOutputStream? = null
                while (!dead) {
                    val b0 = input.read()
                    if (b0 < 0) break
                    val fin = (b0 and 0x80) != 0
                    val opcode = b0 and 0x0F
                    val b1 = input.read()
                    if (b1 < 0) break
                    val masked = (b1 and 0x80) != 0
                    var len = (b1 and 0x7F).toLong()
                    if (len == 126L) {
                        len = ((readByte() shl 8) or readByte()).toLong()
                    } else if (len == 127L) {
                        var v = 0L
                        repeat(8) { v = (v shl 8) or readByte().toLong() }
                        len = v
                    }
                    if (len > 32L * 1024 * 1024) break   // not a MOO frame
                    val mask = if (masked) ByteArray(4) { readByte().toByte() } else null
                    val payload = ByteArray(len.toInt())
                    var read = 0
                    while (read < payload.size) {
                        val n = input.read(payload, read, payload.size - read)
                        if (n < 0) { terminate("the Core closed the connection"); return }
                        read += n
                    }
                    if (mask != null) {
                        for (i in payload.indices) {
                            payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                        }
                    }

                    when (opcode) {
                        OP_CLOSE -> { terminate("the Core closed the connection"); return }
                        OP_PING -> write(OP_PONG, payload)
                        OP_PONG -> Unit
                        OP_TEXT, OP_BINARY, OP_CONTINUATION -> {
                            if (fin && pending == null) {
                                handlers.onFrame(payload)
                            } else {
                                val buf = pending ?: java.io.ByteArrayOutputStream()
                                    .also { pending = it }
                                buf.write(payload)
                                if (fin) {
                                    handlers.onFrame(buf.toByteArray())
                                    pending = null
                                }
                            }
                        }
                        else -> Unit
                    }
                }
                terminate("disconnected")
            } catch (e: Exception) {
                terminate(e.message ?: "the connection failed")
            }
        }

        private fun readByte(): Int {
            val b = input.read()
            if (b < 0) throw RoonException("the connection ended mid-frame")
            return b and 0xFF
        }

        private fun terminate(reason: String) {
            if (dead) return
            dead = true
            log("socket closed: $reason")
            runCatching { socket.close() }
            handlers.onClose(reason)
        }
    }
}
