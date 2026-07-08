package com.weekd.miracastreceiver.airplay

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.weekd.miracastreceiver.ui.PlayerActivity
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * AirPlay 屏幕镜像接收器
 *
 * 负责接收和解码 AirPlay 镜像流：
 * 1. 处理 RTSP 协议握手
 * 2. 接收 RTP 视频流（H.264）
 * 3. 接收 RTP 音频流（AAC/ALAC）
 * 4. 视频解码和渲染
 * 5. 音频解码和播放
 *
 * 注意：这是简化版实现，完整的 AirPlay 镜像需要：
 * - FairPlay DRM 解密（需要苹果授权）
 * - 复杂的时间同步机制
 * - 完整的 RTCP 反馈
 */
class AirPlayMirroringReceiver(private val context: Context) {

    companion object {
        const val VIDEO_RTP_PORT = 6000
        const val AUDIO_RTP_PORT = 7000
        const val TIMING_PORT = 6002
        const val CONTROL_PORT = 6001

        // H.264 NAL Unit Types
        const val NAL_SLICE = 1
        const val NAL_IDR_SLICE = 5
        const val NAL_SPS = 7
        const val NAL_PPS = 8
    }

    private var videoSocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null

    private var videoDecoder: MediaCodec? = null
    private var audioDecoder: MediaCodec? = null

    private var surface: Surface? = null
    private var isReceiving = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var videoJob: Job? = null
    private var audioJob: Job? = null
    private var timingJob: Job? = null
    private var controlJob: Job? = null

    // 音频接收器
    private var audioReceiver: AirPlayAudioReceiver? = null

    // RTP 包重组缓存
    private val videoPacketCache = ConcurrentHashMap<Int, ByteArray>()
    private var lastVideoSequence = -1

    // SPS/PPS 缓存
    private var spsData: ByteArray? = null
    private var ppsData: ByteArray? = null

    // 统计信息
    private var videoPacketsReceived = 0L
    private var audioPacketsReceived = 0L
    private var packetsLost = 0L

    /**
     * 启动 AirPlay 镜像接收
     */
    fun start(surface: Surface? = null) {
        if (isReceiving) {
            Timber.w("AirPlay mirroring already receiving")
            return
        }

        this.surface = surface
        isReceiving = true

        try {
            // 创建 UDP 套接字
            videoSocket = DatagramSocket(VIDEO_RTP_PORT)
            audioSocket = DatagramSocket(AUDIO_RTP_PORT)
            timingSocket = DatagramSocket(TIMING_PORT)
            controlSocket = DatagramSocket(CONTROL_PORT)

            // 初始化音频接收器
            audioReceiver = AirPlayAudioReceiver()
            audioReceiver?.start()

            // 启动接收线程
            startVideoReceiver()
            startTimingReceiver()
            startControlReceiver()

            Timber.i("AirPlay mirroring receiver started")

        } catch (e: Exception) {
            Timber.e(e, "Error starting AirPlay mirroring receiver")
            stop()
        }
    }

    /**
     * 停止接收
     */
    fun stop() {
        isReceiving = false

        videoJob?.cancel()
        audioJob?.cancel()
        timingJob?.cancel()
        controlJob?.cancel()

        videoSocket?.close()
        audioSocket?.close()
        timingSocket?.close()
        controlSocket?.close()

        audioReceiver?.stop()
        audioReceiver = null

        releaseDecoders()

        videoPacketCache.clear()
        lastVideoSequence = -1
        spsData = null
        ppsData = null

        Timber.i("AirPlay mirroring receiver stopped")
    }

    /**
     * 启动视频接收线程
     */
    private fun startVideoReceiver() {
        videoJob = scope.launch {
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isReceiving && videoSocket?.isClosed == false) {
                try {
                    videoSocket?.receive(packet)
                    val data = packet.data.copyOfRange(0, packet.length)
                    processVideoRtpPacket(data)
                } catch (e: Exception) {
                    if (isReceiving) {
                        Timber.e(e, "Error receiving video RTP packet")
                    }
                }
            }
        }
    }

    /**
     * 启动音频接收线程
     */
    private fun startAudioReceiver() {
        audioJob = scope.launch {
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isReceiving && audioSocket?.isClosed == false) {
                try {
                    audioSocket?.receive(packet)
                    val data = packet.data.copyOfRange(0, packet.length)
                    processAudioRtpPacket(data)
                } catch (e: Exception) {
                    if (isReceiving) {
                        Timber.e(e, "Error receiving audio RTP packet")
                    }
                }
            }
        }
    }

    /**
     * 启动时间同步接收线程
     */
    private fun startTimingReceiver() {
        timingJob = scope.launch {
            val buffer = ByteArray(256)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isReceiving && timingSocket?.isClosed == false) {
                try {
                    timingSocket?.receive(packet)
                    // 处理 NTP 时间同步包
                    // 简化版暂不实现完整时间同步
                } catch (e: Exception) {
                    if (isReceiving) {
                        Timber.e(e, "Error receiving timing packet")
                    }
                }
            }
        }
    }

    /**
     * 启动控制接收线程
     */
    private fun startControlReceiver() {
        controlJob = scope.launch {
            val buffer = ByteArray(256)
            val packet = DatagramPacket(buffer, buffer.size)

            while (isReceiving && controlSocket?.isClosed == false) {
                try {
                    controlSocket?.receive(packet)
                    // 处理 RTCP 控制包
                    // 简化版暂不实现完整 RTCP
                } catch (e: Exception) {
                    if (isReceiving) {
                        Timber.e(e, "Error receiving control packet")
                    }
                }
            }
        }
    }

    /**
     * 处理视频 RTP 包
     */
    private fun processVideoRtpPacket(data: ByteArray) {
        if (data.size < 12) return

        videoPacketsReceived++

        // 解析 RTP 头
        val version = (data[0].toInt() shr 6) and 0x03
        val padding = (data[0].toInt() shr 5) and 0x01
        val extension = (data[0].toInt() shr 4) and 0x01
        val csrcCount = data[0].toInt() and 0x0F
        val marker = (data[1].toInt() shr 7) and 0x01
        val payloadType = data[1].toInt() and 0x7F
        val sequence = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val timestamp = ((data[4].toInt() and 0xFF) shl 24) or
                       ((data[5].toInt() and 0xFF) shl 16) or
                       ((data[6].toInt() and 0xFF) shl 8) or
                       (data[7].toInt() and 0xFF)
        val ssrc = ((data[8].toInt() and 0xFF) shl 24) or
                   ((data[9].toInt() and 0xFF) shl 16) or
                   ((data[10].toInt() and 0xFF) shl 8) or
                   (data[11].toInt() and 0xFF)

        // 检测丢包
        if (lastVideoSequence >= 0) {
            val expectedSeq = (lastVideoSequence + 1) and 0xFFFF
            if (sequence != expectedSeq) {
                val lost = if (sequence > expectedSeq) {
                    sequence - expectedSeq
                } else {
                    (0xFFFF - expectedSeq) + sequence + 1
                }
                packetsLost += lost
                Timber.w("Video packets lost: $lost (expected: $expectedSeq, got: $sequence)")
            }
        }
        lastVideoSequence = sequence

        // 计算 payload 起始位置
        var payloadStart = 12 + csrcCount * 4

        if (extension == 1 && data.size > payloadStart + 4) {
            val extLength = ((data[payloadStart + 2].toInt() and 0xFF) shl 8) or
                          (data[payloadStart + 3].toInt() and 0xFF)
            payloadStart += 4 + extLength * 4
        }

        if (payloadStart >= data.size) return

        // 提取 H.264 payload
        val payload = data.copyOfRange(payloadStart, data.size)

        // 处理 H.264 NAL units
        processH264Payload(payload, marker == 1, timestamp)
    }

    /**
     * 处理 H.264 Payload
     */
    private fun processH264Payload(payload: ByteArray, isLastFragment: Boolean, timestamp: Int) {
        if (payload.isEmpty()) return

        val nalType = payload[0].toInt() and 0x1F

        when (nalType) {
            NAL_SPS -> {
                // 收到 SPS
                spsData = payload
                Timber.d("Received SPS: ${payload.size} bytes")
                tryInitializeDecoder()
            }
            NAL_PPS -> {
                // 收到 PPS
                ppsData = payload
                Timber.d("Received PPS: ${payload.size} bytes")
                tryInitializeDecoder()
            }
            NAL_IDR_SLICE, NAL_SLICE -> {
                // 关键帧或普通帧
                if (videoDecoder != null) {
                    feedToDecoder(payload, timestamp)
                }
            }
            28 -> {
                // FU-A 分片
                processFuaFragment(payload, isLastFragment, timestamp)
            }
            else -> {
                Timber.d("Unsupported NAL type: $nalType")
            }
        }
    }

    /**
     * 处理 FU-A 分片
     */
    private fun processFuaFragment(payload: ByteArray, isLastFragment: Boolean, timestamp: Int) {
        if (payload.size < 2) return

        val fuIndicator = payload[0]
        val fuHeader = payload[1]
        val start = (fuHeader.toInt() shr 7) and 0x01
        val end = (fuHeader.toInt() shr 6) and 0x01
        val nalType = fuHeader.toInt() and 0x1F

        // FU-A payload starts at byte 2
        val fragmentData = payload.copyOfRange(2, payload.size)

        if (start == 1) {
            // 第一个分片，重建 NAL header
            val nalHeader = ((fuIndicator.toInt() and 0xE0) or nalType).toByte()
            videoPacketCache[timestamp] = byteArrayOf(nalHeader) + fragmentData
        } else {
            // 后续分片，追加数据
            val existing = videoPacketCache[timestamp]
            if (existing != null) {
                videoPacketCache[timestamp] = existing + fragmentData
            }
        }

        if (end == 1 || isLastFragment) {
            // 最后一个分片，送入解码器
            val completeNal = videoPacketCache.remove(timestamp)
            if (completeNal != null && videoDecoder != null) {
                feedToDecoder(completeNal, timestamp)
            }
        }
    }

    /**
     * 尝试初始化解码器
     */
    private fun tryInitializeDecoder() {
        if (videoDecoder != null || spsData == null || ppsData == null) return

        try {
            // 创建 H.264 解码器
            videoDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

            // 从 SPS 解析视频尺寸（简化版，实际应该完整解析 SPS）
            // 这里假设常见的 iPhone 分辨率
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                1920, // 宽度，应从 SPS 解析
                1080  // 高度，应从 SPS 解析
            )

            // 设置 CSD（Codec Specific Data）
            spsData?.let { sps ->
                val csd0 = ByteBuffer.wrap(sps)
                format.setByteBuffer("csd-0", csd0)
            }
            ppsData?.let { pps ->
                val csd1 = ByteBuffer.wrap(pps)
                format.setByteBuffer("csd-1", csd1)
            }

            // 配置解码器
            videoDecoder?.configure(format, surface, null, 0)
            videoDecoder?.start()

            Timber.i("Video decoder initialized")

        } catch (e: Exception) {
            Timber.e(e, "Error initializing video decoder")
            videoDecoder?.release()
            videoDecoder = null
        }
    }

    /**
     * 将数据送入解码器
     */
    private fun feedToDecoder(nalData: ByteArray, timestamp: Int) {
        try {
            val decoder = videoDecoder ?: return

            val inputIndex = decoder.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(nalData)

                decoder.queueInputBuffer(
                    inputIndex,
                    0,
                    nalData.size,
                    timestamp.toLong() * 1000, // 转换为微秒
                    0
                )
            }

            // 获取输出
            val bufferInfo = MediaCodec.BufferInfo()
            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex >= 0) {
                decoder.releaseOutputBuffer(outputIndex, true)
            }

        } catch (e: Exception) {
            Timber.e(e, "Error feeding data to decoder")
        }
    }

    /**
     * 处理音频 RTP 包
     */
    private fun processAudioRtpPacket(data: ByteArray) {
        audioPacketsReceived++
        // 音频处理已迁移到 AirPlayAudioReceiver
    }

    /**
     * 释放解码器
     */
    private fun releaseDecoders() {
        try {
            videoDecoder?.stop()
            videoDecoder?.release()
            videoDecoder = null

            audioDecoder?.stop()
            audioDecoder?.release()
            audioDecoder = null

            Timber.i("Decoders released")
        } catch (e: Exception) {
            Timber.e(e, "Error releasing decoders")
        }
    }

    /**
     * 设置渲染 Surface
     */
    fun setSurface(surface: Surface?) {
        this.surface = surface
        // 如果解码器已初始化，需要重新配置
        // 简化版暂不支持运行时切换 surface
    }

    /**
     * 获取接收统计信息
     */
    fun getStats(): MirroringStats {
        return MirroringStats(
            isReceiving = isReceiving,
            videoPacketsReceived = videoPacketsReceived,
            audioPacketsReceived = audioPacketsReceived,
            packetsLost = packetsLost,
            hasVideoDecoder = videoDecoder != null,
            hasAudioReceiver = audioReceiver != null,
            hasSps = spsData != null,
            hasPps = ppsData != null
        )
    }

    /**
     * 设置音量
     */
    fun setVolume(volume: Float) {
        audioReceiver?.setVolume(volume)
    }

    /**
     * 暂停镜像音频
     */
    fun pauseAudio() {
        audioReceiver?.pause()
    }

    /**
     * 恢复镜像音频
     */
    fun resumeAudio() {
        audioReceiver?.resume()
    }

    data class MirroringStats(
        val isReceiving: Boolean,
        val videoPacketsReceived: Long,
        val audioPacketsReceived: Long,
        val packetsLost: Long,
        val hasVideoDecoder: Boolean,
        val hasAudioReceiver: Boolean,
        val hasSps: Boolean,
        val hasPps: Boolean
    )
}
