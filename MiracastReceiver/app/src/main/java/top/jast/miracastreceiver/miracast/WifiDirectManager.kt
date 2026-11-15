package top.jast.miracastreceiver.miracast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import androidx.core.content.ContextCompat
import android.os.Looper
import timber.log.Timber

/**
 * Wi-Fi Direct (P2P) 管理器
 * 用于 Miracast/WFD 设备发现
 */
/**
 * @param deviceName 对外显示的 P2P 设备名。Windows 的「无线显示器」列表里显示的就是它，
 *   所以必须和 AirPlay / DLNA 用同一个名字（[top.jast.miracastreceiver.discovery.DeviceInfoProvider]），
 *   否则同一台设备在不同投屏方式下会显示成不同名称。
 */
class WifiDirectManager(
    private val context: Context,
    private var deviceName: String
) {
    /**
     * 不少老电视（尤其非 Android TV 认证的整机）根本没有 Wi-Fi Direct，
     * `getSystemService` 会返回 null。原先用非空强转，一启动就抛异常闪退 ——
     * 而 Miracast 只是三种投屏方式之一，不该拖垮整个应用。
     */
    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isStarted = false
    enum class StartupState { STARTING, UNSUPPORTED, PERMISSION_REQUIRED, WIFI_DISABLED, FAILED }
    var onStartupState: ((StartupState) -> Unit)? = null
    var onWfdConfigured: (() -> Unit)? = null
    var isWfdConfigured = false
        private set

    var onDeviceConnected: ((WifiP2pDevice) -> Unit)? = null
    var onDeviceDisconnected: (() -> Unit)? = null
    var onGroupCreated: ((WifiP2pGroup) -> Unit)? = null

    fun start() {
        if (isStarted) {
            Timber.w("Wi-Fi Direct already started")
            // Retry group creation after the user grants Wi-Fi Direct permission.
            createGroup()
            return
        }

        val p2p = manager
        if (p2p == null) {
            onStartupState?.invoke(StartupState.UNSUPPORTED)
            Timber.w("本机不支持 Wi-Fi Direct，Miracast 不可用（AirPlay / DLNA 不受影响）")
            return
        }

        try {
            onStartupState?.invoke(StartupState.STARTING)
            channel = p2p.initialize(context, Looper.getMainLooper(), null)

            if (channel == null) {
                onStartupState?.invoke(StartupState.FAILED)
                Timber.e("Failed to initialize Wi-Fi P2P channel")
                return
            }

            // 注册 Wi-Fi Direct 广播接收器
            registerReceiver()

            // 尝试设置系统级 Wi-Fi Display Sink 信息；Windows 发现无线显示器依赖这个 WFD IE
            setWfdInfo()

            // 创建 Wi-Fi Direct 组（作为 GO - Group Owner）
            createGroup()

            // 注册本地服务以支持服务发现
            registerLocalService()

            isStarted = true
            Timber.i("Wi-Fi Direct started for Miracast")
        } catch (e: SecurityException) {
            onStartupState?.invoke(StartupState.PERMISSION_REQUIRED)
            Timber.w(e, "Wi-Fi Direct permission required")
        } catch (e: Exception) {
            onStartupState?.invoke(StartupState.FAILED)
            Timber.e(e, "Failed to start Wi-Fi Direct")
        }
    }

    private fun setWfdInfo() {
        val ch = channel ?: return

        try {
            // 尝试使用反射设置 WFD Info（需要系统权限，但值得一试）
            val wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val wfdInfo = wfdInfoClass.newInstance()

            // setWfdEnabled(true)
            val setWfdEnabledMethod = wfdInfoClass.getMethod("setWfdEnabled", Boolean::class.java)
            setWfdEnabledMethod.invoke(wfdInfo, true)

            // setDeviceType(WFD_SOURCE = 0)  // 实际上我们是 SINK，但先尝试设置
            // 注意：PRIMARY_SINK = 1, SECONDARY_SINK = 2, SOURCE = 0
            val setDeviceTypeMethod = wfdInfoClass.getMethod("setDeviceType", Int::class.java)
            setDeviceTypeMethod.invoke(wfdInfo, 1)  // 1 = PRIMARY_SINK

            // setSessionAvailable(true)
            val setSessionAvailableMethod = wfdInfoClass.getMethod("setSessionAvailable", Boolean::class.java)
            setSessionAvailableMethod.invoke(wfdInfo, true)

            // setControlPort(7236)  // RTSP 端口
            val setControlPortMethod = wfdInfoClass.getMethod("setControlPort", Int::class.java)
            setControlPortMethod.invoke(wfdInfo, 7236)

            // setMaxThroughput(50)  // 50 Mbps
            val setMaxThroughputMethod = wfdInfoClass.getMethod("setMaxThroughput", Int::class.java)
            setMaxThroughputMethod.invoke(wfdInfo, 50)

            // 调用 WifiP2pManager.setWFDInfo()
            val setWFDInfoMethod = WifiP2pManager::class.java.getMethod(
                "setWFDInfo",
                WifiP2pManager.Channel::class.java,
                wfdInfoClass,
                WifiP2pManager.ActionListener::class.java
            )

            setWFDInfoMethod.invoke(manager, ch, wfdInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    isWfdConfigured = true
                    onWfdConfigured?.invoke()
                    Timber.i("✅ WFD Info set successfully! Windows should be able to discover this device now.")
                }

                override fun onFailure(reason: Int) {
                    val reasonText = when (reason) {
                        WifiP2pManager.ERROR -> "ERROR"
                        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                        WifiP2pManager.BUSY -> "BUSY"
                        else -> "UNKNOWN($reason)"
                    }
                    Timber.e("❌ Failed to set WFD Info: $reasonText (需要系统权限)")
                }
            })

        } catch (e: SecurityException) {
            Timber.e(e, "❌ SecurityException: setWFDInfo 需要系统权限 (CONFIGURE_WIFI_DISPLAY)")
            Timber.e("需要系统签名或 Root 权限才能让 Windows 发现此设备")
        } catch (e: ClassNotFoundException) {
            Timber.e(e, "❌ WifiP2pWfdInfo class not found")
        } catch (e: NoSuchMethodException) {
            Timber.e(e, "❌ setWFDInfo method not found")
        } catch (e: Exception) {
            Timber.e(e, "❌ Error setting WFD Info")
        }
    }

    private fun createGroup() {
        val ch = channel ?: return

        try {
            manager?.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Wi-Fi Direct group created successfully")

                    // 查询组信息
                    manager?.requestGroupInfo(ch) { group ->
                        if (group != null) {
                            Timber.i("Group created - SSID: ${group.networkName}, Owner: ${group.isGroupOwner}")
                            onGroupCreated?.invoke(group)

                            // 设置设备名称
                            setDeviceName(deviceName)
                        } else {
                            onStartupState?.invoke(StartupState.FAILED)
                        }
                    }
                }

                override fun onFailure(reason: Int) {
                    val reasonText = when (reason) {
                        WifiP2pManager.ERROR -> "ERROR"
                        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                        WifiP2pManager.BUSY -> "BUSY"
                        else -> "UNKNOWN($reason)"
                    }
                    Timber.e("Failed to create Wi-Fi Direct group: $reasonText")

                    // 如果失败，尝试作为客户端模式
                    if (reason == WifiP2pManager.BUSY) {
                        // 可能已经有组存在，尝试获取当前组信息
                        manager?.requestGroupInfo(ch) { group ->
                            if (group != null) {
                                Timber.i("Existing group found: ${group.networkName}")
                                onGroupCreated?.invoke(group)
                                setDeviceName(deviceName)
                            } else {
                                onStartupState?.invoke(StartupState.FAILED)
                            }
                        }
                    } else {
                        onStartupState?.invoke(if (reason == WifiP2pManager.P2P_UNSUPPORTED)
                            StartupState.UNSUPPORTED else StartupState.FAILED)
                    }
                }
            })
        } catch (e: SecurityException) {
            onStartupState?.invoke(StartupState.PERMISSION_REQUIRED)
            Timber.e(e, "Security exception when creating group - missing permissions?")
        } catch (e: Exception) {
            onStartupState?.invoke(StartupState.FAILED)
            Timber.e(e, "Exception when creating group")
        }
    }

    /**
     * 尝试把 P2P 设备名改成 [deviceName]（安卓手机投屏列表里显示的就是它）。
     *
     * Android 11 起系统要求调用方持有 NETWORK_SETTINGS / NETWORK_STACK / OVERRIDE_WIFI_CONFIG
     * 之一，普通应用通常拿不到。旧系统或系统签名构建可直接成功；
     * 通过 root 开启 WFD 的设备由服务另外调用 WfdRootHelper 同步名称。
     */
    private fun setDeviceName(name: String) {
        val p2pName = P2pDeviceName.fromDisplayName(name) ?: return
        try {
            // 使用反射设置设备名称（API 限制）
            val setDeviceNameMethod = WifiP2pManager::class.java.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )

            setDeviceNameMethod.invoke(manager, channel, p2pName, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Device name set to: $name")
                }

                override fun onFailure(reason: Int) {
                    Timber.w("Failed to set device name: $reason")
                }
            })
        } catch (e: Exception) {
            Timber.w(e, "Could not set device name (requires system permissions)")
        }
    }

    fun updateDeviceName(name: String) {
        deviceName = name
        if (channel != null) setDeviceName(name)
    }

    private fun registerLocalService() {
        val ch = channel ?: return

        try {
            // 创建 WFD 服务信息
            val record = mapOf(
                "version" to "1.0",
                "type" to "miracast-sink",
                "rtsp_port" to "7236"
            )

            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                "_miracast", "_tcp", record
            )

            manager?.addLocalService(ch, serviceInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Local Miracast service registered")
                }

                override fun onFailure(reason: Int) {
                    Timber.e("Failed to register local service: $reason")
                }
            })

            // Miracast 依赖 P2P 设备发现与 WFD IE；discoverServices 没有
            // addServiceRequest 时会以 NO_SERVICE_REQUESTS 失败，无法启动发现。
            manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.i("Miracast peer discovery started")
                }

                override fun onFailure(reason: Int) {
                    Timber.e("Failed to start Miracast peer discovery: $reason")
                }
            })
        } catch (e: SecurityException) {
            // 用户拒绝定位权限时走这里 —— Miracast 用不了，但不该影响 AirPlay / DLNA
            Timber.w("注册 P2P 本地服务被拒绝（缺少定位权限），Miracast 不可用")
        } catch (e: Exception) {
            Timber.e(e, "Error registering local service")
        }
    }

    private fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        if (!enabled) onStartupState?.invoke(StartupState.WIFI_DISABLED)
                        Timber.d("Wi-Fi P2P state changed: ${if (enabled) "ENABLED" else "DISABLED"}")
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        Timber.d("Wi-Fi P2P peers changed")
                        // requestPeers 需要定位权限，用户拒绝时会抛 SecurityException。
                        // 这里是广播回调，异常没人接就直接崩掉整个应用。
                        channel?.let { ch ->
                            try {
                                manager?.requestPeers(ch) { peerList ->
                                    Timber.d("Peers discovered: ${peerList.deviceList.size}")
                                }
                            } catch (e: SecurityException) {
                                Timber.w("requestPeers 被拒绝（缺少定位权限）")
                            }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        Timber.d("Wi-Fi P2P connection changed")
                        channel?.let { ch ->
                            manager?.requestConnectionInfo(ch) { info ->
                                if (info.groupFormed) {
                                    Timber.i("P2P Group formed - Group Owner: ${info.isGroupOwner}")

                                    if (info.isGroupOwner) {
                                        Timber.i("This device is Group Owner, IP: ${info.groupOwnerAddress?.hostAddress}")
                                    }
                                }
                            }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        Timber.d("This device changed: ${device?.deviceName}, Status: ${device?.status}")
                    }
                }
            }
        }

        try {
            // 必须走 ContextCompat：带 flags 的 registerReceiver 重载是 API 26 才有的，
            // 在 Android 5/6/7 上直接调用会抛 NoSuchMethodError 导致应用一启动就闪退
            // （minSdk 是 21，这类老电视确实装得上）。
            ContextCompat.registerReceiver(
                context, receiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            Timber.i("Wi-Fi P2P broadcast receiver registered")
        } catch (e: Exception) {
            Timber.e(e, "Failed to register Wi-Fi P2P receiver")
        }
    }

    fun stop() {
        if (!isStarted) return

        try {
            // 移除本地服务
            channel?.let { ch ->
                manager?.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Timber.i("Local services cleared")
                    }

                    override fun onFailure(reason: Int) {
                        Timber.w("Failed to clear local services: $reason")
                    }
                })

                // 移除组
                manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Timber.i("Wi-Fi Direct group removed")
                    }

                    override fun onFailure(reason: Int) {
                        Timber.w("Failed to remove group: $reason")
                    }
                })
            }

            // 注销广播接收器
            receiver?.let {
                context.unregisterReceiver(it)
                receiver = null
            }

            channel = null
            isStarted = false
            Timber.i("Wi-Fi Direct stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping Wi-Fi Direct")
        }
    }

    fun isRunning(): Boolean = isStarted

    /** Drop the current sender's P2P link, then prepare a fresh receiving group. */
    fun disconnectSender() {
        val ch = channel ?: return
        manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (isStarted && channel === ch) createGroup()
            }
            override fun onFailure(reason: Int) {
                Timber.w("Miracast disconnect group failed: $reason")
            }
        })
    }
}
