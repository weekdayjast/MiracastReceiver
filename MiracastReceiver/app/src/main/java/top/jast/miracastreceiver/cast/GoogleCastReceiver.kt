package top.jast.miracastreceiver.cast

import android.content.Context
import top.jast.miracastreceiver.utils.NetworkUtils
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** LAN-only experimental Cast V2 receiver. Lifecycle methods run on the main thread. */
class GoogleCastReceiver(
    private val context: Context,
    private var name: String,
    private val deviceId: String,
    val session: CastSession
) {
    private var scope: CoroutineScope? = null
    private var server: SSLServerSocket? = null
    private var advertiser: CastMdnsAdvertiser? = null
    private var startup: Job? = null
    private var acceptJob: Job? = null
    private var address: String? = null
    private var boundAt = 0L
    private val clients = ConcurrentHashMap<String, SSLSocket>()

    fun start() {
        if (scope != null) return
        val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = activeScope
        activeScope.launch {
            while (isActive) {
                val ip = NetworkUtils.getLocalIpAddress(context)?.takeUnless { it == "127.0.0.1" }
                if (ip != address || (server == null && startup?.isActive != true) ||
                    (server != null && System.currentTimeMillis() - boundAt > 24 * 60 * 60_000L)) {
                    closeTransport()
                    address = ip
                    if (ip != null) bind(activeScope, ip)
                } else if (server != null && advertiser == null) advertise()
                delay(5_000)
            }
        }
    }

    private fun bind(activeScope: CoroutineScope, ip: String) {
        startup = activeScope.launch(Dispatchers.IO) {
            var socket: SSLServerSocket? = null
            var installed = false
            try {
                val identity = CastIdentity()
                socket = identity.sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
                socket.reuseAddress = true
                socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                socket.bind(InetSocketAddress(ip, 8009))
                val bound = socket
                withContext(Dispatchers.Main.immediate) {
                    ensureActive()
                    server = bound
                    installed = true
                    boundAt = System.currentTimeMillis()
                    advertise()
                    Timber.i("Google Cast experimental receiver listening on %s:8009", ip)
                    acceptJob = activeScope.launch(Dispatchers.IO) {
                        try {
                            while (isActive) {
                                val client = bound.accept() as SSLSocket
                                if (clients.size >= 8) { client.close(); continue }
                                val id = UUID.randomUUID().toString()
                                clients[id] = client
                                launch { serve(id, client, identity) }
                            }
                        } catch (e: Exception) {
                            if (isActive) {
                                Timber.w(e, "Google Cast listener stopped")
                                withContext(Dispatchers.Main.immediate) { closeTransport() }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) Timber.w(e, "Google Cast unavailable; retrying in 5s")
            } finally {
                if (!installed) runCatching { socket?.close() }
            }
        }
    }

    private suspend fun serve(id: String, socket: SSLSocket, identity: CastIdentity) = coroutineScope {
        var ticker: Job? = null
        try {
            Timber.i("Cast client connected: %s", socket.inetAddress.hostAddress)
            socket.soTimeout = 30_000
            socket.startHandshake()
            Timber.i("Cast TLS established: %s", socket.inetAddress.hostAddress)
            val output = socket.outputStream
            fun send(message: CastMessage) = synchronized(output) { CastWire.write(output, message) }
            ticker = launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        delay(1_000)
                        synchronized(output) { session.updates(id).forEach { send(it) } }
                    }
                } catch (e: Exception) { runCatching { socket.close() } }
            }
            while (isActive) {
                val message = CastWire.read(socket.inputStream)
                if (message.namespace == CastWire.AUTH) {
                    send(CastMessage(message.destination, message.source, CastWire.AUTH,
                        binary = identity.authenticate(requireNotNull(message.binary))))
                    Timber.i("Cast device-auth response sent")
                } else {
                    try {
                        val payload = runCatching {
                            com.google.gson.JsonParser.parseString(message.text).asJsonObject
                        }.getOrNull()
                        val type = payload?.string("type") ?: ""
                        if (type !in listOf("GET_STATUS", "PING", "PONG")) {
                            // Log protocol shape, never sender URLs, credentials or metadata.
                            Timber.i("Cast request: %s %s, mediaSessionId=%s, contentType=%s",
                                message.namespace, type, payload?.get("mediaSessionId"),
                                payload?.getAsJsonObject("media")?.string("contentType"))
                        }
                        synchronized(output) {
                            // Serialize queued lifecycle notifications before a new
                            // request/reply so an old exit cannot arrive after relaunch.
                            session.drainNotifications(id).forEach { send(it) }
                            session.handle(id, message).forEach {
                                val response = runCatching { com.google.gson.JsonParser.parseString(it.text).asJsonObject }.getOrNull()
                                if (response?.string("type") in listOf("INVALID_REQUEST", "LOAD_FAILED", "LAUNCH_ERROR")) {
                                    Timber.w("Cast request rejected: %s (%s)", response?.string("type"), response?.string("reason"))
                                }
                                send(it)
                            }
                        }
                    } catch (e: IllegalArgumentException) {
                        Timber.d("Invalid Cast request: %s", e.message)
                        val requestId = runCatching {
                            com.google.gson.JsonParser.parseString(message.text).asJsonObject.get("requestId")
                        }.getOrNull()
                        val error = obj("type" to "INVALID_REQUEST", "reason" to "INVALID_PARAMETER")
                        requestId?.let { error.add("requestId", it) }
                        send(CastMessage(message.destination, message.source, message.namespace, error.toString()))
                    }
                }
            }
        } catch (e: Exception) {
            Timber.i("Cast connection closed: %s (%s)", e.javaClass.simpleName, e.message)
        } finally {
            ticker?.cancel()
            runCatching { socket.close() }
            clients.remove(id)
            session.disconnect(id)
        }
    }

    private fun advertise() {
        if (advertiser != null) return
        val ip = address ?: return
        lateinit var registration: CastMdnsAdvertiser
        registration = CastMdnsAdvertiser(context, deviceId, name, ip) {
            scope?.launch {
                if (advertiser === registration) { registration.stop(); advertiser = null }
            }
        }
        advertiser = registration
        registration.start()
    }

    fun updateDisplayName(value: String) {
        name = value
        advertiser?.stop()
        advertiser = null
        if (server != null) advertise()
    }

    private fun closeTransport() {
        startup?.cancel(); startup = null
        advertiser?.stop(); advertiser = null
        runCatching { server?.close() }; server = null
        clients.values.forEach { runCatching { it.close() } }
        acceptJob?.cancel(); acceptJob = null
        clients.clear()
        session.reset()
    }

    fun stop() {
        closeTransport()
        scope?.cancel(); scope = null
        address = null
    }
}
