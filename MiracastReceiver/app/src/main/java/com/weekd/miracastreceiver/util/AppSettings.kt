package com.weekd.miracastreceiver.util

import android.content.Context

/**
 * 应用设置（用户可在主界面里改的开关）。
 *
 * 单独放在 SharedPreferences 里，和 AirPlay 配对信息、设备 UUID 那几个
 * 内部用的 prefs 文件分开，避免清配对时误删用户设置。
 */
object AppSettings {
    fun isAutoRotateToFit(context: Context): Boolean = prefs(context).getBoolean("auto_rotate_to_fit", false)

    fun setAutoRotateToFit(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("auto_rotate_to_fit", enabled).apply()
    }

    private const val PREFS_NAME = "miracast_settings"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_RETURN_TO_APP_AFTER_CAST = "return_to_app_after_cast"

    /**
     * 是否开机自动启动接收服务。
     *
     * 默认开启：这个应用装在电视上就是当常驻投屏接收器用的，
     * 每次重启电视都要手动打开一次不符合使用场景。
     */
    fun isAutoStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, true)

    fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply()
    }

    /** 界面语言的 BCP 47 标签，空串表示跟随系统。由 [AppLanguage] 读写。 */
    fun getLanguageTag(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, "") ?: ""

    fun setLanguageTag(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, tag).apply()
    }

    /**
     * 投屏结束（遥控器返回键 / 手机端停止）后，是否留在本应用的设置页。
     *
     * 默认关闭：退出投屏就回到电视桌面，这是多数人投完屏想继续看电视的路径。
     * 打开后停在设置页，适合需要接着调设置（自启、语言）或确认接收状态的人。
     */
    fun isReturnToAppAfterCast(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RETURN_TO_APP_AFTER_CAST, false)

    fun setReturnToAppAfterCast(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RETURN_TO_APP_AFTER_CAST, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
