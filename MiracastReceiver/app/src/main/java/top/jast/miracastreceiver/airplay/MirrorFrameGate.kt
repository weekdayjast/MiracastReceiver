package top.jast.miracastreceiver.airplay

/** Decode the initial codec configuration before a window exists, but pause after losing it. */
internal class MirrorFrameGate {
    @Volatile private var running = false
    @Volatile private var outputLost = false

    val acceptsFrames: Boolean get() = running && !outputLost

    fun sessionChanged(active: Boolean) {
        if (active) outputLost = false
        running = active
    }

    fun outputAttached() { outputLost = false }
    fun outputDetached() { outputLost = true }
}
