package top.jast.miracastreceiver.service

import org.junit.Assert.*
import org.junit.Test
import top.jast.miracastreceiver.service.PlaybackSessionManager.Source

class PlaybackSessionManagerTest {
    @Test fun anotherMiracastSenderStopsOldTransportBeforeGainingOwnership() {
        val manager = PlaybackSessionManager()
        val events = mutableListOf<String>()
        val huawei = manager.begin(Source.MIRACAST, "huawei-rtsp") { events += "huawei-disconnected" }
        manager.bind(huawei) { events += "huawei-picture-and-sound-stopped" }
        val windows = manager.begin(Source.MIRACAST, "windows-rtsp") { events += "windows-disconnected" }
        assertEquals(listOf("huawei-picture-and-sound-stopped", "huawei-disconnected"), events)
        manager.end(huawei, disconnect = false)
        assertEquals(windows, manager.active)
        assertEquals(2, events.size)
    }

    @Test fun everyProtocolReplacesEveryOtherProtocolBeforeStarting() {
        for (first in Source.values()) for (second in Source.values()) {
            if (first == second) continue
            val manager = PlaybackSessionManager()
            val events = mutableListOf<String>()
            val old = manager.begin(first) { events += "transport" }
            manager.bind(old) { events += "picture-and-sound" }
            val next = manager.begin(second) { events += "new-transport" }
            assertEquals(listOf("picture-and-sound", "transport"), events)
            assertEquals(next, manager.active)
            manager.end(old)
            assertEquals(next, manager.active)
            assertFalse(manager.bind(old) { fail("Stale player was bound") })
        }
    }

    @Test fun nextMediaItemStopsOldPlayerButKeepsProtocolTransport() {
        val manager = PlaybackSessionManager()
        var stoppedPlayers = 0
        var disconnected = 0
        val old = manager.begin(Source.DLNA) { disconnected++ }
        manager.bind(old) { stoppedPlayers++ }
        val next = manager.begin(Source.DLNA) { disconnected++ }
        assertNotEquals(old.id, next.id)
        assertEquals(1, stoppedPlayers)
        assertEquals(0, disconnected)
        manager.end(next)
        assertEquals(1, disconnected)
    }

    @Test fun teardownCallbacksCannotReleaseReplacementOrStopTwice() {
        val manager = PlaybackSessionManager()
        var stops = 0
        lateinit var old: PlaybackSessionManager.Session
        old = manager.begin(Source.AIRPLAY) { manager.end(old); stops++ }
        manager.bind(old) { manager.end(old); stops++ }
        val next = manager.begin(Source.CAST) { stops++ }
        assertEquals(next, manager.active)
        assertEquals(2, stops)
        manager.stop()
        manager.stop()
        assertNull(manager.active)
        assertEquals(3, stops)
    }

    @Test fun remoteEndReleasesPlayerWithoutDisconnectingTransportAgain() {
        val manager = PlaybackSessionManager()
        var playerStops = 0
        val owner = manager.begin(Source.MIRACAST) { fail("Already disconnected") }
        manager.bind(owner) { playerStops++ }
        manager.end(owner, disconnect = false)
        assertNull(manager.active)
        assertEquals(1, playerStops)
    }

    @Test fun playerFailureStillStopsTransportAndDoesNotGrantNewOwner() {
        val manager = PlaybackSessionManager()
        var disconnected = false
        val owner = manager.begin(Source.AIRPLAY) { disconnected = true }
        manager.bind(owner) { error("Player release failed") }
        try {
            manager.begin(Source.DLNA) { fail("Replacement must not start") }
            fail("Expected teardown failure")
        } catch (_: IllegalStateException) {
            assertTrue(disconnected)
            assertNull(manager.active)
        }
    }
}
