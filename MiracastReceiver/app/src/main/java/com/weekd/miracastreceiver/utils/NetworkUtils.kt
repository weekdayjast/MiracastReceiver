package com.weekd.miracastreceiver.utils

import android.content.Context
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网络工具类
 */
object NetworkUtils {

    /**
     * 检查网络是否连接。
     *
     * `activeNetwork` / `getNetworkCapabilities` 是 API 23 起才有的，而 minSdk 是 21 ——
     * 在 Android 5.x 上直接调用会 NoSuchMethodError，且这两个方法在应用启动路径上，
     * 结果就是一装上就闪退。低版本回退到已废弃但一直可用的 activeNetworkInfo。
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.isConnected == true
    }

    /**
     * 检查是否连接到 Wi-Fi。低版本同样回退到废弃 API，原因见 [isNetworkAvailable]。
     */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
    }

    /**
     * 获取本机在当前局域网里的 IPv4 地址。
     *
     * 优先问系统「默认网络是哪一张网卡」，取它的 LinkProperties —— 这才是手机
     * 真正能访问到的那个地址。
     *
     * 早期版本是直接遍历 NetworkInterface 取第一个非回环 IPv4，但遍历顺序由内核
     * 枚举顺序决定，电视上 p2p0（Wi-Fi Direct，常见 192.168.49.x / 通配 192.168.x.x）
     * 往往排在 wlan0 前面。结果是界面和 mDNS TXT 里报出去的地址是投屏根本用不到
     * 的 p2p 地址，用户照着这个 IP 去连是连不上的。DeviceInfoProvider 和 DLNA
     * 也走同一个方法，所以这个错误地址还会被写进 AirPlay / DLNA 的 announce 报文。
     *
     * @param context 传了就优先走系统的默认网络；为 null 时退回纯网卡遍历（兼容旧调用）。
     */
    fun getLocalIpAddress(context: Context? = null): String? {
        if (context != null) {
            getDefaultNetworkIpv4(context)?.let { return it }
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!isRealNetworkInterface(networkInterface)) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    // 只返回 IPv4 地址，排除回环地址
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        Timber.d("Found IP address: $ip (${networkInterface.name})")
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting local IP address")
        }
        return null
    }

    /** 默认网络（有路由的那张网卡）的 IPv4 地址。API 23 起才有 activeNetwork。 */
    private fun getDefaultNetworkIpv4(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null

        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork ?: return null
            val linkProperties = cm.getLinkProperties(network) ?: return null

            val ip = linkProperties.linkAddresses
                .map { it.address }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress

            ip?.let { Timber.d("Found IP address: $it (default network)") }
            ip
        } catch (e: Exception) {
            Timber.w(e, "Error reading address of the default network")
            null
        }
    }

    /**
     * 是否是一张真实的上网网卡。
     *
     * 跳过 p2p（Wi-Fi Direct）、dummy、tun/tap（VPN）、rmnet（蜂窝）等虚拟接口：
     * 它们也有 IPv4 地址，但都不是手机该连的那个。
     */
    private fun isRealNetworkInterface(networkInterface: NetworkInterface): Boolean {
        if (networkInterface.isLoopback) return false
        if (!networkInterface.isUp) return false

        val name = networkInterface.name.lowercase()
        return VIRTUAL_INTERFACE_PREFIXES.none { name.startsWith(it) }
    }

    /** 虚拟网卡名前缀，见 [isRealNetworkInterface]。 */
    private val VIRTUAL_INTERFACE_PREFIXES = listOf(
        "p2p", "dummy", "tun", "tap", "rmnet", "ppp", "sit", "ip6tnl", "ipsec", "clat"
    )

    /**
     * 获取 Wi-Fi SSID
     */
    fun getWifiSSID(context: Context): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            return wifiInfo.ssid?.replace("\"", "")
        } catch (e: Exception) {
            Timber.e(e, "Error getting WiFi SSID")
        }
        return null
    }

    /**
     * 获取设备 MAC 地址（用于 AirPlay）
     */
    fun getMacAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // 跳过回环接口
                if (networkInterface.isLoopback) continue

                // 获取硬件地址
                val mac = networkInterface.hardwareAddress
                if (mac != null && mac.isNotEmpty()) {
                    val macAddress = mac.joinToString(":") {
                        String.format("%02X", it)
                    }
                    Timber.d("Found MAC address: $macAddress")
                    return macAddress
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting MAC address")
        }

        // 如果获取失败，生成一个随机 MAC 地址
        return generateRandomMacAddress()
    }

    /**
     * 生成随机 MAC 地址
     */
    private fun generateRandomMacAddress(): String {
        val mac = ByteArray(6) { (0..255).random().toByte() }
        // 设置本地管理位
        mac[0] = (mac[0].toInt() or 0x02).toByte()
        return mac.joinToString(":") { String.format("%02X", it) }
    }
}
