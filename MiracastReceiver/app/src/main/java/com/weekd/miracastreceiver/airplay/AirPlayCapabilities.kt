package com.weekd.miracastreceiver.airplay

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.weekd.miracastreceiver.util.Logger
import java.util.Locale

/** One source of truth for the receiver's /info and mDNS video capabilities. */
object AirPlayCapabilities {
    private data class HevcDecoder(val name: String, val video: MediaCodecInfo.VideoCapabilities)
    private data class Selection(val decoder: HevcDecoder, val maxFps: Int)

    private val hardwareDecoders: List<HevcDecoder> by lazy {
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.mapNotNull { info ->
                if (info.isEncoder || info.name.endsWith(".secure", true) ||
                    !info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }) {
                    return@mapNotNull null
                }
                val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else
                    !isSoftwareDecoderName(info.name)
                if (!hardware) return@mapNotNull null
                runCatching {
                    val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                    if (caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) ||
                        caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback)) {
                        null
                    } else {
                        HevcDecoder(info.name, caps.videoCapabilities)
                    }
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
        Logger.i("AirPlay hardware HEVC decoders=${decoders.map { it.name }}")
        decoders
    }

    private fun select(width: Int, height: Int): Selection? = hardwareDecoders.mapNotNull { decoder ->
        runCatching {
            val (codedWidth, codedHeight) = alignedVideoSize(
                width, height, decoder.video.widthAlignment, decoder.video.heightAlignment
            )
            if (!decoder.video.areSizeAndRateSupported(codedWidth, codedHeight, 30.0)) null else
                Selection(decoder, if (decoder.video.areSizeAndRateSupported(codedWidth, codedHeight, 60.0)) 60 else 30)
        }.getOrNull()
    }.maxByOrNull { it.maxFps }

    val hardwareHevc4k: Boolean get() = select(3840, 2160) != null
    fun hevcDecoderName(width: Int, height: Int): String? =
        (select(width, height) ?: select(3840, 2160))?.decoder?.name
    fun maxFps(width: Int, height: Int): Int =
        if (hardwareHevc4k) select(width, height)?.maxFps ?: 30 else 60

    // ScreenMultiCodec (bit 42) enables the HEVC configuration packets macOS sends for 4K.
    val features: Long get() = 0x1E5A7FFFF7L or (if (hardwareHevc4k) 1L shl 42 else 0L)
    val featuresTxt: String get() = "0x${(features and 0xFFFFFFFFL).toString(16)},0x${(features ushr 32).toString(16)}"
}

/** Pre-29 codecs have no hardware flag; reject known software naming conventions. */
internal fun isSoftwareDecoderName(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    return lower.startsWith("omx.google.") || lower.startsWith("c2.android.") ||
        lower.startsWith("omx.ffmpeg.") || lower.contains("swvdec") || lower.contains("software") ||
        Regex("(^|[._-])sw([._-]|$)").containsMatchIn(lower)
}

/** Scale down only, retaining the panel's aspect ratio and smaller native modes. */
internal fun fitMirrorResolution(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
    require(width >= 2 && height >= 2 && maxWidth >= 2 && maxHeight >= 2)
    if (width <= maxWidth && height <= maxHeight) return width to height
    val (scaledWidth, scaledHeight) = if (width.toLong() * maxHeight >= height.toLong() * maxWidth) {
        maxWidth to (height.toLong() * maxWidth / width).toInt()
    } else {
        (width.toLong() * maxHeight / height).toInt() to maxHeight
    }
    // Chroma-subsampled output needs even dimensions; do not change native modes above.
    return maxOf(2, scaledWidth and -2) to maxOf(2, scaledHeight and -2)
}

/** Capability queries concern coded dimensions, which can be larger than the display crop. */
internal fun alignedVideoSize(width: Int, height: Int, widthAlignment: Int, heightAlignment: Int): Pair<Int, Int> {
    fun align(value: Int, alignment: Int): Int {
        require(value > 0 && alignment > 0)
        val aligned = ((value.toLong() + alignment - 1) / alignment) * alignment
        require(aligned <= Int.MAX_VALUE)
        return aligned.toInt()
    }
    return align(width, widthAlignment) to align(height, heightAlignment)
}
