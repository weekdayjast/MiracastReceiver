package top.jast.miracastreceiver.miracast

import android.content.Context
import android.content.Intent
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Wi-Fi Display RTSP 会话处理器（Sink 侧）。
 *
 * 重要：WFD 里 **Source 才是 RTSP 监听方**，Sink 必须主动连到 Source 的 7236 端口。
 * 这一点已对 Windows 11 的 MSMiracastSource 实测确认。连接建立后，双方在同一条 TCP 上
 * 互为客户端和服务端：
 *
 * ```
 * M1  Source → Sink   OPTIONS         本类回 200 + Public
 * M2  Sink   → Source OPTIONS         本类主动发
 * M3  Source → Sink   GET_PARAMETER   本类回能力集（必须含 wfd_client_rtp_ports）
 * M4  Source → Sink   SET_PARAMETER   选定格式 + presentation URL
 * M5  Source → Sink   SET_PARAMETER   wfd_trigger_method: SETUP
 * M6  Sink   → Source SETUP           本类主动发，带 client_port
 * M7  Sink   → Source PLAY            本类主动发，之后 RTP 开始流入
 * ```
 *
 * @param socket 已连接到 Source 的 TCP 连接
 * @param rtpPort 本机用于接收 RTP 的 UDP 端口，会在 M3 和 M6 里告知 Source
 */
class WfdSessionHandler(
    private val context: Context,
    private val socket: Socket,
    private val rtpPort: Int,
    private val customModeProvider: (Boolean) -> WfdCustomVideoMode? = { wfdCustomVideoMode(context, it) }
) {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    private var outCseq = 0                  // 我们主动发起的请求用的 CSeq
    private var sessionId = ""
    private var presentationUrl = ""
    private var playRequested = false
    private var advertisedCustomMode: WfdCustomVideoMode? = null

    var onSessionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStart: ((rtpPort: Int) -> Unit)? = null
    var onStreamStop: (() -> Unit)? = null

    companion object {
        private const val AUDIO_CODECS = "AAC 00000001 00"
    }

    fun handleSession() {
        try {
            Timber.i("WFD session started with source ${socket.inetAddress.hostAddress}")
            while (!socket.isClosed) {
                val msg = readMessage() ?: break
                if (msg.startsWith("RTSP/1.0")) handleResponse(msg) else handleRequest(msg)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error in WFD session")
        } finally {
            Timber.i("WFD session ended")
            onStreamStop?.invoke()
            close()
        }
    }

    // ─── 读取一条完整 RTSP 消息（头部 + 按 Content-Length 读 body）────────────
    private fun readMessage(): String? {
        // 握手失败的 Source 可能一直不关闭 TCP。不能永久占住唯一的会话循环。
        // 正常播放时保留更长时间，允许 Source 按协议发送 keep-alive。
        socket.soTimeout = if (sessionId.isEmpty()) 15_000 else 90_000
        val buf = StringBuilder()
        val one = ByteArray(1)

        // 读到头部结束
        while (!buf.endsWith("\r\n\r\n")) {
            val n = input.read(one)
            if (n <= 0) return null
            buf.append(one[0].toInt().toChar())
            if (buf.length > 64 * 1024) {
                Timber.w("WFD: header too large, dropping session")
                return null
            }
        }

        // 按 Content-Length 读 body（不能用 readLine，长度不足会静默截断）
        val contentLength = Regex("(?i)Content-Length:\\s*(\\d+)")
            .find(buf)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (contentLength > 0) {
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n <= 0) return null
                read += n
            }
            buf.append(String(body, StandardCharsets.UTF_8))
        }

        val msg = buf.toString()
        Timber.d("WFD >> ${msg.lineSequence().first()}")
        Timber.v("WFD >> full:\n$msg")
        return msg
    }

    private fun send(msg: String) {
        Timber.d("WFD << ${msg.lineSequence().first()}")
        Timber.v("WFD << full:\n$msg")
        output.write(msg.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    // ─── 处理 Source 发来的请求 ──────────────────────────────────────────────
    private fun handleRequest(msg: String) {
        val method = msg.substringBefore(' ')
        val cseq = header(msg, "CSeq") ?: "0"

        when (method) {
            "OPTIONS" -> {
                sendOk(cseq, "Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n")
                sendOptions()                                    // M2
            }
            "GET_PARAMETER" -> {
                val requested = msg.substringAfter("\r\n\r\n", "").lineSequence()
                    .map { it.substringBefore(':').trim().lowercase(java.util.Locale.ROOT) }
                    .filter { it.isNotEmpty() }.toSet()
                if (requested.any { it.startsWith("wfd") || it == "microsoft_custom_video_formats" })
                    sendCapabilities(cseq, requested)  // M3
                else sendOk(cseq)                                 // keep-alive
            }
            "SET_PARAMETER" -> {
                // M4 里的 presentation URL 有两个值（"<url> none"），只能取第一个，
                // 否则拼出的 SETUP 请求行会多一段，Source 判定畸形直接断链。
                param(msg, "wfd_presentation_URL")
                    ?.substringBefore(' ')
                    ?.takeIf { it.startsWith("rtsp://") }
                    ?.let {
                        presentationUrl = it
                        Timber.i("WFD: presentation URL = $it")
                    }
                // M4 里 Source 回选的格式，第 5 个字段就是它选中的 CEA 分辨率位
                (param(msg, "wfdx_video_formats") ?: param(msg, "wfd_video_formats"))?.let { selected ->
                    Timber.i("WFD: source selected video format = $selected")
                    val ceaBit = selected.split(' ').getOrNull(4)?.toLongOrNull(16) ?: 0L
                    val mode = when (ceaBit) {
                        0x100L -> "1920x1080p60"
                        0x080L -> "1920x1080p30"
                        0x040L -> "1280x720p60"
                        0x020L -> "1280x720p30"
                        else -> "CEA 0x%08X".format(ceaBit)
                    }
                    Timber.i("WFD: negotiated mode = $mode")
                }
                param(msg, "microsoft_custom_video_formats")?.takeIf { it != "none" }?.let { selected ->
                    val expected = advertisedCustomMode
                    if (expected == null || !matchesWfdCustomMode(selected, expected)) {
                        Timber.w("WFD: source selected an unadvertised or malformed custom mode")
                        send("RTSP/1.0 400 Bad Request\r\nCSeq: $cseq\r\nContent-Length: 0\r\n\r\n")
                        return
                    }
                    Timber.i("WFD: negotiated custom mode = %dx%d p%d", expected.width, expected.height, expected.fps)
                }
                param(msg, "wfd_audio_codecs")?.let {
                    Timber.i("WFD: source selected audio codec = $it")
                }
                sendOk(cseq)
                if (msg.contains("wfd_trigger_method: SETUP")) sendSetup()      // M5 → M6
                if (msg.contains("wfd_trigger_method: TEARDOWN")) close()
            }
            "TEARDOWN" -> {
                sendOk(cseq)
                onStreamStop?.invoke()
                close()
            }
            else -> sendOk(cseq)
        }
    }

    // ─── 处理 Source 对我们请求的响应 ────────────────────────────────────────
    private fun handleResponse(msg: String) {
        val session = header(msg, "Session")?.substringBefore(';')
        if (!session.isNullOrBlank() && sessionId.isEmpty()) {
            sessionId = session
            Timber.i("WFD: session id = $sessionId")
            onSessionEstablished?.invoke(sessionId)
            sendPlay()                                           // M7
        } else if (playRequested) {
            Timber.i("WFD: PLAY acknowledged, RTP should start on $rtpPort")
            onStreamStart?.invoke(rtpPort)
            startPlayerActivity()
            playRequested = false
        }
    }

    // ─── 我们主动发起的请求 ──────────────────────────────────────────────────
    private fun sendOptions() = send(
        "OPTIONS * RTSP/1.0\r\n" +
            "CSeq: ${++outCseq}\r\n" +
            "Require: org.wfa.wfd1.0\r\n\r\n"
    )

    private fun sendSetup() {
        if (presentationUrl.isEmpty()) {
            presentationUrl = "rtsp://${socket.inetAddress.hostAddress}/wfd1.0/streamid=0"
        }
        send(
            "SETUP $presentationUrl RTSP/1.0\r\n" +
                "CSeq: ${++outCseq}\r\n" +
                "Transport: RTP/AVP/UDP;unicast;client_port=$rtpPort\r\n\r\n"
        )
    }

    private fun sendPlay() {
        playRequested = true
        send(
            "PLAY $presentationUrl RTSP/1.0\r\n" +
                "CSeq: ${++outCseq}\r\n" +
                "Session: $sessionId\r\n\r\n"
        )
    }

    // ─── 响应构造 ───────────────────────────────────────────────────────────
    private fun sendOk(cseq: String, extraHeaders: String = "") =
        send("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$extraHeaders\r\n")

    /**
     * M3 能力响应：Source 问什么答什么，不认识的参数直接不答。
     * 实测 Windows 会问 27 项（含 wfd2_* / intel_* / microsoft_* 私有扩展），
     * 标准模式始终保留；仅在 Source 查询扩展时声明硬件支持的自定义分辨率。
     */
    private fun sendCapabilities(cseq: String, requested: Set<String>) {
        val mode = if ("microsoft_custom_video_formats" in requested || "wfdx_video_formats" in requested)
            customModeProvider("wfdx_video_formats" in requested) else null
        if ("microsoft_custom_video_formats" in requested) advertisedCustomMode = mode?.takeIf {
            "wfdx_video_formats" in requested || it.levelBit <= 0x10
        }
        if (mode != null) Timber.i("WFD: custom receive declaration = %dx%d p%d", mode.width, mode.height, mode.fps)
        val body = buildString {
            wfdVideoCapabilityLines(requested, mode).forEach { append(it).append("\r\n") }
            append("wfd_audio_codecs: $AUDIO_CODECS\r\n")
            // 关键：告诉 Source 往哪个 UDP 端口发 RTP，缺了这项收不到画面
            append("wfd_client_rtp_ports: RTP/AVP/UDP;unicast $rtpPort 0 mode=play\r\n")
            append("wfd_content_protection: none\r\n")
            append("wfd_display_edid: none\r\n")
            append("wfd_uibc_capability: none\r\n")
            append("wfd_connector_type: 05\r\n")
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        send(
            "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Content-Type: text/parameters\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n" +
                body
        )
    }

    // ─── 解析辅助 ───────────────────────────────────────────────────────────
    private fun header(msg: String, name: String): String? =
        Regex("(?i)^$name:\\s*(.+)$", RegexOption.MULTILINE)
            .find(msg)?.groupValues?.get(1)?.trim()

    private fun param(msg: String, name: String): String? =
        Regex("(?i)^$name:\\s*(.+)$", RegexOption.MULTILINE)
            .find(msg)?.groupValues?.get(1)?.trim()

    private fun startPlayerActivity() {
        val intent = Intent(context, top.jast.miracastreceiver.ui.PlayerActivity::class.java).apply {
            putExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_SOURCE_TYPE, "MIRACAST")
            putExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_RTP_PORT, rtpPort)
            putExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }

    fun close() {
        try {
            socket.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing WFD session socket")
        }
    }

    fun disconnectSender() {
        try {
            if (sessionId.isNotEmpty()) send(
                "TEARDOWN $presentationUrl RTSP/1.0\r\nCSeq: ${++outCseq}\r\nSession: $sessionId\r\n\r\n"
            )
        } finally {
            close()
        }
    }
}
