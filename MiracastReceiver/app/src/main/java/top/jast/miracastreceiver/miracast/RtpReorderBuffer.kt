package top.jast.miracastreceiver.miracast

/** Single-consumer jitter window. A late/duplicate packet is not a 65535-packet loss. */
internal class RtpReorderBuffer(
    private val onPayload: (ByteArray) -> Unit,
    private val onGap: (Int) -> Unit,
    private val maxPackets: Int = 64,
    private val maxDelayMs: Long = 40
) {
    private data class Pending(val bytes: ByteArray, val arrivedMs: Long)
    private val pending = HashMap<Int, Pending>()
    private var expected = -1
    var ignoredPackets = 0L
        private set

    fun offer(sequence: Int, payload: ByteArray, nowMs: Long) {
        val seq = sequence and 0xffff
        if (expected < 0) expected = seq
        val distance = distance(seq)
        if (distance >= 0x8000 || pending.containsKey(seq)) {
            ignoredPackets++
            return
        }
        pending[seq] = Pending(payload, nowMs)
        drain(nowMs)
    }

    fun drain(nowMs: Long) {
        while (pending.isNotEmpty()) {
            val ready = pending.remove(expected)
            if (ready != null) {
                expected = (expected + 1) and 0xffff
                onPayload(ready.bytes)
                continue
            }
            val nearest = pending.keys.minByOrNull(::distance) ?: return
            val oldest = pending.values.minOf { it.arrivedMs }
            if (pending.size < maxPackets && distance(nearest) < maxPackets && nowMs - oldest < maxDelayMs) return
            val lost = distance(nearest)
            expected = nearest
            onGap(lost)
        }
    }

    private fun distance(sequence: Int) = (sequence - expected) and 0xffff
}
