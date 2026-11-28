package top.jast.miracastreceiver.miracast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无 root 回退：调用 JMGO 固件里的 com.jmgo.systemapi.SettingService，
 * 以 uid 1000(system) 身份执行 shell 命令。
 *
 * 协议来自 com.exec.cmd 源码（Binder 直调，无需依赖该 APK）：
 *   ComponentName("com.jmgo.systemapi", "com.jmgo.systemapi.SettingService")
 *   writeInterfaceToken("com.jmgo.systemapi.ISettings")
 *   writeString(command)
 *   transact(10, ...) -> String
 *
 * 注意：该服务对 execCommand 的返回值恒为 null（实测），因此这里只把
 * “transact 未抛异常”当作命令已被接受；是否真正生效由调用方检查副作用。
 */
object SystemExecClient {
    private const val SERVICE_PKG = "com.jmgo.systemapi"
    private const val SERVICE_CLS = "com.jmgo.systemapi.SettingService"
    private const val INTERFACE = "com.jmgo.systemapi.ISettings"
    private const val TRANSACTION_EXEC = 10

    private val main = Handler(Looper.getMainLooper())

    /** 固件是否安装了该 system 执行器。 */
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SERVICE_PKG, 0)
        true
    }.getOrDefault(false)

    /**
     * 以 uid 1000 执行一条命令；返回 transact 是否被服务接受（不代表命令成功）。
     */
    fun exec(context: Context, command: String, timeoutMs: Long = 40_000): Boolean {
        Timber.i("SystemExec: exec(%d chars) ...", command.length)
        val done = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        val unbound = AtomicBoolean(false)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                Timber.i("SystemExec: connected, binder=%s", binder != null)
                // transact 会阻塞到命令执行完，必须离开主线程。
                Thread {
                    try {
                        if (binder != null) accepted.set(transact(binder, command))
                    } catch (e: Exception) {
                        Timber.w(e, "SystemExec transact failed")
                    } finally {
                        done.countDown()
                        if (unbound.compareAndSet(false, true)) {
                            main.post { runCatching { context.unbindService(this) } }
                        }
                    }
                }.start()
            }

            override fun onServiceDisconnected(name: ComponentName?) { }
        }

        var bound = false
        return try {
            val intent = Intent().setComponent(ComponentName(SERVICE_PKG, SERVICE_CLS))
            bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            if (!bound) {
                Timber.w("SystemExec: bindService returned false")
                return false
            }
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Timber.w("SystemExec: timeout after %d ms", timeoutMs)
                if (unbound.compareAndSet(false, true)) {
                    runCatching { context.unbindService(conn) }
                }
                return false
            }
            accepted.get()
        } catch (e: Exception) {
            Timber.w(e, "SystemExec bind failed")
            if (bound && unbound.compareAndSet(false, true)) {
                runCatching { context.unbindService(conn) }
            }
            false
        }
    }

    private fun transact(binder: IBinder, command: String): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE)
            data.writeString(command)
            binder.transact(TRANSACTION_EXEC, data, reply, 0)
        } catch (e: Exception) {
            Timber.w(e, "SystemExec transact exception")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
