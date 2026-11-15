package top.jast.miracastreceiver.util

import android.graphics.Matrix
import android.view.TextureView

/** Transform a full-size texture once; never aspect-fit the view before rotating its pixels. */
internal fun applyVideoTextureTransform(texture: TextureView, videoWidth: Int, videoHeight: Int,
                                        rotation: Int, crop: VideoContent = VideoContent()) {
    val vw = texture.width.toFloat()
    val vh = texture.height.toFloat()
    if (vw <= 0 || vh <= 0) return
    val w = if (videoWidth > 0) videoWidth.toFloat() else vw
    val h = if (videoHeight > 0) videoHeight.toFloat() else vh
    val fit = videoContentScale(w, h, vw, vh, rotation, crop)
    val matrix = Matrix().apply {
        postScale(w / vw, h / vh)
        postTranslate(-w * (crop.left + crop.right) / 2f, -h * (crop.top + crop.bottom) / 2f)
        postRotate(rotation.toFloat())
        postScale(fit, fit)
        postTranslate(vw / 2f, vh / 2f)
    }
    texture.setTransform(matrix)
    texture.invalidate()
}

internal fun videoContentScale(width: Float, height: Float, screenWidth: Float, screenHeight: Float,
                              rotation: Int, crop: VideoContent = VideoContent()): Float {
    val sideways = rotation % 180 != 0
    val contentW = width * crop.width
    val contentH = height * crop.height
    val rotatedW = if (sideways) contentH else contentW
    val rotatedH = if (sideways) contentW else contentH
    // Preserve the requested long-edge sizing for quarter turns only.
    if (sideways) return when {
        rotatedW > rotatedH -> screenWidth / rotatedW
        rotatedH > rotatedW -> screenHeight / rotatedH
        else -> maxOf(screenWidth, screenHeight) / rotatedW
    }
    // Upright playback must keep BOTH dimensions inside the screen.
    // Using just the long edge clips the other edge on phones with a wider display aspect ratio.
    return minOf(screenWidth / rotatedW, screenHeight / rotatedH)
}
