package com.weekd.miracastreceiver.airplay.handshake

/**
 * BPlist — minimal, dependency-free Apple binary plist ("bplist00") reader.
 *
 * WHY: dd-plist 1.28's PropertyListParser.parse() reaches
 * `BinaryPropertyListParser$$ExternalSyntheticLambda0`, a D8-desugared lambda
 * synthetic class. On some Android runtimes that class fails to load; on the device
 * where this was reproduced ART logs "Rejecting re-init on previously-failed class"
 * and throws NoClassDefFoundError at BinaryPropertyListParser.parseObject. The AirPlay 2
 * mirror SETUP is the first binary-plist decode in a session, so handleMirrorSetup's
 * catch(Throwable) turns it into `400 Bad Request` and iOS reports "Unable to connect".
 * Decoding with a small in-project reader keeps the code in the same dex as PlistCodec
 * and removes that fragile runtime dependency. The dd-plist *writer* is unaffected:
 * PlistCodec.encode() still uses it.
 *
 * Supports the object types AirPlay uses: null/bool, int, real, date, data,
 * ASCII / UTF-8 / UTF-16BE strings, UID, arrays, sets and dictionaries.
 */

private fun beUInt(b: ByteArray, off: Int, len: Int): Long {
    require(off >= 0 && len >= 0 && off + len <= b.size) { "bplist: uint out of range" }
    var v = 0L
    for (i in 0 until len) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
    return v
}

internal object BPlist {

    fun parse(bytes: ByteArray): Map<String, Any?> {
        require(bytes.size >= 40) { "bplist: too short (${bytes.size})" }
        require(String(bytes, 0, 8, Charsets.US_ASCII) == "bplist00") { "bplist: bad header" }

        val trailer = bytes.size - 32
        val offsetSize = bytes[trailer + 6].toInt() and 0xFF
        val refSize = bytes[trailer + 7].toInt() and 0xFF
        val numObjects = beUInt(bytes, trailer + 8, 8).toInt()
        val top = beUInt(bytes, trailer + 16, 8).toInt()
        val tableOffset = beUInt(bytes, trailer + 24, 8).toInt()
        require(offsetSize in 1..8 && refSize in 1..8) { "bplist: bad trailer sizes" }
        require(numObjects in 1..1_000_000 && top in 0 until numObjects) { "bplist: bad object count" }
        require(tableOffset >= 8 && tableOffset + numObjects * offsetSize <= trailer) {
            "bplist: offset table out of range"
        }

        val offsets = IntArray(numObjects) { i ->
            beUInt(bytes, tableOffset + i * offsetSize, offsetSize).toInt()
        }
        @Suppress("UNCHECKED_CAST")
        return Reader(bytes, offsets, refSize).read(top, 0) as? Map<String, Any?>
            ?: throw IllegalArgumentException("bplist: root is not a dictionary")
    }

    private class Reader(
        private val b: ByteArray,
        private val offsets: IntArray,
        private val refSize: Int
    ) {
        private fun refAt(p: Int): Int {
            val idx = beUInt(b, p, refSize).toInt()
            require(idx in offsets.indices) { "bplist: bad object ref $idx" }
            return idx
        }

        /** Returns (element count, position of first byte/ref). Handles the 0xF length extension. */
        private fun count(markerPos: Int, low: Int): Pair<Int, Int> {
            if (low < 0x0F) return low to (markerPos + 1)
            val m = b[markerPos + 1].toInt() and 0xFF
            require((m ushr 4) == 0x1) { "bplist: length is not an int object" }
            val intLen = 1 shl (m and 0x0F)
            val start = markerPos + 2
            val v = if (intLen > 8) {
                beUInt(b, start + (intLen - 8), 8)
            } else {
                beUInt(b, start, intLen)
            }
            require(v in 0..Int.MAX_VALUE) { "bplist: length too large" }
            return v.toInt() to (start + intLen)
        }

        private fun intAt(markerPos: Int, low: Int): Long {
            val len = 1 shl low
            val start = markerPos + 1
            return if (len > 8) beUInt(b, start + (len - 8), 8) else beUInt(b, start, len)
        }

        fun read(idx: Int, depth: Int): Any? {
            require(depth <= 64) { "bplist: nesting too deep" }
            require(idx in offsets.indices) { "bplist: bad object index $idx" }
            val o = offsets[idx]
            val marker = b[o].toInt() and 0xFF
            val hi = marker ushr 4
            val lo = marker and 0x0F
            return when (hi) {
                0x0 -> when (lo) {
                    0x0 -> null
                    0x8 -> false
                    0x9 -> true
                    else -> null
                }
                0x1 -> intAt(o, lo)
                0x2 -> {
                    val len = 1 shl lo
                    val bits = beUInt(b, o + 1, len)
                    if (len == 4) java.lang.Float.intBitsToFloat(bits.toInt()).toDouble()
                    else java.lang.Double.longBitsToDouble(bits)
                }
                0x3 -> java.lang.Double.longBitsToDouble(beUInt(b, o + 1, 8))
                0x4 -> {
                    val (n, start) = count(o, lo)
                    require(start + n <= b.size) { "bplist: data out of range" }
                    b.copyOfRange(start, start + n)
                }
                0x5 -> {
                    val (n, start) = count(o, lo)
                    require(start + n <= b.size) { "bplist: ascii string out of range" }
                    String(b, start, n, Charsets.US_ASCII)
                }
                0x6 -> {
                    val (n, start) = count(o, lo)
                    require(start + n * 2 <= b.size) { "bplist: utf16 string out of range" }
                    String(b, start, n * 2, Charsets.UTF_16BE)
                }
                0x7 -> {   // UTF-8 string (newer plist writers)
                    val (n, start) = count(o, lo)
                    require(start + n <= b.size) { "bplist: utf8 string out of range" }
                    String(b, start, n, Charsets.UTF_8)
                }
                0x8 -> beUInt(b, o + 1, lo + 1)
                0xA -> {
                    val (n, start) = count(o, lo)
                    (0 until n).map { read(refAt(start + it * refSize), depth + 1) }
                }
                0xC -> {   // set / ordered set — expose as an array, like dd-plist
                    val (n, start) = count(o, lo)
                    (0 until n).map { read(refAt(start + it * refSize), depth + 1) }
                }
                0xD -> {
                    val (n, start) = count(o, lo)
                    val valuesStart = start + n * refSize
                    val map = LinkedHashMap<String, Any?>(n)
                    for (i in 0 until n) {
                        val key = read(refAt(start + i * refSize), depth + 1).toString()
                        map[key] = read(refAt(valuesStart + i * refSize), depth + 1)
                    }
                    map
                }
                else -> throw IllegalArgumentException(
                    "bplist: unsupported object marker 0x${marker.toString(16)} at $o"
                )
            }
        }
    }
}
