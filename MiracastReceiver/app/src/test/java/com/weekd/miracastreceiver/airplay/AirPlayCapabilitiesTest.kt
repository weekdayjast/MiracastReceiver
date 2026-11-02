package com.weekd.miracastreceiver.airplay

import org.junit.Assert.*
import org.junit.Test

class AirPlayCapabilitiesTest {
    @Test fun preservesSmallerAndNonWidescreenPanels() {
        assertEquals(1280 to 720, fitMirrorResolution(1280, 720, 1920, 1080))
        assertEquals(1366 to 768, fitMirrorResolution(1366, 768, 1920, 1080))
        assertEquals(1280 to 800, fitMirrorResolution(1280, 800, 1920, 1080))
    }
    @Test fun scalesLargerPanelsWithinBothBoundsWithoutChangingAspectRatio() {
        assertEquals(1920 to 1080, fitMirrorResolution(3840, 2160, 1920, 1080))
        assertEquals(1728 to 1080, fitMirrorResolution(2560, 1600, 1920, 1080))
    }
    @Test fun scaledUltrawideOutputHasEvenChromaDimensions() {
        assertEquals(1920 to 802, fitMirrorResolution(3440, 1440, 1920, 1080))
    }
    @Test fun capabilityQueriesUseAlignedCodedDimensions() {
        assertEquals(1368 to 768, alignedVideoSize(1366, 768, 4, 4))
        assertEquals(1920 to 1088, alignedVideoSize(1920, 1080, 16, 16))
        assertEquals(3840 to 2160, alignedVideoSize(3840, 2160, 16, 16))
    }
    @Test fun rejectsKnownSoftwareNamesBeforeHardwareFlagsAreAvailable() {
        for (name in listOf("OMX.google.hevc.decoder", "c2.android.hevc.decoder", "OMX.ffmpeg.hevc.decoder",
                "OMX.qcom.video.decoder.hevcswvdec", "OMX.SEC.hevc.sw.decoder", "OMX.VENDOR.software.hevc")) {
            assertTrue(name, isSoftwareDecoderName(name))
        }
        assertFalse(isSoftwareDecoderName("OMX.MTK.VIDEO.DECODER.HEVC"))
        assertFalse(isSoftwareDecoderName("OMX.qcom.video.decoder.hevc"))
    }
}
