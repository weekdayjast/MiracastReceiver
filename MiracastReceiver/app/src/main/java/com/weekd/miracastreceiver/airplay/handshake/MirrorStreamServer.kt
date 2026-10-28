package com.weekd.miracastreceiver.airplay.handshake

import android.view.Surface
import com.weekd.miracastreceiver.airplay.StreamStats
import com.weekd.miracastreceiver.airplay.VideoDecoder
import com.weekd.miracastreceiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MirrorStreamServer — receives and decodes the AirPlay 2 mirroring video stream.
 *
 * macOS connects to [dataPort] and sends [128-byte header][payload] packets:
 *  - payload_size = little-endian int at header offset 0
 *  - payload_type = little-endian short at header offset 4, low byte
 *      • type 1: unencrypted avcC (SPS/PPS) → (re)configure the decoder
 *      • type 0: AES-CTR-encrypted H.264 (AVCC) → decrypt, convert to Annex-B, decode
 *
 * Architecture: a network thread reads + decrypts packets (keeping the AES-CTR keystream strictly
 * ordered) and pushes work onto a bounded queue; a separate decoder thread consumes it. This way
 * the socket is always drained fast — if the decoder can't keep up (this SoC is modest, and the
 * audio codec competes for resources), frames are dropped from the queue instead of stalling the
 * socket, which previously caused macOS to drop the whole session.
 *
 * Reference: RPiPlay lib/raop_rtp_mirror.c (raop_rtp_mirror_thread).
 */
class MirrorStreamServer(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    streamConnectionId: Long,
    private val surfaceProvider: () -> Surface?,
    private val width: Int = 1920,
    private val height: Int = 1080,
) {
    private sealed class Item
    private class Config(val sps: ByteArray, val pps: ByteArray) : Item()
    private class Frame(val annexB: ByteArray) : Item()

    private val cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
    private val serverSocket = ServerSocket(0)            // OS-assigned free port
    private val queue = ArrayBlockingQueue<Item>(QUEUE_CAPACITY)

    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    @Volatile private var decoder: VideoDecoder? = null   // owned by the decoder thread
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    // The Surface the current decoder was built against. The SurfaceView destroys its Surface when
    // the app backgrounds and creates a NEW one on return, so we watch for the identity changing
    // and rebuild the decoder — otherwise video stays black after foregrounding.
    @Volatile private var configuredSurface: Surface? = null
    private var framePtsUs = 0L
    private var framesIn = 0
    private var framesDropped = 0
    private var lastStatMs = 0L
    // Set by the reader thread when a frame is dropped under load; the decoder thread then skips
    // frames until the next keyframe (IDR) so it never decodes a reference-broken, corrupt stream.
    @Volatile private var awaitingKeyframe = false
    // 从最近一个关键帧（IDR）开始的所有帧。解码器不得不重建时（Surface 换了且不支持原地切换、
    // 切后台回来）立刻把这一组重新喂一遍就能出画面，不用干等下一个 IDR —— 镜像画面静止时
    // iPhone 可能很久才发一次关键帧，期间会一直黑屏。只在解码线程里读写。
    private val gopCache = ArrayList<ByteArray>()
    private var gopCacheBytes = 0
    private var gopCacheValid = false
    // 读线程丢帧时置位，解码线程据此作废缓存（缺帧的 GOP 重放出来是花的）
    @Volatile private var gopCacheBroken = false
    // Set while an AirPlay video-URL handoff owns the Surface (iOS plays a full-screen web/app video
    // during a mirror and POSTs /play). A Surface takes only ONE producer at a time, so the decoder
    // thread releases its MediaCodec (freeing the Surface for MediaPlayer) and drops frames until
    // [resumeDecoding]; SPS/PPS updates that arrive meanwhile are still cached.
    @Volatile private var suspended = false
    @Volatile private var suspendLatch: CountDownLatch? = null

    /** The OS-assigned TCP port macOS should connect to (returned in the SETUP response). */
    val dataPort: Int get() = serverSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) { runReader() }
        scope.launch(Dispatchers.IO) { runDecoder() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        queue.clear()
        Logger.i("MirrorStreamServer stopped")
    }

    /**
     * Releases the mirror decoder so the URL-video player can attach to the shared Surface. Blocks
     * until the decoder thread has let go (or [SUSPEND_TIMEOUT_MS]); returns true if it did.
     */
    fun suspendDecoding(): Boolean {
        if (!running) return true
        val latch = CountDownLatch(1)
        suspendLatch = latch
        suspended = true
        val released = latch.await(SUSPEND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        Logger.i("Mirror decoding suspended for URL video (surface released=$released)")
        return released
    }

    /** Ends [suspendDecoding]: the decoder is rebuilt from the cached SPS/PPS and resyncs at the next IDR. */
    fun resumeDecoding() {
        if (!suspended) return
        suspended = false
        Logger.i("Mirror decoding resumed after URL video")
    }

    val isSuspended: Boolean get() = suspended

    // ─── Network reader thread: read + decrypt (ordered), enqueue, never block on decode ──────
    private fun runReader() {
        try {
            Logger.i("MirrorStreamServer listening on data port $dataPort")
            val socket = serverSocket.accept().also { client = it }
            Logger.i("Mirror data connection from ${socket.inetAddress.hostAddress}")
            val input = socket.getInputStream()
            val header = ByteArray(128)
            while (running && !socket.isClosed) {
                if (!readFully(input, header, 128)) break
                val payloadSize = leInt(header, 0)
                val payloadType = leShort(header, 4) and 0xFF
                if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD) {
                    Logger.w("Mirror: bad payloadSize=$payloadSize type=$payloadType — stopping")
                    break
                }
                val payload = ByteArray(payloadSize)
                if (!readFully(input, payload, payloadSize)) break
                StreamStats.videoBytesTotal += payloadSize
                when (payloadType) {
                    0 -> {
                        // ALWAYS advance the AES-CTR keystream, in order, for every video payload —
                        // skipping any packet desyncs the keystream and corrupts all later frames.
                        val annexB = MirrorCrypto.avccToAnnexB(cipher.update(payload))
                        if (annexB.isNotEmpty()) enqueue(Frame(annexB))
                    }
                    1 -> parseConfig(payload)?.let { enqueue(it) }
                    else -> Logger.v("Mirror: ignoring payload type $payloadType ($payloadSize B)")
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror reader error", e)
        } finally {
            running = false
            Logger.i("Mirror data connection ended")
        }
    }

    /** Bounded enqueue — if the decoder is behind, drop the oldest item to keep latency bounded. */
    private fun enqueue(item: Item) {
        framesIn++
        StreamStats.videoFramesTotal++
        if (!queue.offer(item)) {
            queue.poll()
            queue.offer(item)
            framesDropped++
            awaitingKeyframe = true        // a frame was lost — resync the decoder at the next IDR
            gopCacheBroken = true
        }
        StreamStats.videoQueue = queue.size
        if (framesIn % 300 == 0) {
            val now = System.currentTimeMillis()
            if (lastStatMs != 0L) StreamStats.videoFps = (300_000L / (now - lastStatMs).coerceAtLeast(1)).toInt()
            lastStatMs = now
            StreamStats.videoDropPct = framesDropped * 100 / framesIn
            Logger.i("Video stats: in=$framesIn dropped=$framesDropped " +
                "(${StreamStats.videoDropPct}%) queue=${queue.size}/$QUEUE_CAPACITY ${StreamStats.videoFps}fps")
        }
    }

    private fun parseConfig(payload: ByteArray): Config? = try {
        val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        val sps = payload.copyOfRange(8, 8 + spsSize)
        val ppsLenOffset = 8 + spsSize + 1                   // skip the 1-byte PPS count
        val ppsSize = ((payload[ppsLenOffset].toInt() and 0xFF) shl 8) or
            (payload[ppsLenOffset + 1].toInt() and 0xFF)
        Config(sps, payload.copyOfRange(ppsLenOffset + 2, ppsLenOffset + 2 + ppsSize))
    } catch (e: Exception) {
        Logger.e("Mirror: failed to parse SPS/PPS", e); null
    }

    // ─── Decoder thread: consume the queue; the only thread that touches the decoder ──────────
    private fun runDecoder() {
        try {
            while (running) {
                if (suspended) releaseForSuspend()
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (suspended) {
                    // URL video owns the Surface: keep the latest config for the resume, drop frames.
                    if (item is Config) { lastSps = item.sps; lastPps = item.pps }
                    continue
                }
                when (item) {
                    is Config -> configureDecoder(item.sps, item.pps)
                    is Frame -> decodeFrame(item.annexB)
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror decoder thread error", e)
        } finally {
            decoder?.release()
            decoder = null
        }
    }

    /** Decoder thread: drop the MediaCodec (disconnecting it from the Surface) and ack [suspendDecoding]. */
    private fun releaseForSuspend() {
        decoder?.let { it.release(); decoder = null }
        configuredSurface = null        // forces a rebuild against the live Surface on resume
        clearGopCache()                 // 暂停期间的帧都丢了，缓存不再连续
        suspendLatch?.let { it.countDown(); suspendLatch = null }
    }

    private fun configureDecoder(sps: ByteArray, pps: ByteArray) {
        // New SPS/PPS (or first config) — cache it and (re)build against the current surface.
        val d = decoder
        val surface = awaitSurface()
        if (d != null && d.isHealthy && sps.contentEquals(lastSps) && pps.contentEquals(lastPps) &&
            surface === configuredSurface) return
        lastSps = sps
        lastPps = pps
        clearGopCache()                    // 新配置（可能换了分辨率）之后旧帧不能再用
        rebuildDecoder(surface)
    }

    /**
     * (Re)creates the decoder for [surface] from the cached SPS/PPS. Safe to call on a surface
     * change (app background→foreground): releases the old decoder and resyncs at the next keyframe.
     * If the surface or config isn't available yet, leaves the decoder null and retries on a later
     * frame (frames are dropped until then).
     */
    private fun rebuildDecoder(surface: Surface?) {
        decoder?.release()
        decoder = null
        configuredSurface = surface
        val sps = lastSps ?: return
        val pps = lastPps ?: return
        if (surface == null) return                            // backgrounded — wait for the surface to return
        if (!surface.isValid) return
        val sc = MirrorCrypto.START_CODE
        decoder = VideoDecoder(surface).also { it.initialize(sc + sps, sc + pps, width, height) }
        awaitingKeyframe = true                                // a fresh decoder must start at an IDR
        StreamStats.videoRes = "${width}x${height}"
        Logger.i("Mirror decoder (re)built for surface (sps=${sps.size}B pps=${pps.size}B)")
    }

    private fun clearGopCache() {
        gopCache.clear()
        gopCacheBytes = 0
        gopCacheValid = false
    }

    private fun cacheFrame(annexB: ByteArray) {
        if (gopCacheBroken) {
            gopCacheBroken = false
            clearGopCache()
        }
        if (isKeyframe(annexB)) {
            clearGopCache()
            gopCacheValid = true
        }
        if (!gopCacheValid) return
        gopCache.add(annexB)
        gopCacheBytes += annexB.size
        // GOP 太长就放弃缓存（重放太久反而慢），退回等下一个关键帧
        if (gopCache.size > GOP_CACHE_MAX_FRAMES || gopCacheBytes > GOP_CACHE_MAX_BYTES) clearGopCache()
    }

    private fun decodeFrame(annexB: ByteArray) {
        cacheFrame(annexB)
        // Re-attach to the live Surface if it changed (the app was backgrounded and returned, so the
        // SurfaceView made a new Surface). Without this, video stays black after foregrounding.
        val liveSurface = surfaceProvider()
        if (liveSurface !== configuredSurface) {
            val current = decoder
            if (current != null && current.isHealthy && liveSurface != null && liveSurface.isValid &&
                current.switchOutputSurface(liveSurface)) {
                // 手动旋转等场景：直接换输出 Surface，不用等关键帧
                Logger.i("Mirror: surface changed — switched decoder output in place")
                configuredSurface = liveSurface
            } else {
                Logger.i("Mirror: surface ${if (liveSurface == null) "lost" else "changed"} — re-attaching decoder")
                rebuildDecoder(liveSurface)
                if (replayGopCache()) return            // 缓存里已包含当前帧
            }
        }
        val d = decoder ?: return                              // need surface + SPS/PPS first
        if (!d.isHealthy) {                                    // error state — drop, await next config
            Logger.w("Mirror: decoder unhealthy — dropping, awaiting new SPS/PPS")
            d.release(); decoder = null; configuredSurface = null; lastSps = null; lastPps = null
            return
        }
        if (awaitingKeyframe) {
            // After a dropped frame the stream is reference-broken; skip until the next IDR so we
            // don't feed the decoder predicted frames with missing references (which smear/blocky).
            if (!isKeyframe(annexB)) return
            awaitingKeyframe = false
            Logger.i("Mirror: resynced on keyframe after a dropped frame")
        }
        if (framePtsUs == 0L) Logger.i("Mirror: first video frame fed to decoder (${annexB.size}B)")
        d.decodeNalUnit(annexB, framePtsUs)
        framePtsUs += FRAME_INTERVAL_US
    }

    /** 新建的解码器立刻重放缓存的 GOP（含当前帧），马上恢复画面。缓存不可用时返回 false。 */
    private fun replayGopCache(): Boolean {
        val d = decoder ?: return false
        if (!gopCacheValid || gopCache.isEmpty()) return false
        for (frame in gopCache) {
            d.decodeNalUnit(frame, framePtsUs)
            framePtsUs += FRAME_INTERVAL_US
        }
        awaitingKeyframe = false
        Logger.i("Mirror: replayed ${gopCache.size} cached frames (${gopCacheBytes / 1024}KB) — no wait for next IDR")
        return true
    }

    /** True if the Annex-B frame contains an IDR NAL unit (type 5) — a decodable resync point. */
    private fun isKeyframe(annexB: ByteArray): Boolean {
        var i = 0
        while (i + 3 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                if ((annexB[i + 3].toInt() and 0x1F) == 5) return true   // IDR slice
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    /** The streaming Surface appears shortly after CONNECTED is emitted; poll briefly. */
    private fun awaitSurface(): Surface? {
        repeat(SURFACE_WAIT_TRIES) {
            if (!running) return null
            surfaceProvider()?.let { return it }
            try { Thread.sleep(SURFACE_WAIT_MS) } catch (_: InterruptedException) { return null }
        }
        return surfaceProvider()
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n == -1) return false
            read += n
        }
        return true
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    companion object {
        private const val MAX_PAYLOAD = 8 * 1024 * 1024        // 8 MB sanity cap per frame
        private const val FRAME_INTERVAL_US = 1_000_000L / 60  // monotonic PTS hint (~60fps)
        private const val QUEUE_CAPACITY = 90                  // ~1.5s @60fps before dropping
        private const val GOP_CACHE_MAX_FRAMES = 600           // ~10s @60fps
        private const val GOP_CACHE_MAX_BYTES = 24 * 1024 * 1024
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L
        private const val SUSPEND_TIMEOUT_MS = 2000L
    }
}

