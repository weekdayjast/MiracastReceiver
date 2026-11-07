package top.jast.miracastreceiver.castplus

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.core.content.ContextCompat
import com.huawei.castpluskit.*
import top.jast.miracastreceiver.discovery.DeviceInfoProvider
import top.jast.miracastreceiver.ui.CastPlusPlayerActivity
import top.jast.miracastreceiver.ui.ConnectActivity
import top.jast.miracastreceiver.util.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.UUID

/** Main-thread adapter to the unmodified Huawei SDK. SDK callbacks never log PINs or device IDs. */
class CastPlusReceiver(private val context: Context, private val takePlayback: () -> Unit) {
    enum class Status { OFF, UNSUPPORTED, PERMISSION_REQUIRED, STARTING, READY, PAIRING,
        CONNECTING, PLAYING, PAUSED, DISCONNECTING, ERROR }
    data class Request(val token: String, val name: String, val pin: String, val accepted: Boolean = false)
    data class State(val status: Status = Status.OFF, val session: String? = null,
        val name: String = "", val request: Request? = null, val width: Int = 0, val height: Int = 0)
    companion object {
        const val TOKEN = "castplus_session"
        @Volatile var current: CastPlusReceiver? = null
            private set
    }
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = CastPlusPlaybackGate()
    private var epoch = 0
    private var enabled = false
    private var initializing: Job? = null
    private var client: PlayerClient? = null
    private var listener: IEventListener? = null
    private var device: ProjectionDevice? = null
    private var pendingDevice: ProjectionDevice? = null
    private var surface: Surface? = null
    private var closing = false
    private var displayProfile: CastPlusVideoProfile? = null

    /** Updates the SDK's capability cache for the next connection; does not tear down playback. */
    fun updateDisplayOrientation(): Boolean {
        if (!enabled || client == null) return false
        val (width, height) = castPlusDisplaySize(context)
        val profile = castPlusVideoProfile(width, height, AppSettings.getCastPlusRotation(context))
        if (profile == displayProfile) return true
        val capability = HiSightCapability(profile.screenWidth, profile.screenHeight,
            profile.videoWidth, profile.videoHeight).apply {
            videoFps = 30
            videoBitrate = profile.bitrate
            setIsSupportRemoteCtrl(false)
        }
        if (call { client?.setCapability(capability) } != true) {
            Timber.w("Cast+ display capability update rejected")
            return false
        }
        displayProfile = profile
        Timber.i("Cast+ declared screen=%dx%d, video=%dx%d at 30fps, bitrate=%d",
            profile.screenWidth, profile.screenHeight, profile.videoWidth, profile.videoHeight, profile.bitrate)
        return true
    }

    fun start() {
        current = this
        if (enabled && state.value.status !in setOf(Status.ERROR, Status.PERMISSION_REQUIRED)) return
        stop()
        current = this
        enabled = true
        if (Build.VERSION.SDK_INT < 23 || Build.SUPPORTED_ABIS.none { it in setOf("armeabi-v7a", "arm64-v8a") }) {
            mutableState.value = State(Status.UNSUPPORTED); return
        }
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        if (permissions.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
            mutableState.value = State(Status.PERMISSION_REQUIRED); return
        }
        val generation = ++epoch
        mutableState.value = State(Status.STARTING)
        initializing = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    listOf("securec", "HwKeystoreSDK", "HwDeviceAuthSDK", "ConnectAuthNative",
                        "nstackx_util", "nstackx_dmsg", "nstackx_ctrl", "nf_onehop",
                        "near_field_onehop_jni", "jniFtSocketSink", "native_codec_jni")
                        .forEach(System::loadLibrary)
                }
                if (enabled && generation == epoch) bind(generation)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("Cast+ initialization failed", e) }
            catch (e: LinkageError) { fail("Cast+ native dependency failed", e) }
        }
        handler.postDelayed({
            if (generation == epoch && state.value.status == Status.STARTING) fail("Cast+ bind timed out")
        }, 20_000)
    }

    private fun bind(generation: Int) {
        listener = object : IEventListener.Stub() {
            override fun onEvent(event: Event?): Boolean {
                val id = event?.eventId ?: return false
                handler.post { if (enabled && generation == epoch) this@CastPlusReceiver.event(id, null) }
                return true
            }
            override fun onDisplayEvent(id: Int, info: DisplayInfo?): Boolean {
                handler.post { if (enabled && generation == epoch) event(id, info) }
                return true
            }
        }
        client = PlayerClient.getInstance()
        if (call { client!!.registerCallback(listener) } != true || call { client!!.init(context) } != true)
            fail("Cast+ service bind rejected")
    }

    private fun event(id: Int, info: DisplayInfo?) {
        Timber.d("Cast+ event: %d", id)
        when (id) {
            Constant.EVENT_ID_SERVICE_BIND_SUCCESS -> {
                if (!updateDisplayOrientation() ||
                    call { client!!.setDiscoverable(true, DeviceInfo(DeviceInfoProvider(context).getDeviceName(), DeviceInfo.TYPE_TV)) } != true ||
                    call { client!!.setAuthMode(AuthInfo(AuthInfo.AUTH_MODE_GENERIC)) } != true) {
                    fail("Cast+ capability or discovery rejected")
                } else mutableState.value = State(Status.READY)
            }
            Constant.EVENT_ID_SERVICE_BIND_FAILED, Constant.EVENT_ID_BINDER_DIED -> fail("Cast+ service disconnected")
            Constant.EVENT_ID_PIN_CODE_SHOW, Constant.EVENT_ID_CONFIRMATION_SHOW -> request(info)
            Constant.EVENT_ID_PIN_CODE_SHOW_FINISH -> {
                mutableState.value = state.value.copy(request = null)
            }
            Constant.EVENT_ID_CONNECT_REQ -> connected(info)
            Constant.EVENT_ID_PAUSED -> {
                if (gate.token != null && !closing) {
                    gate.paused()
                    mutableState.value = state.value.copy(status = Status.PAUSED)
                    playIfReady()
                }
            }
            Constant.EVENT_ID_CASTING -> if (gate.token != null && !closing)
                mutableState.value = state.value.copy(status = Status.PLAYING)
            Constant.EVENT_ID_DEVICE_DISCONNECTED -> {
                // Do not accept a new session until teardown completes (or the SDK is rebound).
                clearSession()
                updateDisplayOrientation()
                mutableState.value = State(Status.READY)
            }
            Constant.EVENT_ID_RESOLUTION_UPDATE -> if (gate.token != null && info != null) {
                Timber.i("Cast+ negotiated video: %dx%d", info.videoWidth, info.videoHeight)
                mutableState.value = state.value.copy(width = info.videoWidth, height = info.videoHeight)
            }
        }
    }

    private fun request(info: DisplayInfo?) {
        val remote = info?.projectionDevice ?: return
        if (closing || device != null || (pendingDevice != null && pendingDevice?.deviceId != remote.deviceId)) {
            call { client?.setConnectRequestChooseResult(ConnectRequestChoice(Constant.CONNECT_REQ_CHOICE_REJECT, remote)) }
            return
        }
        pendingDevice = remote
        val old = state.value.request
        val request = Request(old?.token ?: UUID.randomUUID().toString(), remote.deviceName.orEmpty(),
            info.pinCode.orEmpty(), old?.accepted ?: false)
        mutableState.value = State(Status.PAIRING, request = request)
        if (old == null) {
            try {
                context.startActivity(Intent(context, ConnectActivity::class.java).apply {
                    putExtra(TOKEN, request.token)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (e: Exception) { fail("Cannot show Cast+ confirmation", e); return }
            handler.postDelayed({ if (state.value.request?.token == request.token) end() }, 60_000)
        }
    }

    fun answer(token: String, allow: Boolean, remember: Boolean = false) {
        val request = state.value.request?.takeIf { it.token == token && !it.accepted } ?: return
        val remote = pendingDevice ?: return
        val choice = if (!allow) Constant.CONNECT_REQ_CHOICE_REJECT else if (remember)
            Constant.CONNECT_REQ_CHOICE_ALWAYS else Constant.CONNECT_REQ_CHOICE_ONCE
        if (call { client?.setConnectRequestChooseResult(ConnectRequestChoice(choice, remote)) } != true) {
            fail("Cast+ confirmation rejected"); return
        }
        if (allow) mutableState.value = state.value.copy(request = request.copy(accepted = true))
        else end()
    }

    private fun connected(info: DisplayInfo?) {
        val remote = info?.projectionDevice ?: return
        if (device?.deviceId == remote.deviceId && gate.token != null) return
        if (closing || device != null) { call { client?.disconnectDevice(remote) }; return }
        device = remote; pendingDevice = null
        val token = UUID.randomUUID().toString()
        gate.begin(token)
        mutableState.value = State(Status.CONNECTING, token, remote.deviceName.orEmpty(),
            width = info.videoWidth, height = info.videoHeight)
        try {
            takePlayback()
            context.startActivity(Intent(context, CastPlusPlayerActivity::class.java).apply {
                putExtra(TOKEN, token)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        } catch (e: Exception) { fail("Cannot open Cast+ player", e) }
        handler.postDelayed({
            if (state.value.session == token && surface == null) fail("Cast+ player did not become ready")
        }, 20_000)
    }

    fun setSurface(token: String, value: Surface?) {
        if (gate.token != token) return
        surface = value?.takeIf { it.isValid }
        gate.surface(token, surface != null)
        if (surface == null) call { device?.let { client?.pause(it) } }
        else playIfReady()
    }
    fun setVisible(token: String, visible: Boolean) {
        if (gate.token != token) return
        gate.visible(token, visible)
        if (!visible) call { device?.let { client?.pause(it) } }
        else playIfReady()
    }
    private fun playIfReady() {
        val target = surface?.takeIf { it.isValid } ?: return
        val remote = device ?: return
        if (!gate.canPlay() || closing) return
        if (call { client?.setHiSightSurface(target) } == true &&
            call { client?.play(TrackControl(remote.deviceId, TrackControl.TRACK_ALL)) } == true) {
            gate.started()
            mutableState.value = state.value.copy(status = Status.PLAYING)
        } else fail("Cast+ player rejected Surface or play")
    }
    fun end(token: String? = gate.token) {
        if (token != null && token != gate.token) return
        val remote = device ?: pendingDevice ?: return
        closing = true
        gate.token?.let(gate::end)
        surface = null
        mutableState.value = State(Status.DISCONNECTING)
        call { client?.disconnectDevice(remote) }
        val generation = epoch
        handler.postDelayed({
            if (closing && enabled && generation == epoch) { stop(); start() }
        }, 5_000)
    }
    private fun clearSession() {
        gate.token?.let(gate::end)
        surface = null; device = null; pendingDevice = null; closing = false
    }
    /** Keep the SDK discovery name aligned with the app's naming setting. */
    fun updateDisplayName(name: String) {
        if (!enabled || state.value.status !in setOf(Status.READY, Status.PAIRING,
                Status.CONNECTING, Status.PLAYING, Status.PAUSED)) return
        if (call { client?.setDiscoverable(true, DeviceInfo(name, DeviceInfo.TYPE_TV)) } != true) {
            Timber.w("Cast+ discovery name update failed")
        }
    }

    fun stop() {
        enabled = false; epoch++; initializing?.cancel(); initializing = null
        handler.removeCallbacksAndMessages(null)
        call { client?.setDiscoverable(false, DeviceInfo(DeviceInfoProvider(context).getDeviceName(), DeviceInfo.TYPE_TV)) }
        call { device?.let { client?.disconnectDevice(it) } }
        call { listener?.let { client?.unregisterCallback(it) } }
        call { client?.deinit() }
        client = null; listener = null
        displayProfile = null
        clearSession()
        mutableState.value = State()
        if (current === this) current = null
    }
    private fun fail(message: String, error: Throwable? = null) {
        Timber.w(error, message)
        stop()
        current = this
        mutableState.value = State(Status.ERROR)
    }
    private inline fun <T> call(block: () -> T): T? = try { block() }
    catch (e: Exception) { Timber.w(e, "Cast+ SDK call failed"); null }
    catch (e: LinkageError) { Timber.w(e, "Cast+ SDK dependency failed"); null }
}
