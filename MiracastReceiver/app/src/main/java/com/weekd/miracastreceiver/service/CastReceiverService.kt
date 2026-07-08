package com.weekd.miracastreceiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.content.BroadcastReceiver
import android.content.Context
import androidx.core.app.NotificationCompat
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.airplay.AirPlayServer
import com.weekd.miracastreceiver.discovery.DeviceInfoProvider
import com.weekd.miracastreceiver.dlna.DlnaMediaRenderer
import com.weekd.miracastreceiver.dlna.SsdpServer
import com.weekd.miracastreceiver.dlna.UpnpHttpServer
import com.weekd.miracastreceiver.miracast.WfdServer
import com.weekd.miracastreceiver.miracast.WifiDirectManager
import com.weekd.miracastreceiver.utils.NetworkUtils
import timber.log.Timber
import java.util.UUID

/**
 * 投屏接收后台服务
 * 保持应用在后台持续监听投屏连接
 */
class CastReceiverService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "cast_receiver_service"
        private const val CHANNEL_NAME = "投屏接收服务"
        private const val ACTION_UPDATE_POSITION = "com.weekd.miracastreceiver.ACTION_UPDATE_POSITION"
    }

    private lateinit var airPlayServer: AirPlayServer
    private lateinit var dlnaRenderer: DlnaMediaRenderer
    private lateinit var ssdpServer: SsdpServer
    private lateinit var upnpHttpServer: UpnpHttpServer
    private lateinit var wfdServer: WfdServer
    private lateinit var wifiDirectManager: WifiDirectManager
    private lateinit var deviceUuid: String

    private val playerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE_POSITION) {
                val position = intent.getLongExtra("position", 0L)
                val duration = intent.getLongExtra("duration", 0L)
                val isPlaying = intent.getBooleanExtra("is_playing", false)
                dlnaRenderer.updatePosition(position, duration)
                if (isPlaying) dlnaRenderer.setPlaying() else dlnaRenderer.setPaused()
                Timber.d("Player position updated: $position / $duration")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("CastReceiverService created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // 初始化设备 UUID
        deviceUuid = generateDeviceUuid()

        // 初始化 AirPlay 服务器
        airPlayServer = AirPlayServer(this)

        // 初始化 Windows 无线显示器（Miracast/WFD）服务器
        initWfdServer()

        // 初始化 DLNA/UPnP 服务
        initDlnaServices()
        registerReceiver(playerStateReceiver, IntentFilter(ACTION_UPDATE_POSITION), RECEIVER_NOT_EXPORTED)
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
                Timber.i("Miracast stream stopped")
            }
        }

        // 初始化 Wi-Fi Direct 以支持 Windows 无线显示器发现
        wifiDirectManager = WifiDirectManager(this).apply {
            onGroupCreated = { group ->
                Timber.i("Wi-Fi Direct group created for Miracast: ${group.networkName}")
            }
            onDeviceConnected = { device ->
                Timber.i("Miracast device connected: ${device.deviceName}")
            }
        }
    }

    private fun initDlnaServices() {
        val deviceInfoProvider = DeviceInfoProvider(this)
        val localIp = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"

        // 创建 DLNA MediaRenderer
        dlnaRenderer = DlnaMediaRenderer()

        // 设置回调
        dlnaRenderer.onSetUri = { uri, metadata ->
            Timber.i("DLNA SetURI: $uri")
            val state = dlnaRenderer.getState()
            // 启动 PlayerActivity 并播放
            val intent = Intent(this, com.weekd.miracastreceiver.ui.PlayerActivity::class.java).apply {
                if (state.playlist.size > 1) {
                    putStringArrayListExtra(
                        com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_URIS,
                        ArrayList(state.playlist)
                    )
                    putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_START_INDEX, state.currentIndex)
                } else {
                    putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_URI, uri)
                }
                putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_MEDIA_TITLE, extractTitle(metadata))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }

        dlnaRenderer.onPlay = {
            Timber.i("DLNA Play")
            // 发送播放广播到 PlayerActivity
            val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_PLAY).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onPause = {
            Timber.i("DLNA Pause")
            // 发送暂停广播到 PlayerActivity
            val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_PAUSE).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onStop = {
            Timber.i("DLNA Stop")
            // 发送停止广播到 PlayerActivity
            val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_STOP).apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onSeek = { position ->
            Timber.i("DLNA Seek: $position")
            // 发送跳转广播到 PlayerActivity
            val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_SEEK).apply {
                putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_SEEK_POSITION, position)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onVolumeChanged = { volume ->
            Timber.i("DLNA Volume: $volume")
            // 发送音量广播到 PlayerActivity
            val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_SET_VOLUME).apply {
                putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_VOLUME, volume)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onSpeedChanged = { speed ->
            Timber.i("DLNA Speed: $speed")
            // 发送播放速度广播到 PlayerActivity
            val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_SET_SPEED).apply {
                putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_SPEED, speed)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        }

        dlnaRenderer.onQualityUriChanged = { uri ->
            Timber.i("DLNA Quality URI: $uri")
            // 发送画质 URI 变化广播到 PlayerActivity
            val intent = Intent(com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_SET_QUALITY_URL).apply {
                putExtra(com.weekd.miracastreceiver.ui.PlayerActivity.EXTRA_QUALITY_URI, uri)
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
        return match?.groupValues?.getOrNull(1) ?: "DLNA 投屏"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("CastReceiverService started")

        // 启动 AirPlay 服务器
        airPlayServer.start(7000)

        // 启动 DLNA/UPnP 服务
        upnpHttpServer.start()
        ssdpServer.start()

        // 启动 Windows 无线显示器（Miracast/WFD）RTSP 服务
        wfdServer.start()
        wifiDirectManager.start()

        Timber.i("All cast services started (AirPlay + DLNA + Miracast/WFD)")

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("CastReceiverService destroyed")

        try {
            unregisterReceiver(playerStateReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Error unregistering playerStateReceiver")
        }

        // 停止 AirPlay 服务器
        airPlayServer.stop()

        // 停止 DLNA/UPnP 服务
        ssdpServer.stop()
        upnpHttpServer.stop()

        // 停止 Miracast/WFD 服务
        wifiDirectManager.stop()
        wfdServer.stop()
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
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持投屏接收服务运行"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("正在等待 AirPlay/DLNA/Miracast 投屏连接")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
