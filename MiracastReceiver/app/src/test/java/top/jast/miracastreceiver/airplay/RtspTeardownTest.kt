package top.jast.miracastreceiver.airplay

import android.content.ContextWrapper
import top.jast.miracastreceiver.airplay.handshake.PlistCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class RtspTeardownTest {
    private var sessionStops = 0
    private var videoStops = 0
    private var audioStops = 0
    private var bufferedStops = 0
    private val handler = object : RtspHandler(
        context = ContextWrapper(null),
        videoSurfaceProvider = { null },
        onStreamingStarted = {},
        onStreamingStopped = { sessionStops++ },
        onMirrorVideoStop = { videoStops++ },
        onMirrorAudioStop = { audioStops++ },
        onBufferedAudioStop = { bufferedStops++ }
    ) {
        fun streams(vararg types: Int) {
            activeStreamTypes.clear()
            activeStreamTypes.addAll(types.toList())
        }
        fun active() = activeStreamTypes.toSet()
    }

    private fun teardown(vararg types: Int): RtspResponse {
        val body = if (types.isEmpty()) ByteArray(0) else PlistCodec.encode(
            mapOf("streams" to types.map { mapOf("type" to it.toLong()) })
        )
        return handler.handleTeardownInternal(
            RtspRequest("TEARDOWN", "/stream", emptyMap(), "", body)
        )
    }

    @Test fun videoResolutionSwitchKeepsAudioAndSession() {
        handler.streams(96, 110)
        assertEquals(200, teardown(110).statusCode)
        assertEquals(1, videoStops)
        assertEquals(0, audioStops)
        assertEquals(0, sessionStops)
        assertEquals(setOf(96), handler.active())
    }

    @Test fun videoOnlyResolutionSwitchKeepsSessionForStreamReadd() {
        handler.streams(110)
        teardown(110)
        assertEquals(1, videoStops)
        assertEquals(0, sessionStops)
        assertEquals(emptySet<Int>(), handler.active())
        handler.streams(110) // Sender SETUPs replacement video on the existing session.
        teardown()
        assertEquals(1, sessionStops)
    }

    @Test fun stoppingAllNamedStreamsStillAllowsRenegotiation() {
        handler.streams(96, 110)
        teardown(96, 110)
        assertEquals(1, videoStops)
        assertEquals(1, audioStops)
        assertEquals(0, sessionStops)
        teardown()
        assertEquals(1, sessionStops)
    }

    @Test fun bufferedAudioStreamStopDoesNotEndControlSession() {
        handler.streams(103)
        teardown(103)
        assertEquals(1, bufferedStops)
        assertEquals(0, sessionStops)
    }

    @Test fun fullSessionTeardownStillStopsPlayback() {
        handler.streams(96, 110)
        teardown()
        assertEquals(1, sessionStops)
        assertEquals(emptySet<Int>(), handler.active())
    }
}
