package com.weekd.miracastreceiver.ui

import android.os.SystemClock

/**
 * 视频流信息采集：把各来源写入的「单调累加计数器」换算成瞬时速率。
 *
 * AirPlay 镜像（StreamStats）和 Miracast（RtpReceiver）都只在热路径上累加字节数/帧数，
 * 由 UI 每秒采样一次做差分，这样采集端零成本、显示端也不用额外的时间窗口逻辑。
 */
class StreamInfoTracker {

    /** 一次采样的结果。首次采样（没有基准点）时全部为 0。 */
    data class Sample(val bitrateBps: Long, val bytesPerSec: Long, val fps: Int)

    private var lastSampleMs = 0L
    private var lastBytes = 0L
    private var lastFrames = 0L

    /** 切换播放源时清零，避免把上一路的累计值算进第一次差分。 */
    fun reset() {
        lastSampleMs = 0L
        lastBytes = 0L
        lastFrames = 0L
    }

    /**
     * 用累计字节数与累计帧数采样一次。
     * [totalBytes] 或 [totalFrames] 变小（计数器被重置）时同样从头开始。
     */
    fun sample(totalBytes: Long, totalFrames: Long): Sample {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = now - lastSampleMs
        val isFirst = lastSampleMs == 0L
        val restarted = totalBytes < lastBytes || totalFrames < lastFrames

        val sample = if (isFirst || restarted || elapsedMs <= 0) {
            Sample(0L, 0L, 0)
        } else {
            val bytes = totalBytes - lastBytes
            val frames = totalFrames - lastFrames
            Sample(
                bitrateBps = bytes * 8 * 1000 / elapsedMs,
                bytesPerSec = bytes * 1000 / elapsedMs,
                fps = (frames * 1000 / elapsedMs).toInt()
            )
        }

        lastSampleMs = now
        lastBytes = totalBytes
        lastFrames = totalFrames
        return sample
    }

    companion object {
        /** 码率：自动在 kbps / Mbps 之间切换。 */
        fun formatBitrate(bps: Long): String = when {
            bps <= 0 -> "—"
            bps >= 1_000_000 -> String.format("%.1f Mbps", bps / 1_000_000.0)
            else -> "${bps / 1000} kbps"
        }

        /** 网速：自动在 KB/s / MB/s 之间切换。 */
        fun formatSpeed(bytesPerSec: Long): String = when {
            bytesPerSec <= 0 -> "—"
            bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
            else -> "${bytesPerSec / 1024} KB/s"
        }

        /** 分辨率，任一边为 0 时显示占位符。 */
        fun formatResolution(width: Int, height: Int): String =
            if (width > 0 && height > 0) "${width}x$height" else "—"

        fun formatFps(fps: Float): String =
            if (fps > 0f) String.format("%.0f fps", fps) else "—"

        /** MIME 类型转成常见叫法，未知类型就去掉 "video/" 前缀原样显示。 */
        fun formatCodec(mimeType: String?): String = when (mimeType?.lowercase()) {
            null, "" -> "—"
            "video/avc" -> "H.264 (AVC)"
            "video/hevc" -> "H.265 (HEVC)"
            "video/av01" -> "AV1"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/x-vnd.on2.vp8" -> "VP8"
            "video/mpeg2" -> "MPEG-2"
            "video/mp4v-es" -> "MPEG-4"
            "video/3gpp" -> "H.263"
            "video/dolby-vision" -> "Dolby Vision"
            else -> mimeType.removePrefix("video/").uppercase()
        }

        /**
         * 标签在等宽字体下占的列数：中日韩字符按 2 列（一个汉字正好占两个字符位），
         * 泰文等语言里叠在前一个字符上的附加符号不占列。
         */
        fun labelWidth(label: String): Int = label.sumOf { c ->
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> 0
                c.code >= 0x2E80 -> 2
                else -> 1
            }.toInt()
        }

        /** 把标签补齐到固定列宽，让等宽字体下各行数值对齐。 */
        fun padLabel(label: String, columns: Int = 12): String =
            label + " ".repeat((columns - labelWidth(label)).coerceAtLeast(1))
    }
}
