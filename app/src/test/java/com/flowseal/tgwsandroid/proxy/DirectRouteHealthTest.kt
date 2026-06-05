package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DirectRouteHealthTest {
    @Test
    fun successfulProbeClosesProbeSocketAndRecordsHealthyState() {
        val socket = FakeProbeSocket()
        val logs = CopyOnWriteArrayList<String>()
        val health = DirectRouteHealth(
            connector = RawWebSocketConnector { _, _, _, _ -> socket },
            dcRedirects = mapOf(2 to "203.0.113.2"),
            wsDomainsProvider = ::wsDomains,
            logger = ProxyLogger { logs.add(it) },
        )

        assertTrue(health.probeOnce())

        val snapshot = health.snapshot()
        assertEquals(DirectHealthState.HEALTHY, snapshot.state)
        assertEquals(1L, snapshot.successes)
        assertTrue(socket.closed)
        assertTrue(logs.any { it.contains("direct health probe started") })
        assertTrue(logs.any { it.contains("direct health probe success") })
    }

    @Test
    fun failedProbeRecordsUnhealthyStateAndLastError() {
        val health = DirectRouteHealth(
            connector = RawWebSocketConnector { _, domain, _, _ -> throw IOException("blocked $domain") },
            dcRedirects = mapOf(2 to "203.0.113.2"),
            wsDomainsProvider = ::wsDomains,
            logger = ProxyLogger {},
        )

        assertFalse(health.probeOnce())

        val snapshot = health.snapshot()
        assertEquals(DirectHealthState.UNHEALTHY, snapshot.state)
        assertEquals(1L, snapshot.failures)
        assertTrue(snapshot.lastError!!.contains("blocked"))
    }

    @Test
    fun cooldownPreventsPromotionProbe() {
        val logs = CopyOnWriteArrayList<String>()
        val attempts = AtomicInteger(0)
        val health = DirectRouteHealth(
            connector = RawWebSocketConnector { _, _, _, _ ->
                attempts.incrementAndGet()
                FakeProbeSocket()
            },
            dcRedirects = mapOf(2 to "203.0.113.2"),
            wsDomainsProvider = ::wsDomains,
            logger = ProxyLogger { logs.add(it) },
        )

        health.startCooldown(30_000L, "test cooldown")
        health.startPromotionProbe(shouldContinue = { true }, onPromote = { throw AssertionError("must not promote") })
        waitUntil { logs.any { it.contains("direct promotion skipped") } }

        assertEquals(DirectHealthState.COOLDOWN, health.snapshot().state)
        assertEquals(0, attempts.get())
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for condition")
    }

    private class FakeProbeSocket : WebSocketBinaryStream {
        var closed = false
        override fun send(data: ByteArray) = Unit
        override fun sendBatch(parts: List<ByteArray>) = Unit
        override fun recv(): ByteArray? = null
        override fun close() {
            closed = true
        }
    }
}
