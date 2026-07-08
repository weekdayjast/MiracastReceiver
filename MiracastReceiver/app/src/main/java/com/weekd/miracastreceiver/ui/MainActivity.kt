package com.weekd.miracastreceiver.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.discovery.DeviceInfoProvider
import com.weekd.miracastreceiver.discovery.MdnsAdvertiser
import com.weekd.miracastreceiver.service.CastReceiverService
import com.weekd.miracastreceiver.utils.NetworkUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import android.widget.TextView

/**
 * 主 Activity - 等待投屏连接
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mdnsAdvertiser: MdnsAdvertiser
    private lateinit var deviceInfoProvider: DeviceInfoProvider

    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceIp: TextView
    private lateinit var tvConnectionCode: TextView
    private lateinit var tvStatus: TextView

    private var connectionCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initServices()
        initViews()
        checkNetworkAndStart()
    }

    private fun initServices() {
        deviceInfoProvider = DeviceInfoProvider(this)
        mdnsAdvertiser = MdnsAdvertiser(this)

        // 生成连接码
        connectionCode = NetworkUtils.generateConnectionCode()
    }

    private fun initViews() {
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvDeviceIp = findViewById(R.id.tv_device_ip)
        tvConnectionCode = findViewById(R.id.tv_connection_code)
        tvStatus = findViewById(R.id.tv_status)

        // 设置设备名称
        val deviceName = deviceInfoProvider.getDeviceName()
        tvDeviceName.text = deviceName

        // 设置连接码
        tvConnectionCode.text = getString(R.string.connection_code, connectionCode)
    }

    private fun checkNetworkAndStart() {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            tvStatus.text = "网络未连接"
            Timber.w("Network not available")
            return
        }

        if (!NetworkUtils.isWifiConnected(this)) {
            tvStatus.text = "未连接到 Wi-Fi"
            Timber.w("WiFi not connected")
        }

        // 获取 IP 地址
        val ipAddress = NetworkUtils.getLocalIpAddress()
        if (ipAddress != null) {
            tvDeviceIp.text = getString(R.string.device_ip, ipAddress)
            Timber.i("Local IP: $ipAddress")
        } else {
            tvDeviceIp.text = getString(R.string.device_ip, "获取中...")
        }

        // 启动服务
        startCastService()
        startAdvertising()

        // 更新状态
        updateStatus()
    }

    private fun startCastService() {
        val intent = Intent(this, CastReceiverService::class.java)
        startService(intent)
        Timber.i("Cast receiver service started")
    }

    private fun startAdvertising() {
        val deviceInfo = deviceInfoProvider.getDeviceInfo()
        val deviceName = deviceInfoProvider.getDeviceName()
        val macAddress = NetworkUtils.getMacAddress()

        // 启动 AirPlay 服务广播（用于 iPhone 投屏）
        mdnsAdvertiser.startAirPlayAdvertising(
            deviceName = deviceName,
            macAddress = macAddress,
            port = 7000
        )

        // 启动自定义 Miracast 服务广播（用于自定义 Android 发送端）
        mdnsAdvertiser.startAdvertising(
            serviceName = deviceName,
            port = 8080,
            deviceInfo = deviceInfo + ("code" to connectionCode)
        )

        Timber.i("Started AirPlay and Miracast advertising: $deviceName")
    }

    private fun updateStatus() {
        lifecycleScope.launch {
            while (true) {
                if (mdnsAdvertiser.isAdvertising()) {
                    tvStatus.text = getString(R.string.waiting_connection)
                } else {
                    tvStatus.text = "服务启动中..."
                }
                delay(2000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mdnsAdvertiser.stopAdvertising()
        Timber.i("MainActivity destroyed")
    }
}
