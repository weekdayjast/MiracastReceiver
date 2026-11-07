package top.jast.miracastreceiver.cast

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Cast V2's proto2 envelope. Unknown protobuf fields are skipped, never trusted as lengths. */
data class CastMessage(
    val source: String,
    val destination: String,
    val namespace: String,
    val text: String? = null,
    val binary: ByteArray? = null
)

object CastWire {
    const val MAX_FRAME = 1024 * 1024
    const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val AUTH = "urn:x-cast:com.google.cast.tp.deviceauth"
    const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val MEDIA = "urn:x-cast:com.google.cast.media"

    fun read(input: InputStream): CastMessage {
        val stream = DataInputStream(input)
        val length = stream.readInt()
        require(length in 1..MAX_FRAME) { "Invalid Cast frame length" }
        val bytes = ByteArray(length)
        stream.readFully(bytes)
        val fields = fields(bytes)
        fun string(tag: Int) = fields[tag]?.firstOrNull()?.toString(Charsets.UTF_8)
        require(fields.keys.containsAll(listOf(1, 2, 3, 4, 5))) { "Incomplete Cast envelope" }
        require(number(fields, 1) == 0L) { "Unsupported Cast version" }
        require(number(fields, 8) != 1L) { "Chunked Cast frames unsupported" }
        val type = number(fields, 5)
        require(type == 0L || type == 1L) { "Invalid Cast payload type" }
        return CastMessage(string(2)!!, string(3)!!, string(4)!!,
            if (type == 0L) requireNotNull(string(6)) else null,
            if (type == 1L) requireNotNull(fields[7]?.firstOrNull()) else null)
    }

    fun write(output: OutputStream, message: CastMessage) {
        val body = ByteArrayOutputStream().apply {
            integer(1, 0)
            bytes(2, message.source.toByteArray())
            bytes(3, message.destination.toByteArray())
            bytes(4, message.namespace.toByteArray())
            integer(5, if (message.binary == null) 0 else 1)
            if (message.binary != null) bytes(7, message.binary)
            else bytes(6, requireNotNull(message.text).toByteArray())
        }.toByteArray()
        require(body.size <= MAX_FRAME)
        DataOutputStream(output).apply { writeInt(body.size); write(body); flush() }
    }

    fun ByteArrayOutputStream.integer(tag: Int, value: Long) {
        varint((tag shl 3).toLong()); varint(value)
    }

    fun ByteArrayOutputStream.bytes(tag: Int, value: ByteArray) {
        varint(((tag shl 3) or 2).toLong()); varint(value.size.toLong()); write(value)
    }

    private fun ByteArrayOutputStream.varint(value: Long) {
        var rest = value
        while (rest and -128L != 0L) { write((rest.toInt() and 127) or 128); rest = rest ushr 7 }
        write(rest.toInt())
    }

    // Varints are retained as their encoded bytes; length-delimited values as their contents.
    fun fields(data: ByteArray): Map<Int, List<ByteArray>> {
        val result = mutableMapOf<Int, MutableList<ByteArray>>()
        var position = 0
        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                require(position < data.size) { "Truncated protobuf varint" }
                val b = data[position++].toInt() and 255
                require(shift != 63 || b <= 1) { "Protobuf varint overflow" }
                value = value or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return value
            }
            error("Invalid protobuf varint")
        }
        while (position < data.size) {
            val key = varint()
            val tag = (key ushr 3).toInt()
            require(tag > 0)
            val start: Int
            val end: Int
            when ((key and 7).toInt()) {
                0 -> { start = position; varint(); end = position }
                1, 5 -> { start = position; end = position + if (key and 7 == 1L) 8 else 4 }
                2 -> {
                    val size = varint()
                    require(size in 0..(data.size - position).toLong()) { "Truncated protobuf field" }
                    start = position; end = position + size.toInt()
                }
                else -> error("Unsupported protobuf wire type")
            }
            require(end <= data.size)
            result.getOrPut(tag) { mutableListOf() }.add(data.copyOfRange(start, end))
            position = end
        }
        return result
    }

    fun number(fields: Map<Int, List<ByteArray>>, tag: Int): Long? = fields[tag]?.firstOrNull()?.let {
        var value = 0L
        it.forEachIndexed { index, b -> value = value or ((b.toLong() and 127) shl (index * 7)) }
        value
    }
}
