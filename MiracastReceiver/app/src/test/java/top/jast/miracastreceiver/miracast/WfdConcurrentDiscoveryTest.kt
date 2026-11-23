package top.jast.miracastreceiver.miracast

import android.content.ContextWrapper
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class WfdConcurrentDiscoveryTest {
    @Test fun newSourceIsDialedWhilePreviousSourceSessionIsStillRunning() {
        val firstRunning = CountDownLatch(1)
        val secondRunning = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.2")).use { first ->
            first.soTimeout = 10000
            val receiver = WfdServer(ContextWrapper(null), port = first.localPort,
                subnetProvider = { "127.0.0." }, sessionRunner = { socket ->
                    if (socket.inetAddress.hostAddress == "127.0.0.2") firstRunning.countDown()
                    else secondRunning.countDown()
                    // Simulate an established RTSP session blocking until its sender closes.
                    runCatching { socket.getInputStream().read() }
                })
            receiver.start()
            try {
                first.accept().use { firstPeer ->
                    assertTrue(firstRunning.await(5, TimeUnit.SECONDS))
                    // The second sender appears only after the first session is active.
                    ServerSocket(first.localPort, 1, InetAddress.getByName("127.0.0.3")).use { second ->
                        second.soTimeout = 10000
                        second.accept().use { secondPeer ->
                            assertTrue(secondRunning.await(5, TimeUnit.SECONDS))
                            assertFalse(firstPeer.isClosed)
                            assertFalse(secondPeer.isClosed)
                        }
                    }
                }
            } finally { receiver.stop() }
        }
    }
}
