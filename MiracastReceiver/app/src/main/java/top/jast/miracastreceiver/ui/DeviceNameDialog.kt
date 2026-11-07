package top.jast.miracastreceiver.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.EncodeHintType
import top.jast.miracastreceiver.R
import top.jast.miracastreceiver.discovery.DeviceInfoProvider
import top.jast.miracastreceiver.settings.NameSetupPage
import top.jast.miracastreceiver.settings.NameSetupServer
import top.jast.miracastreceiver.util.AppSettings
import top.jast.miracastreceiver.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Both remote-control and phone flows write the same persistent setting. */
class DeviceNameDialog(private val activity: AppCompatActivity) {
    private var dialog: AlertDialog? = null
    private var server: NameSetupServer? = null
    private fun string(id: Int) = activity.getString(id)
    private fun presets() = activity.resources.getStringArray(R.array.receiver_name_presets)

    private fun save(name: String, onSaved: () -> Unit = {}) {
        activity.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { AppSettings.setReceiverName(activity, name) }
            Toast.makeText(activity, if (saved) R.string.receiver_name_saved else R.string.receiver_name_invalid,
                Toast.LENGTH_LONG).show()
            if (saved) onSaved()
        }
    }

    fun show() {
        close()
        val systemName = string(R.string.receiver_name_system)
        val input = AutoCompleteTextView(activity).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(DeviceInfoProvider(activity).getDeviceName(), false)
            setAdapter(ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, presets() + systemName))
            threshold = 0
            selectAll()
            setOnClickListener { showDropDown() }
            hint = string(R.string.receiver_name_hint)
        }
        val view = LinearLayout(activity).apply {
            setPadding(32, 8, 32, 8)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val custom = AlertDialog.Builder(activity).setTitle(R.string.receiver_name_custom)
            .setMessage(R.string.receiver_name_hint).setView(view)
            .setPositiveButton(R.string.receiver_name_save, null)
            .setNeutralButton(R.string.receiver_name_phone) { _, _ -> showQr() }
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog = custom
        input.setOnItemClickListener { parent, _, position, _ ->
            if (parent.getItemAtPosition(position) == systemName) save("") { custom.dismiss() }
        }
        custom.setOnShowListener {
            input.requestFocus()
            custom.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            // Wait for layout so the dropdown has a measured anchor and a window token.
            input.post { if (custom.isShowing) input.showDropDown() }
            custom.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (top.jast.miracastreceiver.util.ReceiverName.normalize(input.text.toString()) == null) {
                    input.error = string(R.string.receiver_name_invalid)
                } else save(input.text.toString()) { custom.dismiss() }
            }
        }
        custom.show()
    }

    private fun showQr() {
        val ip = NetworkUtils.getLocalIpAddress(activity)
        if (ip == null) {
            Toast.makeText(activity, R.string.receiver_name_network, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val title = string(R.string.receiver_name)
            val hint = string(R.string.receiver_name_hint)
            val saveLabel = string(R.string.receiver_name_save)
            val rooms = presets().toList()
            val setup = NameSetupServer(ip, page = { message, saved ->
                NameSetupPage.render(title, hint, DeviceInfoProvider(activity).getDeviceName(), rooms, saveLabel, message, saved)
            }, save = { AppSettings.setReceiverName(activity, it) },
                savedMessage = string(R.string.receiver_name_saved), invalidMessage = string(R.string.receiver_name_invalid))
            server = setup
            val bits = MultiFormatWriter().encode(setup.url, BarcodeFormat.QR_CODE, 360, 360,
                mapOf(EncodeHintType.MARGIN to 4))
            val bitmap = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(IntArray(360 * 360) { if (bits[it % 360, it / 360]) Color.BLACK else Color.WHITE },
                0, 360, 0, 0, 360, 360)
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(24, 8, 24, 8)
                addView(TextView(activity).apply { text = string(R.string.receiver_name_qr_hint); textSize = 18f })
                val size = (200 * resources.displayMetrics.density).toInt()
                addView(ImageView(activity).apply {
                    setImageBitmap(bitmap)
                    contentDescription = string(R.string.receiver_name_phone)
                }, LinearLayout.LayoutParams(size, size))
                addView(TextView(activity).apply { text = setup.url; textSize = 14f; gravity = Gravity.CENTER })
            }
            dialog = AlertDialog.Builder(activity).setTitle(R.string.receiver_name_phone)
                .setView(ScrollView(activity).apply { addView(content) })
                .setPositiveButton(android.R.string.ok, null).create().apply {
                    setOnDismissListener { setup.close(); if (server === setup) server = null }
                    show()
                }
        } catch (_: Exception) {
            server?.close()
            server = null
            Toast.makeText(activity, R.string.receiver_name_network, Toast.LENGTH_LONG).show()
        }
    }

    fun close() {
        dialog?.dismiss()
        dialog = null
        server?.close()
        server = null
    }
}
