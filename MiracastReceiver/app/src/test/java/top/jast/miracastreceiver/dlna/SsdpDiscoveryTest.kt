package top.jast.miracastreceiver.dlna

import android.content.ContextWrapper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class SsdpDiscoveryTest {
    private val uuid = "00000000-0000-0000-0000-000000000024"
    private val renderer = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private val expected = setOf("upnp:rootdevice", "uuid:$uuid", renderer,
        "urn:schemas-upnp-org:service:AVTransport:1",
        "urn:schemas-upnp-org:service:RenderingControl:1",
        "urn:schemas-upnp-org:service:ConnectionManager:1")

    private fun search(target: String): List<Map<String, String>> {
        val server = SsdpServer(ContextWrapper(null), uuid, "192.168.2.96")
        val loopback = InetAddress.getByName("127.0.0.1")
        return DatagramSocket(0, loopback).use { socket ->
            socket.soTimeout = 200
            val method = SsdpServer::class.java.getDeclaredMethod("handleSearchRequest",
                String::class.java, InetAddress::class.java, Int::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(server, "M-SEARCH * HTTP/1.1\r\nst: $target\r\n\r\n", loopback, socket.localPort)
            val responses = mutableListOf<Map<String, String>>()
            while (true) {
                val packet = DatagramPacket(ByteArray(4096), 4096)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
                val response = String(packet.data, 0, packet.length)
                assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
                assertTrue(response.endsWith("\r\n\r\n"))
                responses += response.split("\r\n").drop(1).filter { it.contains(':') }
                    .associate { it.substringBefore(':') to it.substringAfter(':').trim() }
            }
            responses
        }
    }

    @Test fun allSearchReturnsSixConcreteAdvertisements() {
        val responses = search("ssdp:all")
        assertEquals(6, responses.size)
        assertEquals(expected, responses.map { it.getValue("ST") }.toSet())
        for (response in responses) {
            val target = response.getValue("ST")
            assertEquals(if (target == "uuid:$uuid") target else "uuid:$uuid::$target", response["USN"])
            assertEquals("http://192.168.2.96:8080/device.xml", response["LOCATION"])
        }
    }

    @Test fun rendererSearchKeepsExistingSingleResponse() {
        val responses = search(renderer)
        assertEquals(1, responses.size)
        assertEquals(renderer, responses.single()["ST"])
    }

    @Test fun uuidSearchHasNoTypeSuffix() {
        assertEquals("uuid:$uuid", search("uuid:$uuid").single()["USN"])
    }

    @Test fun unsupportedServicesAreNotAdvertised() {
        assertTrue(search("urn:schemas-upnp-org:service:ContentDirectory:1").isEmpty())
        assertTrue(search("urn:schemas-upnp-org:device:MediaRenderer:2").isEmpty())
    }
}
