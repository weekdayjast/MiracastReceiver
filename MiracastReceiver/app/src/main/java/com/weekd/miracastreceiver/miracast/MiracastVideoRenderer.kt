package com.weekd.miracastreceiver.miracast

import android.view.Surface
import com.weekd.miracastreceiver.airplay.VideoDecoder
import timber.log.Timber

/**
 * Miracast 视频送显：把 [TsDemuxer] 解出的 H.264 访问单元直接喂给 MediaCodec。
 *
 * 刻意不经过 ExoPlayer —— 播放器的缓冲和时钟同步对第二屏幕这种实时用途是负担，
 * 实测会引入 10 秒以上延迟。这里沿用 AirPlay 镜像同样的做法（见
 * [com.weekd.miracastreceiver.airplay.handshake.MirrorStreamServer]）：收到即解码、解完即送显。
 *
 * H.264 的 SPS/PPS 在 TS 码流里是带内传输的（每个 IDR 前会重复），所以这里先攒够
 * SPS+PPS 才能初始化解码器，在那之前的帧只能丢弃 —— 通常一两百毫秒内就会等到。
 *
 * @param surfaceProvider 取当前可用的 Surface；应用切后台时会返回 null
 */
class MiracastVideoRenderer(private val surfaceProvider: () -> Surface?) {

    private var decoder: VideoDecoder? = null
    private var configuredSurface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var awaitingKeyframe = true
    private var droppedBeforeConfig = 0

    /**
     * 数据有丢失时调用：残缺的帧会让解码器输出花屏，而且错误会沿着帧间预测一直传播下去，
     * 所以必须丢弃后续帧直到下一个 IDR 才重新开始解码。
     */
    fun onDiscontinuity() {
        if (!awaitingKeyframe) {
            awaitingKeyframe = true
            Timber.w("Miracast: data loss detected, waiting for next keyframe")
        }
    }

    /** [TsDemuxer] 的回调入口。 */
    fun onAccessUnit(unit: ByteArray, ptsUs: Long) {
        captureParameterSets(unit)

        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            releaseDecoder()
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

        // 新解码器必须从 IDR 开始，否则解出来是花的
        if (awaitingKeyframe) {
            if (!containsIdr(unit)) return
            awaitingKeyframe = false
            Timber.i("Miracast: keyframe found, decoding started")
        }

        decoder?.decodeNalUnit(unit, ptsUs)
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
        releaseDecoder()
        sps = null
        pps = null
        awaitingKeyframe = true
    }
}
