package top.jast.miracastreceiver.miracast

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket

class WfdVideoCapabilitiesTest {
    @Test fun nativeResolutionAndRotationAreDynamic() {
        val landscape = requireNotNull(chooseWfdCustomVideoMode(1080, 2160, false, true) { _, _, _, _ -> true })
        val portrait = requireNotNull(chooseWfdCustomVideoMode(1080, 2160, true, true) { _, _, _, _ -> true })
        assertEquals(2160 to 1080, landscape.width to landscape.height)
        assertEquals(1080 to 2160, portrait.width to portrait.height)
        assertEquals("0438 0870 003C", portrait.customFormat())
        assertEquals(0x20, portrait.levelBit)
        assertEquals(1440 to 3120, requireNotNull(chooseWfdCustomVideoMode(1440, 3120, true, true)
            { _, _, _, _ -> true }).let { it.width to it.height })
    }

    @Test fun legacyCodecLimitsTriggerProportionalFallback() {
        val mode = requireNotNull(chooseWfdCustomVideoMode(1080, 2160, true, false) { _, _, _, _ -> true })
        assertEquals(960 to 1920, mode.width to mode.height)
        assertEquals(0x10, mode.levelBit)
    }

    @Test fun hardwareRateAndSizeLimitsAreRespected() {
        val mode = requireNotNull(chooseWfdCustomVideoMode(3840, 2160, false, true) { w, h, fps, _ ->
            w <= 1920 && h <= 1080 && fps <= 30
        })
        assertEquals(1920 to 1080, mode.width to mode.height)
        assertEquals(30, mode.fps)
        assertNull(chooseWfdCustomVideoMode(3840, 2160, false, true) { _, _, _, _ -> false })
    }

    @Test fun codecLevelAccountsForMacroblocksAndFrameRate() {
        assertEquals(0x10, wfdAvcLevel(1920, 1080, 60)?.first)
        assertEquals(0x20, wfdAvcLevel(2160, 1080, 60)?.first)
        assertEquals(0x40, wfdAvcLevel(3840, 2160, 30)?.first)
        assertEquals(0x80, wfdAvcLevel(3840, 2160, 60)?.first)
    }

    @Test fun extensionFieldsHaveTheSpecifiedWidths() {
        val fields = WfdCustomVideoMode(2160, 1080, 60, 0x20).extendedFormats().split(' ')
        assertEquals(4, fields[0].length)
        assertEquals(4, fields[2].length)
        assertEquals(4, fields[3].length)
        assertEquals(10, fields[4].length)
        assertEquals(10, fields[5].length)
        assertEquals("00000001C0", fields[4])
    }

    @Test fun unsupportedExtensionsAreNeverSentToLegacyPeers() {
        val mode = WfdCustomVideoMode(1080, 2160, 60, 0x20)
        val legacy = wfdVideoCapabilityLines(setOf("wfd_video_formats"), mode)
        assertEquals(1, legacy.size)
        assertTrue(legacy.single().startsWith("wfd_video_formats:"))
        assertFalse(legacy.single().contains("microsoft"))
        assertEquals("microsoft_custom_video_formats: none",
            wfdVideoCapabilityLines(setOf("microsoft_custom_video_formats"), mode).last())
    }

    @Test fun selectedCustomModeMustMatchTheAdvertisedSizeAndRate() {
        val mode = WfdCustomVideoMode(1080, 2160, 60, 0x20)
        assertTrue(matchesWfdCustomMode("0438 0870 003c", mode))
        assertFalse(matchesWfdCustomMode("0870 0438 003C", mode))
        assertFalse(matchesWfdCustomMode("0438 0870 001E", mode))
        assertFalse(matchesWfdCustomMode("1080 2160", mode))
    }

    private class WireSocket(input: String) : Socket() {
        val replies = ByteArrayOutputStream()
        private val requests = ByteArrayInputStream(input.toByteArray(Charsets.UTF_8))
        private var closed = false
        override fun getInputStream() = requests
        override fun getOutputStream() = replies
        override fun getInetAddress(): InetAddress = InetAddress.getLoopbackAddress()
        override fun isClosed() = closed
        override fun setSoTimeout(timeout: Int) = Unit
        override fun close() { closed = true }
    }

    private fun getParameters(names: String): String =
        "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\nContent-Length: ${names.toByteArray().size}\r\n\r\n$names"

    @Test fun actualM3ResponseAdvertisesCustomPortraitModeAndCorrectByteLength() {
        val request = "wfd_video_formats\r\nwfdx_video_formats\r\nmicrosoft_custom_video_formats\r\n"
        val socket = WireSocket(getParameters(request))
        WfdSessionHandler(ContextWrapper(null), socket, 19000) { extended ->
            assertTrue(extended)
            WfdCustomVideoMode(1080, 2160, 60, 0x20)
        }.handleSession()
        val response = socket.replies.toString("UTF-8")
        val body = response.substringAfter("\r\n\r\n")
        assertTrue(body.contains("microsoft_custom_video_formats: 0438 0870 003C\r\n"))
        assertTrue(body.contains("wfdx_video_formats: 0040 00 0002 0020"))
        assertTrue(body.contains("wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000"))
        assertTrue(response.contains("Content-Length: ${body.toByteArray().size}\r\n"))
    }

    @Test fun legacyM3DoesNotEvenQueryCustomHardwareCapabilities() {
        val socket = WireSocket(getParameters("wfd_video_formats\r\nwfd_client_rtp_ports\r\n"))
        WfdSessionHandler(ContextWrapper(null), socket, 19000) { error("Unexpected extension query") }.handleSession()
        val response = socket.replies.toString("UTF-8")
        assertTrue(response.contains("wfd_video_formats:"))
        assertFalse(response.contains("wfdx_video_formats:"))
        assertFalse(response.contains("microsoft_custom_video_formats:"))
    }

    @Test fun m4RejectsAnUnadvertisedCustomResolution() {
        val body = "microsoft_custom_video_formats: 0438 0870 003C\r\n"
        val socket = WireSocket("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 4\r\nContent-Length: ${body.length}\r\n\r\n$body")
        WfdSessionHandler(ContextWrapper(null), socket, 19000).handleSession()
        assertTrue(socket.replies.toString("UTF-8").startsWith("RTSP/1.0 400 Bad Request"))
    }
}
