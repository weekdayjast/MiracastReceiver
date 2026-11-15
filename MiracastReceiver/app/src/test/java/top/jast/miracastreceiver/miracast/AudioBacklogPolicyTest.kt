package top.jast.miracastreceiver.miracast

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioBacklogPolicyTest {
    @Test fun ordinaryBurstOfTenPesDoesNotDrop170msOfAudio() {
        assertEquals(0, AudioBacklogPolicy.dropCount(List(10) { 1_000_000L + it * 21_333 }))
    }
    @Test fun pesCountAloneDoesNotDetermineLatency() {
        assertEquals(0, AudioBacklogPolicy.dropCount(List(40) { 1_000_000L + it * 1_000 }))
    }
    @Test fun genuinelyStaleQueueKeeps200msOfRecentAudio() {
        assertEquals(8, AudioBacklogPolicy.dropCount(List(11) { 1_000_000L + it * 100_000 }))
    }
    @Test fun missingOrResetTimestampsUseTheExistingHardQueueBoundInstead() {
        assertEquals(0, AudioBacklogPolicy.dropCount(List(30) { 0L }))
        assertEquals(0, AudioBacklogPolicy.dropCount(listOf(10_000_000L, 1_000_000L, 2_000_000L)))
    }
}
