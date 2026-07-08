package com.weekd.miracastreceiver.discovery

import android.content.Context
import android.os.Build
import com.weekd.miracastreceiver.utils.CodecUtils
import com.weekd.miracastreceiver.utils.NetworkUtils

/**
 * 设备信息提供器
 */
class DeviceInfoProvider(private val context: Context) {

    fun getDeviceName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            .ifEmpty { "Android TV" }
    }

    fun getDeviceId(): String {
        return "${Build.MANUFACTURER}-${Build.MODEL}-${Build.SERIAL}"
            .replace(" ", "-")
            .replace("/", "-")
    }

    fun getDeviceInfo(): Map<String, String> {
        val videoConfig = CodecUtils.getRecommendedVideoConfig()

        return mapOf(
            "name" to getDeviceName(),
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "android_version" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "ip" to (NetworkUtils.getLocalIpAddress() ?: "unknown"),
            "h264" to CodecUtils.isVideoDecoderSupported(CodecUtils.MIME_VIDEO_H264).toString(),
            "h265" to CodecUtils.isVideoDecoderSupported(CodecUtils.MIME_VIDEO_H265).toString(),
            "recommended_codec" to videoConfig.mimeType,
            "max_width" to videoConfig.width.toString(),
            "max_height" to videoConfig.height.toString(),
            "max_fps" to videoConfig.frameRate.toString()
        )
    }
}
