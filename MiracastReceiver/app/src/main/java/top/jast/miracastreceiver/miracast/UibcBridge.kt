package top.jast.miracastreceiver.miracast

/** 当前 WFD 会话的 UIBC 发送端；由 WfdSessionHandler 设置，播放页读取。 */
object UibcBridge {
    @Volatile
    var sender: UibcSender? = null

    fun key(androidKeyCode: Int, down: Boolean) {
        sender?.sendKey(androidKeyCode, down)
    }

    fun mouseAbsolute(x: Int, y: Int, wheel: Int = 0) {
        sender?.sendMouseAbsolute(x, y, wheel)
    }

    fun mouseButton(button: Int, down: Boolean) {
        sender?.sendMouseButton(button, down)
    }

    fun wheel(delta: Int) {
        sender?.sendWheel(delta)
    }
}
