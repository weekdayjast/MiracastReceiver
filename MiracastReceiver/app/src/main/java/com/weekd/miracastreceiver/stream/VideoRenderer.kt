package com.weekd.miracastreceiver.stream

import android.view.SurfaceView
import timber.log.Timber

/**
 * 视频渲染器
 * MVP 阶段占位实现，后续接入 WebRTC / MediaCodec 渲染
 */
class VideoRenderer(private val surfaceView: SurfaceView) {

    fun initialize() {
        Timber.i("VideoRenderer initialized")
    }

    fun start() {
        Timber.i("VideoRenderer started")
    }

    fun stop() {
        Timber.i("VideoRenderer stopped")
    }

    fun release() {
        Timber.i("VideoRenderer released")
    }
}
