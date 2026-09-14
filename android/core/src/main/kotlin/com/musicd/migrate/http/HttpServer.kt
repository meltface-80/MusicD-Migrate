package com.musicd.migrate.http

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small HTTP/1.1 server bound to the loopback interface.
 *
 * ADAPTED FROM meltface-80/Android-Random-Remote (com.musicd.lite.http), same
 * author, same licence, and the hard-won details are kept: SO_REUSEADDR before
 * the bind, the insistent retry on the requested port, keep-alive, and a
 * shutdown that does not interrupt the worker doing the shutting down.
 *
 * This is what lets the repository's public/ run unchanged inside the APK: the
 * page is the same HTML, CSS and JavaScript the Docker build serves, and it
 * still talks to /api/… over HTTP. Intercepting requests in the WebView cannot
 * work, because shouldInterceptRequest is not given the BODY of a POST — and
 * this app POSTs to sign in, to start a migration and to cancel one.
 *
 * LOOPBACK BY DEFAULT, AND THAT IS THE ONLY SAFE STATE HERE. Behind this
 * socket are two services' access tokens and the ability to write to somebody's
 * music library. MusicD Remote Lite offers a LAN switch with a PIN gate in
 * front; this app deliberately does not offer one at all — there is no reason
 * for anybody else's device to reach a migration running on this phone, and an
 * option that only ever adds risk is an option not worth having.
 */
class HttpServer(
    private val handler: (Request) -> Response,
    requestedPort: Int = 0
) {
    private val server = bind(requestedPort)
    private val running = AtomicBoolean(false)
    private val threadSeq = AtomicInteger(0)

    private val workers = ThreadPoolExecutor(
        2, 16, 60L, TimeUnit.SECONDS, SynchronousQueue(),
        { r -> Thread(r, "http-${threadSeq.incrementAndGet()}").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy()
    )

    private val acceptor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "http-accept").apply { isDaemon = true }
    }

    val port: Int get() = server.localPort
    val rootUrl: String get() = "http://$LOOPBACK:$port"

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptor.execute {
            while (running.get()) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    continue
                }
                workers.execute { serve(client) }
            }
        }
    }

    /**
     * Closes the listening socket and lets the workers finish.
     *
     * shutdown(), NOT shutdownNow(): a request handler runs ON one of these
     * workers, so shutdownNow() would interrupt the very thread doing the
     * shutting down and everything interruptible it does next fails instantly.
     * The socket is closed first either way, which is what actually stops new
     * work arriving.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server.close() }
        workers.shutdown()
        acceptor.shutdownNow()
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = READ_TIMEOUT_MS
            client.tcpNoDelay = true
            val input = client.getInputStream().buffered()
            val output = BufferedOutputStream(client.getOutputStream())

            // Keep-alive: the page polls a job's progress roughly once a
            // second, and a fresh connection per poll is pure overhead.
            while (running.get()) {
                val request = try {
                    readRequest(input, client.inetAddress?.hostAddress ?: "")
                } catch (e: SocketTimeoutException) {
                    return
                } catch (e: HttpError) {
                    write(output, Response.text(e.status, e.message ?: "Bad request"), false)
                    output.flush()
                    return
                } ?: return

                val response = try {
                    handler(request)
                } catch (e: Exception) {
                    Response.json(500, """{"error":${quote(e.message ?: "Internal error")}}""")
                }

                write(output, response, request.method == "HEAD")
                output.flush()
                if (!request.keepAlive) return
            }
        } catch (e: IOException) {
            // The other end went away mid-request. Ordinary.
        } finally {
            runCatching { client.close() }
        }
    }

    private class HttpError(val status: Int, message: String) : IOException(message)

    private fun readRequest(input: InputStream, remote: String): Request? {
        val line = readLine(input) ?: return null
        if (line.isEmpty()) return null

        val parts = line.split(' ')
        if (parts.size < 3) throw HttpError(400, "Malformed request line")
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        var headerBytes = line.length
        while (true) {
            val h = readLine(input) ?: throw HttpError(400, "Truncated headers")
            if (h.isEmpty()) break
            headerBytes += h.length
            if (headerBytes > MAX_HEADER_BYTES) throw HttpError(431, "Headers too large")
            val colon = h.indexOf(':')
            if (colon <= 0) continue
            headers[h.substring(0, colon).lowercase()] = h.substring(colon + 1).trim()
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY_BYTES) throw HttpError(413, "Body too large")
        val body = if (length > 0) ByteArray(length).also { readFully(input, it) } else ByteArray(0)

        val keepAlive = headers["connection"]?.lowercase() != "close"
        val q = target.indexOf('?')
        val path = if (q < 0) target else target.substring(0, q)
        val query = if (q < 0) emptyMap() else parseQuery(target.substring(q + 1))

        return Request(method, decodePath(path), query, headers, body, keepAlive, remote,
            rawTarget = target)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(128)
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
            if (sb.length > MAX_HEADER_BYTES) throw HttpError(431, "Header line too long")
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw HttpError(400, "Truncated body")
            read += n
        }
    }

    private fun write(out: BufferedOutputStream, response: Response, headOnly: Boolean) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(response.status).append(' ')
            .append(statusText(response.status)).append("\r\n")
        sb.append("Content-Type: ").append(response.contentType).append("\r\n")
        sb.append("Content-Length: ").append(response.body.size).append("\r\n")
        for ((k, v) in response.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(response.body)
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        private const val BIND_TRIES = 20
        private const val BIND_WAIT_MS = 25L
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 32 * 1024
        private const val MAX_BODY_BYTES = 4 * 1024 * 1024

        /**
         * THE RETRY IS NOT POLITENESS. For a few milliseconds after a close
         * the kernel can still refuse the same port — the old connections have
         * not finished going away. Taking the first refusal at face value
         * silently moves the port, and the WebView is already showing a page
         * loaded from the old one, so it would be left talking to nothing with
         * no way to explain itself.
         */
        private fun bind(port: Int): ServerSocket {
            val addr = InetAddress.getByName(LOOPBACK)
            if (port == 0) return open(0, addr)
            repeat(BIND_TRIES) {
                try {
                    return open(port, addr)
                } catch (e: IOException) {
                    try {
                        Thread.sleep(BIND_WAIT_MS)
                    } catch (interrupted: InterruptedException) {
                        // Keep the flag for whoever owns this thread, but do
                        // NOT give up on the port: taking a random one is the
                        // damaging outcome, not waiting a moment longer.
                        Thread.currentThread().interrupt()
                    }
                }
            }
            return open(0, addr)
        }

        /**
         * SO_REUSEADDR has to be set BEFORE the bind, which the
         * ServerSocket(port, backlog, address) constructor cannot do: its
         * initial setting is explicitly undefined, so on a JDK where it
         * defaults off, reclaiming a port straight after a close never works.
         */
        private fun open(port: Int, addr: InetAddress): ServerSocket =
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(addr, port), 64)
            }

        fun parseQuery(raw: String): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            val out = HashMap<String, String>()
            for (pair in raw.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = if (eq < 0) pair else pair.substring(0, eq)
                val value = if (eq < 0) "" else pair.substring(eq + 1)
                out[formDecode(key)] = formDecode(value)
            }
            return out
        }

        /** Query values are form-encoded, so "+" is a space. */
        private fun formDecode(s: String): String =
            try {
                URLDecoder.decode(s, "UTF-8")
            } catch (e: Exception) {
                s
            }

        /** Path segments are percent-encoded but "+" is a literal plus, not a
         *  space — decoding it as one corrupts any key containing one. */
        fun decodePath(s: String): String =
            try {
                URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                s
            }

        fun quote(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c < ' ' -> sb.append("\\u%04x".format(c.code))
                    else -> sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }

        fun statusText(code: Int): String = when (code) {
            200 -> "OK"
            204 -> "No Content"
            302 -> "Found"
            304 -> "Not Modified"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            else -> "Status $code"
        }
    }
}

class Request(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val keepAlive: Boolean,
    val remoteAddress: String = HttpServer.LOOPBACK,
    /** The request target exactly as it arrived, query string included. The
     *  OAuth callbacks parse this rather than reassembling it. */
    val rawTarget: String = path
) {
    val bodyText: String get() = body.toString(Charsets.UTF_8)
    fun param(name: String): String? = query[name]?.takeIf { it.isNotEmpty() }
}

class Response(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap()
) {
    companion object {
        fun json(status: Int, json: String) =
            Response(status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))

        fun text(status: Int, text: String) =
            Response(status, "text/plain; charset=utf-8", text.toByteArray(Charsets.UTF_8))

        fun html(status: Int, html: String) =
            Response(status, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))

        fun bytes(status: Int, contentType: String, body: ByteArray,
                  headers: Map<String, String> = emptyMap()) =
            Response(status, contentType, body, headers)

        fun redirect(location: String) =
            Response(302, "text/plain; charset=utf-8", ByteArray(0),
                mapOf("Location" to location))

        fun notFound() = json(404, """{"error":"Not found"}""")
    }
}

/** Where the bundled page comes from. The app module reads the APK's assets;
 *  tests can supply anything. */
interface Assets {
    fun read(path: String): ByteArray?
}
