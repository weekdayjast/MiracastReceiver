package com.weekd.miracastreceiver.miracast

import android.content.Context
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Wi-Fi Display (Miracast) RTSP 服务器
 * 实现 Wi-Fi Display 协议栈
 */
class WfdServer(
    private val context: Context,
    private val port: Int = 7236  // WFD 标准 RTSP 端口
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sessionHandler: WfdSessionHandler? = null

    // 回调接口
    var onConnectionRequested: ((clientName: String, clientAddress: String) -> Unit)? = null
    var onConnectionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStarted: ((rtpPort: Int) -> Unit)? = null
    var onStreamStopped: (() -> Unit)? = null

    fun start() {
        if (isRunning) {
            Timber.w("WFD Server already running")
            return
        }

        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true
                Timber.i("WFD RTSP Server started on port $port")

                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        if (clientSocket != null) {
                            Timber.i("WFD client connected: ${clientSocket.inetAddress.hostAddress}")
                            handleClient(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Timber.e(e, "Error accepting WFD client")
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start WFD Server")
                isRunning = false
            }
        }
    }

    private fun handleClient(socket: Socket) {
        scope.launch {
            try {
                sessionHandler = WfdSessionHandler(context, socket).apply {
                    onConnectionRequest = { clientName, clientAddr ->
                        onConnectionRequested?.invoke(clientName, clientAddr)
                    }
                    onConnectionEstablish = { sessionId ->
                        onConnectionEstablished?.invoke(sessionId)
                    }
                    onStreamStart = { rtpPort ->
                        onStreamStarted?.invoke(rtpPort)
                    }
                    onStreamStop = {
                        onStreamStopped?.invoke()
                    }
                }
                sessionHandler?.handleSession()
            } catch (e: Exception) {
                Timber.e(e, "Error handling WFD client session")
            }
        }
    }

    fun stop() {
        isRunning = false
        sessionHandler?.close()
        scope.cancel()
        try {
            serverSocket?.close()
            serverSocket = null
            Timber.i("WFD Server stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping WFD Server")
        }
    }

    fun isRunning(): Boolean = isRunning
}
