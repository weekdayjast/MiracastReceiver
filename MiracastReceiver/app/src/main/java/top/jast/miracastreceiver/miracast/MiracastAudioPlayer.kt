package top.jast.miracastreceiver.miracast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Miracast 音频播放：把 [TsDemuxer] 解出的音频 PES 解码后写进 AudioTrack。
 *
 * WFD 的音频有两种封装：
 * - AAC（stream_type 0x0F）：PES 负载是一串 ADTS 帧，剥掉 ADTS 头后逐帧喂给 MediaCodec
 * - LPCM（stream_type 0x83）：PES 负载是 4 字节私有头 + 16 位大端 PCM，字节序翻转后直接播放
 *
 * 和视频一样走「收到即播」，不按 PTS 做音画同步：视频是收到即送显的，音频只要不积压，
 * 两者自然对齐。所以这里把队列压得很浅，积压超过阈值（源端时钟比电视快、或者解码卡顿）
 * 就丢掉旧数据追上实时，宁可短暂断音也不让声音越拖越晚。
 *
 * 解码器和 AudioTrack 只在播放线程上创建、使用和释放，不跨线程碰 MediaCodec。
 */
class MiracastAudioPlayer {

    private val queue = ArrayBlockingQueue<Pair<TsDemuxer.AudioCodec, ByteArray>>(QUEUE_CAPACITY)

    @Volatile
    private var running = false

    /** release() 之后解复用线程可能还会送来最后几个 PES，不能让它们把线程再拉起来 */
    @Volatile
    private var released = false
    private var thread: Thread? = null

    /** 数据有丢失，播放线程应丢掉残缺的 ADTS 数据重新找同步字 */
    @Volatile
    private var resyncRequested = false

    // 以下字段只在播放线程上访问
    private var codec: MediaCodec? = null
    private var codecConfig = 0                  // 当前解码器对应的 ASC，变了就重建
    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = 0
    private var trackChannels = 0
    private val adts = ByteArrayOutputStream(16 * 1024)
    private var firstPcm = true
    private var droppedPes = 0L

    companion object {
        /** AAC 一帧 1024 样本，48kHz 下约 21ms；Windows 通常一个 PES 放一两帧 */
        private const val QUEUE_CAPACITY = 64

        /** 积压超过这么多 PES 就认为落后了，丢到只剩 [QUEUE_KEEP] 个 */
        private const val MAX_BACKLOG = 8
        private const val QUEUE_KEEP = 2

        private val SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
        )
    }

    /** [TsDemuxer] 的回调入口，运行在解复用线程上，只入队不做重活。 */
    fun onAudioPes(codecType: TsDemuxer.AudioCodec, data: ByteArray) {
        if (released) return
        if (!running) start()
        val item = codecType to data
        if (!queue.offer(item)) {
            queue.poll()
            queue.offer(item)
            droppedPes++
        }
    }

    fun onDiscontinuity() {
        resyncRequested = true
    }

    @Synchronized
    private fun start() {
        if (running || released) return
        running = true
        thread = Thread(::runPlayback, "MiracastAudio").apply { start() }
    }

    @Synchronized
    fun release() {
        released = true
        running = false
        thread?.interrupt()
        thread = null
        queue.clear()
    }

    private fun runPlayback() {
        Timber.i("Miracast audio: playback thread started")
        try {
            while (running) {
                val (type, data) = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                } ?: continue

                if (queue.size > MAX_BACKLOG) {
                    var dropped = 0
                    while (queue.size > QUEUE_KEEP) {
                        queue.poll()
                        dropped++
                    }
                    droppedPes += dropped
                    resyncRequested = true
                    Timber.w("Miracast audio: behind real time, dropped $dropped PES (total $droppedPes)")
                }

                try {
                    when (type) {
                        TsDemuxer.AudioCodec.AAC_ADTS -> handleAac(data)
                        TsDemuxer.AudioCodec.LPCM -> handleLpcm(data)
                    }
                } catch (e: Exception) {
                    if (running) Timber.e(e, "Miracast audio: decode error")
                    releaseCodec()                       // 解码器出错后状态不可信，下一帧重建
                }
            }
        } finally {
            releaseCodec()
            releaseTrack()
            Timber.i("Miracast audio: playback thread stopped, dropped $droppedPes PES")
        }
    }

    // ─── AAC ────────────────────────────────────────────────────────────────

    /** 把 PES 负载接到缓冲尾部，逐个切出完整的 ADTS 帧解码（ADTS 帧可能跨 PES）。 */
    private fun handleAac(data: ByteArray) {
        if (resyncRequested) {
            resyncRequested = false
            adts.reset()
        }
        adts.write(data)
        val buf = adts.toByteArray()
        var pos = 0

        while (pos + 7 <= buf.size) {
            // ADTS 同步字 0xFFF
            if ((buf[pos].toInt() and 0xFF) != 0xFF || (buf[pos + 1].toInt() and 0xF0) != 0xF0) {
                pos++
                continue
            }
            val b1 = buf[pos + 1].toInt() and 0xFF
            val b2 = buf[pos + 2].toInt() and 0xFF
            val b3 = buf[pos + 3].toInt() and 0xFF
            val b4 = buf[pos + 4].toInt() and 0xFF
            val b5 = buf[pos + 5].toInt() and 0xFF

            val headerLength = if ((b1 and 0x01) != 0) 7 else 9      // protection_absent=0 时带 2 字节 CRC
            val objectType = ((b2 shr 6) and 0x03) + 1               // ADTS profile + 1 = AudioObjectType
            val srIndex = (b2 shr 2) and 0x0F
            val channelConfig = ((b2 and 0x01) shl 2) or ((b3 shr 6) and 0x03)
            val frameLength = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 shr 5) and 0x07)

            if (srIndex >= SAMPLE_RATES.size || channelConfig == 0 || frameLength <= headerLength) {
                pos++                                                // 伪同步字，继续往后找
                continue
            }
            if (pos + frameLength > buf.size) break                  // 帧不完整，等下一个 PES

            ensureAacDecoder(objectType, srIndex, channelConfig)
            decodeAacFrame(buf, pos + headerLength, frameLength - headerLength)
            pos += frameLength
        }

        adts.reset()
        if (pos < buf.size) adts.write(buf, pos, buf.size - pos)
    }

    private fun ensureAacDecoder(objectType: Int, srIndex: Int, channelConfig: Int) {
        // AudioSpecificConfig：5 位 objectType + 4 位采样率索引 + 4 位声道配置 + 3 位 0
        val asc = (objectType shl 11) or (srIndex shl 7) or (channelConfig shl 3)
        if (codec != null && asc == codecConfig) return

        releaseCodec()
        val sampleRate = SAMPLE_RATES[srIndex]
        val channels = if (channelConfig == 7) 8 else channelConfig
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            if (objectType == 2) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((asc shr 8).toByte(), asc.toByte())))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
        codecConfig = asc
        ensureTrack(sampleRate, channels)
        Timber.i("Miracast audio: AAC decoder ready, objectType=$objectType ${sampleRate}Hz x$channels")
    }

    private fun decodeAacFrame(buf: ByteArray, offset: Int, length: Int) {
        val mc = codec ?: return
        val inIdx = mc.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val inBuf = mc.getInputBuffer(inIdx)!!
            inBuf.clear()
            inBuf.put(buf, offset, length)
            mc.queueInputBuffer(inIdx, 0, length, 0, 0)
        }

        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = mc.dequeueOutputBuffer(info, 0)
            when {
                outIdx >= 0 -> {
                    val outBuf = mc.getOutputBuffer(outIdx)!!
                    val pcm = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.get(pcm)
                    mc.releaseOutputBuffer(outIdx, false)
                    writePcm(pcm, pcm.size)
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // HE-AAC 等情况下实际输出的采样率/声道可能和 ADTS 头里写的不同，以解码器为准
                    val fmt = mc.outputFormat
                    ensureTrack(
                        fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    )
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                // INFO_OUTPUT_BUFFERS_CHANGED 等其它值：继续取
            }
        }
    }

    // ─── LPCM ───────────────────────────────────────────────────────────────

    /**
     * WFD 的 LPCM PES 负载：4 字节私有头（0xA0、帧数、保留、量化/采样率/声道），
     * 后面是 16 位大端 PCM。AudioTrack 要小端，逐样本翻转字节序即可。
     */
    private fun handleLpcm(data: ByteArray) {
        var offset = 0
        var sampleRate = 48000
        var channels = 2
        if (data.size >= 4 && (data[0].toInt() and 0xFF) == 0xA0) {
            val info = data[3].toInt() and 0xFF
            sampleRate = if (((info shr 3) and 0x07) == 1) 44100 else 48000
            channels = if ((info and 0x07) == 0) 1 else 2
            offset = 4
        }
        val length = (data.size - offset) and 1.inv()               // 按 16 位样本对齐
        if (length <= 0) return

        val pcm = ByteArray(length)
        var i = 0
        while (i < length) {
            pcm[i] = data[offset + i + 1]
            pcm[i + 1] = data[offset + i]
            i += 2
        }
        ensureTrack(sampleRate, channels)
        writePcm(pcm, length)
    }

    // ─── AudioTrack ─────────────────────────────────────────────────────────

    private fun ensureTrack(sampleRate: Int, channels: Int) {
        if (audioTrack != null && sampleRate == trackSampleRate && channels == trackChannels) return
        releaseTrack()

        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        // 缓冲取最小值：视频是收到即送显的，音频多缓冲一分就多落后画面一分。
        // 网络抖动由上游的队列吸收，AudioTrack 自身只需要够硬件不欠载。
        audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            // AudioTrack.Builder 是 API 23 起才有的，minSdk 是 21，低版本回退到老式构造函数
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf,
                AudioTrack.MODE_STREAM
            )
        }.also { it.play() }
        trackSampleRate = sampleRate
        trackChannels = channels
        Timber.i("Miracast audio: AudioTrack ${sampleRate}Hz x$channels, buffer=${minBuf}B " +
            "(~${minBuf * 1000 / (sampleRate * channels * 2)}ms)")
    }

    /** 阻塞写入，由 AudioTrack 的消耗速度给播放线程定速。 */
    private fun writePcm(pcm: ByteArray, length: Int) {
        val track = audioTrack ?: return
        if (firstPcm) {
            firstPcm = false
            Timber.i("Miracast audio: first PCM (${length}B) → AudioTrack")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            track.write(pcm, 0, length, AudioTrack.WRITE_BLOCKING)
        } else {
            @Suppress("DEPRECATION")
            track.write(pcm, 0, length)
        }
    }

    private fun releaseCodec() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        codecConfig = 0
    }

    private fun releaseTrack() {
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        trackSampleRate = 0
        trackChannels = 0
    }
}
