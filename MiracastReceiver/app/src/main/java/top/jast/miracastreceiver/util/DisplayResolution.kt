package top.jast.miracastreceiver.util

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

/** Current physical display mode, independent of the activity's orientation and navigation bars. */
internal fun nativeDisplayResolution(context: Context): Pair<Int, Int> {
    val mode = if (Build.VERSION.SDK_INT >= 23) runCatching {
        (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
    }.getOrNull() else null
    val metrics = context.resources.displayMetrics
    return (mode?.physicalWidth ?: metrics.widthPixels).coerceAtLeast(2) to
        (mode?.physicalHeight ?: metrics.heightPixels).coerceAtLeast(2)
}
