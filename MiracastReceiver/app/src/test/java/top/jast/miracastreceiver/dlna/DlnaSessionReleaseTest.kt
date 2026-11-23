package top.jast.miracastreceiver.dlna

import org.junit.Assert.*
import org.junit.Test

class DlnaSessionReleaseTest {
    @Test fun replacedSessionRemainsStoppedAndCannotBeRevivedByLateControls() {
        val renderer = DlnaMediaRenderer()
        renderer.setAVTransportURI("http://sender/video.mp4", "old metadata")
        val old = renderer.getMediaSessionId()
        renderer.play()
        renderer.onPlayerPosition(old, 12000, 60000, true)
        renderer.setVolume(72)
        assertTrue(renderer.onPlayerStopped(old, releaseMedia = true))
        renderer.onPlayerPosition(old, 14000, 60000, true)
        renderer.play()
        renderer.pause()
        renderer.seek("00:00:20")
        renderer.setQualityUri("http://sender/stale-quality.mp4")
        renderer.stop()
        val state = renderer.getState()
        assertEquals(DlnaMediaRenderer.TransportState.STOPPED, state.transportState)
        assertEquals("", state.uri)
        assertEquals("", state.metadata)
        assertTrue(state.playlist.isEmpty())
        assertEquals(0L, state.position)
        assertEquals(0L, state.duration)
        assertEquals(72, state.volume)
        assertFalse(renderer.onPlayerStopped(old, releaseMedia = true))
    }

    @Test fun freshRequestCanStartAfterReleaseAndOldPlayerCannotStopIt() {
        val renderer = DlnaMediaRenderer()
        renderer.setAVTransportURI("http://first/video.mp4", "first")
        val old = renderer.getMediaSessionId()
        renderer.onPlayerStopped(old, releaseMedia = true)
        renderer.setAVTransportURI("http://second/video.mp4", "second")
        renderer.play()
        assertFalse(renderer.onPlayerStopped(old, releaseMedia = true))
        assertEquals(DlnaMediaRenderer.TransportState.PLAYING, renderer.getState().transportState)
        assertEquals("http://second/video.mp4", renderer.getState().uri)
    }
}
