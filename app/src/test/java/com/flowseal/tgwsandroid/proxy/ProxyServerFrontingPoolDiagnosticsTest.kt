package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ProxyServerFrontingPoolDiagnosticsTest {
    @Test
    fun startupWarmupUsesFrontingAndExposesPreferenceWithoutBackoff() {
        val server = FakeTcpServerTransport()
        val connector = TimeoutThenFrontingConnector()
        val logs = CopyOnWriteArrayList<String>()
        val proxy =
            ProxyServer(
                config = ProxyServerConfig(
                    host = "127.0.0.1",
                    port = 1443,
                    secretHex = "0123456789abcdeffedcba9876543210",
                    dcRedirects = mapOf(2 to TARGET),
                    poolSize = 1,
                    cfproxyEnabled = false,
                    routeMode = NetworkRouteMode.DIRECT_FIRST,
                    networkStatus = "Wi-Fi",
                ),
                serverTransport = server,
                webSocketConnector = connector,
                logger = ProxyLogger { logs += it },
            )

        proxy.start()
        waitUntil("fronting-backed pool warmup") {
            val stats = proxy.stats()
            stats.frontingSuccesses >= 2L && stats.directPoolDiagnostics.readyByKey.values.sum() >= 2
        }
        val stats = proxy.stats()
        proxy.stop()

        assertTrue(stats.frontingAttempts >= 2L)
        assertEquals(stats.frontingAttempts, stats.frontingSuccesses)
        assertEquals(0L, stats.frontingFailures)
        assertTrue(stats.frontingFallbackAttempts >= 2L)
        assertEquals(0L, stats.frontingFirstAttempts)
        assertTrue(stats.frontingPreferredKeys.any { it.contains("dc2") && it.contains(TARGET) })
        assertTrue(stats.directPoolDiagnostics.refillFailureWavesByKey.isEmpty())
        assertTrue(stats.directPoolDiagnostics.refillBackoffRemainingMsByKey.isEmpty())
        assertTrue(stats.directPoolDiagnostics.refillBackoffSuppressedByKey.isEmpty())
        assertTrue(logs.any { it.contains("fronting fallback attempt") })
        assertTrue(connector.frontedSni.all { it == DirectFrontingConnector.DEFAULT_FRONTING_SNI })
    }

    private class TimeoutThenFrontingConnector : RawWebSocketConnector {
        val frontedSni = CopyOnWriteArrayList<String>()

        override fun connect(targetHost: String, domain: String, path: String, timeoutMs: Int): WebSocketBinaryStream {
            throw SocketTimeoutException("planned pool direct timeout")
        }

        override fun connectWithSni(
            targetHost: String,
            domain: String,
            path: String,
            timeoutMs: Int,
            sniHost: String,
        ): WebSocketBinaryStream {
            frontedSni += sniHost
            return FakeWebSocket()
        }
    }

    private class FakeWebSocket : WebSocketBinaryStream {
        @Volatile private var closed = false
        override fun send(data: ByteArray) = Unit
        override fun sendBatch(parts: List<ByteArray>) = Unit
        override fun recv(): ByteArray? = null
        override fun isUsableForPool(): Boolean = !closed
        override fun close() {
            closed = true
        }
    }

    private class FakeTcpServerTransport : TcpServerTransport {
        private val queue = LinkedBlockingQueue<TcpClientTransport>()
        private val closed = AtomicBoolean(false)
        override fun bind(host: String, port: Int) = Unit
        override fun accept(): TcpClientTransport? {
            while (!closed.get()) {
                queue.poll(50, TimeUnit.MILLISECONDS)?.let { return it }
            }
            return null
        }
        override fun close() {
            closed.set(true)
        }
    }

    private fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $message")
    }

    companion object {
        private const val TARGET = "149.154.167.220"
    }
}
