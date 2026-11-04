package com.weekd.miracastreceiver.ui

import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.castplus.CastPlusReceiver
import com.weekd.miracastreceiver.util.AppSettings
import android.content.Intent
import kotlinx.coroutines.launch

/** Cast+ owns this Surface; legacy mirrors and ExoPlayer cannot replace its output. */
class CastPlusPlayerActivity : AppCompatActivity(), SurfaceHolder.Callback {
    private var token: String? = null
    private var receiver: CastPlusReceiver? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var root: FrameLayout
    private var width = 0
    private var height = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra(CastPlusReceiver.TOKEN)
        receiver = CastPlusReceiver.current
        if (token == null || receiver?.state?.value?.session != token) { finish(); return }
        root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val status = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 12, 24, 12)
            text = getString(R.string.castplus_connecting)
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addView(Button(this).apply {
            setText(R.string.castplus_exit)
            setOnClickListener { exit() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        setContentView(root)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }
        surfaceView.holder.addCallback(this)
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
    private fun fitSurface() {
        if (width <= 0 || height <= 0 || root.width == 0 || root.height == 0) return
        val scale = minOf(root.width.toFloat() / width, root.height.toFloat() / height)
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        if (surfaceView.layoutParams.width != w || surfaceView.layoutParams.height != h)
            surfaceView.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
    }
    override fun surfaceCreated(holder: SurfaceHolder) { token?.let { receiver?.setSurface(it, holder.surface) } }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        token?.let { receiver?.setSurface(it, holder.surface) }
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { token?.let { receiver?.setSurface(it, null) } }
    override fun onStart() {
        super.onStart()
        if (receiver?.state?.value?.session == token && token != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            receiver?.setVisible(token!!, true)
        }
    }
    override fun onStop() {
        token?.let { receiver?.setVisible(it, false) }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onDestroy() {
        token?.let { id ->
            receiver?.setSurface(id, null)
            if (isFinishing && receiver?.state?.value?.session == id) receiver?.end(id)
        }
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
