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
import com.flowseal.tgwsandroid.proxy.NoSniTlsDiagnosticTimeoutException
import com.flowseal.tgwsandroid.proxy.NoSniTlsTransportFactory
import com.flowseal.tgwsandroid.proxy.RawWebSocket
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
 * Private-sideload experiment for the mobile path that truncates the TLS server
 * flight after recognizing the workers.dev ClientHello.
 *
 * TLS ClientHello deliberately contains no SNI. After TLS completes, the normal
 * Worker hostname is still sent as HTTP Host by RawWebSocket. Certificate-chain
 * trust remains system-validated, but hostname verification is intentionally not
 * performed because no TLS hostname is presented. This is diagnostic only.
 */
class WorkerNoSniE2eActivity : Activity() {
    private lateinit var networkText: TextView
    private lateinit var resultText: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SE Worker No-SNI E2E"

        networkText = TextView(this).apply { text = "Сеть: ${currentNetworkLabel()}" }
        resultText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Проверяет Worker WebSocket через TLS ClientHello без SNI.\n\n" +
                "Только private-sideload diagnostics; production routing не меняется."
        }
        runButton = Button(this).apply {
            text = "Проверить Worker без SNI"
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
            addView(TextView(this@WorkerNoSniE2eActivity).apply {
                text = "Worker no-SNI E2E"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@WorkerNoSniE2eActivity).apply {
                text = "Убирает workers.dev из TLS ClientHello и проверяет маршрутизацию Worker по HTTP Host после handshake."
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

        thread(name = "WorkerNoSniE2E") {
            val lines = mutableListOf(
                "SE Worker no-SNI E2E diagnostics",
                "Network: $network",
                "Worker HTTP Host: $domain",
                "Target: $target:443",
                "TLS SNI: omitted",
                "TLS trust: Android system certificate-chain validation",
                "Hostname verification: intentionally disabled for this diagnostic",
                "Goal: determine whether Cloudflare can route workers.dev by HTTP Host after a no-SNI TLS handshake",
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
                var attempted = 0
                var sawHttpResponse = false
                var sawCompletedTls = false

                for (edge in candidates.take(MAX_EDGE_CANDIDATES)) {
                    val tcpReachable = stage(lines, "TCP edge $edge") {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(edge, 443), CONNECT_TIMEOUT_MS)
                            "local=${socket.localAddress.hostAddress}:${socket.localPort}"
                        }
                    }
                    if (!tcpReachable) continue
                    attempted += 1

                    lines += "--- no-SNI TLS trace via $edge ---"
                    publish(lines)
                    val tracedTransport = NoSniTlsTransportFactory.traced { event ->
                        if (event.contains("HANDSHAKE FINISHED")) sawCompletedTls = true
                        lines += "TRACE $event"
                        publish(lines)
                    }

                    val ws = try {
                        var connected: RawWebSocket? = null
                        val elapsed = measureTimeMillis {
                            connected = RawWebSocket.connect(
                                host = edge,
                                domain = domain,
                                timeoutMs = CONNECT_TIMEOUT_MS,
                                path = workerProbePath(target),
                                transportFactory = tracedTransport,
                            )
                        }
                        lines += "OK   no-SNI TLS + WebSocket upgrade via $edge (${elapsed}ms): HTTP 101 received"
                        publish(lines)
                        connected
                    } catch (error: Throwable) {
                        val wsError = findWsHandshakeException(error)
                        if (wsError != null) {
                            sawHttpResponse = true
                            lines += "RESP no-SNI TLS completed, HTTP ${wsError.statusCode} via $edge: ${wsError.statusLine}"
                            if (wsError.statusCode == 403) {
                                lines += "INFO Cloudflare reached HTTP but rejected the request; no-SNI Host routing is not directly usable on this edge."
                            }
                        } else {
                            val stage = diagnosticTimeoutStage(error)
                            val suffix = stage?.let { " [$it]" }.orEmpty()
                            lines += "FAIL no-SNI TLS + WebSocket via $edge$suffix: ${errorSummary(error)}"
                        }
                        publish(lines)
                        null
                    } ?: continue

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
                            if (!text.contains("\"type\":\"worker_tcp_probe\"") || !text.contains("\"ok\":true")) {
                                error("unexpected Worker probe response: ${text.take(240)}")
                            }
                            lines += "OK   Worker WebSocket probe via $edge: ${text.take(240)}"
                            lines += ""
                            lines += "RESULT: NO-SNI WORKER END-TO-END OK VIA $edge"
                            lines += "The mobile path works when the Worker hostname is absent from ClientHello."
                            lines += "Cloudflare accepted the no-SNI TLS connection, routed by HTTP Host, and the Worker reached Telegram TCP."
                            lines += "This is a viable transport experiment, but production use requires an explicit security review because TLS hostname verification is absent."
                            return@thread finish(lines)
                        } finally {
                            executor.shutdownNow()
                        }
                    } catch (error: Throwable) {
                        lines += "FAIL Worker probe via $edge: ${errorSummary(error)}"
                        publish(lines)
                    } finally {
                        runCatching { ws.close() }
                    }
                }

                lines += ""
                lines += "RESULT: NO-SNI WORKER E2E FAILED"
                lines += when {
                    attempted == 0 -> "No tested Cloudflare IPv4 accepted TCP."
                    sawHttpResponse -> "TLS reached Cloudflare and produced HTTP responses, but Cloudflare did not accept the no-SNI Worker WebSocket request."
                    sawCompletedTls -> "At least one no-SNI TLS handshake completed, but no HTTP response/Worker upgrade completed."
                    else -> "No tested edge completed the no-SNI TLS/WebSocket path."
                }
                lines += "This test is independent of the previous SNI-fragmentation result and is intended to determine whether hiding workers.dev entirely is possible without extra infrastructure."
            } catch (error: Throwable) {
                lines += ""
                lines += "RESULT: TEST ERROR: ${errorSummary(error)}"
            } finally {
                finish(lines)
            }
        }
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

    private fun diagnosticTimeoutStage(error: Throwable): String? =
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Worker no-SNI E2E diagnostics", resultText.text))
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
        val diagnostic = generateSequence(error) { it.cause }
            .filterIsInstance<NoSniTlsDiagnosticTimeoutException>()
            .firstOrNull()
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
        private const val MAX_EDGE_CANDIDATES = 5
        private val FALLBACK_EDGE_CANDIDATES = listOf(
            "104.21.25.4",
            "172.67.221.133",
            "104.16.123.96",
            "104.24.0.10",
            "188.114.96.10",
        )
    }
}
