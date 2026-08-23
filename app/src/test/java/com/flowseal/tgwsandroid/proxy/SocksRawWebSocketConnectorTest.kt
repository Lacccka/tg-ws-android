package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class SocksRawWebSocketConnectorTest {
    @Test
    fun connectPreservesTargetHostDomainPathAndTimeout() {
        val factory = RecordingTransportFactory()
        val connector = SocksRawWebSocketConnector(factory)

        val stream = connector.connect(
            targetHost = "149.154.167.220",
            domain = "kws2.web.telegram.org",
            path = "/apiws",
            timeoutMs = 10_000,
        )

        assertEquals(
            listOf(Call("149.154.167.220", 443, "kws2.web.telegram.org", 10_000)),
            factory.calls,
        )
        val request = factory.transport.outputBytes().toString(Charsets.UTF_8)
        assertTrue(request.startsWith("GET /apiws HTTP/1.1\r\n"))
        assertTrue(request.contains("Host: kws2.web.telegram.org\r\n"))
        assertTrue(request.contains("Upgrade: websocket\r\n"))
        assertTrue(stream.isUsableForPool())
        stream.close()
    }

    @Test
    fun connectWithSniOverridesOnlyTlsServerName() {
        val factory = RecordingTransportFactory()
        val connector = SocksRawWebSocketConnector(factory)

        val stream = connector.connectWithSni(
            targetHost = "149.154.167.220",
            domain = "kws2.web.telegram.org",
            path = "/apiws",
            timeoutMs = 7_000,
            sniHost = "front.example",
        )

        assertEquals(
            listOf(Call("149.154.167.220", 443, "front.example", 7_000)),
            factory.calls,
        )
        val request = factory.transport.outputBytes().toString(Charsets.UTF_8)
        assertTrue(request.startsWith("GET /apiws HTTP/1.1\r\n"))
        assertTrue(request.contains("Host: kws2.web.telegram.org\r\n"))
        stream.close()
    }

    private data class Call(
        val host: String,
        val port: Int,
        val sni: String,
        val timeoutMs: Int,
    )

    private class RecordingTransportFactory : RawWebSocket.TransportFactory {
        val calls = mutableListOf<Call>()
        val transport = FakeTransport()

        override fun connect(
            host: String,
            port: Int,
            tlsServerName: String,
            timeoutMs: Int,
        ): RawWebSocket.Transport {
            calls += Call(host, port, tlsServerName, timeoutMs)
            return transport
        }
    }

    private class FakeTransport : RawWebSocket.Transport {
        override val input: InputStream = ByteArrayInputStream(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
                .toByteArray(Charsets.UTF_8),
        )
        private val outputBuffer = ByteArrayOutputStream()
        override val output: OutputStream = outputBuffer
        private var closed = false

        override fun isOpen(): Boolean = !closed

        override fun close() {
            closed = true
        }

        fun outputBytes(): ByteArray = outputBuffer.toByteArray()
    }
}
