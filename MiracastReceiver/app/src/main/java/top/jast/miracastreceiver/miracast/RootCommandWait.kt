package top.jast.miracastreceiver.miracast

/** API 21 compatible bounded wait. Never block on stdout before the child exits. */
internal fun waitForRootCommand(process: Process, timeoutMs: Long = 15_000): Boolean {
    try {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val exit = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
            if (exit != null) return exit == 0
            Thread.sleep(50)
        }
        return false
    } finally {
        process.destroy()
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
    }
}
