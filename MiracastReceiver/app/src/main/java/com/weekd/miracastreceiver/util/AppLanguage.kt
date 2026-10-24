package com.weekd.miracastreceiver.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 应用内切换界面语言。
 *
 * 用 AppCompat 的 per-app language：Android 13+ 由系统保存选择，并作用于所有组件；
 * Android 13 以下 AppCompat 只在内存里记住、只对 AppCompatActivity 生效，所以
 * 这里另外存一份到 [AppSettings]，每次进程启动在 [com.weekd.miracastreceiver.MiracastApp]
 * 里恢复，后台服务再用 [wrap] 自己套一层语言配置。
 */
object AppLanguage {

    /** 可选语言。名称用该语言自己的写法，看不懂当前界面语言的用户也能找到。 */
    val OPTIONS: List<Pair<String, String>> = listOf(
        "en" to "English",
        "zh-CN" to "简体中文",
        "zh-TW" to "繁體中文",
        "ja" to "日本語",
        "ko" to "한국어",
        "ru" to "Русский",
        "fr" to "Français",
        "es" to "Español",
        "th" to "ไทย",
        "hi" to "हिन्दी"
    )

    /**
     * 当前选择在 [OPTIONS] 里的下标，跟随系统时返回 -1。
     * 按语言 + 地区比较：系统设置里选的可能是 zh-Hans-CN 这种带书写体系的标签。
     */
    fun currentIndex(): Int {
        val selected = AppCompatDelegate.getApplicationLocales()[0] ?: return -1
        return OPTIONS.indexOfFirst { (tag, _) ->
            val option = Locale.forLanguageTag(tag)
            option.language == selected.language &&
                (option.country.isEmpty() || option.country == selected.country)
        }
    }

    /** 切换语言；已打开的 AppCompatActivity 会被自动重建。传空串恢复跟随系统。 */
    fun apply(context: Context, tag: String) {
        AppSettings.setLanguageTag(context, tag)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /** 进程启动时恢复上次的选择。Android 13+ 系统自己会恢复，不能再覆盖（用户可能在系统设置里改过）。 */
    fun restore(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val tag = AppSettings.getLanguageTag(context)
        if (tag.isNotEmpty()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }

    /** 给非 Activity 组件（服务）套上所选语言。Android 13+ 系统已经处理，原样返回。 */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return base
        val config = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(locales.unwrap() as android.os.LocaleList)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locales[0]
        }
        return base.createConfigurationContext(config)
    }
}
