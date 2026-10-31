package com.weekd.miracastreceiver.airplay

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.weekd.miracastreceiver.util.Logger

/** One source of truth for the receiver's /info and mDNS video capabilities. */
object AirPlayCapabilities {
    private data class HevcDecoder(val name: String, val maxFps: Int)

    private val hevcDecoder: HevcDecoder? by lazy {
        val selected = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.mapNotNull { info ->
                if (info.isEncoder || info.name.endsWith(".secure", true) ||
                    !info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }) {
                    return@mapNotNull null
                }
                val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else
                    !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                if (!hardware) return@mapNotNull null
                runCatching {
                    val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                    val video = caps.videoCapabilities
                    if (caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) ||
                        caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback) ||
                        !video.areSizeAndRateSupported(3840, 2160, 30.0)) {
                        null
                    } else {
                        HevcDecoder(info.name, if (video.areSizeAndRateSupported(3840, 2160, 60.0)) 60 else 30)
                    }
                }.getOrNull()
            }.maxByOrNull { it.maxFps }
        }.getOrNull()
        Logger.i("AirPlay 4K HEVC decoder=${selected?.name} maxFPS=${selected?.maxFps}")
        selected
    }

    val hardwareHevc4k: Boolean get() = hevcDecoder != null
    val hevcDecoderName: String? get() = hevcDecoder?.name
    val maxFps: Int get() = hevcDecoder?.maxFps ?: 60
    // ScreenMultiCodec (bit 42) enables the HEVC configuration packets macOS sends for 4K.
    val features: Long get() = 0x1E5A7FFFF7L or (if (hardwareHevc4k) 1L shl 42 else 0L)
    val featuresTxt: String get() = "0x${(features and 0xFFFFFFFFL).toString(16)},0x${(features ushr 32).toString(16)}"
}
