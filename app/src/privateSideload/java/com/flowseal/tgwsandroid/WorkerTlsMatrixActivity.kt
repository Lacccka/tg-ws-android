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
import com.flowseal.tgwsandroid.proxy.FragmentedTlsDiagnosticTimeoutException
import com.flowseal.tgwsandroid.proxy.FragmentedTlsTransportFactory
import com.flowseal.tgwsandroid.proxy.NoSniTlsDiagnosticTimeoutException
import com.flowseal.tgwsandroid.proxy.NoSniTlsTransportFactory
import com.flowseal.tgwsandroid.proxy.RawWebSocket
import com.flowseal.tgwsandroid.proxy.SystemSniTlsDiagnosticTimeoutException
import com.flowseal.tgwsandroid.proxy.SystemSniTlsTransportFactory
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * Private-sideload A/B/C diagnostic for the Worker mobile path.
 *
 * Each reachable Cloudflare edge is tested sequentially with the same HTTP Host:
 *  A) ordinary platform TLS with SNI and full system hostname verification,
 *  B) the SSLEngine ClientHello whose Worker SNI bytes are split into TLS records,
 *  C) platform TLS without SNI (diagnostic only; hostname verification disabled).
 *
 * Running the modes against the same edge removes DNS/edge selection as a major
 * source of ambiguity in the mobile-network investigation.
 */
class WorkerTlsMatrixActivity : Activity() {
    private lateinit var networkText: TextView
    private lateinit var resultText: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SE Worker TLS Matrix"

        networkText = TextView(this).apply { text = "Сеть: ${currentNetworkLabel()}" }
        resultText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text =
                "Сравнивает на одном Cloudflare edge:\n" +
                    "A. normal SNI TLS\n" +
                    "B. fragmented SNI TLS\n" +
                    "C. no-SNI TLS\n\n" +
                    "Только private-sideload diagnostics; production routing не меняется."
        }
        runButton = Button(this).apply {
            text = "Запустить TLS matrix"
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
            addView(TextView(this@WorkerTlsMatrixActivity).apply {
                text = "Worker TLS comparison matrix"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@WorkerTlsMatrixActivity).apply {
                text = "Сравнительный E2E-прогон normal / fragmented / no-SNI на одинаковом edge IP."
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
        val target = config.dcIp
            .firstOrNull { it.substringBefore(':').trim() == "2" }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "149.154.167.220"
        if (domain.isBlank()) {
            toast("Сначала сохраните домен в SE Worker Test")
            return
        }

        val network = currentNetworkLabel()
        networkText.text = "Сеть: $network"
        runButton.isEnabled = false

        thread(name = "WorkerTlsMatrix") {
            val lines = mutableListOf(
                "SE Worker TLS comparison matrix",
                "Network: $network",
                "Worker HTTP Host: $domain",
                "Target: $target:443",
                "A normal_sni: platform SSLSocket + system trust + HTTPS hostname verification",
                "B fragmented_sni: SSLEngine + system trust + HTTPS hostname verification + sni_byte_records",
                "C no_sni: platform SSLSocket + system chain trust; SNI/hostname verification intentionally absent",
                "Mode order is fixed and all modes use the same selected edge IP.",
                "",
            )

            try {
                val resolved = InetAddress.getAllByName(domain)
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .distinct()
                lines += "Worker DNS IPv4: ${resolved.ifEmpty { listOf("none") }.joinToString(", ")}"
                publish(lines)

                val candidates = (resolved + FALLBACK_EDGE_CANDIDATES).distinct()
                var reachableEdges = 0
                var comparedEdges = 0

                for (edge in candidates) {
                    if (comparedEdges >= MAX_COMPARED_EDGES) break
                    val tcpReachable = stage(lines, "TCP edge $edge") {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(edge, 443), CONNECT_TIMEOUT_MS)
                            "local=${socket.localAddress.hostAddress}:${socket.localPort}"
                        }
                    }
                    if (!tcpReachable) continue
                    reachableEdges += 1
                    comparedEdges += 1

                    lines += ""
                    lines += "===== EDGE $edge ====="
                    publish(lines)

                    val normal = runMode(
                        mode = "A normal_sni",
                        edge = edge,
                        domain = domain,
                        target = target,
                        lines = lines,
                        factoryBuilder = SystemSniTlsTransportFactory::traced,
                        timeoutStage = ::systemSniTimeoutStage,
                    )
                    val fragmented = runMode(
                        mode = "B fragmented_sni",
                        edge = edge,
                        domain = domain,
                        target = target,
                        lines = lines,
                        factoryBuilder = FragmentedTlsTransportFactory::traced,
                        timeoutStage = ::fragmentedTimeoutStage,
                    )
                    val noSni = runMode(
                        mode = "C no_sni",
                        edge = edge,
                        domain = domain,
                        target = target,
                        lines = lines,
                        factoryBuilder = NoSniTlsTransportFactory::traced,
                        timeoutStage = ::noSniTimeoutStage,
                    )

                    lines += "--- edge comparison ---"
                    lines += normal.summaryLine()
                    lines += fragmented.summaryLine()
                    lines += noSni.summaryLine()
                    lines += "VERDICT: ${classify(normal, fragmented, noSni)}"
                    publish(lines)
                }

                lines += ""
                lines += "RESULT: TLS MATRIX COMPLETE"
                lines += when {
                    reachableEdges == 0 -> "No tested Cloudflare IPv4 accepted TCP."
                    comparedEdges == 1 -> "Compared all three TLS modes on 1 TCP-reachable edge."
                    else -> "Compared all three TLS modes on $comparedEdges TCP-reachable edges."
                }
                lines += "Use each edge VERDICT together with the mode traces; do not infer a production workaround from no-SNI success alone."
            } catch (error: Throwable) {
                lines += ""
                lines += "RESULT: TEST ERROR: ${errorSummary(error)}"
            } finally {
                finish(lines)
            }
        }
    }

    private fun runMode(
        mode: String,
        edge: String,
        domain: String,
        target: String,
        lines: MutableList<String>,
        factoryBuilder: ((String) -> Unit) -> RawWebSocket.TransportFactory,
        timeoutStage: (Throwable) -> String?,
    ): ProbeResult {
        lines += "--- $mode via $edge ---"
        publish(lines)

        var tlsCompleted = false
        val transport = factoryBuilder { event ->
            if (event.contains("HANDSHAKE FINISHED")) tlsCompleted = true
            lines += "TRACE [$mode] $event"
            publish(lines)
        }

        val ws: RawWebSocket = try {
            var connected: RawWebSocket? = null
            val elapsed = measureTimeMillis {
                connected = RawWebSocket.connect(
                    host = edge,
                    domain = domain,
                    timeoutMs = CONNECT_TIMEOUT_MS,
                    path = workerProbePath(target),
                    transportFactory = transport,
                )
            }
            lines += "OK   [$mode] TLS + WebSocket upgrade (${elapsed}ms): HTTP 101"
            publish(lines)
            connected ?: error("RawWebSocket.connect returned null")
        } catch (error: Throwable) {
            val wsError = findWsHandshakeException(error)
            if (wsError != null) {
                lines += "RESP [$mode] HTTP ${wsError.statusCode}: ${wsError.statusLine}"
                publish(lines)
                return ProbeResult(
                    mode = mode,
                    tlsCompleted = tlsCompleted,
                    http101 = false,
                    httpStatus = wsError.statusCode,
                    workerOk = false,
                    failureStage = "http_${wsError.statusCode}",
                    error = wsError.statusLine,
                )
            }

            val stage = timeoutStage(error)
            lines += "FAIL [$mode]${stage?.let { " [$it]" }.orEmpty()}: ${errorSummary(error)}"
            publish(lines)
            return ProbeResult(
                mode = mode,
                tlsCompleted = tlsCompleted,
                http101 = false,
                httpStatus = null,
                workerOk = false,
                failureStage = stage,
                error = errorSummary(error),
            )
        }

        try {
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<ByteArray?> { ws.recv() }
                val message = try {
                    future.get(PROBE_RESPONSE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                } catch (timeout: TimeoutException) {
                    throw TimeoutException("no Worker probe response within ${PROBE_RESPONSE_TIMEOUT_MS}ms")
                }
                val text = message?.toString(Charsets.UTF_8).orEmpty()
                val workerOk =
                    text.contains("\"type\":\"worker_tcp_probe\"") &&
                        text.contains("\"ok\":true")
                if (!workerOk) {
                    lines += "FAIL [$mode] unexpected Worker response: ${text.take(240)}"
                    publish(lines)
                    return ProbeResult(
                        mode = mode,
                        tlsCompleted = tlsCompleted,
                        http101 = true,
                        httpStatus = 101,
                        workerOk = false,
                        failureStage = "unexpected_worker_response",
                        error = text.take(240),
                    )
                }

                lines += "OK   [$mode] Worker probe: ${text.take(240)}"
                publish(lines)
                return ProbeResult(
                    mode = mode,
                    tlsCompleted = tlsCompleted,
                    http101 = true,
                    httpStatus = 101,
                    workerOk = true,
                    failureStage = null,
                    error = null,
                )
            } finally {
                executor.shutdownNow()
            }
        } catch (error: Throwable) {
            lines += "FAIL [$mode] Worker probe: ${errorSummary(error)}"
            publish(lines)
            return ProbeResult(
                mode = mode,
                tlsCompleted = tlsCompleted,
                http101 = true,
                httpStatus = 101,
                workerOk = false,
                failureStage = "worker_probe_failed",
                error = errorSummary(error),
            )
        } finally {
            runCatching { ws.close() }
        }
    }

    private fun classify(
        normal: ProbeResult,
        fragmented: ProbeResult,
        noSni: ProbeResult,
    ): String = when {
        normal.workerOk && fragmented.workerOk ->
            "normal and fragmented SNI both complete E2E; the earlier fragmented timeout is not reproduced on this edge/run."

        normal.workerOk && !fragmented.tlsCompleted ->
            "ordinary verified SNI works, while fragmented TLS does not finish its handshake. The failure is specific to the fragmented/SSLEngine path on this edge; a plain-SSLEngine control is the next isolation step before blaming the network alone."

        normal.tlsCompleted && !fragmented.tlsCompleted ->
            "ordinary verified SNI completes TLS but fragmented TLS does not. This confirms a pre-HTTP difference caused by the fragmented/SSLEngine path."

        noSni.workerOk && !normal.workerOk && !fragmented.workerOk ->
            "no-SNI reaches the Worker while both SNI modes fail. This is strong evidence of SNI-sensitive path interference, but no-SNI remains diagnostic because hostname verification is absent."

        noSni.tlsCompleted && !normal.tlsCompleted && !fragmented.tlsCompleted ->
            "removing SNI allows TLS to finish while both Worker-SNI modes fail before TLS completion; the mobile path is strongly SNI-sensitive."

        normal.workerOk && fragmented.tlsCompleted && !fragmented.workerOk ->
            "both modes get through TLS, but only ordinary SNI completes Worker E2E; investigate fragmented post-handshake/HTTP handling rather than ClientHello reachability."

        normal.tlsCompleted && fragmented.tlsCompleted ->
            "both SNI modes complete TLS; compare HTTP/Worker outcomes because the failure is after the TLS handshake."

        !normal.tlsCompleted && !fragmented.tlsCompleted && !noSni.tlsCompleted ->
            "all three modes fail before TLS completion on this edge; the result is not specific enough to attribute to SNI fragmentation."

        else ->
            "mixed result; use the three mode traces and failureStage values before changing transport logic."
    }

    private data class ProbeResult(
        val mode: String,
        val tlsCompleted: Boolean,
        val http101: Boolean,
        val httpStatus: Int?,
        val workerOk: Boolean,
        val failureStage: String?,
        val error: String?,
    ) {
        fun summaryLine(): String =
            "$mode: tlsCompleted=$tlsCompleted http101=$http101 httpStatus=${httpStatus ?: "none"} " +
                "workerOk=$workerOk failureStage=${failureStage ?: "none"}"
    }

    private fun workerProbePath(target: String): String {
        val encodedTarget = URLEncoder.encode(target, Charsets.UTF_8.name())
        return "/apiws?dst=$encodedTarget&dc=2&probe=1"
    }

    private fun stage(lines: MutableList<String>, name: String, block: () -> String): Boolean {
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

    private fun systemSniTimeoutStage(error: Throwable): String? =
        generateSequence(error) { it.cause }
            .filterIsInstance<SystemSniTlsDiagnosticTimeoutException>()
            .firstOrNull()
            ?.stage

    private fun fragmentedTimeoutStage(error: Throwable): String? =
        generateSequence(error) { it.cause }
            .filterIsInstance<FragmentedTlsDiagnosticTimeoutException>()
            .firstOrNull()
            ?.stage

    private fun noSniTimeoutStage(error: Throwable): String? =
        generateSequence(error) { it.cause }
            .filterIsInstance<NoSniTlsDiagnosticTimeoutException>()
            .firstOrNull()
            ?.stage

    private fun findWsHandshakeException(error: Throwable): RawWebSocket.WsHandshakeException? =
        generateSequence(error) { it.cause }
            .filterIsInstance<RawWebSocket.WsHandshakeException>()
            .firstOrNull()

    private fun finish(lines: List<String>) {
        publish(lines)
        runOnUiThread {
            runButton.isEnabled = true
            networkText.text = "Сеть: ${currentNetworkLabel()}"
        }
    }

    private fun publish(lines: List<String>) {
        val snapshot = lines.toList()
        runOnUiThread { resultText.text = snapshot.joinToString("\n") }
    }

    private fun copyResult() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Worker TLS comparison matrix", resultText.text))
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
        val diagnostic = generateSequence(error) { it.cause }.firstOrNull {
            it is FragmentedTlsDiagnosticTimeoutException ||
                it is NoSniTlsDiagnosticTimeoutException ||
                it is SystemSniTlsDiagnosticTimeoutException
        }
        if (diagnostic != null) {
            return "${diagnostic::class.java.simpleName}: ${diagnostic.message.orEmpty()}"
        }
        val root = generateSequence(error) { it.cause }.last()
        val message = root.message?.replace('\n', ' ')?.take(300).orEmpty()
        return if (message.isBlank()) root::class.java.simpleName else "${root::class.java.simpleName}: $message"
    }

    private fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = topMargin }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val PROBE_RESPONSE_TIMEOUT_MS = 8_000
        private const val MAX_COMPARED_EDGES = 2
        private val FALLBACK_EDGE_CANDIDATES = listOf(
            "104.21.25.4",
            "172.67.221.133",
            "104.16.123.96",
            "104.24.0.10",
            "188.114.96.10",
        )
    }
}
