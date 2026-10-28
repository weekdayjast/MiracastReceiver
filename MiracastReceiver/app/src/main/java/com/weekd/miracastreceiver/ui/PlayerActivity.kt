package com.weekd.miracastreceiver.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
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
        var mirrorSurface: Surface? = null
            private set(value) {
                if (field === value) return
                field = value
                mirrorSurfaceListener?.invoke()
            }

        /** 镜像 Surface 换了（手动旋转切换渲染视图）时通知 AirPlay 的 URL 视频播放器跟着换。 */
        @Volatile
        var mirrorSurfaceListener: (() -> Unit)? = null

        private const val IMAGE_SLIDE_INTERVAL_MS = 5_000L
        private const val QUALITY_AUTO = -1

        // Kodi 风格连续快进快退：短时间内连续按键会加大跳转步长
        private const val SEEK_ACCEL_WINDOW_MS = 1_500L
        private const val SEEK_COMMIT_DELAY_MS = 500L
        private val SEEK_STEP_TABLE_MS = longArrayOf(10_000L, 30_000L, 60_000L, 120_000L, 300_000L)

        private const val ACTION_PLAYBACK_STOPPED = "com.weekd.miracastreceiver.ACTION_PLAYBACK_STOPPED"

        private const val ROTATE_BUTTON_HIDE_DELAY_MS = 4_000L
        private val ROTATE_REVEAL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN
        )
    }

    private lateinit var playerView: PlayerView
    private lateinit var mirrorSurfaceView: SurfaceView
    private lateinit var imageView: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvTitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var tvStreamInfo: TextView
    private lateinit var bufferingIndicator: ProgressBar

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
    private var isMiracastSession = false
    private var isAirPlayMirrorSession = false
    private var mirrorAspectJob: Job? = null
    private lateinit var btnRotateOverlay: ImageButton
    private var rotateButtonHideJob: Job? = null

    // 手动旋转画面：只转画面内容，不改屏幕方向，所以电视上也有效
    /** 当前手动旋转角度（0/90/180/270，顺时针）。 */
    private var rotationDegrees = 0
    /** 镜像旋转用的 TextureView 层：0° 时隐藏，仍走 SurfaceView 保住电视上的性能。 */
    private lateinit var mirrorRotateLayer: FrameLayout
    private lateinit var mirrorRotateTexture: TextureView
    private var mirrorHolderSurface: Surface? = null
    private var mirrorTextureSurface: Surface? = null
    /** 点播视频旋转用的 TextureView，挂在 PlayerView 的 overlay 层（画面之上、控制条之下）。 */
    private var exoRotateLayer: FrameLayout? = null
    private var exoRotateTexture: TextureView? = null
    private var currentSpeed = 1f

    // 视频流信息面板
    private val streamInfoTracker = StreamInfoTracker()
    private var streamInfoJob: Job? = null
    private var isStreamInfoVisible = false
    private var bandwidthEstimateBps = 0L

    // Kodi 风格连续快进快退状态
    private var pendingSeekDeltaMs = 0L
    private var seekAccelerationStep = 0
    private var lastSeekWasForward = true
    private var lastSeekPressAt = 0L
    private var seekCommitJob: Job? = null
    private var isControllerVisible = false
    private var isDialogShowing = false

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
                    currentSpeed = speed
                    tvStatus.text = getString(R.string.status_speed, "${speed}x")
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
        mirrorSurfaceView = findViewById(R.id.airplay_mirror_surface)
        imageView = findViewById(R.id.image_view)
        tvStatus = findViewById(R.id.tv_status)
        tvTitle = findViewById(R.id.tv_title)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        tvStreamInfo = findViewById(R.id.tv_stream_info)
        bufferingIndicator = findViewById(R.id.buffering_indicator)
        btnRotateOverlay = findViewById(R.id.btn_rotate_overlay)
        btnRotateOverlay.setOnClickListener {
            rotateClockwise()
            showRotateButton(focus = false)
        }
        mirrorRotateLayer = findViewById(R.id.mirror_rotate_layer)
        mirrorRotateTexture = findViewById(R.id.mirror_rotate_texture)
        mirrorRotateTexture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                mirrorTextureSurface = Surface(st)
                updateMirrorSurfaceTarget()
                applyRotation()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyRotation()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                val old = mirrorTextureSurface
                mirrorTextureSurface = null
                updateMirrorSurfaceTarget()
                old?.release()
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        // 屏幕尺寸/方向变化后重新计算旋转后的缩放
        findViewById<View>(android.R.id.content).addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) applyRotation()
        }
    }

    /**
     * @param lowLatency 实时投屏（Miracast）用。默认的缓冲策略是为点播设计的
     *   （起播要缓冲 2.5 秒、重缓冲后要 5 秒），而实时流的数据严格按实时速率到达，
     *   永远攒不出那么多缓冲，会陷入「解几帧 → 缓冲耗尽 → 转圈」的循环。
     */
    private fun initPlayer(lowLatency: Boolean = false) {
        player?.release()

        // 默认不限制分辨率（对应画质菜单的「自动」）。原先默认 setMaxVideoSizeSd() 会把所有
        // 播放压到标清，Miracast 的 1080p 视频轨会被直接排除，导致选不出轨道、
        // loader 停止加载，最终报 "stuck buffering and not loading"。
        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().clearVideoSizeConstraints())
        }

        val loadControl = DefaultLoadControl.Builder()
            .apply {
                if (lowLatency) {
                    // maxBufferMs 不能压太小：到达上限后 ExoPlayer 会停止读取数据源，
                    // 而 RTP 仍按实时速率灌进管道，管道溢出丢数据就会把 TS 流打出空洞，
                    // 解码器拿不到完整 PES 直接黑屏。留出足够余量让它持续排空管道。
                    setBufferDurationsMs(
                        /* minBufferMs = */ 1_000,
                        /* maxBufferMs = */ 8_000,
                        /* bufferForPlaybackMs = */ 500,
                        /* bufferForPlaybackAfterRebufferMs = */ 1_000
                    )
                    setPrioritizeTimeOverSizeThresholds(true)
                }
            }
            .build()

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updateBufferingState(playbackState == Player.STATE_BUFFERING)
                        when (playbackState) {
                            Player.STATE_IDLE -> Timber.d("Player state: IDLE")
                            Player.STATE_BUFFERING -> {
                                Timber.d("Player state: BUFFERING")
                                tvStatus.text = getString(R.string.status_buffering)
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
                                tvStatus.text = getString(R.string.status_finished)
                                reportPlaybackPosition()
                                playNextOrFinish()
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // 缓冲中 isPlaying 也是 false，此时别把 onPlaybackStateChanged
                        // 刚写好的「正在缓冲...」覆盖成「已暂停」，那会让人以为是暂停了
                        tvStatus.text = when {
                            isPlaying -> getString(R.string.playing)
                            playbackState == Player.STATE_READY -> getString(R.string.status_paused)
                            else -> return
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        applyRotation()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Timber.e(error, "Player error")
                        updateBufferingState(false)
                        showPlaybackError(error.message)
                        tvError.visibility = View.VISIBLE
                    }
                })
                // 视频信息面板的「网速」取自带宽估计（ExoPlayer 默认的 DefaultBandwidthMeter 采样）
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onBandwidthEstimate(
                        eventTime: AnalyticsListener.EventTime,
                        totalLoadTimeMs: Int,
                        totalBytesLoaded: Long,
                        bitrateEstimate: Long
                    ) {
                        bandwidthEstimateBps = bitrateEstimate
                    }
                })
            }

        playerView.player = player
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            findViewById<View?>(R.id.status_bar)?.visibility = visibility
            isControllerVisible = visibility == View.VISIBLE
        })
        playerView.setShowNextButton(true)
        playerView.setShowPreviousButton(true)
        setupControllerActions()
    }

    private fun setupControllerActions() {
        playerView.findViewById<View?>(R.id.btn_more)?.setOnClickListener { showMoreMenu() }
        playerView.findViewById<View?>(R.id.btn_rotate)?.setOnClickListener { rotateClockwise() }
        playerView.findViewById<View?>(R.id.exo_ffwd)?.setOnClickListener { handleSeekPress(forward = true) }
        playerView.findViewById<View?>(R.id.exo_rew)?.setOnClickListener { handleSeekPress(forward = false) }
    }

    /** 「更多」弹窗：控制条精简后，低频功能都收到这里。 */
    private fun showMoreMenu() {
        val labels = arrayOf(
            getString(if (isStreamInfoVisible) R.string.menu_stream_info_hide else R.string.menu_stream_info),
            getString(R.string.menu_quality),
            getString(R.string.menu_speed, formatSpeedLabel(currentSpeed)),
            getString(R.string.menu_subtitles),
            getString(R.string.cd_rotate_screen)
        )
        isDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle(R.string.more)
            .setItems(labels) { _, which ->
                // 本弹窗关闭后再打开下一级弹窗，否则 onDismiss 会把 isDialogShowing 误置回 false
                tvStatus.post {
                    when (which) {
                        0 -> toggleStreamInfo()
                        1 -> showQualityDialog()
                        2 -> showSpeedDialog()
                        3 -> Toast.makeText(this, R.string.subtitles_auto, Toast.LENGTH_SHORT).show()
                        4 -> rotateClockwise()
                    }
                }
            }
            .setOnDismissListener { isDialogShowing = false }
            .show()
    }

    private fun formatSpeedLabel(speed: Float): String =
        if (speed == 1f) "1.0x" else "${speed}x"

    /**
     * 参考 Kodi 的连续快进快退：短时间内连续按键会累加跳转步长并放大跨度，
     * 松开按键一段时间后再统一提交一次 seek，避免频繁 seek 造成反复缓冲。
     */
    private fun handleSeekPress(forward: Boolean) {
        if (isCurrentImage() || player == null) return
        val now = SystemClock.elapsedRealtime()
        val withinAccelWindow = now - lastSeekPressAt <= SEEK_ACCEL_WINDOW_MS
        seekAccelerationStep = if (withinAccelWindow && forward == lastSeekWasForward) {
            (seekAccelerationStep + 1).coerceAtMost(SEEK_STEP_TABLE_MS.lastIndex)
        } else {
            pendingSeekDeltaMs = 0L
            0
        }
        lastSeekWasForward = forward
        lastSeekPressAt = now

        val step = SEEK_STEP_TABLE_MS[seekAccelerationStep]
        pendingSeekDeltaMs += if (forward) step else -step

        previewPendingSeek()

        seekCommitJob?.cancel()
        seekCommitJob = lifecycleScope.launch {
            delay(SEEK_COMMIT_DELAY_MS)
            commitPendingSeek()
        }
    }

    private fun previewPendingSeek() {
        val currentPlayer = player ?: return
        val duration = currentPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (currentPlayer.currentPosition + pendingSeekDeltaMs).coerceIn(0L, duration)
        val sign = if (pendingSeekDeltaMs >= 0) "+" else "-"
        val deltaSeconds = kotlin.math.abs(pendingSeekDeltaMs) / 1000
        tvStatus.text = "${sign}${deltaSeconds}s  ${formatTimeMs(target)}"
        playerView.showController()
    }

    private fun commitPendingSeek() {
        val currentPlayer = player ?: return
        if (pendingSeekDeltaMs == 0L) return
        val duration = currentPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (currentPlayer.currentPosition + pendingSeekDeltaMs).coerceIn(0L, duration)
        currentPlayer.seekTo(target)
        pendingSeekDeltaMs = 0L
        seekAccelerationStep = 0
        reportPlaybackPosition()
    }

    private fun formatTimeMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 镜像/图片模式：遥控器按 OK 或上下键先呼出旋转按钮并给它焦点，再按 OK 就旋转
        if (event.action == KeyEvent.ACTION_DOWN && !isDialogShowing && usesRotateOverlay() &&
            event.keyCode in ROTATE_REVEAL_KEYS) {
            val alreadyFocused = btnRotateOverlay.visibility == View.VISIBLE && btnRotateOverlay.isFocused
            showRotateButton(focus = true)
            if (!alreadyFocused) return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && !isDialogShowing) {
            // 镜像/Miracast 没有控制条，INFO / MENU 键是信息面板的唯一入口
            if (event.keyCode == KeyEvent.KEYCODE_INFO || event.keyCode == KeyEvent.KEYCODE_MENU) {
                toggleStreamInfo()
                return true
            }
            // 面板打开时返回键先关面板，不要直接结束播放
            if (event.keyCode == KeyEvent.KEYCODE_BACK && isStreamInfoVisible) {
                hideStreamInfo()
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && !isCurrentImage() && !isDialogShowing) {
            val isForwardKey = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            val isRewindKey = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
            if (isForwardKey || isRewindKey) {
                val isHardwareSeekKey = event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ||
                    event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                val inSeekMode = SystemClock.elapsedRealtime() - lastSeekPressAt <= SEEK_ACCEL_WINDOW_MS
                // 方向键仅在 OSD 未显示或正处于连续快进快退中时才拦截用于 seek，
                // 否则放行给控制条做按钮焦点导航（与 Kodi 行为一致）。
                if (isHardwareSeekKey || inSeekMode || !isControllerVisible) {
                    handleSeekPress(forward = isForwardKey)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleIntent(intent: Intent?) {
        // 每次新的投屏请求都从 0° 开始
        rotationDegrees = 0
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
        mediaTitle = playlistTitles.getOrNull(currentIndex) ?: getString(R.string.title_dlna_indexed, currentIndex + 1, playlist.size)
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
        isAirPlayMirrorSession = true
        isMiracastSession = false
        streamInfoTracker.reset()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopImageSlideShow()
        player?.clearVideoSurface()
        player?.pause()
        playerView.player = null
        playerView.visibility = View.GONE
        mirrorSurfaceView.visibility = View.VISIBLE
        imageView.visibility = View.GONE
        tvTitle.text = getString(R.string.title_iphone_mirror)
        tvStatus.text = getString(R.string.status_receiving_iphone)
        tvError.visibility = View.GONE
        updateBufferingState(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        publishMirrorSurface()
        applyRotation()
        showRotateButton(focus = false)
    }

    /**
     * 把镜像 SurfaceView 的 Surface 发布给解码器。AirPlay 镜像和 Miracast 共用这一块
     * Surface —— 两者都是「解码器直接送显」的实时镜像，不经过 ExoPlayer。
     *
     * 应用切后台时 SurfaceView 会销毁 Surface、回到前台再造一个新的，所以必须持续跟踪
     * 变化并置空，否则解码器会往失效的 Surface 上写，画面一直黑。
     */
    private fun publishMirrorSurface() {
        val waitingText = getString(
            if (isMiracastSession) R.string.status_waiting_windows else R.string.status_waiting_airplay
        )
        val activeText = getString(
            if (isMiracastSession) R.string.status_receiving_windows else R.string.status_receiving_iphone
        )

        fun publish(holder: SurfaceHolder) {
            mirrorHolderSurface = holder.surface.takeIf { it.isValid }
            updateMirrorSurfaceTarget()
            Timber.i("Mirror surface ${if (mirrorSurface == null) "not ready" else "ready"}")
            tvStatus.text = if (mirrorSurface == null) waitingText else activeText
        }

        mirrorSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = publish(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = publish(holder)
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Timber.i("Mirror surface destroyed")
                mirrorHolderSurface = null
                updateMirrorSurfaceTarget()
            }
        })
        mirrorSurfaceView.post { publish(mirrorSurfaceView.holder) }
        startMirrorAspectFitUpdates()
    }

    private fun startMirrorAspectFitUpdates() {
        mirrorAspectJob?.cancel()
        mirrorAspectJob = lifecycleScope.launch {
            var lastW = 0
            var lastH = 0
            var lastParentW = 0
            var lastParentH = 0
            while (isActive) {
                val videoW = StreamStats.videoWidth
                val videoH = StreamStats.videoHeight
                // 手动旋转后父视图宽高会对调，也要重新适配，否则画面仍按旋转前的框显示
                val parent = mirrorSurfaceView.parent as? View
                val parentW = parent?.width ?: 0
                val parentH = parent?.height ?: 0
                // 只有真正适配成功才记下尺寸：上一会话残留的尺寸会在布局完成前就读到，
                // 此时父视图宽高为 0 无法适配，若提前记下就不再重试，画面会一直被拉伸铺满。
                if (videoW > 0 && videoH > 0 &&
                    (videoW != lastW || videoH != lastH || parentW != lastParentW || parentH != lastParentH) &&
                    fitMirrorSurface(videoW, videoH)) {
                    lastW = videoW
                    lastH = videoH
                    lastParentW = parentW
                    lastParentH = parentH
                    applyRotation()
                }
                delay(300)
            }
        }
    }

    /** 按视频比例缩放镜像 Surface；父视图尚未完成布局时返回 false，由调用方稍后重试。 */
    private fun fitMirrorSurface(videoW: Int, videoH: Int): Boolean {
        val parent = mirrorSurfaceView.parent as? View ?: return false
        val parentW = parent.width
        val parentH = parent.height
        if (parentW <= 0 || parentH <= 0) return false

        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val parentAspect = parentW.toFloat() / parentH.toFloat()
        val (targetW, targetH) = if (videoAspect > parentAspect) {
            parentW to (parentW / videoAspect).toInt()
        } else {
            (parentH * videoAspect).toInt() to parentH
        }

        mirrorSurfaceView.layoutParams = mirrorSurfaceView.layoutParams.apply {
            width = targetW.coerceAtLeast(1)
            height = targetH.coerceAtLeast(1)
        }
        Timber.i("AirPlay mirror aspect-fit: video=${videoW}x$videoH view=${targetW}x$targetH parent=${parentW}x$parentH")
        return true
    }

    /**
     * Miracast 显示。RTP 接收、TS 解复用和解码都由
     * [com.weekd.miracastreceiver.miracast.WfdServer] 那条链路完成，这里只负责把镜像
     * Surface 交出去 —— 和 AirPlay 镜像完全同一套机制。
     *
     * 刻意不经过 ExoPlayer：播放器的缓冲和时钟同步会引入秒级延迟（实测超过 10 秒），
     * 而第二屏幕这种用途要的是「收到即解码、解完即送显」。
     */
    private fun startMiracastPlayback(rtpPort: Int, sessionId: String?) {
        Timber.i("Starting Miracast display: rtpPort=$rtpPort session=$sessionId")
        isMiracastSession = true
        isAirPlayMirrorSession = false
        streamInfoTracker.reset()
        // 画面不经过 ExoPlayer，PlayerView 的自动常亮不生效，必须手动保持，否则电视会进屏保
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopImageSlideShow()

        player?.clearVideoSurface()
        player?.pause()
        playerView.player = null
        playerView.visibility = View.GONE
        imageView.visibility = View.GONE
        mirrorSurfaceView.visibility = View.VISIBLE
        tvTitle.text = getString(R.string.title_windows_display)
        tvStatus.text = getString(R.string.status_receiving_windows)
        tvError.visibility = View.GONE
        updateBufferingState(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        publishMirrorSurface()
        applyRotation()
        showRotateButton(focus = false)
    }

    private fun playMedia(uri: String) {
        Timber.i("Playing media: $uri")
        isMiracastSession = false
        isAirPlayMirrorSession = false
        streamInfoTracker.reset()
        stopImageSlideShow()
        imageView.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideRotateButton()
        applyRotation()

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
            showPlaybackError(e.message)
            tvError.visibility = View.VISIBLE
            updateBufferingState(false)
        }
    }

    private fun showPlaybackError(message: String?) {
        tvStatus.text = getString(R.string.status_playback_error)
        tvError.text = getString(R.string.error_playback_detail, message ?: getString(R.string.error_unknown))
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
        isMiracastSession = false
        isAirPlayMirrorSession = false
        player?.pause()
        playerView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        tvStatus.text = getString(R.string.status_showing_image)
        updateBufferingState(true)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        applyRotation()
        showRotateButton(focus = false)

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
                    imageView.post { applyRotation() }
                    startImageSlideShow()
                } else {
                    throw IllegalArgumentException(getString(R.string.error_image_decode))
                }
            } catch (e: Exception) {
                Timber.e(e, "Error showing image")
                updateBufferingState(false)
                tvStatus.text = getString(R.string.status_image_error)
                tvError.text = getString(R.string.error_image_detail, e.message ?: getString(R.string.error_unknown))
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
        if (isCurrentImage()) tvStatus.text = getString(R.string.status_slideshow_paused)
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
            reportPlaybackStopped()
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
        bufferingIndicator.visibility = if (isBuffering) View.VISIBLE else View.GONE
    }

    // ─── 视频流信息面板 ────────────────────────────────────────────────────
    /** 切换信息面板。镜像/Miracast 没有控制条，只能靠遥控 INFO / MENU 键触发。 */
    private fun toggleStreamInfo() {
        if (isStreamInfoVisible) hideStreamInfo() else showStreamInfo()
    }

    private fun showStreamInfo() {
        isStreamInfoVisible = true
        streamInfoTracker.reset()
        tvStreamInfo.text = buildStreamInfoText()
        tvStreamInfo.visibility = View.VISIBLE
        streamInfoJob?.cancel()
        streamInfoJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                tvStreamInfo.text = buildStreamInfoText()
            }
        }
    }

    private fun hideStreamInfo() {
        isStreamInfoVisible = false
        streamInfoJob?.cancel()
        streamInfoJob = null
        tvStreamInfo.visibility = View.GONE
    }

    private fun buildStreamInfoText(): String = when {
        isAirPlayMirrorSession -> buildAirPlayStreamInfo()
        isMiracastSession -> buildMiracastStreamInfo()
        else -> buildExoPlayerStreamInfo()
    }

    /** DLNA / 普通网络播放：分辨率、帧率、码率取自当前视频轨，网速取带宽估计。 */
    private fun buildExoPlayerStreamInfo(): String {
        val currentPlayer = player
        val format = currentPlayer?.videoFormat
        val videoSize = currentPlayer?.videoSize
        val bitrateBps = listOfNotNull(format?.bitrate, format?.averageBitrate, format?.peakBitrate)
            .firstOrNull { it != Format.NO_VALUE }?.toLong() ?: 0L
        return streamInfoLines(
            resolution = StreamInfoTracker.formatResolution(videoSize?.width ?: 0, videoSize?.height ?: 0),
            codec = StreamInfoTracker.formatCodec(format?.sampleMimeType),
            fps = StreamInfoTracker.formatFps(format?.frameRate ?: 0f),
            bitrate = StreamInfoTracker.formatBitrate(bitrateBps),
            speed = StreamInfoTracker.formatSpeed(bandwidthEstimateBps / 8)
        )
    }

    /** AirPlay 镜像：码率只算视频流，网速把音频流字节也算进去。 */
    private fun buildAirPlayStreamInfo(): String {
        val videoBytes = StreamStats.videoBytesTotal
        val totalBytes = videoBytes + StreamStats.audioBytesTotal
        val sample = streamInfoTracker.sample(totalBytes, StreamStats.videoFramesTotal)
        // 视频码率 = 总码率中扣掉音频部分，按本次采样的视频/总字节比例折算
        val videoShare = if (totalBytes > 0) videoBytes.toDouble() / totalBytes else 1.0
        return streamInfoLines(
            resolution = StreamInfoTracker.formatResolution(StreamStats.videoWidth, StreamStats.videoHeight),
            codec = StreamInfoTracker.formatCodec(StreamStats.videoCodec),
            fps = StreamInfoTracker.formatFps(sample.fps.toFloat()),
            bitrate = StreamInfoTracker.formatBitrate((sample.bitrateBps * videoShare).toLong()),
            speed = StreamInfoTracker.formatSpeed(sample.bytesPerSec)
        )
    }

    /**
     * Miracast：不经过 ExoPlayer，所以分辨率取解码器上报的值（[StreamStats]，由
     * VideoDecoder 写入），码率和网速取 [RtpReceiver] 的累计字节数。
     * 帧率暂不统计 —— 解码路径上没有帧计数器。
     */
    private fun buildMiracastStreamInfo(): String {
        val sample = streamInfoTracker.sample(RtpReceiver.active?.bytesReceived ?: 0L, 0L)
        return streamInfoLines(
            resolution = StreamInfoTracker.formatResolution(StreamStats.videoWidth, StreamStats.videoHeight),
            codec = StreamInfoTracker.formatCodec("video/avc"),
            fps = StreamInfoTracker.formatFps(0f),
            bitrate = StreamInfoTracker.formatBitrate(sample.bitrateBps),
            speed = StreamInfoTracker.formatSpeed(sample.bytesPerSec)
        )
    }

    private fun streamInfoLines(
        resolution: String,
        codec: String,
        fps: String,
        bitrate: String,
        speed: String
    ): String {
        val (displayW, displayH) = currentDisplaySize()
        val rows = listOf(
            getString(R.string.info_source_resolution) to resolution,
            getString(R.string.info_display_resolution) to StreamInfoTracker.formatResolution(displayW, displayH),
            getString(R.string.info_codec) to codec,
            getString(R.string.info_frame_rate) to fps,
            getString(R.string.info_bitrate) to bitrate,
            getString(R.string.info_network_speed) to speed
        )
        // 各语言标签长短差别很大（英文、俄文远比中文长），按最长标签对齐值列
        val columns = rows.maxOf { StreamInfoTracker.labelWidth(it.first) } + 2
        return rows.joinToString("\n", prefix = getString(R.string.info_title) + "\n") { (label, value) ->
            StreamInfoTracker.padLabel(label, columns) + value
        }
    }

    /**
     * 画面实际渲染到屏幕上的像素尺寸。
     * PlayerView 会按比例把内容框缩到片源宽高比，所以这里拿到的是去掉黑边后的真实显示尺寸；
     * 镜像模式则读 [fitMirrorSurface] 调整过的镜像 Surface。视图还没测量时退回屏幕分辨率。
     */
    private fun currentDisplaySize(): Pair<Int, Int> {
        val renderView = if (isAirPlayMirrorSession) mirrorSurfaceView else playerView.videoSurfaceView
        val width = renderView?.width ?: 0
        val height = renderView?.height ?: 0
        if (width > 0 && height > 0) return width to height
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun showQualityDialog() {
        val labels = arrayOf(
            getString(R.string.quality_auto),
            getString(R.string.quality_480),
            getString(R.string.quality_720),
            getString(R.string.quality_1080),
            getString(R.string.quality_original)
        )
        val heights = intArrayOf(QUALITY_AUTO, 480, 720, 1080, Int.MAX_VALUE)
        val checked = heights.indexOf(qualityHeight).takeIf { it >= 0 } ?: 0
        isDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle(R.string.quality_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                qualityHeight = heights[which]
                applyQuality(qualityHeight)
                dialog.dismiss()
            }
            .setOnDismissListener { isDialogShowing = false }
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
        tvStatus.text = getString(
            R.string.status_quality,
            if (height == QUALITY_AUTO) getString(R.string.quality_auto) else "${height}p"
        )
    }

    private fun showSpeedDialog() {
        val labels = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        isDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle(R.string.speed_title)
            .setItems(labels) { _, which ->
                player?.setPlaybackSpeed(speeds[which])
                currentSpeed = speeds[which]
                tvStatus.text = getString(R.string.status_speed, labels[which])
            }
            .setOnDismissListener { isDialogShowing = false }
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

    /**
     * 手动把画面顺时针转 90°。每次都朝同一方向转，转满一圈回到 0°。
     * 只旋转画面内容、不改屏幕方向，所以电视（忽略方向请求）上也有效。
     */
    private fun rotateClockwise() {
        rotationDegrees = (rotationDegrees + 90) % 360
        Timber.i("Manual rotation -> $rotationDegrees°")
        applyRotation()
    }

    /** 按当前模式把 [rotationDegrees] 应用到画面上；尺寸变化时也会被反复调用，要保证幂等。 */
    private fun applyRotation() {
        when {
            isMirrorSession() -> applyMirrorRotation()
            isCurrentImage() -> applyImageRotation()
            else -> applyExoRotation()
        }
        // 离开的模式恢复原状，避免残留的变换带到下一个模式
        if (!isMirrorSession()) setMirrorRotateLayerVisible(false)
        if (!isCurrentImage() || isMirrorSession()) resetImageRotation()
    }

    private fun applyMirrorRotation() {
        setMirrorRotateLayerVisible(rotationDegrees != 0)
        if (rotationDegrees != 0) {
            setTextureRotation(mirrorRotateTexture, StreamStats.videoWidth, StreamStats.videoHeight)
        }
    }

    private fun setMirrorRotateLayerVisible(visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (mirrorRotateLayer.visibility != target) mirrorRotateLayer.visibility = target
        updateMirrorSurfaceTarget()
    }

    /**
     * 选出解码器该往哪块 Surface 送帧：旋转时用 TextureView（可以做变换），
     * 0° 时用 SurfaceView（走硬件视频层，电视上 4K 也不卡）。解码器检测到 Surface
     * 变化会原地切换输出（见 VideoDecoder.switchOutputSurface）。
     */
    private fun updateMirrorSurfaceTarget() {
        // TextureView 第一次显示时 SurfaceTexture 要等一帧绘制才就绪。这段时间继续用 SurfaceView，
        // 千万不能发布 null：解码器会当成 Surface 丢失而重建，重建后要等 iPhone 的下一个关键帧，
        // 静止画面下可能一直黑屏。就绪后再原地切过去。
        val useTexture = isMirrorSession() && rotationDegrees != 0 && mirrorTextureSurface != null
        val target = if (useTexture) mirrorTextureSurface else mirrorHolderSurface
        if (target !== mirrorSurface) {
            Timber.i("Mirror render target -> ${if (useTexture) "TextureView" else "SurfaceView"} (${if (target == null) "not ready" else "ready"})")
            mirrorSurface = target
        }
    }

    private fun applyExoRotation() {
        val currentPlayer = player ?: return
        if (rotationDegrees == 0) {
            if (exoRotateLayer?.visibility == View.VISIBLE) {
                exoRotateLayer?.visibility = View.GONE
                exoRotateTexture?.let { currentPlayer.clearVideoTextureView(it) }
                (playerView.videoSurfaceView as? SurfaceView)?.let { currentPlayer.setVideoSurfaceView(it) }
            }
            return
        }
        val texture = exoRotateTexture ?: createExoRotateLayer() ?: return
        if (exoRotateLayer?.visibility != View.VISIBLE) {
            exoRotateLayer?.visibility = View.VISIBLE
            currentPlayer.setVideoTextureView(texture)
        }
        val size = currentPlayer.videoSize
        setTextureRotation(texture, (size.width * size.pixelWidthHeightRatio).toInt(), size.height)
    }

    /** 在 PlayerView 的 overlay 层（视频之上、控制条之下）放一块黑底 TextureView 承接旋转后的画面。 */
    private fun createExoRotateLayer(): TextureView? {
        val overlay = playerView.overlayFrameLayout ?: return null
        val texture = TextureView(this)
        val layer = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            visibility = View.GONE
            addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        // 尺寸变化（转屏、首次布局）后重新算变换
        texture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (rotationDegrees != 0) applyRotation() }
        overlay.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        exoRotateLayer = layer
        exoRotateTexture = texture
        return texture
    }

    /**
     * TextureView 默认把画面拉伸铺满整个视图。这里先还原成视频本身的宽高、绕中心旋转，
     * 再按旋转后的宽高等比缩放到能完整放进视图，效果等同 fitCenter + 旋转。
     */
    private fun setTextureRotation(texture: TextureView, videoW: Int, videoH: Int) {
        val viewW = texture.width.toFloat()
        val viewH = texture.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return
        val w = if (videoW > 0) videoW.toFloat() else viewW
        val h = if (videoH > 0) videoH.toFloat() else viewH
        val sideways = rotationDegrees % 180 != 0
        val rotatedW = if (sideways) h else w
        val rotatedH = if (sideways) w else h
        val fit = minOf(viewW / rotatedW, viewH / rotatedH)
        val matrix = Matrix().apply {
            postScale(w / viewW, h / viewH)
            postTranslate(-w / 2f, -h / 2f)
            postRotate(rotationDegrees.toFloat())
            postScale(fit, fit)
            postTranslate(viewW / 2f, viewH / 2f)
        }
        texture.setTransform(matrix)
        texture.invalidate()
    }

    /** 图片直接转 ImageView；转 90°/270° 时按旋转后的宽高重新等比缩放，保证完整显示。 */
    private fun applyImageRotation() {
        val drawable = imageView.drawable
        val viewW = imageView.width.toFloat()
        val viewH = imageView.height.toFloat()
        var scale = 1f
        if (drawable != null && viewW > 0f && viewH > 0f && rotationDegrees % 180 != 0) {
            val iw = drawable.intrinsicWidth.toFloat()
            val ih = drawable.intrinsicHeight.toFloat()
            if (iw > 0f && ih > 0f) {
                val fitNormal = minOf(viewW / iw, viewH / ih)
                val fitRotated = minOf(viewW / ih, viewH / iw)
                scale = fitRotated / fitNormal
            }
        }
        imageView.rotation = rotationDegrees.toFloat()
        imageView.scaleX = scale
        imageView.scaleY = scale
    }

    private fun resetImageRotation() {
        if (imageView.rotation != 0f || imageView.scaleX != 1f) {
            imageView.rotation = 0f
            imageView.scaleX = 1f
            imageView.scaleY = 1f
        }
    }

    private fun isMirrorSession() = isAirPlayMirrorSession || isMiracastSession

    /** 镜像和图片没有控制条，用浮动按钮旋转；点播视频的旋转按钮在控制条里。 */
    private fun usesRotateOverlay() = isMirrorSession() || isCurrentImage()

    /** 呼出浮动旋转按钮，几秒无操作后自动隐藏。[focus] 为 true 时让遥控器焦点落在按钮上，按 OK 即可旋转。 */
    private fun showRotateButton(focus: Boolean) {
        if (!usesRotateOverlay()) return
        btnRotateOverlay.visibility = View.VISIBLE
        if (focus) btnRotateOverlay.requestFocus()
        rotateButtonHideJob?.cancel()
        rotateButtonHideJob = lifecycleScope.launch {
            delay(ROTATE_BUTTON_HIDE_DELAY_MS)
            btnRotateOverlay.visibility = View.GONE
        }
    }

    private fun hideRotateButton() {
        rotateButtonHideJob?.cancel()
        btnRotateOverlay.visibility = View.GONE
    }

    // 镜像/图片模式没有控制条，点一下屏幕任意位置呼出旋转按钮
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) showRotateButton(focus = false)
        return super.dispatchTouchEvent(ev)
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
        // 同上：带 flags 的 registerReceiver 是 API 26 起才有的重载
        ContextCompat.registerReceiver(
            this, controlReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStart() {
        super.onStart()
        if (isCurrentImage()) startImageSlideShow() else {
            player?.play()
            startProgressUpdates()
        }
        if (isStreamInfoVisible) showStreamInfo()
    }

    override fun onStop() {
        super.onStop()
        if (isCurrentImage()) stopImageSlideShow() else player?.pause()
        stopProgressUpdates()
        // 保留 isStreamInfoVisible，回到前台时自动恢复刷新
        streamInfoJob?.cancel()
        streamInfoJob = null
        reportPlaybackPosition()
    }

    private fun stopPlayback() {
        stopImageSlideShow()
        stopProgressUpdates()
        mirrorAspectJob?.cancel()
        mirrorAspectJob = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player?.stop()
        reportPlaybackStopped()
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

    /**
     * TV 端本地退出/播放结束时通知 DLNA 渲染器状态归零，
     * 否则 Emby 会一直认为该设备处于播放中，无法取消投屏或切换到新文件。
     */
    private fun reportPlaybackStopped() {
        val intent = Intent(ACTION_PLAYBACK_STOPPED).apply {
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
        mirrorAspectJob?.cancel()
        mirrorAspectJob = null
        streamInfoJob?.cancel()
        streamInfoJob = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // RTP 接收器归 WfdServer 所有（会话结束时由它停止），这里不要碰
        mirrorSurface = null
        mirrorTextureSurface?.release()
        mirrorTextureSurface = null
        player?.release()
        player = null
        reportPlaybackStopped()
        Timber.i("PlayerActivity destroyed")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        stopPlayback()
        super.onBackPressed()
    }
}
