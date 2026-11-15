package top.jast.miracastreceiver.miracast

import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class WfdRecoveryTest {
    @Test fun establishedSessionCanRequestKeyframeWithoutBreakingRtspFraming() {
        val playerStarted = CountDownLatch(1)
        val context = object : ContextWrapper(null) {
            override fun startActivity(intent: Intent) { playerStarted.countDown() }
        }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { sink ->
                server.accept().use { source ->
                    source.soTimeout = 3000
                    val handler = WfdSessionHandler(context, sink, 19000)
                    val worker = thread { handler.handleSession() }
                    try {
                        fun send(value: String) { source.getOutputStream().apply { write(value.toByteArray()); flush() } }
                        fun parameter(cseq: Int, body: String) = send(
                            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: $cseq\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")
                        parameter(1, "wfd_presentation_URL: rtsp://source/wfd1.0/streamid=0 none\r\n")
                        assertTrue(readMessage(source).startsWith("RTSP/1.0 200 OK"))
                        parameter(2, "wfd_trigger_method: SETUP\r\n")
                        assertTrue(readMessage(source).startsWith("RTSP/1.0 200 OK"))
                        val setup = readMessage(source)
                        assertTrue(setup.startsWith("SETUP rtsp://source/wfd1.0/streamid=0 RTSP/1.0"))
                        fun cseq(value: String) = Regex("CSeq: (\\d+)").find(value)!!.groupValues[1]
                        send("RTSP/1.0 200 OK\r\nCSeq: ${cseq(setup)}\r\nSession: test123\r\n\r\n")
                        val play = readMessage(source)
                        assertTrue(play.startsWith("PLAY "))
                        send("RTSP/1.0 200 OK\r\nCSeq: ${cseq(play)}\r\n\r\n")
                        assertTrue(playerStarted.await(3, TimeUnit.SECONDS))
                        handler.requestKeyFrame()
                        val idr = readMessage(source)
                        assertTrue(idr.startsWith("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n"))
                        assertTrue(idr.contains("Session: test123\r\n"))
                        assertEquals("wfd_idr_request\r\n", idr.substringAfter("\r\n\r\n"))
                        assertTrue(cseq(idr).toInt() > cseq(play).toInt())
                        handler.requestKeyFrame() // throttle: the same loss burst must not flood the phone
                        assertEquals(0, source.getInputStream().available())
                    } finally {
                        handler.close()
                        worker.join(3000)
                        assertFalse(worker.isAlive)
                    }
                }
            }
        }
    }

    private fun readMessage(socket: Socket): String {
        val input = socket.getInputStream()
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val byte = input.read()
            check(byte >= 0)
            header.append(byte.toChar())
        }
        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.toInt() ?: 0
        repeat(length) { header.append(input.read().also { check(it >= 0) }.toChar()) }
        return header.toString()
    }
}
