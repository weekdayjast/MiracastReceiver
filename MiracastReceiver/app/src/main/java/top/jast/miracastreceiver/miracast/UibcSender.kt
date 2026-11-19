package top.jast.miracastreceiver.miracast

import android.view.KeyEvent
import timber.log.Timber
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * UIBC HIDC sender.
 *
 * The WFD source announces its UIBC TCP port in the M4
 * `wfd_uibc_capability` parameter and listens on it; the sink connects and
 * sends HID input reports for its own input devices.
 *
 * UIBC frame: version(1) | type(1) | length(2, big endian) | payload.
 * type = 1 is HIDC. HIDC body: HID Input Path(1) | HID Type(1) |
 * Usage(1) | Length(1) | HIDC value.
 */
class UibcSender(private val host: String, private val port: Int) {
    companion object {
        private const val HID_INPUT_PATH_USB = 1
        private const val HID_TYPE_KEYBOARD = 0
        private const val HID_TYPE_MOUSE = 1
        private const val USAGE_INPUT_REPORT = 0
        private const val USAGE_DESCRIPTOR = 1

        /** Standard USB HID boot keyboard report descriptor (8-byte report). */
        private val KEYBOARD_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x05, 0x07, 0x19, 0xE0.toByte(),
            0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08,
            0x81.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01, 0x95.toByte(), 0x05,
            0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91.toByte(), 0x02, 0x95.toByte(),
            0x01, 0x75, 0x03, 0x91.toByte(), 0x01, 0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00,
            0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00, 0xC0.toByte()
        )

        /** USB HID boot mouse: 3 buttons, relative X/Y, wheel. Works across displays. */
        private val MOUSE_DESCRIPTOR = byteArrayOf(
            0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x09, 0x01, 0xA1.toByte(), 0x00,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03,
            0x75, 0x01, 0x81.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x01,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81.toByte(), 0x25,
            0x7F, 0x75, 0x08, 0x95.toByte(), 0x03, 0x81.toByte(), 0x06, 0xC0.toByte(), 0xC0.toByte()
        )
    }

    private var socket: Socket? = null
    private var output: OutputStream? = null
    private val lock = Any()

    // Input events arrive on the main thread; socket writes must stay off it
    // and preserve order, so use one background writer.
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "uibc-writer").apply { isDaemon = true }
    }

    private var modifiers = 0
    private val keys = IntArray(6) { 0 }
    private var buttons = 0
    private val mouseLock = Any()
    private var pendingDx = 0
    private var pendingDy = 0
    private var pendingWheel = 0
    private var mouseFlushInFlight = false

    @Volatile
    var connected = false
        private set

    fun connect(): Boolean {
        if (connected) return true
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 3000)
            socket = s
            output = s.getOutputStream()
            connected = true
            Timber.i("UIBC: connected to %s:%d", host, port)
            // The source needs the HID report descriptors before input reports.
            sendHidc(HID_TYPE_KEYBOARD, USAGE_DESCRIPTOR, KEYBOARD_DESCRIPTOR, direct = true)
            sendHidc(HID_TYPE_MOUSE, USAGE_DESCRIPTOR, MOUSE_DESCRIPTOR, direct = true)
            startReader(s)
            true
        } catch (e: Exception) {
            Timber.w(e, "UIBC: connect to %s:%d failed", host, port)
            false
        }
    }

    private fun startReader(socket: Socket) {
        Thread {
            try {
                val input = socket.getInputStream()
                val header = ByteArray(4)
                while (connected) {
                    if (!readFully(input, header, 4)) break
                    val version = header[0].toInt() and 0xff
                    val type = header[1].toInt() and 0xff
                    val length = ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
                    if (length > 2048) break
                    val payload = ByteArray(length)
                    if (length > 0 && !readFully(input, payload, length)) break
                    Timber.i("UIBC: rx v=%d type=%d len=%d hex=%s", version, type, length, payload.toHex())
                    handleIncoming(type, payload)
                }
            } catch (e: Exception) {
                if (connected) Timber.i("UIBC: reader closed (%s)", e.message)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handleIncoming(type: Int, payload: ByteArray) {
        // HIDC: HID Input Path | HID Type | Usage | Length(2) | value
        if (type != 1 || payload.size < 5) return
        val path = payload[0].toInt() and 0xff
        val hidType = payload[1].toInt() and 0xff
        val usage = payload[2].toInt() and 0xff
        if (path != HID_INPUT_PATH_USB) return
        if (usage != USAGE_INPUT_REPORT && usage != USAGE_DESCRIPTOR) {
            // Unknown usage is treated as a descriptor request for that HID type.
            if (hidType == HID_TYPE_MOUSE) {
                sendHidc(HID_TYPE_MOUSE, USAGE_DESCRIPTOR, MOUSE_DESCRIPTOR)
            } else if (hidType == HID_TYPE_KEYBOARD) {
                sendHidc(HID_TYPE_KEYBOARD, USAGE_DESCRIPTOR, KEYBOARD_DESCRIPTOR)
            }
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val n = input.read(buffer, offset, length - offset)
            if (n <= 0) return false
            offset += n
        }
        return true
    }

    private fun ByteArray.toHex(limit: Int = 64): String {
        val n = minOf(size, limit)
        val sb = StringBuilder(n * 3)
        for (i in 0 until n) sb.append("%02x".format(this[i].toInt() and 0xff))
        if (size > limit) sb.append("...")
        return sb.toString()
    }

    fun close() {        connected = false
        runCatching { output?.close() }
        runCatching { socket?.close() }
        output = null
        socket = null
        synchronized(lock) {
            modifiers = 0
            keys.fill(0)
            buttons = 0
        }
        writer.shutdownNow()
    }

    fun sendKey(androidKeyCode: Int, down: Boolean) {
        if (!connected) return
        if (isModifier(androidKeyCode)) {
            val bit = modifierBit(androidKeyCode)
            synchronized(lock) {
                modifiers = if (down) modifiers or bit else modifiers and bit.inv()
            }
            sendKeyboardReport()
            return
        }
        val usage = hidUsage(androidKeyCode)
        if (usage == 0) return
        synchronized(lock) {
            if (down) {
                if (!keys.contains(usage)) {
                    val slot = keys.indexOf(0)
                    if (slot >= 0) keys[slot] = usage
                }
            } else {
                for (i in keys.indices) if (keys[i] == usage) keys[i] = 0
            }
        }
        sendKeyboardReport()
    }

    fun sendMouse(dx: Int, dy: Int, wheel: Int = 0) {
        if (!connected) return
        synchronized(mouseLock) {
            pendingDx += dx
            pendingDy += dy
            pendingWheel += wheel
            if (mouseFlushInFlight) return
            mouseFlushInFlight = true
        }
        scheduleMouseFlush()
    }

    /**
     * Event driven with backpressure: send immediately; if a write is already in
     * flight, accumulate and send the remainder as soon as that write completes.
     * This adds no artificial delay and cannot grow an unbounded queue.
     */
    private fun scheduleMouseFlush() {
        try {
            writer.execute {
                val delta = synchronized(mouseLock) {
                    val d = pendingDx.coerceIn(-127, 127)
                    val e = pendingDy.coerceIn(-127, 127)
                    val w = pendingWheel.coerceIn(-127, 127)
                    pendingDx = 0
                    pendingDy = 0
                    pendingWheel = 0
                    intArrayOf(d, e, w)
                }
                sendMouseReport(delta[0], delta[1], delta[2], direct = true)
                val more = synchronized(mouseLock) {
                    if (pendingDx != 0 || pendingDy != 0 || pendingWheel != 0) {
                        true
                    } else {
                        mouseFlushInFlight = false
                        false
                    }
                }
                if (more) scheduleMouseFlush()
            }
        } catch (_: Exception) {
            synchronized(mouseLock) { mouseFlushInFlight = false }
        }
    }

    private fun sendMouseReport(dx: Int, dy: Int, wheel: Int, direct: Boolean) {
        val report = byteArrayOf(
            buttons.toByte(),
            dx.toByte(),
            dy.toByte(),
            wheel.toByte()
        )
        sendHidc(HID_TYPE_MOUSE, USAGE_INPUT_REPORT, report, direct)
    }

    fun sendMouseButton(androidButton: Int, down: Boolean) {
        if (!connected) return
        val bit = when (androidButton) {
            android.view.MotionEvent.BUTTON_PRIMARY -> 0x01
            android.view.MotionEvent.BUTTON_SECONDARY -> 0x02
            android.view.MotionEvent.BUTTON_TERTIARY -> 0x04
            else -> 0
        }
        if (bit == 0) return
        synchronized(lock) { buttons = if (down) buttons or bit else buttons and bit.inv() }
        sendMouseReport(0, 0, 0, direct = false)
    }

    fun sendWheel(delta: Int) {
        if (!connected) return
        sendMouse(0, 0, delta)
    }

    private fun sendKeyboardReport() {
        val report = ByteArray(8)
        synchronized(lock) {
            report[0] = modifiers.toByte()
            for (i in keys.indices) report[2 + i] = keys[i].toByte()
        }
        sendHidc(HID_TYPE_KEYBOARD, USAGE_INPUT_REPORT, report)
    }

    private fun sendHidc(hidType: Int, usage: Int, value: ByteArray, direct: Boolean = false) {
        val payload = ByteArray(5 + value.size)
        payload[0] = HID_INPUT_PATH_USB.toByte()
        payload[1] = hidType.toByte()
        payload[2] = usage.toByte()
        // Length is two octets (Table 22).
        payload[3] = ((value.size shr 8) and 0xff).toByte()
        payload[4] = (value.size and 0xff).toByte()
        System.arraycopy(value, 0, payload, 5, value.size)
        // Length covers the whole TCP payload: 4-byte UIBC header + body.
        val total = 4 + payload.size
        val header = byteArrayOf(
            0, // Version 000, T=0, reserved
            1, // Input Category = HIDC
            ((total shr 8) and 0xff).toByte(),
            (total and 0xff).toByte()
        )
        if (direct) {
            writeFrame(header, payload)
        } else {
            try {
                writer.execute { writeFrame(header, payload) }
            } catch (_: Exception) {
                // writer already shut down after close()
            }
        }
    }

    private fun writeFrame(header: ByteArray, payload: ByteArray) {
        synchronized(lock) {
            try {
                output?.write(header)
                output?.write(payload)
                output?.flush()
            } catch (e: Exception) {
                Timber.w(e, "UIBC: send failed")
                close()
            }
        }
    }

    private fun isModifier(code: Int): Boolean =
        code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT ||
            code == KeyEvent.KEYCODE_ALT_LEFT || code == KeyEvent.KEYCODE_ALT_RIGHT ||
            code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT ||
            code == KeyEvent.KEYCODE_META_LEFT || code == KeyEvent.KEYCODE_META_RIGHT

    private fun modifierBit(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_CTRL_LEFT -> 0x01
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0x10
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0x02
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0x20
        KeyEvent.KEYCODE_ALT_LEFT -> 0x04
        KeyEvent.KEYCODE_ALT_RIGHT -> 0x40
        KeyEvent.KEYCODE_META_LEFT -> 0x08
        KeyEvent.KEYCODE_META_RIGHT -> 0x80
        else -> 0
    }

    /** Android KeyEvent to USB HID Usage ID (Keyboard/Keypad page). */
    private fun hidUsage(code: Int): Int = when {
        code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x04 + (code - KeyEvent.KEYCODE_A)
        code in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x1E + (code - KeyEvent.KEYCODE_1)
        code == KeyEvent.KEYCODE_0 -> 0x27
        code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x28
        code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BACK -> 0x29
        code == KeyEvent.KEYCODE_DEL -> 0x2A
        code == KeyEvent.KEYCODE_TAB -> 0x2B
        code == KeyEvent.KEYCODE_SPACE -> 0x2C
        code == KeyEvent.KEYCODE_MINUS -> 0x2D
        code == KeyEvent.KEYCODE_EQUALS -> 0x2E
        code == KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
        code == KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
        code == KeyEvent.KEYCODE_BACKSLASH -> 0x31
        code == KeyEvent.KEYCODE_SEMICOLON -> 0x33
        code == KeyEvent.KEYCODE_APOSTROPHE -> 0x34
        code == KeyEvent.KEYCODE_GRAVE -> 0x35
        code == KeyEvent.KEYCODE_COMMA -> 0x36
        code == KeyEvent.KEYCODE_PERIOD -> 0x37
        code == KeyEvent.KEYCODE_SLASH -> 0x38
        code == KeyEvent.KEYCODE_DPAD_UP -> 0x52
        code == KeyEvent.KEYCODE_DPAD_DOWN -> 0x51
        code == KeyEvent.KEYCODE_DPAD_LEFT -> 0x50
        code == KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4F
        code == KeyEvent.KEYCODE_HOME -> 0x4A
        code == KeyEvent.KEYCODE_MENU -> 0x65
        code == KeyEvent.KEYCODE_VOLUME_UP -> 0x80
        code == KeyEvent.KEYCODE_VOLUME_DOWN -> 0x81
        code == KeyEvent.KEYCODE_MUTE -> 0x7F
        code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> 0xCD
        code == KeyEvent.KEYCODE_MEDIA_NEXT -> 0xB5
        code == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> 0xB6
        code == KeyEvent.KEYCODE_MEDIA_STOP -> 0xB7
        code == KeyEvent.KEYCODE_PAGE_UP -> 0x4B
        code == KeyEvent.KEYCODE_PAGE_DOWN -> 0x4E
        code == KeyEvent.KEYCODE_MOVE_HOME -> 0x4A
        code == KeyEvent.KEYCODE_MOVE_END -> 0x4D
        code == KeyEvent.KEYCODE_INSERT -> 0x49
        code == KeyEvent.KEYCODE_FORWARD_DEL -> 0x4C
        code == KeyEvent.KEYCODE_CAPS_LOCK -> 0x39
        code == KeyEvent.KEYCODE_NUM_LOCK -> 0x53
        code == KeyEvent.KEYCODE_SCROLL_LOCK -> 0x47
        code == KeyEvent.KEYCODE_SYSRQ -> 0x46
        code == KeyEvent.KEYCODE_BREAK -> 0x48
        else -> 0
    }
}
