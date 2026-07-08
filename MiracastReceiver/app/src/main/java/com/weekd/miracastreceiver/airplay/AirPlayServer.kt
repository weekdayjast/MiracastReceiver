package com.weekd.miracastreceiver.airplay

import android.content.Context
import android.content.Intent
import com.weekd.miracastreceiver.ui.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * AirPlay HTTP 服务端
 *
 * 注意：这是 AirPlay 协议的初步兼容实现，只用于让 iPhone 能够发现并尝试连接。
 * 完整屏幕镜像还需要实现：
 * 1. FairPlay/AirPlay 认证握手
 * 2. RTSP 会话控制
 * 3. H.264 视频流解密/解码
 * 4. AAC/ALAC 音频流处理
 */
class AirPlayServer(private val context: Context) {

    companion object {
        const val DEFAULT_PORT = 7000
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var mirroringReceiver: AirPlayMirroringReceiver? = null
    private var currentSession: String? = null

    fun start(port: Int = DEFAULT_PORT) {
        if (serverJob?.isActive == true) {
            Timber.w("AirPlay server already running")
            return
        }

        // 初始化镜像接收器
        mirroringReceiver = AirPlayMirroringReceiver(context)

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Timber.i("AirPlay server started on port $port")

                while (serverSocket?.isClosed == false) {
                    val client = serverSocket?.accept()
                    if (client != null) {
                        launch {
                            handleClient(client)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "AirPlay server error")
            }
        }
    }

    fun stop() {
        try {
            serverJob?.cancel()
            serverSocket?.close()
            mirroringReceiver?.stop()
            mirroringReceiver = null
            currentSession = null
            Timber.i("AirPlay server stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping AirPlay server")
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val output = client.getOutputStream()

                val requestLine = reader.readLine() ?: return
                Timber.i("AirPlay request: $requestLine")

                val headers = mutableMapOf<String, String>()
                var line: String?
                while (true) {
                    line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                    val index = line.indexOf(':')
                    if (index > 0) {
                        headers[line.substring(0, index).trim()] = line.substring(index + 1).trim()
                    }
                }
                Timber.d("AirPlay headers: $headers")

                val isRtsp = requestLine.contains("RTSP/1.0", ignoreCase = true)
                val cseq = headers["CSeq"] ?: headers["Cseq"] ?: headers["cseq"]

                when {
                    requestLine.startsWith("GET /server-info") -> {
                        if (isRtsp) sendRtspServerInfo(output, cseq) else sendServerInfo(output)
                    }
                    requestLine.startsWith("GET /info") -> {
                        if (isRtsp) sendRtspInfo(output, cseq) else sendInfo(output)
                    }
                    requestLine.startsWith("POST /pair-setup") -> {
                        if (isRtsp) sendRtspBinaryOk(output, cseq) else sendPairSetup(output)
                    }
                    requestLine.startsWith("POST /pair-verify") -> {
                        if (isRtsp) sendRtspBinaryOk(output, cseq) else sendPairVerify(output)
                    }
                    requestLine.startsWith("POST /fp-setup") -> {
                        if (isRtsp) sendRtspBinaryOk(output, cseq) else sendFpSetup(output)
                    }
                    requestLine.startsWith("POST /stream") -> {
                        if (isRtsp) sendRtspOk(output, cseq) else sendOk(output)
                    }
                    requestLine.startsWith("POST /play") -> {
                        handleAirPlayPlay(headers, reader)
                        if (isRtsp) sendRtspOk(output, cseq) else sendOk(output)
                    }
                    requestLine.startsWith("POST /stop") -> {
                        context.sendBroadcast(Intent(PlayerActivity.ACTION_STOP).setPackage(context.packageName))
                        if (isRtsp) sendRtspOk(output, cseq) else sendOk(output)
                    }
                    requestLine.startsWith("POST /scrub") -> {
                        handleAirPlayScrub(headers, reader)
                        if (isRtsp) sendRtspOk(output, cseq) else sendOk(output)
                    }
                    requestLine.startsWith("GET /scrub") -> {
                        if (isRtsp) sendRtspOk(output, cseq) else sendHttpResponse(output, "200 OK", "text/parameters", "duration: 0\r\nposition: 0\r\n".toByteArray())
                    }
                    requestLine.startsWith("POST /rate") -> {
                        handleAirPlayRate(requestLine)
                        if (isRtsp) sendRtspOk(output, cseq) else sendOk(output)
                    }
                    requestLine.startsWith("POST /reverse") -> sendReverse(output)
                    requestLine.startsWith("POST /feedback") -> {
                        if (isRtsp) sendRtspOk(output, cseq) else sendOk(output)
                    }
                    requestLine.startsWith("POST /command") -> {
                        if (isRtsp) sendRtspOk(output, cseq) else sendOk(output)
                    }
                    requestLine.startsWith("SETUP") -> {
                        handleRtspSetup(headers)
                        sendRtspSetup(output, cseq)
                    }
                    requestLine.startsWith("TEARDOWN") -> {
                        handleRtspTeardown()
                        sendRtspOk(output, cseq)
                    }
                    requestLine.startsWith("GET_PARAMETER") -> sendRtspOk(output, cseq)
                    requestLine.startsWith("SET_PARAMETER") -> {
                        handleRtspSetParameter(headers, reader)
                        sendRtspOk(output, cseq)
                    }
                    requestLine.startsWith("RECORD") -> {
                        handleRtspRecord()
                        sendRtspOk(output, cseq)
                    }
                    requestLine.startsWith("FLUSH") -> {
                        handleRtspFlush()
                        sendRtspOk(output, cseq)
                    }
                    requestLine.startsWith("OPTIONS") -> sendRtspOptions(output, cseq)
                    else -> {
                        Timber.w("Unsupported AirPlay request: $requestLine")
                        if (isRtsp) sendRtspOk(output, cseq) else sendNotFound(output)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error handling AirPlay client")
            }
        }
    }

    private fun sendServerInfo(output: OutputStream) {
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>deviceid</key><string>00:11:22:33:44:55</string>
                <key>features</key><integer>119019395</integer>
                <key>model</key><string>AppleTV6,2</string>
                <key>protovers</key><string>1.1</string>
                <key>srcvers</key><string>366.0</string>
            </dict>
            </plist>
        """.trimIndent()
        sendHttpResponse(output, "200 OK", "text/x-apple-plist+xml", body.toByteArray())
    }

    private fun sendRtspServerInfo(output: OutputStream, cseq: String?) {
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>deviceid</key><string>A6:9F:ED:FA:68:AE</string>
                <key>features</key><integer>0x5A7FFFF7,0x1E</integer>
                <key>model</key><string>AppleTV6,2</string>
                <key>protovers</key><string>1.1</string>
                <key>srcvers</key><string>366.0</string>
                <key>pi</key><string>A6:9F:ED:FA:68:AE</string>
            </dict>
            </plist>
        """.trimIndent()
        sendRtspResponse(output, "200 OK", cseq, "text/x-apple-plist+xml", body.toByteArray())
    }

    private fun sendInfo(output: OutputStream) {
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>features</key><integer>0x5A7FFFF7,0x1E</integer>
                <key>model</key><string>AppleTV6,2</string>
                <key>name</key><string>Miracast Receiver</string>
                <key>sourceVersion</key><string>366.0</string>
            </dict>
            </plist>
        """.trimIndent()
        sendHttpResponse(output, "200 OK", "text/x-apple-plist+xml", body.toByteArray())
    }

    private fun sendRtspInfo(output: OutputStream, cseq: String?) {
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>deviceid</key><string>A6:9F:ED:FA:68:AE</string>
                <key>features</key><integer>1518337783</integer>
                <key>model</key><string>AppleTV6,2</string>
                <key>name</key><string>Miracast Receiver</string>
                <key>pi</key><string>A6:9F:ED:FA:68:AE</string>
                <key>pk</key><string>0000000000000000000000000000000000000000000000000000000000000000</string>
                <key>sourceVersion</key><string>366.0</string>
                <key>statusFlags</key><integer>68</integer>
                <key>vv</key><integer>2</integer>
                <key>audioFormats</key>
                <array>
                    <dict>
                        <key>type</key><integer>96</integer>
                        <key>audioInputFormats</key><integer>16777216</integer>
                        <key>audioOutputFormats</key><integer>16777216</integer>
                    </dict>
                </array>
                <key>displays</key>
                <array>
                    <dict>
                        <key>uuid</key><string>e0ff8a27-6738-3d56-8a16-cc53aacee925</string>
                        <key>width</key><integer>1920</integer>
                        <key>height</key><integer>1080</integer>
                        <key>widthPhysical</key><integer>0</integer>
                        <key>heightPhysical</key><integer>0</integer>
                        <key>widthPixels</key><integer>1920</integer>
                        <key>heightPixels</key><integer>1080</integer>
                        <key>refreshRate</key><real>60.0</real>
                        <key>rotation</key><integer>0</integer>
                        <key>overscanned</key><false/>
                    </dict>
                </array>
            </dict>
            </plist>
        """.trimIndent()
        Timber.i("Sending /info response with ${body.length} bytes")
        sendRtspResponse(output, "200 OK", cseq, "text/x-apple-plist+xml", body.toByteArray())
    }

    private fun sendPairSetup(output: OutputStream) {
        // 占位响应：完整 AirPlay 需要 SRP/Curve25519 认证
        sendHttpResponse(output, "200 OK", "application/octet-stream", ByteArray(0))
    }

    private fun sendRtspBinaryOk(output: OutputStream, cseq: String?) {
        sendRtspResponse(output, "200 OK", cseq, "application/octet-stream", ByteArray(0))
    }

    private fun sendPairVerify(output: OutputStream) {
        // 占位响应：完整 AirPlay 需要 pair verify
        sendHttpResponse(output, "200 OK", "application/octet-stream", ByteArray(0))
    }

    private fun sendFpSetup(output: OutputStream) {
        // 占位响应：完整 AirPlay 镜像需要 FairPlay setup
        sendHttpResponse(output, "200 OK", "application/octet-stream", ByteArray(0))
    }

    private fun sendReverse(output: OutputStream) {
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: PTTH/1.0\r\n" +
            "Connection: Upgrade\r\n" +
            "\r\n"
        output.write(response.toByteArray())
        output.flush()
    }

    private fun sendOk(output: OutputStream) {
        sendHttpResponse(output, "200 OK", "text/plain", ByteArray(0))
    }

    private fun sendNotFound(output: OutputStream) {
        sendHttpResponse(output, "404 Not Found", "text/plain", "Not Found".toByteArray())
    }

    private fun sendRtspOptions(output: OutputStream, cseq: String?) {
        val response = "RTSP/1.0 200 OK\r\n" +
            (cseq?.let { "CSeq: $it\r\n" } ?: "") +
            "Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER\r\n" +
            "Server: AirTunes/366.0\r\n" +
            "\r\n"
        output.write(response.toByteArray())
        output.flush()
    }

    private fun sendRtspSetup(output: OutputStream, cseq: String?) {
        val response = "RTSP/1.0 200 OK\r\n" +
            (cseq?.let { "CSeq: $it\r\n" } ?: "") +
            "Transport: RTP/AVP/UDP;unicast;mode=record;server_port=6000;control_port=6001;timing_port=6002\r\n" +
            "Session: 1\r\n" +
            "Audio-Jack-Status: connected\r\n" +
            "Audio-Latency: 0\r\n" +
            "Server: AirTunes/366.0\r\n" +
            "\r\n"
        output.write(response.toByteArray())
        output.flush()
        Timber.i("RTSP SETUP response sent")
    }

    private fun sendRtspOk(output: OutputStream, cseq: String?) {
        val response = "RTSP/1.0 200 OK\r\n" +
            (cseq?.let { "CSeq: $it\r\n" } ?: "") +
            "Server: AirTunes/366.0\r\n" +
            "\r\n"
        output.write(response.toByteArray())
        output.flush()
    }

    private fun sendRtspResponse(output: OutputStream, status: String, cseq: String?, contentType: String, body: ByteArray) {
        val header = "RTSP/1.0 $status\r\n" +
            (cseq?.let { "CSeq: $it\r\n" } ?: "") +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Server: AirTunes/366.0\r\n" +
            "\r\n"
        output.write(header.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun sendHttpResponse(output: OutputStream, status: String, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(header.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun handleAirPlayPlay(headers: Map<String, String>, reader: BufferedReader) {
        try {
            val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
            if (contentLength <= 0) return

            val body = CharArray(contentLength)
            reader.read(body, 0, contentLength)
            val bodyStr = String(body)
            Timber.d("AirPlay /play body: $bodyStr")

            // 解析 Content-Location 或 body 中的 URL
            val mediaUrl = headers["Content-Location"]
                ?: headers["content-location"]
                ?: extractUrlFromPlist(bodyStr)

            if (!mediaUrl.isNullOrBlank()) {
                val intent = Intent(context, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_MEDIA_URI, mediaUrl)
                    putExtra(PlayerActivity.EXTRA_MEDIA_TITLE, "AirPlay 投屏")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(intent)
                Timber.i("AirPlay play: $mediaUrl")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling AirPlay play")
        }
    }

    private fun handleAirPlayScrub(headers: Map<String, String>, reader: BufferedReader) {
        try {
            val position = headers["position"]?.toFloatOrNull()
            if (position != null) {
                val intent = Intent(PlayerActivity.ACTION_SEEK).apply {
                    putExtra(PlayerActivity.EXTRA_SEEK_POSITION, (position * 1000).toLong())
                    setPackage(context.packageName)
                }
                context.sendBroadcast(intent)
                Timber.i("AirPlay scrub to: $position")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling AirPlay scrub")
        }
    }

    private fun handleAirPlayRate(requestLine: String) {
        try {
            val rate = requestLine.substringAfter("?rate=").substringBefore(" ").toFloatOrNull() ?: 1f
            val action = if (rate > 0) PlayerActivity.ACTION_PLAY else PlayerActivity.ACTION_PAUSE
            context.sendBroadcast(Intent(action).setPackage(context.packageName))
            Timber.i("AirPlay rate: $rate")
        } catch (e: Exception) {
            Timber.e(e, "Error handling AirPlay rate")
        }
    }

    private fun extractUrlFromPlist(plist: String): String? {
        return try {
            val contentLocPattern = Regex("<key>Content-Location</key>\\s*<string>([^<]+)</string>")
            contentLocPattern.find(plist)?.groupValues?.get(1)
                ?: Regex("<key>Start-Position</key>").find(plist)?.let { null }
        } catch (e: Exception) {
            Timber.e(e, "Error extracting URL from plist")
            null
        }
    }

    /**
     * 处理 RTSP SETUP 请求
     */
    private fun handleRtspSetup(headers: Map<String, String>) {
        try {
            currentSession = "AIRPLAY-${System.currentTimeMillis()}"

            // 解析 Transport 头
            val transport = headers["Transport"] ?: headers["transport"]
            Timber.i("AirPlay SETUP - Transport: $transport")

            // 启动镜像接收器
            mirroringReceiver?.start()

        } catch (e: Exception) {
            Timber.e(e, "Error handling RTSP SETUP")
        }
    }

    /**
     * 处理 RTSP RECORD 请求（开始镜像）
     */
    private fun handleRtspRecord() {
        try {
            Timber.i("AirPlay RECORD - Starting mirroring")

            // 启动 PlayerActivity 以显示镜像画面
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_MEDIA_TITLE, "iPhone 屏幕镜像")
                putExtra(PlayerActivity.EXTRA_IS_AIRPLAY_MIRROR, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            context.startActivity(intent)

        } catch (e: Exception) {
            Timber.e(e, "Error handling RTSP RECORD")
        }
    }

    /**
     * 处理 RTSP TEARDOWN 请求
     */
    private fun handleRtspTeardown() {
        try {
            Timber.i("AirPlay TEARDOWN - Stopping mirroring")

            mirroringReceiver?.stop()
            currentSession = null

            // 停止播放
            context.sendBroadcast(Intent(PlayerActivity.ACTION_STOP).setPackage(context.packageName))

        } catch (e: Exception) {
            Timber.e(e, "Error handling RTSP TEARDOWN")
        }
    }

    /**
     * 处理 RTSP FLUSH 请求
     */
    private fun handleRtspFlush() {
        try {
            Timber.i("AirPlay FLUSH - Flushing buffers")
            // 简化版：暂不处理缓冲区刷新
        } catch (e: Exception) {
            Timber.e(e, "Error handling RTSP FLUSH")
        }
    }

    /**
     * 处理 RTSP SET_PARAMETER 请求
     */
    private fun handleRtspSetParameter(headers: Map<String, String>, reader: BufferedReader) {
        try {
            val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
            if (contentLength > 0) {
                val body = CharArray(contentLength)
                reader.read(body, 0, contentLength)
                val bodyStr = String(body)
                Timber.d("AirPlay SET_PARAMETER body: $bodyStr")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling RTSP SET_PARAMETER")
        }
    }

    /**
     * 获取镜像接收器（供 PlayerActivity 使用）
     */
    fun getMirroringReceiver(): AirPlayMirroringReceiver? = mirroringReceiver
}
