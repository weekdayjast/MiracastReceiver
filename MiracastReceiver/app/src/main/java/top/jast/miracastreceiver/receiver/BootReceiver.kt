package top.jast.miracastreceiver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import top.jast.miracastreceiver.service.CastReceiverService
import top.jast.miracastreceiver.util.AppSettings
import timber.log.Timber

/**
 * 开机自启：电视启动完成后把投屏接收服务拉起来，不打开主界面。
 *
 * 开机时 Wi-Fi / IP / mDNS 往往还没就绪，这里不做等待——服务自己会轮询到
 * 拿得到局域网 IP 再启动各路接收器（见 CastReceiverService.startWhenNetworkReady）。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // 部分国产电视/盒子快速开机走的是自定义广播，不发标准 BOOT_COMPLETED
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            // 应用升级后进程会被杀掉，顺手恢复服务
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in BOOT_ACTIONS) return

        if (!AppSettings.isAutoStartOnBoot(context)) {
            Timber.i("Boot received ($action) but auto-start is disabled")
            return
        }

        val serviceIntent = Intent(context, CastReceiverService::class.java).apply {
            putExtra(CastReceiverService.EXTRA_FROM_BOOT, true)
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            Timber.i("Boot received ($action), cast receiver service starting")
        } catch (e: Exception) {
            // Android 12+ 对后台启动前台服务有限制，MY_PACKAGE_REPLACED 这类
            // 非开机广播可能被拒；不能让接收器因此崩掉。
            Timber.e(e, "Failed to start cast service from $action")
        }
    }
}
