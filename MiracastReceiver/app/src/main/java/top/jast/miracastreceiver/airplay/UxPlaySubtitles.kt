package top.jast.miracastreceiver.airplay

import androidx.media3.common.text.Cue
import kotlinx.coroutines.flow.MutableStateFlow

/** Sender-controlled selection and decoded cues, independent of the video Surface. */
internal object UxPlaySubtitles {
    val selection = MutableStateFlow(UxPlaySubtitleSelection())
    val cues = MutableStateFlow<List<Cue>>(emptyList())

    fun clear() {
        selection.value = UxPlaySubtitleSelection()
        cues.value = emptyList()
    }
}
