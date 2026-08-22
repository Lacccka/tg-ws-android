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
import com.flowseal.tgwsandroid.proxy.FragmentedTlsTransportFactory
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
 * End-to-end proof that the TLS-record fragmentation demonstrated by
 * [WorkerTlsFragmentActivity] can complete a real platform-validated TLS
 * handshake and carry the Worker's WebSocket probe.
 *
 * This remains private-sideload diagnostics only. It does not alter ProxyServer
 * route selection or production Worker behavior.
 */
class WorkerFragmentedE2eActivity : Activity() {
    private lateinit var networkText: TextView
    private lateinit var resultText: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SE Worker Frag E2E"

        networkText = TextView(this).apply { text = "Сеть: ${currentNetworkLabel()}" }
        resultText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Полный тест: fragmented ClientHello → TLS handshake → WebSocket Worker → Telegram TCP.\n\n" +
                "Сертификат и hostname проверяются системным TLS provider."
        }
        runButton = Button(this).apply {
            text = "Проверить fragmented Worker E2E"
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
            addView(TextView(this@WorkerFragmentedE2eActivity).apply {
                text = "Fragmented Worker E2E"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@WorkerFragmentedE2eActivity).apply {
                text = "Доказывает полный защищённый Worker transport поверх TLS-record fragmentation."
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

        thread(name = "WorkerFragmentedE2E") {
            val lines = mutableListOf(
                "SE Worker fragmented E2E diagnostics",
                "Network: $network",
                "Worker: $domain",
                "Target: $target:443",
                "TLS: Android SSLEngine + system trust + HTTPS hostname verification",
                "Fragmentation: first ClientHello TLS record split inside Worker SNI",
                "",
            )

            try {
                val resolved = InetAddress.getAllByName(domain)
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .distinct()
                lines += "DNS IPv4: ${resolved.ifEmpty { listOf("none") }.joinToString(", ")}"
                publish(lines)

                val candidates = (resolved + FALLBACK_EDGE_CANDIDATES).distinct()
                var attempted = 0
                for (edge in candidates.take(MAX_EDGE_CANDIDATES)) {
                    val tcpReachable = stage(lines, "TCP edge $edge") {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(edge, 443), CONNECT_TIMEOUT_MS)
                            "local=${socket.localAddress.hostAddress}:${socket.localPort}"
                        }
                    }
                    if (!tcpReachable) continue
                    attempted += 1

                    val ws = try {
                        var connected: RawWebSocket? = null
                        val elapsed = measureTimeMillis {
                            connected = RawWebSocket.connect(
                                host = edge,
                                domain = domain,
                                timeoutMs = CONNECT_TIMEOUT_MS,
                                path = workerProbePath(target),
                                transportFactory = FragmentedTlsTransportFactory,
                            )
                        }
                        lines += "OK   TLS + WebSocket upgrade via $edge (${elapsed}ms): system certificate/hostname validation passed; HTTP 101 received"
                        publish(lines)
                        connected
                    } catch (error: Throwable) {
                        lines += "FAIL TLS + WebSocket upgrade via $edge: ${errorSummary(error)}"
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
                            lines += "RESULT: FRAGMENTED WORKER END-TO-END OK VIA $edge"
                            lines += "Android -> fragmented TLS -> Cloudflare Worker -> Telegram TCP is working on this network."
                            lines += "This is sufficient evidence to implement the transport as a Worker fallback candidate in ProxyServer."
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
                lines += "RESULT: FRAGMENTED WORKER E2E FAILED"
                lines += if (attempted == 0) {
                    "No tested Cloudflare IPv4 accepted TCP."
                } else {
                    "TCP-reachable edges existed, but the full fragmented TLS/WebSocket probe did not complete."
                }
                lines += "The earlier ServerHello result still proves DPI bypass at the first handshake flight; inspect the first FAIL above for the next TLS/WS boundary."
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

    private fun finish(lines: List<String>) {
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Worker fragmented E2E diagnostics", resultText.text))
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
