package com.flowseal.tgwsandroid.proxy

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Credentials for an RFC 1929 SOCKS5 username/password exchange. */
data class Socks5Credentials(
    val username: String,
    val password: String,
) {
    init {
        validatePart("username", username)
        validatePart("password", password)
    }

    companion object {
        private fun validatePart(label: String, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.isNotEmpty()) { "SOCKS $label must not be blank" }
            require(bytes.size <= 255) { "SOCKS $label is too long: ${bytes.size} bytes" }
        }
    }
}

/**
 * RawWebSocket transport that reaches the upstream through a local SOCKS5 proxy.
 *
 * Hostname targets are encoded as ATYP=DOMAIN so upstream DNS resolution happens
 * inside the tunnel (for example an embedded Xray VLESS/REALITY outbound), not on
 * the mobile network. Literal IPv4/IPv6 targets are encoded as numeric SOCKS
 * addresses and therefore need no DNS at all.
 *
 * TLS is established only after SOCKS CONNECT succeeds and mirrors the existing
 * upstream-parity trust-all behavior used by [TrustAllTlsTransportFactory].
 */
internal class Socks5TlsTransportFactory(
    private val socksHost: String,
    private val socksPort: Int,
    private val credentials: Socks5Credentials? = null,
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
            Socks5Protocol.connect(
                input = rawSocket.getInputStream(),
                output = rawSocket.getOutputStream(),
                targetHost = host,
                targetPort = port,
                credentials = credentials,
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
    private const val METHOD_USERNAME_PASSWORD = 0x02
    private const val METHOD_NO_ACCEPTABLE = 0xFF
    private const val AUTH_VERSION = 0x01
    private const val COMMAND_CONNECT = 0x01
    private const val ADDRESS_IPV4 = 0x01
    private const val ADDRESS_DOMAIN = 0x03
    private const val ADDRESS_IPV6 = 0x04

    fun connectNoAuth(
        input: InputStream,
        output: OutputStream,
        targetHost: String,
        targetPort: Int,
    ) = connect(input, output, targetHost, targetPort, credentials = null)

    fun connect(
        input: InputStream,
        output: OutputStream,
        targetHost: String,
        targetPort: Int,
        credentials: Socks5Credentials?,
    ) {
        require(targetHost.isNotBlank()) { "SOCKS target host must not be blank" }
        require(targetPort in 1..65535) { "SOCKS target port out of range: $targetPort" }

        val requestedMethod = if (credentials == null) METHOD_NO_AUTH else METHOD_USERNAME_PASSWORD
        output.write(byteArrayOf(VERSION.toByte(), 0x01, requestedMethod.toByte()))
        output.flush()

        val greetingVersion = readUnsignedByte(input)
        val selectedMethod = readUnsignedByte(input)
        if (greetingVersion != VERSION) {
            throw IOException("SOCKS5 greeting returned unexpected version: $greetingVersion")
        }
        if (selectedMethod == METHOD_NO_ACCEPTABLE) {
            throw IOException("SOCKS5 proxy rejected all authentication methods")
        }
        if (selectedMethod != requestedMethod) {
            throw IOException("SOCKS5 proxy selected unexpected authentication method: $selectedMethod")
        }

        if (credentials != null) authenticateUsernamePassword(input, output, credentials)

        output.write(buildConnectRequest(targetHost, targetPort))
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

    private fun authenticateUsernamePassword(
        input: InputStream,
        output: OutputStream,
        credentials: Socks5Credentials,
    ) {
        val username = credentials.username.toByteArray(Charsets.UTF_8)
        val password = credentials.password.toByteArray(Charsets.UTF_8)
        val request = ByteArray(3 + username.size + password.size)
        request[0] = AUTH_VERSION.toByte()
        request[1] = username.size.toByte()
        username.copyInto(request, destinationOffset = 2)
        val passwordLengthIndex = 2 + username.size
        request[passwordLengthIndex] = password.size.toByte()
        password.copyInto(request, destinationOffset = passwordLengthIndex + 1)
        output.write(request)
        output.flush()

        val version = readUnsignedByte(input)
        val status = readUnsignedByte(input)
        if (version != AUTH_VERSION) {
            throw IOException("SOCKS5 username/password auth returned unexpected version: $version")
        }
        if (status != 0x00) {
            throw IOException("SOCKS5 username/password authentication failed")
        }
    }

    internal fun buildConnectRequest(targetHost: String, targetPort: Int): ByteArray {
        require(targetHost.isNotBlank()) { "SOCKS target host must not be blank" }
        require(targetPort in 1..65535) { "SOCKS target port out of range: $targetPort" }

        val address = encodeTargetAddress(targetHost)
        val request = ByteArray(4 + address.payload.size + 2)
        request[0] = VERSION.toByte()
        request[1] = COMMAND_CONNECT.toByte()
        request[2] = 0x00
        request[3] = address.type.toByte()
        address.payload.copyInto(request, destinationOffset = 4)
        request[request.lastIndex - 1] = ((targetPort ushr 8) and 0xFF).toByte()
        request[request.lastIndex] = (targetPort and 0xFF).toByte()
        return request
    }

    private fun encodeTargetAddress(host: String): EncodedAddress {
        parseIpv4(host)?.let { return EncodedAddress(ADDRESS_IPV4, it) }

        if (host.contains(':')) {
            val ipv6 = runCatching { InetAddress.getByName(host).address }
                .getOrNull()
                ?.takeIf { it.size == 16 }
            if (ipv6 != null) return EncodedAddress(ADDRESS_IPV6, ipv6)
        }

        val asciiHost = runCatching { IDN.toASCII(host) }
            .getOrElse { throw IllegalArgumentException("Invalid SOCKS target hostname: $host", it) }
        val hostBytes = asciiHost.toByteArray(Charsets.US_ASCII)
        require(hostBytes.isNotEmpty()) { "SOCKS target host must not be blank" }
        require(hostBytes.size <= 255) { "SOCKS target host is too long: ${hostBytes.size} bytes" }
        return EncodedAddress(
            ADDRESS_DOMAIN,
            byteArrayOf(hostBytes.size.toByte()) + hostBytes,
        )
    }

    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || part.any { !it.isDigit() }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    private fun consumeBoundAddress(input: InputStream, addressType: Int) {
        when (addressType) {
            ADDRESS_IPV4 -> readExact(input, 4)
            ADDRESS_DOMAIN -> readExact(input, readUnsignedByte(input))
            ADDRESS_IPV6 -> readExact(input, 16)
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

    private data class EncodedAddress(
        val type: Int,
        val payload: ByteArray,
    )
}

internal class Socks5ConnectException(
    val replyCode: Int,
    description: String,
) : IOException("SOCKS5 CONNECT failed: 0x${replyCode.toString(16).padStart(2, '0')} ($description)")
