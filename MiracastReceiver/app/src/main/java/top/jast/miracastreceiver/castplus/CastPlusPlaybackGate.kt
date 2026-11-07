package top.jast.miracastreceiver.castplus

/** A decoder-ready callback and a visible, current Surface are both required to play. */
class CastPlusPlaybackGate {
    var token: String? = null
        private set
    private var visible = false
    private var surfaceReady = false
    private var decoderReady = false
    var playing = false
        private set

    fun begin(id: String) {
        token = id; visible = false; surfaceReady = false; decoderReady = false; playing = false
    }
    fun surface(id: String, ready: Boolean) {
        if (id != token) return
        surfaceReady = ready
        if (!ready) playing = false
    }
    fun visible(id: String, ready: Boolean) {
        if (id != token) return
        visible = ready
        if (!ready) playing = false
    }
    fun paused() { decoderReady = true; playing = false }
    fun canPlay(): Boolean = token != null && visible && surfaceReady && decoderReady && !playing
    fun started() { playing = true; decoderReady = false }
    fun end(id: String) {
        if (id != token) return
        token = null; visible = false; surfaceReady = false; decoderReady = false; playing = false
    }
}
