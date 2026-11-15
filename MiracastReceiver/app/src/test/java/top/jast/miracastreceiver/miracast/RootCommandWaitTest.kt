package top.jast.miracastreceiver.miracast

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class RootCommandWaitTest {
    private class Child(private val exit: Int?) : Process() {
        var destroyed = false
        override fun getInputStream() = object : ByteArrayInputStream(byteArrayOf()) {
            override fun read(): Int = error("Must not block on root authorization output")
        }
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int = error("Must not wait indefinitely for root authorization")
        override fun exitValue(): Int = exit ?: throw IllegalThreadStateException()
        override fun destroy() { destroyed = true }
    }

    @Test fun successfulCommandIsAcceptedAndCleanedUp() {
        val child = Child(0)
        assertTrue(waitForRootCommand(child))
        assertTrue(child.destroyed)
    }

    @Test fun deniedRootIsNotReportedAsSuccess() {
        val child = Child(1)
        assertFalse(waitForRootCommand(child))
        assertTrue(child.destroyed)
    }

    @Test(timeout = 2000) fun unansweredAuthorizationCannotHangStartup() {
        val child = Child(null)
        assertFalse(waitForRootCommand(child, timeoutMs = 60))
        assertTrue(child.destroyed)
    }

    @Test fun cancellationStillDestroysTheChild() {
        val child = Child(null)
        Thread.currentThread().interrupt()
        try {
            waitForRootCommand(child)
            fail("Expected interruption")
        } catch (_: InterruptedException) {
            assertTrue(child.destroyed)
        } finally {
            Thread.interrupted()
        }
    }
}
