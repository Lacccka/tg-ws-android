package com.flowseal.tgwsandroid.proxy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ProxyServerFrontingTest {
    @Test
    fun coldDirectTimeoutUsesFrontingWithoutPoisoningDirectFailureState() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val logs = CopyOnWriteArrayList<String>()
        val connector = TimeoutThenFrontingConnector()
        val bridgeRuns = CopyOnWriteArrayList<String>()
        val proxy =
            ProxyServer(
                config = baseConfig(),
                serverTransport = server,
                webSocketConnector = connector,
                bridgeRunner = ProxyBridgeRunner { _, _, _, _, _ -> bridgeRuns += "bridge" },
                randomBytes = DeterministicRandomBytes,
                logger = ProxyLogger { logs += it },
            )

        proxy.start()
        server.enqueue(client)
        waitUntil("fronted bridge") { bridgeRuns.isNotEmpty() }
        waitUntil("client closed") { client.closed }
        proxy.stop()

        assertEquals(1, connector.normalCalls.size)
        assertEquals("149.154.167.220", connector.normalCalls.single().targetHost)
        assertEquals("kws2.web.telegram.org", connector.normalCalls.single().domain)
        assertEquals(ProxyServer.DEFAULT_WS_PATH, connector.normalCalls.single().path)

        assertEquals(1, connector.frontedCalls.size)
        val fronted = connector.frontedCalls.single()
        assertEquals("149.154.167.220", fronted.targetHost)
        assertEquals("kws2.web.telegram.org", fronted.domain)
        assertEquals(DirectFrontingConnector.DEFAULT_FRONTING_SNI, fronted.sniHost)
        assertEquals(ProxyServer.DEFAULT_WS_PATH, fronted.path)

        val stats = proxy.stats()
        assertEquals("direct-cold", stats.lastRouteUsed)
        assertEquals(0L, stats.directTimeouts)
        assertEquals(0L, stats.directTargetIpCooldownSets)
        assertTrue(connector.frontedSocket.sent.isNotEmpty())
        assertTrue(logs.any { it.contains("fronting fallback attempt") && it.contains("sprinthost.ru") })
        assertTrue(logs.any { it.contains("fronting success") })
        assertTrue(logs.any { it.contains("fronted=true") })
    }

    @Test
    fun nonTimeoutDirectFailureDoesNotUseFronting() {
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        val server = FakeTcpServerTransport()
        val connector = NonTimeoutFailureConnector()
        val proxy =
            ProxyServer(
                config = baseConfig(),
                serverTransport = server,
                webSocketConnector = connector,
                bridgeRunner = ProxyBridgeRunner { _, _, _, _, _ -> },
                randomBytes = DeterministicRandomBytes,
            )

        proxy.start()
        server.enqueue(client)
        waitUntil("client closed after direct failures") { client.closed }
        proxy.stop()

        assertEquals(2, connector.normalCalls)
        assertEquals(0, connector.frontedCalls)
        assertFalse(proxy.stats().lastRouteUsed == "direct-cold")
    }

    private fun baseConfig(): ProxyServerConfig =
        ProxyServerConfig(
            host = "127.0.0.1",
            port = 1443,
            secretHex = "0123456789abcdeffedcba9876543210",
            dcRedirects = mapOf(2 to "149.154.167.220"),
            bufferSizeBytes = 4096,
            poolSize = 0,
            cfproxyEnabled = false,
            routeMode = NetworkRouteMode.DIRECT_FIRST,
            networkStatus = "Wi-Fi",
        )

    private fun handshakeVector(name: String): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("handshake_vectors.json")
        val vectors = JSONObject(stream.reader(Charsets.UTF_8).readText()).getJSONArray("vectors")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            if (vector.getString("name") == name) return vector
        }
        throw AssertionError("Missing handshake vector $name")
    }

    private fun waitUntil(
        message: String,
        predicate: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $message")
    }

    private data class ConnectCall(
        val targetHost: String,
        val domain: String,
        val path: String,
        val timeoutMs: Int,
        val sniHost: String? = null,
    )

    private class TimeoutThenFrontingConnector : RawWebSocketConnector {
        val normalCalls = CopyOnWriteArrayList<ConnectCall>()
        val frontedCalls = CopyOnWriteArrayList<ConnectCall>()
        val frontedSocket = FakeWebSocketBinaryStream()

        override fun connect(
            targetHost: String,
            domain: String,
            path: String,
            timeoutMs: Int,
        ): WebSocketBinaryStream {
            normalCalls += ConnectCall(targetHost, domain, path, timeoutMs)
            throw SocketTimeoutException("planned normal direct timeout")
        }

        override fun connectWithSni(
            targetHost: String,
            domain: String,
            path: String,
            timeoutMs: Int,
            sniHost: String,
        ): WebSocketBinaryStream {
            frontedCalls += ConnectCall(targetHost, domain, path, timeoutMs, sniHost)
            return frontedSocket
        }
    }

    private class NonTimeoutFailureConnector : RawWebSocketConnector {
        var normalCalls: Int = 0
        var frontedCalls: Int = 0

        override fun connect(
            targetHost: String,
            domain: String,
            path: String,
            timeoutMs: Int,
        ): WebSocketBinaryStream {
            normalCalls += 1
            throw java.io.IOException("planned non-timeout failure")
        }

        override fun connectWithSni(
            targetHost: String,
            domain: String,
            path: String,
            timeoutMs: Int,
            sniHost: String,
        ): WebSocketBinaryStream {
            frontedCalls += 1
            return FakeWebSocketBinaryStream()
        }
    }

    private class FakeWebSocketBinaryStream : WebSocketBinaryStream {
        val sent = CopyOnWriteArrayList<ByteArray>()
        @Volatile var closed: Boolean = false

        override fun send(data: ByteArray) {
            sent += data.copyOf()
        }

        override fun sendBatch(parts: List<ByteArray>) {
            parts.forEach(::send)
        }

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
                val client = queue.poll(50, TimeUnit.MILLISECONDS)
                if (client != null) return client
            }
            return null
        }

        override fun close() {
            closed.set(true)
        }

        fun enqueue(client: TcpClientTransport) {
            queue.put(client)
        }
    }

    private class FakeTcpClientTransport(
        bytes: ByteArray,
    ) : TcpClientTransport {
        private val input = bytes.copyOf()
        private var offset: Int = 0
        @Volatile var closed: Boolean = false

        override val remoteLabel: String = "fronting-test-client"

        override fun readExact(byteCount: Int): ByteArray? {
            if (offset + byteCount > input.size) return null
            return input.copyOfRange(offset, offset + byteCount).also { offset += byteCount }
        }

        override fun read(bufferSize: Int): ByteArray? {
            if (offset >= input.size) return null
            val end = minOf(input.size, offset + bufferSize)
            return input.copyOfRange(offset, end).also { offset = end }
        }

        override fun write(data: ByteArray) = Unit

        override fun close() {
            closed = true
        }
    }

    private object DeterministicRandomBytes : RelayInit.RandomBytes {
        override fun nextBytes(length: Int): ByteArray = ByteArray(length) { index -> (index + 1).toByte() }
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
