package top.jast.miracastreceiver.miracast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TsDemuxerTest {

    private val videoUnits = mutableListOf<ByteArray>()
    private val audioUnits = mutableListOf<Pair<TsDemuxer.AudioCodec, ByteArray>>()
    private var audioGaps = 0

    private val demuxer = TsDemuxer(
        onAccessUnit = { data, _ -> videoUnits += data },
        onAudioPes = { codec, data -> audioUnits += codec to data },
        onAudioDiscontinuity = { audioGaps++ }
    )

    private val cc = IntArray(0x2000)

    /** 构造一个 188 字节 TS 包，负载不足时用自适应字段填充。 */
    private fun tsPacket(pid: Int, pusi: Boolean, payload: ByteArray): ByteArray {
        require(payload.size <= 184)
        val pkt = ByteArray(188) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (((if (pusi) 0x40 else 0) or (pid shr 8))).toByte()
        pkt[2] = pid.toByte()
        val counter = cc[pid]
        cc[pid] = (counter + 1) and 0x0F
        val stuffing = 184 - payload.size
        if (stuffing == 0) {
            pkt[3] = (0x10 or counter).toByte()
            System.arraycopy(payload, 0, pkt, 4, payload.size)
        } else {
            pkt[3] = (0x30 or counter).toByte()
            pkt[4] = (stuffing - 1).toByte()
            if (stuffing >= 2) pkt[5] = 0x00
            System.arraycopy(payload, 0, pkt, 4 + stuffing, payload.size)
        }
        return pkt
    }

    private fun section(tableId: Int, body: ByteArray): ByteArray {
        val sectionLength = 5 + body.size + 4
        val out = byteArrayOf(
            0x00,                                         // pointer_field
            tableId.toByte(),
            (0xB0 or (sectionLength shr 8)).toByte(), sectionLength.toByte(),
            0x00, 0x01, 0xC1.toByte(), 0x00, 0x00
        )
        return out + body + byteArrayOf(0, 0, 0, 0)      // CRC 不校验
    }

    private fun pat() = tsPacket(0, true, section(0x00, byteArrayOf(0x00, 0x01, 0xF0.toByte(), 0x00)))

    private fun pmt(audioType: Int) = tsPacket(
        0x1000, true, section(
            0x02, byteArrayOf(
                0xF0.toByte(), 0x11, 0xF0.toByte(), 0x00,          // PCR PID, program_info_length=0
                0x1B, 0xF0.toByte(), 0x11, 0xF0.toByte(), 0x00,    // H.264 @ 0x1011
                audioType.toByte(), 0xF1.toByte(), 0x00, 0xF0.toByte(), 0x00  // 音频 @ 0x1100
            )
        )
    )

    private fun pes(streamId: Int, payload: ByteArray, withLength: Boolean): ByteArray {
        val header = byteArrayOf(0x21, 0x00, 0x01, 0x00, 0x01)           // PTS
        val len = if (withLength) 3 + header.size + payload.size else 0
        return byteArrayOf(
            0, 0, 1, streamId.toByte(), (len shr 8).toByte(), len.toByte(),
            0x80.toByte(), 0x80.toByte(), header.size.toByte()
        ) + header + payload
    }

    private fun feed(vararg packets: ByteArray) = packets.forEach { demuxer.feed(it, 0, it.size) }

    @Test
    fun extractsAacAudioPesWithoutWaitingForNextPes() {
        val aac = ByteArray(300) { it.toByte() }
        val audio = pes(0xC0, aac, withLength = true)
        feed(pat(), pmt(0x0F), tsPacket(0x1100, true, audio.copyOfRange(0, 184)),
            tsPacket(0x1100, false, audio.copyOfRange(184, audio.size)))

        assertEquals(1, audioUnits.size)
        assertEquals(TsDemuxer.AudioCodec.AAC_ADTS, audioUnits[0].first)
        assertArrayEquals(aac, audioUnits[0].second)
    }

    @Test
    fun recognisesLpcmStream() {
        val pcm = ByteArray(40) { it.toByte() }
        feed(pat(), pmt(0x83), tsPacket(0x1100, true, pes(0xBD, pcm, withLength = true)))

        assertEquals(TsDemuxer.AudioCodec.LPCM, audioUnits.single().first)
        assertArrayEquals(pcm, audioUnits.single().second)
    }

    @Test
    fun videoStillFlushesOnNextPesStart() {
        val frame1 = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        val frame2 = byteArrayOf(0, 0, 0, 1, 0x41, 4, 5, 6)
        feed(pat(), pmt(0x0F),
            tsPacket(0x1011, true, pes(0xE0, frame1, withLength = false)),
            tsPacket(0x1011, true, pes(0xE0, frame2, withLength = false)))

        assertEquals(1, videoUnits.size)
        assertArrayEquals(frame1, videoUnits[0])
        assertEquals(0, audioUnits.size)
    }

    @Test
    fun audioContinuityGapDropsPartialPes() {
        val audio = pes(0xC0, ByteArray(300), withLength = true)
        feed(pat(), pmt(0x0F), tsPacket(0x1100, true, audio.copyOfRange(0, 184)))
        cc[0x1100] = (cc[0x1100] + 1) and 0x0F                            // 模拟丢一个包
        feed(tsPacket(0x1100, false, audio.copyOfRange(184, audio.size)))

        assertEquals(1, audioGaps)
        assertEquals(0, audioUnits.size)
    }
}
