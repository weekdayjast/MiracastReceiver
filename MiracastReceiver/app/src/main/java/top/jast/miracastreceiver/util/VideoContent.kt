package top.jast.miracastreceiver.util

internal data class VideoContent(val left: Float = 0f, val top: Float = 0f,
                                val right: Float = 1f, val bottom: Float = 1f) {
    val width get() = right - left
    val height get() = bottom - top
}
