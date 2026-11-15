package top.jast.miracastreceiver.miracast

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * 通过 root 向 wpa_supplicant 注入 Wi-Fi Display Sink 的 WFD IE。
 *
 * 为什么必须走 root：Windows 靠扫描 beacon/probe response 里的 WFD IE 来发现无线显示器，
 * 而应用层调 `WifiP2pManager.setWFDInfo()` 需要 signature 级的 `CONFIGURE_WIFI_DISPLAY`
 * 权限，普通应用必定失败（实测：`SecurityException: Wifi Display Permission denied`）。
 * 唯一的绕法是直接和 wpa_supplicant 的控制 socket 对话，而那个 socket 需要 root + 合适的
 * SELinux 上下文，所以借助随 APK 打包的 [BINARY_NAME]（见 cpp/wfdctl.c）由 su 拉起。
 *
 * 没有 root 时全部方法安全失败并返回 false —— Miracast 发现不了，其余投屏方式不受影响。
 */
object WfdRootHelper {
    enum class AdvertiseResult { COMMAND_SENT, ROOT_UNAVAILABLE, HELPER_MISSING, SOCKET_UNAVAILABLE, COMMAND_FAILED }

    private const val BINARY_NAME = "libwfdctl.so"

    /** wpa_supplicant 控制 socket 的常见位置，按新旧顺序尝试。 */
    private val CTRL_SOCKET_PATHS = listOf(
        "/data/vendor/wifi/wpa/sockets/p2p0",
        "/data/misc/wifi/sockets/p2p0",
        "/data/vendor/wifi/wpa/sockets/wlan0",
        "/data/misc/wifi/sockets/wlan0"
    )

    /**
     * WFD 设备信息子元素（子元素 0），共 6 字节负载：
     *   0011 → 设备类型 primary sink(01) + 会话可用(bits4-5=01)
     *   1c44 → RTSP 会话管理控制端口 7236
     *   0032 → 最大吞吐 50 Mbps
     * 前面的 0006 是子元素长度字段，wpa_supplicant 要求连长度一起给。
     */
    private fun subelemHex(controlPort: Int, maxThroughputMbps: Int = 50): String =
        "0006" + "0011" + "%04x".format(controlPort) + "%04x".format(maxThroughputMbps)

    /**
     * 注入 WFD IE，让本机以 Miracast Sink 的身份被发现。
     *
     * @param controlPort 我们在 IE 里宣告的 RTSP 控制端口（WFD 标准 7236）
     * @return 是否成功执行（不代表 Windows 一定能发现，还取决于驱动是否支持 sink 模式）
     */
    fun advertiseSink(context: Context, controlPort: Int = 7236): Boolean =
        advertiseSinkResult(context, controlPort) == AdvertiseResult.COMMAND_SENT

    fun advertiseSinkResult(context: Context, controlPort: Int = 7236): AdvertiseResult {
        if (!isRootAvailable()) return AdvertiseResult.ROOT_UNAVAILABLE
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) {
            Timber.w("WFD: $BINARY_NAME not found in nativeLibraryDir")
            return AdvertiseResult.HELPER_MISSING
        }

        val socketPath = CTRL_SOCKET_PATHS.firstOrNull { runAsRoot("ls $it") }
        if (socketPath == null) {
            Timber.w("WFD: no accessible wpa_supplicant control socket found")
            return AdvertiseResult.SOCKET_UNAVAILABLE
        }

        val cmd = "${binary.absolutePath} $socketPath " +
            "\"SET wifi_display 1\" " +
            "\"WFD_SUBELEM_SET 0 ${subelemHex(controlPort)}\""

        return if (runAsRoot(cmd)) {
            Timber.i("WFD: sink IE injected via $socketPath (control port $controlPort)")
            AdvertiseResult.COMMAND_SENT
        } else {
            Timber.w("WFD: sink IE command failed or timed out after root authorization")
            AdvertiseResult.COMMAND_FAILED
        }
    }

    /** Rename the native P2P device after this service has already enabled WFD via root. */
    fun setDeviceName(context: Context, displayName: String): Boolean {
        val name = P2pDeviceName.fromDisplayName(displayName) ?: return false
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) return false
        val socket = CTRL_SOCKET_PATHS.firstOrNull { runAsRoot("ls $it") } ?: return false
        val command = P2pDeviceName.command(binary.absolutePath, socket, name) ?: return false
        if (!runAsRoot(command)) return false
        // Keep Android's stored P2P name in sync, so framework reinitialization keeps it.
        if (!runAsRoot("settings put global wifi_p2p_device_name ${P2pDeviceName.quote(name)}")) {
            Timber.w("WFD: P2P name sent, but system name persistence failed")
        }
        Timber.i("WFD: P2P device name command sent")
        return true
    }

    /** 撤销 WFD 广播（关闭投屏接收时调用，避免一直对外宣告自己是显示器）。 */
    fun stopAdvertising(context: Context): Boolean {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) return false
        val socketPath = CTRL_SOCKET_PATHS.firstOrNull { runAsRoot("ls $it") } ?: return false
        return runAsRoot("${binary.absolutePath} $socketPath \"SET wifi_display 0\"")
    }

    /** 设备是否可用 root（用于 UI 提示 Miracast 是否可能工作）。 */
    fun isRootAvailable(): Boolean = runAsRoot("test \"$(id -u)\" = 0")

    private fun runAsRoot(command: String): Boolean = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        waitForRootCommand(process)
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        Timber.d("WFD root unavailable: ${e.message}")
        false
    }
}
