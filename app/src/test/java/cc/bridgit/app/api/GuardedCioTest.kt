package cc.bridgit.app.api

import cc.bridgit.app.api.engine.DeadListener
import cc.bridgit.app.api.engine.acceptBackoffMillis
import cc.bridgit.app.api.engine.acceptUntilDead
import cc.bridgit.app.api.engine.guardedHttpServer
import io.ktor.server.cio.HttpServerSettings
import io.ktor.server.engine.connector
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicInteger

class GuardedCioTest {
    private val abort = IOException("Software caused connection abort")

    @Test
    fun backoffDoublesUpToOneSecond() {
        assertEquals(listOf(10L, 20L, 40L, 80L, 160L, 320L, 640L, 1000L, 1000L), (1..9).map { acceptBackoffMillis(it) })
    }

    /** Android destroys the socket when Tailscale drops and every accept fails at once: no spin, and stop still works. */
    @Test
    fun failingAcceptBacksOffAndCancels() = runBlocking {
        val calls = AtomicInteger()
        val job = launch(Dispatchers.IO) {
            acceptUntilDead<Unit>({ calls.incrementAndGet(); throw abort }) { }
        }
        delay(600)
        withTimeout(2000) { job.cancelAndJoin() }
        // 10+20+40+80+160+320 ms of backoff fit in 600 ms: about 7 tries. Stock CIO makes millions.
        assertTrue("accept tried ${calls.get()} times", calls.get() in 3..10)
    }

    @Test
    fun acceptThatKeepsFailingGivesUp() = runBlocking {
        var calls = 0
        val e = runCatching { withTimeout(2000) { acceptUntilDead<Unit>({ calls++; throw abort }, giveUpAfter = 5) { } } }
        assertTrue(e.exceptionOrNull().toString(), e.exceptionOrNull() is DeadListener)
        assertEquals(5, calls)
    }

    @Test
    fun successResetsFailureCount() = runBlocking {
        var n = 0
        var accepted = 0
        // fail, fail, ok, fail, fail, ok, ... never 3 failures in a row, until a closed socket ends it.
        val e = runCatching {
            withTimeout(5000) {
                acceptUntilDead({ n++; if (n > 30) throw ClosedChannelException(); if (n % 3 == 0) n else throw abort }, giveUpAfter = 3) { accepted++ }
            }
        }
        assertTrue(e.exceptionOrNull() is DeadListener)
        assertEquals(10, accepted)
    }

    @Test
    fun closedSocketIsDead() = runBlocking {
        var accepted = 0
        var n = 0
        val e = runCatching { withTimeout(2000) { acceptUntilDead({ if (++n <= 2) n else throw ClosedChannelException() }) { accepted++ } } }
        assertTrue(e.exceptionOrNull() is DeadListener)
        assertEquals(2, accepted)
    }

    /** A dead socket fails the server's jobs, which is what tells ApiServer to ask for a rebind. */
    @Test
    fun deadSocketFailsServer() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
        try {
            val server = scope.guardedHttpServer(
                HttpServerSettings(host = "127.0.0.1", port = 0), giveUpAfter = 3, accept = { throw abort }
            ) { }
            val cause = kotlinx.coroutines.CompletableDeferred<Throwable?>()
            server.acceptJob.invokeOnCompletion { cause.complete(it) }
            withTimeout(5000) { server.rootServerJob.join() }
            assertTrue(server.rootServerJob.isCancelled)
            assertTrue(cause.await() is DeadListener)
        } finally {
            scope.cancel()
        }
    }

    /** The engine reports a dead socket through `stopped` (ApiServer pokes the service) and does not crash the app. */
    @Test
    fun deadSocketEndsEngineWithoutCrash() = runBlocking {
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val env = io.ktor.server.engine.applicationEngineEnvironment {
                connector { host = "127.0.0.1"; port = 0 }
            }
            val engine = cc.bridgit.app.api.engine.GuardedCioEngine(env, {}, 3) { throw abort }
            engine.start(wait = false)
            withTimeout(5000) { engine.stopped.join() }
            assertTrue(engine.stopped.isCancelled)
            engine.stop(100, 200) // what ApiServer does next; must not hang or throw
            delay(200)
            assertEquals(emptyList<Throwable>(), uncaught.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun stopEndsRealServer() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        val server = scope.guardedHttpServer(HttpServerSettings(host = "127.0.0.1", port = 0)) { }
        withTimeout(5000) { server.serverSocket.await() }
        server.rootServerJob.cancel()
        withTimeout(5000) { server.rootServerJob.join() }
        scope.cancel()
    }
}
