package top.jast.miracastreceiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayLifecycleTest {
    private val states = mutableListOf<AirPlayState>()
    private var ends = 0
    private val lifecycle = AirPlayLifecycle({ states.add(it) }, { ends++ })

    @Test fun discoveryReregistrationDoesNotEndPlayback() {
        lifecycle.sessionConnected()
        lifecycle.discoveryChanged(AirPlayState.DISABLED)
        lifecycle.discoveryChanged(AirPlayState.ADVERTISING)
        lifecycle.discoveryChanged(AirPlayState.ERROR)
        assertEquals(listOf(AirPlayState.CONNECTED), states)
        assertEquals(0, ends)
    }

    @Test fun teardownThenSocketCloseNotifiesEndOnlyOnce() {
        lifecycle.sessionConnected()
        lifecycle.sessionEnded()
        lifecycle.sessionEnded()
        lifecycle.discoveryChanged(AirPlayState.ADVERTISING)
        assertEquals(1, ends)
        assertEquals(AirPlayState.ADVERTISING, states.last())
    }

    @Test fun idleDiscoveryAndNewSessionStillWork() {
        lifecycle.discoveryChanged(AirPlayState.ERROR)
        assertEquals(listOf(AirPlayState.ERROR), states)
        lifecycle.sessionConnected()
        lifecycle.sessionEnded()
        lifecycle.sessionConnected()
        lifecycle.discoveryChanged(AirPlayState.DISABLED)
        assertEquals(AirPlayState.CONNECTED, states.last())
        lifecycle.sessionEnded()
        assertEquals(2, ends)
    }

    @Test fun probeConnectionClosureDoesNotClosePlayer() {
        lifecycle.sessionEnded()
        assertEquals(0, ends)
    }
}
