package com.weekd.miracastreceiver.airplay.handshake

import org.junit.Assert.*
import org.junit.Test

class AirPlayVideoConfigTest {
    private val vps = byteArrayOf(0x40, 1, 12)
    private val sps = byteArrayOf(0x42, 1, 1, 9)
    private val pps = byteArrayOf(0x44, 1, 2)
    private fun short(n: Int) = byteArrayOf((n ushr 8).toByte(), n.toByte())
    private fun int(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    private fun box(tag: String, data: ByteArray) = int(data.size + 8) + tag.toByteArray() + data
    private fun hvcc(): ByteArray {
        var result = ByteArray(23).also { it[0] = 1; it[21] = 3; it[22] = 3 }
        for ((type, nal) in listOf(32 to vps, 33 to sps, 34 to pps)) {
            result += byteArrayOf((0x80 or type).toByte()) + short(1) + short(nal.size) + nal
        }
        return result
    }
    @Test fun parsesRawHevcArrays() {
        val config = AirPlayVideoConfigParser.parse(hvcc())
        assertArrayEquals(vps, config.vps); assertArrayEquals(sps, config.sps); assertArrayEquals(pps, config.pps)
    }
    @Test fun parsesAirPlayHevcSampleEntry() {
        val packet = box("hvc1", ByteArray(78) + box("hvcC", hvcc()) + box("pasp", ByteArray(8)))
        val config = AirPlayVideoConfigParser.parse(packet)
        assertArrayEquals(vps, config.vps); assertArrayEquals(sps, config.sps)
    }
    @Test fun skipsUnknownChildBoxesRatherThanSearchingInsideTheirPayload() {
        val fake = hvcc().also { it[it.size - 1] = 99 }
        val packet = box("hvc1", ByteArray(78) + box("free", box("hvcC", fake)) + box("hvcC", hvcc()))
        assertArrayEquals(pps, AirPlayVideoConfigParser.parse(packet).pps)
    }
    @Test fun doesNotTreatSampleEntryMetadataAsAChildBox() {
        val header = ByteArray(78)
        box("hvcC", hvcc()).copyInto(header, 10)
        assertRejected(box("hvc1", header))
    }
    @Test fun parsesStandaloneHevcConfigurationBox() {
        assertArrayEquals(vps, AirPlayVideoConfigParser.parse(box("hvcC", hvcc())).vps)
    }
    @Test fun preservesLegacyAvcConfiguration() {
        val config = AirPlayVideoConfigParser.parse(byteArrayOf(1, 100, 0, 42, -1, -31) +
            short(3) + byteArrayOf(0x67, 100, 0) + byteArrayOf(1) + short(2) + byteArrayOf(0x68, 0))
        assertNull(config.vps); assertArrayEquals(byteArrayOf(0x67, 100, 0), config.sps)
    }
    @Test fun rejectsTruncatedAndOversizedConfiguration() {
        val packet = box("hvc1", ByteArray(78) + box("hvcC", hvcc()))
        for (bad in listOf(byteArrayOf(), hvcc().copyOf(25), packet.copyOf(packet.size - 1),
                           hvcc().also { it[26] = 0x7F; it[27] = -1 })) {
            assertRejected(bad)
        }
    }
    @Test fun rejectsUnsupportedBoxAndNalLengthEncodings() {
        for (size in listOf(0, 1, Int.MAX_VALUE)) {
            assertRejected(int(size) + "hvc1".toByteArray() + ByteArray(78) + box("hvcC", hvcc()))
        }
        assertRejected(hvcc().also { it[21] = 2 })
        assertRejected(hvcc().also { it[22] = 2 }) // Missing PPS array.
    }
    @Test fun skipsSeiArraysInHevcConfiguration() {
        val withSei = hvcc().also { it[22] = 4 } + byteArrayOf(0xA7.toByte()) + short(1) +
            short(3) + byteArrayOf(0x4E, 1, 0)
        assertArrayEquals(pps, AirPlayVideoConfigParser.parse(withSei).pps)
    }
    @Test fun identifiesCodecSpecificRandomAccessFrames() {
        val start = byteArrayOf(0, 0, 0, 1)
        assertTrue(AirPlayVideoConfigParser.containsRandomAccessFrame(start + byteArrayOf(0x65, 1), false))
        assertTrue(AirPlayVideoConfigParser.containsRandomAccessFrame(start + byteArrayOf(0x26, 1), true))
        assertFalse(AirPlayVideoConfigParser.containsRandomAccessFrame(start + byteArrayOf(0x02, 1), true))
        assertFalse(AirPlayVideoConfigParser.containsRandomAccessFrame(start + byteArrayOf(0x41, 1), false))
        assertFalse(AirPlayVideoConfigParser.containsRandomAccessFrame(start + byteArrayOf(0x02, 1) + start + byteArrayOf(0x26, 1), true))
        assertFalse(AirPlayVideoConfigParser.containsRandomAccessFrame(start + byteArrayOf(0x41, 1) + start + byteArrayOf(0x65, 1), false))
        assertTrue(AirPlayVideoConfigParser.containsRandomAccessFrame(start + byteArrayOf(0x46, 1) + start + byteArrayOf(0x26, 1), true))
    }

    private fun assertRejected(bytes: ByteArray) {
        try { AirPlayVideoConfigParser.parse(bytes); fail("Accepted malformed configuration") }
        catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
}
