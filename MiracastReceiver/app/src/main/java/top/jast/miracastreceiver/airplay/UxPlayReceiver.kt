package top.jast.miracastreceiver.airplay

import android.content.Context
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.media.MediaFormat
import top.jast.miracastreceiver.util.Logger
import top.jast.miracastreceiver.util.NetworkUtils
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import io.github.jqssun.airplay.renderer.VideoRenderer
import java.util.concurrent.Executors
import kotlin.math.pow

/** ARM32 AirPlay backend using the JNI library built from android-airplay-server v0.0.36. */
internal class UxPlayReceiver(
    private val context: Context,
    @Volatile private var name: String,
    private val surfaceProvider: () -> Surface?,
    private val onConnected: () -> Unit,
    private val onStopped: () -> Unit,
    private val onAdvertising: (String) -> Unit,
    private val onError: () -> Unit,
) : RaopCallbackHandler {
    private val main = Handler(Looper.getMainLooper())
    private val nativeWorker = Executors.newSingleThreadExecutor()
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    @Volatile private var discoveryGeneration = 0
    private var listeningPort = 0
    private var airplayTxtRecords: Map<String, String> = emptyMap()
    private var raopTxtRecords: Map<String, String> = emptyMap()
    private var raopServicePrefix = ""
    private var multicastLock: WifiManager.MulticastLock? = null
    private var handle = 0L
    private var player: AirPlayVideoPlayer? = null
    private var hlsFallback: AirPlayHlsPlayer? = null
    private var desiredRate = 1f
    private var desiredSeek: Double? = null
    private val mirror = VideoRenderer(context)
    private var mirrorSurface: Surface? = null
    private var mirrorRunning = false
    private var mirrorAudio = false
    @Volatile private var hlsPlaying = false
    private val mirrorFrames = MirrorFrameGate()
    @Volatile private var stopped = false
    @Volatile private var clientVolume = 0f
    private val reportTick = object : Runnable {
        override fun run() {
            if (stopped) return
            if (handle != 0L) (hlsFallback?.info() ?: player?.info())?.let { info ->
                NativeBridge.nativeUpdatePlaybackInfo(
                    handle, info.positionSec.toFloat(), info.durationSec.toFloat(),
                    info.rate.toFloat(), info.readyToPlay,
                )
            }
            main.postDelayed(this, 250L)
        }
    }

    private fun pairingKeyFile(): String {
        val key = context.filesDir.resolve("uxplay.pem")
        val previous = context.filesDir.resolve("uxplay-test.pem")
        if (!key.exists() && previous.isFile) previous.copyTo(key)
        return key.absolutePath
    }

    fun start() = main.post {
        if (stopped) return@post
        mirror.selectDecoders(1920, 1080, 30, false)
        UxPlaySubtitles.clear()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("miracast_uxplay").apply {
            setReferenceCounted(false)
            acquire()
        }
        nativeWorker.execute {
            var created = 0L
            runCatching {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                NativeBridge.nativeSetDefaultStreamValues(
                    audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
                    audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0,
                )
                val address = NetworkUtils.getAirPlayDeviceId(context).split(':')
                    .map { it.toInt(16).toByte() }.toByteArray()
                created = NativeBridge.nativeInit(
                    this, address, name, pairingKeyFile(),
                    false, false,
                )
                check(created != 0L) { "UxPlay native initialization failed" }
                NativeBridge.nativeSetHlsEnabled(created, true)
                NativeBridge.nativeSetFeature(created, NativeBridge.FT_MIRROR, true)
                NativeBridge.nativeSetFeature(created, NativeBridge.FT_AUDIO, true)
                NativeBridge.nativeSetH265Enabled(created, false)
                NativeBridge.nativeSetCodecs(created, true, true)
                NativeBridge.nativeSetDisplaySize(created, 1920, 1080, 30)
                val port = NativeBridge.nativeStart(created, 7000)
                check(port > 0) { "UxPlay failed to bind port 7000" }
                val nativeHandle = created
                val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(created).orEmpty()
                val raopTxt = NativeBridge.nativeGetRaopTxtRecords(created).orEmpty()
                val raopName = NativeBridge.nativeGetRaopServiceName(created) ?: name
                main.post {
                    if (stopped) destroyNative(nativeHandle) else {
                        handle = nativeHandle
                        NativeBridge.nativeServerAudioConfigure(handle, 0, 95, 0, false, false, true, true, false)
                        listeningPort = port
                        airplayTxtRecords = airplayTxt
                        raopTxtRecords = raopTxt
                        raopServicePrefix = raopName.substringBefore('@') + "@"
                        publishDiscovery()
                        main.post(reportTick)
                        Logger.i("UxPlay protocol backend running on port $port")
                    }
                }
            }.onFailure { error ->
                if (created != 0L) {
                    NativeBridge.nativeStop(created)
                    NativeBridge.nativeDestroy(created)
                }
                Logger.e("UxPlay startup failed", error)
                main.post { if (!stopped) { releaseDiscovery(); onError() } }
                nativeWorker.shutdown()
            }
        }
    }

    private fun register(service: String, type: String, portNumber: Int, txt: Map<String, String>) {
        val generation = discoveryGeneration
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Logger.i("UxPlay registered $type as ${info.serviceName}")
                if (type == "_airplay._tcp" && !stopped && generation == discoveryGeneration) onAdvertising(info.serviceName)
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Logger.w("UxPlay registration failed: $type error=$errorCode")
                if (!stopped && generation == discoveryGeneration) onError()
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrations += listener
        nsd.registerService(NsdServiceInfo().apply {
            serviceName = service
            serviceType = type
            port = portNumber
            txt.forEach { (key, value) -> setAttribute(key, value) }
        }, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun publishDiscovery() {
        register(name, "_airplay._tcp", listeningPort, airplayTxtRecords)
        register(raopServicePrefix + name, "_raop._tcp", listeningPort, raopTxtRecords)
    }

    fun updateDisplayName(newName: String) = main.post {
        if (stopped || name == newName) return@post
        name = newName
        if (listeningPort == 0) return@post
        discoveryGeneration++
        registrations.forEach { listener -> runCatching { nsd.unregisterService(listener) } }
        registrations.clear()
        publishDiscovery()
        Logger.i("UxPlay discovery name updated to $newName")
    }

    fun attachSurface() {
        if (Looper.myLooper() == main.looper) updateOutputSurface() else main.post { updateOutputSurface() }
    }

    private fun updateOutputSurface() {
        if (stopped) return
        when {
            hlsFallback != null -> hlsFallback?.attachSurface()
            player != null -> player?.attachSurface()
            else -> attachMirrorSurface()
        }
    }

    private fun attachMirrorSurface() {
        val surface = surfaceProvider()?.takeIf { it.isValid && mirrorRunning && !hlsPlaying }
        if (surface == null) {
            // Before the first window arrives, keep decoding into the renderer's
            // private SurfaceTexture: the sender may send SPS/PPS only once.
            if (mirrorSurface != null || !mirrorRunning || hlsPlaying) {
                mirrorFrames.outputDetached()
                releaseMirrorOutput()
            }
            return
        }
        mirrorFrames.outputAttached()
        if (mirrorSurface !== surface) {
            mirrorSurface?.let { mirror.clearSurface(it) }
            mirrorSurface = surface
            surface?.let { mirror.setSurface(it) }
        }
    }

    private fun releaseMirrorOutput() {
        synchronized(mirror) {
            mirrorSurface?.let { mirror.clearSurface(it) }
            mirrorSurface = null
            // stopSession() stops only MediaCodec; release() also joins the EGL thread
            // and disconnects its window before Miracast can configure the same Surface.
            mirror.release()
        }
    }

    fun stop() = main.post {
        if (stopped) return@post
        stopped = true
        UxPlaySubtitles.clear()
        main.removeCallbacks(reportTick)
        player?.release()
        player = null
        hlsFallback?.release()
        hlsFallback = null
        hlsPlaying = false
        synchronized(mirror) { mirror.release() }
        mirrorSurface = null
        releaseDiscovery()
        val oldHandle = handle
        handle = 0L
        if (oldHandle != 0L) destroyNative(oldHandle)
    }

    private fun releaseDiscovery() {
        discoveryGeneration++
        listeningPort = 0
        registrations.forEach { runCatching { nsd.unregisterService(it) } }
        registrations.clear()
        multicastLock?.release()
        multicastLock = null
    }

    private fun destroyNative(oldHandle: Long) {
        nativeWorker.execute {
            NativeBridge.nativeStop(oldHandle)
            NativeBridge.nativeDestroy(oldHandle)
        }
        nativeWorker.shutdown()
    }

    fun disconnectSender() = main.post {
        if (stopped || handle == 0L) return@post
        stopVideo()
        mirrorRunning = false
        mirrorFrames.sessionChanged(false)
        releaseMirrorOutput()
        mirrorAudio = false
        NativeBridge.nativeServerAudioStop(handle)
        onStopped()
        val currentHandle = handle
        nativeWorker.execute {
            NativeBridge.nativeStop(currentHandle)
            val port = NativeBridge.nativeStart(currentHandle, 7000)
            main.post { if (!stopped && port < 0) onError() }
        }
    }

    private fun stopVideo() {
        UxPlaySubtitles.cues.value = emptyList()
        val wasPlaying = player != null || hlsFallback != null
        player?.release()
        player = null
        hlsFallback?.release()
        hlsFallback = null
        hlsPlaying = false
        if (handle != 0L) NativeBridge.nativeUpdatePlaybackInfo(handle, 0f, -1f, 0f, false)
        if (mirrorRunning && !stopped) {
            attachMirrorSurface()
            if (mirrorAudio && handle != 0L) NativeBridge.nativeServerAudioStart(handle)
        } else if (wasPlaying) onStopped()
    }

    override fun onVideoPlay(location: String, startPositionSeconds: Float) {
        main.post {
            if (stopped) return@post
            hlsPlaying = true
            desiredRate = 1f
            desiredSeek = null
            // EGL must release the display Surface before MediaPlayer can claim it.
            synchronized(mirror) { mirror.release() }
            mirrorSurface?.let { mirror.clearSurface(it) }
            mirrorSurface = null
            if (handle != 0L) NativeBridge.nativeServerAudioStop(handle)
            player?.release()
            player = null
            hlsFallback?.release()
            hlsFallback = null
            onConnected()
            if (location.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                startHlsFallback(location, startPositionSeconds, "HLS with sender-controlled subtitles")
                return@post
            }
            player = AirPlayVideoPlayer(
                surfaceProvider = surfaceProvider,
                onEnded = { onMediaEnded() },
                onFailed = { reason ->
                    Logger.w("UxPlay MediaPlayer failed: $reason")
                    if (reason.contains("extra=-1007") || reason.contains("extra=-1010")) {
                        startHlsFallback(location, startPositionSeconds)
                    } else stopVideo()
                },
                onPlaying = {},
            ).also { it.play(location, 0.0, startPositionSeconds.toDouble()) }
            Logger.i("UxPlay media playback via MediaPlayer at $startPositionSeconds seconds")
        }
    }

    private fun startHlsFallback(location: String, requestedStart: Float, reason: String = "MediaPlayer format error") {
        if (stopped || hlsFallback != null) return
        val position = player?.info()?.positionSec?.takeIf { it > 0.0 }
            ?: desiredSeek ?: requestedStart.toDouble()
        player?.release()
        player = null
        Logger.i("UxPlay: starting Media3 HLS ($reason) at $position seconds")
        // Keep the same UxPlay session and reverse channel alive while changing the local player.
        // Reporting stopped here would make the sender abandon the new main-content item.
        hlsFallback = AirPlayHlsPlayer(
            context, surfaceProvider,
            onEnded = { onMediaEnded() },
            onFailed = { reason -> Logger.w("UxPlay HLS fallback failed: $reason"); stopVideo() },
            onPlaying = {}, youtubeMlhls = true,
        ).also { it.play(location, 0.0, position); it.setRate(desiredRate) }
    }

    private fun onMediaEnded() {
        // An item's EOS (especially an advertisement) is not an AirPlay session stop.
        // Keep publishing position == duration, rate == 0, ready == true so the sender
        // can advance to its next item. /stop or a replacement /play releases this player.
        Logger.i("UxPlay media item completed; keeping final playback info for sender handoff")
    }

    override fun onVideoSubtitle(language: String?, name: String?, enabled: Boolean) {
        main.post {
            if (stopped) return@post
            UxPlaySubtitles.selection.value = UxPlaySubtitleSelection(language, name, enabled)
            if (!enabled) UxPlaySubtitles.cues.value = emptyList()
            Logger.i("UxPlay sender subtitles: enabled=$enabled language=$language")
        }
    }

    override fun onVideoScrub(positionSeconds: Float) {
        main.post {
            desiredSeek = positionSeconds.toDouble()
            hlsFallback?.scrub(positionSeconds.toDouble()) ?: player?.scrub(positionSeconds.toDouble())
        }
    }
    override fun onVideoRate(rate: Float) {
        main.post { desiredRate = rate; hlsFallback?.setRate(rate) ?: player?.setRate(rate) }
    }
    override fun onVideoStop() { main.post { if (!stopped) stopVideo() } }
    override fun onVideoSessionPoll() = Unit
    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(mirror) {
            if (!stopped && mirrorFrames.acceptsFrames && !hlsPlaying) {
                mirror.feedFrame(data, ntpTimeNs, isH265)
            }
        }
    }
    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        synchronized(mirror) {
            if (stopped || w <= 0 || h <= 0) return
            StreamStats.videoWidth = w.toInt()
            StreamStats.videoHeight = h.toInt()
            StreamStats.videoCodec = MediaFormat.MIMETYPE_VIDEO_AVC
            mirror.setResolution(w.toInt(), h.toInt())
            Logger.i("UxPlay mirror size: ${w.toInt()}x${h.toInt()}")
        }
    }
    override fun onMirrorRunning(running: Boolean) {
        mirrorFrames.sessionChanged(running)
        main.post {
            if (stopped) return@post
            mirrorRunning = running
            if (running) {
                UxPlaySubtitles.cues.value = emptyList()
                mirror.startSession()
                if (!hlsPlaying) { onConnected(); attachMirrorSurface() }
            } else {
                releaseMirrorOutput()
                if (!hlsPlaying) onStopped()
            }
            Logger.i("UxPlay mirror running=$running")
        }
    }
    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        main.post {
            if (stopped || handle == 0L) return@post
            mirrorAudio = usingScreen
            if (usingScreen && !hlsPlaying) {
                NativeBridge.nativeServerAudioStart(handle)
                NativeBridge.nativeServerAudioFormat(handle, ct, spf)
            }
        }
    }
    override fun onAudioTeardown() {
        main.post {
            mirrorAudio = false
            if (!stopped && handle != 0L) NativeBridge.nativeServerAudioStop(handle)
        }
    }
    override fun onClientVolume(db: Float) {
        clientVolume = db
        main.post {
            if (!stopped && handle != 0L) NativeBridge.nativeServerAudioSetClientGain(
                handle, if (db <= -144f) 0f else 10.0.pow(db.toDouble() / 20.0).toFloat(),
            )
        }
    }
    override fun onClientVolumeQuery(): Float = clientVolume
    override fun onConnectionInit() = Unit
    override fun onConnectionDestroy() = Unit
    override fun onConnectionReset(reason: Int) = Unit
    override fun onDisplayPin(pin: String) = Unit
    override fun onMetadata(data: ByteArray) = Unit
    override fun onCoverArt(data: ByteArray) = Unit
    override fun onProgress(start: Long, curr: Long, end: Long) = Unit
    override fun onDacpId(dacpId: String, activeRemote: String) = Unit
    fun onLog(message: String) { Logger.d("UxPlay native audio: $message") }
}
