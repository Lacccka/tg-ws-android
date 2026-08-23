package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class RawWebSocketTimeoutParityTest {
    @Test
    fun callerMayRequestUpstreamTenSecondTimeout() {
        val factory = CapturingFactory()
        val ws = RawWebSocket.connect(
            host = "203.0.113.10",
            domain = "kws2.example.test",
            timeoutMs = 10_000,
            transportFactory = factory,
            randomProvider = { ByteArray(it) },
        )
        ws.close()

        assertEquals(10_000, factory.connectTimeoutMs)
        assertEquals(listOf(10_000, 0), factory.readTimeouts)
    }

    @Test
    fun timeoutAboveUpstreamCeilingIsCappedAtTenSeconds() {
        val factory = CapturingFactory()
        val ws = RawWebSocket.connect(
            host = "203.0.113.10",
            domain = "kws2.example.test",
            timeoutMs = 30_000,
            transportFactory = factory,
            randomProvider = { ByteArray(it) },
        )
        ws.close()

        assertEquals(RawWebSocket.MAX_CONNECT_TIMEOUT_MS, factory.connectTimeoutMs)
        assertEquals(listOf(RawWebSocket.MAX_CONNECT_TIMEOUT_MS, 0), factory.readTimeouts)
    }

    private class CapturingFactory : RawWebSocket.TransportFactory {
        var connectTimeoutMs: Int = -1
        val readTimeouts = mutableListOf<Int>()

        override fun connect(
            host: String,
            port: Int,
            tlsServerName: String,
            timeoutMs: Int,
        ): RawWebSocket.Transport {
            connectTimeoutMs = timeoutMs
            return object : RawWebSocket.Transport {
                override val input: InputStream = ByteArrayInputStream(
                    "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray(),
                )
                override val output: OutputStream = ByteArrayOutputStream()

                override fun setReadTimeout(timeoutMs: Int) {
                    readTimeouts += timeoutMs
                }

                override fun close() = Unit
            }
        }
    }
}
