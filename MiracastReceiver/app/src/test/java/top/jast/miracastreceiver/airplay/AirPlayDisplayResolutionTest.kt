package top.jast.miracastreceiver.airplay

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test

class AirPlayDisplayResolutionTest {
    @Test fun quarterTurnUsesPortraitDeclaration() {
        assertEquals(1080 to 2160, orientMirrorResolution(2160, 1080, true))
        assertEquals(2160 to 1080, orientMirrorResolution(2160, 1080, false))
    }

    @Test fun repeatedUpdatesDoNotKeepSwappingDimensions() {
        assertEquals(1080 to 2160, orientMirrorResolution(1080, 2160, true))
        assertEquals(2160 to 1080, orientMirrorResolution(1080, 2160, false))
    }

    @Test fun liveHandlerCanChangeDeclarationWithoutOpeningOrClosingTransport() {
        val handler = RtspHandler(context = ContextWrapper(null), displayWidth = 2160,
            displayHeight = 1080, videoSurfaceProvider = { null },
            onStreamingStarted = {}, onStreamingStopped = {})
        assertEquals(2160 to 1080, handler.displayResolution)
        handler.updateDisplayResolution(1080, 2160)
        assertEquals(1080 to 2160, handler.displayResolution)
        handler.updateDisplayResolution(2160, 1080)
        assertEquals(2160 to 1080, handler.displayResolution)
    }
}
