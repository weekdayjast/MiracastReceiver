package top.jast.miracastreceiver.dlna

/** Concrete formats let control points classify the renderer without interpreting MIME wildcards. */
internal object DlnaProtocolInfo {
    val sink: String = listOf(
        "http-get:*:image/jpeg:*",
        "http-get:*:image/png:*",
        "http-get:*:video/mp4:*",
        "http-get:*:audio/mpeg:*",
        "http-get:*:video/*:*",
        "http-get:*:audio/*:*",
        "http-get:*:image/*:*"
    ).joinToString(",")
}
