package top.jast.miracastreceiver.dlna

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import top.jast.miracastreceiver.ui.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.TreeMap
import java.util.UUID

/**
 * UPnP HTTP 服务器
 * 处理设备描述、服务描述和 SOAP 控制请求
 */
class UpnpHttpServer(
    private val context: Context,
    private val renderer: DlnaMediaRenderer,
    private val deviceUuid: String,
    @Volatile private var deviceName: String,
    private val manufacturer: String,
    private val modelName: String,
    private val localIp: String,
    private val port: Int = 8080
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private class EventSubscription(val callback: URI, val service: String, var expiresAt: Long) {
        var nextSequence = 0L
    }
    private val subscriptions = mutableMapOf<String, EventSubscription>()

    fun start() {
        if (serverJob?.isActive == true) {
            Timber.w("UPnP HTTP server already running")
            return
        }

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Timber.i("UPnP HTTP server started on port $port")

                while (serverSocket?.isClosed == false) {
                    val client = serverSocket?.accept()
                    if (client != null) {
                        launch {
                            handleClient(client)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "UPnP HTTP server error")
            }
        }
    }

    fun stop() {
        synchronized(subscriptions) { subscriptions.clear() }
        try {
            serverJob?.cancel()
            serverSocket?.close()
            Timber.i("UPnP HTTP server stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping UPnP HTTP server")
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val output = client.getOutputStream()

                val requestLine = reader.readLine() ?: return
                val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
                var contentLength = 0

                var line: String?
                while (true) {
                    line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                    val index = line.indexOf(':')
                    if (index > 0) {
                        val key = line.substring(0, index).trim()
                        val value = line.substring(index + 1).trim()
                        headers[key] = value

                        if (key.equals("Content-Length", ignoreCase = true)) {
                            contentLength = value.toIntOrNull() ?: 0
                        }
                    }
                }

                // 读取 body
                val body = if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    reader.read(buffer, 0, contentLength)
                    String(buffer)
                } else {
                    ""
                }

                val parts = requestLine.split(" ")
                if (parts.size >= 2) {
                    val method = parts[0]
                    val path = parts[1]

                    Timber.d("UPnP $method $path from ${socket.inetAddress.hostAddress}")

                    when {
                        path == "/device.xml" -> sendDeviceDescription(output)
                        path == "/service/ConnectionManager.xml" -> sendConnectionManagerScpd(output)
                        path == "/service/AVTransport.xml" -> sendAvTransportScpd(output)
                        path == "/service/RenderingControl.xml" -> sendRenderingControlScpd(output)
                        path == "/control/AVTransport" -> handleAvTransportControl(output, body, headers)
                        path == "/control/RenderingControl" -> handleRenderingControl(output, body, headers)
                        path == "/control/ConnectionManager" -> handleConnectionManager(output, body, headers)
                        path == "/event/AVTransport" || path == "/event/RenderingControl" ->
                            handleSubscription(client, method, path.substringAfterLast('/'), headers)
                        path.startsWith("/event/") -> handleEvent(output)
                        else -> sendNotFound(output)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error handling UPnP client")
            }
        }
    }

    fun updateDisplayName(value: String) {
        deviceName = value
    }

    private fun sendDeviceDescription(output: OutputStream) {
        val baseUrl = "http://$localIp:$port"
        val xml = DlnaXmlBuilder.buildDeviceDescription(
            deviceUuid, deviceName, manufacturer, modelName, baseUrl
        )
        sendXmlResponse(output, xml)
    }

    private fun sendConnectionManagerScpd(output: OutputStream) {
        sendXmlResponse(output, DlnaXmlBuilder.buildConnectionManagerScpd())
    }

    private fun sendAvTransportScpd(output: OutputStream) {
        sendXmlResponse(output, DlnaXmlBuilder.buildAvTransportScpd())
    }

    private fun sendRenderingControlScpd(output: OutputStream) {
        sendXmlResponse(output, DlnaXmlBuilder.buildRenderingControlScpd())
    }

    private fun handleAvTransportControl(output: OutputStream, body: String, headers: Map<String, String>) {
        val action = soapAction(headers)
        Timber.i("AVTransport action: $action")

        // 调试：打印完整请求体，帮助排查 Emby 的格式
        if (action == "SetAVTransportURI") {
            Timber.d("SetAVTransportURI body (first 1000 chars): ${body.take(1000)}")
        }

        val response = when (action) {
            "SetAVTransportURI" -> {
                val uri = SoapParser.extractTagValue(body, "CurrentURI")
                val metadata = SoapParser.extractTagValue(body, "CurrentURIMetaData")

                // 如果 URI 为空，记录错误并尝试更宽松的解析
                if (uri.isBlank()) {
                    Timber.e("SetAVTransportURI: Empty URI! Trying fallback parsing...")
                    Timber.e("Request body: $body")

                    // 尝试其他可能的标签名
                    val fallbackUri = listOf("URI", "Url", "MediaUri", "CurrentUri").firstNotNullOfOrNull { tag ->
                        val value = SoapParser.extractTagValue(body, tag)
                        if (value.isNotBlank()) value else null
                    } ?: ""

                    if (fallbackUri.isNotBlank()) {
                        Timber.i("Found URI via fallback: $fallbackUri")
                        renderer.setAVTransportURI(fallbackUri, metadata)
                    } else {
                        renderer.setAVTransportURI(uri, metadata)
                    }
                } else {
                    renderer.setAVTransportURI(uri, metadata)
                }
                buildSoapResponse(action, "")
            }
            "SetNextAVTransportURI" -> {
                val uri = SoapParser.extractTagValue(body, "NextURI")
                if (uri.isNotBlank()) renderer.setQualityUri(uri)
                buildSoapResponse(action, "")
            }
            "Play" -> {
                val speed = extractSpeed(body)
                if (speed.isNotBlank()) {
                    renderer.setPlaybackSpeed(speed)
                }
                renderer.play()
                buildSoapResponse(action, "")
            }
            "SetRate", "SetSpeed", "SetPlaybackSpeed", "SetTransportPlaySpeed",
            "X_SetRate", "X_SetSpeed", "X_SetPlaybackSpeed", "X_SetTransportPlaySpeed" -> {
                val speed = extractSpeed(body)
                if (speed.isNotBlank()) {
                    renderer.setPlaybackSpeed(speed)
                    buildSoapResponse(action, "")
                } else {
                    buildSoapFault("402", "Invalid Args")
                }
            }
            "Pause" -> {
                renderer.pause()
                buildSoapResponse(action, "")
            }
            "Stop" -> {
                renderer.stop()
                buildSoapResponse(action, "")
            }
            "Seek" -> {
                val target = SoapParser.extractTagValue(body, "Target")
                renderer.seek(target)
                buildSoapResponse(action, "")
            }
            "GetTransportInfo" -> {
                val state = renderer.getState()
                val content = """
                    <CurrentTransportState>${state.transportState}</CurrentTransportState>
                    <CurrentTransportStatus>OK</CurrentTransportStatus>
                    <CurrentSpeed>${formatSpeed(state.playbackSpeed)}</CurrentSpeed>
                """.trimIndent()
                buildSoapResponse(action, content)
            }
            "GetPositionInfo" -> {
                val state = renderer.getState()
                val content = """
                    <Track>1</Track>
                    <TrackDuration>${SoapParser.formatTime(state.duration)}</TrackDuration>
                    <TrackMetaData>${SoapParser.escapeXml(state.metadata)}</TrackMetaData>
                    <TrackURI>${SoapParser.escapeXml(state.uri)}</TrackURI>
                    <RelTime>${SoapParser.formatTime(state.position)}</RelTime>
                    <AbsTime>${SoapParser.formatTime(state.position)}</AbsTime>
                    <RelCount>2147483647</RelCount>
                    <AbsCount>2147483647</AbsCount>
                """.trimIndent()
                buildSoapResponse(action, content)
            }
            "GetMediaInfo" -> {
                val state = renderer.getState()
                val content = """
                    <NrTracks>1</NrTracks>
                    <MediaDuration>${SoapParser.formatTime(state.duration)}</MediaDuration>
                    <CurrentURI>${SoapParser.escapeXml(state.uri)}</CurrentURI>
                    <CurrentURIMetaData>${SoapParser.escapeXml(state.metadata)}</CurrentURIMetaData>
                    <NextURI></NextURI>
                    <NextURIMetaData></NextURIMetaData>
                    <PlayMedium>NETWORK</PlayMedium>
                    <RecordMedium>NOT_IMPLEMENTED</RecordMedium>
                    <WriteStatus>NOT_IMPLEMENTED</WriteStatus>
                """.trimIndent()
                buildSoapResponse(action, content)
            }
            "GetCurrentTransportActions" -> {
                val content = "<Actions>Play,Pause,Stop,Seek</Actions>"
                buildSoapResponse(action, content)
            }
            "GetTransportSettings" -> {
                val content = """
                    <PlayMode>NORMAL</PlayMode>
                    <RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>
                """.trimIndent()
                buildSoapResponse(action, content)
            }
            "X_GetFeatureList" -> {
                // 一些客户端请求扩展特性
                buildSoapResponse(action, "<FeatureList></FeatureList>")
            }
            else -> buildSoapFault("401", "Invalid Action")
        }

        sendSoapResponse(output, response)
    }

    private fun handleRenderingControl(output: OutputStream, body: String, headers: Map<String, String>) {
        val action = soapAction(headers)
        Timber.i("RenderingControl action: $action")

        val response = when (action) {
            "GetVolume" -> {
                val state = renderer.getState()
                buildSoapResponse(action, "<CurrentVolume>${state.volume}</CurrentVolume>", "RenderingControl")
            }
            "SetVolume" -> {
                val volume = SoapParser.extractTagValue(body, "DesiredVolume").toIntOrNull() ?: 50
                renderer.setVolume(volume)
                buildSoapResponse(action, "", "RenderingControl")
            }
            "GetMute" -> {
                val state = renderer.getState()
                val mute = if (state.muted) "1" else "0"
                buildSoapResponse(action, "<CurrentMute>$mute</CurrentMute>", "RenderingControl")
            }
            "SetMute" -> {
                val mute = SoapParser.extractTagValue(body, "DesiredMute") == "1"
                renderer.setMute(mute)
                buildSoapResponse(action, "", "RenderingControl")
            }
            else -> buildSoapFault("401", "Invalid Action")
        }

        sendSoapResponse(output, response)
    }

    private fun handleConnectionManager(output: OutputStream, body: String, headers: Map<String, String>) {
        val action = soapAction(headers)
        Timber.i("ConnectionManager action: $action")

        val response = when (action) {
            "GetProtocolInfo" -> {
                val content = """
                    <Source></Source>
                    <Sink>http-get:*:image/jpeg:*,http-get:*:image/png:*,http-get:*:video/mp4:*,http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/*:*</Sink>
                """.trimIndent()
                buildSoapResponse(action, content, "ConnectionManager")
            }
            "GetCurrentConnectionIDs" -> {
                buildSoapResponse(action, "<ConnectionIDs>0</ConnectionIDs>", "ConnectionManager")
            }
            "GetCurrentConnectionInfo" -> {
                val content = """
                    <RcsID>0</RcsID>
                    <AVTransportID>0</AVTransportID>
                    <ProtocolInfo></ProtocolInfo>
                    <PeerConnectionManager></PeerConnectionManager>
                    <PeerConnectionID>-1</PeerConnectionID>
                    <Direction>Input</Direction>
                    <Status>OK</Status>
                """.trimIndent()
                buildSoapResponse(action, content, "ConnectionManager")
            }
            else -> buildSoapFault("401", "Invalid Action")
        }

        sendSoapResponse(output, response)
    }

    private fun handleEvent(output: OutputStream) {
        // 简化实现：返回 200 OK
        val response = "HTTP/1.1 200 OK\r\n\r\n"
        output.write(response.toByteArray())
        output.flush()
    }

    private fun soapAction(headers: Map<String, String>): String =
        SoapParser.extractAction(headers.entries.firstOrNull { it.key.equals("SOAPAction", ignoreCase = true) }?.value)

    private fun handleSubscription(client: Socket, method: String, service: String, headers: Map<String, String>) {
        val peer = client.inetAddress.hostAddress
        val sid = headers["SID"]
        val callback = headers["CALLBACK"]?.let { value ->
            Regex("<([^>]+)>").findAll(value).mapNotNull { match ->
                runCatching { URI(match.groupValues[1]) }.getOrNull()
            }.firstOrNull { it.scheme == "http" && it.host == peer && it.userInfo == null && it.fragment == null }
        }
        val timeout = headers["TIMEOUT"]?.substringAfter('-')?.toLongOrNull()?.coerceIn(1, 1800) ?: 1800L
        val now = SystemClock.elapsedRealtime()
        val expiresAt = now + timeout * 1000
        val activeSid = synchronized(subscriptions) {
            subscriptions.entries.removeAll { it.value.expiresAt <= now }
            val existing = subscriptions[sid]?.takeIf { it.callback.host == peer && it.service == service }
            when {
                method == "UNSUBSCRIBE" && existing != null -> sid.also { subscriptions.remove(it) }
                method == "SUBSCRIBE" && existing != null -> sid.also { existing.expiresAt = expiresAt }
                method == "SUBSCRIBE" && sid == null && callback != null &&
                    headers["NT"].equals("upnp:event", true) && subscriptions.size < 128 -> {
                    "uuid:${UUID.randomUUID()}".also { subscriptions[it] = EventSubscription(callback, service, expiresAt) }
                }
                else -> null
            }
        }
        val response = when {
            activeSid == null -> "HTTP/1.1 412 Precondition Failed\r\n"
            method == "UNSUBSCRIBE" -> "HTTP/1.1 200 OK\r\n"
            else -> "HTTP/1.1 200 OK\r\nSID: $activeSid\r\nTIMEOUT: Second-$timeout\r\n"
        }
        client.getOutputStream().apply {
            write((response + "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
            flush()
        }
        client.close()
        if (activeSid != null && sid == null && callback != null) {
            val subscription = synchronized(subscriptions) { subscriptions[activeSid] } ?: return
            synchronized(subscription) { sendEvent(activeSid, subscription) }
        }
    }

    fun notifyAvTransportChanged() {
        val active = synchronized(subscriptions) {
            subscriptions.filterValues { it.service == "AVTransport" && it.expiresAt > SystemClock.elapsedRealtime() }
        }
        for ((sid, subscription) in active) {
            scope.launch {
                synchronized(subscription) {
                    val stillSubscribed = synchronized(subscriptions) {
                        subscriptions[sid] === subscription && subscription.expiresAt > SystemClock.elapsedRealtime()
                    }
                    if (stillSubscribed && subscription.nextSequence != 0L) sendEvent(sid, subscription)
                }
            }
        }
    }

    private fun sendEvent(sid: String, subscription: EventSubscription) {
        val callback = subscription.callback
        val service = subscription.service
        val sequence = subscription.nextSequence
        subscription.nextSequence = if (sequence == 0xFFFF_FFFFL) 1L else sequence + 1
        val state = renderer.getState()
        val (namespace, properties) = if (service == "AVTransport") {
            val uri = SoapParser.escapeXml(SoapParser.escapeXml(state.uri))
            val metadata = SoapParser.escapeXml(SoapParser.escapeXml(state.metadata))
            "AVT" to ("&lt;TransportState val=&quot;${state.transportState.name}&quot;/&gt;" +
                "&lt;TransportStatus val=&quot;OK&quot;/&gt;" +
                "&lt;AVTransportURI val=&quot;$uri&quot;/&gt;" +
                "&lt;AVTransportURIMetaData val=&quot;$metadata&quot;/&gt;" +
                "&lt;CurrentTrackURI val=&quot;$uri&quot;/&gt;")
        } else {
            val mute = if (state.muted) 1 else 0
            "RCS" to ("&lt;Volume channel=&quot;Master&quot; val=&quot;${state.volume}&quot;/&gt;" +
                "&lt;Mute channel=&quot;Master&quot; val=&quot;$mute&quot;/&gt;")
        }
        val lastChange = "&lt;Event xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/$namespace/&quot;&gt;" +
            "&lt;InstanceID val=&quot;0&quot;&gt;$properties&lt;/InstanceID&gt;&lt;/Event&gt;"
        val body = ("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">" +
            "<e:property><LastChange>$lastChange</LastChange></e:property></e:propertyset>").toByteArray()
        val path = callback.rawPath.ifEmpty { "/" } + (callback.rawQuery?.let { "?$it" } ?: "")
        val port = if (callback.port == -1) 80 else callback.port
        val headers = "NOTIFY $path HTTP/1.1\r\nHOST: ${callback.host}:$port\r\n" +
            "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\n" +
            "SID: $sid\r\nSEQ: $sequence\r\nCONTENT-LENGTH: ${body.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(callback.host, port), 2000)
                socket.soTimeout = 2000
                socket.getOutputStream().apply {
                    write(headers.toByteArray())
                    write(body)
                    flush()
                }
                val status = socket.getInputStream().bufferedReader().readLine()
                Timber.d("$service event $sequence to ${callback.host}: $status")
            }
        }.onFailure { Timber.d(it, "$service event $sequence failed for ${callback.host}") }
    }

    private fun buildSoapResponse(action: String, content: String, service: String = "AVTransport"): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:${action}Response xmlns:u="urn:schemas-upnp-org:service:$service:1">
      $content
    </u:${action}Response>
  </s:Body>
</s:Envelope>"""
    }

    private fun buildSoapFault(errorCode: String, errorDescription: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <s:Fault>
      <faultcode>s:Client</faultcode>
      <faultstring>UPnPError</faultstring>
      <detail>
        <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
          <errorCode>$errorCode</errorCode>
          <errorDescription>$errorDescription</errorDescription>
        </UPnPError>
      </detail>
    </s:Fault>
  </s:Body>
</s:Envelope>"""
    }

    private fun sendXmlResponse(output: OutputStream, xml: String) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/xml; charset=utf-8\r\n" +
            "Content-Length: ${xml.toByteArray().size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(header.toByteArray())
        output.write(xml.toByteArray())
        output.flush()
    }

    private fun sendSoapResponse(output: OutputStream, xml: String) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/xml; charset=utf-8\r\n" +
            "EXT:\r\n" +
            "Content-Length: ${xml.toByteArray().size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(header.toByteArray())
        output.write(xml.toByteArray())
        output.flush()
    }

    private fun sendNotFound(output: OutputStream) {
        val response = "HTTP/1.1 404 Not Found\r\n\r\n"
        output.write(response.toByteArray())
        output.flush()
    }

    private fun formatSpeed(speed: Float): String {
        return if (speed == 1f) "1" else speed.toString()
    }

    private fun extractSpeed(body: String): String {
        val tagNames = listOf(
            "Speed",
            "DesiredSpeed",
            "Rate",
            "DesiredRate",
            "PlaybackSpeed",
            "TransportPlaySpeed",
            "CurrentSpeed"
        )
        for (tag in tagNames) {
            val value = SoapParser.extractTagValue(body, tag)
            if (value.isNotBlank()) return normalizeSpeed(value)
        }

        // 兜底：有些客户端会把倍速放在非标准字段或属性里
        val regexes = listOf(
            Regex("(?i)<[^>]*(?:speed|rate)[^>]*>([^<]+)</[^>]+>"),
            Regex("(?i)(?:speed|rate)\\s*=\\s*[\"']?([0-9.]+)"),
            Regex("(?i)([0-9]+(?:\\.[0-9]+)?)\\s*x")
        )
        for (regex in regexes) {
            val value = regex.find(body)?.groupValues?.getOrNull(1)
            if (!value.isNullOrBlank()) return normalizeSpeed(value)
        }
        return ""
    }

    private fun normalizeSpeed(value: String): String {
        return value.trim()
            .removeSuffix("x")
            .removeSuffix("X")
            .toFloatOrNull()
            ?.coerceIn(0.25f, 4f)
            ?.toString()
            ?: ""
    }
}
