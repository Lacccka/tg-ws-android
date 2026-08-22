package com.flowseal.tgwsandroid.proxy

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/**
 * Diagnostic-only baseline TLS transport.
 *
 * Unlike [FragmentedTlsTransportFactory], this sends an ordinary ClientHello.
 * Unlike [NoSniTlsTransportFactory], it keeps the Worker hostname in SNI and
 * enables Android system certificate-chain plus HTTPS hostname verification.
 *
 * The transport exists only so private-sideload diagnostics can compare normal,
 * fragmented, and no-SNI TLS against the same Cloudflare edge and HTTP Host.
 */
internal object SystemSniTlsTransportFactory {
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
            connect(InetSocketAddress(host, port), timeoutMs)
        }
        trace(
            "CONNECT local=${rawSocket.localAddress.hostAddress}:${rawSocket.localPort} " +
                "remote=${rawSocket.inetAddress.hostAddress}:${rawSocket.port} sni=$tlsServerName",
        )

        try {
            val context = SSLContext.getInstance("TLS")
            context.init(null, null, null)
            // The raw socket is already connected to the selected edge IP. Passing
            // tlsServerName here sets the logical peer host used by HTTPS endpoint
            // identification instead of binding verification to the edge IP.
            val sslSocket = context.socketFactory.createSocket(rawSocket, tlsServerName, port, true) as SSLSocket
            sslSocket.soTimeout = timeoutMs

            val parameters = sslSocket.sslParameters
            parameters.serverNames = listOf(SNIHostName(tlsServerName))
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            sslSocket.sslParameters = parameters

            trace(
                "CLIENT_HELLO strategy=normal_sni sni=$tlsServerName " +
                    "systemChainTrust=enabled hostnameVerification=HTTPS",
            )
            val handshakeStartedNs = System.nanoTime()
            try {
                sslSocket.startHandshake()
            } catch (timeout: SocketTimeoutException) {
                val elapsedMs = (System.nanoTime() - handshakeStartedNs) / 1_000_000
                trace("TIMEOUT stage=timeout_during_system_sni_tls_handshake elapsed=${elapsedMs}ms")
                throw SystemSniTlsDiagnosticTimeoutException(
                    stage = "timeout_during_system_sni_tls_handshake",
                    details = "ordinary system-SNI TLS handshake timed out after ${elapsedMs}ms",
                    cause = timeout,
                )
            } catch (handshake: SSLHandshakeException) {
                trace("TLS_HANDSHAKE_FAILED ${handshake.message.orEmpty().replace('\n', ' ').take(240)}")
                throw handshake
            }

            val session = sslSocket.session
            val peer = runCatching { session.peerPrincipal.name }.getOrElse { "unavailable" }
            val peerHost = runCatching { session.peerHost }.getOrElse { "unavailable" }
            trace(
                "HANDSHAKE FINISHED protocol=${session.protocol} cipher=${session.cipherSuite} " +
                    "peerHost=$peerHost peer=$peer",
            )

            return SystemSniTransport(sslSocket, traceSink, startedNs)
        } catch (error: Throwable) {
            runCatching { rawSocket.close() }
            throw error
        }
    }

    private class SystemSniTransport(
        private val socket: SSLSocket,
        private val traceSink: (String) -> Unit,
        private val startedNs: Long,
    ) : RawWebSocket.Transport {
        override val input: InputStream = socket.inputStream
        override val output: OutputStream = socket.outputStream

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

internal class SystemSniTlsDiagnosticTimeoutException(
    val stage: String,
    details: String,
    cause: Throwable? = null,
) : IOException(details, cause)
