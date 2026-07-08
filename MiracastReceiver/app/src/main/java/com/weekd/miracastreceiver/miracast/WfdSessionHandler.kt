package com.weekd.miracastreceiver.miracast

import android.content.Context
import android.content.Intent
import timber.log.Timber
import java.io.*
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Wi-Fi Display RTSP 会话处理器
 * 处理 RTSP 请求和响应
 */
class WfdSessionHandler(
    private val context: Context,
    private val socket: Socket
) {
    private val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
    private val output = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))

    private var cseq = 0
    private var sessionId: String = generateSessionId()
    private var rtpPort: Int = 0

    // WFD 参数
    private val wfdVideoFormats = "00 00 02 02 00000040 00000000 00000000 00 0000 0000 00 none none"
    private val wfdAudioFormats = "LPCM 00000003 00, AAC 00000001 00"
    private val wfdContentProtection = "none"
    private val wfd3dVideoFormats = "none"
    private val wfdCoupledSink = "none"
    private val wfdDisplayEdid = "none"

    // 回调接口
    var onConnectionRequest: ((clientName: String, clientAddress: String) -> Unit)? = null
    var onConnectionEstablish: ((sessionId: String) -> Unit)? = null
    var onStreamStart: ((rtpPort: Int) -> Unit)? = null
    var onStreamStop: (() -> Unit)? = null

    suspend fun handleSession() {
        try {
            while (socket.isConnected && !socket.isClosed) {
                val request = readRtspRequest() ?: break
                handleRtspRequest(request)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error in WFD session")
        } finally {
            close()
        }
    }

    private fun readRtspRequest(): RtspRequest? {
        try {
            val requestLine = input.readLine() ?: return null
            if (requestLine.isEmpty()) return null

            val parts = requestLine.split(" ")
            if (parts.size < 3) return null

            val method = parts[0]
            val uri = parts[1]
            val version = parts[2]

            val headers = mutableMapOf<String, String>()
            var line = input.readLine()
            while (line != null && line.isNotEmpty()) {
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    headers[key] = value
                }
                line = input.readLine()
            }

            // 读取消息体
            var body: String? = null
            val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
            if (contentLength > 0) {
                val buffer = CharArray(contentLength)
                input.read(buffer, 0, contentLength)
                body = String(buffer)
            }

            cseq = headers["CSeq"]?.toIntOrNull() ?: 0

            Timber.d("RTSP Request: $method $uri")

            return RtspRequest(method, uri, version, headers, body)
        } catch (e: Exception) {
            Timber.e(e, "Error reading RTSP request")
            return null
        }
    }

    private fun handleRtspRequest(request: RtspRequest) {
        when (request.method) {
            "OPTIONS" -> handleOptions(request)
            "GET_PARAMETER" -> handleGetParameter(request)
            "SET_PARAMETER" -> handleSetParameter(request)
            "SETUP" -> handleSetup(request)
            "PLAY" -> handlePlay(request)
            "PAUSE" -> handlePause(request)
            "TEARDOWN" -> handleTeardown(request)
            else -> sendResponse(501, "Not Implemented")
        }
    }

    private fun handleOptions(request: RtspRequest) {
        val response = buildResponse(200, "OK") {
            append("Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, TEARDOWN, PAUSE\r\n")
        }
        sendRawResponse(response)
    }

    private fun handleGetParameter(request: RtspRequest) {
        val body = request.body
        if (body.isNullOrEmpty()) {
            // Keep-alive
            sendResponse(200, "OK")
            return
        }

        val responseBody = StringBuilder()

        body.lines().forEach { param ->
            val paramName = param.trim()
            when (paramName) {
                "wfd_video_formats" -> responseBody.append("wfd_video_formats: $wfdVideoFormats\r\n")
                "wfd_audio_codecs" -> responseBody.append("wfd_audio_codecs: $wfdAudioFormats\r\n")
                "wfd_content_protection" -> responseBody.append("wfd_content_protection: $wfdContentProtection\r\n")
                "wfd_3d_video_formats" -> responseBody.append("wfd_3d_video_formats: $wfd3dVideoFormats\r\n")
                "wfd_coupled_sink" -> responseBody.append("wfd_coupled_sink: $wfdCoupledSink\r\n")
                "wfd_display_edid" -> responseBody.append("wfd_display_edid: $wfdDisplayEdid\r\n")
                "wfd_client_rtp_ports" -> {
                    // 客户端提供的 RTP 端口
                }
            }
        }

        val response = buildResponse(200, "OK", responseBody.toString())
        sendRawResponse(response)
    }

    private fun handleSetParameter(request: RtspRequest) {
        val body = request.body ?: ""

        // 解析 WFD 参数
        body.lines().forEach { line ->
            when {
                line.startsWith("wfd_trigger_method:") -> {
                    val trigger = line.substringAfter(":").trim()
                    if (trigger == "SETUP") {
                        Timber.i("WFD: Trigger SETUP received")
                        val clientAddr = socket.inetAddress.hostAddress ?: "Unknown"
                        onConnectionRequest?.invoke("Windows PC", clientAddr)
                    }
                }
                line.startsWith("wfd_presentation_URL:") -> {
                    val urls = line.substringAfter(":").trim()
                    Timber.i("WFD: Presentation URLs: $urls")
                }
                line.startsWith("wfd_client_rtp_ports:") -> {
                    val ports = line.substringAfter(":").trim()
                    // 解析格式: RTP/AVP/UDP;unicast 1028 0 mode=play
                    val portMatch = Regex("(\\d+)").find(ports)
                    rtpPort = portMatch?.value?.toIntOrNull() ?: 0
                    Timber.i("WFD: Client RTP port: $rtpPort")
                }
            }
        }

        sendResponse(200, "OK")
    }

    private fun handleSetup(request: RtspRequest) {
        // 提取 Transport 信息
        val transport = request.headers["Transport"] ?: ""
        Timber.i("WFD SETUP: Transport=$transport")

        // 响应 Transport
        val responseTransport = "$transport;server_port=$rtpPort"

        val response = buildResponse(200, "OK") {
            append("Transport: $responseTransport\r\n")
            append("Session: $sessionId\r\n")
        }
        sendRawResponse(response)

        onConnectionEstablish?.invoke(sessionId)
    }

    private fun handlePlay(request: RtspRequest) {
        Timber.i("WFD PLAY: Starting stream")

        val response = buildResponse(200, "OK") {
            append("Session: $sessionId\r\n")
        }
        sendRawResponse(response)

        // 启动播放器接收 RTP 流
        onStreamStart?.invoke(rtpPort)
        startPlayerActivity()
    }

    private fun handlePause(request: RtspRequest) {
        Timber.i("WFD PAUSE: Pausing stream")

        val response = buildResponse(200, "OK") {
            append("Session: $sessionId\r\n")
        }
        sendRawResponse(response)

        // 发送暂停广播
        sendPlayerBroadcast("PAUSE")
    }

    private fun handleTeardown(request: RtspRequest) {
        Timber.i("WFD TEARDOWN: Stopping stream")

        sendResponse(200, "OK")

        onStreamStop?.invoke()
        sendPlayerBroadcast("STOP")
        close()
    }

    private fun startPlayerActivity() {
        val intent = Intent(context, com.weekd.miracastreceiver.ui.PlayerActivity::class.java).apply {
            putExtra("SOURCE_TYPE", "MIRACAST")
            putExtra("RTP_PORT", rtpPort)
            putExtra("SESSION_ID", sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        context.startActivity(intent)
    }

    private fun sendPlayerBroadcast(action: String) {
        val broadcastAction = when (action) {
            "PLAY" -> com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_PLAY
            "PAUSE" -> com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_PAUSE
            "STOP" -> com.weekd.miracastreceiver.ui.PlayerActivity.ACTION_STOP
            else -> return
        }

        val intent = Intent(broadcastAction).apply {
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }

    private fun buildResponse(statusCode: Int, statusText: String, body: String? = null, extraHeaders: (StringBuilder.() -> Unit)? = null): String {
        val response = StringBuilder()
        response.append("RTSP/1.0 $statusCode $statusText\r\n")
        response.append("CSeq: $cseq\r\n")

        extraHeaders?.invoke(response)

        if (body != null) {
            val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
            response.append("Content-Type: text/parameters\r\n")
            response.append("Content-Length: ${bodyBytes.size}\r\n")
            response.append("\r\n")
            response.append(body)
        } else {
            response.append("\r\n")
        }

        return response.toString()
    }

    private fun sendResponse(statusCode: Int, statusText: String) {
        val response = buildResponse(statusCode, statusText)
        sendRawResponse(response)
    }

    private fun sendRawResponse(response: String) {
        try {
            output.write(response)
            output.flush()
            Timber.v("RTSP Response sent: ${response.lines().first()}")
        } catch (e: Exception) {
            Timber.e(e, "Error sending RTSP response")
        }
    }

    fun close() {
        try {
            input.close()
            output.close()
            socket.close()
            Timber.d("WFD session closed")
        } catch (e: Exception) {
            Timber.e(e, "Error closing WFD session")
        }
    }

    private fun generateSessionId(): String {
        return System.currentTimeMillis().toString(36)
    }

    data class RtspRequest(
        val method: String,
        val uri: String,
        val version: String,
        val headers: Map<String, String>,
        val body: String?
    )
}
