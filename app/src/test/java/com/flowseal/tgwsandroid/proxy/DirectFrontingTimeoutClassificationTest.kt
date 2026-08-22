package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

class DirectFrontingTimeoutClassificationTest {
    @Test
    fun timeoutWordInsideNonTimeoutMessageDoesNotEnableFronting() {
        var fronted = false
        val connector =
            DirectFrontingConnector(
                normalConnect = { _, _, _, _ -> throw IOException("planned non-timeout failure") },
                frontedConnect = { _, _, _, _, _ ->
                    fronted = true
                    FakeWebSocket()
                },
            )

        val error = try {
            connector.connect(2, false, "149.154.167.220", "kws2.web.telegram.org", "/apiws", 2_000, 1L)
            throw AssertionError("Expected IOException")
        } catch (error: IOException) {
            error
        }

        assertEquals("planned non-timeout failure", error.message)
        assertFalse(fronted)
    }

    private class FakeWebSocket : WebSocketBinaryStream {
        override fun send(data: ByteArray) = Unit
        override fun sendBatch(parts: List<ByteArray>) = Unit
        override fun recv(): ByteArray? = null
        override fun close() = Unit
    }
}
