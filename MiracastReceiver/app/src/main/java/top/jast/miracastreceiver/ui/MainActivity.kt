package top.jast.miracastreceiver.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import top.jast.miracastreceiver.R
import top.jast.miracastreceiver.discovery.DeviceInfoProvider
import top.jast.miracastreceiver.discovery.MdnsAdvertiser
import top.jast.miracastreceiver.service.CastReceiverService
import top.jast.miracastreceiver.service.ReceiverStatus
import top.jast.miracastreceiver.castplus.CastPlusReceiver
import top.jast.miracastreceiver.util.AppLanguage
import top.jast.miracastreceiver.util.AppSettings
import top.jast.miracastreceiver.utils.NetworkUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import android.widget.TextView
import android.widget.ProgressBar

/**
 * 主 Activity - 等待投屏连接
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mdnsAdvertiser: MdnsAdvertiser
    private lateinit var deviceInfoProvider: DeviceInfoProvider

    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceIp: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvMiracastStatus: TextView
    private lateinit var startupProgress: ProgressBar
    private lateinit var switchAutoStart: SwitchCompat
    private lateinit var switchReturnToApp: SwitchCompat
    private lateinit var tvLanguageValue: TextView
    private lateinit var deviceNameDialog: DeviceNameDialog
    private val nameListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppSettings.KEY_RECEIVER_NAME) {
            tvDeviceName.text = deviceInfoProvider.getDeviceName()
            mdnsAdvertiser.stopMiracastAdvertising()
            startAdvertising()
        }
    }

    /** 定位权限弹窗还在时不叠加悬浮窗权限提示，等它有结果再说 */
    private var wifiPermissionPending = false
    private var overlayPromptShown = false

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001

        /**
         * Wi-Fi Direct 需要的危险权限，必须运行时申请。
         *
         * 少了它 `WifiP2pManager.createGroup()` 会抛 SecurityException，Miracast 直接不可用
         * （AirPlay / DLNA 不受影响）。Android 13+ 用 NEARBY_WIFI_DEVICES 取代定位权限。
         */
        private val WIFI_DIRECT_PERMISSIONS: Array<String>
            get() = if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                // Android 12+ 只申请 FINE 会被系统直接拒绝，必须两个一起申请
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initServices()
        initViews()
        AppSettings.observe(this, nameListener)
        // 切换语言会重建本页（savedInstanceState 非空），这时不要把权限弹窗再弹一遍
        if (savedInstanceState == null) {
            requestWifiDirectPermissions()
        } else {
            overlayPromptShown = true
        }
        checkNetworkAndStart()
    }

    private fun requestWifiDirectPermissions() {
        val permissions = WIFI_DIRECT_PERMISSIONS.toMutableSet()
        if (AppSettings.isCastPlusEnabled(this)) permissions += setOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Timber.i("Requesting Wi-Fi Direct permissions: $missing")
            wifiPermissionPending = true
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return
        wifiPermissionPending = false

        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            // 权限是在服务启动之后才拿到的，重启一次服务让 Wi-Fi Direct 重新初始化
            Timber.i("Wi-Fi Direct permissions granted, restarting cast service")
            startCastService()
        } else {
            Timber.w("Wi-Fi Direct permissions denied — Miracast unavailable")
            tvStatus.text = getString(R.string.status_permission_denied)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!wifiPermissionPending) promptOverlayPermissionIfNeeded()
    }

    /**
     * 「显示在其他应用上层」权限。
     *
     * Android 10 起系统禁止后台启动 Activity，前台服务也不例外。开机自启或者用户按了
     * 主页键之后，应用不在前台，投屏连上时服务拉起播放页会被系统静默拦截 —— 声音、
     * 解码都在跑，屏幕上却什么都没有。持有这个权限的应用可以豁免该限制。
     */
    private fun promptOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (overlayPromptShown || Settings.canDrawOverlays(this)) return
        overlayPromptShown = true

        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_permission_title)
            .setMessage(R.string.overlay_permission_message)
            .setPositiveButton(R.string.overlay_permission_open) { _, _ -> openOverlaySettings() }
            .setNegativeButton(R.string.overlay_permission_later, null)
            .show()
    }

    private fun openOverlaySettings() {
        val withPackage = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        // 部分电视的设置页不认带包名的 Intent，退回到列表页让用户自己找
        val intents = listOf(withPackage, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Timber.w("Overlay settings not available: ${intent.data}")
            }
        }
        Timber.w("No overlay permission settings page on this device")
        Toast.makeText(
            this,
            getString(R.string.overlay_settings_missing, packageName),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun initServices() {
        deviceInfoProvider = DeviceInfoProvider(this)
        mdnsAdvertiser = MdnsAdvertiser(this)
    }

    private fun initViews() {
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvDeviceIp = findViewById(R.id.tv_device_ip)
        tvStatus = findViewById(R.id.tv_status)
        findViewById<SwitchCompat>(R.id.switch_castplus).apply {
            isChecked = AppSettings.isCastPlusEnabled(this@MainActivity)
            isEnabled = Build.VERSION.SDK_INT >= 23
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setCastPlusEnabled(this@MainActivity, checked)
                if (checked) requestWifiDirectPermissions()
                startCastService()
            }
        }
        tvMiracastStatus = findViewById(R.id.tv_miracast_status)
        startupProgress = findViewById(R.id.startup_progress)
        switchAutoStart = findViewById(R.id.switch_auto_start)
        findViewById<SwitchCompat>(R.id.switch_auto_rotate).apply {
            isChecked = AppSettings.isAutoRotateToFit(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> AppSettings.setAutoRotateToFit(this@MainActivity, checked) }
        }
        switchReturnToApp = findViewById(R.id.switch_return_to_app)
        tvLanguageValue = findViewById(R.id.tv_language_value)

        // 开机自启开关
        switchAutoStart.isChecked = AppSettings.isAutoStartOnBoot(this)
        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setAutoStartOnBoot(this, isChecked)
            Timber.i("Auto start on boot set to $isChecked")
        }

        // 投屏结束后是否留在本应用
        switchReturnToApp.isChecked = AppSettings.isReturnToAppAfterCast(this)
        switchReturnToApp.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setReturnToAppAfterCast(this, isChecked)
            Timber.i("Return to app after cast set to $isChecked")
        }

        // 界面语言
        findViewById<View>(R.id.setting_language).setOnClickListener { showLanguageDialog() }
        tvLanguageValue.text = currentLanguageLabel()

        // 设置设备名称
        val deviceName = deviceInfoProvider.getDeviceName()
        tvDeviceName.text = deviceName
        deviceNameDialog = DeviceNameDialog(this)
        tvDeviceName.setOnClickListener { deviceNameDialog.show() }
    }

    /** 第一项是「跟随系统」，其后是 [AppLanguage.OPTIONS]。 */
    private fun languageLabels(): Array<String> =
        arrayOf(getString(R.string.language_follow_system)) + AppLanguage.OPTIONS.map { it.second }

    private fun currentLanguageIndex(): Int = AppLanguage.currentIndex() + 1

    private fun currentLanguageLabel(): String = languageLabels()[currentLanguageIndex()]

    private fun showLanguageDialog() {
        val checked = currentLanguageIndex()
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(languageLabels(), checked) { dialog, which ->
                dialog.dismiss()
                if (which == checked) return@setSingleChoiceItems
                val tag = if (which == 0) "" else AppLanguage.OPTIONS[which - 1].first
                Timber.i("Switching app language to '${tag.ifEmpty { "system" }}'")
                // 会重建当前页面，新页面直接用新语言
                AppLanguage.apply(this, tag)
            }
            .show()
    }

    private fun checkNetworkAndStart() {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            tvStatus.text = getString(R.string.status_no_network)
            Timber.w("Network not available")
        }

        if (!NetworkUtils.isWifiConnected(this)) {
            tvStatus.text = getString(R.string.status_no_wifi)
            Timber.w("WiFi not connected")
        }

        // 获取 IP 地址
        val ipAddress = NetworkUtils.getLocalIpAddress(this)
        if (ipAddress != null) {
            tvDeviceIp.text = getString(R.string.device_ip, ipAddress)
            Timber.i("Local IP: $ipAddress")
        } else {
            tvDeviceIp.text = getString(R.string.device_ip, getString(R.string.device_ip_fetching))
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

        // AirPlay is advertised by CastReceiverService/AirPlayReceiver only.
        // Do not register a second _airplay._tcp service here: duplicate AirPlay mDNS
        // records with different TXT capabilities can make iOS connect with the wrong
        // protocol path and never send the mirroring video SETUP.

        // 启动自定义 Miracast 服务广播（用于自定义 Android 发送端）
        mdnsAdvertiser.startAdvertising(
            serviceName = deviceName,
            port = 8080,
            deviceInfo = deviceInfo
        )

        Timber.i("Started AirPlay and Miracast advertising: $deviceName")
    }

    private fun updateStatus() {
        lifecycleScope.launch {
            while (true) {
                val service = ReceiverStatus.service
                val castPlus = CastPlusReceiver.current?.state?.value?.status ?: CastPlusReceiver.Status.OFF
                findViewById<TextView>(R.id.tv_castplus_status).setText(when (castPlus) {
                    CastPlusReceiver.Status.OFF -> R.string.castplus_off
                    CastPlusReceiver.Status.UNSUPPORTED -> R.string.castplus_unsupported
                    CastPlusReceiver.Status.PERMISSION_REQUIRED -> R.string.castplus_permission
                    CastPlusReceiver.Status.STARTING -> R.string.castplus_starting
                    CastPlusReceiver.Status.READY -> R.string.castplus_ready
                    CastPlusReceiver.Status.PAIRING -> R.string.castplus_pairing
                    CastPlusReceiver.Status.CONNECTING -> R.string.castplus_connecting
                    CastPlusReceiver.Status.PLAYING -> R.string.castplus_playing
                    CastPlusReceiver.Status.PAUSED -> R.string.castplus_paused
                    CastPlusReceiver.Status.DISCONNECTING -> R.string.castplus_disconnecting
                    CastPlusReceiver.Status.ERROR -> R.string.castplus_error
                })
                val miracast = ReceiverStatus.miracast
                tvStatus.setText(when (service) {
                    ReceiverStatus.Service.STARTING -> R.string.status_service_starting
                    ReceiverStatus.Service.WAITING_NETWORK -> R.string.status_waiting_network
                    ReceiverStatus.Service.RUNNING -> R.string.status_receiver_running
                    ReceiverStatus.Service.STOPPED -> R.string.status_receiver_stopped
                })
                tvMiracastStatus.visibility = if (service == ReceiverStatus.Service.RUNNING) View.VISIBLE else View.GONE
                tvMiracastStatus.setText(when (miracast) {
                    ReceiverStatus.Miracast.CHECKING -> R.string.status_miracast_checking
                    ReceiverStatus.Miracast.ADVERTISING -> R.string.status_miracast_advertising
                    ReceiverStatus.Miracast.SYSTEM_MANAGED -> R.string.status_miracast_system
                    ReceiverStatus.Miracast.ROOT_REQUIRED -> R.string.status_miracast_root_required
                    ReceiverStatus.Miracast.SETUP_FAILED -> R.string.status_miracast_failed
                    ReceiverStatus.Miracast.UNSUPPORTED -> R.string.status_miracast_unsupported
                    ReceiverStatus.Miracast.PERMISSION_REQUIRED -> R.string.status_miracast_permission
                    ReceiverStatus.Miracast.WIFI_DISABLED -> R.string.status_miracast_wifi_disabled
                })
                startupProgress.visibility = if (service == ReceiverStatus.Service.STARTING ||
                    service == ReceiverStatus.Service.WAITING_NETWORK ||
                    (service == ReceiverStatus.Service.RUNNING && miracast == ReceiverStatus.Miracast.CHECKING))
                    View.VISIBLE else View.GONE
                delay(2000)
            }
        }
    }

    override fun onStop() {
        deviceNameDialog.close()
        super.onStop()
    }

    override fun onDestroy() {
        AppSettings.unobserve(this, nameListener)
        super.onDestroy()
        mdnsAdvertiser.stopAdvertising()
        Timber.i("MainActivity destroyed")
    }
}
