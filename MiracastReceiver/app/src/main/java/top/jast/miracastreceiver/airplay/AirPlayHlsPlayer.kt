package top.jast.miracastreceiver.airplay

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import top.jast.miracastreceiver.util.Logger
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Media3 playback for AirPlay's relayed HLS manifests. Player access stays on its application looper. */
internal class AirPlayHlsPlayer(
    private val context: Context,
    private val surfaceProvider: () -> Surface?,
    private val onEnded: () -> Unit,
    private val onFailed: (String) -> Unit,
    private val onPlaying: () -> Unit,
    private val youtubeMlhls: Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var boundSurface: Surface? = null
    private var prepared = false
    private val subtitleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var released = false
    @Volatile private var snapshot = PlaybackInfo(0.0, 0.0, 0.0, readyToPlay = false)
    private val reportTick = object : Runnable {
        override fun run() {
            if (released) return
            player?.let { exo -> updateSurface(exo); refresh(exo) }
            main.postDelayed(this, 250L)
        }
    }

    fun play(url: String, startFraction: Double, startSeconds: Double?) = dispatch {
        runCatching {
            val renderers = DefaultRenderersFactory(context)
            if (youtubeMlhls && Build.DEVICE == "BRAVIA_VH22") {
                renderers.setMediaCodecSelector { mime, secure, tunneling ->
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).map { info ->
                        if (info.name.equals("OMX.MTK.VIDEO.DECODER.VP9", ignoreCase = true)) {
                            // This decoder advertises adaptive playback but fails on resolution
                            // changes. Keep its size capabilities, but require codec recreation.
                            Logger.i("AirPlay Media3 HLS: disabling seamless adaptation for ${info.name}")
                            MediaCodecInfo.newInstance(
                                info.name, info.mimeType, info.codecMimeType, info.capabilities,
                                info.hardwareAccelerated, info.softwareOnly, info.vendor,
                                true, info.secure,
                            )
                        } else info
                    }
                }
            }
            val builder = ExoPlayer.Builder(context, renderers).setLooper(main.looper)
            if (!youtubeMlhls) builder.setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    500,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                )
                .build())
            val exo = builder.build()
            player = exo
            UxPlaySubtitles.cues.value = emptyList()
            applySubtitleSelection(exo)
            subtitleScope.launch {
                UxPlaySubtitles.selection.collect {
                    if (!released) applySubtitleSelection(exo)
                }
            }
            if (youtubeMlhls) {
                // Leave quality adaptive unless a sender selection is available.
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                    .clearVideoSizeConstraints()
                    .clearViewportSizeConstraints()
                    .setForceHighestSupportedBitrate(false)
                    .build()
                Logger.i("AirPlay Media3 HLS: adaptive source selection, no forced highest track or resolution cap")
            }
            exo.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime, format: Format,
                    decoderReuseEvaluation: DecoderReuseEvaluation?,
                ) {
                    Logger.i("AirPlay Media3 HLS: input ${format.width}x${format.height} " +
                        "reuse=${decoderReuseEvaluation?.result} discardReasons=${decoderReuseEvaluation?.discardReasons}")
                }

                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime, decoderName: String,
                    initializedTimestampMs: Long, initializationDurationMs: Long,
                ) {
                    Logger.i("AirPlay Media3 HLS: decoder initialized $decoderName")
                }

                override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                    Logger.i("AirPlay Media3 HLS: decoder released $decoderName")
                }

                override fun onLoadError(
                    eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                    error: IOException,
                    wasCanceled: Boolean,
                ) {
                    if (released || wasCanceled) return
                    val status = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    Logger.w("AirPlay Media3 HLS: load error type=${mediaLoadData.dataType} " +
                        "host=${loadEventInfo.uri.host} status=$status error=${error.javaClass.simpleName}")
                }
            })
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (released) return
                    Logger.d("AirPlay Media3 HLS: state=$state bufferedMs=${exo.totalBufferedDuration}")
                    if (state == Player.STATE_READY && !prepared) {
                        prepared = true
                        if (startSeconds == null && startFraction > 0 && exo.duration > 0) {
                            exo.seekTo((startFraction.coerceIn(0.0, 1.0) * exo.duration).toLong())
                        }
                        refresh(exo)
                        onPlaying()
                    } else if (state == Player.STATE_ENDED) {
                        refresh(exo)
                        onEnded()
                    } else {
                        refresh(exo)
                    }
                }

                override fun onTimelineChanged(timeline: Timeline, reason: Int) = refresh(exo)

                override fun onIsPlayingChanged(isPlaying: Boolean) = refresh(exo)

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (released || videoSize.width <= 0 || videoSize.height <= 0) return
                    StreamStats.videoWidth = if (youtubeMlhls) {
                        (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
                    } else videoSize.width
                    StreamStats.videoHeight = videoSize.height
                    Logger.i("AirPlay Media3 HLS: size ${videoSize.width}x${videoSize.height}")
                }

                override fun onTracksChanged(tracks: Tracks) {
                    if (released) return
                    applySubtitleSelection(exo)

                }

                override fun onCues(cueGroup: CueGroup) {
                    if (!released) UxPlaySubtitles.cues.value = cueGroup.cues.toList()
                }

                override fun onRenderedFirstFrame() {
                    if (!released) Logger.i("AirPlay Media3 HLS: rendered first frame")
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!released) {
                        Logger.e("AirPlay Media3 HLS: ${error.errorCodeName}", error)
                        onFailed("Media3 ${error.errorCodeName}")
                    }
                }
            })
            updateSurface(exo)
            val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            exo.setMediaItem(item, ((startSeconds ?: 0.0) * 1000).toLong().coerceAtLeast(0))
            exo.playWhenReady = true
            Logger.i("AirPlay Media3 HLS: preparing")
            if (youtubeMlhls) {
                snapshot = PlaybackInfo(0.0, (startSeconds ?: 0.0).coerceAtLeast(0.0), 0.0, readyToPlay = false)
                main.postDelayed(reportTick, 250L)
            }
            exo.prepare()
        }.onFailure {
            Logger.e("AirPlay Media3 HLS: setup failed", it)
            if (!released) onFailed("Media3 setup failed: ${it.message}")
        }
        Unit
    }

    fun setRate(rate: Float) = dispatch {
        player?.let { exo ->
            if (rate <= 0f || exo.playbackState != Player.STATE_ENDED) {
                if (youtubeMlhls && rate > 0f) exo.playbackParameters = PlaybackParameters(rate.coerceAtLeast(0.1f))
                exo.playWhenReady = rate > 0f
            }
        }
    }

    fun scrub(positionSec: Double) = dispatch {
        player?.let { exo ->
            exo.seekTo((positionSec * 1000).toLong().coerceAtLeast(0))
            refresh(exo)
        }
    }

    fun info(): PlaybackInfo {
        if (!youtubeMlhls) dispatch { player?.let(::refresh) }
        return snapshot
    }

    fun attachSurface() = dispatch { player?.let { updateSurface(it, force = true) } }

    private fun updateSurface(exo: ExoPlayer, force: Boolean = false) {
        val surface = surfaceProvider()?.takeIf { it.isValid }
        if (force || surface !== boundSurface) {
            exo.setVideoSurface(surface)
            boundSurface = surface
            Logger.i("AirPlay Media3 HLS: output surface ${if (surface != null) "attached" else "unavailable"}")
        }
    }

    fun release() {
        released = true
        if (Looper.myLooper() == main.looper) releasePlayer() else main.post { releasePlayer() }
    }

    private fun releasePlayer() {
        subtitleScope.cancel()
        UxPlaySubtitles.cues.value = emptyList()
        main.removeCallbacks(reportTick)
        player?.release()
        player = null
        boundSurface = null
    }

    private fun applySubtitleSelection(exo: ExoPlayer) {
        val selection = UxPlaySubtitles.selection.value
        val candidates = exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
        }
        val chosen = findUxPlaySubtitleTrack(selection, candidates.map { (group, index) ->
            group.getTrackFormat(index).let { UxPlaySubtitleTrack(it.language, it.label) }
        })
        val builder = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, chosen == null)
        if (chosen != null) {
            val (group, index) = candidates[chosen]
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        } else UxPlaySubtitles.cues.value = emptyList()
        val parameters = builder.build()
        if (parameters != exo.trackSelectionParameters) exo.trackSelectionParameters = parameters
    }

    private fun refresh(exo: ExoPlayer) {
        if (released) return
        if (!youtubeMlhls && !prepared && (exo.isCurrentMediaItemLive || exo.duration <= 0)) return
        val ready = if (!youtubeMlhls) true else if (exo.isCurrentMediaItemLive) {
            exo.playbackState == Player.STATE_READY
        } else exo.duration != C.TIME_UNSET
        val duration = exo.duration.coerceAtLeast(0) / 1000.0
        val position = if (exo.playbackState == Player.STATE_ENDED) duration else exo.currentPosition / 1000.0
        val rate = if (youtubeMlhls) {
            if (exo.playWhenReady && exo.playbackState == Player.STATE_READY) exo.playbackParameters.speed.toDouble() else 0.0
        } else if (exo.isPlaying) 1.0 else 0.0
        snapshot = PlaybackInfo(
            duration, position, rate, readyToPlay = ready,
        )
    }

    private fun dispatch(action: () -> Unit) {
        main.post { if (!released) action() }
    }
}
