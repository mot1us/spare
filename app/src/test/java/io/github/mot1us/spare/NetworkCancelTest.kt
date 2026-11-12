package io.github.mot1us.spare

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test

class NetworkCancelTest {
    @Test fun interruptingADownloadWaitingOnASilentServerReleasesItsThreadPromptly() {
        ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { server ->
            val accepted = CountDownLatch(1)
            val holder = Thread { runCatching { server.accept().use { accepted.countDown(); Thread.sleep(30000) } } }.apply { isDaemon = true; start() }
            val pool = Executors.newSingleThreadExecutor()
            val task = pool.submit<Throwable?> {
                runCatching { Network.get("http://127.0.0.1:${server.localPort}/player_api.php", http = true, timeoutMs = 20000) }.exceptionOrNull()
            }
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)
            val began = System.nanoTime()
            pool.shutdownNow()
            assertTrue("worker stayed blocked on the socket", pool.awaitTermination(3, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 3000)
            assertEquals(0, Network.openConnections)
            holder.interrupt(); task.cancel(true)
        }
    }
}
