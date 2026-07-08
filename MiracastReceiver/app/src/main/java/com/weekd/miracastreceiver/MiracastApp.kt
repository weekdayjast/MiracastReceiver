package com.weekd.miracastreceiver

import android.app.Application
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

        Timber.i("MiracastApp initialized")
    }
}
