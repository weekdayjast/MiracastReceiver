package com.weekd.miracastreceiver.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import com.weekd.miracastreceiver.util.Logger

/**
 * VideoDecoder — Hardware H.264 video decoder using Android's MediaCodec API.
 *
 * WHY: AirPlay screen mirroring sends video as a stream of H.264-encoded frames.
 * To display these frames on screen, we need to decode the H.264 bitstream.
 * MediaCodec is Android's API to the hardware video decoder (GPU), which is
 * much faster and more power-efficient than any software decoder.
 *
 * HOW: Initialize with a [Surface] (from StreamingScreen) and the codec parameters
 * from the SDP (received in the RTSP ANNOUNCE). Then call [decodeNalUnit] for each
 * video chunk received from the RTP stream. MediaCodec outputs decoded frames directly
 * to the Surface — no intermediate buffer copies.
 *
 * AirPlay video data flow:
 *   RTP packet → RtspHandler strips RTP header → NAL unit bytes → VideoDecoder.decodeNalUnit()
 *   → MediaCodec input buffer → GPU hardware decode → Surface (displayed on TV)
 *
 * Example:
 *   val decoder = VideoDecoder(surface)
 *   decoder.initialize(spsBytes, ppsBytes, width, height)  // call once with SDP params
 *   decoder.decodeNalUnit(nalUnitBytes)                    // call for each video chunk
 *   decoder.release()                                       // call when done
 */
class VideoDecoder(private val outputSurface: Surface) {

    // The underlying hardware decoder — null until initialize() is called
    private var mediaCodec: MediaCodec? = null

    // Track whether the decoder has been initialized (to prevent double-init)
    @Volatile
    private var isInitialized = false

    /**
     * False once MediaCodec has thrown (entered an unrecoverable error state). The caller should
     * drop this decoder and create a fresh one — error state cannot be cleared by reconfigure.
     */
    @Volatile
    var isHealthy = true
        private set

    /**
     * 专用的输出线程。
     *
     * 原先只在 [decodeNalUnit] 里排空输出队列，意味着第 N 帧解码完成后要**等第 N+1 帧到达**
     * 才会被送显 —— 系统性地多出整整一个帧间隔（60fps 下 16ms）。屏幕镜像是实时链路，
     * 这部分延迟纯属浪费，所以改成独立线程阻塞等待，解码器一吐帧就立刻送显。
     *
     * MediaCodec 同步模式下「输入在一条线程、输出在另一条线程」是官方支持的用法。
     * 但 [release] 必须先停掉本线程并等它退出，再释放 codec —— 一边 dequeue 一边 release
     * 会直接把进程搞崩（native 层的互斥量被销毁）。
     */
    private var outputThread: Thread? = null

    @Volatile
    private var outputRunning = false

    /**
     * Initializes the MediaCodec decoder with the video stream parameters from the SDP.
     *
     * This must be called ONCE before any calls to [decodeNalUnit].
     * The parameters (SPS, PPS, width, height) come from the SDP body of the
     * RTSP ANNOUNCE message.
     *
     * What is SPS/PPS?
     *   H.264 requires two special "configuration" NAL units before the first frame:
     *   - SPS (Sequence Parameter Set): describes the video resolution, profile, level
     *   - PPS (Picture Parameter Set): describes encoding parameters for each frame
     *   MediaCodec needs these to configure the hardware decoder correctly.
     *
     * RULE 5: If initialization fails, the exception propagates to the caller
     * (RtspHandler) which will handle it gracefully (log + return to WAITING state).
     *
     * @param spsBytes  The SPS NAL unit bytes (from SDP "sprop-parameter-sets" field)
     * @param ppsBytes  The PPS NAL unit bytes (from SDP "sprop-parameter-sets" field)
     * @param width     Video width in pixels (from SDP)
     * @param height    Video height in pixels (from SDP)
     */
    fun initialize(spsBytes: ByteArray, ppsBytes: ByteArray, width: Int, height: Int) {
        if (isInitialized) {
            Logger.w("VideoDecoder.initialize() called twice — ignoring second call")
            return
        }

        // Try to extract actual resolution from the SPS NAL unit. Keep validating the result and
        // falling back to the hint: a malformed SPS can still yield nonsense, and configuring
        // MediaCodec with that wedges the decoder (no input buffers, no frames). The hardware
        // decoder reads the true size from the SPS (csd-0) itself and reports it via
        // INFO_OUTPUT_FORMAT_CHANGED, which we use to refine the size for aspect-fit.
        val parsed = Companion.parseSpsResolution(spsBytes)
        val (actualWidth, actualHeight) = parsed?.takeIf { isPlausibleSize(it.first, it.second) } ?: run {
            Logger.w("SPS resolution $parsed implausible/failed — using hint ${width}x${height}")
            Pair(width, height)
        }

        Logger.i("Initializing H.264 decoder: ${actualWidth}x${actualHeight} " +
                 "(hint was ${width}x${height})")

        // Provisional size for StreamingScreen aspect-fit; replaced by the decoder's reported size.
        StreamStats.videoWidth = actualWidth
        StreamStats.videoHeight = actualHeight
        StreamStats.videoCodec = MediaFormat.MIMETYPE_VIDEO_AVC

        // Create the MediaFormat that describes the H.264 stream to the hardware decoder
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,  // AVC = Advanced Video Coding = H.264
            actualWidth,
            actualHeight
        ).apply {
            // Provide SPS and PPS so MediaCodec can configure the hardware decoder.
            // These are wrapped in ByteBuffers as required by the MediaCodec API.
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(spsBytes))  // SPS
            setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(ppsBytes))  // PPS
            // NOTE: do NOT set KEY_MAX_WIDTH/HEIGHT here — this SoC's MStar decoder rejects
            // adaptive playback (BadParameter / buffer-count failures) and produces banding.
            // Resolution changes are handled by recreating the decoder in MirrorStreamServer.

            // 低延迟提示。屏幕镜像（AirPlay / Miracast）要的是「解出一帧立刻显示」，
            // 而解码器默认会攒几帧输出缓冲以提高吞吐，那会平白增加上百毫秒延迟。
            // 解码器不认识的 key 会被忽略，所以这里全部设上、按平台各取所需：
            setInteger("low-latency", 1)                        // Android 11+ 标准 key
            setInteger("vendor.qti-ext-dec-low-latency.enable", 1)  // 高通
            setInteger("vendor.low-latency.enable", 1)              // 部分联发科/展锐
            setInteger(MediaFormat.KEY_PRIORITY, 0)            // 0 = 实时任务
        }

        // Create the hardware H.264 decoder.
        // "video/avc" is the MIME type for H.264. Android will pick the best
        // available hardware decoder for this format.
        mediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

        // Configure the decoder:
        // - format: what the input will look like (H.264, width, height, SPS/PPS)
        // - outputSurface: where decoded frames go (directly to screen — no intermediate copy)
        // - crypto: null (we handle decryption before this point, if needed)
        // - flags: 0 (0 = decoder mode; CONFIGURE_FLAG_ENCODE would be for encoding)
        mediaCodec!!.configure(format, outputSurface, null, 0)
        mediaCodec!!.start()

        isInitialized = true
        startOutputThread()
        Logger.i("H.264 decoder initialized successfully")
    }

    /** 启动输出线程：阻塞等待解码完成的帧并立即送显。 */
    private fun startOutputThread() {
        outputRunning = true
        outputThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            while (outputRunning) {
                val codec = mediaCodec ?: break
                try {
                    // 阻塞等待而不是轮询：解码器一有输出就立刻返回，延迟最低
                    val index = codec.dequeueOutputBuffer(bufferInfo, OUTPUT_BUFFER_TIMEOUT_US)
                    when {
                        index >= 0 -> codec.releaseOutputBuffer(index, true)
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                            publishOutputSize(codec.outputFormat)
                        // INFO_TRY_AGAIN_LATER：超时无输出，继续等
                    }
                } catch (e: IllegalStateException) {
                    if (outputRunning) {
                        Logger.e("VideoDecoder output thread: codec in error state", e)
                        isHealthy = false
                    }
                    break
                } catch (e: Exception) {
                    if (outputRunning) Logger.e("VideoDecoder output thread error", e)
                    break
                }
            }
            Logger.d("VideoDecoder output thread exited")
        }, "VideoDecoderOutput").apply {
            priority = Thread.MAX_PRIORITY      // 送显是延迟敏感路径
            start()
        }
    }

    /**
     * Decodes a single H.264 NAL unit and sends it to the display.
     *
     * A NAL unit (Network Abstraction Layer Unit) is the basic building block of H.264.
     * Each RTP packet from AirPlay contains one or more NAL units.
     * Some NAL units are full frames (IDR frames), others are partial updates.
     *
     * PERFORMANCE: This method runs on the IO coroutine dispatcher (network thread).
     * MediaCodec handles the actual decoding on its own internal thread.
     * The decoded frame appears on the Surface without any UI thread involvement.
     *
     * SECURITY: The caller (RtspHandler) is responsible for validating the byte array
     * length before passing it here.
     *
     * @param nalUnit The raw NAL unit bytes (without the RTP header).
     * @param presentationTimeUs Presentation timestamp in microseconds (for A/V sync).
     */
    fun decodeNalUnit(nalUnit: ByteArray, presentationTimeUs: Long) {
        val codec = mediaCodec ?: run {
            Logger.w("decodeNalUnit() called but decoder not initialized")
            return
        }

        try {
            // 输出队列的排空交给专用输出线程（见 startOutputThread），这里只管喂输入。
            // 原先在这里排空会导致每帧要等下一帧到达才送显，白白多一个帧间隔的延迟。

            // Wait for an input buffer. Longer than before: on a modest SoC the decoder can
            // briefly fall behind, and waiting beats dropping (which corrupts the stream).
            val inputBufferIndex = codec.dequeueInputBuffer(INPUT_BUFFER_TIMEOUT_US)

            if (inputBufferIndex >= 0) {
                // We got an input buffer — fill it with the NAL unit bytes
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                inputBuffer.clear()
                inputBuffer.put(nalUnit)

                // Tell MediaCodec: "input buffer [index] is filled with [size] bytes
                // of data with timestamp [presentationTimeUs] — please decode it"
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,                 // offset: start from beginning of buffer
                    nalUnit.size,      // size: how many bytes to decode
                    presentationTimeUs,
                    0                  // flags: 0 = normal frame (not end-of-stream)
                )
            } else {
                // No input buffer available — the decoder is catching up.
                // Drop this NAL unit to avoid building up backlog (prefer low latency).
                Logger.v("VideoDecoder: no input buffer available, dropping NAL unit")
            }
        } catch (e: IllegalStateException) {
            // MediaCodec is now in the error state and cannot recover — flag for recreation.
            Logger.e("VideoDecoder entered error state — will recreate", e)
            isHealthy = false
        } catch (e: Exception) {
            Logger.e("Error decoding NAL unit", e)
        }
    }

    /*
     * 送显策略（原 releaseOutputBuffers 的注释，逻辑已移入输出线程）：
     * 一律 releaseOutputBuffer(index, true) 立即渲染，刻意**不**做定时送显来对齐音画 ——
     * 这个 Surface 的 BufferQueue 只有约 3 帧，任何等待都会迅速反压到解码器，
     * 导致上游队列积压、延迟飙升并丢帧（丢帧即花屏）。
     * 音画对齐靠 AudioStreamServer 保持音频路径低延迟来实现。
     */

    /** Reads the decoder's true display size (honouring the crop rectangle) for StreamingScreen. */
    private fun publishOutputSize(format: MediaFormat) {
        var w = format.getInteger(MediaFormat.KEY_WIDTH)
        var h = format.getInteger(MediaFormat.KEY_HEIGHT)
        if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
            w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1
            h = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        }
        if (isPlausibleSize(w, h)) {
            StreamStats.videoWidth = w
            StreamStats.videoHeight = h
            Logger.i("Video output size ${w}x$h")
        }
    }

    /**
     * Releases all MediaCodec resources.
     *
     * MUST be called when streaming ends (from AirPlayReceiver.onStreamingStopped()
     * or from onDestroy()). Failing to release MediaCodec causes:
     * - Memory leaks (codec buffers are GPU memory, a scarce resource)
     * - The hardware decoder being unavailable to other apps
     *
     * After release(), call initialize() again before using the decoder.
     */
    /**
     * 不重建解码器、直接把输出切到另一块 Surface（API 23+ 的 setOutputSurface）。
     * 手动旋转画面时 SurfaceView ↔ TextureView 来回切靠它：重建解码器要等下一个 IDR，
     * 静止画面下 iPhone 可能好几秒都不发关键帧，期间会一直黑屏。
     * 返回 false 表示不支持或切换失败，调用方应退回重建解码器。
     */
    fun switchOutputSurface(surface: Surface): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val codec = mediaCodec ?: return false
        return try {
            codec.setOutputSurface(surface)
            true
        } catch (e: Exception) {
            Logger.w("VideoDecoder: setOutputSurface failed, caller will rebuild (${e.message})")
            false
        }
    }

    fun release() {
        Logger.d("Releasing VideoDecoder")

        // 必须先停掉输出线程并等它真正退出，再释放 codec。
        // 一边 dequeueOutputBuffer 一边 release 会在 native 层销毁仍在使用的互斥量，
        // 直接 SIGABRT 崩掉整个进程（AudioStreamServer 里踩过同类问题）。
        outputRunning = false
        outputThread?.let { thread ->
            runCatching { thread.join(500) }
            if (thread.isAlive) Logger.w("VideoDecoder output thread did not exit in time")
        }
        outputThread = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Logger.e("Error releasing MediaCodec (non-fatal)", e)
        } finally {
            mediaCodec = null
            isInitialized = false
        }
    }

    /** True if (w, h) look like a real video resolution — rejects parser garbage (e.g. 32x87392). */
    private fun isPlausibleSize(w: Int, h: Int): Boolean = w in 64..8192 && h in 64..8192

    companion object {
        // How long to wait for an input buffer before dropping (microseconds).
        // 100ms — generous enough that the decoder rarely has to drop a NAL unit (which would
        // corrupt the stream), while still bounding stall if the codec is truly wedged.
        // 输出线程每次阻塞等待的上限（微秒）。20ms 比一帧还短，解码器有输出会立刻返回；
        // 超时只是为了让线程有机会检查退出标志，不影响送显延迟。
        private const val OUTPUT_BUFFER_TIMEOUT_US = 20_000L

        private const val INPUT_BUFFER_TIMEOUT_US = 100_000L

        /**
         * Parses the H.264 SPS NAL unit to extract the actual video resolution.
         *
         * WHY: AirPlay SDP does not include explicit width/height fields.
         * The true resolution is encoded inside the SPS (Sequence Parameter Set) NAL unit
         * as `pic_width_in_mbs_minus1` and `pic_height_in_map_units_minus1`.
         * Each macroblock (MB) is 16×16 pixels.
         *
         * Formula:
         *   width  = (pic_width_in_mbs_minus1  + 1) × 16
         *   height = (pic_height_in_map_units_minus1 + 1) × 16
         *
         * Handles Baseline (66), Main (77), and High profile (100+) SPS formats.
         * Returns null (graceful fallback) if the SPS is too short, malformed, or uses
         * an unsupported bitstream feature.
         *
         * Exposed as `internal` for unit testing without an Android runtime.
         *
         * @param sps The raw SPS NAL unit bytes (first byte is the NAL type, 0x67).
         * @return (width, height) in pixels, or null if parsing fails.
         */
        internal fun parseSpsResolution(sps: ByteArray): Pair<Int, Int>? {
            try {
                if (sps.size < 4) return null

                // 所有调用方传进来的 SPS 都带 Annex B 起始码（MirrorStreamServer 传 sc+sps，
                // MiracastVideoRenderer 从码流里抓的也带），必须先跳过起始码再跳过 NAL 头字节。
                //
                // 原先固定 startOffset=1，等于只跳过了起始码的第一个 0x00，之后按位读到的
                // 全是错位数据 —— 上面那句「某些发送端的 SPS 会被误读」其实对所有发送端都成立。
                // Miracast 实测把 1920x1080 解成 190x140，而这个值恰好通过了合法性检查，
                // 于是解码器就按 190x140 去配置解码 1080p 的流。
                val nalStart = when {
                    sps.size >= 4 && sps[0].toInt() == 0 && sps[1].toInt() == 0 &&
                        sps[2].toInt() == 0 && sps[3].toInt() == 1 -> 4
                    sps[0].toInt() == 0 && sps[1].toInt() == 0 && sps[2].toInt() == 1 -> 3
                    else -> 0     // 没有起始码，首字节就是 NAL 头
                }
                if (nalStart + 1 >= sps.size) return null

                val reader = SpsBitReader(sps, startOffset = nalStart + 1)  // 跳过 NAL 头字节（0x67）

                val profileIdc = reader.readBits(8)
                reader.readBits(8)   // constraint flags + 2 reserved zeros
                reader.readBits(8)   // level_idc
                reader.readUe()      // seq_parameter_set_id

                // Default for Baseline/Main profiles per H.264 spec.
                var chromaFormatIdc = 1
                var separateColorPlaneFlag = 0

                // High-profile and variants have extra fields before the common fields.
                val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
                if (profileIdc in highProfiles) {
                    chromaFormatIdc = reader.readUe()
                    if (chromaFormatIdc == 3) {
                        separateColorPlaneFlag = reader.readBits(1)  // separate_colour_plane_flag
                    }
                    reader.readUe()     // bit_depth_luma_minus8
                    reader.readUe()     // bit_depth_chroma_minus8
                    reader.readBits(1)  // qpprime_y_zero_transform_bypass_flag
                    if (reader.readBits(1) == 1) {  // seq_scaling_matrix_present_flag
                        val count = if (chromaFormatIdc != 3) 8 else 12
                        repeat(count) {
                            if (reader.readBits(1) == 1) {  // seq_scaling_list_present_flag[i]
                                reader.skipScalingList(if (it < 6) 16 else 64)
                            }
                        }
                    }
                }

                reader.readUe()   // log2_max_frame_num_minus4
                val picOrderCntType = reader.readUe()
                when (picOrderCntType) {
                    0 -> reader.readUe()  // log2_max_pic_order_cnt_lsb_minus4
                    1 -> {
                        reader.readBits(1)  // delta_pic_order_always_zero_flag
                        reader.readSe()     // offset_for_non_ref_pic
                        reader.readSe()     // offset_for_top_to_bottom_field
                        repeat(reader.readUe()) { reader.readSe() }
                    }
                }

                reader.readUe()     // max_num_ref_frames
                reader.readBits(1)  // gaps_in_frame_num_value_allowed_flag

                val picWidthInMbsMinus1 = reader.readUe()
                val picHeightInMapUnitsMinus1 = reader.readUe()
                val frameMbsOnlyFlag = reader.readBits(1)
                if (frameMbsOnlyFlag == 0) {
                    reader.readBits(1)  // mb_adaptive_frame_field_flag
                }
                reader.readBits(1)  // direct_8x8_inference_flag

                var cropLeft = 0
                var cropRight = 0
                var cropTop = 0
                var cropBottom = 0
                if (reader.readBits(1) == 1) {  // frame_cropping_flag
                    cropLeft = reader.readUe()
                    cropRight = reader.readUe()
                    cropTop = reader.readUe()
                    cropBottom = reader.readUe()
                }

                val codedWidth = (picWidthInMbsMinus1 + 1) * 16
                val codedHeight = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag)

                val chromaArrayType = if (separateColorPlaneFlag == 1) 0 else chromaFormatIdc

                val subWidthC = when (chromaArrayType) {
                    0 -> 1
                    1, 2 -> 2
                    else -> 1
                }
                val subHeightC = when (chromaArrayType) {
                    1 -> 2
                    else -> 1
                }

                val cropUnitX = if (chromaArrayType == 0) 1 else subWidthC
                val cropUnitY = if (chromaArrayType == 0) (2 - frameMbsOnlyFlag) else subHeightC * (2 - frameMbsOnlyFlag)

                val width = codedWidth - (cropLeft + cropRight) * cropUnitX
                val height = codedHeight - (cropTop + cropBottom) * cropUnitY
                if (width <= 0 || height <= 0) return null

                Logger.d("SPS parsed: ${width}x${height} (profile=$profileIdc)")
                return Pair(width, height)

            } catch (e: Exception) {
                Logger.w("SPS resolution parsing failed: ${e.message} — will use hint dimensions")
                return null
            }
        }

        /**
         * Bitstream reader for H.264 NAL units.
         *
         * Reads bits MSB-first and implements unsigned/signed Exp-Golomb coding.
         * Exposed as `internal` for use in tests.
         *
         * @param data        The raw SPS byte array (including NAL type header).
         * @param startOffset Byte offset to start reading from (typically 1 to skip NAL type).
         */
        class SpsBitReader(private val data: ByteArray, startOffset: Int) {
            private var bytePos = startOffset
            private var bitPos  = 7  // MSB first (bit 7 = most significant)

            fun readBit(): Int {
                if (bytePos >= data.size) throw IndexOutOfBoundsException("SPS RBSP underflow")
                val bit = (data[bytePos].toInt() ushr bitPos) and 1
                if (--bitPos < 0) { bitPos = 7; bytePos++ }
                return bit
            }

            fun readBits(n: Int): Int {
                var result = 0
                repeat(n) { result = (result shl 1) or readBit() }
                return result
            }

            /** Reads an unsigned Exp-Golomb code ue(v). */
            fun readUe(): Int {
                var leadingZeros = 0
                while (readBit() == 0) {
                    if (++leadingZeros > 31) throw ArithmeticException("ue(v) overflow")
                }
                return if (leadingZeros == 0) 0
                       else (1 shl leadingZeros) - 1 + readBits(leadingZeros)
            }

            /** Reads a signed Exp-Golomb code se(v). */
            fun readSe(): Int {
                val k = readUe()
                return if (k == 0) 0 else if (k % 2 == 1) (k + 1) / 2 else -(k / 2)
            }

            /** Skips a scaling list of [size] entries (High Profile SPS §7.3.2.1.1.1). */
            fun skipScalingList(size: Int) {
                var lastScale = 8
                var nextScale = 8
                repeat(size) {
                    if (nextScale != 0) {
                        val deltaScale = readSe()
                        nextScale = (lastScale + deltaScale + 256) % 256
                    }
                    lastScale = if (nextScale == 0) lastScale else nextScale
                }
            }
        }
    }
}

