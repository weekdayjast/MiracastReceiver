package com.weekd.miracastreceiver.airplay

import com.weekd.miracastreceiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * TimingHandler — Responds to Apple NTP timing probes for A/V synchronization.
 *
 * WHY: AirPlay senders periodically send timing probe packets (UDP) so that both
 * sides can calculate network latency and synchronize their clocks. The sender
 * uses the round-trip response to align its RTP presentation timestamps with
 * the receiver's wall clock, achieving A/V drift below 40 ms (NFR-15).
 *
 * PROTOCOL: Apple uses a simplified NTP-over-UDP format (32-byte packets).
 *
 * Timing request layout (sent by the AirPlay sender):
 *   [0]    RTP version flags (0x80)
 *   [1]    type 0xD2 — timing request marker
 *   [2-3]  sequence number (echoed in response)
 *   [4-7]  padding (zeros)
 *   [8-15] reference NTP timestamp  (sender's reference clock)
 *   [16-23] received NTP timestamp  (zeros in request — receiver fills this in response)
 *   [24-31] transmit NTP timestamp  (sender's clock when the packet was sent)
 *
 * Timing response layout (sent by us):
 *   [0]    0x80
 *   [1]    type 0xD3 — timing response marker
 *   [2-3]  echoed sequence number
 *   [4-7]  zeros
 *   [8-15] reference = sender's transmit time from [24-31] of request
 *   [16-23] received = our wall clock at the moment we received the probe
 *   [24-31] transmit = our wall clock when we are sending this response
 *
 * NTP TIMESTAMP FORMAT:
 *   NTP counts seconds from 1900-01-01 (not the Unix epoch of 1970-01-01).
 *   The 64-bit value packs seconds (high 32 bits) + sub-second fraction (low 32 bits).
 *   Converting from Java millis: add NTP_EPOCH_OFFSET for the seconds, scale % 1000 for fraction.
 *
 * [rtpClockOffsetUs] is updated on every probe response. It represents the estimated
 * offset between our local clock and the sender's RTP clock, in microseconds.
 * [AudioPlayer] uses this to keep audio presentation in sync with video.
 *
 * Usage:
 *   val handler = TimingHandler()
 *   handler.start(scope)   // listens on TIMING_PORT (6002) in background
 *   handler.stop()         // closes socket and exits loop
 */
class TimingHandler {

    // The UDP socket — null when not running; @Volatile so stop() is visible to runLoop()
    @Volatile private var socket: DatagramSocket? = null

    /**
     * Estimated offset between our local clock and the sender's RTP timestamp clock,
     * in microseconds. Updated on every received timing probe.
     *
     * AudioPlayer uses this to correct presentation timestamps from the RTP stream,
     * reducing A/V drift. A value of 0 means no probes have been received yet.
     */
    @Volatile
    var rtpClockOffsetUs: Long = 0L
        private set

    /**
     * Starts listening for Apple NTP timing probes on the given UDP [port].
     *
     * Non-blocking — the receive loop runs in a background coroutine.
     * Caller should use [AirPlayReceiver]'s supervisor scope so a crash here
     * does not affect the main RTSP or mDNS components.
     *
     * @param scope Coroutine scope to launch the listener in.
     * @param port  UDP port to listen on. Defaults to [TIMING_PORT] (6002).
     */
    fun start(scope: CoroutineScope, port: Int = TIMING_PORT) {
        scope.launch(Dispatchers.IO) {
            runLoop(this, port)
        }
    }

    /**
     * Stops the timing handler.
     *
     * Closes the UDP socket, which causes the receive loop to exit with an exception
     * (this is the standard way to unblock DatagramSocket.receive()).
     * Safe to call before [start] or multiple times.
     */
    fun stop() {
        try {
            socket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing timing socket (non-fatal)", e)
        }
        socket = null
    }

    // ─── Private: receive loop ────────────────────────────────────────────────

    private fun runLoop(scope: CoroutineScope, port: Int) {
        try {
            // Use a local val to avoid repeated null-checks on the @Volatile field
            val sock = DatagramSocket(port)
            socket = sock
            Logger.i("Timing handler listening on UDP port $port")

            val buf = ByteArray(PACKET_SIZE)
            val packet = DatagramPacket(buf, buf.size)

            var firstProbe = true
            while (scope.isActive) {
                // receive() blocks until a packet arrives or socket is closed
                sock.receive(packet)
                if (firstProbe) {
                    Logger.i("Timing: first probe from ${packet.address?.hostAddress} " +
                        "(${packet.length}B, type 0x${(packet.data[1].toInt() and 0xFF).toString(16)})")
                    firstProbe = false
                }
                val receiveNtp = currentNtpTimestamp()
                handleProbe(packet, receiveNtp)
            }
        } catch (e: Exception) {
            // SocketException thrown when socket.close() is called from stop() — expected
            if (socket != null) {
                Logger.e("Timing handler error (unexpected)", e)
            } else {
                Logger.d("Timing socket closed (expected during shutdown)")
            }
        }
    }

    /**
     * Processes a single Apple NTP timing probe and sends the response.
     *
     * Exposed as `internal` for unit testing without a real UDP socket.
     * Silently ignores packets that are too short or have a wrong type byte.
     *
     * @param packet     The received UDP datagram (must be exactly [PACKET_SIZE] bytes).
     * @param receiveNtp Our wall-clock receive instant as a packed 64-bit NTP timestamp.
     */
    internal fun handleProbe(packet: DatagramPacket, receiveNtp: Long) {
        val data = packet.data
        if (packet.length < PACKET_SIZE) {
            Logger.w("Timing probe too short (${packet.length} bytes) — ignoring")
            return
        }
        if (data[1].toInt() and 0xFF != REQUEST_TYPE) {
            Logger.d("Timing: unknown type 0x${(data[1].toInt() and 0xFF).toString(16)} — ignoring")
            return
        }

        // Sender's transmit NTP time is in bytes [24-31]
        val refSec  = readUint32(data, 24)
        val refFrac = readUint32(data, 28)

        // Build 32-byte response
        val response = ByteArray(PACKET_SIZE)
        response[0] = 0x80.toByte()
        response[1] = RESPONSE_TYPE.toByte()
        response[2] = data[2]  // echo sequence high byte
        response[3] = data[3]  // echo sequence low byte

        // [8-15] reference = sender's transmit time (from request [24-31])
        writeUint32(response, 8,  refSec)
        writeUint32(response, 12, refFrac)

        // [16-23] received = our receive instant
        writeUint32(response, 16, (receiveNtp ushr 32).toInt())
        writeUint32(response, 20, (receiveNtp and 0xFFFFFFFFL).toInt())

        // [24-31] transmit = our current instant (time we send the response)
        val transmitNtp = currentNtpTimestamp()
        writeUint32(response, 24, (transmitNtp ushr 32).toInt())
        writeUint32(response, 28, (transmitNtp and 0xFFFFFFFFL).toInt())

        try {
            socket?.send(DatagramPacket(response, response.size, packet.address, packet.port))
        } catch (e: Exception) {
            Logger.w("Failed to send timing response: ${e.message}")
        }

        // Update clock offset estimate:
        //   offset = our receive time (µs) − sender's send time (µs)
        // A positive offset means our clock is ahead of the sender's.
        val senderSendUs  = ntpToUs(refSec.toLong()  and 0xFFFFFFFFL, refFrac.toLong() and 0xFFFFFFFFL)
        val ourReceiveUs  = ntpToUs(receiveNtp ushr 32,               receiveNtp and 0xFFFFFFFFL)
        rtpClockOffsetUs  = ourReceiveUs - senderSendUs
    }

    // ─── Public companion: constants + pure helpers ───────────────────────────

    companion object {
        /** UDP port the sender probes for clock synchronization. */
        const val TIMING_PORT = 6002

        private const val PACKET_SIZE   = 32
        private const val REQUEST_TYPE  = 0xD2  // Apple timing request marker
        private const val RESPONSE_TYPE = 0xD3  // Apple timing response marker

        /**
         * Seconds between NTP epoch (1900-01-01) and Unix epoch (1970-01-01).
         * Used to convert Java's System.currentTimeMillis() to NTP seconds.
         */
        private const val NTP_EPOCH_OFFSET = 2_208_988_800L

        /**
         * Returns the current wall clock as a packed 64-bit NTP timestamp:
         *   high 32 bits = seconds since NTP epoch (1900-01-01)
         *   low  32 bits = sub-second fraction (0 = 0s, 0xFFFF_FFFF ≈ 1s)
         *
         * Exposed for testing.
         */
        fun currentNtpTimestamp(): Long {
            val millis   = System.currentTimeMillis()
            val seconds  = millis / 1000L + NTP_EPOCH_OFFSET
            val fraction = (millis % 1000L) * 0x1_0000_0000L / 1000L
            return (seconds shl 32) or fraction
        }

        /**
         * Converts a packed NTP timestamp (separate seconds + fraction) to
         * microseconds relative to the Unix epoch (1970-01-01 00:00:00 UTC).
         *
         * @param ntpSeconds  Seconds since NTP epoch (high 32 bits value).
         * @param ntpFraction Sub-second fraction (low 32 bits value, unsigned).
         * @return Microseconds since Unix epoch.
         */
        fun ntpToUs(ntpSeconds: Long, ntpFraction: Long): Long {
            val unixSeconds = ntpSeconds - NTP_EPOCH_OFFSET
            val subUs = ntpFraction * 1_000_000L / 0x1_0000_0000L
            return unixSeconds * 1_000_000L + subUs
        }

        /**
         * Reads a big-endian unsigned 32-bit integer from [data] starting at [offset].
         * Exposed for testing.
         */
        fun readUint32(data: ByteArray, offset: Int): Int =
            ((data[offset    ].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl  8) or
            ( data[offset + 3].toInt() and 0xFF)

        /**
         * Writes a big-endian 32-bit integer [value] into [data] at [offset].
         * Exposed for testing.
         */
        fun writeUint32(data: ByteArray, offset: Int, value: Int) {
            data[offset    ] = (value ushr 24).toByte()
            data[offset + 1] = (value ushr 16).toByte()
            data[offset + 2] = (value ushr  8).toByte()
            data[offset + 3] = value.toByte()
        }
    }
}

