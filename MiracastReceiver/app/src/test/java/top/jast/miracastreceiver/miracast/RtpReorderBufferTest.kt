package top.jast.miracastreceiver.miracast

import org.junit.Assert.*
import org.junit.Test

class RtpReorderBufferTest {
    private val output = mutableListOf<Int>()
    private val losses = mutableListOf<Int>()
    private val events = mutableListOf<String>()
    private val buffer = RtpReorderBuffer(
        onPayload = { output += it[0].toInt() and 255; events += "data:${output.last()}" },
        onGap = { losses += it; events += "gap:$it" })
    private fun offer(seq: Int, now: Long = 0) = buffer.offer(seq, byteArrayOf(seq.toByte()), now)

    @Test fun inOrderPacketsAreNotArtificiallyDelayed() {
        offer(1); offer(2); offer(3)
        assertEquals(listOf(1, 2, 3), output)
        assertTrue(losses.isEmpty())
    }

    @Test fun shortReorderingPreservesCompressedReferenceChain() {
        offer(1); offer(3, 1); offer(4, 2)
        assertEquals(listOf(1), output)
        offer(2, 20)
        assertEquals(listOf(1, 2, 3, 4), output)
        assertTrue(losses.isEmpty())
    }

    @Test fun duplicatesAndLatePacketsDoNotBecomeHugeLosses() {
        offer(1); offer(1); offer(3, 1); offer(3, 2); offer(2, 3); offer(2, 4)
        assertEquals(listOf(1, 2, 3), output)
        assertTrue(losses.isEmpty())
        assertEquals(3L, buffer.ignoredPackets)
    }

    @Test fun missingPacketTimesOutEvenWithoutAnyFurtherArrivals() {
        offer(1); offer(3, 10)
        buffer.drain(49)
        assertEquals(listOf(1), output)
        buffer.drain(50)
        assertEquals(listOf(1, 3), output)
        assertEquals(listOf("data:1", "gap:1", "data:3"), events)
        offer(2, 51)
        assertEquals(1L, buffer.ignoredPackets)
    }

    @Test fun sequenceWrapCanReorderAcross65535() {
        offer(65534); offer(0, 1); offer(65535, 2); offer(1, 3)
        assertEquals(listOf(254, 255, 0, 1), output)
        assertTrue(losses.isEmpty())
    }

    @Test fun windowBoundsMemoryAndReportsOnlyTheActualForwardGap() {
        offer(1); offer(100, 1)
        assertEquals(listOf(1, 100), output)
        assertEquals(listOf(98), losses)
    }
}
