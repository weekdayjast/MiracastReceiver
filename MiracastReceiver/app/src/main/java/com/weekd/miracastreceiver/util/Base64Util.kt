package com.weekd.miracastreceiver.util

/**
 * Base64Util — a small, dependency-free standard Base64 codec.
 *
 * WHY: SDP parsing and key handling need Base64, but `android.util.Base64` is an Android-framework
 * class that returns null under plain-JVM unit tests (and `java.util.Base64` needs API 26 > our
 * minSdk 25). A pure-Kotlin implementation works on every API level and is unit-testable directly.
 *
 * Standard alphabet (`A–Z a–z 0–9 + /`), `=` padding. [decode] tolerates embedded whitespace
 * (SDP/PEM line wrapping) and throws [IllegalArgumentException] on any other invalid character.
 */
object Base64Util {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Reverse lookup: ASCII code → 6-bit value, or -1 for non-alphabet bytes. */
    private val DECODE = IntArray(128) { -1 }.also { t ->
        for (i in ALPHABET.indices) t[ALPHABET[i].code] = i
    }

    /** Decodes a standard Base64 string. Whitespace and `=` padding are ignored. */
    fun decode(input: String): ByteArray {
        val out = ArrayList<Byte>(input.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in input) {
            if (c == '=' || c == '\n' || c == '\r' || c == ' ' || c == '\t') continue
            val v = if (c.code < 128) DECODE[c.code] else -1
            require(v >= 0) { "invalid Base64 character: '$c'" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer ushr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    /** Encodes bytes to a padded standard Base64 string. */
    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(ALPHABET[(triple ushr 18) and 0x3F])
            sb.append(ALPHABET[(triple ushr 12) and 0x3F])
            sb.append(if (i + 1 < data.size) ALPHABET[(triple ushr 6) and 0x3F] else '=')
            sb.append(if (i + 2 < data.size) ALPHABET[triple and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }
}

