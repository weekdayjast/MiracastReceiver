package top.jast.miracastreceiver.miracast

import android.os.SystemClock

/** Counters shared between MiracastVideoRenderer and the on-screen stats overlay. */
object MiracastVideoStats {
    @Volatile var sourceFrames = 0L
    @Volatile var renderedFrames = 0L
    @Volatile var lastFrameAt = 0L

    fun onSource() {
        sourceFrames++
        lastFrameAt = SystemClock.elapsedRealtime()
    }

    fun lastFrameAgeMs(): Long =
        if (lastFrameAt == 0L) -1 else SystemClock.elapsedRealtime() - lastFrameAt
}
