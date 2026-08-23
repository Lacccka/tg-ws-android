package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class Socks5ProtocolTest {
    @Test
    fun connectNoAuthUsesRemoteDomainAndPort443() {
        val serverReplies = byteArrayOf(
            0x05, 0x00, // greeting: no auth
            0x05, 0x00, 0x00, 0x01, // CONNECT success, IPv4 bind address
            127, 0, 0, 1,
            0x23, 0x45,
        )
        val output = ByteArrayOutputStream()

        Socks5Protocol.connectNoAuth(
            input = ByteArrayInputStream(serverReplies),
            output = output,
            targetHost = "kws2.pclead.co.uk",
            targetPort = 443,
        )

        val host = "kws2.pclead.co.uk".toByteArray(Charsets.UTF_8)
        val expected = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x05, 0x01, 0x00))
            write(byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()))
            write(host)
            write(byteArrayOf(0x01, 0xBB.toByte()))
        }.toByteArray()

        assertArrayEquals(expected, output.toByteArray())
    }

    @Test
    fun connectNoAuthRejectsAuthenticationRequirement() {
        val output = ByteArrayOutputStream()

        val error = captureIOException {
            Socks5Protocol.connectNoAuth(
                input = ByteArrayInputStream(byteArrayOf(0x05, 0x02)),
                output = output,
                targetHost = "example.com",
                targetPort = 443,
            )
        }

        assertTrue(error.message.orEmpty().contains("unsupported authentication method"))
        assertArrayEquals(byteArrayOf(0x05, 0x01, 0x00), output.toByteArray())
    }

    @Test
    fun connectNoAuthExposesConnectReplyCode() {
        val serverReplies = byteArrayOf(
            0x05, 0x00,
            0x05, 0x05, 0x00, 0x03, // connection refused, domain bind address
            0x03, 'f'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(),
            0x00, 0x00,
        )

        val error = try {
            Socks5Protocol.connectNoAuth(
                input = ByteArrayInputStream(serverReplies),
                output = ByteArrayOutputStream(),
                targetHost = "149.154.167.220",
                targetPort = 443,
            )
            throw AssertionError("Expected Socks5ConnectException")
        } catch (error: Socks5ConnectException) {
            error
        }

        assertEquals(0x05, error.replyCode)
        assertTrue(error.message.orEmpty().contains("connection refused"))
    }

    @Test
    fun connectNoAuthRejectsUnexpectedEof() {
        val error = captureIOException {
            Socks5Protocol.connectNoAuth(
                input = ByteArrayInputStream(byteArrayOf(0x05)),
                output = ByteArrayOutputStream(),
                targetHost = "example.com",
                targetPort = 443,
            )
        }

        assertTrue(error.message.orEmpty().contains("Unexpected EOF"))
    }

    private fun captureIOException(block: () -> Unit): IOException = try {
        block()
        throw AssertionError("Expected IOException")
    } catch (error: IOException) {
        error
    }
}
