package top.jast.miracastreceiver.miracast

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log

/**
 * 以 uid 1000(system) 运行的入口：由 vendor 的 system 执行器通过 app_process 启动。
 *
 * 为什么这样做：`WifiP2pManager.setWFDInfo()` 需要 signature 级的
 * `CONFIGURE_WIFI_DISPLAY` 权限。普通应用拿不到，但 `android.uid.system`
 * （uid 1000，本 ROM 的 com.jmgo.systemapi 就在这个 shared uid 下）已被授予该权限
 * （`dumpsys package com.jmgo.systemapi` 可见 granted=true）。因此在 uid 1000 的
 * 进程里调这个 API 就能走框架正常路径，无需 root、无需直接和 supplicant socket 对话。
 *
 * 启动方式（由 WfdRootHelper 拼好交给 system 执行器）：
 *   app_process -Djava.class.path=<base.apk> /system/bin \
 *       top.jast.miracastreceiver.miracast.WfdSystemMain <controlPort>
 */
object WfdSystemMain {
    private const val TAG = "WfdSystem"
    private const val RESULT_PROP = "debug.jmgo.wfd.framework"

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.firstOrNull()?.toIntOrNull() ?: 7236
        if (args.firstOrNull() == "list") {
            listApi()
            System.exit(0)
        }
        try {
            runCatching { Looper.prepareMainLooper() }
            setProp(RESULT_PROP, "0")

            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("systemMain").invoke(null)
            val ctx = at.getMethod("getSystemContext").invoke(thread) as Context

            val mgr = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            val channel = mgr.initialize(ctx, Looper.getMainLooper(), null)

            val wfdCls = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val info = wfdCls.getDeclaredConstructor().newInstance()
            // 这台 ROM 用 setEnabled/isEnabled，AOSP 用 setWfdEnabled；两个都容错尝试。
            if (!invokeIfPresent(wfdCls, info, "setEnabled", Boolean::class.javaPrimitiveType, true)) {
                invokeIfPresent(wfdCls, info, "setWfdEnabled", Boolean::class.javaPrimitiveType, true)
            }
            val primarySink = runCatching { wfdCls.getField("DEVICE_TYPE_PRIMARY_SINK").getInt(null) }
                .getOrDefault(1)
            invokeIfPresent(wfdCls, info, "setDeviceType", Int::class.javaPrimitiveType, primarySink)
            invokeIfPresent(wfdCls, info, "setSessionAvailable", Boolean::class.javaPrimitiveType, true)
            invokeIfPresent(wfdCls, info, "setControlPort", Int::class.javaPrimitiveType, port)
            invokeIfPresent(wfdCls, info, "setMaxThroughput", Int::class.javaPrimitiveType, 50)

            // 有的 ROM 是 setWFDInfo，有的是 setWfdInfo；两个都试。
            val args = arrayOf(
                WifiP2pManager.Channel::class.java,
                wfdCls,
                WifiP2pManager.ActionListener::class.java
            )
            val method = runCatching { WifiP2pManager::class.java.getMethod("setWFDInfo", *args) }
                .getOrElse { WifiP2pManager::class.java.getMethod("setWfdInfo", *args) }
            method.invoke(mgr, channel, info, null)
            Log.i(TAG, "setWFDInfo invoked (port=$port)")
            Thread.sleep(1500)
            setProp(RESULT_PROP, "1")
            Log.i(TAG, "done")
        } catch (t: Throwable) {
            Log.e(TAG, "failed: ${t}", t)
        } finally {
            System.exit(0)
        }
    }

    private fun listApi() {
        runCatching {
            val wfdCls = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            wfdCls.declaredConstructors.forEach { c ->
                Log.i(TAG, "ctor(${c.parameterTypes.joinToString { it.simpleName }})")
            }
            wfdCls.declaredFields.forEach { f ->
                Log.i(TAG, "field ${f.name}: ${f.type.simpleName}")
            }
            wfdCls.declaredMethods.sortedBy { it.name }.forEach { m ->
                Log.i(TAG, "method ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }
        }
    }

    private fun invokeIfPresent(cls: Class<*>, target: Any, name: String, type: Class<*>?, value: Any): Boolean =
        runCatching { cls.getMethod(name, type).invoke(target, value); true }
            .onFailure { Log.w(TAG, "skip $name: ${it.message}") }
            .getOrDefault(false)

    private fun setProp(key: String, value: String) {
        runCatching {
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("set", String::class.java, String::class.java).invoke(null, key, value)
        }
    }
}
