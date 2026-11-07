package top.jast.miracastreceiver.airplay

/** Discovery can change independently of a live RTSP/URL playback session. */
internal class AirPlayLifecycle(
    private val onStateChanged: (AirPlayState) -> Unit,
    private val onSessionEnded: () -> Unit
) {
    private var connected = false

    @Synchronized
    fun discoveryChanged(state: AirPlayState) {
        if (!connected) onStateChanged(state)
    }

    @Synchronized
    fun sessionConnected() {
        connected = true
        onStateChanged(AirPlayState.CONNECTED)
    }

    @Synchronized
    fun sessionEnded() {
        val wasConnected = connected
        connected = false
        if (wasConnected) onSessionEnded()
        onStateChanged(AirPlayState.ADVERTISING)
    }
}
