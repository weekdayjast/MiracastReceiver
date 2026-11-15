package top.jast.miracastreceiver.cast

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class CastDiscoveryTest {
    private val records = CastMdnsPacket("2a95d794-26a7-3c19-b8c1-a8278ffaa9f1", "Living Room TV", "192.168.2.96")
    private fun name(value: String) = ByteArrayOutputStream().apply {
        value.trimEnd('.').split('.').forEach { write(it.length); write(it.toByteArray()) }; write(0)
    }.toByteArray()
    private fun query(owner: String = CastMdnsPacket.SERVICE, type: Int = 12, queryClass: Int = 1): ByteArray =
        ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeShort(123); writeShort(0); writeShort(1); writeShort(0); writeInt(0)
                write(name(owner)); writeShort(type); writeShort(queryClass)
            }
        }.toByteArray()

    private fun readName(input: DataInputStream): String {
        val labels = mutableListOf<String>()
        while (true) {
            val length = input.readUnsignedByte()
            if (length == 0) return labels.joinToString(".", postfix = ".")
            require(length <= 63)
            labels.add(ByteArray(length).also { input.readFully(it) }.toString(Charsets.UTF_8))
        }
    }

    @Test fun vlcSeesVideoCapabilitiesAndFriendlyNameInOneDiscoveryPacket() {
        val input = DataInputStream(records.response().inputStream())
        input.skipBytes(6)
        val count = input.readUnsignedShort()
        input.skipBytes(4)
        val types = mutableListOf<Int>()
        var hasSrv = false
        var video = false
        var audio = true // VLC's default when it misses the TXT record.
        var friendlyName: String? = null
        val decoded = mutableListOf<Pair<Int, ByteArray>>()
        repeat(count) {
            readName(input)
            val type = input.readUnsignedShort()
            types.add(type)
            input.readUnsignedShort(); input.readInt()
            val body = ByteArray(input.readUnsignedShort()).also { input.readFully(it) }
            decoded.add(type to body)
        }
        // libmicrodns mdns_parse prepends entries, reversing their wire order.
        // Feed VLC's discovery state machine in that actual traversal order.
        for ((type, body) in decoded.asReversed()) {
            when (type) {
                33 -> {
                    val srv = DataInputStream(body.inputStream())
                    srv.skipBytes(4)
                    assertEquals(8009, srv.readUnsignedShort())
                    assertEquals(records.host, readName(srv))
                    hasSrv = true
                }
                // Model VLC microdns: it ignores TXT until it has encountered SRV.
                16 -> if (hasSrv) {
                    val txt = DataInputStream(body.inputStream())
                    while (txt.available() > 0) {
                        val item = ByteArray(txt.readUnsignedByte()).also { txt.readFully(it) }.toString(Charsets.UTF_8)
                        if (item.startsWith("fn=")) friendlyName = item.substring(3)
                        if (item.startsWith("ca=")) {
                            val caps = item.substring(3).toInt()
                            video = caps and 1 != 0; audio = caps and 4 != 0
                        }
                    }
                }
                1 -> assertArrayEquals(byteArrayOf(-64, -88, 2, 96), body)
            }
        }
        assertEquals(listOf(12, 16, 33, 1), types)
        assertTrue(video); assertTrue(audio)
        assertEquals("Living Room TV", friendlyName)
    }

    @Test fun multicastUnicastAndLegacyQueriesUseTheCorrectResponseAddressing() {
        val multicast = records.query(query(), 5353)!!
        assertFalse(multicast.unicast)
        assertEquals(0, multicast.id)
        assertTrue(multicast.legacyQuestions.isEmpty())
        assertTrue(records.query(query(queryClass = 0x8001), 5353)!!.unicast)
        val legacy = records.query(query(), 42424)!!
        assertTrue(legacy.unicast); assertEquals(123, legacy.id)
        val response = DataInputStream(records.response(query = legacy).inputStream())
        assertEquals(123, response.readUnsignedShort())
        response.skipBytes(2)
        assertEquals(1, response.readUnsignedShort())
        response.skipBytes(6)
        assertEquals(CastMdnsPacket.SERVICE, readName(response))
    }

    @Test fun malformedQueriesAndOtherServicesDoNotProduceResponses() {
        assertNull(records.query(byteArrayOf(1), 5353))
        assertNull(records.query(records.response(), 5353))
        assertNull(records.query(query("_airplay._tcp.local."), 5353))
        val cyclic = query().copyOfRange(0, 12) + byteArrayOf(-64, 12, 0, 12, 0, 1)
        assertNull(records.query(cyclic, 5353))
    }

    @Test fun compressedQuestionsAreDecodedAndGoodbyeWithdrawsEveryRecord() {
        val initial = query()
        initial[5] = 2
        val compressed = initial + byteArrayOf(-64, 12, 0, 16, 0, 1)
        val parsed = records.query(compressed, 42424)!!
        val response = DataInputStream(records.response(ttl = 0, query = parsed).inputStream())
        response.skipBytes(4)
        assertEquals(2, response.readUnsignedShort())
        assertEquals(4, response.readUnsignedShort())
        response.skipBytes(4)
        repeat(2) { assertEquals(CastMdnsPacket.SERVICE, readName(response)); response.skipBytes(4) }
        repeat(4) {
            readName(response); response.skipBytes(4)
            assertEquals(0, response.readInt())
            response.skipBytes(response.readUnsignedShort())
        }
    }
}
