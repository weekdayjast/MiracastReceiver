package com.weekd.miracastreceiver.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the in-project binary plist reader.
 *
 * PlistCodec.encode still uses dd-plist's BinaryPropertyListWriter, so a round trip
 * exercises every object type dd-plist can emit (including UTF-16BE strings, reals,
 * nested dicts and byte data) against our own decoder without needing a device.
 */
class BPlistCompatTest {

    @Test
    fun roundTripAllSupportedTypes() {
        val original: Map<String, Any?> = linkedMapOf(
            "ascii" to "hello-airplay",
            "unicode" to "中文投影仪😀",          // emitted as UTF-16BE, includes a surrogate pair
            "smallInt" to 42L,
            "negativeInt" to -7L,
            "bigInt" to 5_000_000_000L,
            "real" to 3.5,
            "yes" to true,
            "no" to false,
            "data" to byteArrayOf(0, 1, 2, -1, 127),
            "list" to listOf("a", 1L, true),
            "nested" to mapOf("k" to "v", "n" to 9L)
        )

        val encoded = PlistCodec.encode(original)
        assertTrue(String(encoded, 0, 8, Charsets.US_ASCII) == "bplist00")

        val decoded = PlistCodec.decode(encoded)
        assertEquals(original.keys, decoded.keys)
        assertEquals("hello-airplay", decoded["ascii"])
        assertEquals("中文投影仪😀", decoded["unicode"])
        assertEquals(42L, decoded["smallInt"])
        assertEquals(-7L, decoded["negativeInt"])
        assertEquals(5_000_000_000L, decoded["bigInt"])
        assertEquals(3.5, (decoded["real"] as Number).toDouble(), 0.0001)
        assertEquals(true, decoded["yes"])
        assertEquals(false, decoded["no"])
        assertArrayEquals(byteArrayOf(0, 1, 2, -1, 127), decoded["data"] as ByteArray)
        assertEquals(listOf("a", 1L, true), decoded["list"])
        assertEquals(mapOf("k" to "v", "n" to 9L), decoded["nested"])
    }

    @Test
    fun xmlPlistStillDecodes() {
        val xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<plist version=\"1.0\"><dict>" +
            "<key>Content-Location</key><string>http://example.invalid/v.mp4</string>" +
            "<key>Start-Position-Seconds</key><real>1.5</real>" +
            "</dict></plist>").toByteArray()
        val decoded = PlistCodec.decode(xml)
        assertEquals("http://example.invalid/v.mp4", decoded["Content-Location"])
        assertEquals(1.5, (decoded["Start-Position-Seconds"] as Number).toDouble(), 0.0001)
    }

    @Test
    fun longAsciiStringUsesLengthExtension() {
        val long = "x".repeat(400)
        val decoded = PlistCodec.decode(PlistCodec.encode(mapOf("k" to long)))
        assertEquals(long, decoded["k"])
    }
}
