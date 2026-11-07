package top.jast.miracastreceiver.castplus

import org.junit.Assert.*
import org.junit.Test

class CastPlusVideoProfileTest {
    @Test fun nativeUsbPhoneResolutionIsNotClampedToASquare() {
        val p = chooseCastPlusVideoProfile(1080, 2160) { _, _ -> true }
        assertEquals(2160, p.screenWidth)
        assertEquals(1080, p.screenHeight)
        assertEquals(2160, p.videoWidth)
        assertEquals(1080, p.videoHeight)
        assertEquals(16_000_000, p.bitrate)
    }

    @Test fun quarterTurnsSwapActualDimensions() {
        for (angle in listOf(90, 270)) {
            val p = chooseCastPlusVideoProfile(1080, 2160, angle) { _, _ -> true }
            assertEquals(1080, p.screenWidth)
            assertEquals(2160, p.screenHeight)
            assertEquals(1080, p.videoWidth)
            assertEquals(2160, p.videoHeight)
        }
    }

    @Test fun noDeviceSpecificResolutionIsHardcoded() {
        val p = chooseCastPlusVideoProfile(3840, 2160, 90) { _, _ -> true }
        assertEquals(2160, p.videoWidth)
        assertEquals(3840, p.videoHeight)
        val phone = chooseCastPlusVideoProfile(1440, 3120, 270) { _, _ -> true }
        assertEquals(1440, phone.videoWidth)
        assertEquals(3120, phone.videoHeight)
    }

    @Test fun highResolutionProfilesStayWithinHuaweiSdkBitrateLimit() {
        for ((w, h) in listOf(3840 to 2160, 1440 to 3120, 7680 to 4320)) {
            for (rotation in listOf(0, 90, 180, 270)) {
                val profile = chooseCastPlusVideoProfile(w, h, rotation) { _, _ -> true }
                assertEquals(20_000_000, profile.bitrate)
            }
        }
    }

    @Test fun nativePanelOrderingDoesNotAffectTheDeclaration() {
        assertEquals(chooseCastPlusVideoProfile(1080, 2160, 90) { _, _ -> true },
            chooseCastPlusVideoProfile(2160, 1080, 90) { _, _ -> true })
    }

    @Test fun unsupportedVideoSizesFallBackWithoutChangingDeclaredPanelOrAspectRatio() {
        val p = chooseCastPlusVideoProfile(1080, 2160) { w, h -> w <= 1920 && h <= 1080 }
        assertEquals(2160, p.screenWidth)
        assertEquals(1080, p.screenHeight)
        assertEquals(1920, p.videoWidth)
        assertEquals(960, p.videoHeight)
        val portrait = chooseCastPlusVideoProfile(1080, 2160, 90) { w, h -> w <= 1080 && h <= 1920 }
        assertEquals(960, portrait.videoWidth)
        assertEquals(1920, portrait.videoHeight)
    }

    @Test fun fallbackAndOddPanelsKeepEvenVideoDimensions() {
        val p = chooseCastPlusVideoProfile(1081, 2161) { _, _ -> false }
        assertEquals(2161, p.screenWidth)
        assertEquals(1081, p.screenHeight)
        assertTrue(p.videoWidth <= 720 && p.videoHeight <= 720)
        assertEquals(0, p.videoWidth % 2)
        assertEquals(0, p.videoHeight % 2)
    }
}
