package com.weekd.miracastreceiver.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import timber.log.Timber

/**
 * mDNS 服务广播器
 * 支持 AirPlay 和自定义 Miracast 接收协议
 */
class MdnsAdvertiser(private val context: Context) {

    companion object {
        const val AIRPLAY_SERVICE_TYPE = "_airplay._tcp."
        const val RAOP_SERVICE_TYPE = "_raop._tcp."
        const val MIRACAST_SERVICE_TYPE = "_miracast-receiver._tcp."
        const val DEFAULT_PORT = 7000
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var airplayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null
    private var miracastListener: NsdManager.RegistrationListener? = null

    private var isAirplayRegistered = false
    private var isRaopRegistered = false
    private var isMiracastRegistered = false

    /**
     * 启动 AirPlay 服务广播
     */
    fun startAirPlayAdvertising(deviceName: String, macAddress: String, port: Int = DEFAULT_PORT) {
        // 注册 AirPlay 服务
        registerAirPlayService(deviceName, macAddress, port)

        // 注册 RAOP 服务（Remote Audio Output Protocol）
        registerRaopService(deviceName, macAddress, port)
    }

    private fun registerAirPlayService(deviceName: String, macAddress: String, port: Int) {
        if (isAirplayRegistered) {
            Timber.w("AirPlay service already registered")
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceType = AIRPLAY_SERVICE_TYPE
            serviceName = deviceName
            this.port = port

            // AirPlay 服务属性
            setAttribute("deviceid", macAddress)
            setAttribute("features", "0x5A7FFFF7,0x1E") // AirPlay 2 features
            setAttribute("flags", "0x4")
            setAttribute("model", "AppleTV6,2")
            setAttribute("pi", macAddress)
            setAttribute("psi", "00000000-0000-0000-0000-$macAddress")
            setAttribute("pk", generatePublicKey())
            setAttribute("srcvers", "366.0")
            setAttribute("vv", "2")
        }

        airplayListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                isAirplayRegistered = true
                Timber.i("AirPlay service registered: ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isAirplayRegistered = false
                Timber.e("AirPlay registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isAirplayRegistered = false
                Timber.i("AirPlay service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("AirPlay unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, airplayListener)
            Timber.i("Starting AirPlay advertising: $deviceName on port $port")
        } catch (e: Exception) {
            Timber.e(e, "Error starting AirPlay advertising")
            isAirplayRegistered = false
        }
    }

    private fun registerRaopService(deviceName: String, macAddress: String, port: Int) {
        if (isRaopRegistered) {
            Timber.w("RAOP service already registered")
            return
        }

        val raopName = "$macAddress@$deviceName"

        val serviceInfo = NsdServiceInfo().apply {
            serviceType = RAOP_SERVICE_TYPE
            serviceName = raopName
            this.port = port

            // RAOP 服务属性
            setAttribute("txtvers", "1")
            setAttribute("ch", "2") // 立体声
            setAttribute("cn", "0,1,2,3")
            setAttribute("da", "true")
            setAttribute("et", "0,3,5")
            setAttribute("md", "0,1,2")
            setAttribute("pw", "false") // 不需要密码
            setAttribute("sr", "44100") // 采样率
            setAttribute("ss", "16") // 采样大小
            setAttribute("sv", "false")
            setAttribute("tp", "UDP")
            setAttribute("vn", "65537")
            setAttribute("vs", "366.0")
            setAttribute("am", "AppleTV6,2")
        }

        raopListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                isRaopRegistered = true
                Timber.i("RAOP service registered: ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isRaopRegistered = false
                Timber.e("RAOP registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isRaopRegistered = false
                Timber.i("RAOP service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("RAOP unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, raopListener)
            Timber.i("Starting RAOP advertising: $raopName on port $port")
        } catch (e: Exception) {
            Timber.e(e, "Error starting RAOP advertising")
            isRaopRegistered = false
        }
    }

    /**
     * 开始广播自定义 Miracast 服务
     */
    fun startAdvertising(
        serviceName: String,
        port: Int = 8080,
        deviceInfo: Map<String, String> = emptyMap()
    ) {
        if (isMiracastRegistered) {
            Timber.w("Miracast service already registered")
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceType = MIRACAST_SERVICE_TYPE
            this.serviceName = serviceName
            this.port = port

            deviceInfo.forEach { (key, value) ->
                setAttribute(key, value)
            }
            setAttribute("version", "1.0")
            setAttribute("platform", "android-tv")
            setAttribute("protocol", "webrtc,rtsp")
        }

        miracastListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                isMiracastRegistered = true
                Timber.i("Miracast service registered: ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                isMiracastRegistered = false
                Timber.e("Miracast registration failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isMiracastRegistered = false
                Timber.i("Miracast service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Timber.e("Miracast unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, miracastListener)
            Timber.i("Starting Miracast advertising: $serviceName on port $port")
        } catch (e: Exception) {
            Timber.e(e, "Error starting Miracast advertising")
            isMiracastRegistered = false
        }
    }

    /**
     * 停止所有广播服务
     */
    fun stopAdvertising() {
        stopAirPlayAdvertising()
        stopMiracastAdvertising()
    }

    fun stopAirPlayAdvertising() {
        airplayListener?.let {
            try {
                nsdManager.unregisterService(it)
                Timber.i("Stopping AirPlay advertising")
            } catch (e: Exception) {
                Timber.e(e, "Error stopping AirPlay advertising")
            } finally {
                airplayListener = null
                isAirplayRegistered = false
            }
        }

        raopListener?.let {
            try {
                nsdManager.unregisterService(it)
                Timber.i("Stopping RAOP advertising")
            } catch (e: Exception) {
                Timber.e(e, "Error stopping RAOP advertising")
            } finally {
                raopListener = null
                isRaopRegistered = false
            }
        }
    }

    fun stopMiracastAdvertising() {
        miracastListener?.let {
            try {
                nsdManager.unregisterService(it)
                Timber.i("Stopping Miracast advertising")
            } catch (e: Exception) {
                Timber.e(e, "Error stopping Miracast advertising")
            } finally {
                miracastListener = null
                isMiracastRegistered = false
            }
        }
    }

    fun isAdvertising(): Boolean = isAirplayRegistered || isRaopRegistered || isMiracastRegistered

    private fun generatePublicKey(): String {
        // 简化版本，实际应该生成真实的 Ed25519 公钥
        // 这里返回一个占位符
        return "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
