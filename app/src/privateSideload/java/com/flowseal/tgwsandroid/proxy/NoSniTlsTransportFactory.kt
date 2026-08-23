package com.flowseal.tgwsandroid.proxy

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/**
 * Diagnostic-only TLS transport that deliberately omits SNI from ClientHello.
 *
 * The HTTP Host header is still produced by [RawWebSocket] from the configured
 * Worker hostname. This lets the private-sideload probe test whether Cloudflare
 * can route a Worker request after a no-SNI TLS handshake, while keeping the
 * blocked workers.dev hostname out of the TLS ClientHello seen by the network.
 *
 * System certificate-chain validation remains enabled. HTTPS hostname binding is
 * intentionally disabled because there is no hostname in TLS to bind against.
 * This transport must not be used by production routing without a separate
 * security review.
 */
internal object NoSniTlsTransportFactory {
    fun traced(trace: (String) -> Unit): RawWebSocket.TransportFactory =
        RawWebSocket.TransportFactory { host, port, tlsServerName, timeoutMs ->
            connect(host, port, tlsServerName, timeoutMs, trace)
        }

    private fun connect(
        host: String,
        port: Int,
        requestedHost: String,
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
                "remote=${rawSocket.inetAddress.hostAddress}:${rawSocket.port} requestedHost=$requestedHost",
        )

        try {
            val context = SSLContext.getInstance("TLS")
            context.init(null, null, null)
            val sslSocket = context.socketFactory.createSocket(rawSocket, host, port, true) as SSLSocket
            sslSocket.soTimeout = timeoutMs

            val parameters = sslSocket.sslParameters
            parameters.serverNames = emptyList()
            parameters.endpointIdentificationAlgorithm = null
            sslSocket.sslParameters = parameters

            trace(
                "CLIENT_HELLO strategy=no_sni serverNames=empty " +
                    "systemChainTrust=enabled hostnameVerification=disabled",
            )
            val handshakeStartedNs = System.nanoTime()
            try {
                sslSocket.startHandshake()
            } catch (timeout: SocketTimeoutException) {
                val elapsedMs = (System.nanoTime() - handshakeStartedNs) / 1_000_000
                trace("TIMEOUT stage=timeout_during_no_sni_tls_handshake elapsed=${elapsedMs}ms")
                throw NoSniTlsDiagnosticTimeoutException(
                    stage = "timeout_during_no_sni_tls_handshake",
                    details = "no-SNI TLS handshake timed out after ${elapsedMs}ms",
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

            return NoSniTransport(sslSocket, traceSink, startedNs)
        } catch (error: Throwable) {
            runCatching { rawSocket.close() }
            throw error
        }
    }

    private class NoSniTransport(
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

internal class NoSniTlsDiagnosticTimeoutException(
    val stage: String,
    details: String,
    cause: Throwable? = null,
) : IOException(details, cause)
