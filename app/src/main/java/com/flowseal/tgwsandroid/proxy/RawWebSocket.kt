package com.flowseal.tgwsandroid.proxy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Live WebSocket handshake and frame I/O ported from upstream
 * `third_party/tg-ws-proxy/proxy/raw_websocket.py::RawWebSocket`.
 *
 * The upstream Python implementation opens a TLS connection with hostname checks
 * and certificate verification disabled (`ssl.CERT_NONE`). For parity, the
 * default Kotlin transport below intentionally uses a clearly named trust-all
 * TLS factory while still setting SNI to the supplied `domain` where the JVM
 * supports it. Callers that need platform certificate validation can inject a
 * different [TransportFactory].
 */
class RawWebSocket private constructor(
    private val transport: Transport,
    private val randomProvider: (Int) -> ByteArray,
) {
    private var closed: Boolean = false

    /** Testable blocking stream transport used by the live connection layer. */
    interface Transport {
        val input: InputStream
        val output: OutputStream

        fun flush() = output.flush()

        fun setReadTimeout(timeoutMs: Int) = Unit

        fun close()
    }

    /** Factory abstraction so tests can connect over in-memory streams. */
    fun interface TransportFactory {
        fun connect(
            host: String,
            port: Int,
            domain: String,
            timeoutMs: Int,
        ): Transport
    }

    class WsHandshakeException(
        val statusCode: Int,
        val statusLine: String,
        val headers: Map<String, String> = emptyMap(),
        val location: String? = null,
    ) : IOException("HTTP $statusCode: $statusLine") {
        val isRedirect: Boolean
            get() = statusCode in REDIRECT_STATUS_CODES
    }

    fun send(data: ByteArray) {
        ensureOpen()
        transport.output.write(maskedFrame(RawWebSocketCodec.OP_BINARY, data))
        transport.flush()
    }

    fun sendBatch(parts: List<ByteArray>) {
        ensureOpen()
        for (part in parts) {
            transport.output.write(maskedFrame(RawWebSocketCodec.OP_BINARY, part))
        }
        transport.flush()
    }

    fun recv(): ByteArray? {
        while (!closed) {
            val frame = RawWebSocketCodec.parseFrame(transport.input)
            when (frame.opcode) {
                RawWebSocketCodec.OP_CLOSE -> {
                    closed = true
                    try {
                        val closePayload =
                            if (frame.payload.isEmpty()) {
                                ByteArray(
                                    0,
                                )
                            } else {
                                frame.payload.copyOfRange(0, minOf(2, frame.payload.size))
                            }
                        transport.output.write(maskedFrame(RawWebSocketCodec.OP_CLOSE, closePayload))
                        transport.flush()
                    } catch (_: Exception) {
                        // Match upstream: ignore failures while acknowledging close.
                    }
                    return null
                }

                RawWebSocketCodec.OP_PING -> {
                    try {
                        transport.output.write(maskedFrame(RawWebSocketCodec.OP_PONG, frame.payload))
                        transport.flush()
                    } catch (_: Exception) {
                        // Match upstream: ignore pong write errors and keep reading.
                    }
                }

                RawWebSocketCodec.OP_PONG -> {
                    Unit
                }

                OP_TEXT, RawWebSocketCodec.OP_BINARY -> {
                    return frame.payload
                }

                else -> {
                    Unit
                }
            }
        }
        return null
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            transport.output.write(maskedFrame(RawWebSocketCodec.OP_CLOSE, ByteArray(0)))
            transport.flush()
        } catch (_: Exception) {
            // Match upstream: close is best-effort.
        }
        try {
            transport.close()
        } catch (_: Exception) {
            // Match upstream: ignore socket close failures.
        }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("WebSocket closed")
    }

    private fun maskedFrame(
        opcode: Int,
        payload: ByteArray,
    ): ByteArray = RawWebSocketCodec.buildFrame(opcode, payload, mask = true, randomProvider = randomProvider)

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        private const val OP_TEXT = 0x1
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private val secureRandom = SecureRandom()

        @JvmStatic
        @JvmOverloads
        fun connect(
            host: String,
            domain: String,
            timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
            path: String = "/apiws",
            transportFactory: TransportFactory = TrustAllTlsTransportFactory,
            randomProvider: (Int) -> ByteArray = { length ->
                ByteArray(length).also { secureRandom.nextBytes(it) }
            },
        ): RawWebSocket {
            val boundedTimeoutMs = minOf(timeoutMs, DEFAULT_CONNECT_TIMEOUT_MS)
            val transport = transportFactory.connect(host, 443, domain, boundedTimeoutMs)
            try {
                transport.setReadTimeout(boundedTimeoutMs)
                val wsKey = Base64.getEncoder().encodeToString(randomProvider(16))
                val request =
                    RawWebSocketCodec.buildUpgradeRequest(
                        path = path,
                        domain = domain,
                        secWebSocketKey = wsKey,
                    )
                transport.output.write(request.toByteArray(Charsets.UTF_8))
                transport.flush()

                val rawResponse = readHttpHeaders(transport.input)
                val response = RawWebSocketCodec.parseHandshakeResponse(rawResponse)
                if (response.success) {
                    transport.setReadTimeout(0)
                    return RawWebSocket(transport, randomProvider)
                }

                throw WsHandshakeException(
                    statusCode = response.statusCode,
                    statusLine = response.statusLine,
                    headers = response.headers,
                    location = response.location,
                )
            } catch (error: Exception) {
                try {
                    transport.close()
                } catch (_: Exception) {
                    // Preserve the original connection/handshake error.
                }
                throw error
            }
        }

        private fun readHttpHeaders(input: InputStream): ByteArray {
            val response = ByteArrayOutputStream()
            val line = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                if (next < 0) {
                    if (response.size() == 0 && line.size() == 0) {
                        throw WsHandshakeException(0, "empty response")
                    }
                    break
                }
                response.write(next)
                line.write(next)
                if (next == '\n'.code) {
                    val lineBytes = line.toByteArray()
                    if (lineBytes.contentEquals(byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())) ||
                        lineBytes.contentEquals(byteArrayOf('\n'.code.toByte()))
                    ) {
                        break
                    }
                    line.reset()
                }
            }
            if (response.size() == 0) throw WsHandshakeException(0, "empty response")
            return response.toByteArray()
        }
    }
}

/**
 * Default RawWebSocket TLS transport that mirrors upstream `ssl.CERT_NONE`.
 *
 * This is intentionally trust-all and hostname-verification-free for parity with
 * `tg-ws-proxy`. SNI is still set to `domain` where practical, and basic socket
 * options mirror upstream `set_sock_opts` best-effort behavior.
 */
internal object TrustAllTlsTransportFactory : RawWebSocket.TransportFactory {
    private const val BUFFER_SIZE = 256 * 1024

    override fun connect(
        host: String,
        port: Int,
        domain: String,
        timeoutMs: Int,
    ): RawWebSocket.Transport {
        val socket = trustAllSocketFactory().createSocket() as SSLSocket
        socket.soTimeout = timeoutMs
        socket.tcpNoDelay = true
        try {
            socket.receiveBufferSize = BUFFER_SIZE
            socket.sendBufferSize = BUFFER_SIZE
        } catch (_: Exception) {
            // Best effort, matching upstream set_sock_opts behavior.
        }
        setSni(socket, domain)
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.startHandshake()
        return SocketTransport(socket)
    }

    private fun trustAllSocketFactory(): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(TrustAllX509TrustManager), SecureRandom())
        return context.socketFactory
    }

    private fun setSni(
        socket: SSLSocket,
        domain: String,
    ) {
        try {
            val parameters = socket.sslParameters
            parameters.serverNames = listOf(SNIHostName(domain))
            socket.sslParameters = parameters
        } catch (_: Exception) {
            // Some runtimes reject unusual host names; upstream also treats SNI as practical best effort.
        }
    }

    private object TrustAllX509TrustManager : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = Unit

        override fun checkServerTrusted(
            chain: Array<out X509Certificate>?,
            authType: String?,
        ) = Unit

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

        override fun close() = socket.close()
    }
}
