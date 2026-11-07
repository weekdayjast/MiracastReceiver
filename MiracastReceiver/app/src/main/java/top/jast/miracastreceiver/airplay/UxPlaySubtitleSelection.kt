package top.jast.miracastreceiver.airplay

internal data class UxPlaySubtitleSelection(
    val language: String? = null,
    val name: String? = null,
    val enabled: Boolean = false,
)

internal data class UxPlaySubtitleTrack(val language: String?, val name: String?)

/** Match the sender's choice without substituting a different language. */
internal fun findUxPlaySubtitleTrack(selection: UxPlaySubtitleSelection, tracks: List<UxPlaySubtitleTrack>): Int? {
    if (!selection.enabled) return null
    fun normalize(value: String?) = value?.replace('_', '-')?.lowercase(java.util.Locale.ROOT)
    val language = normalize(selection.language)
    val byName = tracks.indexOfFirst {
        !selection.name.isNullOrBlank() && selection.name == it.name &&
            (language.isNullOrBlank() || normalize(it.language) == language || it.language.isNullOrBlank())
    }
    if (byName >= 0) return byName
    if (language.isNullOrBlank()) return null
    val exact = tracks.indexOfFirst { normalize(it.language) == language }
    if (exact >= 0) return exact
    // A generic language track can satisfy a regional request, but a different
    // regional/script variant (e.g. zh-TW for zh-CN) must not be substituted.
    val generic = tracks.indexOfFirst { normalize(it.language) == language.substringBefore('-') }
    return generic.takeIf { it >= 0 }
}
