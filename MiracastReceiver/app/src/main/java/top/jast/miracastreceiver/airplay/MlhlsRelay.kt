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
    companion object {
        private val manifestUrl = Regex("http://127\\.0\\.0\\.1:[0-9]+/[0-9]+/manifest\\?url=.*")
        private val playlistUri = Regex("URI=\"([^\"]+)\"")
        private const val MAX_PREFETCH_PLAYLISTS = 24
        private const val MAX_PREFETCH_CHARS = 4 * 1024 * 1024
        private const val PREFETCH_BUDGET_MS = 5000L

        fun isManifestUrl(url: String): Boolean = manifestUrl.matches(url)
    }

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val worker = Executors.newCachedThreadPool()
    private class Reply {
        val latch = CountDownLatch(1)
        var data: ByteArray? = null
    }
    private val pending = ConcurrentHashMap<Int, Reply>()
    private val prefetched = mutableMapOf<String, String>()
    private var prefetchedChars = 0
    private val sequence = AtomicInteger()
    private val generation = AtomicInteger()
    private val requestLock = Any()
    private var session = ""
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
                            val epoch = path.substringAfter('/').substringBefore('/').toInt()
                            require(path.startsWith("/$epoch/manifest?url=")) { "invalid manifest path" }
                            val url = URLDecoder.decode(path.substringAfter("/manifest?url="), "UTF-8")
                            require(url.startsWith("mlhls://localhost/"))
                            val data = loadManifest(url, epoch).toByteArray()
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

    fun play(url: String, sessionId: String): String = synchronized(requestLock) {
        reset()
        session = sessionId
        local(url, generation.get())
    }

    private fun local(url: String, epoch: Int) = "http://127.0.0.1:${server.localPort}/$epoch/manifest?url=" + URLEncoder.encode(url, "UTF-8")

    private fun loadManifest(url: String, epoch: Int): String {
        synchronized(requestLock) {
            check(epoch == generation.get()) { "expired playback" }
            prefetched[url]?.let { return it }
        }
        val text = fetch(url, epoch).toString(Charsets.UTF_8)
        val manifest = rewrite(url, text, epoch)
        if (text.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF:") }) {
            prefetchMediaPlaylists(url, text, epoch)
        }
        return manifest
    }

    // Fetch the media playlists in order before handing the master to the player.
    // Only cache finished playlists so live manifests still refresh on demand.
    private fun prefetchMediaPlaylists(masterUrl: String, text: String, epoch: Int) {
        val base = URI(masterUrl)
        val urls = text.lineSequence().map(String::trim).mapNotNull { line ->
            when {
                line.startsWith("#EXT-X-MEDIA:") -> playlistUri.find(line)?.groupValues?.get(1)
                line.isNotEmpty() && !line.startsWith('#') -> line
                else -> null
            }
        }.mapNotNull { runCatching { base.resolve(it).toString() }.getOrNull() }
            .filter { it.startsWith("mlhls://localhost/") }.distinct().take(MAX_PREFETCH_PLAYLISTS)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PREFETCH_BUDGET_MS)
        for (url in urls) {
            if (synchronized(requestLock) { url in prefetched }) continue
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0) break
            val media = runCatching { fetch(url, epoch, remainingMs).toString(Charsets.UTF_8) }
                .getOrElse { breakPrefetch(it); return }
            if (media.lineSequence().none { it.trim() == "#EXT-X-ENDLIST" }) continue
            val rewritten = runCatching { rewrite(url, media, epoch) }.getOrElse { breakPrefetch(it); return }
            synchronized(requestLock) {
                check(epoch == generation.get()) { "expired playback" }
                if (url !in prefetched && prefetched.size < MAX_PREFETCH_PLAYLISTS &&
                    rewritten.length <= MAX_PREFETCH_CHARS - prefetchedChars
                ) {
                    prefetched[url] = rewritten
                    prefetchedChars += rewritten.length
                }
            }
        }
    }

    private fun breakPrefetch(error: Throwable) {
        Logger.d("mlhls media prefetch ended: ${error.message}")
    }

    private fun fetch(url: String, epoch: Int, timeoutMs: Long = 15000): ByteArray {
        val id = sequence.incrementAndGet()
        val result = Reply()
        try {
            synchronized(requestLock) {
                check(epoch == generation.get()) { "expired playback" }
                pending[id] = result
                Logger.d("mlhls resource request id=$id")
                send(mapOf("sessionID" to 1, "type" to "unhandledURLRequest", "request" to mapOf(
                    "FCUP_Response_ClientInfo" to 1, "FCUP_Response_ClientRef" to 40030004,
                    "FCUP_Response_RequestID" to id, "FCUP_Response_URL" to url, "sessionID" to 1,
                    "FCUP_Response_Headers" to mapOf("X-Playback-Session-Id" to session,
                        "User-Agent" to "AppleCoreMedia/1.0.0.11B554a (Apple TV; U; CPU OS 7_0_4 like Mac OS X; en_us")
                )))
            }
            check(result.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "sender resource timeout" }
            check(epoch == generation.get()) { "expired playback" }
            return result.data ?: error("sender resource failed or cancelled")
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

    internal fun rewrite(url: String, text: String, epoch: Int = generation.get()): String {
        require(text.trimStart().startsWith("#EXTM3U"))
        var base: String? = null
        var prefix = ""
        var params = emptyList<String>()
        fun resolve(value: String): String {
            val absolute = URI(url).resolve(value).toString()
            return if (absolute.startsWith("mlhls:")) local(absolute, epoch) else absolute
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

    fun reset() = synchronized(requestLock) {
        generation.incrementAndGet()
        pending.values.forEach { it.latch.countDown() }
        pending.clear()
        prefetched.clear()
        prefetchedChars = 0
    }
    fun close() { closed = true; reset(); server.close(); worker.shutdownNow() }
}
