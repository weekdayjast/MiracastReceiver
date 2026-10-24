package com.weekd.miracastreceiver

import android.app.Application
import com.weekd.miracastreceiver.util.AppLanguage
import timber.log.Timber

/**
 * Miracast Receiver Application
 */
class MiracastApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 初始化 Timber 日志库
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 要在任何 Activity / 服务创建前恢复界面语言
        AppLanguage.restore(this)

        Timber.i("MiracastApp initialized")
    }
}
