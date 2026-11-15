package top.jast.miracastreceiver.miracast

import android.content.Context
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Wi-Fi Display (Miracast) 会话发起器。
 *
 * 注意这里**不是** RTSP 服务器 —— WFD 规范里 Source 才是监听方。实测（Windows 11
 * MSMiracastSource）确认：Source 接入 P2P 组后会在自己的 7236 端口等待，Sink 必须
 * 主动连过去，否则双方互等到超时（约 60 秒）后断开。
 *
 * 因此本类的职责是：
 *   1. 在 P2P 网段里找出正在监听 7236 的 Source
 *   2. 连过去，交给 [WfdSessionHandler] 跑 M1–M7
 *   3. 会话期间用 [RtpReceiver] 收 RTP（MPEG-2 TS）
 *
 * 前置条件：Source 能发现本机，依赖 wpa_supplicant 广播 WFD IE，这需要 root 注入
 * （见 [WfdRootHelper]）—— 应用自身调 setWFDInfo 会被系统拒绝。
 *
 * @param port Source 的 RTSP 监听端口（WFD 标准 7236）
 * @param rtpPort 本机接收 RTP 的 UDP 端口
 */
class WfdServer(
    private val context: Context,
    private val port: Int = 7236,
    private val rtpPort: Int = 19000
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isRunning = false
    @Volatile private var reconnectAfterMs = 0L

    private var sessionHandler: WfdSessionHandler? = null
    private var rtpReceiver: RtpReceiver? = null

    // 与旧实现保持同样的回调签名，服务层无需改动
    var onConnectionRequested: ((clientName: String, clientAddress: String) -> Unit)? = null
    var onConnectionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStarted: ((rtpPort: Int) -> Unit)? = null
    var onStreamStopped: (() -> Unit)? = null

    companion object {
        private const val SCAN_INTERVAL_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 400
        private const val SCAN_CHUNK = 32          // 每批并发探测的地址数
    }

    fun start() {
        if (isRunning) {
            Timber.w("WFD session starter already running")
            return
        }
        isRunning = true
        Timber.i("WFD session starter running (will dial source:$port, RTP on $rtpPort)")

        scope.launch {
            while (isRunning) {
                try {
                    if (android.os.SystemClock.elapsedRealtime() < reconnectAfterMs) {
                        delay(SCAN_INTERVAL_MS)
                        continue
                    }
                    val socket = dialSource()
                    if (socket != null) {
                        runSession(socket)
                    }
                } catch (e: Exception) {
                    if (isRunning) Timber.e(e, "WFD session loop error")
                }
                if (isRunning) delay(SCAN_INTERVAL_MS)
            }
        }
    }

    /**
     * 在 P2P 网段里找到 Source 并**保持**那条连接。
     *
     * 关键：不能「探测后关闭再重连」—— Source 的监听队列只接受一次连接，探测连接会被它
     * 当成 Sink 正式接入，随后 listener 就关闭了；等我们再连时必然 ECONNREFUSED，
     * 而 Source 那边看到会话刚建立就断开，直接报"连接失败"。
     * 所以扫描成功的那条 socket 直接用作会话连接。
     */
    private suspend fun dialSource(): Socket? = coroutineScope {
        val prefix = p2pSubnetPrefix() ?: return@coroutineScope null

        for (chunkStart in 2..254 step SCAN_CHUNK) {
            if (!isRunning) return@coroutineScope null
            val range = chunkStart until minOf(chunkStart + SCAN_CHUNK, 255)
            val connected = range.map { host ->
                async { tryConnect("$prefix$host") }
            }.awaitAll().filterNotNull()

            if (connected.isNotEmpty()) {
                val session = connected.first()
                connected.drop(1).forEach { runCatching { it.close() } }
                Timber.i("WFD: connected to source at ${session.inetAddress.hostAddress}:$port")
                return@coroutineScope session
            }
        }
        null
    }

    private fun tryConnect(ip: String): Socket? = try {
        Socket().also { it.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS) }
    } catch (e: Exception) {
        null
    }

    /**
     * 取本机 P2P 接口的 /24 前缀（通常是 "192.168.49."）。
     * 组主地址本身要排除掉，剩下的就是 Source 可能拿到的地址。
     */
    private fun p2pSubnetPrefix(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .firstOrNull { it.name.startsWith("p2p") && it.isUp }
            ?.inetAddresses?.toList()
            ?.firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
            ?.substringBeforeLast('.')
            ?.plus(".")
    } catch (e: Exception) {
        Timber.e(e, "Failed to resolve P2P subnet")
        null
    }

    /** 在已连接的 socket 上跑完整个会话；结束后返回，外层循环继续等待下一次投屏。 */
    private fun runSession(socket: Socket) {
        val sourceIp = socket.inetAddress.hostAddress ?: "unknown"
        onConnectionRequested?.invoke("Windows PC", sourceIp)

        // RTP 接收必须先起来：M3 会把端口告诉 Source，M7 之后随时可能来包。
        // 送显目标复用镜像专用 SurfaceView（与 AirPlay 镜像同一块），
        // 播放页尚未创建时返回 null，解码器会等它就绪。
        val receiver = RtpReceiver(rtpPort, { top.jast.miracastreceiver.ui.PlayerActivity.miracastSurface }).apply {
            onError = { Timber.e("Miracast RTP error: $it") }
            start()
        }
        rtpReceiver = receiver

        val handler = WfdSessionHandler(context, socket, rtpPort).apply {
            onSessionEstablished = { onConnectionEstablished?.invoke(it) }
            onStreamStart = { onStreamStarted?.invoke(it) }
            onStreamStop = { onStreamStopped?.invoke() }
        }
        sessionHandler = handler

        try {
            handler.handleSession()          // 阻塞直到会话结束
        } finally {
            receiver.stop()
            rtpReceiver = null
            sessionHandler = null
        }
    }

    fun stop() {
        isRunning = false
        sessionHandler?.close()
        sessionHandler = null
        rtpReceiver?.stop()
        rtpReceiver = null
        scope.cancel()
        Timber.i("WFD session starter stopped")
    }

    fun isRunning(): Boolean = isRunning

    fun disconnectSender() {
        reconnectAfterMs = android.os.SystemClock.elapsedRealtime() + 5000
        val current = sessionHandler
        scope.launch { runCatching { current?.disconnectSender() } }
    }
}
