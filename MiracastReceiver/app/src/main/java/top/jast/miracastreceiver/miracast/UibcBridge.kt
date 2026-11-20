package top.jast.miracastreceiver.miracast

/** 当前 WFD 会话的 UIBC 发送端；由 WfdSessionHandler 设置，播放页读取。 */
object UibcBridge {
    @Volatile
    var sender: UibcSender? = null

    fun key(androidKeyCode: Int, down: Boolean) {
        sender?.sendKey(androidKeyCode, down)
    }

    fun mouse(dx: Int, dy: Int, wheel: Int = 0) {
        sender?.sendMouse(dx, dy, wheel)
    }

    fun mouseButton(button: Int, down: Boolean) {
        sender?.sendMouseButton(button, down)
    }

    fun wheel(delta: Int) {
        sender?.sendWheel(delta)
    }

    fun touch(x: Int, y: Int, down: Boolean) {
        sender?.sendTouch(x, y, down)
    }

    /** [events, frames, maxLatencyMs] for the last interval, or null when UIBC is off. */
    fun stats(): IntArray? = sender?.takeStats()
}
