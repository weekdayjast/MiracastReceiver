package com.weekd.miracastreceiver.airplay

import android.content.Context
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.media.MediaFormat
import com.weekd.miracastreceiver.util.Logger
import com.weekd.miracastreceiver.util.NetworkUtils
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import io.github.jqssun.airplay.renderer.VideoRenderer
import java.util.concurrent.Executors
import kotlin.math.pow

/** ARM32 test backend using the JNI library built from android-airplay-server v0.0.36. */
internal class UxPlayTestReceiver(
    private val context: Context,
    private val name: String,
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
    private var multicastLock: WifiManager.MulticastLock? = null
    private var handle = 0L
    private var player: AirPlayHlsPlayer? = null
    private val mirror = VideoRenderer(context)
    private var mirrorSurface: Surface? = null
    private var mirrorRunning = false
    private var mirrorAudio = false
    @Volatile private var hlsPlaying = false
    @Volatile private var acceptingMirrorFrames = false
    @Volatile private var stopped = false
    @Volatile private var clientVolume = 0f
    private val reportTick = object : Runnable {
        override fun run() {
            if (stopped) return
            if (handle != 0L) player?.info()?.let { info ->
                NativeBridge.nativeUpdatePlaybackInfo(
                    handle, info.positionSec.toFloat(), info.durationSec.toFloat(),
                    info.rate.toFloat(), info.readyToPlay,
                )
            }
            main.postDelayed(this, 250L)
        }
    }

    fun start() = main.post {
        if (stopped) return@post
        mirror.selectDecoders(1920, 1080, 30, false)
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("miracast_uxplay_test").apply {
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
                    this, address, name, context.filesDir.resolve("uxplay-test.pem").absolutePath,
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
                        register(name, "_airplay._tcp", port, airplayTxt)
                        register(raopName, "_raop._tcp", port, raopTxt)
                        main.post(reportTick)
                        Logger.i("UxPlay test protocol backend running on port $port")
                    }
                }
            }.onFailure { error ->
                if (created != 0L) {
                    NativeBridge.nativeStop(created)
                    NativeBridge.nativeDestroy(created)
                }
                Logger.e("UxPlay test startup failed", error)
                main.post { if (!stopped) { releaseDiscovery(); onError() } }
                nativeWorker.shutdown()
            }
        }
    }

    private fun register(service: String, type: String, portNumber: Int, txt: Map<String, String>) {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Logger.i("UxPlay test registered $type as ${info.serviceName}")
                if (type == "_airplay._tcp" && !stopped) onAdvertising(info.serviceName)
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Logger.w("UxPlay test registration failed: $type error=$errorCode")
                if (!stopped) onError()
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

    fun attachSurface() = main.post {
        if (stopped) return@post
        if (player != null) player?.attachSurface() else attachMirrorSurface()
    }

    private fun attachMirrorSurface() {
        val surface = surfaceProvider()?.takeIf { it.isValid }
        if (mirrorSurface !== surface) {
            mirrorSurface?.let { mirror.clearSurface(it) }
            mirrorSurface = surface
            surface?.let { mirror.setSurface(it) }
        }
    }

    fun stop() = main.post {
        if (stopped) return@post
        stopped = true
        main.removeCallbacks(reportTick)
        player?.release()
        player = null
        hlsPlaying = false
        synchronized(mirror) { mirror.release() }
        mirrorSurface = null
        releaseDiscovery()
        val oldHandle = handle
        handle = 0L
        if (oldHandle != 0L) destroyNative(oldHandle)
    }

    private fun releaseDiscovery() {
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
        acceptingMirrorFrames = false
        mirror.stopSession()
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
        val wasPlaying = player != null
        player?.release()
        player = null
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
            // EGL must release the display Surface before Media3 can claim it.
            synchronized(mirror) { mirror.release() }
            mirrorSurface?.let { mirror.clearSurface(it) }
            mirrorSurface = null
            if (handle != 0L) NativeBridge.nativeServerAudioStop(handle)
            player?.release()
            onConnected()
            player = AirPlayHlsPlayer(
                context, surfaceProvider,
                onEnded = { stopVideo() },
                onFailed = { reason -> Logger.w("UxPlay test media failed: $reason"); stopVideo() },
                onPlaying = {}, youtubeMlhls = true,
            ).also { it.play(location, 0.0, startPositionSeconds.toDouble()) }
            Logger.i("UxPlay test media play at $startPositionSeconds seconds")
        }
    }

    override fun onVideoScrub(positionSeconds: Float) { main.post { player?.scrub(positionSeconds.toDouble()) } }
    override fun onVideoRate(rate: Float) { main.post { player?.setRate(rate) } }
    override fun onVideoStop() { main.post { if (!stopped) stopVideo() } }
    override fun onVideoSessionPoll() = Unit
    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(mirror) {
            if (!stopped && acceptingMirrorFrames && !hlsPlaying) mirror.feedFrame(data, ntpTimeNs, isH265)
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
        acceptingMirrorFrames = running
        main.post {
            if (stopped) return@post
            mirrorRunning = running
            if (running) {
                mirror.startSession()
                if (!hlsPlaying) { onConnected(); attachMirrorSurface() }
            } else {
                mirror.stopSession()
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
    override fun onConnectionInit() { Logger.d("UxPlay test sender connection opened") }
    override fun onConnectionDestroy() { Logger.d("UxPlay test sender connection closed") }
    override fun onConnectionReset(reason: Int) { Logger.d("UxPlay test connection reset reason=$reason") }
    override fun onDisplayPin(pin: String) = Unit
    override fun onMetadata(data: ByteArray) = Unit
    override fun onCoverArt(data: ByteArray) = Unit
    override fun onProgress(start: Long, curr: Long, end: Long) = Unit
    override fun onDacpId(dacpId: String, activeRemote: String) = Unit
    fun onLog(message: String) { Logger.d("UxPlay native audio: $message") }
}
