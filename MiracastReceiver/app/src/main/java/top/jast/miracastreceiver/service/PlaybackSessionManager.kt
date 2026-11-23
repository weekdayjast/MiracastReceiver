package top.jast.miracastreceiver.service

/** Single owner of both picture and sound. Calls are serialized by the main thread adapter. */
class PlaybackSessionManager {
    enum class Source { AIRPLAY, DLNA, MIRACAST, CAST_PLUS, CAST }
    data class Session(val id: Long, val source: Source)
    private var sequence = 0L
    var active: Session? = null
        private set
    private var stopTransport: (() -> Unit)? = null
    private var stopPlayer: (() -> Unit)? = null
    private var transportId: String? = null

    fun begin(source: Source, transportId: String? = null, stop: () -> Unit): Session {
        val previous = active
        val previousTransportId = this.transportId
        val transport = stopTransport
        val player = stopPlayer
        // Revoke ownership before teardown: synchronous callbacks from the old
        // transport/player cannot end or control the replacement.
        active = null
        this.transportId = null
        stopTransport = null
        stopPlayer = null
        try { player?.invoke() } finally {
            if (previous?.source != source || previousTransportId != transportId) transport?.invoke()
        }
        return Session(++sequence, source).also {
            active = it; stopTransport = stop; this.transportId = transportId
        }
    }

    fun bind(session: Session, stop: () -> Unit): Boolean {
        if (active != session) return false
        stopPlayer = stop
        return true
    }

    fun end(session: Session, disconnect: Boolean = true) {
        if (active != session) return
        val transport = stopTransport
        val player = stopPlayer
        active = null
        transportId = null
        stopTransport = null
        stopPlayer = null
        try { player?.invoke() } finally {
            if (disconnect) transport?.invoke()
        }
    }

    fun stop() { active?.let { end(it) } }
}
