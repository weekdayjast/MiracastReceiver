package top.jast.miracastreceiver.ui

import android.os.Bundle
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import top.jast.miracastreceiver.R
import top.jast.miracastreceiver.castplus.CastPlusReceiver
import top.jast.miracastreceiver.util.applyVideoTextureTransform
import top.jast.miracastreceiver.util.AppSettings
import android.content.Intent
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/** Cast+ owns this Surface; legacy mirrors and ExoPlayer cannot replace its output. */
class CastPlusPlayerActivity : AppCompatActivity(), TextureView.SurfaceTextureListener {
    companion object {
        private const val ROTATE_BUTTON_HIDE_DELAY_MS = 4_000L
        private val ROTATE_REVEAL_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN
        )
    }
    private var token: String? = null
    private var receiver: CastPlusReceiver? = null
    private lateinit var textureView: TextureView
    private var surface: Surface? = null
    private lateinit var root: FrameLayout
    private var width = 0
    private var height = 0
    private var rotationDegrees = 0
    private lateinit var rotateButton: ImageButton
    private var rotateButtonHideJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rotationDegrees = savedInstanceState?.getInt("castplus_rotation") ?: AppSettings.getCastPlusRotation(this)
        token = intent.getStringExtra(CastPlusReceiver.TOKEN)
        receiver = CastPlusReceiver.current
        if (token == null || receiver?.state?.value?.session != token) { finish(); return }
        root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        textureView = TextureView(this).apply { surfaceTextureListener = this@CastPlusPlayerActivity }
        root.addView(textureView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val status = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 12, 24, 12)
            text = getString(R.string.castplus_connecting)
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        layoutInflater.inflate(R.layout.rotate_overlay_button, root, true)
        rotateButton = root.findViewById(R.id.btn_rotate_overlay)
        rotateButton.setOnClickListener {
                rotationDegrees = (rotationDegrees + 90) % 360
                AppSettings.setCastPlusRotation(this@CastPlusPlayerActivity, rotationDegrees)
                val updated = receiver?.updateDisplayOrientation() == true
                Toast.makeText(this@CastPlusPlayerActivity,
                    if (updated) R.string.castplus_resolution_reconnect else R.string.castplus_resolution_failed,
                    Toast.LENGTH_LONG).show()
                fitSurface()
                showRotateButton(focus = false)
        }
        setContentView(root)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = exit()
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                receiver!!.state.collect { state ->
                    if (state.session != token) { finish(); return@collect }
                    width = state.width; height = state.height
                    fitSurface()
                    status.visibility = if (state.status == CastPlusReceiver.Status.PLAYING)
                        android.view.View.GONE else android.view.View.VISIBLE
                }
            }
        }
    }

    private fun showRotateButton(focus: Boolean) {
        if (!::rotateButton.isInitialized) return
        rotateButton.visibility = View.VISIBLE
        if (focus) rotateButton.requestFocus()
        rotateButtonHideJob?.cancel()
        rotateButtonHideJob = lifecycleScope.launch {
            delay(ROTATE_BUTTON_HIDE_DELAY_MS)
            rotateButton.visibility = View.GONE
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) showRotateButton(focus = false)
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::rotateButton.isInitialized && event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in ROTATE_REVEAL_KEYS) {
            val alreadyFocused = rotateButton.visibility == View.VISIBLE && rotateButton.isFocused
            showRotateButton(focus = true)
            if (!alreadyFocused) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun fitSurface() {
        if (width > 0 && height > 0) textureView.surfaceTexture?.setDefaultBufferSize(width, height)
        applyVideoTextureTransform(textureView, width, height, rotationDegrees)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        surface?.release()
        surface = Surface(texture)
        fitSurface()
        token?.let { receiver?.setSurface(it, surface) }
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = fitSurface()
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        token?.let { receiver?.setSurface(it, null) }
        surface?.release()
        surface = null
        return true
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("castplus_rotation", rotationDegrees)
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart()
        if (receiver?.state?.value?.session == token && token != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            receiver?.setVisible(token!!, true)
            showRotateButton(focus = false)
        }
    }
    override fun onStop() {
        rotateButtonHideJob?.cancel()
        if (::rotateButton.isInitialized) rotateButton.visibility = View.GONE
        token?.let { receiver?.setVisible(it, false) }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onDestroy() {
        token?.let { id ->
            receiver?.setSurface(id, null)
            if (isFinishing && receiver?.state?.value?.session == id) receiver?.end(id)
        }
        surface?.release()
        surface = null
        super.onDestroy()
    }
    private fun exit() {
        token?.let { receiver?.end(it) }
        if (AppSettings.isReturnToAppAfterCast(this)) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        } else finishAffinity()
    }
}
