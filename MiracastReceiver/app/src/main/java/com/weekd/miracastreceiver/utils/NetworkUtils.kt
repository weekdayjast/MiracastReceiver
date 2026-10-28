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
     * 获取本机 IP 地址
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    // 只返回 IPv4 地址，排除回环地址
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        Timber.d("Found IP address: $ip")
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting local IP address")
        }
        return null
    }

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
