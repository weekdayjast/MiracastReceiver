package com.weekd.miracastreceiver.airplay.handshake

import java.io.ByteArrayOutputStream

/** Annex-B SPS/PPS/VPS groups, retaining every parameter set of each type. */
data class AirPlayVideoConfig(val sps: ByteArray, val pps: ByteArray, val vps: ByteArray? = null)

object AirPlayVideoConfigParser {
    fun parse(bytes: ByteArray): AirPlayVideoConfig {
        require(bytes.isNotEmpty()) { "Empty video configuration" }
        if (tagAt(bytes, 4, "hvc1") || tagAt(bytes, 4, "hev1") || tagAt(bytes, 4, "hvcC")) {
            return parseHevc(findBoxContents(bytes, "hvcC"))
        }
        if (tagAt(bytes, 4, "avc1") || tagAt(bytes, 4, "avcC")) {
            return parseAvc(findBoxContents(bytes, "avcC"))
        }
        // AVCDecoderConfigurationRecord has reserved bits followed by SPS count at byte 5.
        val isAvc = bytes.size >= 7 && (bytes[4].toInt() and 0xFC) == 0xFC &&
            (bytes[5].toInt() and 0xE0) == 0xE0
        return if (isAvc) parseAvc(bytes) else parseHevc(bytes)
    }

    private fun tagAt(bytes: ByteArray, offset: Int, tag: String): Boolean =
        offset >= 0 && offset + 4 <= bytes.size && (0..3).all { bytes[offset + it].toInt() == tag[it].code }

    private fun findBoxContents(bytes: ByteArray, tag: String): ByteArray {
        fun endOfBox(start: Int, parentEnd: Int): Int {
            require(start >= 0 && parentEnd - start >= 8) { "Truncated box header" }
            val size = (0..3).fold(0L) { n, j -> (n shl 8) or (bytes[start + j].toLong() and 255L) }
            // These codec packets use finite 32-bit sizes; do not accept open-ended or extended boxes.
            require(size >= 8L && size <= (parentEnd - start).toLong()) { "Invalid codec box size $size" }
            return start + size.toInt()
        }
        val parentEnd = endOfBox(0, bytes.size)
        if (tagAt(bytes, 4, tag)) return bytes.copyOfRange(8, parentEnd)
        // hvc1/hev1/avc1 contain the 78-byte VisualSampleEntry header after their box header.
        var position = 8 + 78
        require(position <= parentEnd) { "Truncated visual sample entry" }
        while (position < parentEnd) {
            val end = endOfBox(position, parentEnd)
            if (tagAt(bytes, position + 4, tag)) return bytes.copyOfRange(position + 8, end)
            position = end
        }
        error("Missing or truncated $tag box")
    }

    private class Cursor(val bytes: ByteArray, var position: Int = 0) {
        fun byte(): Int { require(position < bytes.size) { "Truncated configuration" }; return bytes[position++].toInt() and 255 }
        fun short(): Int = (byte() shl 8) or byte()
        fun nal(): ByteArray {
            val length = short()
            require(length > 0 && length <= bytes.size - position) { "Invalid parameter-set length $length" }
            return bytes.copyOfRange(position, position + length).also { position += length }
        }
    }

    private fun parseAvc(bytes: ByteArray): AirPlayVideoConfig {
        require(bytes.size >= 7 && bytes[0].toInt() == 1) { "Invalid avcC" }
        require((bytes[4].toInt() and 3) == 3) { "Only 4-byte AVC NAL lengths supported" }
        val cursor = Cursor(bytes, 5)
        val spsCount = cursor.byte() and 31
        require(spsCount > 0)
        val sps = List(spsCount) { cursor.nal().also { require((it[0].toInt() and 31) == 7) } }
        val ppsCount = cursor.byte()
        require(ppsCount > 0)
        val pps = List(ppsCount) { cursor.nal().also { require((it[0].toInt() and 31) == 8) } }
        return AirPlayVideoConfig(annexB(sps), annexB(pps))
    }

    private fun parseHevc(bytes: ByteArray): AirPlayVideoConfig {
        require(bytes.size >= 23 && bytes[0].toInt() == 1) { "Invalid hvcC" }
        require((bytes[21].toInt() and 3) == 3) { "Only 4-byte HEVC NAL lengths supported" }
        val cursor = Cursor(bytes, 22)
        val arrays = cursor.byte()
        require(arrays in 1..64)
        val sets = mutableMapOf<Int, MutableList<ByteArray>>()
        repeat(arrays) {
            val type = cursor.byte() and 63
            val count = cursor.short()
            require(count <= 1024)
            repeat(count) {
                val nal = cursor.nal()
                require(nal.size >= 2 && ((nal[0].toInt() and 0x7E) ushr 1) == type) { "HEVC NAL type mismatch" }
                if (type in 32..34) sets.getOrPut(type) { mutableListOf() }.add(nal)
            }
        }
        return AirPlayVideoConfig(
            annexB(requireNotNull(sets[33]) { "Missing HEVC SPS" }),
            annexB(requireNotNull(sets[34]) { "Missing HEVC PPS" }),
            annexB(requireNotNull(sets[32]) { "Missing HEVC VPS" })
        )
    }

    private fun annexB(nals: List<ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        for (nal in nals) {
            output.write(byteArrayOf(0, 0, 0, 1))
            output.write(nal)
        }
        output.toByteArray()
    }

    fun containsRandomAccessFrame(bytes: ByteArray, hevc: Boolean): Boolean {
        var i = 0
        while (i + 3 < bytes.size) {
            if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0 && bytes[i + 2].toInt() == 1) {
                val header = bytes[i + 3].toInt() and 255
                if (hevc) {
                    if (i + 4 >= bytes.size) return false
                    val type = (header and 0x7E) ushr 1
                    if (type in 0..31) return type in 16..21
                } else {
                    val type = header and 31
                    if (type in 1..5) return type == 5
                }
                i += 3
            } else i++
        }
        return false
    }
}
