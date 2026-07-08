package com.weekd.miracastreceiver.utils

import android.media.MediaCodecList
import android.media.MediaFormat
import timber.log.Timber

/**
 * 编解码工具类
 */
object CodecUtils {

    const val MIME_VIDEO_H264 = MediaFormat.MIMETYPE_VIDEO_AVC
    const val MIME_VIDEO_H265 = MediaFormat.MIMETYPE_VIDEO_HEVC
    const val MIME_AUDIO_AAC = MediaFormat.MIMETYPE_AUDIO_AAC

    /**
     * 检查是否支持指定视频解码器
     */
    fun isVideoDecoderSupported(mimeType: String): Boolean {
        return try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val codecs = codecList.codecInfos

            val supported = codecs.any { codecInfo ->
                !codecInfo.isEncoder && codecInfo.supportedTypes.any { type ->
                    type.equals(mimeType, ignoreCase = true)
                }
            }

            Timber.d("Video decoder support for $mimeType: $supported")
            supported
        } catch (e: Exception) {
            Timber.e(e, "Error checking video decoder support")
            false
        }
    }

    /**
     * 获取支持的解码器列表
     */
    fun getSupportedVideoDecoders(): List<String> {
        val decoders = mutableListOf<String>()

        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            val codecs = codecList.codecInfos

            codecs.forEach { codecInfo ->
                if (!codecInfo.isEncoder) {
                    codecInfo.supportedTypes.forEach { type ->
                        if (type.startsWith("video/")) {
                            decoders.add("${codecInfo.name}: $type")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting supported decoders")
        }

        return decoders
    }

    /**
     * 获取推荐的视频配置
     */
    fun getRecommendedVideoConfig(): VideoConfig {
        val supportsH265 = isVideoDecoderSupported(MIME_VIDEO_H265)
        val supportsH264 = isVideoDecoderSupported(MIME_VIDEO_H264)

        return VideoConfig(
            mimeType = when {
                supportsH265 -> MIME_VIDEO_H265
                supportsH264 -> MIME_VIDEO_H264
                else -> MIME_VIDEO_H264
            },
            width = 1920,
            height = 1080,
            frameRate = 30,
            bitRate = 5_000_000
        )
    }
}

data class VideoConfig(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val bitRate: Int
)
