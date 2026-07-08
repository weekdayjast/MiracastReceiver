package com.weekd.miracastreceiver.signaling

/**
 * 信令消息类型
 */
enum class MessageType {
    DEVICE_INFO,
    CONNECT_REQUEST,
    CONNECT_RESPONSE,
    OFFER,
    ANSWER,
    ICE_CANDIDATE,
    START_STREAM,
    STOP_STREAM,
    HEARTBEAT,
    ERROR
}

/**
 * 信令消息
 */
data class SignalingMessage(
    val type: MessageType,
    val sessionId: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val payload: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 连接响应
 */
data class ConnectResponse(
    val accepted: Boolean,
    val reason: String? = null,
    val sessionId: String? = null
)

/**
 * 投屏设备信息
 */
data class CastDevice(
    val deviceId: String,
    val deviceName: String,
    val deviceType: DeviceType,
    val ipAddress: String,
    val connectedAt: Long = System.currentTimeMillis()
)

enum class DeviceType {
    ANDROID,
    IOS,
    WINDOWS,
    MACOS,
    WEB,
    UNKNOWN
}
