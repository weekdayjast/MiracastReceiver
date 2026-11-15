package top.jast.miracastreceiver.miracast

import android.view.Surface
import top.jast.miracastreceiver.airplay.VideoDecoder
import timber.log.Timber

/**
 * Miracast 视频送显：把 [TsDemuxer] 解出的 H.264 访问单元直接喂给 MediaCodec。
 *
 * 刻意不经过 ExoPlayer —— 播放器的缓冲和时钟同步对第二屏幕这种实时用途是负担，
 * 实测会引入 10 秒以上延迟。这里沿用 AirPlay 镜像同样的做法（见
 * [top.jast.miracastreceiver.airplay.handshake.MirrorStreamServer]）：收到即解码、解完即送显。
 *
 * H.264 的 SPS/PPS 在 TS 码流里是带内传输的（每个 IDR 前会重复），所以这里先攒够
 * SPS+PPS 才能初始化解码器，在那之前的帧只能丢弃 —— 通常一两百毫秒内就会等到。
 *
 * @param surfaceProvider 取当前可用的 Surface；应用切后台时会返回 null
 */
class MiracastVideoRenderer(
    private val surfaceProvider: () -> Surface?,
    private val onRecoveryNeeded: () -> Unit = {}
) {

    private data class AccessUnit(val data: ByteArray, val ptsUs: Long)

    private companion object {
        const val MAX_PENDING_UNITS = 120
        const val MAX_PENDING_BYTES = 8 * 1024 * 1024
    }

    private var decoder: VideoDecoder? = null
    private var configuredSurface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var awaitingKeyframe = true
    private var droppedBeforeConfig = 0
    private val pendingUnits = ArrayDeque<AccessUnit>()
    private var pendingBytes = 0
    private var inputStalls = 0L
    @Volatile private var released = false
    private var sourceFrames = 0L
    private var statsStartMs = 0L
    private var lastStatsMs = 0L
    private var firstPtsUs = 0L

    /** Stop retries immediately; MediaCodec itself is released by the demux thread. */
    fun requestStop() { released = true }

    /**
     * 数据有丢失时调用：残缺的帧会让解码器输出花屏，而且错误会沿着帧间预测一直传播下去，
     * 所以必须丢弃后续帧直到下一个 IDR 才重新开始解码。
     */
    fun onDiscontinuity() {
        clearPendingUnits()
        if (!awaitingKeyframe) {
            awaitingKeyframe = true
            Timber.w("Miracast: data loss detected, waiting for next keyframe")
            onRecoveryNeeded()
        }
    }

    /** [TsDemuxer] 的回调入口。 */
    fun onAccessUnit(unit: ByteArray, ptsUs: Long) {
        if (released) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (statsStartMs == 0L) { statsStartMs = now; firstPtsUs = ptsUs }
        sourceFrames++
        if (now - lastStatsMs >= 2_000) {
            lastStatsMs = now
            Timber.i("Miracast video stats: units=%d, queued=%d, rendered=%d, sourceMs=%d, wallMs=%d, inputStalls=%d, waitKey=%s",
                sourceFrames, decoder?.framesQueued ?: 0, decoder?.framesReleased ?: 0,
                (ptsUs - firstPtsUs) / 1000, now - statsStartMs, inputStalls, awaitingKeyframe)
        }
        captureParameterSets(unit)

        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            releaseDecoder()
            bufferUntilSurfaceReady(unit, ptsUs)
            return
        }

        // Surface 变了（手动旋转切换渲染视图）先尝试原地换输出，不用等关键帧
        val current = decoder
        if (current != null && surface !== configuredSurface && current.switchOutputSurface(surface)) {
            Timber.i("Miracast: surface changed — switched decoder output in place")
            configuredSurface = surface
        }

        // Surface 变了（应用切后台再回前台会换新的）就重建解码器，否则画面会一直黑
        if (decoder == null || surface !== configuredSurface) {
            if (!rebuildDecoder(surface)) {
                droppedBeforeConfig++
                if (droppedBeforeConfig % 60 == 0) {
                    Timber.d("Miracast: waiting for SPS/PPS, dropped $droppedBeforeConfig units")
                }
                return
            }
        }

        // Surface 就绪后，从暂存的 IDR 开始，按原顺序送入依赖它的帧。
        if (pendingUnits.isNotEmpty()) {
            if (!containsIdr(unit)) {
                for (pending in pendingUnits) decodeAccessUnit(pending.data, pending.ptsUs)
            }
            clearPendingUnits()
        }
        decodeAccessUnit(unit, ptsUs)
    }

    private fun decodeAccessUnit(unit: ByteArray, ptsUs: Long) {
        // 新解码器必须从 IDR 开始，否则解出来是花的
        if (awaitingKeyframe) {
            if (!containsIdr(unit)) return
            awaitingKeyframe = false
            Timber.i("Miracast: keyframe found, decoding started")
        }

        val current = decoder ?: return
        val started = System.nanoTime()
        var stalled = false
        // A brief codec stall must not silently break the H.264 reference chain.
        // Keep the same AU for up to 500ms; RTP reception continues on its own thread.
        while (!released && current.isHealthy) {
            if (current.tryQueueNalUnit(unit, ptsUs)) {
                if (stalled) Timber.i("Miracast: retained AU accepted after %dms (stalls=%d)",
                    (System.nanoTime() - started) / 1_000_000, inputStalls)
                return
            }
            if (!stalled) { inputStalls++; stalled = true }
            if (System.nanoTime() - started >= 500_000_000L) break
        }
        if (!released) {
            Timber.w("Miracast: codec input stalled; waiting for a clean keyframe (stalls=%d)", inputStalls)
            onDiscontinuity()
            releaseDecoder()
        }
    }

    private fun bufferUntilSurfaceReady(unit: ByteArray, ptsUs: Long) {
        // 投屏端可能在播放页创建之前就发出首个 IDR，之后很久才再次发送。
        if (containsIdr(unit)) {
            clearPendingUnits()
        } else if (pendingUnits.isEmpty()) {
            return
        }
        if (pendingUnits.size >= MAX_PENDING_UNITS || unit.size > MAX_PENDING_BYTES - pendingBytes) {
            clearPendingUnits()
            return
        }
        pendingUnits.addLast(AccessUnit(unit, ptsUs))
        pendingBytes += unit.size
    }

    private fun clearPendingUnits() {
        pendingUnits.clear()
        pendingBytes = 0
    }

    private fun rebuildDecoder(surface: Surface): Boolean {
        val spsBytes = sps ?: return false
        val ppsBytes = pps ?: return false

        releaseDecoder()
        val (width, height) = VideoDecoder.parseSpsResolution(spsBytes) ?: (1920 to 1080)
        decoder = VideoDecoder(surface).also { it.initialize(spsBytes, ppsBytes, width, height) }
        configuredSurface = surface
        awaitingKeyframe = true
        Timber.i("Miracast decoder initialized: ${width}x$height")
        return true
    }

    /** 从访问单元里提取 SPS(type 7) / PPS(type 8)，带起始码保存以便直接作为 csd 使用。 */
    private fun captureParameterSets(unit: ByteArray) {
        if (sps != null && pps != null) return
        forEachNal(unit) { start, end ->
            val nalType = unit[start].toInt() and 0x1F
            if (nalType == 7 || nalType == 8) {
                val withStartCode = ByteArray(4 + (end - start))
                withStartCode[3] = 1
                System.arraycopy(unit, start, withStartCode, 4, end - start)
                if (nalType == 7 && sps == null) {
                    sps = withStartCode
                    Timber.i("Miracast: SPS captured (${end - start} bytes)")
                } else if (nalType == 8 && pps == null) {
                    pps = withStartCode
                    Timber.i("Miracast: PPS captured (${end - start} bytes)")
                }
            }
        }
    }

    private fun containsIdr(unit: ByteArray): Boolean {
        var found = false
        forEachNal(unit) { start, _ ->
            if ((unit[start].toInt() and 0x1F) == 5) found = true
        }
        return found
    }

    /** 遍历 Annex B 码流里的每个 NAL（回调收到的是去掉起始码后的区间）。 */
    private inline fun forEachNal(data: ByteArray, action: (start: Int, end: Int) -> Unit) {
        var i = 0
        var nalStart = -1
        while (i + 3 <= data.size) {
            val isStartCode3 = data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 1
            val isStartCode4 = i + 4 <= data.size && data[i].toInt() == 0 &&
                data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1
            if (isStartCode3 || isStartCode4) {
                if (nalStart >= 0) action(nalStart, i)
                i += if (isStartCode4) 4 else 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart in 0 until data.size) action(nalStart, data.size)
    }

    private fun releaseDecoder() {
        decoder?.release()
        decoder = null
        configuredSurface = null
    }

    fun release() {
        released = true
        releaseDecoder()
        clearPendingUnits()
        sps = null
        pps = null
        awaitingKeyframe = true
    }
}
