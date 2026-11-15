package top.jast.miracastreceiver.airplay

import org.junit.Assert.*
import org.junit.Test

class MirrorFrameGateTest {
    @Test fun firstCodecConfigurationCanArriveBeforeTheActivityWindow() {
        val gate = MirrorFrameGate()
        assertFalse(gate.acceptsFrames)
        gate.sessionChanged(true)
        assertTrue(gate.acceptsFrames)
        gate.outputAttached()
        assertTrue(gate.acceptsFrames)
    }

    @Test fun handoffToAnotherProtocolSuspendsThePreviousStream() {
        val gate = MirrorFrameGate()
        gate.sessionChanged(true)
        gate.outputAttached()
        gate.outputDetached()
        assertFalse(gate.acceptsFrames)
    }

    @Test fun replacementAirplayWindowResumesDecoding() {
        val gate = MirrorFrameGate()
        gate.sessionChanged(true)
        gate.outputAttached()
        gate.outputDetached()
        gate.outputAttached()
        assertTrue(gate.acceptsFrames)
    }

    @Test fun lateWindowCannotRestartAnEndedSession() {
        val gate = MirrorFrameGate()
        gate.sessionChanged(true)
        gate.sessionChanged(false)
        gate.outputAttached()
        assertFalse(gate.acceptsFrames)
    }

    @Test fun reconnectAcceptsInitialConfigurationAfterPreviousHandoff() {
        val gate = MirrorFrameGate()
        gate.sessionChanged(true)
        gate.outputAttached()
        gate.outputDetached()
        gate.sessionChanged(false)
        gate.sessionChanged(true)
        assertTrue(gate.acceptsFrames)
    }
}
