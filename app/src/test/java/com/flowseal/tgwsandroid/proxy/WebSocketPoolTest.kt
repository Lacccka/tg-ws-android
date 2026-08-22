package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WebSocketPoolTest {
    @Test
    fun failedParallelRefillWaveIncrementsBackoffOncePerKey() {
        var now = 0L
        val calls = AtomicInteger(0)
        val suppressed = AtomicInteger(0)
        val pool =
            WebSocketPool(
                poolSize = 4,
                connector = RawWebSocketConnector { _, _, _, _ ->
                    calls.incrementAndGet()
                    throw IOException("blocked")
                },
                nowMs = { now },
                executor = DirectExecutorService(),
                ownsExecutor = false,
                onRefillBackoffSuppressed = { _, _, _ -> suppressed.incrementAndGet() },
            )
        val key = WebSocketPool.Key(2, false)

        assertNull(pool.get(2, false, TARGET, listOf(DOMAIN)))

        assertEquals(4, calls.get())
        assertEquals(1, pool.refillFailuresSnapshot()[key])
        assertEquals(60_000L, pool.refillBackoffUntilSnapshot()[key])

        assertNull(pool.get(2, false, TARGET, listOf(DOMAIN)))
        assertEquals(4, calls.get())
        assertEquals(1, suppressed.get())

        now = 60_000L
        assertNull(pool.get(2, false, TARGET, listOf(DOMAIN)))

        assertEquals(8, calls.get())
        assertEquals(2, pool.refillFailuresSnapshot()[key])
        assertEquals(180_000L, pool.refillBackoffUntilSnapshot()[key])
    }

    @Test
    fun successfulRefillWaveClearsExistingBackoff() {
        var now = 0L
        var fail = true
        val calls = AtomicInteger(0)
        val pool =
            WebSocketPool(
                poolSize = 4,
                connector = RawWebSocketConnector { _, _, _, _ ->
                    calls.incrementAndGet()
                    if (fail) throw IOException("blocked")
                    FakeWebSocket()
                },
                nowMs = { now },
                executor = DirectExecutorService(),
                ownsExecutor = false,
            )
        val key = WebSocketPool.Key(2, false)

        pool.get(2, false, TARGET, listOf(DOMAIN))
        assertEquals(1, pool.refillFailuresSnapshot()[key])

        now = 60_000L
        fail = false
        pool.get(2, false, TARGET, listOf(DOMAIN))

        assertEquals(8, calls.get())
        assertEquals(4, pool.readyCount(2, false))
        assertFalse(pool.refillFailuresSnapshot().containsKey(key))
        assertFalse(pool.refillBackoffUntilSnapshot().containsKey(key))
    }

    @Test
    fun confirmedDirectSuccessClearsBackoffImmediately() {
        val pool =
            WebSocketPool(
                poolSize = 1,
                connector = RawWebSocketConnector { _, _, _, _ -> throw IOException("blocked") },
                nowMs = { 0L },
                executor = DirectExecutorService(),
                ownsExecutor = false,
            )
        val key = WebSocketPool.Key(4, true)

        pool.get(4, true, TARGET, listOf(DOMAIN))
        assertTrue(pool.refillFailuresSnapshot().containsKey(key))

        pool.reportSuccess(4, true)

        assertFalse(pool.refillFailuresSnapshot().containsKey(key))
        assertFalse(pool.refillBackoffUntilSnapshot().containsKey(key))
    }

    @Test
    fun closedIdleSocketIsPrunedWithoutConsumingAFrameAndReplaced() {
        val created = mutableListOf<FakeWebSocket>()
        val pool =
            WebSocketPool(
                poolSize = 1,
                connector = RawWebSocketConnector { _, _, _, _ ->
                    FakeWebSocket().also(created::add)
                },
                nowMs = { 0L },
                executor = DirectExecutorService(),
                ownsExecutor = false,
            )
        val key = WebSocketPool.Key(2, false)

        assertNull(pool.get(2, false, TARGET, listOf(DOMAIN)))
        assertEquals(1, pool.readyCount(2, false))
        assertEquals(1, created.size)

        created.single().usable = false
        assertNull(pool.get(2, false, TARGET, listOf(DOMAIN)))

        assertTrue(created.first().closed)
        assertEquals(2, created.size)
        assertEquals(1, pool.readyCount(2, false))
        assertEquals(1L, pool.closedIdlePrunedSnapshot()[key])
    }

    private class FakeWebSocket : WebSocketBinaryStream {
        var usable: Boolean = true
        var closed: Boolean = false

        override fun send(data: ByteArray) = Unit

        override fun sendBatch(parts: List<ByteArray>) = Unit

        override fun recv(): ByteArray? = null

        override fun isUsableForPool(): Boolean = usable && !closed

        override fun close() {
            closed = true
        }
    }

    private class DirectExecutorService : AbstractExecutorService() {
        @Volatile private var shutdown = false

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return mutableListOf()
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = shutdown

        override fun execute(command: Runnable) {
            if (shutdown) throw RejectedExecutionException("executor is shut down")
            command.run()
        }
    }

    companion object {
        private const val TARGET = "149.154.167.220"
        private const val DOMAIN = "kws2.web.telegram.org"
    }
}
