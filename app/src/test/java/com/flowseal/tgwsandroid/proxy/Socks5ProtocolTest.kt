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

        val host = "kws2.pclead.co.uk".toByteArray(Charsets.US_ASCII)
        val expected = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x05, 0x01, 0x00))
            write(byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()))
            write(host)
            write(byteArrayOf(0x01, 0xBB.toByte()))
        }.toByteArray()

        assertArrayEquals(expected, output.toByteArray())
    }

    @Test
    fun connectWithCredentialsUsesRfc1929BeforeConnect() {
        val serverReplies = byteArrayOf(
            0x05, 0x02, // SOCKS greeting selects username/password
            0x01, 0x00, // RFC 1929 auth success
            0x05, 0x00, 0x00, 0x01, // CONNECT success
            127, 0, 0, 1,
            0x00, 0x00,
        )
        val output = ByteArrayOutputStream()
        val credentials = Socks5Credentials("tgws", "secret")

        Socks5Protocol.connect(
            input = ByteArrayInputStream(serverReplies),
            output = output,
            targetHost = "149.154.167.220",
            targetPort = 443,
            credentials = credentials,
        )

        val expected = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x05, 0x01, 0x02))
            write(byteArrayOf(0x01, 0x04, 't'.code.toByte(), 'g'.code.toByte(), 'w'.code.toByte(), 's'.code.toByte()))
            write(byteArrayOf(0x06, 's'.code.toByte(), 'e'.code.toByte(), 'c'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 't'.code.toByte()))
            write(Socks5Protocol.buildConnectRequest("149.154.167.220", 443))
        }.toByteArray()

        assertArrayEquals(expected, output.toByteArray())
    }

    @Test
    fun connectWithCredentialsRejectsFailedAuthentication() {
        val error = captureIOException {
            Socks5Protocol.connect(
                input = ByteArrayInputStream(byteArrayOf(0x05, 0x02, 0x01, 0x01)),
                output = ByteArrayOutputStream(),
                targetHost = "example.com",
                targetPort = 443,
                credentials = Socks5Credentials("user", "wrong"),
            )
        }

        assertTrue(error.message.orEmpty().contains("authentication failed"))
    }

    @Test
    fun buildConnectRequestEncodesTelegramIpv4Numerically() {
        assertArrayEquals(
            byteArrayOf(
                0x05, 0x01, 0x00, 0x01,
                149.toByte(), 154.toByte(), 167.toByte(), 220.toByte(),
                0x01, 0xBB.toByte(),
            ),
            Socks5Protocol.buildConnectRequest("149.154.167.220", 443),
        )
    }

    @Test
    fun buildConnectRequestEncodesIpv6Numerically() {
        val request = Socks5Protocol.buildConnectRequest("2606:4700::6810:84e5", 443)

        assertEquals(0x05, request[0].toInt() and 0xFF)
        assertEquals(0x01, request[1].toInt() and 0xFF)
        assertEquals(0x04, request[3].toInt() and 0xFF)
        assertEquals(22, request.size) // 4-byte header + 16-byte IPv6 + 2-byte port
        assertEquals(0x01, request[20].toInt() and 0xFF)
        assertEquals(0xBB, request[21].toInt() and 0xFF)
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

        assertTrue(error.message.orEmpty().contains("unexpected authentication method"))
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
