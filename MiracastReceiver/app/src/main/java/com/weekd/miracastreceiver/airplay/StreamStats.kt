package com.weekd.miracastreceiver.airplay

/**
 * StreamStats — live counters for the optional on-screen debug overlay (Settings → "Debug overlay").
 *
 * The mirror video server ([com.weekd.miracastreceiver.airplay.handshake.MirrorStreamServer]) and audio server
 * ([com.weekd.miracastreceiver.airplay.handshake.AudioStreamServer]) write these volatile fields as they run;
 * [com.weekd.miracastreceiver.ui.StreamingScreen] polls [summary] a few times a second to render the HUD. Plain
 * volatile ints keep the hot path allocation-free — no flows or locks on the per-frame path.
 *
 * [overlayEnabled] is set from [com.weekd.miracastreceiver.settings.AppSettings.showDebugOverlay] when the AirPlay
 * receiver starts, so the HUD appears for the next mirroring session after the toggle is flipped.
 */
object StreamStats {
    /** Master switch, mirrored from the user setting; the overlay only draws when true. */
    @Volatile var overlayEnabled = false

    // ─── Video (MirrorStreamServer) ──────────────────────────────────────────
    @Volatile var videoRes = ""        // e.g. "1920x1080"
    @Volatile var videoFps = 0         // frames/sec over the last sample window
    @Volatile var videoQueue = 0       // current decode-queue depth
    @Volatile var videoDropPct = 0     // cumulative % of frames dropped under load

    // Actual decoded video dimensions (from the SPS, so portrait phone streams are portrait here).
    // StreamingScreen reads these to aspect-fit the Surface instead of stretching to 16:9.
    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0

    // ─── Audio (AudioStreamServer) ───────────────────────────────────────────
    @Volatile var audioActive = false  // true while an audio stream is running
    @Volatile var audioQueue = 0       // current playback-queue depth
    @Volatile var audioDupPct = 0      // % of RTP packets that were redundant duplicates

    /** Clears per-stream counters (call when a mirror session ends). Keeps [overlayEnabled]. */
    fun resetStreams() {
        videoRes = ""; videoFps = 0; videoQueue = 0; videoDropPct = 0
        videoWidth = 0; videoHeight = 0
        audioActive = false; audioQueue = 0; audioDupPct = 0
    }

    /** Human-readable multi-line HUD text. */
    fun summary(): String =
        "PhairPlay · debug\n" +
        "VIDEO  ${videoRes.ifEmpty { "—" }}   ${videoFps} fps   q ${videoQueue}   drop ${videoDropPct}%\n" +
        "AUDIO  " + (if (audioActive) "on   q ${audioQueue}   dup ${audioDupPct}%" else "off")
}

