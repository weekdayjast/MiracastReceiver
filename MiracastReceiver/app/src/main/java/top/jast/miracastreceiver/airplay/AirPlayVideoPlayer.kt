package top.jast.miracastreceiver.airplay

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import top.jast.miracastreceiver.util.Logger

/** Snapshot of URL-video playback for `GET /playback-info`. */
data class PlaybackInfo(
    val durationSec: Double,
    val positionSec: Double,
    val rate: Double,        // 0.0 = paused, 1.0 = playing
    val readyToPlay: Boolean,
)

/**
 * AirPlayVideoPlayer — plays an AirPlay "video URL" stream (the non-mirroring mode: a sender app
 * like Safari or a TV app says "AirPlay this video" and POSTs a URL via `/play`). We hand the URL to
 * Android's [MediaPlayer] and render to the same streaming [Surface] the mirror decoder uses.
 *
 * Distinct from screen mirroring (H.264 over a data stream): here the TV fetches and plays the media
 * itself, and the sender only drives transport (`/rate`, `/scrub`, `/stop`) + polls `/playback-info`.
 *
 * Methods are `@Synchronized` because RTSP control verbs arrive on the RTSP thread while MediaPlayer
 * callbacks fire on its own thread.
 */
class AirPlayVideoPlayer(
    private val surfaceProvider: () -> Surface?,
    private val onEnded: () -> Unit = {},
    /** Playback could not start or broke (bad URL, 403, unsupported format). Fired once per play(). */
    private val onFailed: (reason: String) -> Unit = {},
    /** First frame is about to play (prepare finished). */
    private val onPlaying: () -> Unit = {},
) {
    private var mp: MediaPlayer? = null
    @Volatile private var prepared = false
    @Volatile private var startFraction = 0.0
    @Volatile private var startSeconds: Double? = null
    // prepare 完成前收到的 /rate、/scrub：此时调用 start()/seekTo() 会报 -38（状态非法），
    // 先记下来，prepared 后再执行。iOS 在 /play 之后几十毫秒内就会发 /rate?value=1。
    @Volatile private var pendingRate = 1f
    @Volatile private var pendingSeekSec: Double? = null
    // 已播到结尾（停在最后一帧）。iPhone 靠 /playback-info 的 位置==时长、速率=0 判断这一段结束，
    // MediaPlayer 播完后的 currentPosition 不一定等于时长；它看不出结束就会发 /rate=1，而此时
    // start() 会从头重播，iPhone 随即判定投送异常、不再投正片。
    @Volatile private var completed = false

    /**
     * Starts playing [url] once prepared, from [startPositionSeconds] if given, else from
     * [startPositionFraction] (0..1 of duration).
     */
    @Synchronized
    fun play(url: String, startPositionFraction: Double, startPositionSeconds: Double? = null) {
        release()
        startFraction = startPositionFraction.coerceIn(0.0, 1.0)
        startSeconds = startPositionSeconds?.takeIf { it > 0.0 }
        pendingRate = 1f
        pendingSeekSec = null
        completed = false
        Logger.i("AirPlay video: play url=$url start=$startFraction startSec=$startSeconds")
        val player = MediaPlayer()
        mp = player
        prepared = false
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            surfaceProvider()?.let { player.setSurface(it) }
            player.setOnPreparedListener { onPrepared(it) }
            player.setOnCompletionListener { p ->
                if (p !== mp) return@setOnCompletionListener
                completed = true
                Logger.i("AirPlay video: completed")
                onEnded()
            }
            // 画面 Surface 是镜像时按镜像流比例（竖屏手机 → 窄竖条）缩放的；把视频真实尺寸交给
            // StreamStats，PlayerActivity 的 aspect-fit 会按横屏视频重新铺排。回到镜像时解码器
            // 会重新上报镜像尺寸，画面再缩回去。
            player.setOnVideoSizeChangedListener { p, w, h ->
                if (p === mp && w > 0 && h > 0) {
                    StreamStats.videoWidth = w
                    StreamStats.videoHeight = h
                    Logger.i("AirPlay video: size ${w}x$h")
                }
            }
            player.setOnErrorListener { p, what, extra ->
                Logger.e("AirPlay video error what=$what extra=$extra url=$url")
                // Only report errors from the CURRENT player (a replaced one may still call back).
                if (p === mp) onFailed("MediaPlayer error what=$what extra=$extra")
                true   // handled — don't also fire onCompletion
            }
            player.setDataSource(url)
            player.prepareAsync()
        }.onFailure {
            Logger.e("AirPlay video: setup failed url=$url", it)
            release()
            onFailed("setup failed: ${it.message}")
        }
    }

    @Synchronized
    private fun onPrepared(player: MediaPlayer) {
        // onPrepared fires on MediaPlayer's own thread and can race release()/a new play(): if the
        // player was released (or replaced) while preparing, mp no longer points at it — bail before
        // touching a dead MediaPlayer (which would throw IllegalStateException).
        if (mp !== player) return
        // The Surface usually doesn't exist at setDataSource() time (the Activity creates it only
        // after CONNECTED) — attach it now that prepare has completed.
        surfaceProvider()?.let { s ->
            runCatching { player.setSurface(s) }
                .onFailure { Logger.e("AirPlay video: setSurface failed (surface still owned by mirror decoder?)", it) }
        } ?: Logger.w("AirPlay video: no Surface at prepare time — video will be invisible")
        val seekMs = pendingSeekSec?.let { (it * 1000).toInt() }
            ?: startSeconds?.let { (it * 1000).toInt() }
            ?: if (startFraction > 0.0) (startFraction * player.duration).toInt() else null
        seekMs?.let { ms -> runCatching { player.seekTo(ms) } }
        prepared = true
        if (pendingRate > 0f) runCatching { player.start() }
        Logger.i("AirPlay video: prepared dur=${runCatching { player.duration }.getOrDefault(0)}ms seek=${seekMs}ms → ${if (pendingRate > 0f) "playing" else "paused"}")
        onPlaying()
    }

    /** rate ≤ 0 pauses, > 0 resumes. */
    @Synchronized
    fun setRate(rate: Float) {
        val player = mp ?: return
        if (!prepared) { pendingRate = rate; return }
        if (completed && rate > 0f) {
            Logger.d("AirPlay video: /rate=$rate at end of item — ignored (no replay)")
            return
        }
        runCatching {
            if (rate <= 0f) { if (player.isPlaying) player.pause() }
            else { if (!player.isPlaying) player.start() }
        }
    }

    /** Seeks to [positionSec] seconds. */
    @Synchronized
    fun scrub(positionSec: Double) {
        if (mp != null && !prepared) { pendingSeekSec = positionSec; return }
        val player = mp ?: return
        // 跳回结尾之前的位置 = 发送端要重播/继续，此后的 /rate=1 才真正开始播放
        if (completed && positionSec * 1000 < runCatching { player.duration }.getOrDefault(0) - 500) completed = false
        runCatching { player.seekTo((positionSec * 1000).toInt()) }
    }

    /** Current playback snapshot for `/playback-info`, or null if nothing is loaded. */
    @Synchronized
    fun info(): PlaybackInfo? {
        val player = mp ?: return null
        if (!prepared) return PlaybackInfo(0.0, 0.0, 0.0, readyToPlay = false)
        val dur = runCatching { player.duration }.getOrDefault(0)
        if (completed) return PlaybackInfo(dur / 1000.0, dur / 1000.0, 0.0, readyToPlay = true)
        val pos = runCatching { player.currentPosition }.getOrDefault(0)
        val playing = runCatching { player.isPlaying }.getOrDefault(false)
        return PlaybackInfo(dur / 1000.0, pos / 1000.0, if (playing) 1.0 else 0.0, readyToPlay = true)
    }

    /** Re-attach the streaming surface (after the Activity recreates it on foreground). */
    @Synchronized
    fun attachSurface() {
        surfaceProvider()?.let { runCatching { mp?.setSurface(it) } }
    }

    @Synchronized
    fun release() {
        mp?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        mp = null
        prepared = false
    }
}

