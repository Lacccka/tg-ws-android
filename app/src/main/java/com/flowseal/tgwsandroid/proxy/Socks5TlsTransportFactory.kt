package com.flowseal.tgwsandroid.proxy

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * RawWebSocket transport that reaches the upstream through a local SOCKS5 proxy.
 *
 * The SOCKS request always uses ATYP=DOMAIN, even for hostname targets that could
 * be resolved locally. This deliberately keeps upstream DNS resolution inside the
 * tunnel (for example an embedded Xray VLESS/REALITY outbound) instead of leaking
 * or depending on the mobile network's resolver.
 *
 * TLS is established only after SOCKS CONNECT succeeds and mirrors the existing
 * upstream-parity trust-all behavior used by [TrustAllTlsTransportFactory].
 */
internal class Socks5TlsTransportFactory(
    private val socksHost: String,
    private val socksPort: Int,
) : RawWebSocket.TransportFactory {
    init {
        require(socksHost.isNotBlank()) { "SOCKS host must not be blank" }
        require(socksPort in 1..65535) { "SOCKS port out of range: $socksPort" }
    }

    override fun connect(
        host: String,
        port: Int,
        tlsServerName: String,
        timeoutMs: Int,
    ): RawWebSocket.Transport {
        val rawSocket = Socket().apply {
            soTimeout = timeoutMs
            tcpNoDelay = true
        }

        try {
            rawSocket.connect(InetSocketAddress(socksHost, socksPort), timeoutMs)
            Socks5Protocol.connectNoAuth(
                input = rawSocket.getInputStream(),
                output = rawSocket.getOutputStream(),
                targetHost = host,
                targetPort = port,
            )

            val sslSocket = trustAllContext().socketFactory.createSocket(
                rawSocket,
                tlsServerName,
                port,
                true,
            ) as SSLSocket
            sslSocket.soTimeout = timeoutMs
            sslSocket.tcpNoDelay = true
            setSni(sslSocket, tlsServerName)
            sslSocket.startHandshake()
            return SocketTransport(sslSocket)
        } catch (error: Throwable) {
            runCatching { rawSocket.close() }
            throw error
        }
    }

    private fun trustAllContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(TrustAllX509TrustManager), SecureRandom())
    }

    private fun setSni(socket: SSLSocket, tlsServerName: String) {
        try {
            val parameters = socket.sslParameters
            parameters.serverNames = listOf(SNIHostName(tlsServerName))
            parameters.endpointIdentificationAlgorithm = null
            socket.sslParameters = parameters
        } catch (_: Exception) {
            // Preserve upstream parity: custom SNI is best effort for unusual names.
        }
    }

    private object TrustAllX509TrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class SocketTransport(
        private val socket: Socket,
    ) : RawWebSocket.Transport {
        override val input: InputStream = socket.getInputStream()
        override val output: OutputStream = socket.getOutputStream()

        override fun setReadTimeout(timeoutMs: Int) {
            socket.soTimeout = timeoutMs
        }

        override fun isOpen(): Boolean =
            socket.isConnected && !socket.isClosed && !socket.isInputShutdown && !socket.isOutputShutdown

        override fun close() = socket.close()
    }
}

internal object Socks5Protocol {
    private const val VERSION = 0x05
    private const val METHOD_NO_AUTH = 0x00
    private const val METHOD_NO_ACCEPTABLE = 0xFF
    private const val COMMAND_CONNECT = 0x01
    private const val ADDRESS_DOMAIN = 0x03

    fun connectNoAuth(
        input: InputStream,
        output: OutputStream,
        targetHost: String,
        targetPort: Int,
    ) {
        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
        require(hostBytes.isNotEmpty()) { "SOCKS target host must not be blank" }
        require(hostBytes.size <= 255) { "SOCKS target host is too long: ${hostBytes.size} bytes" }
        require(targetPort in 1..65535) { "SOCKS target port out of range: $targetPort" }

        output.write(byteArrayOf(VERSION.toByte(), 0x01, METHOD_NO_AUTH.toByte()))
        output.flush()

        val greetingVersion = readUnsignedByte(input)
        val selectedMethod = readUnsignedByte(input)
        if (greetingVersion != VERSION) {
            throw IOException("SOCKS5 greeting returned unexpected version: $greetingVersion")
        }
        if (selectedMethod == METHOD_NO_ACCEPTABLE) {
            throw IOException("SOCKS5 proxy rejected all authentication methods")
        }
        if (selectedMethod != METHOD_NO_AUTH) {
            throw IOException("SOCKS5 proxy requires unsupported authentication method: $selectedMethod")
        }

        val request = ByteArray(7 + hostBytes.size)
        request[0] = VERSION.toByte()
        request[1] = COMMAND_CONNECT.toByte()
        request[2] = 0x00
        request[3] = ADDRESS_DOMAIN.toByte()
        request[4] = hostBytes.size.toByte()
        hostBytes.copyInto(request, destinationOffset = 5)
        request[5 + hostBytes.size] = ((targetPort ushr 8) and 0xFF).toByte()
        request[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
        output.write(request)
        output.flush()

        val responseVersion = readUnsignedByte(input)
        val responseCode = readUnsignedByte(input)
        readUnsignedByte(input) // reserved
        val addressType = readUnsignedByte(input)

        if (responseVersion != VERSION) {
            throw IOException("SOCKS5 CONNECT returned unexpected version: $responseVersion")
        }

        consumeBoundAddress(input, addressType)
        readExact(input, 2) // bound port

        if (responseCode != 0x00) {
            throw Socks5ConnectException(responseCode, replyDescription(responseCode))
        }
    }

    private fun consumeBoundAddress(input: InputStream, addressType: Int) {
        when (addressType) {
            0x01 -> readExact(input, 4)
            0x03 -> readExact(input, readUnsignedByte(input))
            0x04 -> readExact(input, 16)
            else -> throw IOException("SOCKS5 CONNECT returned unsupported address type: $addressType")
        }
    }

    private fun readUnsignedByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw IOException("Unexpected EOF during SOCKS5 handshake")
        return value
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) throw IOException("Unexpected EOF during SOCKS5 handshake")
            offset += read
        }
        return bytes
    }

    private fun replyDescription(code: Int): String = when (code) {
        0x01 -> "general SOCKS server failure"
        0x02 -> "connection not allowed by ruleset"
        0x03 -> "network unreachable"
        0x04 -> "host unreachable"
        0x05 -> "connection refused"
        0x06 -> "TTL expired"
        0x07 -> "command not supported"
        0x08 -> "address type not supported"
        else -> "unknown SOCKS5 error"
    }
}

internal class Socks5ConnectException(
    val replyCode: Int,
    description: String,
) : IOException("SOCKS5 CONNECT failed: 0x${replyCode.toString(16).padStart(2, '0')} ($description)")
