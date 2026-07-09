package com.weekd.miracastreceiver.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.airplay.StreamStats
import com.weekd.miracastreceiver.miracast.RtpReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.URL

/**
 * 投屏播放页面
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_URI = "media_uri"
        const val EXTRA_MEDIA_TITLE = "media_title"
        const val EXTRA_MEDIA_URIS = "media_uris"
        const val EXTRA_MEDIA_TITLES = "media_titles"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_IS_AIRPLAY_MIRROR = "is_airplay_mirror"

        // Miracast 额外参数
        const val EXTRA_SOURCE_TYPE = "SOURCE_TYPE"
        const val EXTRA_RTP_PORT = "RTP_PORT"
        const val EXTRA_SESSION_ID = "SESSION_ID"

        // 广播 Action
        const val ACTION_PLAY = "com.weekd.miracastreceiver.ACTION_PLAY"
        const val ACTION_PAUSE = "com.weekd.miracastreceiver.ACTION_PAUSE"
        const val ACTION_STOP = "com.weekd.miracastreceiver.ACTION_STOP"
        const val ACTION_SEEK = "com.weekd.miracastreceiver.ACTION_SEEK"
        const val ACTION_SET_VOLUME = "com.weekd.miracastreceiver.ACTION_SET_VOLUME"
        const val ACTION_SET_PLAYLIST = "com.weekd.miracastreceiver.ACTION_SET_PLAYLIST"
        const val ACTION_SET_SPEED = "com.weekd.miracastreceiver.ACTION_SET_SPEED"
        const val ACTION_SET_QUALITY_URL = "com.weekd.miracastreceiver.ACTION_SET_QUALITY_URL"

        const val EXTRA_SEEK_POSITION = "seek_position"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_QUALITY_URI = "quality_uri"

        @Volatile
        var airPlayMirrorSurface: Surface? = null
            private set

        private const val IMAGE_SLIDE_INTERVAL_MS = 5_000L
        private const val QUALITY_AUTO = -1
    }

    private lateinit var playerView: PlayerView
    private lateinit var airPlayMirrorSurfaceView: SurfaceView
    private lateinit var imageView: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvTitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var mediaUri: String? = null
    private var mediaTitle: String? = null
    private var playlist: List<String> = emptyList()
    private var playlistTitles: List<String> = emptyList()
    private var currentIndex: Int = 0
    private var slideJob: Job? = null
    private var progressUpdateJob: Job? = null
    private var qualityHeight: Int = QUALITY_AUTO
    private var rtpReceiver: RtpReceiver? = null
    private var isMiracastSession = false
    private var airPlayAspectJob: Job? = null

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PLAY -> if (isCurrentImage()) startImageSlideShow() else player?.play()
                ACTION_PAUSE -> if (isCurrentImage()) stopImageSlideShow() else player?.pause()
                ACTION_STOP -> {
                    stopPlayback()
                    finish()
                }
                ACTION_SEEK -> {
                    val position = intent.getLongExtra(EXTRA_SEEK_POSITION, 0L)
                    player?.seekTo(position)
                    reportPlaybackPosition()
                }
                ACTION_SET_VOLUME -> {
                    val volume = intent.getIntExtra(EXTRA_VOLUME, 50)
                    player?.volume = volume / 100f
                }
                ACTION_SET_PLAYLIST -> handleIntent(intent)
                ACTION_SET_SPEED -> {
                    val speed = intent.getFloatExtra(EXTRA_SPEED, 1f).coerceIn(0.25f, 4f)
                    player?.setPlaybackSpeed(speed)
                    playerView.findViewById<TextView?>(R.id.tv_playback_speed)?.text = if (speed == 1f) "1.0x" else "${speed}x"
                    tvStatus.text = "播放速度：${speed}x"
                    reportPlaybackPosition()
                }
                ACTION_SET_QUALITY_URL -> {
                    val uri = intent.getStringExtra(EXTRA_QUALITY_URI)
                    if (!uri.isNullOrBlank()) {
                        playMedia(uri)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        initViews()
        initPlayer()
        handleIntent(intent)
        registerControlReceiver()

        Timber.i("PlayerActivity created")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun initViews() {
        playerView = findViewById(R.id.player_view)
        airPlayMirrorSurfaceView = findViewById(R.id.airplay_mirror_surface)
        imageView = findViewById(R.id.image_view)
        tvStatus = findViewById(R.id.tv_status)
        tvTitle = findViewById(R.id.tv_title)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
    }

    private fun initPlayer() {
        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setMaxVideoSizeSd())
        }
        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updateBufferingState(playbackState == Player.STATE_BUFFERING)
                        when (playbackState) {
                            Player.STATE_IDLE -> Timber.d("Player state: IDLE")
                            Player.STATE_BUFFERING -> {
                                Timber.d("Player state: BUFFERING")
                                tvStatus.text = "正在缓冲..."
                            }
                            Player.STATE_READY -> {
                                Timber.d("Player state: READY")
                                tvError.visibility = View.GONE
                                tvStatus.text = getString(R.string.playing)
                                adaptOrientationToVideo()
                                startProgressUpdates()
                            }
                            Player.STATE_ENDED -> {
                                Timber.d("Player state: ENDED")
                                tvStatus.text = "播放完成"
                                reportPlaybackPosition()
                                playNextOrFinish()
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        tvStatus.text = if (isPlaying) getString(R.string.playing) else "已暂停"
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Timber.e(error, "Player error")
                        updateBufferingState(false)
                        tvStatus.text = "播放错误"
                        tvError.text = "播放错误: ${error.message ?: "未知错误"}"
                        tvError.visibility = View.VISIBLE
                    }
                })
            }

        playerView.player = player
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            findViewById<View?>(R.id.status_bar)?.visibility = visibility
        })
        playerView.setShowNextButton(true)
        playerView.setShowPreviousButton(true)
        setupControllerActions()
    }

    private fun setupControllerActions() {
        playerView.findViewById<View?>(R.id.btn_quality)?.setOnClickListener { showQualityDialog() }
        playerView.findViewById<View?>(R.id.btn_fullscreen)?.setOnClickListener { toggleOrientation() }
        playerView.findViewById<TextView?>(R.id.tv_playback_speed)?.setOnClickListener { showSpeedDialog(it as TextView) }
        playerView.findViewById<View?>(R.id.btn_subtitle)?.setOnClickListener {
            Toast.makeText(this, "字幕切换将随媒体字幕轨自动支持", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_SOURCE_TYPE) == "MIRACAST") {
            startMiracastPlayback(intent.getIntExtra(EXTRA_RTP_PORT, 0), intent.getStringExtra(EXTRA_SESSION_ID))
            return
        }

        if (intent?.getBooleanExtra(EXTRA_IS_AIRPLAY_MIRROR, false) == true) {
            startAirPlayMirrorPlayback()
            return
        }

        val list = intent?.getStringArrayListExtra(EXTRA_MEDIA_URIS)
            ?: intent?.getStringExtra(EXTRA_MEDIA_URI)?.let { arrayListOf(it) }
            ?: arrayListOf()
        val titles = intent?.getStringArrayListExtra(EXTRA_MEDIA_TITLES)
            ?: intent?.getStringExtra(EXTRA_MEDIA_TITLE)?.let { arrayListOf(it) }
            ?: arrayListOf()

        playlist = list.filter { it.isNotBlank() }
        playlistTitles = titles
        currentIndex = intent?.getIntExtra(EXTRA_START_INDEX, 0)?.coerceIn(0, (playlist.size - 1).coerceAtLeast(0)) ?: 0

        if (playlist.isNotEmpty()) {
            playCurrent()
        }
    }

    private fun playCurrent() {
        mediaUri = playlist.getOrNull(currentIndex)
        mediaTitle = playlistTitles.getOrNull(currentIndex) ?: "DLNA 投屏 ${currentIndex + 1}/${playlist.size}"
        tvTitle.text = mediaTitle
        val uri = mediaUri ?: return
        if (isImageUri(uri)) {
            showImage(uri)
        } else {
            playMedia(uri)
        }
    }

    private fun startAirPlayMirrorPlayback() {
        Timber.i("Starting AirPlay mirror playback")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopImageSlideShow()
        player?.clearVideoSurface()
        player?.pause()
        playerView.player = null
        playerView.visibility = View.GONE
        airPlayMirrorSurfaceView.visibility = View.VISIBLE
        imageView.visibility = View.GONE
        tvTitle.text = "iPhone 屏幕镜像"
        tvStatus.text = "正在接收 iPhone 屏幕..."
        tvError.visibility = View.GONE
        updateBufferingState(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val surfaceView = airPlayMirrorSurfaceView

        fun publishSurface(holder: SurfaceHolder) {
            val surface = holder.surface
            airPlayMirrorSurface = surface.takeIf { it.isValid }
            Timber.i("AirPlay mirror surface ${if (airPlayMirrorSurface == null) "not ready" else "ready"}")
            if (airPlayMirrorSurface == null) {
                tvStatus.text = "等待 AirPlay 显示画面..."
            } else {
                tvStatus.text = "正在接收 iPhone 屏幕..."
            }
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = publishSurface(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = publishSurface(holder)
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Timber.i("AirPlay mirror surface destroyed")
                airPlayMirrorSurface = null
            }
        })
        playerView.post { publishSurface(surfaceView.holder) }
        startAirPlayAspectFitUpdates()
    }

    private fun startAirPlayAspectFitUpdates() {
        airPlayAspectJob?.cancel()
        airPlayAspectJob = lifecycleScope.launch {
            var lastW = 0
            var lastH = 0
            while (isActive) {
                val videoW = StreamStats.videoWidth
                val videoH = StreamStats.videoHeight
                if (videoW > 0 && videoH > 0 && (videoW != lastW || videoH != lastH)) {
                    lastW = videoW
                    lastH = videoH
                    fitAirPlaySurface(videoW, videoH)
                }
                delay(300)
            }
        }
    }

    private fun fitAirPlaySurface(videoW: Int, videoH: Int) {
        val parent = airPlayMirrorSurfaceView.parent as? View ?: return
        val parentW = parent.width
        val parentH = parent.height
        if (parentW <= 0 || parentH <= 0) return

        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val parentAspect = parentW.toFloat() / parentH.toFloat()
        val (targetW, targetH) = if (videoAspect > parentAspect) {
            parentW to (parentW / videoAspect).toInt()
        } else {
            (parentH * videoAspect).toInt() to parentH
        }

        airPlayMirrorSurfaceView.layoutParams = airPlayMirrorSurfaceView.layoutParams.apply {
            width = targetW.coerceAtLeast(1)
            height = targetH.coerceAtLeast(1)
        }
        Timber.i("AirPlay mirror aspect-fit: video=${videoW}x$videoH view=${targetW}x$targetH parent=${parentW}x$parentH")
    }

    private fun startMiracastPlayback(rtpPort: Int, sessionId: String?) {
        if (rtpPort <= 0) {
            tvStatus.text = "Miracast 连接错误"
            tvError.text = "无效的 RTP 端口"
            tvError.visibility = View.VISIBLE
            return
        }

        Timber.i("Starting Miracast playback: port=$rtpPort session=$sessionId")
        isMiracastSession = true
        stopImageSlideShow()
        player?.pause()
        playerView.visibility = View.VISIBLE
        imageView.visibility = View.GONE
        tvTitle.text = "Windows 无线显示器"
        tvStatus.text = "正在接收 Windows 屏幕..."
        tvError.visibility = View.GONE
        updateBufferingState(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        playerView.post {
            val surface = (playerView.videoSurfaceView as? SurfaceView)?.holder?.surface
            rtpReceiver?.stop()
            rtpReceiver = RtpReceiver(rtpPort).apply {
                onError = { message ->
                    runOnUiThread {
                        tvStatus.text = "Miracast 播放错误"
                        tvError.text = message
                        tvError.visibility = View.VISIBLE
                    }
                }
                start(surface)
            }
        }
    }

    private fun playMedia(uri: String) {
        Timber.i("Playing media: $uri")
        stopImageSlideShow()
        imageView.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        try {
            tvError.visibility = View.GONE
            updateBufferingState(true)

            // 根据 URI 判断媒体类型
            val mediaItem = createMediaItem(uri)

            val items = playlist.mapIndexedNotNull { index, itemUri ->
                if (!isImageUri(itemUri)) {
                    createMediaItem(itemUri).also { if (index == currentIndex) mediaUri = itemUri }
                } else null
            }
            if (playlist.size > 1 && items.isNotEmpty()) {
                val videoIndex = playlist.take(currentIndex + 1).count { !isImageUri(it) } - 1
                player?.setMediaItems(items, videoIndex.coerceAtLeast(0), 0L)
            } else {
                player?.setMediaItem(mediaItem)
            }
            player?.prepare()
            player?.play()
        } catch (e: Exception) {
            Timber.e(e, "Error playing media")
            tvStatus.text = "播放错误"
            tvError.text = "播放错误: ${e.message ?: "未知错误"}"
            tvError.visibility = View.VISIBLE
            updateBufferingState(false)
        }
    }

    private fun createMediaItem(uri: String): MediaItem {
        // 检测 HLS 流
        val isHls = uri.contains(".m3u8") || uri.contains("/playlist/m3u8")

        return if (isHls) {
            // HLS 流：明确指定 MIME 类型
            MediaItem.Builder()
                .setUri(uri)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
        } else {
            // 其他格式：让 ExoPlayer 自动检测
            MediaItem.fromUri(uri)
        }
    }

    private fun showImage(uri: String) {
        Timber.i("Showing image: $uri")
        player?.pause()
        playerView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        tvStatus.text = "正在显示图片"
        updateBufferingState(true)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    if (uri.startsWith("http://") || uri.startsWith("https://")) {
                        URL(uri).openStream().use { BitmapFactory.decodeStream(it) }
                    } else {
                        contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
                    }
                }
                updateBufferingState(false)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    adaptOrientationToImage(bitmap.width, bitmap.height)
                    startImageSlideShow()
                } else {
                    throw IllegalArgumentException("无法加载图片")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error showing image")
                updateBufferingState(false)
                tvStatus.text = "图片加载错误"
                tvError.text = "图片加载错误: ${e.message ?: "未知错误"}"
                tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun startImageSlideShow() {
        if (playlist.count { isImageUri(it) } <= 1) return
        slideJob?.cancel()
        slideJob = lifecycleScope.launch {
            while (isActive && isCurrentImage()) {
                delay(IMAGE_SLIDE_INTERVAL_MS)
                playNextOrFinish(loopImages = true)
            }
        }
    }

    private fun stopImageSlideShow() {
        slideJob?.cancel()
        slideJob = null
        if (isCurrentImage()) tvStatus.text = "图片轮播已暂停"
    }

    private fun playNextOrFinish(loopImages: Boolean = false) {
        if (playlist.isEmpty()) return
        if (currentIndex < playlist.lastIndex) {
            currentIndex++
            playCurrent()
        } else if (loopImages) {
            currentIndex = 0
            playCurrent()
        } else {
            updateBufferingState(false)
        }
    }

    private fun isCurrentImage(): Boolean = mediaUri?.let { isImageUri(it) } == true

    private fun isImageUri(uri: String): Boolean {
        val clean = uri.substringBefore('?').lowercase()
        return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") ||
            clean.endsWith(".gif") || clean.endsWith(".webp") || clean.endsWith(".bmp") ||
            clean.startsWith("content://") && clean.contains("image")
    }

    private fun updateBufferingState(isBuffering: Boolean) {
        progressBar.visibility = if (isBuffering) View.VISIBLE else View.GONE
        playerView.findViewById<View?>(R.id.buffering_indicator)?.visibility = if (isBuffering) View.VISIBLE else View.GONE
    }

    private fun showQualityDialog() {
        val labels = arrayOf("自动", "流畅 480p", "高清 720p", "超清 1080p", "原画")
        val heights = intArrayOf(QUALITY_AUTO, 480, 720, 1080, Int.MAX_VALUE)
        val checked = heights.indexOf(qualityHeight).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle("选择画质")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                qualityHeight = heights[which]
                applyQuality(qualityHeight)
                dialog.dismiss()
            }
            .show()
    }

    private fun applyQuality(height: Int) {
        val builder = trackSelector?.buildUponParameters() ?: return
        when (height) {
            QUALITY_AUTO -> builder.clearVideoSizeConstraints()
            Int.MAX_VALUE -> builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            else -> builder.setMaxVideoSize(Int.MAX_VALUE, height)
        }
        trackSelector?.setParameters(builder)
        tvStatus.text = if (height == QUALITY_AUTO) "画质：自动" else "画质：${height}p"
    }

    private fun showSpeedDialog(speedView: TextView) {
        val labels = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog.Builder(this)
            .setTitle("播放速度")
            .setItems(labels) { _, which ->
                player?.setPlaybackSpeed(speeds[which])
                speedView.text = labels[which]
            }
            .show()
    }

    private fun adaptOrientationToVideo() {
        // TV 端固定横屏显示，竖屏视频由播放器按比例居中渲染。
        // 不再根据视频宽高切换 Activity 方向，避免 Surface 重建导致竖屏投屏反复缓冲/黑屏。
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun adaptOrientationToImage(width: Int, height: Int) {
        requestedOrientation = if (width >= height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    private fun registerControlReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_STOP)
            addAction(ACTION_SEEK)
            addAction(ACTION_SET_VOLUME)
            addAction(ACTION_SET_PLAYLIST)
            addAction(ACTION_SET_SPEED)
            addAction(ACTION_SET_QUALITY_URL)
        }
        registerReceiver(controlReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onStart() {
        super.onStart()
        if (isCurrentImage()) startImageSlideShow() else {
            player?.play()
            startProgressUpdates()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isCurrentImage()) stopImageSlideShow() else player?.pause()
        stopProgressUpdates()
        reportPlaybackPosition()
    }

    private fun stopPlayback() {
        stopImageSlideShow()
        stopProgressUpdates()
        airPlayAspectJob?.cancel()
        airPlayAspectJob = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player?.stop()
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressUpdateJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                reportPlaybackPosition()
            }
        }
    }

    private fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }

    private fun reportPlaybackPosition() {
        val currentPlayer = player ?: return
        val position = currentPlayer.currentPosition
        val duration = currentPlayer.duration.takeIf { it > 0 } ?: 0L

        // 发送广播以更新 DLNA renderer 状态
        val intent = Intent("com.weekd.miracastreceiver.ACTION_UPDATE_POSITION").apply {
            putExtra("position", position)
            putExtra("duration", duration)
            putExtra("is_playing", currentPlayer.isPlaying)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Error unregistering receiver")
        }
        stopImageSlideShow()
        airPlayAspectJob?.cancel()
        airPlayAspectJob = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rtpReceiver?.stop()
        rtpReceiver = null
        airPlayMirrorSurface = null
        player?.release()
        player = null
        Timber.i("PlayerActivity destroyed")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        stopPlayback()
        super.onBackPressed()
    }
}
