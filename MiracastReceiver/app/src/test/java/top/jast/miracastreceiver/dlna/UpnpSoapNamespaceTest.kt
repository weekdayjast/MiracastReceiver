package top.jast.miracastreceiver.dlna

import android.content.ContextWrapper
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpSoapNamespaceTest {
    private fun response(handler: String, service: String, action: String, header: String = "SOAPAction"): org.w3c.dom.Document {
        val server = UpnpHttpServer(ContextWrapper(null), DlnaMediaRenderer(), "test-device",
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
}
