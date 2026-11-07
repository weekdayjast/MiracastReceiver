package top.jast.miracastreceiver.cast

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress

/** DNS-SD records ordered for libmicrodns's reverse traversal: A, SRV, TXT, PTR. */
class CastMdnsPacket(deviceId: String, name: String, ip: String, private val port: Int = 8009) {
    companion object { const val SERVICE = "_googlecast._tcp.local." }
    val instance = "Miracast-${deviceId.replace("-", "")}.$SERVICE"
    val host = "miracast-${deviceId.replace("-", "")}.local."
    private val address = InetAddress.getByName(ip).address.also { require(it.size == 4) }
    private val txt = linkedMapOf("id" to deviceId.replace("-", ""), "fn" to name.take(80),
        "md" to "Miracast Receiver", "ve" to "05", "ca" to "5", "st" to "0", "nf" to "1", "rs" to "")

    data class Query(val id: Int, val legacyQuestions: ByteArray, val unicast: Boolean)

    fun query(data: ByteArray, sourcePort: Int): Query? = runCatching {
        require(data.size >= 12)
        fun word(at: Int) = ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
        if (word(2) and 0x8000 != 0) return null
        val questions = word(4)
        require(questions in 1..32)
        var position = 12
        var relevant = false
        var unicast = sourcePort != 5353
        repeat(questions) {
            val (questionName, end) = readName(data, position)
            position = end
            require(position + 4 <= data.size)
            val type = word(position)
            val queryClass = word(position + 2)
            position += 4
            if (queryClass and 0x7fff == 1 &&
                questionName.lowercase() in setOf(SERVICE.lowercase(), instance.lowercase(), host.lowercase()) &&
                type in setOf(1, 12, 16, 33, 255)) {
                relevant = true
                if (queryClass and 0x8000 != 0) unicast = true
            }
        }
        if (!relevant) return null
        // Re-encode questions instead of copying compression pointers into a different packet.
        val echoed = if (sourcePort != 5353) ByteArrayOutputStream().apply {
            var at = 12
            repeat(questions) {
                val (questionName, end) = readName(data, at)
                write(dnsName(questionName)); write(data, end, 4); at = end + 4
            }
        }.toByteArray() else byteArrayOf()
        Query(if (sourcePort != 5353) word(0) else 0, echoed, unicast)
    }.getOrNull()

    fun response(ttl: Int = 120, query: Query? = null): ByteArray {
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        output.writeShort(query?.id ?: 0)
        output.writeShort(0x8400)
        // Legacy unicast queries must echo their question section and transaction ID.
        val questions = query?.legacyQuestions ?: byteArrayOf()
        var count = 0
        var offset = 0
        while (offset < questions.size) { offset = readName(questions, offset).second + 4; count++ }
        output.writeShort(count); output.writeShort(4); output.writeInt(0)
        output.write(questions)
        fun record(owner: String, type: Int, unique: Boolean, data: ByteArray) {
            output.write(dnsName(owner)); output.writeShort(type)
            output.writeShort(if (unique && questions.isEmpty()) 0x8001 else 1)
            output.writeInt(ttl); output.writeShort(data.size); output.write(data)
        }
        record(SERVICE, 12, false, dnsName(instance))
        // libmicrodns prepends each decoded RR to its list. On the wire TXT must
        // precede SRV, and A must be last, so VLC sees A -> SRV -> TXT -> PTR.
        record(instance, 16, true, ByteArrayOutputStream().apply {
            txt.forEach { (key, value) ->
                val item = "$key=$value".toByteArray(Charsets.UTF_8)
                require(item.size <= 255); write(item.size); write(item)
            }
        }.toByteArray())
        record(instance, 33, true, ByteArrayOutputStream().apply {
            DataOutputStream(this).apply { writeShort(0); writeShort(0); writeShort(port); write(dnsName(host)) }
        }.toByteArray())
        record(host, 1, true, address)
        return bytes.toByteArray()
    }

    private fun dnsName(value: String) = ByteArrayOutputStream().apply {
        value.trimEnd('.').split('.').forEach {
            val label = it.toByteArray(Charsets.UTF_8)
            require(label.size in 1..63)
            write(label.size); write(label)
        }
        write(0)
    }.toByteArray()

    private fun readName(data: ByteArray, start: Int): Pair<String, Int> {
        var position = start
        var end = -1
        val labels = mutableListOf<String>()
        val visited = mutableSetOf<Int>()
        repeat(128) {
            require(position in data.indices && visited.add(position))
            val length = data[position].toInt() and 255
            if (length == 0) return labels.joinToString(".", postfix = ".") to if (end < 0) position + 1 else end
            if (length and 0xc0 == 0xc0) {
                require(position + 1 < data.size)
                if (end < 0) end = position + 2
                position = ((length and 63) shl 8) or (data[position + 1].toInt() and 255)
            } else {
                require(length in 1..63 && position + 1 + length <= data.size)
                labels.add(data.copyOfRange(position + 1, position + 1 + length).toString(Charsets.UTF_8))
                position += length + 1
            }
        }
        error("DNS name too long")
    }
}
