package top.jast.miracastreceiver.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import top.jast.miracastreceiver.R
import timber.log.Timber
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import top.jast.miracastreceiver.castplus.CastPlusReceiver
import kotlinx.coroutines.launch

/**
 * 连接确认页面
 */
class ConnectActivity : AppCompatActivity() {
    private var token: String? = null
    private var receiver: CastPlusReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        val density = resources.displayMetrics.density
        val width = minOf((640 * density).toInt(),
            resources.displayMetrics.widthPixels - (24 * density).toInt()).coerceAtLeast(1)
        window.setLayout(width, WindowManager.LayoutParams.MATCH_PARENT)
        token = intent.getStringExtra(CastPlusReceiver.TOKEN)
        receiver = CastPlusReceiver.current
        if (token == null || receiver?.state?.value?.request?.token != token) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val remember = findViewById<CheckBox>(R.id.cb_remember_device)
        val allow = findViewById<Button>(R.id.btn_allow)
        val deny = findViewById<Button>(R.id.btn_deny)
        val info = findViewById<TextView>(R.id.tv_request_info)
        val pin = findViewById<TextView>(R.id.tv_pairing_pin)
        allow.setOnClickListener { token?.let { receiver?.answer(it, true, remember.isChecked) } }
        deny.setOnClickListener { cancel(); finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { cancel(); finish() }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                receiver!!.state.collect { state ->
                    val request = state.request
                    if (request == null || request.token != token) { finish(); return@collect }
                    info.text = getString(R.string.castplus_request, request.name)
                    allow.visibility = if (request.accepted) View.GONE else View.VISIBLE
                    remember.visibility = allow.visibility
                    pin.text = if (request.accepted && request.pin.isNotEmpty())
                        getString(R.string.castplus_pin, request.pin) else if (request.accepted)
                        getString(R.string.castplus_connecting) else ""
                    pin.visibility = if (pin.text.isNotEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }
    private fun cancel() {
        if (receiver?.state?.value?.request?.token == token) {
            if (receiver?.state?.value?.request?.accepted == true) receiver?.end()
            else token?.let { receiver?.answer(it, false) }
        }
    }
    override fun onDestroy() {
        if (isFinishing) cancel()
        super.onDestroy()
    }
}
