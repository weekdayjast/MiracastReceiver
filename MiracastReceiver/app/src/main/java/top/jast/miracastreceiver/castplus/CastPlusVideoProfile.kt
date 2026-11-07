package top.jast.miracastreceiver.castplus

import android.content.Context
import android.media.MediaCodecList
import top.jast.miracastreceiver.util.nativeDisplayResolution

internal data class CastPlusVideoProfile(val screenWidth: Int, val screenHeight: Int,
    val videoWidth: Int, val videoHeight: Int, val bitrate: Int)

/** Native panel dimensions remain stable when the activity or sender rotates. */
internal fun castPlusDisplaySize(context: Context): Pair<Int, Int> {
    return nativeDisplayResolution(context)
}

internal fun chooseCastPlusVideoProfile(screenWidth: Int, screenHeight: Int, rotation: Int = 0,
    supports: (Int, Int) -> Boolean): CastPlusVideoProfile {
    require(screenWidth >= 2 && screenHeight >= 2)
    val longEdge = maxOf(screenWidth, screenHeight)
    val shortEdge = minOf(screenWidth, screenHeight)
    val portrait = rotation % 180 != 0
    val displayW = if (portrait) shortEdge else longEdge
    val displayH = if (portrait) longEdge else shortEdge
    fun scaled(edge: Int): Pair<Int, Int> = if (portrait) {
        maxOf(2, (shortEdge.toLong() * edge / longEdge).toInt() and -2) to maxOf(2, edge and -2)
    } else {
        maxOf(2, edge and -2) to maxOf(2, (shortEdge.toLong() * edge / longEdge).toInt() and -2)
    }
    val candidates = (listOf(longEdge) + listOf(3840, 2560, 2160, 1920, 1600, 1280, 1080, 960, 720, 640)
        .filter { it < longEdge }).map(::scaled).distinct()
    val (videoW, videoH) = candidates.firstOrNull { (w, h) -> supports(w, h) }
        ?: scaled(minOf(longEdge, 720))
    val pixels = videoW.toLong() * videoH
    val bitrate = when {
        // Huawei SDK rejects capability bitrates above 20 Mbps, including on 4K panels.
        pixels > 2560L * 1440 -> 20_000_000
        pixels > 1920L * 1080 -> 16_000_000
        pixels > 1280L * 720 -> 10_000_000
        else -> 5_242_880
    }
    return CastPlusVideoProfile(displayW, displayH, videoW, videoH, bitrate)
}

internal fun castPlusVideoProfile(screenWidth: Int, screenHeight: Int, rotation: Int): CastPlusVideoProfile {
    val decoders = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.mapNotNull { codec ->
            runCatching {
                if (codec.supportedTypes.none { it.equals("video/avc", true) }) return@runCatching null
                if (codec.name.startsWith("OMX.google.") || codec.name.startsWith("c2.android.")) return@runCatching null
                codec.getCapabilitiesForType("video/avc").videoCapabilities
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
    return chooseCastPlusVideoProfile(screenWidth, screenHeight, rotation) { width, height ->
        decoders.any { runCatching { it.areSizeAndRateSupported(width, height, 30.0) }.getOrDefault(false) }
    }
}
