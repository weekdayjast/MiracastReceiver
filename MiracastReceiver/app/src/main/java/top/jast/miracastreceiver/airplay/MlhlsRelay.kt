package top.jast.miracastreceiver.airplay

import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import top.jast.miracastreceiver.util.Logger

/** Loopback manifest gateway. Media segments with HTTPS addresses are fetched by the player. */
internal class MlhlsRelay(private val send: (Map<String, Any?>) -> Unit) {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val worker = Executors.newCachedThreadPool()
    private class Reply {
        val latch = CountDownLatch(1)
        var data: ByteArray? = null
    }
    private val pending = ConcurrentHashMap<Int, Reply>()
    private val sequence = AtomicInteger()
    private val generation = AtomicInteger()
    private val requestLock = Any()
    @Volatile private var session = ""
    @Volatile private var closed = false

    init {
        worker.execute {
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                worker.execute {
                    socket.use {
                        runCatching {
                            socket.soTimeout = 15000
                            val input = socket.getInputStream().bufferedReader()
                            val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                            while (!input.readLine().isNullOrEmpty()) { }
                            require(path.startsWith("/${generation.get()}/manifest?url=")) { "expired playback" }
                            val url = URLDecoder.decode(path.substringAfter("/manifest?url="), "UTF-8")
                            require(url.startsWith("mlhls://localhost/"))
                            val data = rewrite(url, fetch(url).toString(Charsets.UTF_8)).toByteArray()
                            val out = socket.getOutputStream()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(data)
                        }.onFailure { error ->
                            Logger.w("mlhls manifest failed: ${error.message}")
                            runCatching { socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
                        }
                    }
                }
            }
        }
    }

    fun play(url: String, sessionId: String): String {
        reset()
        session = sessionId
        return local(url)
    }

    private fun local(url: String) = "http://127.0.0.1:${server.localPort}/${generation.get()}/manifest?url=" + URLEncoder.encode(url, "UTF-8")

    private fun fetch(url: String): ByteArray = synchronized(requestLock) {
        val id = sequence.incrementAndGet()
        val result = Reply()
        pending[id] = result
        try {
            send(mapOf("sessionID" to 1, "type" to "unhandledURLRequest", "request" to mapOf(
                "FCUP_Response_ClientInfo" to 1, "FCUP_Response_ClientRef" to 40030004,
                "FCUP_Response_RequestID" to id, "FCUP_Response_URL" to url, "sessionID" to 1,
                "FCUP_Response_Headers" to mapOf("X-Playback-Session-Id" to session,
                    "User-Agent" to "AppleCoreMedia/1.0.0.11B554a (Apple TV; U; CPU OS 7_0_4 like Mac OS X; en_us")
            )))
            check(result.latch.await(15, TimeUnit.SECONDS)) { "sender resource timeout" }
            result.data ?: error("sender resource failed or cancelled")
        } finally { pending.remove(id) }
    }

    fun action(plist: Map<String, Any?>) {
        if (plist["type"] != "unhandledURLResponse") return
        val params = plist["params"] as? Map<*, *> ?: return
        val id = (params["FCUP_Response_RequestID"] as? Number)?.toInt() ?: return
        val future = pending[id] ?: return
        val status = (params["FCUP_Response_StatusCode"] as? Number)?.toInt() ?: 200
        val data = params["FCUP_Response_Data"] as? ByteArray
        // FCUP uses 0 for a successful sender-side fetch; some senders return HTTP 200.
        if ((status == 0 || status in 200..299) && data != null) future.data = data
        Logger.i("mlhls resource response id=$id status=$status bytes=${data?.size ?: 0}")
        future.latch.countDown()
    }

    internal fun rewrite(url: String, text: String): String {
        require(text.trimStart().startsWith("#EXTM3U"))
        var base: String? = null
        var prefix = ""
        var params = emptyList<String>()
        fun resolve(value: String): String {
            val absolute = URI(url).resolve(value).toString()
            return if (absolute.startsWith("mlhls:")) local(absolute) else absolute
        }
        return text.lineSequence().map { line ->
            when {
                line.startsWith("#YT-EXT-CONDENSED-URL:") || line.startsWith("#YT-EXT-CONDENSED-URI:") -> {
                    base = Regex("BASE-URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    prefix = Regex("PREFIX=\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: ""
                    params = Regex("PARAMS=\"([^\"]+)\"").find(line)?.groupValues?.get(1)?.split(',') ?: emptyList()
                    require(base != null && prefix.isNotEmpty() && params.isNotEmpty()) { "invalid condensed manifest" }
                    ""
                }
                line.isBlank() -> line
                !line.startsWith("#") -> if (base != null && line.startsWith(prefix)) {
                    val values = line.removePrefix(prefix).split('/', limit = params.size)
                    require(values.size == params.size)
                    base!!.trimEnd('/') + "/" + params.zip(values).joinToString("/") { (key, value) -> "$key/$value" }
                } else resolve(line)
                else -> Regex("URI=\"([^\"]+)\"").replace(line) { "URI=\"${resolve(it.groupValues[1])}\"" }
            }
        }.joinToString("\n") + "\n"
    }

    fun reset() { generation.incrementAndGet(); pending.values.forEach { it.latch.countDown() }; pending.clear() }
    fun close() { closed = true; reset(); server.close(); worker.shutdownNow() }
}
