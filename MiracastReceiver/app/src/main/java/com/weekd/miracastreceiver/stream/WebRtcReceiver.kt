package com.weekd.miracastreceiver.stream

import timber.log.Timber

/**
 * WebRTC 接收器
 * MVP 阶段占位实现，后续实现真正的 WebRTC PeerConnection 接收逻辑
 */
class WebRtcReceiver {

    fun initialize() {
        Timber.i("WebRtcReceiver initialized")
    }

    fun handleOffer(offerSdp: String): String {
        Timber.i("Handle WebRTC offer")
        // TODO: 创建 PeerConnection、设置远端 SDP、生成 answer
        return ""
    }

    fun addIceCandidate(candidate: String) {
        Timber.i("Add ICE candidate")
        // TODO: 添加 ICE candidate
    }

    fun close() {
        Timber.i("WebRtcReceiver closed")
        // TODO: 关闭 PeerConnection
    }
}
