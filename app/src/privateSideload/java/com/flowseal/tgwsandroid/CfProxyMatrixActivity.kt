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
import com.flowseal.tgwsandroid.proxy.CfProxyDomains
import com.flowseal.tgwsandroid.proxy.RawWebSocket
import com.flowseal.tgwsandroid.proxy.TracedTlsDiagnosticTimeoutException
import com.flowseal.tgwsandroid.proxy.TracedTrustAllTlsTransportFactory
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * Private-sideload reachability matrix for the bundled/upstream CF proxy pool.
 *
 * This version deliberately pins one IPv4 and one IPv6 address instead of
 * letting Android choose a family implicitly. It also traces TCP and TLS so a
 * generic SocketTimeoutException can be classified as TLS or HTTP-upgrade wait.
 */
class CfProxyMatrixActivity : Activity() {
    private lateinit var networkText: TextView
    private lateinit var resultText: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SE CF Proxy Matrix"

        networkText = TextView(this).apply { text = "Сеть: ${currentNetworkLabel()}" }
        resultText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Проверяет IPv4/IPv6 отдельно и различает TCP → TLS → HTTP Upgrade.\n\n" +
                "Production routing не меняется."
        }
        runButton = Button(this).apply {
            text = "Проверить CF proxy IPv4/IPv6"
            isAllCaps = false
            setOnClickListener { runProbe() }
        }
        val copyButton = Button(this).apply {
            text = "Копировать результат"
            isAllCaps = false
            setOnClickListener { copyResult() }
        }

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val gap = (10 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@CfProxyMatrixActivity).apply {
                text = "CF proxy family/stage matrix"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@CfProxyMatrixActivity).apply {
                text = "После предыдущего прогона проверяет, одинаково ли ведут себя IPv4 и IPv6 и где именно останавливается WebSocket handshake."
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
        val configured = config.cfproxyUserDomain.map { it.trim() }.filter { it.isNotEmpty() }
        val domains = (if (configured.isNotEmpty()) configured else CfProxyDomains.defaults)
            .distinct()
            .take(MAX_BASE_DOMAINS)

        val network = currentNetworkLabel()
        networkText.text = "Сеть: $network"
        runButton.isEnabled = false

        thread(name = "CfProxyFamilyStageMatrix") {
            val lines = mutableListOf(
                "SE CF proxy family/stage matrix diagnostics",
                "Network: $network",
                "Pool source: ${if (configured.isNotEmpty()) "user-configured" else "bundled upstream defaults"}",
                "Base domains tested: ${domains.size}",
                "DC tested: $TEST_DC",
                "WebSocket path: /apiws",
                "Per-attempt timeout: ${WS_TIMEOUT_MS}ms (upstream CF fallback parity)",
                "TLS verification: production/upstream trust-all parity transport",
                "Goal: force IPv4 and IPv6 separately and identify TCP/TLS/HTTP failure stage",
                "",
            )

            var dnsHosts = 0
            var attempts = 0
            var tcpReached = 0
            var tlsReached = 0
            var httpResponses = 0
            var wsOk = 0
            var ipv4WsOk = 0
            var ipv6WsOk = 0
            var httpTimeouts = 0
            var tlsTimeouts = 0

            try {
                for (base in domains) {
                    val logicalHost = "kws$TEST_DC.$base"
                    lines += "=== $logicalHost ==="
                    publish(lines)

                    val resolved = try {
                        InetAddress.getAllByName(logicalHost).toList()
                    } catch (error: Throwable) {
                        lines += "FAIL DNS: ${errorSummary(error)}"
                        lines += ""
                        publish(lines)
                        continue
                    }

                    val v4 = resolved.filterIsInstance<Inet4Address>().distinctBy { it.hostAddress }
                    val v6 = resolved.filterIsInstance<Inet6Address>().distinctBy { it.hostAddress }
                    if (v4.isEmpty() && v6.isEmpty()) {
                        lines += "FAIL DNS: no IPv4/IPv6 addresses"
                        lines += ""
                        publish(lines)
                        continue
                    }
                    dnsHosts += 1
                    lines += "DNS IPv4: ${v4.joinToString(", ") { it.hostAddress ?: "?" }.ifEmpty { "none" }}"
                    lines += "DNS IPv6: ${v6.joinToString(", ") { it.hostAddress ?: "?" }.ifEmpty { "none" }}"
                    publish(lines)

                    val candidates = buildList {
                        v4.firstOrNull()?.let { add(AddressCandidate("IPv4", it.hostAddress)) }
                        v6.firstOrNull()?.let { add(AddressCandidate("IPv6", it.hostAddress)) }
                    }

                    for (candidate in candidates) {
                        attempts += 1
                        lines += "--- ${candidate.family} ${candidate.address} ---"
                        publish(lines)

                        var sawTcpFinished = false
                        var sawTlsFinished = false
                        val trace = mutableListOf<String>()
                        val transport = TracedTrustAllTlsTransportFactory.traced { event ->
                            synchronized(trace) {
                                trace += event
                                if (event.contains("TCP_CONNECT finished")) sawTcpFinished = true
                                if (event.contains("TLS_HANDSHAKE finished")) sawTlsFinished = true
                            }
                        }

                        try {
                            var ws: RawWebSocket? = null
                            val elapsed = measureTimeMillis {
                                ws = RawWebSocket.connect(
                                    host = candidate.address,
                                    domain = logicalHost,
                                    timeoutMs = WS_TIMEOUT_MS,
                                    path = "/apiws",
                                    transportFactory = transport,
                                )
                            }
                            if (sawTcpFinished) tcpReached += 1
                            if (sawTlsFinished) tlsReached += 1
                            wsOk += 1
                            if (candidate.family == "IPv4") ipv4WsOk += 1 else ipv6WsOk += 1
                            appendTrace(lines, trace)
                            lines += "OK   WebSocket (${elapsed}ms): HTTP 101"
                            publish(lines)
                            runCatching { ws?.close() }
                        } catch (error: RawWebSocket.WsHandshakeException) {
                            if (sawTcpFinished) tcpReached += 1
                            if (sawTlsFinished) tlsReached += 1
                            httpResponses += 1
                            appendTrace(lines, trace)
                            lines += "RESP HTTP ${error.statusCode}: ${error.statusLine}" +
                                (error.location?.let { " location=$it" } ?: "")
                            publish(lines)
                        } catch (error: TracedTlsDiagnosticTimeoutException) {
                            if (sawTcpFinished) tcpReached += 1
                            tlsTimeouts += 1
                            appendTrace(lines, trace)
                            lines += "FAIL [${error.stage}]: ${errorSummary(error)}"
                            publish(lines)
                        } catch (error: SocketTimeoutException) {
                            if (sawTcpFinished) tcpReached += 1
                            if (sawTlsFinished) {
                                tlsReached += 1
                                httpTimeouts += 1
                            }
                            appendTrace(lines, trace)
                            val stage = if (sawTlsFinished) "timeout_waiting_http_upgrade_response" else "socket_timeout_before_tls_finished"
                            lines += "FAIL [$stage]: ${errorSummary(error)}"
                            publish(lines)
                        } catch (error: Throwable) {
                            if (sawTcpFinished) tcpReached += 1
                            if (sawTlsFinished) tlsReached += 1
                            appendTrace(lines, trace)
                            lines += "FAIL [${failureStage(sawTcpFinished, sawTlsFinished)}]: ${errorSummary(error)}"
                            publish(lines)
                        }
                    }
                    lines += ""
                }

                lines += "RESULT: CF PROXY FAMILY/STAGE MATRIX COMPLETE"
                lines += "DNS hosts: $dnsHosts"
                lines += "Address-family attempts: $attempts"
                lines += "TCP reached: $tcpReached"
                lines += "TLS completed: $tlsReached"
                lines += "TLS timeouts: $tlsTimeouts"
                lines += "HTTP upgrade timeouts after TLS: $httpTimeouts"
                lines += "HTTP responses without 101: $httpResponses"
                lines += "WebSocket HTTP 101: $wsOk (IPv4=$ipv4WsOk, IPv6=$ipv6WsOk)"
                lines += when {
                    ipv4WsOk > 0 && ipv6WsOk == 0 -> "VERDICT: CF proxy works over forced IPv4 but not IPv6; Android routing should prefer IPv4 for this fallback on this network."
                    ipv6WsOk > 0 && ipv4WsOk == 0 -> "VERDICT: CF proxy works over IPv6 but not IPv4; keep family selection network-specific."
                    wsOk > 0 -> "VERDICT: at least one CF proxy route works; use successful family/domain as runtime fallback evidence."
                    tlsReached > 0 && httpTimeouts > 0 -> "VERDICT: TLS succeeds but CF proxy hosts do not answer the WebSocket Upgrade within the upstream 10s window; this points past TCP/TLS and toward intermediary/service behavior or HTTP-path filtering."
                    tcpReached > 0 && tlsReached == 0 -> "VERDICT: TCP reaches CF addresses but TLS never completes; compare the failing family/SNI traces with Worker TLS behavior."
                    else -> "VERDICT: no tested family produced a usable CF proxy route."
                }
            } catch (error: Throwable) {
                lines += ""
                lines += "RESULT: TEST ERROR: ${errorSummary(error)}"
            } finally {
                finish(lines)
            }
        }
    }

    private fun appendTrace(lines: MutableList<String>, trace: List<String>) {
        synchronized(trace) {
            trace.forEach { lines += "TRACE $it" }
        }
    }

    private fun failureStage(tcpFinished: Boolean, tlsFinished: Boolean): String = when {
        tlsFinished -> "after_tls_before_http101"
        tcpFinished -> "during_tls"
        else -> "during_tcp_connect"
    }

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
        clipboard.setPrimaryClip(ClipData.newPlainText("CF proxy family/stage matrix diagnostics", resultText.text))
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

    private data class AddressCandidate(
        val family: String,
        val address: String,
    )

    companion object {
        private const val TEST_DC = 2
        private const val MAX_BASE_DOMAINS = 3
        private const val WS_TIMEOUT_MS = 10_000
    }
}
