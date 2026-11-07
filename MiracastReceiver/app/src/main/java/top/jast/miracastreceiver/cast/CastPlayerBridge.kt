package top.jast.miracastreceiver.cast

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import top.jast.miracastreceiver.ui.PlayerActivity
import timber.log.Timber

/** Main-thread adapter. Commands arriving before the Activity is ready are queued per load. */
class CastPlayerBridge(private val context: Context) {
    companion object {
        const val ACTION_COMMAND = "top.jast.miracastreceiver.CAST_COMMAND"
        const val ACTION_STATE = "top.jast.miracastreceiver.CAST_STATE"
        const val TOKEN = "cast_token"
        const val COMMAND = "cast_command"
        const val VALUE = "cast_value"
        const val AUTOPLAY = "cast_autoplay"
        const val POSITION = "cast_position"
        const val VOLUME = "cast_volume"
        const val MIME = "cast_mime"
        const val SESSION_ENDED = "cast_session_ended"
    }
    private val handler = Handler(Looper.getMainLooper())
    private var token: String? = null
    private var ready = false
    private var closed = false
    private val pending = ArrayDeque<CastCommand>()
    val session = CastSession(::load, ::command)

    private fun load(request: CastLoad) {
        handler.post {
            if (closed) return@post
            token = request.token; ready = false; pending.clear()
            try {
                context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_SOURCE_TYPE, "CAST")
                    putExtra(PlayerActivity.EXTRA_MEDIA_URI, request.url)
                    putExtra(PlayerActivity.EXTRA_MEDIA_TITLE, request.title)
                    putExtra(TOKEN, request.token)
                    putExtra(AUTOPLAY, request.autoplay)
                    putExtra(POSITION, request.positionMs)
                    putExtra(VOLUME, request.volume)
                    putExtra(MIME, request.contentType)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            } catch (e: Exception) {
                Timber.w(e, "Cannot open Cast player")
                session.update(CastPlaybackState(request.token, "IDLE", idleReason = "ERROR"))
            }
        }
    }

    private fun command(request: CastCommand) {
        handler.post {
            if (closed || request.token != token) return@post
            if (ready) send(request)
            else if (pending.size < 64) pending.addLast(request)
            else {
                pending.clear()
                pending.addLast(CastCommand(request.token, "STOP"))
                session.update(CastPlaybackState(request.token, "IDLE", idleReason = "ERROR"))
            }
        }
    }

    private fun send(request: CastCommand) {
        context.sendBroadcast(Intent(ACTION_COMMAND).setPackage(context.packageName).apply {
            putExtra(TOKEN, request.token); putExtra(COMMAND, request.type); putExtra(VALUE, request.value)
        })
    }

    fun onState(intent: Intent) {
        val id = intent.getStringExtra(TOKEN) ?: return
        if (closed || id != token) return
        session.update(CastPlaybackState(id, intent.getStringExtra("state") ?: "IDLE",
            intent.getLongExtra("position", 0), intent.getLongExtra("duration", 0),
            intent.getStringExtra("idle_reason"), intent.getFloatExtra(VOLUME, 1f)))
        if (intent.getBooleanExtra(SESSION_ENDED, false)) {
            session.endFromPlayer(id)
            token = null; ready = false; pending.clear()
            return
        }
        ready = true
        while (pending.isNotEmpty()) send(pending.removeFirst())
    }

    fun stop() {
        token?.let { send(CastCommand(it, "STOP")) }
        closed = true; pending.clear(); handler.removeCallbacksAndMessages(null)
    }
}
