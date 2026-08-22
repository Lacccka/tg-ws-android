package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class RawWebSocketSniTest {
    @Test
    fun explicitSniOverridesTlsNameButKeepsTelegramHttpHost() {
        val factory = RecordingTransportFactory()

        RawWebSocket.connect(
            host = "149.154.167.220",
            domain = "kws2.web.telegram.org",
            path = "/apiws",
            sni = "sprinthost.ru",
            transportFactory = factory,
            randomProvider = deterministicRandomProvider(),
        )

        assertEquals("sprinthost.ru", factory.tlsServerName)
        val request = factory.transport.outputBytes().toString(Charsets.UTF_8)
        assertTrue(request.contains("Host: kws2.web.telegram.org\r\n"))
        assertFalse(request.contains("Host: sprinthost.ru\r\n"))
    }

    @Test
    fun omittedSniUsesHttpDomainAsBefore() {
        val factory = RecordingTransportFactory()

        RawWebSocket.connect(
            host = "149.154.167.220",
            domain = "kws2.web.telegram.org",
            transportFactory = factory,
            randomProvider = deterministicRandomProvider(),
        )

        assertEquals("kws2.web.telegram.org", factory.tlsServerName)
    }

    private fun deterministicRandomProvider(): (Int) -> ByteArray = { length -> ByteArray(length) { it.toByte() } }

    private class RecordingTransportFactory : RawWebSocket.TransportFactory {
        val transport = FakeTransport()
        var tlsServerName: String? = null

        override fun connect(
            host: String,
            port: Int,
            tlsServerName: String,
            timeoutMs: Int,
        ): RawWebSocket.Transport {
            this.tlsServerName = tlsServerName
            return transport
        }
    }

    private class FakeTransport : RawWebSocket.Transport {
        override val input: InputStream =
            ByteArrayInputStream(
                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
                    .toByteArray(Charsets.UTF_8),
            )
        private val outputBuffer = ByteArrayOutputStream()
        override val output: OutputStream = outputBuffer

        override fun close() = Unit

        fun outputBytes(): ByteArray = outputBuffer.toByteArray()
    }
}
