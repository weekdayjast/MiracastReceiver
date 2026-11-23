package top.jast.miracastreceiver.dlna

import android.content.ContextWrapper
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class UpnpStopEventTest {
    @Test fun subscriberReceivesStoppedAndClearedMediaAfterReplacement() {
        val renderer = DlnaMediaRenderer()
        renderer.setAVTransportURI("http://sender/video.mp4", "old video")
        renderer.play()
        val old = renderer.getMediaSessionId()
        val server = UpnpHttpServer(ContextWrapper(null), renderer, "test", "Receiver",
            "Test", "Test", "127.0.0.1", port = 0)
        val socketField = UpnpHttpServer::class.java.getDeclaredField("serverSocket").apply { isAccessible = true }
        server.start()
        try {
            var listener: ServerSocket? = null
            for (attempt in 0..200) {
                listener = socketField.get(server) as? ServerSocket
                if (listener != null) break
                Thread.sleep(10)
            }
            val port = requireNotNull(listener).localPort
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { callback ->
                callback.soTimeout = 4000
                Socket("127.0.0.1", port).use { client ->
                    client.soTimeout = 4000
                    client.getOutputStream().write(("SUBSCRIBE /event/AVTransport HTTP/1.1\r\n" +
                        "HOST: 127.0.0.1:$port\r\nCALLBACK: <http://127.0.0.1:${callback.localPort}/events>\r\n" +
                        "NT: upnp:event\r\nTIMEOUT: Second-300\r\nContent-Length: 0\r\n\r\n").toByteArray())
                    assertTrue(readHttp(client).startsWith("HTTP/1.1 200"))
                }
                fun notification(): String = callback.accept().use { client ->
                    client.soTimeout = 4000
                    val request = readHttp(client)
                    client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    request
                }
                assertTrue(notification().contains("SEQ: 0\r\n"))
                renderer.onPlayerStopped(old, releaseMedia = true)
                renderer.onPlayerPosition(old, 15000, 60000, true)
                server.notifyAvTransportChanged()
                val stopped = notification()
                assertTrue(stopped.contains("SEQ: 1\r\n"))
                val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                val outer = parser.parse(stopped.substringAfter("\r\n\r\n").byteInputStream())
                val change = parser.parse(outer.getElementsByTagName("LastChange").item(0).textContent.byteInputStream())
                fun value(name: String) = (change.getElementsByTagName(name).item(0) as org.w3c.dom.Element).getAttribute("val")
                assertEquals("STOPPED", value("TransportState"))
                assertEquals("", value("AVTransportURI"))
                assertEquals("", value("CurrentTrackMetaData"))
                assertEquals("0", value("NumberOfTracks"))
                assertEquals("", value("CurrentTransportActions"))
            }
        } finally { server.stop() }
    }

    private fun readHttp(socket: Socket): String {
        val input = socket.getInputStream()
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val byte = input.read()
            check(byte >= 0)
            header.append(byte.toChar())
        }
        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.toInt() ?: 0
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            check(read > 0)
            offset += read
        }
        return header.toString() + body.toString(Charsets.UTF_8)
    }
}
