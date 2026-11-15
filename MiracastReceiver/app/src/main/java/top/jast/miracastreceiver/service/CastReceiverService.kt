package top.jast.miracastreceiver.service

import android.app.Notification
import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import top.jast.miracastreceiver.R
import top.jast.miracastreceiver.cast.CastPlayerBridge
import top.jast.miracastreceiver.cast.GoogleCastReceiver
import top.jast.miracastreceiver.airplay.AirPlayCapabilities
import top.jast.miracastreceiver.airplay.fitMirrorResolution
import top.jast.miracastreceiver.airplay.orientMirrorResolution
import top.jast.miracastreceiver.airplay.AirPlayReceiver
import top.jast.miracastreceiver.discovery.DeviceInfoProvider
import top.jast.miracastreceiver.dlna.DlnaMediaRenderer
import top.jast.miracastreceiver.dlna.SsdpServer
import top.jast.miracastreceiver.dlna.UpnpHttpServer
import top.jast.miracastreceiver.miracast.WfdRootHelper
import top.jast.miracastreceiver.miracast.WfdServer
import top.jast.miracastreceiver.miracast.WifiDirectManager
import top.jast.miracastreceiver.util.AppLanguage
import top.jast.miracastreceiver.util.AppSettings
import android.content.SharedPreferences
import top.jast.miracastreceiver.utils.NetworkUtils
import timber.log.Timber
import java.util.UUID

/**
 * 投屏接收后台服务
 * 保持应用在后台持续监听投屏连接
 */
class CastReceiverService : Service() {

    companion object {
        /** 由 BootReceiver 设置：标记这次启动来自开机广播。 */
        const val EXTRA_FROM_BOOT = "from_boot"

        private const val ACTION_STOP_CAST = "top.jast.miracastreceiver.STOP_CAST"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "cast_receiver_service"
        private const val ACTION_UPDATE_POSITION = "top.jast.miracastreceiver.ACTION_UPDATE_POSITION"
        private const val ACTION_PLAYBACK_STOPPED = "top.jast.miracastreceiver.ACTION_PLAYBACK_STOPPED"

        /** 开机后等网络就绪的轮询间隔与最长等待时间。 */
        private const val NETWORK_RETRY_INTERVAL_MS = 5_000L
        private const val NETWORK_MAX_WAIT_MS = 5 * 60_000L
        // Serialize root commands and cleanup without blocking the service/UI thread.
        private val wfdRootExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    private lateinit var airPlayReceiver: AirPlayReceiver
    private lateinit var dlnaRenderer: DlnaMediaRenderer
    private lateinit var castPlayerBridge: CastPlayerBridge
    private lateinit var googleCastReceiver: GoogleCastReceiver
    private lateinit var castPlusReceiver: top.jast.miracastreceiver.castplus.CastPlusReceiver
    private lateinit var ssdpServer: SsdpServer
    private lateinit var upnpHttpServer: UpnpHttpServer
    private lateinit var wfdServer: WfdServer
    private lateinit var wifiDirectManager: WifiDirectManager
    /** startAllServices() 已执行；防止重复 onStartCommand 再建一套接收器。 */
    private var servicesStarted = false
    private lateinit var deviceUuid: String
    private var airPlayPlayerStarted = false

    /** initReceivers() 是否已经跑过：没跑过时那些 lateinit 字段不能碰。 */
    private var initialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkWaitRunnable: Runnable? = null
    private var networkWaitElapsedMs = 0L
    @Volatile private var stopping = false
    private var rootAdvertisingActive = false
    private var rootAdvertisingPending = false
    private var rootAdvertisingAttempted = false
    private var p2pFailure: ReceiverStatus.Miracast? = null
    private var p2pGroupReady = false
    private var airPlayLandscapeResolution = 1920 to 1080
    private val nameListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppSettings.KEY_RECEIVER_NAME && initialized && !stopping) {
            val name = DeviceInfoProvider(this).getDeviceName()
            airPlayReceiver.updateDisplayName(name)
            upnpHttpServer.updateDisplayName(name)
            ssdpServer.refreshAdvertisement()
            googleCastReceiver.updateDisplayName(name)
            castPlusReceiver.updateDisplayName(name)
            wifiDirectManager.updateDeviceName(name)
            syncRootDeviceName()
        }
        if (key == AppSettings.KEY_AIRPLAY_PORTRAIT_DISPLAY && initialized && !stopping) {
            val (width, height) = orientMirrorResolution(airPlayLandscapeResolution.first,
                airPlayLandscapeResolution.second, AppSettings.isAirPlayPortraitDisplay(this))
            airPlayReceiver.updateDisplayResolution(width, height)
        }
    }

    private val playerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CastPlayerBridge.ACTION_STATE -> castPlayerBridge.onState(intent)
                "top.jast.miracastreceiver.DISCONNECT_SENDER" -> {
                    if (intent.getBooleanExtra("miracast", false)) {
                        wfdServer.disconnectSender()
                        wifiDirectManager.disconnectSender()
                    } else {
                        airPlayReceiver.disconnectSender()
                    }
                }
                ACTION_UPDATE_POSITION -> {
                    val position = intent.getLongExtra("position", 0L)
                    val duration = intent.getLongExtra("duration", 0L)
                    val isPlaying = intent.getBooleanExtra("is_playing", false)
                    dlnaRenderer.updatePosition(position, duration)
                    if (isPlaying) dlnaRenderer.setPlaying() else dlnaRenderer.setPaused()
                    Timber.d("Player position updated: $position / $duration")
                }
                ACTION_PLAYBACK_STOPPED -> {
                    // TV 端本地退出/播放结束，需要让 DLNA 状态归零，
                    // 否则 Emby 会一直显示"正在播放"且无法取消/切换投屏。
                    dlnaRenderer.updatePosition(0L, 0L)
                    dlnaRenderer.setStopped()
                    Timber.i("Playback stopped locally, DLNA renderer reset to STOPPED")
                }
            }
        }
    }

    /** 通知、DLNA 默认标题等文案跟随应用内选择的语言 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate() {
        ReceiverStatus.service = ReceiverStatus.Service.STARTING
        ReceiverStatus.miracast = ReceiverStatus.Miracast.CHECKING
        super.onCreate()
        AppSettings.observe(this, nameListener)
        Timber.i("CastReceiverService created")
        createNotificationChannel()
        // startForeground 放在 onStartCommand 里：前台服务类型要看这次是不是开机拉起的
        // （Android 15 起 BOOT_COMPLETED 不允许启动 mediaPlayback 类型）。
    }

    /**
     * 真正的初始化。开机启动时网络还没就绪，DLNA/SSDP 需要本机 IP 才能正确绑定，
     * 所以推迟到 [startWhenNetworkReady] 拿到 IP 之后再做，且只做一次。
     */
    private fun initReceivers() {
        if (initialized) return
        initialized = true

        // 初始化设备 UUID
        deviceUuid = generateDeviceUuid()

        // A 4K display alone is insufficient: macOS may choose HEVC even when the
        // receiver has only advertised AVC. Keep AVC-only hardware within 1080p.
        val displayResolution = getBestDisplayResolution()
        airPlayLandscapeResolution = if (AirPlayCapabilities.hardwareHevc4k) {
            displayResolution
        } else {
            fitMirrorResolution(displayResolution.first, displayResolution.second, 1920, 1080)
        }
        val mirrorResolution = orientMirrorResolution(airPlayLandscapeResolution.first,
            airPlayLandscapeResolution.second, AppSettings.isAirPlayPortraitDisplay(this))
        val deviceName = DeviceInfoProvider(this).getDeviceName()
        Timber.i("AirPlay mirror advertised resolution: ${mirrorResolution.first}x${mirrorResolution.second}")

        // 初始化 AirPlay 2 接收器（基于 PhairPlay 实现，支持 iOS 屏幕镜像）
        airPlayReceiver = AirPlayReceiver(
            context = this,
            displayName = deviceName,
            mirrorWidth = mirrorResolution.first,
            mirrorHeight = mirrorResolution.second,
            audioEnabled = true,
            videoSurfaceProvider = { top.jast.miracastreceiver.ui.PlayerActivity.airPlaySurface },
            onStateChanged = { state ->
                Timber.i("AirPlay state: $state")
                if (state == top.jast.miracastreceiver.airplay.AirPlayState.CONNECTED && !airPlayPlayerStarted) {
                    airPlayPlayerStarted = true
                    val intent = Intent(this, top.jast.miracastreceiver.ui.PlayerActivity::class.java).apply {
                        putExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_TITLE, getString(R.string.title_iphone_mirror))
                        putExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_IS_AIRPLAY_MIRROR, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                }
            },
            onSessionEnded = {
                if (airPlayPlayerStarted) {
                    airPlayPlayerStarted = false
                    Timber.i("AirPlay session ended, closing player")
                    sendBroadcast(Intent(top.jast.miracastreceiver.ui.PlayerActivity.ACTION_STOP).apply {
                        setPackage(packageName)
                        putExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_SOURCE_TYPE, "AIRPLAY")
                    })
                }
            },
            onSenderNameChanged = { sender -> Timber.i("AirPlay sender: $sender") }
        )

        // 初始化 Windows 无线显示器（Miracast/WFD）服务器
        initWfdServer()

        // 初始化 DLNA/UPnP 服务
        initDlnaServices()
        castPlayerBridge = CastPlayerBridge(this)
        googleCastReceiver = GoogleCastReceiver(this, deviceName, deviceUuid, castPlayerBridge.session)
        castPlusReceiver = top.jast.miracastreceiver.castplus.CastPlusReceiver(this) {
            sendBroadcast(Intent(top.jast.miracastreceiver.ui.PlayerActivity.ACTION_REPLACED).setPackage(packageName))
            airPlayPlayerStarted = false
            runCatching { airPlayReceiver.disconnectSender() }
            runCatching { wfdServer.disconnectSender() }
            dlnaRenderer.setStopped()
        }
        val playerStateFilter = IntentFilter().apply {
            addAction(CastPlayerBridge.ACTION_STATE)
            addAction("top.jast.miracastreceiver.DISCONNECT_SENDER")
            addAction(ACTION_UPDATE_POSITION)
            addAction(ACTION_PLAYBACK_STOPPED)
        }
        // ContextCompat 而非直接调用：带 flags 的重载是 API 26 才有的，
        // 老电视（Android 5/6/7）上直接调会 NoSuchMethodError 闪退。
        ContextCompat.registerReceiver(
            this, playerStateReceiver, playerStateFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun getBestDisplayResolution(): Pair<Int, Int> {
        // Display.getSupportedModes / getMode 是 API 23 起才有的，而 minSdk 是 21。
        // 这个方法在服务 onCreate 里调用，低版本上直接调会 NoSuchMethodError 闪退，
        // 所以 Android 5.x 直接用 displayMetrics（精度略低但够用）。
        var width = resources.displayMetrics.widthPixels
        var height = resources.displayMetrics.heightPixels

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val display = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display?.supportedModes
                ?.maxByOrNull { it.physicalWidth * it.physicalHeight }
                ?: display?.mode
            if (mode != null) {
                width = mode.physicalWidth
                height = mode.physicalHeight
            }
        }
        val longSide = maxOf(width, height).coerceIn(1280, 3840)
        val shortSide = minOf(width, height).coerceIn(720, 2160)
        return longSide to shortSide
    }

    private fun initWfdServer() {
        wfdServer = WfdServer(this, 7236).apply {
            onConnectionRequested = { clientName, clientAddress ->
                Timber.i("Miracast connection requested: $clientName from $clientAddress")
            }
            onConnectionEstablished = { sessionId ->
                Timber.i("Miracast session established: $sessionId")
            }
            onStreamStarted = { rtpPort ->
                Timber.i("Miracast stream started on RTP port: $rtpPort")
            }
            onStreamStopped = {
                Timber.i("Miracast stream stopped, closing player")
                // Windows 断开（RTSP 连接关闭或 TEARDOWN）后必须主动关掉播放页，
                // 否则手机会一直停在最后一帧画面上
                sendBroadcast(Intent(top.jast.miracastreceiver.ui.PlayerActivity.ACTION_STOP).apply {
                    setPackage(packageName)
                    putExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_SOURCE_TYPE, "MIRACAST")
                })
            }
        }

        // 初始化 Wi-Fi Direct 以支持 Windows 无线显示器发现
        wifiDirectManager = WifiDirectManager(this, DeviceInfoProvider(this).getDeviceName()).apply {
            onStartupState = stateChanged@{ state ->
                if (stopping) return@stateChanged
                p2pFailure = when (state) {
                    WifiDirectManager.StartupState.STARTING -> null
                    WifiDirectManager.StartupState.UNSUPPORTED -> ReceiverStatus.Miracast.UNSUPPORTED
                    WifiDirectManager.StartupState.PERMISSION_REQUIRED -> ReceiverStatus.Miracast.PERMISSION_REQUIRED
                    WifiDirectManager.StartupState.WIFI_DISABLED -> ReceiverStatus.Miracast.WIFI_DISABLED
                    WifiDirectManager.StartupState.FAILED -> ReceiverStatus.Miracast.SETUP_FAILED
                }
                ReceiverStatus.miracast = p2pFailure ?: ReceiverStatus.Miracast.CHECKING
            }
            onWfdConfigured = {
                if (!stopping && p2pGroupReady && p2pFailure == null) ReceiverStatus.miracast = ReceiverStatus.Miracast.ADVERTISING
            }
            onGroupCreated = { group ->
                Timber.i("Wi-Fi Direct group created for Miracast: ${group.networkName}")
                p2pGroupReady = true
                p2pFailure = null
                advertiseMiracast()
            }
            onDeviceConnected = { device ->
                Timber.i("Miracast device connected: ${device.deviceName}")
            }
        }
    }

    private fun initDlnaServices() {
        val deviceInfoProvider = DeviceInfoProvider(this)
        val localIp = NetworkUtils.getLocalIpAddress(this) ?: "127.0.0.1"

        // 创建 DLNA MediaRenderer
        dlnaRenderer = DlnaMediaRenderer()

        // 设置回调
        dlnaRenderer.onSetUri = { uri, metadata ->
            Timber.i("DLNA SetURI: $uri")
            val state = dlnaRenderer.getState()
            // 启动 PlayerActivity 并播放
            val intent = Intent(this, top.jast.miracastreceiver.ui.PlayerActivity::class.java).apply {
                if (state.playlist.size > 1) {
                    putStringArrayListExtra(
                        top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_URIS,
                        ArrayList(state.playlist)
                    )
                    putExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_START_INDEX, state.currentIndex)
                } else {
                    putExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_URI, uri)
                }
                putExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_TITLE, extractTitle(metadata))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }

        dlnaRenderer.onPlay = {
            Timber.i("DLNA Play")
            // 发送播放广播到 PlayerActivity
            val intent = Intent(top.jast.miracastreceiver.ui.PlayerActivity.ACTION_PLAY).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onPause = {
            Timber.i("DLNA Pause")
            // 发送暂停广播到 PlayerActivity
            val intent = Intent(top.jast.miracastreceiver.ui.PlayerActivity.ACTION_PAUSE).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onStop = {
            Timber.i("DLNA Stop")
            // 发送停止广播到 PlayerActivity
            val intent = Intent(top.jast.miracastreceiver.ui.PlayerActivity.ACTION_STOP).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onSeek = { position ->
            Timber.i("DLNA Seek: $position")
            // 发送跳转广播到 PlayerActivity
            val intent = Intent(top.jast.miracastreceiver.ui.PlayerActivity.ACTION_SEEK).apply {
                putExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_SEEK_POSITION, position)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onVolumeChanged = { volume ->
            Timber.i("DLNA Volume: $volume")
            // 发送音量广播到 PlayerActivity
            val intent = Intent(top.jast.miracastreceiver.ui.PlayerActivity.ACTION_SET_VOLUME).apply {
                putExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_VOLUME, volume)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onSpeedChanged = { speed ->
            Timber.i("DLNA Speed: $speed")
            // 发送播放速度广播到 PlayerActivity
            val intent = Intent(top.jast.miracastreceiver.ui.PlayerActivity.ACTION_SET_SPEED).apply {
                putExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_SPEED, speed)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onQualityUriChanged = { uri ->
            Timber.i("DLNA Quality URI: $uri")
            // 发送画质 URI 变化广播到 PlayerActivity
            val intent = Intent(top.jast.miracastreceiver.ui.PlayerActivity.ACTION_SET_QUALITY_URL).apply {
                putExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_QUALITY_URI, uri)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        // 创建 UPnP HTTP 服务器
        upnpHttpServer = UpnpHttpServer(
            context = this,
            renderer = dlnaRenderer,
            deviceUuid = deviceUuid,
            deviceName = deviceInfoProvider.getDeviceName(),
            manufacturer = Build.MANUFACTURER,
            modelName = Build.MODEL,
            localIp = localIp,
            port = 8080
        )

        // 创建 SSDP 服务器
        ssdpServer = SsdpServer(
            context = this,
            deviceUuid = deviceUuid,
            localIp = localIp,
            httpPort = 8080
        )
    }

    private fun extractTitle(metadata: String): String {
        // 简单解析 DIDL-Lite metadata 中的 title
        val titlePattern = Regex("<dc:title>(.*?)</dc:title>", RegexOption.IGNORE_CASE)
        val match = titlePattern.find(metadata)
        return match?.groupValues?.getOrNull(1) ?: getString(R.string.title_dlna)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_CAST) {
            // Stop the service as well as the player: audio can outlive its Surface/Activity.
            airPlayPlayerStarted = false
            sendBroadcast(Intent(top.jast.miracastreceiver.ui.PlayerActivity.ACTION_STOP).apply {
                setPackage(packageName)
            })
            stopSelf()
            return START_NOT_STICKY
        }
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) ?: false
        Timber.i("CastReceiverService started (fromBoot=$fromBoot)")

        startForegroundCompat(fromBoot)
        startWhenNetworkReady()

        return START_STICKY
    }

    /**
     * 前台服务类型：
     * - 正常从界面启动用 mediaPlayback（投屏播放）；
     * - 开机自启必须避开 mediaPlayback —— Android 15 起从 BOOT_COMPLETED 启动该类型
     *   会抛 ForegroundServiceStartNotAllowedException，改用 connectedDevice
     *   （本来也符合"接收局域网设备投屏"的语义，CHANGE_WIFI_STATE 已满足前提条件）。
     */
    private fun startForegroundCompat(fromBoot: Boolean) {
        val type = if (fromBoot) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        // ServiceCompat：type 参数在 API 29 以下会被忽略，老电视上不会崩。
        ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(), type)
    }

    /**
     * 等到拿得到局域网 IP 再启动各路接收器。
     *
     * 开机自启时 Wi-Fi 往往还在连，这时候启动 SSDP/mDNS 会绑到错误的地址，
     * 结果就是服务活着但手机搜不到设备。这里每 5 秒看一次，最多等 5 分钟。
     */
    private fun startWhenNetworkReady() {
        networkWaitRunnable?.let { mainHandler.removeCallbacks(it) }
        networkWaitRunnable = null

        // 服务已在运行时再次 onStartCommand（例如首次授予 Wi-Fi Direct 权限后界面重启服务）：
        // 只重试 Wi-Fi Direct。重新 initReceivers + startAllServices 会再建一套 AirPlay/DLNA
        // 接收器，新的绑不上 7000 端口，旧的却还在接连接 —— 视频投送的事件通道就落到了
        // 失效的那一套上，iPhone 收不到播放状态。
        if (servicesStarted) {
            Timber.i("Cast services already running — only retrying Wi-Fi Direct")
            runCatching { wifiDirectManager.start() }
            updateCastPlus()
            return
        }

        if (NetworkUtils.getLocalIpAddress(this) != null) {
            networkWaitElapsedMs = 0L
            initReceivers()
            startAllServices()
            return
        }

        if (networkWaitElapsedMs >= NETWORK_MAX_WAIT_MS) {
            Timber.w("Network still not ready after ${NETWORK_MAX_WAIT_MS / 1000}s, starting anyway")
            networkWaitElapsedMs = 0L
            initReceivers()
            startAllServices()
            return
        }

        Timber.i("Network not ready yet, retry in ${NETWORK_RETRY_INTERVAL_MS}ms")
        ReceiverStatus.service = ReceiverStatus.Service.WAITING_NETWORK
        val runnable = Runnable {
            networkWaitElapsedMs += NETWORK_RETRY_INTERVAL_MS
            startWhenNetworkReady()
        }
        networkWaitRunnable = runnable
        mainHandler.postDelayed(runnable, NETWORK_RETRY_INTERVAL_MS)
    }

    private fun startAllServices() {
        servicesStarted = true
        // 启动 AirPlay 2 接收器
        airPlayReceiver.start()
        // Surface 重建时，恢复 AirPlay URL 视频与屏幕镜像的输出
        top.jast.miracastreceiver.ui.PlayerActivity.mirrorSurfaceListener = { airPlayReceiver.onVideoSurfaceChanged() }

        // 启动 DLNA/UPnP 服务
        upnpHttpServer.start()
        ssdpServer.start()
        googleCastReceiver.start()

        // 启动 Windows 无线显示器（Miracast/WFD）
        // Inject after onGroupCreated: createGroup is asynchronous and can overwrite WFD IE.
        wifiDirectManager.start()
        mainHandler.postDelayed({
            if (!stopping && ReceiverStatus.miracast == ReceiverStatus.Miracast.CHECKING) {
                ReceiverStatus.miracast = ReceiverStatus.Miracast.SETUP_FAILED
            }
        }, 30_000L)
        wfdServer.start()
        ReceiverStatus.service = ReceiverStatus.Service.RUNNING
        updateCastPlus()

        Timber.i("All cast services started (AirPlay + DLNA + Miracast/WFD + experimental Google Cast)")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateCastPlus() {
        if (top.jast.miracastreceiver.util.AppSettings.isCastPlusEnabled(this)) castPlusReceiver.start()
        else castPlusReceiver.stop()
    }

    private fun advertiseMiracast() {
        if (stopping) return
        if (wifiDirectManager.isWfdConfigured) {
            ReceiverStatus.miracast = ReceiverStatus.Miracast.ADVERTISING
            return
        }
        // This setting alone does not prove Sink capability. Report it as unverified,
        // and allow systems with their own WFD advertising to work without root.
        val systemEnabled = runCatching {
            android.provider.Settings.Global.getInt(contentResolver, "wifi_display_on", 0) == 1
        }.getOrDefault(false)
        if (systemEnabled) {
            ReceiverStatus.miracast = ReceiverStatus.Miracast.SYSTEM_MANAGED
            return
        }
        if (rootAdvertisingPending) return
        rootAdvertisingPending = true
        rootAdvertisingAttempted = true
        ReceiverStatus.miracast = ReceiverStatus.Miracast.CHECKING
        val appContext = applicationContext
        wfdRootExecutor.execute {
            val result = runCatching { WfdRootHelper.advertiseSinkResult(appContext) }
                .getOrDefault(WfdRootHelper.AdvertiseResult.COMMAND_FAILED)
            mainHandler.post {
                rootAdvertisingPending = false
                if (!stopping) {
                    rootAdvertisingActive = result == WfdRootHelper.AdvertiseResult.COMMAND_SENT
                    syncRootDeviceName()
                    ReceiverStatus.miracast = p2pFailure ?: when {
                        wifiDirectManager.isWfdConfigured -> ReceiverStatus.Miracast.ADVERTISING
                        result == WfdRootHelper.AdvertiseResult.COMMAND_SENT -> ReceiverStatus.Miracast.ADVERTISING
                        result == WfdRootHelper.AdvertiseResult.ROOT_UNAVAILABLE -> ReceiverStatus.Miracast.ROOT_REQUIRED
                        else -> ReceiverStatus.Miracast.SETUP_FAILED
                    }
                    Timber.i("Miracast startup: %s (%s)", ReceiverStatus.miracast, result)
                }
            }
        }
    }

    private fun syncRootDeviceName() {
        if (!rootAdvertisingActive || stopping) return
        val appContext = applicationContext
        wfdRootExecutor.execute {
            if (!stopping) {
                val name = DeviceInfoProvider(appContext).getDeviceName()
                if (!WfdRootHelper.setDeviceName(appContext, name)) {
                    Timber.w("WFD: could not sync P2P device name via root")
                }
            }
        }
    }

    /** 用户从最近任务里划掉应用时也要断开投屏，否则发送端会以为连接还在。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.i("Task removed, tearing down cast sessions")
        if (initialized) shutdownMiracast()
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        AppSettings.unobserve(this, nameListener)
        ReceiverStatus.service = ReceiverStatus.Service.STOPPED
        super.onDestroy()
        Timber.i("CastReceiverService destroyed")

        networkWaitRunnable?.let { mainHandler.removeCallbacks(it) }
        networkWaitRunnable = null

        // 还没等到网络就被停掉时，下面那些 lateinit 字段根本没初始化，碰了会崩。
        if (!initialized) return

        try {
            unregisterReceiver(playerStateReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Error unregistering playerStateReceiver")
        }

        // 停止 AirPlay 2 接收器
        top.jast.miracastreceiver.ui.PlayerActivity.mirrorSurfaceListener = null
        airPlayReceiver.stop()

        // 停止 DLNA/UPnP 服务
        ssdpServer.stop()
        upnpHttpServer.stop()
        googleCastReceiver.stop()
        castPlusReceiver.stop()
        castPlayerBridge.stop()

        shutdownMiracast()
    }

    /**
     * 断开 Miracast 并停止对外广播。
     *
     * 注意 WFD IE 和 P2P 组都存在 wpa_supplicant（系统进程）里，**不随应用退出而消失**。
     * 不主动撤销的话，应用关掉之后 Windows 依然能搜到这台设备并尝试连接。
     */
    private fun shutdownMiracast() {
        stopping = true
        rootAdvertisingActive = false
        runCatching { wfdServer.stop() }
        if (rootAdvertisingAttempted) {
            rootAdvertisingAttempted = false
            val appContext = applicationContext
            wfdRootExecutor.execute { runCatching { WfdRootHelper.stopAdvertising(appContext) } }
        }
        runCatching { wifiDirectManager.stop() }
    }

    private fun generateDeviceUuid(): String {
        // 使用设备信息生成一致的 UUID
        val deviceId = "${Build.MANUFACTURER}-${Build.MODEL}-${Build.SERIAL}"
        return UUID.nameUUIDFromBytes(deviceId.toByteArray()).toString()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                // 渠道已存在时再次创建只会更新名称和描述，切换系统语言后重启服务即可跟着变
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, top.jast.miracastreceiver.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopCast = PendingIntent.getService(
            this, 1, Intent(this, CastReceiverService::class.java).setAction(ACTION_STOP_CAST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_waiting))
            .setSmallIcon(R.drawable.ic_stat_miracast)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_stop_cast), stopCast)
            .build()
    }
}
