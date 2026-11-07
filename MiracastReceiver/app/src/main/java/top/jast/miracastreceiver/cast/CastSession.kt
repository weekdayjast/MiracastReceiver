package top.jast.miracastreceiver.cast

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.util.UUID

data class CastLoad(val token: String, val url: String, val title: String, val contentType: String,
    val positionMs: Long, val autoplay: Boolean, val volume: Float)
data class CastCommand(val token: String, val type: String, val value: Double = 0.0)
data class CastPlaybackState(val token: String, val state: String, val positionMs: Long = 0,
    val durationMs: Long = 0, val idleReason: String? = null, val volume: Float = 1f)

/** Shared session state, independent of Android. All entry points serialize on this object. */
class CastSession(private val load: (CastLoad) -> Unit, private val command: (CastCommand) -> Unit) {
    companion object { const val DEFAULT_APP = "CC1AD845" }
    private var transport: String? = null
    private var token: String? = null
    private var media: JsonObject? = null
    private var mediaId = 0
    private var volume = 1f
    private var muted = false
    private var pendingVolume: Float? = null
    private var snapshot: CastPlaybackState? = null
    private val bindings = mutableMapOf<String, MutableSet<Pair<String, String>>>()
    private val subscriptions = mutableMapOf<String, MutableSet<String>>()
    private val notifications = mutableMapOf<String, MutableList<CastMessage>>()

    @Synchronized fun handle(client: String, message: CastMessage): List<CastMessage> {
        val payload = JsonParser.parseString(requireNotNull(message.text)).asJsonObject
        val type = payload.string("type")
        val rid = payload.get("requestId")
        fun reply(body: JsonObject, source: String = message.destination): List<CastMessage> {
            if (rid != null) body.add("requestId", rid)
            return listOf(CastMessage(source, message.source, message.namespace, body.toString()))
        }
        fun invalid(reason: String, kind: String = "INVALID_REQUEST") = reply(obj("type" to kind, "reason" to reason))
        when (message.namespace) {
            CastWire.HEARTBEAT -> return if (type == "PING") reply(obj("type" to "PONG")) else emptyList()
            CastWire.CONNECTION -> {
                val pair = message.source to message.destination
                if (type == "CONNECT") {
                    if (message.destination != "receiver-0" && message.destination != transport) {
                        return reply(obj("type" to "CLOSE", "reason" to 1))
                    }
                    bindings.getOrPut(client) { mutableSetOf() }.add(pair)
                } else if (type == "CLOSE") {
                    bindings[client]?.remove(pair)
                    subscriptions[client]?.remove(message.source)
                    stopIfUnbound(message.destination)
                }
                return emptyList()
            }
            CastWire.RECEIVER -> {
                if (message.destination != "receiver-0") return invalid("INVALID_DESTINATION")
                when (type) {
                    "GET_STATUS" -> Unit
                    "GET_APP_AVAILABILITY" -> {
                        val ids = when {
                            payload.get("appId")?.isJsonArray == true -> payload.getAsJsonArray("appId")
                            payload.get("appIds")?.isJsonArray == true -> payload.getAsJsonArray("appIds")
                            payload.has("appId") -> JsonArray().apply { add(payload.get("appId")) }
                            else -> JsonArray()
                        }
                        val availability = JsonObject()
                        ids.forEach { availability.addProperty(it.asString,
                            if (it.asString == DEFAULT_APP) "APP_AVAILABLE" else "APP_UNAVAILABLE") }
                        return reply(obj("type" to "RECEIVER_ACTION", "availability" to availability))
                    }
                    "LAUNCH" -> {
                        if (payload.string("appId") != DEFAULT_APP) return invalid("NOT_FOUND", "LAUNCH_ERROR")
                        if (transport == null) transport = UUID.randomUUID().toString()
                    }
                    "STOP" -> reset()
                    "SET_VOLUME" -> setVolume(payload.getAsJsonObject("volume") ?: return invalid("INVALID_VOLUME"))
                    else -> return invalid("UNSUPPORTED_COMMAND")
                }
                return reply(receiverStatus(), "receiver-0")
            }
            CastWire.MEDIA -> {
                val active = transport ?: return invalid("NO_ACTIVE_SESSION")
                if (message.destination != active || bindings[client]?.contains(message.source to active) != true)
                    return invalid("INVALID_SESSION")
                subscriptions.getOrPut(client) { mutableSetOf() }.add(message.source)
                if (type != "LOAD" && type != "GET_STATUS" &&
                    (media == null || payload.long("mediaSessionId", -1) != mediaId.toLong()))
                    return invalid("INVALID_MEDIA_SESSION_ID")
                when (type) {
                    "GET_STATUS" -> Unit
                    "LOAD" -> {
                        val info = payload.getAsJsonObject("media") ?: return invalid("INVALID_MEDIA", "LOAD_FAILED")
                        val url = info.string("contentId")
                        val uri = runCatching { URI(url) }.getOrNull()
                        if (uri?.scheme?.lowercase() !in listOf("http", "https") || uri?.host.isNullOrEmpty())
                            return invalid("INVALID_URL", "LOAD_FAILED")
                        if (info.has("customData") && info.get("customData").isJsonNull) info.remove("customData")
                        val contentType = info.string("contentType", "video/mp4")
                        if (contentType.startsWith("image/") || contentType.contains("dash"))
                            return invalid("UNSUPPORTED_MEDIA_TYPE", "LOAD_FAILED")
                        val seconds = payload.double("currentTime", 0.0)
                        if (!seconds.isFinite() || seconds < 0 || seconds > Long.MAX_VALUE / 1000.0)
                            return invalid("INVALID_TIME", "LOAD_FAILED")
                        mediaId++
                        token = UUID.randomUUID().toString()
                        media = info.deepCopy()
                        snapshot = CastPlaybackState(token!!, "BUFFERING", (seconds * 1000).toLong())
                        load(CastLoad(token!!, url, info.getAsJsonObject("metadata")?.string("title", "Google Cast")
                            ?: "Google Cast", contentType, (seconds * 1000).toLong(),
                            payload.boolean("autoplay", true), effectiveVolume()))
                    }
                    "PLAY", "PAUSE" -> token?.let { command(CastCommand(it, type)) }
                    "SEEK" -> {
                        val seconds = payload.double("currentTime", -1.0)
                        if (!seconds.isFinite() || seconds < 0 || seconds > Long.MAX_VALUE / 1000.0)
                            return invalid("INVALID_TIME")
                        token?.let { command(CastCommand(it, "SEEK", seconds * 1000)) }
                        when (payload.string("resumeState")) {
                            "PLAYBACK_START" -> token?.let { command(CastCommand(it, "PLAY")) }
                            "PLAYBACK_PAUSE" -> token?.let { command(CastCommand(it, "PAUSE")) }
                        }
                    }
                    "STOP" -> {
                        token?.let { command(CastCommand(it, "STOP")) }
                        snapshot = token?.let { CastPlaybackState(it, "IDLE", idleReason = "CANCELLED") }
                    }
                    "SET_VOLUME" -> setVolume(payload.getAsJsonObject("volume") ?: return invalid("INVALID_VOLUME"))
                    else -> return invalid("UNSUPPORTED_COMMAND")
                }
                val response = mediaStatus()
                payload.get("sequenceNumber")?.let { response.add("sequenceNumber", it) }
                return reply(response, active)
            }
        }
        return emptyList()
    }

    private fun effectiveVolume() = if (muted) 0f else volume

    private fun setVolume(value: JsonObject) {
        value.get("level")?.asDouble?.let { require(it.isFinite()); volume = it.coerceIn(0.0, 1.0).toFloat() }
        value.get("muted")?.asBoolean?.let { muted = it }
        pendingVolume = effectiveVolume()
        token?.let { command(CastCommand(it, "VOLUME", effectiveVolume().toDouble())) }
    }

    @Synchronized fun update(state: CastPlaybackState) {
        if (state.token == token) {
            snapshot = state
            // Ignore a delayed volume callback from before the latest mute/volume
            // command; otherwise unmuting can overwrite the remembered level with 0.
            val pending = pendingVolume
            if (pending == null || kotlin.math.abs(pending - state.volume) < 0.001f) {
                pendingVolume = null
                if (!muted) volume = state.volume.coerceIn(0f, 1f)
            }
        }
    }

    @Synchronized fun updates(client: String): List<CastMessage> {
        val pending = drainNotifications(client)
        val active = transport ?: return pending
        if (media == null) return pending
        return pending + subscriptions[client].orEmpty().filter { bindings[client]?.contains(it to active) == true }.map {
            CastMessage(active, it, CastWire.MEDIA, mediaStatus().toString())
        }
    }

    @Synchronized fun drainNotifications(client: String): List<CastMessage> =
        notifications.remove(client)?.toList() ?: emptyList()

    /** The user closed the receiver player, rather than a sender stopping one media item. */
    @Synchronized fun endFromPlayer(playerToken: String) {
        if (playerToken != token) return
        val previousTransport = transport ?: return
        val recipients = bindings.mapValues { (_, pairs) -> pairs.toSet() }
        snapshot = snapshot?.let {
            if (it.state == "IDLE") it else it.copy(state = "IDLE", idleReason = "CANCELLED")
        }
        val finalMedia = mediaStatus().toString()
        // Do not enqueue another STOP: the Activity is already exiting, and a late
        // command must never close a newer player opened by another protocol.
        clearSession()
        val finalReceiver = receiverStatus().toString()
        recipients.forEach { (client, pairs) ->
            val messages = notifications.getOrPut(client) { mutableListOf() }
            pairs.filter { it.second == previousTransport }.map { it.first }.distinct().forEach {
                messages.add(CastMessage(previousTransport, it, CastWire.MEDIA, finalMedia))
            }
            // RECEIVER_STATUS must follow the final media status. VLC interprets
            // an empty applications list as Stopped; a later media status could
            // incorrectly put it back into Ready/Playing.
            pairs.filter { it.second == "receiver-0" || it.second == previousTransport }
                .map { it.first }.distinct().forEach {
                    messages.add(CastMessage("receiver-0", it, CastWire.RECEIVER, finalReceiver))
                }
        }
    }

    @Synchronized fun disconnect(client: String) {
        val destinations = bindings.remove(client).orEmpty().map { it.second }
        subscriptions.remove(client)
        notifications.remove(client)
        destinations.forEach { stopIfUnbound(it) }
    }

    private fun stopIfUnbound(destination: String) {
        if (destination == transport && bindings.values.none { pairs -> pairs.any { it.second == destination } }) reset()
    }

    @Synchronized fun reset() {
        token?.let { command(CastCommand(it, "STOP")) }
        clearSession()
        notifications.clear()
    }

    private fun clearSession() {
        transport = null; token = null; media = null; snapshot = null
        pendingVolume = null
        bindings.clear(); subscriptions.clear()
    }

    private fun receiverStatus(): JsonObject {
        val apps = JsonArray()
        transport?.let { apps.add(obj("appId" to DEFAULT_APP, "displayName" to "Default Media Receiver",
            "isIdleScreen" to false, "sessionId" to it, "transportId" to it,
            "statusText" to "Ready to cast", "namespaces" to JsonArray().apply { add(obj("name" to CastWire.MEDIA)) })) }
        return obj("type" to "RECEIVER_STATUS", "status" to obj("applications" to apps,
            "volume" to volumeStatus(), "isActiveInput" to true, "isStandBy" to false))
    }

    private fun volumeStatus() = obj("level" to volume, "muted" to muted,
        "controlType" to "attenuation", "stepInterval" to 0.05)

    private fun mediaStatus(): JsonObject {
        val statuses = JsonArray()
        val state = snapshot
        if (media != null && state != null) {
            val info = media!!.deepCopy().apply { addProperty("duration", state.durationMs / 1000.0) }
            statuses.add(obj("mediaSessionId" to mediaId, "playerState" to state.state,
                "currentTime" to state.positionMs / 1000.0, "playbackRate" to 1,
                "supportedMediaCommands" to 15, "volume" to volumeStatus(), "media" to info).apply {
                state.idleReason?.let { addProperty("idleReason", it) }
            })
        }
        return obj("type" to "MEDIA_STATUS", "status" to statuses)
    }
}

internal fun obj(vararg fields: Pair<String, Any?>) = JsonObject().apply {
    fields.forEach { (key, value) -> when (value) {
        is JsonElement -> add(key, value)
        is Number -> addProperty(key, value)
        is Boolean -> addProperty(key, value)
        null -> Unit
        else -> addProperty(key, value.toString())
    } }
}
internal fun JsonObject.string(key: String, default: String = "") = get(key)?.takeUnless { it.isJsonNull }?.asString ?: default
internal fun JsonObject.long(key: String, default: Long) = get(key)?.asLong ?: default
internal fun JsonObject.double(key: String, default: Double) = get(key)?.asDouble ?: default
// VLC 3.x sends the string "false". Cast's autoplay is a JSON boolean;
// missing or incorrectly typed values use the protocol default (true).
internal fun JsonObject.boolean(key: String, default: Boolean): Boolean {
    val value = get(key)
    return if (value?.isJsonPrimitive == true && value.asJsonPrimitive.isBoolean) value.asBoolean else default
}
