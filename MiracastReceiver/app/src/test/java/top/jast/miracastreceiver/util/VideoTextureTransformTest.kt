package top.jast.miracastreceiver.util

import org.junit.Assert.*
import org.junit.Test

class VideoTextureTransformTest {
    @Test fun rotatedPortraitFillsWidthAndKeepsProportionalHeight() {
        for (angle in listOf(90, 270)) {
            val scale = videoContentScale(1080f, 2400f, 2030f, 1080f, angle)
            assertEquals(2030f, 2400f * scale, .01f)
            assertEquals(913.5f, 1080f * scale, .01f)
        }
    }

    @Test fun rotatedPortraitInsideLandscapeFrameFillsLongEdgeWithoutScalingBlackBars() {
        val crop = VideoContent(.375f, 0f, .625f, 1f)
        for (angle in listOf(90, 270)) {
            val scale = videoContentScale(1920f, 1080f, 2030f, 1080f, angle, crop)
            assertEquals(2030f, 1080f * scale, .01f)
            assertEquals(902.2222f, 480f * scale, .01f)
        }
    }

    @Test fun uprightPortraitFillsHeightAndCentresWidth() {
        for (angle in listOf(0, 180)) {
            val scale = videoContentScale(1080f, 2400f, 2030f, 1080f, angle)
            assertEquals(1080f, 2400f * scale, .01f)
            assertEquals(486f, 1080f * scale, .01f)
        }
    }

    @Test fun squareFrameKeepsQuarterTurnRuleButUprightRemainsFullyVisible() {
        assertEquals(2030f / 1920, videoContentScale(1920f, 1920f, 2030f, 1080f, 270), .0001f)
        assertEquals(1080f / 1920, videoContentScale(1920f, 1920f, 1080f, 2030f, 0), .0001f)
    }

    @Test fun unrotatedLandscapeIsNotClippedOnTheUsbPhonesWiderScreen() {
        val scale = videoContentScale(1920f, 1080f, 2030f, 1080f, 0)
        assertEquals(1920f, 1920f * scale, .01f)
        assertEquals(1080f, 1080f * scale, .01f)
    }

    @Test fun everyUprightEdgeStaysWithinScreenBounds() {
        for ((width, height) in listOf(1920f to 1080f, 1080f to 2400f, 1920f to 1920f)) {
            for ((screenW, screenH) in listOf(2030f to 1080f, 1080f to 2030f)) {
                for (angle in listOf(0, 180)) {
                    val scale = videoContentScale(width, height, screenW, screenH, angle)
                    val rotatedW = if (angle % 180 == 0) width else height
                    val rotatedH = if (angle % 180 == 0) height else width
                    assertTrue(rotatedW * scale <= screenW + .01f)
                    assertTrue(rotatedH * scale <= screenH + .01f)
                }
            }
        }
    }
}
