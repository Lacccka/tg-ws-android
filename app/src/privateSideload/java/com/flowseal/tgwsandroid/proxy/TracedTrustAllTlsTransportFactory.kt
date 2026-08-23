package com.flowseal.tgwsandroid.proxy

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Diagnostic-only transport that mirrors production/upstream trust-all TLS while
 * exposing the exact TCP/TLS stages. The [host] may be a literal IPv4/IPv6
 * address; [tlsServerName] remains the logical CF-proxy hostname used for SNI.
 */
internal object TracedTrustAllTlsTransportFactory {
    fun traced(trace: (String) -> Unit): RawWebSocket.TransportFactory =
        RawWebSocket.TransportFactory { host, port, tlsServerName, timeoutMs ->
            connect(host, port, tlsServerName, timeoutMs, trace)
        }

    private fun connect(
        host: String,
        port: Int,
        tlsServerName: String,
        timeoutMs: Int,
        traceSink: (String) -> Unit,
    ): RawWebSocket.Transport {
        val startedNs = System.nanoTime()
        fun trace(message: String) {
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            runCatching { traceSink("+${elapsedMs}ms $message") }
        }

        val rawSocket = Socket().apply {
            soTimeout = timeoutMs
            tcpNoDelay = true
        }

        try {
            trace("TCP_CONNECT start target=$host:$port timeoutMs=$timeoutMs sni=$tlsServerName")
            rawSocket.connect(InetSocketAddress(host, port), timeoutMs)
            trace(
                "TCP_CONNECT finished local=${rawSocket.localAddress.hostAddress}:${rawSocket.localPort} " +
                    "remote=${rawSocket.inetAddress.hostAddress}:${rawSocket.port}",
            )

            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(TrustAllX509TrustManager), SecureRandom())
            val sslSocket = context.socketFactory.createSocket(rawSocket, tlsServerName, port, true) as SSLSocket
            sslSocket.soTimeout = timeoutMs
            val parameters = sslSocket.sslParameters
            parameters.serverNames = listOf(SNIHostName(tlsServerName))
            parameters.endpointIdentificationAlgorithm = null
            sslSocket.sslParameters = parameters

            trace("TLS_HANDSHAKE start strategy=production_trust_all sni=$tlsServerName timeoutMs=$timeoutMs")
            val tlsStartedNs = System.nanoTime()
            try {
                sslSocket.startHandshake()
            } catch (timeout: SocketTimeoutException) {
                val elapsedMs = (System.nanoTime() - tlsStartedNs) / 1_000_000
                trace("TLS_TIMEOUT elapsed=${elapsedMs}ms")
                throw TracedTlsDiagnosticTimeoutException(
                    stage = "timeout_during_tls_handshake",
                    details = "TLS handshake timed out after ${elapsedMs}ms",
                    cause = timeout,
                )
            }

            val session = sslSocket.session
            trace("TLS_HANDSHAKE finished protocol=${session.protocol} cipher=${session.cipherSuite}")
            return TracedTransport(sslSocket, traceSink, startedNs)
        } catch (error: Throwable) {
            runCatching { rawSocket.close() }
            throw error
        }
    }

    private object TrustAllX509TrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private class TracedTransport(
        private val socket: SSLSocket,
        private val traceSink: (String) -> Unit,
        private val startedNs: Long,
    ) : RawWebSocket.Transport {
        override val input: InputStream = socket.inputStream
        override val output: OutputStream = socket.outputStream

        override fun flush() {
            socket.outputStream.flush()
            trace("WRITE_FLUSH finished")
        }

        override fun setReadTimeout(timeoutMs: Int) {
            socket.soTimeout = timeoutMs
            trace("READ_TIMEOUT set=${timeoutMs}ms")
        }

        override fun isOpen(): Boolean =
            socket.isConnected && !socket.isClosed && !socket.isInputShutdown && !socket.isOutputShutdown

        override fun close() {
            socket.close()
        }

        private fun trace(message: String) {
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            runCatching { traceSink("+${elapsedMs}ms $message") }
        }
    }
}

internal class TracedTlsDiagnosticTimeoutException(
    val stage: String,
    details: String,
    cause: Throwable? = null,
) : java.io.IOException(details, cause)
