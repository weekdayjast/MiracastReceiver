package top.jast.miracastreceiver

import android.app.Application
import android.os.Build
import top.jast.miracastreceiver.util.AppLanguage
import timber.log.Timber

/**
 * Miracast Receiver Application
 */
class MiracastApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化 Timber 日志库
        if (BuildConfig.DEBUG) {
            Timber.plant(object : Timber.DebugTree() {
                override fun isLoggable(tag: String?, priority: Int): Boolean =
                    priority >= android.util.Log.INFO
            })
        }

        // Both the UI and :castplus_nearby process load this before the SDK.
        // Earlier Android versions retain the original netlink watcher.
        if (Build.VERSION.SDK_INT >= 30) {
            System.loadLibrary("castplus_network_compat")
        }

        // 要在任何 Activity / 服务创建前恢复界面语言
        AppLanguage.restore(this)

        Timber.i("MiracastApp initialized")
    }
}
