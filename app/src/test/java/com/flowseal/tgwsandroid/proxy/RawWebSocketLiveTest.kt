package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class RawWebSocketLiveTest {
    @Test
    fun connectWritesUpgradeRequestAndReturnsConnectionOn101() {
        val fake = FakeTransportFactory(httpResponse(101, "Switching Protocols"))
        val ws = connect(fake)

        assertNotNull(ws)
        assertEquals(listOf(ConnectionCall("edge.example", 443, "ws.example", 5_000)), fake.calls)
        assertFalse(fake.transport.closed)
        assertTrue(ws.isUsableForPool())
        assertEquals(expectedUpgradeRequest(), fake.transport.outputBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun redirectResponseThrowsHandshakeExceptionWithLocation() {
        val fake =
            FakeTransportFactory(
                "HTTP/1.1 302 Found\r\nLocation: https://redirect.example/apiws\r\n\r\n".toByteArray(Charsets.UTF_8),
            )

        val error = assertThrowsHandshake { connect(fake) }

        assertEquals(302, error.statusCode)
        assertEquals("HTTP/1.1 302 Found", error.statusLine)
        assertEquals("https://redirect.example/apiws", error.location)
        assertTrue(error.isRedirect)
        assertEquals("https://redirect.example/apiws", error.headers["location"])
        assertTrue(fake.transport.closed)
    }

    @Test
    fun emptyResponseThrowsHandshakeException() {
        val fake = FakeTransportFactory(ByteArray(0))

        val error = assertThrowsHandshake { connect(fake) }

        assertEquals(0, error.statusCode)
        assertEquals("empty response", error.statusLine)
        assertFalse(error.isRedirect)
        assertTrue(fake.transport.closed)
    }

    @Test
    fun malformedResponseThrowsHandshakeException() {
        val fake = FakeTransportFactory("not an http response\r\n\r\n".toByteArray(Charsets.UTF_8))

        val error = assertThrowsHandshake { connect(fake) }

        assertEquals(0, error.statusCode)
        assertEquals("not an http response", error.statusLine)
        assertTrue(fake.transport.closed)
    }

    @Test
    fun sendWritesMaskedBinaryFrame() {
        val fake = FakeTransportFactory(httpResponse(101, "Switching Protocols"))
        val ws = connect(fake)
        val requestLength = fake.transport.outputBytes().size

        ws.send("hello".toByteArray(Charsets.UTF_8))

        val frame = fake.transport.outputBytes().copyOfRange(requestLength, fake.transport.outputBytes().size)
        val parsed = RawWebSocketCodec.parseFrame(frame)
        assertEquals(RawWebSocketCodec.OP_BINARY, parsed.opcode)
        assertTrue(parsed.masked)
        assertTrue(parsed.fin)
        assertEquals("hello", parsed.payload.toString(Charsets.UTF_8))
        assertEquals(2, fake.transport.flushCount)
    }

    @Test
    fun sendBatchWritesAllFramesThenFlushesOnce() {
        val fake = FakeTransportFactory(httpResponse(101, "Switching Protocols"))
        val ws = connect(fake)
        val requestLength = fake.transport.outputBytes().size

        ws.sendBatch(listOf("one".bytes(), "two".bytes(), "three".bytes()))

        val frames = ByteArrayInputStream(fake.transport.outputBytes().copyOfRange(requestLength, fake.transport.outputBytes().size))
        val first = RawWebSocketCodec.parseFrame(frames)
        val second = RawWebSocketCodec.parseFrame(frames)
        val third = RawWebSocketCodec.parseFrame(frames)
        assertEquals("one", first.payload.toString(Charsets.UTF_8))
        assertEquals("two", second.payload.toString(Charsets.UTF_8))
        assertEquals("three", third.payload.toString(Charsets.UTF_8))
        assertTrue(first.masked)
        assertTrue(second.masked)
        assertTrue(third.masked)
        assertTrue(first.fin)
        assertTrue(second.fin)
        assertTrue(third.fin)
        assertEquals(0, frames.available())
        assertEquals(2, fake.transport.flushCount)
    }

    @Test
    fun recvReturnsBinaryPayload() {
        val fake =
            FakeTransportFactory(
                httpResponse(101, "Switching Protocols") +
                    RawWebSocketCodec.buildFrame(
                        opcode = RawWebSocketCodec.OP_BINARY,
                        data = "payload".bytes(),
                        mask = false,
                    ),
            )
        val ws = connect(fake)

        assertEquals("payload", ws.recv()!!.toString(Charsets.UTF_8))
    }

    @Test
    fun recvReassemblesFragmentedBinaryMessage() {
        val fake =
            FakeTransportFactory(
                httpResponse(101, "Switching Protocols") +
                    RawWebSocketCodec.buildFrame(
                        opcode = RawWebSocketCodec.OP_BINARY,
                        data = "AAA".bytes(),
                        fin = false,
                    ) +
                    RawWebSocketCodec.buildFrame(
                        opcode = RawWebSocketCodec.OP_CONT,
                        data = "BBB".bytes(),
                        fin = false,
                    ) +
                    RawWebSocketCodec.buildFrame(
                        opcode = RawWebSocketCodec.OP_CONT,
                        data = "CCC".bytes(),
                        fin = true,
                    ),
            )
        val ws = connect(fake)

        assertEquals("AAABBBCCC", ws.recv()!!.toString(Charsets.UTF_8))
    }

    @Test
    fun recvKeepsFragmentStateAcrossControlFrames() {
        val pingPayload = "ping-between-fragments".bytes()
        val fake =
            FakeTransportFactory(
                httpResponse(101, "Switching Protocols") +
                    RawWebSocketCodec.buildFrame(
                        opcode = RawWebSocketCodec.OP_BINARY,
                        data = "left-".bytes(),
                        fin = false,
                    ) +
                    RawWebSocketCodec.buildFrame(
                        opcode = RawWebSocketCodec.OP_PING,
                        data = pingPayload,
                    ) +
                    RawWebSocketCodec.buildFrame(
                        opcode = RawWebSocketCodec.OP_CONT,
                        data = "right".bytes(),
                        fin = true,
                    ),
            )
        val ws = connect(fake)
        val requestLength = fake.transport.outputBytes().size

        assertEquals("left-right", ws.recv()!!.toString(Charsets.UTF_8))

        val pongBytes = fake.transport.outputBytes().copyOfRange(requestLength, fake.transport.outputBytes().size)
        val pong = RawWebSocketCodec.parseFrame(pongBytes)
        assertEquals(RawWebSocketCodec.OP_PONG, pong.opcode)
        assertArrayEquals(pingPayload, pong.payload)
    }

    @Test
    fun recvAcceptsTextFramesAsPayload() {
        val fake =
            FakeTransportFactory(
                httpResponse(101, "Switching Protocols") +
                    RawWebSocketCodec.buildFrame(
                        opcode = 0x1,
                        data = "text-payload".bytes(),
                        mask = false,
                    ),
            )
        val ws = connect(fake)

        assertEquals("text-payload", ws.recv()!!.toString(Charsets.UTF_8))
    }

    @Test
    fun recvRespondsToPingWithMaskedPongThenReturnsNextPayload() {
        val pingPayload = "ping".bytes()
        val binaryPayload = "after-ping".bytes()
        val fake =
            FakeTransportFactory(
                httpResponse(101, "Switching Protocols") +
                    RawWebSocketCodec.buildFrame(RawWebSocketCodec.OP_PING, pingPayload, mask = false) +
                    RawWebSocketCodec.buildFrame(RawWebSocketCodec.OP_BINARY, binaryPayload, mask = false),
            )
        val ws = connect(fake)
        val requestLength = fake.transport.outputBytes().size

        assertArrayEquals(binaryPayload, ws.recv())

        val pongBytes = fake.transport.outputBytes().copyOfRange(requestLength, fake.transport.outputBytes().size)
        val pong = RawWebSocketCodec.parseFrame(pongBytes)
        assertEquals(RawWebSocketCodec.OP_PONG, pong.opcode)
        assertTrue(pong.masked)
        assertArrayEquals(pingPayload, pong.payload)
    }

    @Test
    fun recvReturnsNullOnCloseFrameAndAcknowledgesClose() {
        val fake =
            FakeTransportFactory(
                httpResponse(101, "Switching Protocols") +
                    RawWebSocketCodec.buildFrame(
                        opcode = RawWebSocketCodec.OP_CLOSE,
                        data = byteArrayOf(0x03, 0xE8.toByte(), 0x55),
                        mask = false,
                    ),
            )
        val ws = connect(fake)
        val requestLength = fake.transport.outputBytes().size

        assertNull(ws.recv())
        assertFalse(ws.isUsableForPool())

        val closeAckBytes = fake.transport.outputBytes().copyOfRange(requestLength, fake.transport.outputBytes().size)
        val closeAck = RawWebSocketCodec.parseFrame(closeAckBytes)
        assertEquals(RawWebSocketCodec.OP_CLOSE, closeAck.opcode)
        assertTrue(closeAck.masked)
        assertArrayEquals(byteArrayOf(0x03, 0xE8.toByte()), closeAck.payload)

        ws.close()
        assertTrue(fake.transport.closed)
    }

    @Test
    fun closeSendsMaskedCloseFrameAndClosesTransport() {
        val fake = FakeTransportFactory(httpResponse(101, "Switching Protocols"))
        val ws = connect(fake)
        val requestLength = fake.transport.outputBytes().size

        ws.close()

        val closeBytes = fake.transport.outputBytes().copyOfRange(requestLength, fake.transport.outputBytes().size)
        val closeFrame = RawWebSocketCodec.parseFrame(closeBytes)
        assertEquals(RawWebSocketCodec.OP_CLOSE, closeFrame.opcode)
        assertTrue(closeFrame.masked)
        assertEquals(0, closeFrame.payload.size)
        assertTrue(fake.transport.closed)
        assertFalse(ws.isUsableForPool())
    }

    @Test
    fun connectUsesBoundedHandshakeTimeoutThenResetsIdleReadTimeout() {
        val fake = FakeTransportFactory(httpResponse(101, "Switching Protocols"))

        RawWebSocket.connect(
            host = "edge.example",
            domain = "ws.example",
            timeoutMs = 30_000,
            transportFactory = fake,
            randomProvider = deterministicRandomProvider(),
        )

        assertEquals(5_000, fake.calls.single().timeoutMs)
        assertEquals(listOf(5_000, 0), fake.transport.readTimeouts)
    }

    @Test
    fun failedHandshakeKeepsBoundedReadTimeoutAndClosesTransport() {
        val fake = FakeTransportFactory(httpResponse(403, "Forbidden"))

        assertThrowsHandshake {
            RawWebSocket.connect(
                host = "edge.example",
                domain = "ws.example",
                timeoutMs = 30_000,
                transportFactory = fake,
                randomProvider = deterministicRandomProvider(),
            )
        }

        assertEquals(listOf(5_000), fake.transport.readTimeouts)
        assertTrue(fake.transport.closed)
    }

    private fun connect(fake: FakeTransportFactory): RawWebSocket =
        RawWebSocket.connect(
            host = "edge.example",
            domain = "ws.example",
            transportFactory = fake,
            randomProvider = deterministicRandomProvider(),
        )

    private fun deterministicRandomProvider(): (Int) -> ByteArray =
        { length ->
            when (length) {
                16 -> ByteArray(16) { it.toByte() }
                4 -> byteArrayOf(0x01, 0x23, 0x45, 0x67)
                else -> error("unexpected random length $length")
            }
        }

    private fun expectedUpgradeRequest(): String =
        RawWebSocketCodec.buildUpgradeRequest(
            path = "/apiws",
            domain = "ws.example",
            secWebSocketKey = "AAECAwQFBgcICQoLDA0ODw==",
        )

    private fun httpResponse(
        statusCode: Int,
        reason: String,
    ): ByteArray = "HTTP/1.1 $statusCode $reason\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n".toByteArray(Charsets.UTF_8)

    private fun String.bytes(): ByteArray = toByteArray(Charsets.UTF_8)

    private fun assertThrowsHandshake(block: () -> Unit): RawWebSocket.WsHandshakeException {
        try {
            block()
        } catch (error: RawWebSocket.WsHandshakeException) {
            return error
        }
        throw AssertionError("Expected WsHandshakeException")
    }

    private data class ConnectionCall(
        val host: String,
        val port: Int,
        val domain: String,
        val timeoutMs: Int,
    )

    private class FakeTransportFactory(
        responseBytes: ByteArray,
    ) : RawWebSocket.TransportFactory {
        val transport = FakeTransport(responseBytes)
        val calls = mutableListOf<ConnectionCall>()

        override fun connect(
            host: String,
            port: Int,
            domain: String,
            timeoutMs: Int,
        ): RawWebSocket.Transport {
            calls += ConnectionCall(host, port, domain, timeoutMs)
            return transport
        }
    }

    private class FakeTransport(
        responseBytes: ByteArray,
    ) : RawWebSocket.Transport {
        override val input: InputStream = ByteArrayInputStream(responseBytes)
        private val outputBuffer = ByteArrayOutputStream()
        override val output: OutputStream = outputBuffer
        var flushCount: Int = 0
            private set
        var closed: Boolean = false
            private set
        val readTimeouts = mutableListOf<Int>()

        override fun setReadTimeout(timeoutMs: Int) {
            readTimeouts += timeoutMs
        }

        override fun flush() {
            flushCount += 1
            output.flush()
        }

        override fun isOpen(): Boolean = !closed

        override fun close() {
            closed = true
        }

        fun outputBytes(): ByteArray = outputBuffer.toByteArray()
    }
}
