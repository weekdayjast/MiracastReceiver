package top.jast.miracastreceiver.settings

import top.jast.miracastreceiver.util.ReceiverName
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.UUID
import kotlin.concurrent.thread

/** A short-lived, token-protected LAN form. Owned by the visible QR dialog only. */
class NameSetupServer(
    address: String,
    private val page: (message: String, saved: Boolean) -> String,
    private val save: (String) -> Boolean,
    private val savedMessage: String,
    private val invalidMessage: String,
    private val lifetimeMs: Long = 10 * 60_000L
) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName(address)).apply { soTimeout = 1000 }
    private val path = "/${UUID.randomUUID().toString().replace("-", "")}"
    val url = "http://$address:${server.localPort}$path"
    private val deadline = System.nanoTime() + lifetimeMs * 1_000_000
    @Volatile private var client: Socket? = null

    init {
        thread(name = "receiver-name-setup", isDaemon = true) {
            try {
                while (!server.isClosed && System.nanoTime() < deadline) {
                    try {
                        server.accept().use { socket ->
                            client = socket
                            socket.soTimeout = 3000
                            runCatching { handle(socket) }
                            client = null
                        }
                    } catch (_: SocketTimeoutException) { /* Check expiry. */ }
                }
            } catch (_: Exception) { /* Dialog closed. */ }
            finally { close() }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        fun line(): String {
            val bytes = ArrayList<Byte>()
            while (bytes.size < 4096) {
                require(System.nanoTime() < deadline && !server.isClosed)
                val b = input.read()
                require(b >= 0)
                if (b == 10) return bytes.toByteArray().toString(Charsets.US_ASCII).trimEnd('\r')
                bytes.add(b.toByte())
            }
            error("Header too large")
        }
        val request = line().split(' ')
        require(request.size == 3)
        val headers = mutableMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val value = line()
            if (value.isEmpty()) break
            headerBytes += value.length
            require(headerBytes <= 8192)
            val parts = value.split(':', limit = 2)
            require(parts.size == 2)
            val key = parts[0].lowercase(java.util.Locale.ROOT)
            require(key !in headers)
            headers[key] = parts[1].trim()
        }
        if (request[1] != path) return respond(socket, "404 Not Found", "Not found")
        if (System.nanoTime() >= deadline || server.isClosed) return respond(socket, "410 Gone", "Expired")
        when (request[0]) {
            "GET" -> respond(socket, "200 OK", page("", false))
            "POST" -> {
                if (headers["content-type"]?.substringBefore(';') != "application/x-www-form-urlencoded" ||
                    "transfer-encoding" in headers) return respond(socket, "400 Bad Request", page(invalidMessage, false))
                val size = headers["content-length"]?.toIntOrNull()
                if (size == null || size !in 1..2048) return respond(socket, "400 Bad Request", page(invalidMessage, false))
                val body = ByteArray(size)
                var offset = 0
                while (offset < size) {
                    require(System.nanoTime() < deadline && !server.isClosed)
                    val count = input.read(body, offset, size - offset)
                    require(count > 0)
                    offset += count
                }
                val fields = body.toString(Charsets.UTF_8).split('&').map {
                    val pair = it.split('=', limit = 2)
                    URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                }
                val name = fields.singleOrNull { it.first == "name" }?.second?.let(ReceiverName::normalize)
                if (name == null) respond(socket, "400 Bad Request", page(invalidMessage, false))
                else if (server.isClosed || System.nanoTime() >= deadline) respond(socket, "410 Gone", "Expired")
                else if (runCatching { save(name) }.getOrDefault(false)) respond(socket, "200 OK", page(savedMessage, true))
                else respond(socket, "500 Internal Server Error", page(invalidMessage, false))
            }
            else -> respond(socket, "405 Method Not Allowed", "Method not allowed")
        }
    }

    private fun respond(socket: Socket, status: String, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nCache-Control: no-store\r\n" +
                "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
                "Content-Security-Policy: default-src 'none'; script-src 'sha256-${NameSetupPage.scriptHash}'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    override fun close() {
        runCatching { server.close() }
        runCatching { client?.close() }
    }
}
