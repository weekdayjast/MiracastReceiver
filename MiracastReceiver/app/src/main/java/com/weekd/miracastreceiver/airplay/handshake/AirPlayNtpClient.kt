package com.weekd.miracastreceiver.airplay.handshake

import com.weekd.miracastreceiver.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * AirPlayNtpClient — the receiver side of AirPlay 2 NTP timing.
 *
 * Unlike legacy AirPlay (where the sender probes the receiver), AirPlay 2 mirroring requires
 * the RECEIVER to actively poll the sender's timing port. macOS waits for this timing exchange
 * to begin before it will send the video stream SETUP, so without it the session stalls right
 * after the key exchange.
 *
 * We open a local UDP socket (its port is returned as `timingPort` in the SETUP response) and
 * periodically send a 32-byte NTP request to the sender, draining the replies. Precise clock
 * sync isn't required to start mirroring (frames render on arrival), so we keep this minimal —
 * the goal is to satisfy macOS that timing is live.
 *
 * Reference: RPiPlay lib/raop_ntp.c (raop_ntp_thread).
 */
class AirPlayNtpClient(
    private val remoteAddress: InetAddress,
    private val remoteTimingPort: Int,
) {
    private val socket = DatagramSocket()      // OS-assigned local port
    @Volatile private var running = false

    /** Local UDP port to advertise to macOS as the receiver's timingPort. */
    val localPort: Int get() = socket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        socket.soTimeout = RECV_TIMEOUT_MS
        scope.launch(Dispatchers.IO) { loop() }
        Logger.i("NTP client → [$remoteAddress]:$remoteTimingPort, local timing port $localPort")
    }

    fun stop() {
        running = false
        runCatching { socket.close() }
    }

    private fun loop() {
        // request: [0]=0x80 (RTP), [1]=0xd2 (timing request), [2-3]=seq, [24-31]=send NTP time.
        val request = ByteArray(32)
        request[0] = 0x80.toByte()
        request[1] = 0xD2.toByte()
        request[3] = 0x07
        val response = ByteArray(128)
        var first = true
        var rxCount = 0
        while (running) {
            try {
                putNtpTimestamp(request, 24, System.currentTimeMillis())
                socket.send(DatagramPacket(request, request.size, remoteAddress, remoteTimingPort))
                if (first) { Logger.i("NTP: first timing request sent to macOS"); first = false }
                try {
                    val rx = DatagramPacket(response, response.size)
                    socket.receive(rx)
                    if (rxCount < 4) {
                        Logger.i("NTP RX[$rxCount] ${rx.length}B type=0x${(response[1].toInt() and 0xFF).toString(16)}: " +
                            (0 until minOf(rx.length, 32)).joinToString(" ") { "%02x".format(response[it]) })
                        rxCount++
                    }
                } catch (_: Exception) { /* SO_RCVTIMEO — fine, retry next tick */ }
            } catch (e: Exception) {
                if (running) Logger.e("NTP client send error", e)
            }
            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { return }
        }
    }

    /** Writes a 64-bit NTP timestamp (seconds since 1900 + 32-bit fraction) big-endian. */
    private fun putNtpTimestamp(buf: ByteArray, off: Int, epochMillis: Long) {
        val seconds = epochMillis / 1000 + NTP_EPOCH_OFFSET
        val fraction = (epochMillis % 1000) * (1L shl 32) / 1000
        writeUint32(buf, off, seconds)
        writeUint32(buf, off + 4, fraction)
    }

    private fun writeUint32(buf: ByteArray, off: Int, value: Long) {
        buf[off] = (value ushr 24).toByte()
        buf[off + 1] = (value ushr 16).toByte()
        buf[off + 2] = (value ushr 8).toByte()
        buf[off + 3] = value.toByte()
    }

    companion object {
        private const val NTP_EPOCH_OFFSET = 2208988800L   // seconds between 1900 and 1970
        private const val POLL_INTERVAL_MS = 2000L
        private const val RECV_TIMEOUT_MS = 1000
    }
}

