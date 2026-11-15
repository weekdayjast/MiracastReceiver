package top.jast.miracastreceiver.castplus

import org.junit.Assert.*
import org.junit.Test

class CastPlusPlaybackGateTest {
    @Test fun decoderBeforeSurfaceWaitsForTheVisiblePlayer() {
        val gate = CastPlusPlaybackGate()
        gate.begin("a"); gate.paused()
        assertFalse(gate.canPlay())
        gate.surface("a", true)
        assertFalse(gate.canPlay())
        gate.visible("a", true)
        assertTrue(gate.canPlay())
        gate.started()
        assertFalse(gate.canPlay())
    }
    @Test fun surfaceBeforeDecoderDoesNotStartEarly() {
        val gate = CastPlusPlaybackGate()
        gate.begin("a"); gate.surface("a", true); gate.visible("a", true)
        assertFalse(gate.canPlay())
        gate.paused()
        assertTrue(gate.canPlay())
    }
    @Test fun backgroundCallbackDoesNotResumePlayback() {
        val gate = CastPlusPlaybackGate()
        gate.begin("a"); gate.surface("a", true); gate.visible("a", true); gate.paused(); gate.started()
        gate.visible("a", false); gate.paused()
        assertFalse(gate.canPlay())
        gate.visible("a", true)
        assertTrue(gate.canPlay())
    }
    @Test fun destroyedSurfaceRequiresAnotherDecoderReadyEvent() {
        val gate = CastPlusPlaybackGate()
        gate.begin("a"); gate.visible("a", true); gate.surface("a", true); gate.paused(); gate.started()
        gate.surface("a", false); gate.surface("a", true)
        assertFalse(gate.canPlay())
        gate.paused()
        assertTrue(gate.canPlay())
    }
    @Test fun oldPlayerCannotDetachOrEndANewSession() {
        val gate = CastPlusPlaybackGate()
        gate.begin("old"); gate.begin("new")
        gate.surface("new", true); gate.visible("new", true); gate.paused()
        gate.surface("old", false); gate.visible("old", false); gate.end("old")
        assertEquals("new", gate.token)
        assertTrue(gate.canPlay())
        gate.end("new")
        gate.paused()
        assertFalse(gate.canPlay())
    }
    @Test fun pauseCallbackBeforeSurfaceDestroyedStillAllowsResume() {
        val gate = CastPlusPlaybackGate()
        gate.begin("a"); gate.surface("a", true); gate.visible("a", true); gate.paused(); gate.started()
        gate.visible("a", false); gate.paused(); gate.surface("a", false)
        gate.surface("a", true)
        assertFalse(gate.canPlay())
        gate.visible("a", true)
        assertTrue(gate.canPlay())
    }
}
