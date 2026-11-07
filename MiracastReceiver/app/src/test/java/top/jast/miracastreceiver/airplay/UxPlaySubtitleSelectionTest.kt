package top.jast.miracastreceiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UxPlaySubtitleSelectionTest {
    private val tracks = listOf(
        UxPlaySubtitleTrack("en", "English"),
        UxPlaySubtitleTrack("zh-TW", "Traditional Chinese"),
        UxPlaySubtitleTrack("zh-CN", "中文（简体）"),
    )

    @Test fun startsDisabledWithoutSenderChoice() {
        assertNull(findUxPlaySubtitleTrack(UxPlaySubtitleSelection(), tracks))
    }

    @Test fun selectsExactlyTheRequestedChineseVariant() {
        assertEquals(2, findUxPlaySubtitleTrack(UxPlaySubtitleSelection("zh-CN", enabled = true), tracks))
    }

    @Test fun neverSubstitutesADifferentLanguageOrChineseScript() {
        assertNull(findUxPlaySubtitleTrack(UxPlaySubtitleSelection("fr", enabled = true), tracks))
        assertNull(findUxPlaySubtitleTrack(UxPlaySubtitleSelection("zh-HK", enabled = true), tracks))
    }

    @Test fun supportsGenericLanguageTagsInTheManifest() {
        assertEquals(0, findUxPlaySubtitleTrack(UxPlaySubtitleSelection("en-US", enabled = true), tracks))
    }

    @Test fun offDisablesEvenWhenTheOldLanguageIsRetained() {
        assertNull(findUxPlaySubtitleTrack(UxPlaySubtitleSelection("zh-CN", enabled = false), tracks))
    }

    @Test fun usesSenderLabelToDistinguishSameLanguageTracks() {
        val english = listOf(UxPlaySubtitleTrack("en", "Manual"), UxPlaySubtitleTrack("en", "Automatic"))
        assertEquals(1, findUxPlaySubtitleTrack(UxPlaySubtitleSelection("en", "Automatic", true), english))
    }
}
