package com.weekd.miracastreceiver.airplay.handshake

import com.weekd.miracastreceiver.util.Logger
import java.io.Closeable

/**
 * AlacDecoder — software Apple Lossless (ALAC) decoder for macOS system-audio AirPlay.
 *
 * macOS streams system audio (Chrome, Music, anything routed through "Sound output") as ALAC
 * (codec type 2), no matter what `/info` advertises. This TV has no hardware ALAC codec, so we
 * decode in software via Apple's open-source decoder compiled into `libalac.so` (see
 * cpp/alac_jni.cpp). One decrypted ALAC frame → 16-bit interleaved little-endian PCM.
 *
 * Lifecycle mirrors a MediaCodec: construct once per stream, [decode] per frame on a single
 * thread, [close] when the stream ends. Not thread-safe — the audio playback thread owns it.
 */
class AlacDecoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val frameLength: Int,
) : Closeable {

    /** Native ALACDecoder pointer; 0 until [init] succeeds, back to 0 after [close]. */
    private var handle: Long = 0

    /** Reused PCM output buffer sized for a full frame (frameLength * channels * 2 bytes). */
    private val pcmOut = ByteArray(frameLength * channels * 2)

    init {
        ensureNativeLoaded()
        val cookie = buildMagicCookie(sampleRate, channels, frameLength)
        handle = nativeInit(cookie)
        require(handle != 0L) {
            "ALACDecoder.Init failed for ${sampleRate}Hz/${channels}ch/spf=$frameLength"
        }
        Logger.i("ALAC decoder ready: ${sampleRate}Hz, ${channels}ch, frameLength=$frameLength")
    }

    /**
     * Decodes one decrypted ALAC frame to 16-bit interleaved PCM.
     *
     * @return a fresh ByteArray of PCM bytes, or null on decode error / after [close]. The size is
     *   the actual decoded sample count, which may be a short final frame.
     */
    fun decode(frame: ByteArray, length: Int = frame.size): ByteArray? {
        if (handle == 0L) return null
        val n = nativeDecode(handle, frame, length, pcmOut)
        if (n <= 0) return null
        return pcmOut.copyOf(n)
    }

    override fun close() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0
        }
    }

    private external fun nativeInit(magicCookie: ByteArray): Long
    private external fun nativeDecode(handle: Long, input: ByteArray, inputLen: Int, output: ByteArray): Int
    private external fun nativeRelease(handle: Long)

    companion object {
        @Volatile private var nativeLoaded = false

        private fun ensureNativeLoaded() {
            if (!nativeLoaded) synchronized(this) {
                if (!nativeLoaded) { System.loadLibrary("alac"); nativeLoaded = true }
            }
        }

        // Tuning parameters macOS uses for AirPlay realtime ALAC (SDP fmtp
        // "<spf> 0 16 40 10 14 2 255 0 0 44100"). They must match the encoder, and AirPlay fixes
        // them — only spf/bitDepth/channels/sampleRate vary, and only spf in practice.
        private const val COMPATIBLE_VERSION = 0
        private const val BIT_DEPTH = 16
        private const val PB = 40
        private const val MB = 10
        private const val KB = 14
        private const val MAX_RUN = 255

        /**
         * Builds the 24-byte big-endian ALACSpecificConfig "magic cookie" the native decoder's
         * Init() expects (ALACAudioTypes.h struct layout). Visible for testing.
         */
        fun buildMagicCookie(sampleRate: Int, channels: Int, frameLength: Int): ByteArray {
            val c = ByteArray(24)
            putBE32(c, 0, frameLength)        // frameLength
            c[4] = COMPATIBLE_VERSION.toByte() // compatibleVersion
            c[5] = BIT_DEPTH.toByte()          // bitDepth
            c[6] = PB.toByte()                 // pb
            c[7] = MB.toByte()                 // mb
            c[8] = KB.toByte()                 // kb
            c[9] = channels.toByte()           // numChannels
            putBE16(c, 10, MAX_RUN)            // maxRun
            putBE32(c, 12, 0)                  // maxFrameBytes (0 = unknown)
            putBE32(c, 16, 0)                  // avgBitRate (0 = unknown)
            putBE32(c, 20, sampleRate)         // sampleRate
            return c
        }

        private fun putBE16(b: ByteArray, off: Int, v: Int) {
            b[off] = (v ushr 8).toByte()
            b[off + 1] = v.toByte()
        }

        private fun putBE32(b: ByteArray, off: Int, v: Int) {
            b[off] = (v ushr 24).toByte()
            b[off + 1] = (v ushr 16).toByte()
            b[off + 2] = (v ushr 8).toByte()
            b[off + 3] = v.toByte()
        }
    }
}

