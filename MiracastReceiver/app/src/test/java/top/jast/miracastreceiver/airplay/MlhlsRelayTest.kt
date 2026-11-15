package top.jast.miracastreceiver.airplay

import org.junit.Assert.*
import org.junit.Test

class MlhlsRelayTest {
    @Test fun fetchesMediaPlaylistBeforeServingMasterAndReusesFinishedPlaylist() {
        val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
        lateinit var relay: MlhlsRelay
        relay = MlhlsRelay { event ->
            val request = event["request"] as Map<*, *>
            val url = request["FCUP_Response_URL"] as String
            requested.add(url.substringAfterLast('/'))
            val manifest = if (url.endsWith("/master.m3u8")) {
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvideo.m3u8\n"
            } else {
                "#EXTM3U\n#EXTINF:5,\nhttps://example.com/segment.ts\n#EXT-X-ENDLIST\n"
            }
            relay.action(mapOf("type" to "unhandledURLResponse", "params" to mapOf(
                "FCUP_Response_RequestID" to request["FCUP_Response_RequestID"],
                "FCUP_Response_Data" to manifest.toByteArray(),
            )))
        }
        try {
            val masterUrl = relay.play("mlhls://localhost/master.m3u8", "test-session")
            val master = java.net.URL(masterUrl).readText()
            assertEquals(listOf("master.m3u8", "video.m3u8"), requested.toList())
            val mediaUrl = master.lineSequence().first { it.startsWith("http://") }
            assertTrue(java.net.URL(mediaUrl).readText().contains("segment.ts"))
            assertEquals(2, requested.size)
        } finally { relay.close() }
    }

    @Test fun permitsLargeActionWithoutIncreasingTheGeneralRequestLimit() {
        val reader = RtspRequestReader(65536, PhotoHandler.MAX_PHOTO_BYTES)
        val body = ByteArray(383079)
        fun request(path: String) =
            ("POST $path HTTP/1.1\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body).inputStream()
        assertArrayEquals(body, reader.read(request("/action"))?.bodyBytes)
        assertNull(reader.read(request("/play")))
    }

    @Test fun acceptsLargeArtworkWithoutIncreasingTheLimitForOtherParameters() {
        val reader = RtspRequestReader(65536, PhotoHandler.MAX_PHOTO_BYTES)
        val body = ByteArray(69090)
        fun request(contentType: String) = (
            "SET_PARAMETER rtsp://receiver/session RTSP/1.0\r\n" +
                "Content-Type: $contentType\r\nContent-Length: ${body.size}\r\n\r\n"
            ).toByteArray() + body
        assertArrayEquals(body, reader.read(request("image/jpeg").inputStream())?.bodyBytes)
        assertNull(reader.read(request("text/parameters").inputStream()))
    }

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
