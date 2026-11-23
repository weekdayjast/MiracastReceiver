package top.jast.miracastreceiver.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import top.jast.miracastreceiver.service.PlaybackSessionManager.Session
import top.jast.miracastreceiver.service.PlaybackSessionManager.Source

/** Process-wide arbitration; protocol discovery remains alive when playback changes. */
object PlaybackSessions {
    const val EXTRA_SESSION = "playback_session"
    private val manager = PlaybackSessionManager()
    private var audio: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            onMain { stop() }
        }
    }

    fun <T> onMain(block: () -> T): T =
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else runBlocking(Dispatchers.Main) { block() }

    fun begin(context: Context, source: Source, transportId: String? = null, stop: () -> Unit): Session? = onMain {
        audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val session = try { manager.begin(source, transportId, stop) } catch (error: Exception) {
            abandonFocus()
            throw error
        }
        if (!focusHeld) {
            val result = if (Build.VERSION.SDK_INT >= 26) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setOnAudioFocusChangeListener(focusListener).build()
                focusRequest = request
                audio!!.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                audio!!.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
            focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (focusHeld) session else {
            manager.end(session)
            abandonFocus()
            null
        }
    }

    fun current(source: Source): Session? = onMain { manager.active?.takeIf { it.source == source } }
    fun isCurrent(id: Long): Boolean = onMain { manager.active?.id == id }
    fun bind(id: Long, stop: () -> Unit): Boolean = onMain {
        manager.active?.takeIf { it.id == id }?.let { manager.bind(it, stop) } ?: false
    }
    fun end(id: Long, disconnect: Boolean = true) = onMain {
        manager.active?.takeIf { it.id == id }?.let { manager.end(it, disconnect) }
        if (manager.active == null) abandonFocus()
    }
    fun stop() = onMain { try { manager.stop() } finally { abandonFocus() } }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio?.abandonAudioFocusRequest(it) }
        else {
            @Suppress("DEPRECATION")
            audio?.abandonAudioFocus(focusListener)
        }
        focusRequest = null
        focusHeld = false
    }
}
