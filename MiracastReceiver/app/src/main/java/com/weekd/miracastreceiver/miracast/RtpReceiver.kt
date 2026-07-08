package com.weekd.miracastreceiver.miracast

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

/**
 * RTP 视频流接收器
 * 接收并解析 RTP 包，提取 H.264 视频数据
 */
class RtpReceiver(
    private val port: Int
) {
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var decoder: MediaCodec? = null
    private var surface: Surface? = null

    // RTP 包统计
    private var packetsReceived = 0
    private var bytesReceived = 0L

    // H.264 NAL 单元缓冲
    private val nalBuffer = mutableListOf<ByteArray>()

    var onVideoFrame: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun start(surface: Surface?) {
        if (isRunning) {
            Timber.w("RTP Receiver already running")
            return
        }

        this.surface = surface
        initDecoder()

        scope.launch {
            try {
                socket = DatagramSocket(port)
                isRunning = true
                Timber.i("RTP Receiver started on port $port")

                val buffer = ByteArray(65536) // 64KB 缓冲区
                val packet = DatagramPacket(buffer, buffer.size)

                while (isRunning) {
                    try {
                        socket?.receive(packet)
                        if (packet.length > 0) {
                            val rtpData = packet.data.copyOfRange(0, packet.length)
                            handleRtpPacket(rtpData)
                            packetsReceived++
                            bytesReceived += packet.length

                            if (packetsReceived % 100 == 0) {
                                Timber.v("RTP Stats: $packetsReceived packets, ${bytesReceived / 1024}KB received")
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Timber.e(e, "Error receiving RTP packet")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start RTP Receiver")
                onError?.invoke("Failed to start RTP Receiver: ${e.message}")
                isRunning = false
            }
        }
    }

    private fun handleRtpPacket(data: ByteArray) {
        if (data.size < 12) {
            Timber.w("Invalid RTP packet: too short")
            return
        }

        try {
            // 解析 RTP 头部
            val version = (data[0].toInt() shr 6) and 0x03
            val padding = (data[0].toInt() shr 5) and 0x01
            val extension = (data[0].toInt() shr 4) and 0x01
            val csrcCount = data[0].toInt() and 0x0F

            val marker = (data[1].toInt() shr 7) and 0x01
            val payloadType = data[1].toInt() and 0x7F

            val sequenceNumber = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val timestamp = ((data[4].toInt() and 0xFF) shl 24) or
                    ((data[5].toInt() and 0xFF) shl 16) or
                    ((data[6].toInt() and 0xFF) shl 8) or
                    (data[7].toInt() and 0xFF)

            // 计算 RTP 头部长度
            var headerLength = 12 + (csrcCount * 4)

            // 如果有扩展头
            if (extension == 1 && data.size > headerLength + 4) {
                val extLength = ((data[headerLength + 2].toInt() and 0xFF) shl 8) or
                        (data[headerLength + 3].toInt() and 0xFF)
                headerLength += 4 + (extLength * 4)
            }

            if (data.size <= headerLength) {
                return
            }

            // 提取 RTP 负载 (H.264 数据)
            var payloadLength = data.size - headerLength

            // 处理 padding
            if (padding == 1 && payloadLength > 0) {
                val paddingLength = data[data.size - 1].toInt() and 0xFF
                payloadLength -= paddingLength
            }

            if (payloadLength <= 0) {
                return
            }

            val payload = data.copyOfRange(headerLength, headerLength + payloadLength)

            // 处理 H.264 负载
            handleH264Payload(payload, marker == 1)

        } catch (e: Exception) {
            Timber.e(e, "Error handling RTP packet")
        }
    }

    private fun handleH264Payload(payload: ByteArray, marker: Boolean) {
        if (payload.isEmpty()) return

        try {
            // 检查 NAL 单元类型
            val nalUnitType = payload[0].toInt() and 0x1F

            when (nalUnitType) {
                in 1..23 -> {
                    // 单个 NAL 单元
                    decodeNalUnit(payload)
                }
                24 -> {
                    // STAP-A (单时间聚合包)
                    handleStapA(payload)
                }
                28 -> {
                    // FU-A (分片单元)
                    handleFuA(payload, marker)
                }
                else -> {
                    Timber.v("Unknown NAL unit type: $nalUnitType")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling H.264 payload")
        }
    }

    private fun handleStapA(payload: ByteArray) {
        // STAP-A: 多个 NAL 单元打包在一起
        var offset = 1 // 跳过 STAP-A 头

        while (offset + 2 < payload.size) {
            val nalSize = ((payload[offset].toInt() and 0xFF) shl 8) or
                    (payload[offset + 1].toInt() and 0xFF)
            offset += 2

            if (offset + nalSize <= payload.size) {
                val nalUnit = payload.copyOfRange(offset, offset + nalSize)
                decodeNalUnit(nalUnit)
                offset += nalSize
            } else {
                break
            }
        }
    }

    private fun handleFuA(payload: ByteArray, marker: Boolean) {
        if (payload.size < 2) return

        // FU-A 格式: FU indicator (1 byte) + FU header (1 byte) + FU payload
        val fuIndicator = payload[0]
        val fuHeader = payload[1]

        val start = (fuHeader.toInt() shr 7) and 0x01
        val end = (fuHeader.toInt() shr 6) and 0x01
        val nalUnitType = fuHeader.toInt() and 0x1F

        val fragmentData = payload.copyOfRange(2, payload.size)

        if (start == 1) {
            // 分片开始
            nalBuffer.clear()
            // 重建 NAL 头
            val nalHeader = ((fuIndicator.toInt() and 0xE0) or nalUnitType).toByte()
            nalBuffer.add(byteArrayOf(nalHeader))
        }

        nalBuffer.add(fragmentData)

        if (end == 1 || marker) {
            // 分片结束，组合完整的 NAL 单元
            val completeNal = nalBuffer.flatMap { it.asIterable() }.toByteArray()
            decodeNalUnit(completeNal)
            nalBuffer.clear()
        }
    }

    private fun decodeNalUnit(nalUnit: ByteArray) {
        try {
            val decoder = this.decoder ?: return

            // 添加起始码 (0x00 0x00 0x00 0x01)
            val nalWithStartCode = ByteArray(4 + nalUnit.size)
            nalWithStartCode[0] = 0x00
            nalWithStartCode[1] = 0x00
            nalWithStartCode[2] = 0x00
            nalWithStartCode[3] = 0x01
            System.arraycopy(nalUnit, 0, nalWithStartCode, 4, nalUnit.size)

            // 送入解码器
            val inputBufferIndex = decoder.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(nalWithStartCode)
                    decoder.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        nalWithStartCode.size,
                        System.nanoTime() / 1000,
                        0
                    )
                }
            }

            // 获取输出
            val bufferInfo = MediaCodec.BufferInfo()
            var outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)

            while (outputBufferIndex >= 0) {
                decoder.releaseOutputBuffer(outputBufferIndex, true)
                outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
            }

        } catch (e: Exception) {
            Timber.e(e, "Error decoding NAL unit")
        }
    }

    private fun initDecoder() {
        try {
            // 创建 H.264 解码器
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

            // 配置解码器
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                1920, // 默认宽度
                1080  // 默认高度
            )

            decoder?.configure(format, surface, null, 0)
            decoder?.start()

            Timber.i("H.264 decoder initialized")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize H.264 decoder")
            onError?.invoke("Failed to initialize decoder: ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        scope.cancel()

        try {
            decoder?.stop()
            decoder?.release()
            decoder = null
            Timber.i("H.264 decoder released")
        } catch (e: Exception) {
            Timber.e(e, "Error releasing decoder")
        }

        try {
            socket?.close()
            socket = null
            Timber.i("RTP Receiver stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping RTP Receiver")
        }

        Timber.i("RTP Stats: Total $packetsReceived packets, ${bytesReceived / 1024}KB received")
    }

    fun getStats(): String {
        return "Packets: $packetsReceived, Data: ${bytesReceived / 1024}KB"
    }
}
