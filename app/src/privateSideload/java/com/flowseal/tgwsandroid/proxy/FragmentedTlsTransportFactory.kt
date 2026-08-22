package com.flowseal.tgwsandroid.proxy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/**
 * Diagnostic-only TLS transport that keeps Android's platform TLS stack and
 * certificate validation, but rewrites the first ClientHello from one TLS
 * handshake record into two standards-compliant handshake records split inside
 * the SNI hostname.
 *
 * The peer receives exactly the ClientHello handshake bytes produced by
 * Android's [SSLEngine]; only the TLS record boundaries change. This matches the
 * mobile-network experiment where the normal ClientHello was black-holed while
 * the record-fragmented ClientHello received a ServerHello.
 */
internal object FragmentedTlsTransportFactory : RawWebSocket.TransportFactory {
    private const val BUFFER_SIZE = 256 * 1024

    override fun connect(
        host: String,
        port: Int,
        tlsServerName: String,
        timeoutMs: Int,
    ): RawWebSocket.Transport = connectInternal(host, port, tlsServerName, timeoutMs, null)

    /**
     * Returns the same diagnostic transport with a synchronous trace callback.
     * The callback is best-effort and never changes transport behavior.
     */
    fun traced(trace: (String) -> Unit): RawWebSocket.TransportFactory =
        RawWebSocket.TransportFactory { host, port, tlsServerName, timeoutMs ->
            connectInternal(host, port, tlsServerName, timeoutMs, trace)
        }

    private fun connectInternal(
        host: String,
        port: Int,
        tlsServerName: String,
        timeoutMs: Int,
        trace: ((String) -> Unit)?,
    ): RawWebSocket.Transport {
        val socket = Socket()
        socket.soTimeout = timeoutMs
        socket.tcpNoDelay = true
        try {
            socket.receiveBufferSize = BUFFER_SIZE
            socket.sendBufferSize = BUFFER_SIZE
        } catch (_: Exception) {
            // Best effort only.
        }
        socket.connect(InetSocketAddress(host, port), timeoutMs)

        try {
            val context = SSLContext.getInstance("TLS")
            context.init(null, null, null)
            val engine = context.createSSLEngine(tlsServerName, port)
            engine.useClientMode = true
            val parameters = engine.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            parameters.serverNames = listOf(SNIHostName(tlsServerName))
            engine.sslParameters = parameters

            return EngineTransport(socket, engine, tlsServerName, timeoutMs, trace).also {
                it.handshake()
            }
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    private class EngineTransport(
        private val socket: Socket,
        private val engine: SSLEngine,
        private val tlsServerName: String,
        timeoutMs: Int,
        private val traceSink: ((String) -> Unit)?,
    ) : RawWebSocket.Transport {
        private val rawInput = socket.getInputStream()
        private val rawOutput = socket.getOutputStream()
        private val readLock = Any()
        private val writeLock = Any()
        private val traceStartedNs = System.nanoTime()
        private val incomingRecordTraceBuffer = ByteArrayOutputStream()

        private var netInput = ByteBuffer.allocate(engine.session.packetBufferSize * 2)
        private var plainInput = ByteBuffer.allocate(engine.session.applicationBufferSize * 2).apply { flip() }
        private var totalNetworkBytesReceived = 0L
        private var incomingRecordCount = 0
        private var applicationWriteCount = 0
        private var applicationReadCount = 0
        private var handshakeComplete = false

        override val input: InputStream = EngineInputStream()
        override val output: OutputStream = EngineOutputStream()

        init {
            socket.soTimeout = timeoutMs
            trace(
                "CONNECT local=${socket.localAddress.hostAddress}:${socket.localPort} " +
                    "remote=${socket.inetAddress.hostAddress}:${socket.port} sni=$tlsServerName timeout=${timeoutMs}ms",
            )
        }

        fun handshake() {
            engine.beginHandshake()
            var firstWrap = true
            var status = engine.handshakeStatus
            trace("HANDSHAKE begin status=$status")

            while (status != SSLEngineResult.HandshakeStatus.FINISHED &&
                status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
            ) {
                when (status) {
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()

                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        val networkBytes = wrapHandshakeBytes()
                        val bytesToSend = if (firstWrap) {
                            firstWrap = false
                            val fragmented = fragmentClientHelloInsideSni(networkBytes, tlsServerName)
                            trace(
                                "CLIENT_HELLO original=${fragmented.originalSize} bytes " +
                                    "sniOffset=${fragmented.sniOffset} splitOffset=${fragmented.splitOffset} " +
                                    "fragmented=${fragmented.bytes.size} bytes records=${fragmented.recordLengths.size} " +
                                    "recordLengths=${fragmented.recordLengths.joinToString(",")}",
                            )
                            fragmented.bytes
                        } else {
                            networkBytes
                        }
                        synchronized(writeLock) {
                            rawOutput.write(bytesToSend)
                            rawOutput.flush()
                        }
                        trace("SEND handshake bytes=${bytesToSend.size}")
                    }

                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> unwrapHandshakeData()

                    SSLEngineResult.HandshakeStatus.FINISHED,
                    SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING,
                    -> Unit
                }
                status = engine.handshakeStatus
            }

            handshakeComplete = true
            val session = engine.session
            val peer = runCatching { session.peerPrincipal.name }.getOrElse { "unavailable" }
            trace(
                "HANDSHAKE FINISHED protocol=${session.protocol} cipher=${session.cipherSuite} " +
                    "peer=$peer networkBytesReceived=$totalNetworkBytesReceived records=$incomingRecordCount",
            )
        }

        override fun setReadTimeout(timeoutMs: Int) {
            socket.soTimeout = timeoutMs
            trace("READ_TIMEOUT set=${timeoutMs}ms")
        }

        override fun isOpen(): Boolean =
            socket.isConnected && !socket.isClosed && !socket.isInputShutdown && !socket.isOutputShutdown && !engine.isInboundDone

        override fun close() {
            runCatching { engine.closeOutbound() }
            socket.close()
        }

        private fun wrapHandshakeBytes(): ByteArray {
            var output = ByteBuffer.allocate(engine.session.packetBufferSize * 2)
            while (true) {
                val result = engine.wrap(EMPTY_BUFFER, output)
                trace(
                    "WRAP status=${result.status} hs=${result.handshakeStatus} " +
                        "consumed=${result.bytesConsumed()} produced=${result.bytesProduced()}",
                )
                when (result.status) {
                    SSLEngineResult.Status.OK -> {
                        if (result.bytesProduced() <= 0) {
                            if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks()
                            if (result.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                                return ByteArray(0)
                            }
                            continue
                        }
                        output.flip()
                        return ByteArray(output.remaining()).also(output::get)
                    }

                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        output = growWriteBuffer(output, engine.session.packetBufferSize)
                    }

                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> error("unexpected BUFFER_UNDERFLOW while wrapping TLS handshake")
                    SSLEngineResult.Status.CLOSED -> throw IOException("TLS engine closed while wrapping handshake")
                }
            }
        }

        private fun unwrapHandshakeData() {
            while (true) {
                netInput.flip()
                val appOutput = ByteBuffer.allocate(engine.session.applicationBufferSize)
                val result = engine.unwrap(netInput, appOutput)
                netInput.compact()
                trace(
                    "UNWRAP status=${result.status} hs=${result.handshakeStatus} " +
                        "consumed=${result.bytesConsumed()} produced=${result.bytesProduced()} buffered=${netInput.position()}",
                )

                when (result.status) {
                    SSLEngineResult.Status.OK -> {
                        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks()
                        if (result.bytesConsumed() > 0 || result.bytesProduced() > 0 ||
                            result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP ||
                            result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED ||
                            result.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                        ) {
                            return
                        }
                    }

                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        throw IOException("TLS handshake application buffer overflow")
                    }

                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> Unit
                    SSLEngineResult.Status.CLOSED -> throw IOException("TLS engine closed during handshake")
                }

                readMoreNetworkBytes()
            }
        }

        private fun readMoreNetworkBytes() {
            if (!netInput.hasRemaining()) {
                val grown = ByteBuffer.allocate(netInput.capacity() * 2)
                netInput.flip()
                grown.put(netInput)
                netInput = grown
            }
            val chunk = ByteArray(minOf(16 * 1024, netInput.remaining()))
            val read = try {
                rawInput.read(chunk)
            } catch (_: SocketTimeoutException) {
                val stage = when {
                    handshakeComplete -> "timeout_waiting_http_response"
                    totalNetworkBytesReceived == 0L -> "timeout_waiting_first_tls_record"
                    else -> "timeout_during_tls_handshake"
                }
                val details =
                    "stage=$stage handshakeStatus=${engine.handshakeStatus} " +
                        "networkBytesReceived=$totalNetworkBytesReceived recordsReceived=$incomingRecordCount " +
                        "bufferedEncrypted=${netInput.position()}"
                trace("TIMEOUT $details")
                throw FragmentedTlsDiagnosticTimeoutException(stage, details)
            }
            if (read < 0) throw IOException("TLS peer closed connection")
            totalNetworkBytesReceived += read
            netInput.put(chunk, 0, read)
            traceIncomingTlsRecords(chunk, read)
            trace("RECV network bytes=$read total=$totalNetworkBytesReceived buffered=${netInput.position()}")
        }

        private fun traceIncomingTlsRecords(chunk: ByteArray, length: Int) {
            incomingRecordTraceBuffer.write(chunk, 0, length)
            val bytes = incomingRecordTraceBuffer.toByteArray()
            var offset = 0
            while (offset + TLS_HEADER_SIZE <= bytes.size) {
                val payloadLength = recordLength(bytes, offset)
                val recordEnd = offset + TLS_HEADER_SIZE + payloadLength
                if (recordEnd > bytes.size) break
                incomingRecordCount += 1
                val type = bytes[offset].toInt() and 0xff
                val major = bytes[offset + 1].toInt() and 0xff
                val minor = bytes[offset + 2].toInt() and 0xff
                trace(
                    "RECV record #$incomingRecordCount type=$type(${tlsContentTypeName(type)}) " +
                        "version=$major.$minor length=$payloadLength",
                )
                offset = recordEnd
            }
            if (offset > 0) {
                incomingRecordTraceBuffer.reset()
                incomingRecordTraceBuffer.write(bytes, offset, bytes.size - offset)
            }
            if (incomingRecordTraceBuffer.size() > BUFFER_SIZE) {
                trace("RECV record parser reset after ${incomingRecordTraceBuffer.size()} buffered bytes")
                incomingRecordTraceBuffer.reset()
            }
        }

        private fun runTasks() {
            var taskCount = 0
            var task = engine.delegatedTask
            while (task != null) {
                taskCount += 1
                trace("TASK #$taskCount start")
                task.run()
                trace("TASK #$taskCount done status=${engine.handshakeStatus}")
                task = engine.delegatedTask
            }
            if (taskCount == 0) trace("TASK none status=${engine.handshakeStatus}")
        }

        private inner class EngineInputStream : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                val read = read(one, 0, 1)
                return if (read < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                synchronized(readLock) {
                    while (true) {
                        if (plainInput.hasRemaining()) {
                            val count = minOf(length, plainInput.remaining())
                            plainInput.get(buffer, offset, count)
                            return count
                        }
                        if (engine.isInboundDone) return -1
                        fillPlainInput()
                    }
                }
            }
        }

        private fun fillPlainInput() {
            var appOutput = ByteBuffer.allocate(engine.session.applicationBufferSize * 2)
            while (true) {
                netInput.flip()
                val result = engine.unwrap(netInput, appOutput)
                netInput.compact()
                trace(
                    "APP_UNWRAP status=${result.status} hs=${result.handshakeStatus} " +
                        "consumed=${result.bytesConsumed()} produced=${result.bytesProduced()} buffered=${netInput.position()}",
                )

                when (result.status) {
                    SSLEngineResult.Status.OK -> {
                        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks()
                        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                            emitPostHandshakeWrap()
                        }
                        if (result.bytesProduced() > 0) {
                            appOutput.flip()
                            applicationReadCount += 1
                            trace(
                                "APP RECV #$applicationReadCount plaintext=${appOutput.remaining()} " +
                                    "preview=${textPreview(appOutput)}",
                            )
                            plainInput = appOutput
                            return
                        }
                        if (engine.isInboundDone) {
                            plainInput = ByteBuffer.allocate(0)
                            return
                        }
                    }

                    SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                        appOutput = growWriteBuffer(appOutput, engine.session.applicationBufferSize)
                    }

                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> Unit
                    SSLEngineResult.Status.CLOSED -> {
                        plainInput = ByteBuffer.allocate(0)
                        return
                    }
                }

                readMoreNetworkBytes()
            }
        }

        private fun emitPostHandshakeWrap() {
            synchronized(writeLock) {
                while (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    val bytes = wrapHandshakeBytes()
                    if (bytes.isNotEmpty()) {
                        rawOutput.write(bytes)
                        trace("SEND post-handshake bytes=${bytes.size}")
                    }
                }
                rawOutput.flush()
            }
        }

        private inner class EngineOutputStream : OutputStream() {
            override fun write(value: Int) {
                write(byteArrayOf(value.toByte()), 0, 1)
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (length == 0) return
                synchronized(writeLock) {
                    if (handshakeComplete) {
                        applicationWriteCount += 1
                        trace(
                            "APP SEND #$applicationWriteCount plaintext=$length " +
                                "preview=${textPreview(buffer, offset, length)}",
                        )
                    }
                    val source = ByteBuffer.wrap(buffer, offset, length)
                    while (source.hasRemaining()) {
                        var networkOutput = ByteBuffer.allocate(engine.session.packetBufferSize * 2)
                        var result = engine.wrap(source, networkOutput)
                        if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                            networkOutput = ByteBuffer.allocate(engine.session.packetBufferSize * 4)
                            result = engine.wrap(source, networkOutput)
                        }
                        trace(
                            "APP_WRAP status=${result.status} hs=${result.handshakeStatus} " +
                                "consumed=${result.bytesConsumed()} produced=${result.bytesProduced()}",
                        )
                        when (result.status) {
                            SSLEngineResult.Status.OK -> {
                                networkOutput.flip()
                                if (networkOutput.hasRemaining()) {
                                    val encrypted = ByteArray(networkOutput.remaining()).also(networkOutput::get)
                                    rawOutput.write(encrypted)
                                    trace("SEND application TLS bytes=${encrypted.size}")
                                }
                                if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks()
                            }

                            SSLEngineResult.Status.CLOSED -> throw IOException("TLS engine closed while writing")
                            SSLEngineResult.Status.BUFFER_OVERFLOW -> throw IOException("TLS packet buffer overflow while writing")
                            SSLEngineResult.Status.BUFFER_UNDERFLOW -> throw IOException("unexpected TLS BUFFER_UNDERFLOW while writing")
                        }
                    }
                }
            }

            override fun flush() {
                synchronized(writeLock) { rawOutput.flush() }
            }
        }

        private fun trace(message: String) {
            val sink = traceSink ?: return
            val elapsedMs = (System.nanoTime() - traceStartedNs) / 1_000_000
            runCatching { sink("+${elapsedMs}ms $message") }
        }
    }

    private data class FragmentedClientHello(
        val bytes: ByteArray,
        val originalSize: Int,
        val sniOffset: Int,
        val splitOffset: Int,
        val recordLengths: List<Int>,
    )

    private fun fragmentClientHelloInsideSni(bytes: ByteArray, domain: String): FragmentedClientHello {
        val needle = domain.toByteArray(Charsets.US_ASCII)
        var offset = 0
        while (offset + TLS_HEADER_SIZE <= bytes.size) {
            val length = recordLength(bytes, offset)
            val payloadStart = offset + TLS_HEADER_SIZE
            val payloadEnd = payloadStart + length
            if (payloadEnd > bytes.size) throw IOException("truncated TLS record in ClientHello")
            if ((bytes[offset].toInt() and 0xff) == TLS_TYPE_HANDSHAKE) {
                val index = indexOf(bytes, needle, payloadStart, payloadEnd)
                if (index >= 0) {
                    val payloadSplit = index + (needle.size / 2).coerceAtLeast(1) - payloadStart
                    val fragmented = splitHandshakeRecordAt(bytes, offset, payloadSplit)
                    return FragmentedClientHello(
                        bytes = fragmented,
                        originalSize = bytes.size,
                        sniOffset = index,
                        splitOffset = payloadStart + payloadSplit,
                        recordLengths = tlsRecordLengths(fragmented),
                    )
                }
            }
            offset = payloadEnd
        }
        throw IOException("Worker SNI was not found inside generated ClientHello")
    }

    private fun splitHandshakeRecordAt(bytes: ByteArray, recordOffset: Int, payloadSplit: Int): ByteArray {
        val type = bytes[recordOffset]
        val major = bytes[recordOffset + 1]
        val minor = bytes[recordOffset + 2]
        val length = recordLength(bytes, recordOffset)
        require(payloadSplit in 1 until length) { "invalid TLS split=$payloadSplit length=$length" }
        val payloadStart = recordOffset + TLS_HEADER_SIZE
        val payloadEnd = payloadStart + length

        val output = ByteArrayOutputStream(bytes.size + TLS_HEADER_SIZE)
        output.write(bytes, 0, recordOffset)
        writeTlsRecord(output, type, major, minor, bytes, payloadStart, payloadSplit)
        writeTlsRecord(output, type, major, minor, bytes, payloadStart + payloadSplit, length - payloadSplit)
        output.write(bytes, payloadEnd, bytes.size - payloadEnd)
        return output.toByteArray()
    }

    private fun writeTlsRecord(
        output: ByteArrayOutputStream,
        type: Byte,
        major: Byte,
        minor: Byte,
        source: ByteArray,
        sourceOffset: Int,
        length: Int,
    ) {
        output.write(type.toInt() and 0xff)
        output.write(major.toInt() and 0xff)
        output.write(minor.toInt() and 0xff)
        output.write((length ushr 8) and 0xff)
        output.write(length and 0xff)
        output.write(source, sourceOffset, length)
    }

    private fun indexOf(bytes: ByteArray, needle: ByteArray, start: Int, end: Int): Int {
        if (needle.isEmpty()) return start
        val last = end - needle.size
        for (index in start..last) {
            var matches = true
            for (needleIndex in needle.indices) {
                if (bytes[index + needleIndex] != needle[needleIndex]) {
                    matches = false
                    break
                }
            }
            if (matches) return index
        }
        return -1
    }

    private fun recordLength(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset + 3].toInt() and 0xff) shl 8) or (bytes[offset + 4].toInt() and 0xff)

    private fun tlsRecordLengths(bytes: ByteArray): List<Int> {
        val result = mutableListOf<Int>()
        var offset = 0
        while (offset + TLS_HEADER_SIZE <= bytes.size) {
            val length = recordLength(bytes, offset)
            val recordEnd = offset + TLS_HEADER_SIZE + length
            if (recordEnd > bytes.size) break
            result += length
            offset = recordEnd
        }
        return result
    }

    private fun tlsContentTypeName(type: Int): String = when (type) {
        20 -> "change_cipher_spec"
        21 -> "alert"
        22 -> "handshake"
        23 -> "application_data"
        else -> "unknown"
    }

    private fun textPreview(buffer: ByteArray, offset: Int, length: Int): String {
        val count = minOf(length, PREVIEW_BYTES)
        val text = String(buffer, offset, count, Charsets.ISO_8859_1)
        return sanitizePreview(text) + if (length > count) "..." else ""
    }

    private fun textPreview(buffer: ByteBuffer): String {
        val copy = buffer.asReadOnlyBuffer()
        val count = minOf(copy.remaining(), PREVIEW_BYTES)
        val bytes = ByteArray(count)
        copy.get(bytes)
        return textPreview(bytes, 0, bytes.size) + if (copy.hasRemaining()) "..." else ""
    }

    private fun sanitizePreview(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> if (ch.code in 32..126) append(ch) else append('.')
            }
        }
    }

    private fun growWriteBuffer(buffer: ByteBuffer, minimumExtra: Int): ByteBuffer {
        val grown = ByteBuffer.allocate(maxOf(buffer.capacity() * 2, buffer.capacity() + minimumExtra))
        buffer.flip()
        grown.put(buffer)
        return grown
    }

    private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocate(0)
    private const val TLS_HEADER_SIZE = 5
    private const val TLS_TYPE_HANDSHAKE = 22
    private const val PREVIEW_BYTES = 160
}

internal class FragmentedTlsDiagnosticTimeoutException(
    val stage: String,
    details: String,
) : IOException(details)
