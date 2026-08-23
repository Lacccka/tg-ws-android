package com.flowseal.tgwsandroid

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.flowseal.tgwsandroid.config.AppConfigStore
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngineResult
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * Diagnostic-only probe for mobile networks where TCP to Cloudflare succeeds,
 * but the first TLS ClientHello is black-holed.
 *
 * The activity asks Android's TLS provider to generate a real ClientHello for
 * the configured Worker hostname, then sends the same bytes in several wire
 * layouts to the same Cloudflare edge:
 *
 *  1. one normal write / normal TLS record (control),
 *  2. the unchanged TLS record split into two TCP writes inside the SNI,
 *  3. ClientHello split into two TLS handshake records inside the SNI,
 *  4. ClientHello split into two TLS handshake records after one payload byte.
 *
 * It does not disable certificate validation in production code and it does not
 * change ProxyServer routing. A ServerHello response only proves that the
 * network path stopped black-holing the ClientHello; a full SSLEngine transport
 * would still be required before this can carry Worker WebSocket traffic.
 */
class WorkerTlsFragmentActivity : Activity() {
    private lateinit var networkText: TextView
    private lateinit var resultText: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SE TLS Frag Test"

        networkText = TextView(this).apply { text = "Сеть: ${currentNetworkLabel()}" }
        resultText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Диагностика TLS ClientHello: normal → TCP split → TLS-record split.\n\n" +
                "Тест не меняет основной прокси."
        }
        runButton = Button(this).apply {
            text = "Проверить TLS fragmentation"
            setOnClickListener { runProbe() }
        }
        val copyButton = Button(this).apply {
            text = "Копировать результат"
            setOnClickListener { copyResult() }
        }

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val gap = (10 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@WorkerTlsFragmentActivity).apply {
                text = "TLS ClientHello fragmentation"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@WorkerTlsFragmentActivity).apply {
                text = "Проверяет, отвечает ли Cloudflare на тот же ClientHello после дробления первого TLS handshake."
            }, matchWrap(gap))
            addView(networkText, matchWrap(gap))
            addView(runButton, matchWrap(gap))
            addView(copyButton, matchWrap(gap))
            addView(resultText, matchWrap(gap))
        }
        setContentView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    override fun onResume() {
        super.onResume()
        networkText.text = "Сеть: ${currentNetworkLabel()}"
    }

    private fun runProbe() {
        val config = AppConfigStore.getConfig(applicationContext)
        val domain = config.cfproxyWorkerDomain.firstOrNull().orEmpty().trim()
        if (domain.isBlank()) {
            toast("Сначала сохраните домен в SE Worker Test")
            return
        }

        val network = currentNetworkLabel()
        networkText.text = "Сеть: $network"
        runButton.isEnabled = false

        thread(name = "WorkerTlsFragment") {
            val lines = mutableListOf(
                "SE TLS fragmentation diagnostics",
                "Network: $network",
                "Worker: $domain",
                "",
            )

            try {
                val hello = generateClientHello(domain)
                val sniOffset = indexOf(hello, domain.toByteArray(Charsets.US_ASCII))
                lines += "ClientHello: ${hello.size} bytes; SNI byte offset=${if (sniOffset >= 0) sniOffset else "not-found"}"
                publish(lines)

                val candidates = buildList {
                    runCatching { InetAddress.getAllByName(domain).filterIsInstance<Inet4Address>() }
                        .getOrDefault(emptyList())
                        .forEach { add(it.hostAddress.orEmpty()) }
                    FALLBACK_EDGE_CANDIDATES.forEach(::add)
                }.filter { it.isNotBlank() }.distinct()

                var edge: String? = null
                for (candidate in candidates.take(MAX_EDGE_CANDIDATES)) {
                    val connected = timedAttempt(lines, "TCP edge $candidate") {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(InetAddress.getByName(candidate), 443), CONNECT_TIMEOUT_MS)
                            "local=${socket.localAddress.hostAddress}:${socket.localPort}"
                        }
                    }
                    if (connected) {
                        edge = candidate
                        break
                    }
                }

                if (edge == null) {
                    lines += ""
                    lines += "RESULT: NO CLOUDFLARE EDGE ACCEPTED TCP"
                    return@thread finish(lines)
                }

                lines += ""
                lines += "Using one TCP-reachable edge for all ClientHello variants: $edge"
                publish(lines)

                val normal = observeClientHello(edge, hello, tcpSplitOffset = null)
                appendObservation(lines, "NORMAL ClientHello", normal)
                if (normal.isServerHandshake) {
                    lines += ""
                    lines += "RESULT: RAW SSLEngine CLIENTHELLO GETS A SERVER HANDSHAKE WITHOUT FRAGMENTATION"
                    lines += "The SSLEngine-generated fingerprint differs enough from the SSLSocket path to avoid the observed black-hole."
                    return@thread finish(lines)
                }

                val tcpSniSplitOffset = sniOffset
                    .takeIf { it >= 0 }
                    ?.let { it + (domain.length / 2).coerceAtLeast(1) }
                    ?.coerceIn(1, hello.lastIndex)
                if (tcpSniSplitOffset != null) {
                    val tcpSplit = observeClientHello(edge, hello, tcpSplitOffset = tcpSniSplitOffset)
                    appendObservation(lines, "TCP split inside SNI @$tcpSniSplitOffset", tcpSplit)
                    if (tcpSplit.isServerHandshake) {
                        lines += ""
                        lines += "RESULT: SERVERHELLO AFTER TCP CLIENTHELLO SPLIT"
                        lines += "The same TLS record succeeds when its bytes are emitted in two delayed TCP writes around the SNI."
                        return@thread finish(lines)
                    }
                } else {
                    lines += "SKIP TCP split inside SNI: Worker hostname bytes were not found in generated ClientHello"
                    publish(lines)
                }

                val tlsSniSplit = runCatching { splitHandshakeRecordInsideSni(hello, domain) }.getOrNull()
                if (tlsSniSplit != null) {
                    val fragmented = observeClientHello(edge, tlsSniSplit.bytes, tcpSplitOffset = null)
                    appendObservation(lines, "TLS-record split inside SNI (records=${tlsSniSplit.recordCount})", fragmented)
                    if (fragmented.isServerHandshake) {
                        lines += ""
                        lines += "RESULT: SERVERHELLO AFTER TLS-RECORD SNI FRAGMENTATION"
                        lines += "Strong evidence that TLS ClientHello record fragmentation bypasses the mobile-network black-hole."
                        return@thread finish(lines)
                    }
                } else {
                    lines += "SKIP TLS-record split inside SNI: could not locate SNI inside a handshake record"
                    publish(lines)
                }

                val earlySplit = runCatching { splitFirstHandshakeRecord(hello, 1) }.getOrNull()
                if (earlySplit != null) {
                    val fragmented = observeClientHello(edge, earlySplit.bytes, tcpSplitOffset = null)
                    appendObservation(lines, "TLS-record split after first handshake byte (records=${earlySplit.recordCount})", fragmented)
                    if (fragmented.isServerHandshake) {
                        lines += ""
                        lines += "RESULT: SERVERHELLO AFTER EARLY TLS-RECORD FRAGMENTATION"
                        lines += "TLS record fragmentation changes the DPI outcome even though the ClientHello payload is unchanged."
                        return@thread finish(lines)
                    }
                }

                lines += ""
                lines += "RESULT: CLIENTHELLO FRAGMENTATION DID NOT GET A SERVER HANDSHAKE"
                lines += "Simple TCP splitting and standards-compliant TLS-record splitting are not sufficient on this mobile path."
                lines += "Next class of tests would require stronger desync (fake/disorder/TTL) or a non-Cloudflare relay."
            } catch (error: Throwable) {
                lines += ""
                lines += "RESULT: TEST ERROR: ${errorSummary(error)}"
            } finally {
                finish(lines)
            }
        }
    }

    private fun generateClientHello(domain: String): ByteArray {
        val context = SSLContext.getInstance("TLS")
        context.init(null, null, null)
        val engine = context.createSSLEngine(domain, 443)
        engine.useClientMode = true
        val params = engine.sslParameters
        params.endpointIdentificationAlgorithm = "HTTPS"
        params.serverNames = listOf(SNIHostName(domain))
        engine.sslParameters = params
        engine.beginHandshake()

        while (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            var task = engine.delegatedTask
            while (task != null) {
                task.run()
                task = engine.delegatedTask
            }
        }
        if (engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_WRAP) {
            error("SSLEngine expected NEED_WRAP, got ${engine.handshakeStatus}")
        }

        var output = ByteBuffer.allocate(engine.session.packetBufferSize * 2)
        var result = engine.wrap(EMPTY_BUFFER, output)
        if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
            output = ByteBuffer.allocate(engine.session.packetBufferSize * 4)
            result = engine.wrap(EMPTY_BUFFER, output)
        }
        if (result.status != SSLEngineResult.Status.OK || result.bytesProduced() <= 0) {
            error("SSLEngine failed to produce ClientHello: status=${result.status} produced=${result.bytesProduced()}")
        }
        output.flip()
        return ByteArray(output.remaining()).also(output::get)
    }

    private fun observeClientHello(
        edge: String,
        payload: ByteArray,
        tcpSplitOffset: Int?,
    ): Observation {
        val started = System.nanoTime()
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.soTimeout = RESPONSE_TIMEOUT_MS
            socket.connect(InetSocketAddress(InetAddress.getByName(edge), 443), CONNECT_TIMEOUT_MS)
            val out = socket.getOutputStream()
            if (tcpSplitOffset != null) {
                out.write(payload, 0, tcpSplitOffset)
                out.flush()
                Thread.sleep(TCP_SPLIT_DELAY_MS)
                out.write(payload, tcpSplitOffset, payload.size - tcpSplitOffset)
            } else {
                out.write(payload)
            }
            out.flush()

            val input = socket.getInputStream()
            val header = readFully(input, TLS_HEADER_SIZE)
            val type = header[0].toInt() and 0xff
            val recordLength = ((header[3].toInt() and 0xff) shl 8) or (header[4].toInt() and 0xff)
            val previewLength = recordLength.coerceAtMost(MAX_RESPONSE_PREVIEW)
            val body = if (previewLength > 0) readFully(input, previewLength) else ByteArray(0)
            val handshakeType = if (type == TLS_TYPE_HANDSHAKE && body.isNotEmpty()) body[0].toInt() and 0xff else null
            Observation(
                outcome = Outcome.RESPONSE,
                elapsedMs = elapsedMs(started),
                detail = describeTlsResponse(type, recordLength, handshakeType, body),
                isServerHandshake = type == TLS_TYPE_HANDSHAKE && handshakeType == TLS_HANDSHAKE_SERVER_HELLO,
            )
        } catch (_: SocketTimeoutException) {
            Observation(Outcome.TIMEOUT, elapsedMs(started), "no TLS record received before timeout", false)
        } catch (error: Throwable) {
            Observation(Outcome.ERROR, elapsedMs(started), errorSummary(error), false)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun appendObservation(lines: MutableList<String>, name: String, observation: Observation) {
        val prefix = when (observation.outcome) {
            Outcome.RESPONSE -> "RESP"
            Outcome.TIMEOUT -> "TIMEOUT"
            Outcome.ERROR -> "FAIL"
        }
        lines += "$prefix $name (${observation.elapsedMs}ms): ${observation.detail}"
        publish(lines)
    }

    private fun splitHandshakeRecordInsideSni(bytes: ByteArray, domain: String): FragmentedHello {
        val needle = domain.toByteArray(Charsets.US_ASCII)
        var offset = 0
        while (offset + TLS_HEADER_SIZE <= bytes.size) {
            val length = recordLength(bytes, offset)
            val payloadStart = offset + TLS_HEADER_SIZE
            val payloadEnd = payloadStart + length
            if (payloadEnd > bytes.size) error("truncated TLS record")
            if ((bytes[offset].toInt() and 0xff) == TLS_TYPE_HANDSHAKE) {
                val index = indexOf(bytes, needle, payloadStart, payloadEnd)
                if (index >= 0) {
                    val split = index + (needle.size / 2).coerceAtLeast(1) - payloadStart
                    return splitHandshakeRecordAt(bytes, offset, split)
                }
            }
            offset = payloadEnd
        }
        error("SNI not found in TLS handshake record")
    }

    private fun splitFirstHandshakeRecord(bytes: ByteArray, payloadSplit: Int): FragmentedHello {
        var offset = 0
        while (offset + TLS_HEADER_SIZE <= bytes.size) {
            val length = recordLength(bytes, offset)
            val payloadEnd = offset + TLS_HEADER_SIZE + length
            if (payloadEnd > bytes.size) error("truncated TLS record")
            if ((bytes[offset].toInt() and 0xff) == TLS_TYPE_HANDSHAKE && length > payloadSplit) {
                return splitHandshakeRecordAt(bytes, offset, payloadSplit)
            }
            offset = payloadEnd
        }
        error("no splittable TLS handshake record")
    }

    private fun splitHandshakeRecordAt(bytes: ByteArray, recordOffset: Int, payloadSplit: Int): FragmentedHello {
        val type = bytes[recordOffset]
        val major = bytes[recordOffset + 1]
        val minor = bytes[recordOffset + 2]
        val length = recordLength(bytes, recordOffset)
        require(payloadSplit in 1 until length) { "invalid split=$payloadSplit length=$length" }
        val payloadStart = recordOffset + TLS_HEADER_SIZE
        val payloadEnd = payloadStart + length

        val output = ByteArrayOutputStream(bytes.size + TLS_HEADER_SIZE)
        output.write(bytes, 0, recordOffset)
        writeTlsRecord(output, type, major, minor, bytes, payloadStart, payloadSplit)
        writeTlsRecord(output, type, major, minor, bytes, payloadStart + payloadSplit, length - payloadSplit)
        output.write(bytes, payloadEnd, bytes.size - payloadEnd)
        return FragmentedHello(output.toByteArray(), countTlsRecords(output.toByteArray()))
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

    private fun recordLength(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset + 3].toInt() and 0xff) shl 8) or (bytes[offset + 4].toInt() and 0xff)

    private fun countTlsRecords(bytes: ByteArray): Int {
        var offset = 0
        var count = 0
        while (offset + TLS_HEADER_SIZE <= bytes.size) {
            val length = recordLength(bytes, offset)
            val next = offset + TLS_HEADER_SIZE + length
            if (next > bytes.size) break
            count += 1
            offset = next
        }
        return count
    }

    private fun describeTlsResponse(type: Int, length: Int, handshakeType: Int?, body: ByteArray): String {
        val typeName = when (type) {
            TLS_TYPE_CHANGE_CIPHER_SPEC -> "change_cipher_spec"
            TLS_TYPE_ALERT -> "alert"
            TLS_TYPE_HANDSHAKE -> "handshake"
            TLS_TYPE_APPLICATION_DATA -> "application_data"
            else -> "type-$type"
        }
        val handshake = when (handshakeType) {
            TLS_HANDSHAKE_SERVER_HELLO -> " ServerHello"
            null -> ""
            else -> " handshakeType=$handshakeType"
        }
        val preview = body.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "TLS $typeName$handshake recordLength=$length preview=$preview"
    }

    private fun readFully(input: java.io.InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(bytes, offset, size - offset)
            if (read < 0) error("EOF after $offset/$size bytes")
            offset += read
        }
        return bytes
    }

    private fun timedAttempt(lines: MutableList<String>, name: String, block: () -> String): Boolean {
        var detail = ""
        val elapsed = try {
            measureTimeMillis { detail = block() }
        } catch (error: Throwable) {
            lines += "FAIL $name: ${errorSummary(error)}"
            publish(lines)
            return false
        }
        lines += "OK   $name (${elapsed}ms): $detail"
        publish(lines)
        return true
    }

    private fun indexOf(bytes: ByteArray, needle: ByteArray): Int = indexOf(bytes, needle, 0, bytes.size)

    private fun indexOf(bytes: ByteArray, needle: ByteArray, from: Int, until: Int): Int {
        if (needle.isEmpty()) return from
        val last = until - needle.size
        for (start in from..last) {
            var matches = true
            for (i in needle.indices) {
                if (bytes[start + i] != needle[i]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000L

    private fun finish(lines: MutableList<String>) {
        publish(lines)
        runOnUiThread {
            runButton.isEnabled = true
            networkText.text = "Сеть: ${currentNetworkLabel()}"
        }
    }

    private fun publish(lines: List<String>) {
        runOnUiThread { resultText.text = lines.joinToString("\n") }
    }

    private fun copyResult() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("TLS fragmentation diagnostics", resultText.text))
        toast("Результат скопирован")
    }

    private fun currentNetworkLabel(): String = try {
        val manager = getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        when {
            capabilities == null -> "Нет сети"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Другая сеть"
        }
    } catch (_: Throwable) {
        "Неизвестно"
    }

    private fun errorSummary(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val message = root.message?.replace('\n', ' ')?.take(260).orEmpty()
        return if (message.isBlank()) root::class.java.simpleName else "${root::class.java.simpleName}: $message"
    }

    private fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = topMargin }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private data class FragmentedHello(val bytes: ByteArray, val recordCount: Int)

    private data class Observation(
        val outcome: Outcome,
        val elapsedMs: Long,
        val detail: String,
        val isServerHandshake: Boolean,
    )

    private enum class Outcome { RESPONSE, TIMEOUT, ERROR }

    companion object {
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocate(0)

        private const val CONNECT_TIMEOUT_MS = 2_500
        private const val RESPONSE_TIMEOUT_MS = 4_000
        private const val TCP_SPLIT_DELAY_MS = 120L
        private const val MAX_EDGE_CANDIDATES = 4
        private const val TLS_HEADER_SIZE = 5
        private const val MAX_RESPONSE_PREVIEW = 64

        private const val TLS_TYPE_CHANGE_CIPHER_SPEC = 20
        private const val TLS_TYPE_ALERT = 21
        private const val TLS_TYPE_HANDSHAKE = 22
        private const val TLS_TYPE_APPLICATION_DATA = 23
        private const val TLS_HANDSHAKE_SERVER_HELLO = 2

        private val FALLBACK_EDGE_CANDIDATES = listOf(
            "104.16.123.96",
            "104.24.0.10",
            "188.114.96.10",
        )
    }
}
