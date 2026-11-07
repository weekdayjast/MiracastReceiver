package top.jast.miracastreceiver.miracast

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import top.jast.miracastreceiver.util.AppSettings
import top.jast.miracastreceiver.util.nativeDisplayResolution
import java.util.Locale

data class WfdCustomVideoMode(val width: Int, val height: Int, val fps: Int, val levelBit: Int) {
    fun customFormat(): String = "%04X %04X %04X".format(Locale.ROOT, width, height, fps)
    // MS-WFDPE 2.7.1.1: 16-bit native/profile/level, 40-bit CEA/VESA masks.
    fun extendedFormats(): String =
        "0040 00 0002 %04X 00000001C0 0000000000 00000000 00 0000 0000 00 none none".format(Locale.ROOT, levelBit)
}

/** Pick the smallest H.264 level sufficient for the exact coded macroblock size and rate. */
internal fun wfdAvcLevel(width: Int, height: Int, fps: Int): Pair<Int, Int>? {
    if (width !in 2..65535 || height !in 2..65535 || fps !in 1..60) return null
    val blocks = ((width + 15L) / 16) * ((height + 15L) / 16)
    val levels = listOf(
        Triple(8704L, 522240L, 0x10 to CodecProfileLevel.AVCLevel42),
        Triple(22080L, 589824L, 0x20 to CodecProfileLevel.AVCLevel5),
        Triple(36864L, 983040L, 0x40 to CodecProfileLevel.AVCLevel51),
        Triple(36864L, 2073600L, 0x80 to CodecProfileLevel.AVCLevel52)
    )
    return levels.firstOrNull { blocks <= it.first && blocks * fps <= it.second }?.third
}

internal fun chooseWfdCustomVideoMode(width: Int, height: Int, portrait: Boolean, extended: Boolean,
                                      supports: (Int, Int, Int, Int) -> Boolean): WfdCustomVideoMode? {
    if (width < 2 || height < 2) return null
    val longEdge = maxOf(width, height)
    val shortEdge = minOf(width, height)
    val edges = (listOf(longEdge) + listOf(3840, 2560, 2160, 1920, 1600, 1280, 1080, 960, 720)
        .filter { it < longEdge }).distinct()
    for (edge in edges) {
        val short = maxOf(2, (shortEdge.toLong() * edge / longEdge).toInt() and -2)
        val w = if (portrait) short else edge and -2
        val h = if (portrait) edge and -2 else short
        for (fps in listOf(60, 30)) {
            val (bit, codecLevel) = wfdAvcLevel(w, h, fps) ?: continue
            if ((!extended && bit > 0x10) || !supports(w, h, fps, codecLevel)) continue
            return WfdCustomVideoMode(w, h, fps, bit)
        }
    }
    return null
}

internal fun wfdCustomVideoMode(context: Context, extended: Boolean): WfdCustomVideoMode? {
    val decoders = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.mapNotNull { codec ->
            runCatching {
                if (codec.name.endsWith(".secure", true) || codec.name.startsWith("OMX.google.") || codec.name.startsWith("c2.android.") ||
                    codec.supportedTypes.none { it.equals("video/avc", true) }) null
                else codec.getCapabilitiesForType("video/avc")
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
    val (width, height) = nativeDisplayResolution(context)
    return chooseWfdCustomVideoMode(width, height, AppSettings.isMiracastPortraitDisplay(context), extended) { w, h, fps, level ->
        decoders.any { caps -> runCatching {
            caps.profileLevels.any { it.profile == CodecProfileLevel.AVCProfileHigh && it.level >= level } &&
                (caps.videoCapabilities?.areSizeAndRateSupported(w, h, fps.toDouble()) == true)
        }.getOrDefault(false) }
    }
}

/** Only negotiate extensions the peer explicitly requested; keep the legacy CEA fallback. */
internal fun wfdVideoCapabilityLines(requested: Set<String>, mode: WfdCustomVideoMode?): List<String> {
    val lines = mutableListOf("wfd_video_formats: 00 00 02 10 000001C0 00000000 00000000 00 0000 0000 00 none none")
    if ("wfdx_video_formats" in requested && mode != null) lines += "wfdx_video_formats: ${mode.extendedFormats()}"
    if ("microsoft_custom_video_formats" in requested) {
        val usable = mode?.takeIf { "wfdx_video_formats" in requested || it.levelBit <= 0x10 }
        lines += "microsoft_custom_video_formats: ${usable?.customFormat() ?: "none"}"
    }
    return lines
}

internal fun matchesWfdCustomMode(value: String, mode: WfdCustomVideoMode): Boolean {
    val fields = value.trim().split(Regex("\\s+"))
    if (fields.size < 3 || fields.take(3).any { !it.matches(Regex("[0-9a-fA-F]{4}")) }) return false
    // MS-WFDPE permits trailing fields, but a source selects exactly one resolution.
    return fields[0].toInt(16) == mode.width && fields[1].toInt(16) == mode.height && fields[2].toInt(16) == mode.fps
}
