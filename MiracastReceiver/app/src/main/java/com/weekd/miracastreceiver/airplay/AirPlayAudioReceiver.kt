package com.weekd.miracastreceiver.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * AirPlay 音频接收器
 *
 * 负责接收和解码 AirPlay 音频流：
 * 1. 接收 RTP 音频包（AAC/ALAC）
 * 2. 音频解码
 * 3. 音频播放
 * 4. 与视频同步
 */
class AirPlayAudioReceiver {

    companion object {
        const val AUDIO_RTP_PORT = 7001  // 改为 7001 避免与 AirPlay 信令端口 7000 冲突
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2
        const val BITS_PER_SAMPLE = 16

        // RTP Payload Types
        const val PAYLOAD_TYPE_AAC = 96
        const val PAYLOAD_TYPE_ALAC = 97
    }

    private var audioSocket: DatagramSocket? = null
    private var audioDecoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    private var isReceiving = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var audioJob: Job? = null
    private var playbackJob: Job? = null

    // 音频包队列
    private val audioPacketQueue = ConcurrentLinkedQueue<AudioPacket>()
    private var lastAudioTimestamp = 0L

    data class AudioPacket(
        val data: ByteArray,
        val timestamp: Long,
        val sequence: Int
    )

    /**
     * 启动音频接收
     */
    fun start() {
        if (isReceiving) {
            Timber.w("Audio receiver already running")
            return
        }

        isReceiving = true

        try {
            // 创建 UDP 套接字
            audioSocket = DatagramSocket(AUDIO_RTP_PORT)

            // 初始化 AudioTrack
            initAudioTrack()

            // 启动接收线程
            startAudioReceiver()
            startAudioPlayback()

            Timber.i("AirPlay audio receiver started")

        } catch (e: Exception) {
            Timber.e(e, "Error starting audio receiver")
            stop()
        }
    }

    /**
     * 停止接收
     */
    fun stop() {
        isReceiving = false

        audioJob?.cancel()
        playbackJob?.cancel()

        audioSocket?.close()

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        audioDecoder?.stop()
        audioDecoder?.release()
        audioDecoder = null

        audioPacketQueue.clear()

        Timber.i("AirPlay audio receiver stopped")
    }

    /**
     * 初始化 AudioTrack
     */
    private fun initAudioTrack() {
        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()
            Timber.i("AudioTrack initialized: $SAMPLE_RATE Hz, Stereo, 16-bit")

        } catch (e: Exception) {
            Timber.e(e, "Error initializing AudioTrack")
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
     * 处理音频 RTP 包
     */
    private fun processAudioRtpPacket(data: ByteArray) {
        if (data.size < 12) return

        // 解析 RTP 头
        val version = (data[0].toInt() shr 6) and 0x03
        val padding = (data[0].toInt() shr 5) and 0x01
        val extension = (data[0].toInt() shr 4) and 0x01
        val csrcCount = data[0].toInt() and 0x0F
        val marker = (data[1].toInt() shr 7) and 0x01
        val payloadType = data[1].toInt() and 0x7F
        val sequence = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val timestamp = (
            ((data[4].toInt() and 0xFF) shl 24) or
            ((data[5].toInt() and 0xFF) shl 16) or
            ((data[6].toInt() and 0xFF) shl 8) or
            (data[7].toInt() and 0xFF)
        ).toLong() and 0xFFFFFFFFL

        // 计算 payload 起始位置
        var payloadStart = 12 + csrcCount * 4

        if (extension == 1 && data.size > payloadStart + 4) {
            val extLength = ((data[payloadStart + 2].toInt() and 0xFF) shl 8) or
                          (data[payloadStart + 3].toInt() and 0xFF)
            payloadStart += 4 + extLength * 4
        }

        if (payloadStart >= data.size) return

        // 提取音频 payload
        val payload = data.copyOfRange(payloadStart, data.size)

        // 根据 payload type 处理
        when (payloadType) {
            PAYLOAD_TYPE_AAC -> processAacAudio(payload, timestamp, sequence)
            PAYLOAD_TYPE_ALAC -> processAlacAudio(payload, timestamp, sequence)
            else -> {
                // 假设是 PCM 数据，直接播放
                audioPacketQueue.offer(AudioPacket(payload, timestamp, sequence))
            }
        }
    }

    /**
     * 处理 AAC 音频
     */
    private fun processAacAudio(payload: ByteArray, timestamp: Long, sequence: Int) {
        // AAC 解码需要初始化 MediaCodec
        if (audioDecoder == null) {
            tryInitializeAacDecoder()
        }

        // 简化版：暂时将原始数据加入队列
        audioPacketQueue.offer(AudioPacket(payload, timestamp, sequence))
    }

    /**
     * 处理 ALAC 音频
     */
    private fun processAlacAudio(payload: ByteArray, timestamp: Long, sequence: Int) {
        // ALAC 是 Apple 的无损音频格式
        // 简化版：暂不实现 ALAC 解码
        Timber.d("ALAC audio packet received: ${payload.size} bytes")
    }

    /**
     * 尝试初始化 AAC 解码器
     */
    private fun tryInitializeAacDecoder() {
        try {
            audioDecoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE,
                CHANNELS
            )

            // AAC 特定配置：AAC LC profile = 2
            format.setInteger(MediaFormat.KEY_IS_ADTS, 1)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, 2)

            audioDecoder?.configure(format, null, null, 0)
            audioDecoder?.start()

            Timber.i("AAC audio decoder initialized")

        } catch (e: Exception) {
            Timber.e(e, "Error initializing AAC decoder")
            audioDecoder?.release()
            audioDecoder = null
        }
    }

    /**
     * 启动音频播放线程
     */
    private fun startAudioPlayback() {
        playbackJob = scope.launch {
            while (isReceiving) {
                val packet = audioPacketQueue.poll()
                if (packet != null) {
                    playAudioPacket(packet)
                } else {
                    delay(5) // 等待新数据
                }
            }
        }
    }

    /**
     * 播放音频包
     */
    private fun playAudioPacket(packet: AudioPacket) {
        try {
            val track = audioTrack ?: return

            // 如果有解码器，先解码
            val decoder = audioDecoder
            if (decoder != null) {
                // 解码逻辑
                val inputIndex = decoder.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(packet.data)
                    decoder.queueInputBuffer(
                        inputIndex,
                        0,
                        packet.data.size,
                        packet.timestamp,
                        0
                    )
                }

                // 获取解码输出
                val bufferInfo = MediaCodec.BufferInfo()
                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val pcmData = ByteArray(bufferInfo.size)
                        outputBuffer.get(pcmData)
                        track.write(pcmData, 0, pcmData.size)
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            } else {
                // 直接播放（假设是 PCM）
                track.write(packet.data, 0, packet.data.size)
            }

            lastAudioTimestamp = packet.timestamp

        } catch (e: Exception) {
            Timber.e(e, "Error playing audio packet")
        }
    }

    /**
     * 设置音量
     */
    fun setVolume(volume: Float) {
        audioTrack?.setVolume(volume.coerceIn(0f, 1f))
    }

    /**
     * 获取当前播放时间戳（用于音视频同步）
     */
    fun getCurrentTimestamp(): Long = lastAudioTimestamp

    /**
     * 暂停播放
     */
    fun pause() {
        audioTrack?.pause()
    }

    /**
     * 恢复播放
     */
    fun resume() {
        audioTrack?.play()
    }
}
