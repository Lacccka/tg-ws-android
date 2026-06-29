package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CfWebSocketPoolTest {
    @Test
    fun refillDoesNotBypass429Cooldown() {
        var now = 1_000L
        val health = CfDomainHealth(
            domains = listOf("cf.example"),
            nowMs = { now },
            jitterRatio = { 0.0 },
        )
        health.recordFailure(
            dcId = 2,
            isMedia = false,
            baseDomain = "cf.example",
            error = IOException("HTTP 429"),
            networkStatus = "mobile",
            routeSettling = false,
        )
        val connector = RecordingConnector()
        val pool = CfWebSocketPool(connector = connector, cfDomainHealth = health)

        pool.scheduleRefill(2, isMedia = false, baseDomain = "cf.example")
        Thread.sleep(100)
        pool.closeAll()

        assertTrue("429 cooldown should prevent CF pool prewarm connect", connector.tuples.isEmpty())
    }

    @Test
    fun closeAllClosesReadySocketAndStopsPendingExecutor() {
        val readyConnector = RecordingConnector()
        val readyPool = CfWebSocketPool(
            connector = readyConnector,
            cfDomainHealth = CfDomainHealth(listOf("cf.example")),
        )
        readyPool.scheduleRefill(2, isMedia = false, baseDomain = "cf.example")
        waitUntil { readyPool.readySnapshot().isNotEmpty() }
        readyPool.closeAll()
        assertTrue("ready socket should be closed", readyConnector.sockets.single().closed)

        val releaseConnect = CountDownLatch(1)
        val pendingConnector = RecordingConnector(blockFirstConnect = releaseConnect)
        val pendingPool = CfWebSocketPool(
            connector = pendingConnector,
            cfDomainHealth = CfDomainHealth(listOf("cf.example")),
        )
        pendingPool.scheduleRefill(2, isMedia = false, baseDomain = "cf.example")
        waitUntil { pendingConnector.tuples.size == 1 }
        pendingPool.closeAll()
        releaseConnect.countDown()
        pendingPool.scheduleRefill(2, isMedia = true, baseDomain = "cf.example")
        Thread.sleep(100)

        assertEquals("pending refill executor should be shut down", 1, pendingConnector.tuples.size)
        assertTrue("ready/pending snapshots should be cleared", pendingPool.readySnapshot().isEmpty() && pendingPool.pendingSnapshot().isEmpty())
    }

    private data class ConnectTuple(
        val targetHost: String,
        val domain: String,
        val path: String,
        val timeoutMs: Int,
    )

    private class RecordingConnector(
        private val blockFirstConnect: CountDownLatch? = null,
    ) : RawWebSocketConnector {
        val tuples = CopyOnWriteArrayList<ConnectTuple>()
        val sockets = CopyOnWriteArrayList<FakeWebSocketBinaryStream>()
        private val attempts = AtomicInteger(0)

        override fun connect(targetHost: String, domain: String, path: String, timeoutMs: Int): WebSocketBinaryStream {
            tuples.add(ConnectTuple(targetHost, domain, path, timeoutMs))
            if (attempts.incrementAndGet() == 1) {
                blockFirstConnect?.await(5, TimeUnit.SECONDS)
            }
            return FakeWebSocketBinaryStream().also { sockets.add(it) }
        }
    }

    private class FakeWebSocketBinaryStream : WebSocketBinaryStream {
        var closed = false
        override fun send(data: ByteArray) = Unit
        override fun sendBatch(parts: List<ByteArray>) = Unit
        override fun recv(): ByteArray? = null
        override fun close() { closed = true }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for condition")
    }
}