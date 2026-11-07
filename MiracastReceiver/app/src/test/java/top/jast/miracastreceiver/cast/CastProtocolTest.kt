package top.jast.miracastreceiver.cast

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import top.jast.miracastreceiver.cast.CastWire.bytes
import top.jast.miracastreceiver.cast.CastWire.integer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.Signature

class CastProtocolTest {
    @Test fun wireReadsAnIndependentProtobufFixtureAndSkipsUnknownFields() {
        // version=0, source=s, destination=d, namespace=n, type=STRING, payload={}.
        val fixture = byteArrayOf(8, 0, 18, 1, 115, 26, 1, 100, 34, 1, 110, 40, 0, 50, 2, 123, 125,
            80, 123, 90, 2, 1, 2)
        val framed = ByteArrayOutputStream().apply {
            DataOutputStream(this).writeInt(fixture.size); write(fixture)
        }
        assertEquals(CastMessage("s", "d", "n", "{}"), CastWire.read(framed.toByteArray().inputStream()))
    }

    @Test fun binaryFramesRoundTripWithoutConvertingToText() {
        val expected = byteArrayOf(0, -1, 7, -128)
        val output = ByteArrayOutputStream()
        CastWire.write(output, CastMessage("sender-0", "receiver-0", CastWire.AUTH, binary = expected))
        val read = CastWire.read(output.toByteArray().inputStream())
        assertArrayEquals(expected, read.binary)
        assertNull(read.text)
    }

    @Test fun malformedFramesAreRejectedBeforeAllocationOrDispatch() {
        listOf(-1, 0, CastWire.MAX_FRAME + 1).forEach { size ->
            val framed = ByteArrayOutputStream().apply { DataOutputStream(this).writeInt(size) }
            assertThrows(IllegalArgumentException::class.java) { CastWire.read(framed.toByteArray().inputStream()) }
        }
        assertThrows(IllegalArgumentException::class.java) { CastWire.fields(byteArrayOf(18, 127, 1)) }
        assertThrows(IllegalArgumentException::class.java) { CastWire.fields(ByteArray(12) { -1 }) }
    }

    private class Harness {
        val loads = mutableListOf<CastLoad>()
        val commands = mutableListOf<CastCommand>()
        val session = CastSession({ loads.add(it) }, { commands.add(it) })
        var transport = ""
        fun send(ns: String, data: String, client: String = "client", destination: String = "receiver-0"): JsonObject? {
            val replies = session.handle(client, CastMessage("sender-0", destination, ns, data))
            return replies.firstOrNull()?.text?.let { JsonParser.parseString(it).asJsonObject }
        }
        fun launch(client: String = "client") {
            send(CastWire.CONNECTION, """{"type":"CONNECT"}""", client)
            val response = send(CastWire.RECEIVER, """{"type":"LAUNCH","appId":"CC1AD845","requestId":7}""", client)!!
            assertEquals(7, response.get("requestId").asInt)
            transport = response.getAsJsonObject("status").getAsJsonArray("applications")[0].asJsonObject.get("transportId").asString
            send(CastWire.CONNECTION, """{"type":"CONNECT"}""", client, transport)
        }
        fun media(json: String, client: String = "client") = send(CastWire.MEDIA, json, client, transport)!!
        fun load(url: String = "http://192.168.1.2/movie.mp4") = media("""{"type":"LOAD","requestId":8,"autoplay":false,"currentTime":12.5,"media":{"contentId":"$url","contentType":"video/mp4"}}""")
        fun status() = media("""{"type":"GET_STATUS"}""").getAsJsonArray("status")[0].asJsonObject
    }

    @Test fun mediaFlowPreservesAutoplayStartTimeAndReportsRealPlayerState() {
        val h = Harness(); h.launch()
        val loadResponse = h.load()
        assertEquals(8, loadResponse.get("requestId").asInt)
        val load = h.loads.single()
        assertFalse(load.autoplay); assertEquals(12500L, load.positionMs)
        assertEquals("BUFFERING", h.status().get("playerState").asString)
        h.session.update(CastPlaybackState(load.token, "PAUSED", 12500, 90000))
        assertEquals("PAUSED", h.status().get("playerState").asString)
        h.media("""{"type":"PLAY","mediaSessionId":1}""")
        h.media("""{"type":"SEEK","mediaSessionId":1,"currentTime":30,"resumeState":"PLAYBACK_PAUSE"}""")
        assertEquals(listOf("PLAY", "SEEK", "PAUSE"), h.commands.map { it.type })
        assertEquals(30000.0, h.commands[1].value, 0.0)
        h.session.update(CastPlaybackState(load.token, "IDLE", 90000, 90000, "FINISHED"))
        assertEquals("FINISHED", h.status().get("idleReason").asString)
        assertEquals("sender-0", h.session.updates("client").single().destination)
        assertEquals(h.transport, h.session.updates("client").single().source)
    }

    @Test fun staleStatesAndWrongSessionsCannotControlNewMedia() {
        val h = Harness(); h.launch(); h.load()
        val old = h.loads.single().token
        h.load("http://192.168.1.2/next.mp4")
        h.session.update(CastPlaybackState(old, "IDLE", idleReason = "ERROR"))
        assertEquals("BUFFERING", h.status().get("playerState").asString)
        assertEquals("INVALID_REQUEST", h.media("""{"type":"STOP","mediaSessionId":1}""").get("type").asString)
        assertTrue(h.commands.isEmpty())
        assertEquals("INVALID_REQUEST", h.media("""{"type":"PLAY","mediaSessionId":2}""", "other").get("type").asString)
    }

    @Test fun vlcStringAutoplayUsesDefaultWhileExplicitBooleanFalseStaysPaused() {
        val h = Harness(); h.launch()
        for ((autoplay, expected) in listOf("\"false\"" to true, "null" to true, "false" to false, "true" to true)) {
            h.media("""{"type":"LOAD","autoplay":$autoplay,"media":{"contentId":"http://192.168.1.2/video.mp4","contentType":"video/mp4"}}""")
            assertEquals("autoplay=$autoplay", expected, h.loads.last().autoplay)
        }
        h.media("""{"type":"LOAD","media":{"contentId":"http://192.168.1.2/video.mp4","contentType":"video/mp4"}}""")
        assertTrue(h.loads.last().autoplay)
    }

    @Test fun onlyDefaultMediaAppAndRemoteHttpMediaAreAdvertised() {
        val h = Harness()
        val availability = h.send(CastWire.RECEIVER,
            """{"type":"GET_APP_AVAILABILITY","appId":["CC1AD845","YouTube"]}""")!!.getAsJsonObject("availability")
        assertEquals("APP_AVAILABLE", availability.get("CC1AD845").asString)
        assertEquals("APP_UNAVAILABLE", availability.get("YouTube").asString)
        assertEquals("LAUNCH_ERROR", h.send(CastWire.RECEIVER, """{"type":"LAUNCH","appId":"YouTube"}""")!!.get("type").asString)
        h.launch()
        assertEquals("LOAD_FAILED", h.load("file:///data/data/private").get("type").asString)
        assertTrue(h.loads.isEmpty())
        h.load()
        assertEquals("INVALID_REQUEST", h.media("""{"type":"SEEK","mediaSessionId":1,"currentTime":-1}""").get("type").asString)
        h.session.update(CastPlaybackState(h.loads.single().token, "IDLE", idleReason = "ERROR"))
        assertEquals("ERROR", h.status().get("idleReason").asString)
    }

    @Test fun muteRestoresVolumeAndLastTransportDisconnectStopsOnlyOnce() {
        val h = Harness(); h.launch(); h.load()
        h.send(CastWire.RECEIVER, """{"type":"SET_VOLUME","volume":{"level":0.4}}""")
        h.send(CastWire.RECEIVER, """{"type":"SET_VOLUME","volume":{"muted":true}}""")
        h.send(CastWire.RECEIVER, """{"type":"SET_VOLUME","volume":{"muted":false}}""")
        h.session.update(CastPlaybackState(h.loads.single().token, "PAUSED", volume = 0f))
        assertEquals(0.4, h.status().getAsJsonObject("volume").get("level").asDouble, 0.001)
        h.session.update(CastPlaybackState(h.loads.single().token, "PAUSED", volume = 0.4f))
        assertEquals(listOf(0.4, 0.0, 0.4), h.commands.map { Math.round(it.value * 10) / 10.0 })
        h.send(CastWire.CONNECTION, """{"type":"CONNECT"}""", "second", h.transport)
        h.session.disconnect("client")
        assertFalse(h.commands.any { it.type == "STOP" })
        h.session.disconnect("second")
        h.session.disconnect("second")
        assertEquals(1, h.commands.count { it.type == "STOP" })
    }

    @Test fun deviceAuthenticationBindsNonceToTheActualTlsCertificate() {
        val identity = CastIdentity()
        val nonce = ByteArray(32) { it.toByte() }
        val challenge = ByteArrayOutputStream().apply {
            bytes(1, ByteArrayOutputStream().apply { integer(1, 1); bytes(2, nonce); integer(3, 1) }.toByteArray())
        }.toByteArray()
        val response = CastWire.fields(CastWire.fields(identity.authenticate(challenge))[2]!!.single())
        assertArrayEquals(nonce, response[5]!!.single())
        assertArrayEquals(identity.certificate.encoded, response[2]!!.single())
        assertTrue(Signature.getInstance("SHA256withRSA").run {
            initVerify(identity.certificate); update(nonce + identity.certificate.encoded)
            verify(response[1]!!.single())
        })
        identity.certificate.checkValidity()
        assertTrue(identity.certificate.notAfter.time - System.currentTimeMillis() < 4 * 86400000L)
    }

    @Test fun receiverExitNotifiesEveryClientAndClosesTheAppExactlyOnce() {
        val h = Harness(); h.launch(); h.load()
        val token = h.loads.single().token
        h.session.update(CastPlaybackState(token, "PLAYING", 5000, 90000))
        h.send(CastWire.CONNECTION, """{"type":"CONNECT"}""", "second", h.transport)
        h.session.endFromPlayer(token)
        for (client in listOf("client", "second")) {
            val messages = h.session.updates(client)
            assertEquals(listOf(CastWire.MEDIA, CastWire.RECEIVER), messages.map { it.namespace })
            val stopped = JsonParser.parseString(messages[0].text).asJsonObject.getAsJsonArray("status")[0].asJsonObject
            assertEquals("IDLE", stopped.get("playerState").asString)
            assertEquals("CANCELLED", stopped.get("idleReason").asString)
            val receiver = JsonParser.parseString(messages[1].text).asJsonObject.getAsJsonObject("status")
            assertEquals(0, receiver.getAsJsonArray("applications").size())
            assertTrue(h.session.updates(client).isEmpty())
        }
        assertTrue(h.commands.isEmpty()) // The already-exiting player must not receive another STOP.
        h.session.endFromPlayer(token)
        assertTrue(h.session.updates("client").isEmpty())
        assertEquals("INVALID_REQUEST", h.media("""{"type":"PLAY","mediaSessionId":1}""").get("type").asString)
    }

    @Test fun naturalCompletionAndSenderMediaStopKeepTheReceiverAppAvailable() {
        val h = Harness(); h.launch(); h.load()
        h.session.update(CastPlaybackState(h.loads.single().token, "IDLE", idleReason = "FINISHED"))
        assertEquals(listOf(CastWire.MEDIA), h.session.updates("client").map { it.namespace })
        h.media("""{"type":"STOP","mediaSessionId":1}""")
        val receiver = h.send(CastWire.RECEIVER, """{"type":"GET_STATUS"}""")!!.getAsJsonObject("status")
        assertEquals(1, receiver.getAsJsonArray("applications").size())
        h.load("http://192.168.1.2/next.mp4")
        assertEquals("BUFFERING", h.status().get("playerState").asString)
    }

    @Test fun stalePlayerExitCannotCloseNewLoadAndReceiverCanBeRelaunched() {
        val h = Harness(); h.launch(); h.load()
        val old = h.loads.single().token
        h.load("http://192.168.1.2/next.mp4")
        h.session.endFromPlayer(old)
        assertTrue(h.session.drainNotifications("client").isEmpty())
        assertEquals("BUFFERING", h.status().get("playerState").asString)
        h.session.endFromPlayer(h.loads.last().token)
        assertEquals(2, h.session.drainNotifications("client").size)
        val oldTransport = h.transport
        h.launch(); h.load()
        assertNotEquals(oldTransport, h.transport)
        assertEquals("BUFFERING", h.status().get("playerState").asString)
        h.session.disconnect("second")
        assertEquals("BUFFERING", h.status().get("playerState").asString)
    }
}
