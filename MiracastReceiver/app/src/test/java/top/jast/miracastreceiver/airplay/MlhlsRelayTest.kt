package top.jast.miracastreceiver.airplay

import org.junit.Assert.*
import org.junit.Test

class MlhlsRelayTest {
    @Test fun servesSenderResponseOverLoopback() {
        lateinit var relay: MlhlsRelay
        relay = MlhlsRelay { event ->
            assertEquals("unhandledURLRequest", event["type"])
            val request = event["request"] as Map<*, *>
            val headers = request["FCUP_Response_Headers"] as Map<*, *>
            assertEquals("test-session", headers["X-Playback-Session-Id"])
            relay.action(mapOf("type" to "unhandledURLResponse", "params" to mapOf(
                "FCUP_Response_RequestID" to request["FCUP_Response_RequestID"],
                "FCUP_Response_StatusCode" to 0,
                "FCUP_Response_Data" to "#EXTM3U\n#EXTINF:5,\nhttps://example.com/segment.ts\n".toByteArray()
            )))
        }
        try {
            val url = relay.play("mlhls://localhost/master.m3u8", "test-session")
            val connection = java.net.URL(url).openConnection().apply { readTimeout = 3000 }
            val data = connection.getInputStream().bufferedReader().use { it.readText() }
            assertTrue(data.contains("https://example.com/segment.ts"))
            relay.reset()
            val expired = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            assertEquals(502, expired.responseCode)
            expired.disconnect()
        } finally { relay.close() }
    }

    @Test fun rewritesRelativeAndQuotedManifestUris() {
        val relay = MlhlsRelay { }
        try {
            val result = relay.rewrite("mlhls://localhost/master.m3u8",
                "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio/main.m3u8\"\nvideo/main.m3u8\n")
            assertFalse(result.contains("mlhls://"))
            assertTrue(result.contains("mlhls%3A%2F%2Flocalhost%2Faudio%2Fmain.m3u8"))
            assertTrue(result.contains("mlhls%3A%2F%2Flocalhost%2Fvideo%2Fmain.m3u8"))
        } finally { relay.close() }
    }

    @Test fun expandsYouTubeCondensedSegments() {
        val relay = MlhlsRelay { }
        try {
            val result = relay.rewrite("mlhls://localhost/itag/1/main.m3u8",
                "#EXTM3U\n#YT-EXT-CONDENSED-URL:BASE-URI=\"https://media.example/video\",PARAMS=\"sq,range\",PREFIX=\"s/\"\n#EXTINF:5,\ns/12/100-200\n#EXT-X-ENDLIST\n")
            assertTrue(result.contains("https://media.example/video/sq/12/range/100-200"))
            assertFalse(result.contains("#YT-EXT"))
            assertTrue(result.contains("#EXT-X-ENDLIST"))
        } finally { relay.close() }
    }
}
