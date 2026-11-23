package top.jast.miracastreceiver.dlna

import android.content.ContextWrapper
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpSoapNamespaceTest {
    private fun response(handler: String, service: String, action: String, header: String = "SOAPAction",
        renderer: DlnaMediaRenderer = DlnaMediaRenderer()): org.w3c.dom.Document {
        val server = UpnpHttpServer(ContextWrapper(null), renderer, "test-device",
            "Test receiver", "Xiaomi", "MIX 2S", "192.168.2.96")
        val output = ByteArrayOutputStream()
        val method = UpnpHttpServer::class.java.getDeclaredMethod(handler,
            OutputStream::class.java, String::class.java, Map::class.java)
        method.isAccessible = true
        method.invoke(server, output, "<InstanceID>0</InstanceID>",
            mapOf(header to "\"urn:schemas-upnp-org:service:$service:1#$action\""))
        val xml = output.toString("UTF-8").substringAfter("\r\n\r\n")
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())
    }

    @Test fun protocolInfoUsesConnectionManagerNamespace() {
        val doc = response("handleConnectionManager", "ConnectionManager", "GetProtocolInfo")
        val elements = doc.getElementsByTagNameNS("urn:schemas-upnp-org:service:ConnectionManager:1", "GetProtocolInfoResponse")
        assertEquals(1, elements.length)
        assertTrue(doc.getElementsByTagName("Sink").item(0).textContent.contains("http-get:*:image/*:*"))
    }

    @Test fun protocolInfoAcceptsCaseInsensitiveActionHeader() {
        for (header in listOf("soapaction", "Soapaction", "SOAPACTION")) {
            val doc = response("handleConnectionManager", "ConnectionManager", "GetProtocolInfo", header)
            assertEquals(header, 1, doc.getElementsByTagNameNS("urn:schemas-upnp-org:service:ConnectionManager:1", "GetProtocolInfoResponse").length)
        }
    }

    @Test fun volumeUsesRenderingControlNamespace() {
        val doc = response("handleRenderingControl", "RenderingControl", "GetVolume")
        assertEquals(1, doc.getElementsByTagNameNS("urn:schemas-upnp-org:service:RenderingControl:1", "GetVolumeResponse").length)
        assertEquals("50", doc.getElementsByTagName("CurrentVolume").item(0).textContent)
    }

    @Test fun transportKeepsItsOwnNamespace() {
        val doc = response("handleAvTransportControl", "AVTransport", "GetTransportInfo")
        assertEquals(1, doc.getElementsByTagNameNS("urn:schemas-upnp-org:service:AVTransport:1", "GetTransportInfoResponse").length)
        assertEquals("NO_MEDIA_PRESENT", doc.getElementsByTagName("CurrentTransportState").item(0).textContent)
    }

    @Test fun releasedSessionReportsStoppedAndNoPlayableMediaToPollingSender() {
        val renderer = DlnaMediaRenderer()
        renderer.setAVTransportURI("http://sender/video.mp4", "old video")
        renderer.play()
        renderer.onPlayerStopped(renderer.getMediaSessionId(), releaseMedia = true)
        fun query(action: String) = response("handleAvTransportControl", "AVTransport", action, renderer = renderer)
        assertEquals("STOPPED", query("GetTransportInfo").getElementsByTagName("CurrentTransportState").item(0).textContent)
        val media = query("GetMediaInfo")
        assertEquals("0", media.getElementsByTagName("NrTracks").item(0).textContent)
        assertEquals("", media.getElementsByTagName("CurrentURI").item(0).textContent)
        val position = query("GetPositionInfo")
        assertEquals("0", position.getElementsByTagName("Track").item(0).textContent)
        assertEquals("", position.getElementsByTagName("TrackURI").item(0).textContent)
        assertEquals("", query("GetCurrentTransportActions").getElementsByTagName("Actions").item(0).textContent)
    }
}
