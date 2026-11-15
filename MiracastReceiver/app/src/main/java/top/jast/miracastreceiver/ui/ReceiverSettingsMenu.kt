package top.jast.miracastreceiver.ui

import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import top.jast.miracastreceiver.R
import top.jast.miracastreceiver.util.AppLanguage
import top.jast.miracastreceiver.util.AppSettings

/** Focusable, anchored settings. D-pad OK toggles without dismissing the menu. */
class ReceiverSettingsMenu(
    private val activity: AppCompatActivity,
    private val anchor: View,
    private val castPlusChanged: (Boolean) -> Unit
) {
    private var popup: PopupWindow? = null
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(activity, id)

    fun toggle() { if (popup?.isShowing == true) close() else show() }
    fun close() { popup?.dismiss(); popup = null }

    private fun show() {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val scroll = object : ScrollView(activity) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_SETTINGS) {
                    if (event.action == KeyEvent.ACTION_UP) close()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFillViewport = false
            addView(content)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_settings_panel)
        }
        val metrics = activity.resources.displayMetrics
        val width = minOf(dp(440), metrics.widthPixels - dp(32))
        val position = IntArray(2).also(anchor::getLocationOnScreen)
        val maxHeight = (metrics.heightPixels - position[1] - anchor.height - dp(24)).coerceAtLeast(dp(120))
        val window = PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ContextCompat.getDrawable(activity, R.drawable.bg_settings_panel))
            isOutsideTouchable = true
            elevation = dp(16).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        popup = window

        fun resize() {
            scroll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
            if (window.isShowing) window.update(width, scroll.measuredHeight)
            else window.height = scroll.measuredHeight
        }
        fun text(value: String, secondary: Boolean = false) = TextView(activity).apply {
            this.text = value
            textSize = if (secondary) 13f else 16f
            setTextColor(color(if (secondary) R.color.text_secondary else R.color.text_primary))
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        fun row(value: String, action: () -> Unit): TextView = text(value).apply {
            minHeight = dp(52)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = ContextCompat.getDrawable(activity, R.drawable.bg_setting_item)
            setOnClickListener { action() }
        }
        fun divider() { content.addView(View(activity).apply { setBackgroundColor(0xFF343D4D.toInt()) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(dp(12), dp(8), dp(12), dp(8)) }) }
        fun switch(label: Int, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit): SwitchCompat {
            return SwitchCompat(activity).apply {
                setText(label)
                textSize = 16f
                setTextColor(color(R.color.text_primary))
                setPadding(dp(14), dp(8), dp(14), dp(8))
                minHeight = dp(54)
                switchPadding = dp(20)
                background = ContextCompat.getDrawable(activity, R.drawable.bg_setting_item)
                isFocusable = true
                isEnabled = enabled
                isChecked = checked
                setOnCheckedChangeListener { _, value -> change(value) }
                content.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }

        content.addView(text(activity.getString(R.string.receiver_settings), true))
        val first = switch(R.string.auto_start_on_boot, AppSettings.isAutoStartOnBoot(activity)) {
            AppSettings.setAutoStartOnBoot(activity, it)
        }
        switch(R.string.return_to_app_after_cast, AppSettings.isReturnToAppAfterCast(activity)) {
            AppSettings.setReturnToAppAfterCast(activity, it)
        }
        switch(R.string.auto_rotate_to_fit, AppSettings.isAutoRotateToFit(activity)) {
            AppSettings.setAutoRotateToFit(activity, it)
        }
        switch(R.string.castplus_enable, AppSettings.isCastPlusEnabled(activity), Build.VERSION.SDK_INT >= 23) {
            AppSettings.setCastPlusEnabled(activity, it)
            castPlusChanged(it)
        }
        divider()

        val languages = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val labels = listOf(activity.getString(R.string.language_follow_system)) + AppLanguage.OPTIONS.map { it.second }
        val languageRow = row(activity.getString(R.string.language) + ": " + labels[AppLanguage.currentIndex() + 1] + "  ▾") {
            languages.visibility = if (languages.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            resize()
        }
        content.addView(languageRow)
        labels.forEachIndexed { index, label ->
            languages.addView(row((if (index == AppLanguage.currentIndex() + 1) "✓  " else "     ") + label) {
                close()
                AppLanguage.apply(activity, if (index == 0) "" else AppLanguage.OPTIONS[index - 1].first)
            })
        }
        content.addView(languages)
        scroll.isFocusableInTouchMode = true
        window.setOnDismissListener {
            popup = null
            anchor.requestFocus()
        }
        resize()
        window.showAsDropDown(anchor, 0, dp(8), Gravity.END)
        first.requestFocus()
    }

}
