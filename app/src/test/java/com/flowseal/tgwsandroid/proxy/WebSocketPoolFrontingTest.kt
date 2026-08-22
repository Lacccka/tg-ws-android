package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class WebSocketPoolFrontingTest {
    @Test
    fun refillLearnsFrontingPreferenceReusesItAndResetsOnNetworkGeneration() {
        var networkGeneration = 1L
        var normalCalls = 0
        var frontedCalls = 0
        val state = DirectFrontingPreferenceState()
        val frontingConnector =
            DirectFrontingConnector(
                normalConnect = { _, _, _, _ ->
                    normalCalls += 1
                    if (normalCalls == 1) throw SocketTimeoutException("planned direct timeout")
                    FakeWebSocket()
                },
                frontedConnect = { _, _, _, _, sniHost ->
                    assertEquals(DirectFrontingConnector.DEFAULT_FRONTING_SNI, sniHost)
                    frontedCalls += 1
                    FakeWebSocket()
                },
                state = state,
            )
        val pool =
            WebSocketPool(
                poolSize = 1,
                connector = RawWebSocketConnector { _, _, _, _ ->
                    throw AssertionError("legacy pool connector must not be used when refillConnector is configured")
                },
                refillConnector = DirectPoolRefillConnector { dc, isMedia, targetHost, domain, path, timeoutMs ->
                    frontingConnector.connect(
                        dc = dc,
                        isMedia = isMedia,
                        targetHost = targetHost,
                        domain = domain,
                        path = path,
                        normalTimeoutMs = timeoutMs,
                        networkGeneration = networkGeneration,
                    ).stream
                },
                nowMs = { 0L },
                executor = DirectExecutorService(),
                ownsExecutor = false,
            )
        val key = DirectFrontingRouteKey(2, false, TARGET)

        assertNull(pool.get(2, false, TARGET, listOf(DOMAIN)))
        assertEquals(1, normalCalls)
        assertEquals(1, frontedCalls)
        assertEquals(1, pool.readyCount(2, false))
        assertTrue(state.shouldTryFrontingFirst(key, 1L))

        assertNotNull(pool.get(2, false, TARGET, listOf(DOMAIN)))
        assertEquals("learned preference should make pool refill try fronting first", 1, normalCalls)
        assertEquals(2, frontedCalls)
        assertTrue(state.shouldTryFrontingFirst(key, 1L))

        networkGeneration = 2L
        assertNotNull(pool.get(2, false, TARGET, listOf(DOMAIN)))
        assertEquals("new network generation should restore normal-direct-first", 2, normalCalls)
        assertEquals(2, frontedCalls)
        assertFalse(state.shouldTryFrontingFirst(key, 2L))
    }

    private class FakeWebSocket : WebSocketBinaryStream {
        private var closed = false

        override fun send(data: ByteArray) = Unit
        override fun sendBatch(parts: List<ByteArray>) = Unit
        override fun recv(): ByteArray? = null
        override fun isUsableForPool(): Boolean = !closed
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
