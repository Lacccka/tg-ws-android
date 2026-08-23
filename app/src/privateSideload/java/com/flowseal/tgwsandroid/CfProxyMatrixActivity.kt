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
 * letting Android choose a family implicitly. It traces TCP and TLS so a
 * generic SocketTimeoutException can be classified as TLS or HTTP-upgrade wait.
 *
 * On the first proxy zone it additionally performs TLS-only controls against the
 * exact same Cloudflare IP with benign/root SNI values. This distinguishes
 * proxy-hostname/SNI-sensitive interference from a broader failure of the same
 * Android TLS fingerprint toward Cloudflare.
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
            text = "Проверяет IPv4/IPv6 отдельно, различает TCP → TLS → HTTP Upgrade и сравнивает SNI на одном Cloudflare IP.\n\n" +
                "Production routing не меняется."
        }
        runButton = Button(this).apply {
            text = "Проверить CF proxy + SNI control"
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
                text = "CF proxy family/stage + SNI control"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@CfProxyMatrixActivity).apply {
                text = "Сначала проверяет benign/root SNI на том же Cloudflare IP, затем реальный kws2.* маршрут."
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
                "SE CF proxy family/stage + SNI control diagnostics",
                "Network: $network",
                "Pool source: ${if (configured.isNotEmpty()) "user-configured" else "bundled upstream defaults"}",
                "Base domains tested: ${domains.size}",
                "DC tested: $TEST_DC",
                "WebSocket path: /apiws",
                "Per-attempt timeout: ${WS_TIMEOUT_MS}ms (upstream CF fallback parity)",
                "TLS verification: production/upstream trust-all parity transport",
                "SNI controls: cloudflare.com and first proxy-zone root on the exact same edge IP",
                "Goal: distinguish address-family, TCP/TLS stage, and SNI-sensitive interference",
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
            var controlAttempts = 0
            var controlTlsCompleted = 0
            var benignControlCompleted = 0
            var zoneRootControlCompleted = 0

            try {
                for ((baseIndex, base) in domains.withIndex()) {
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
                        v4.firstOrNull()?.hostAddress?.let { add(AddressCandidate("IPv4", it)) }
                        v6.firstOrNull()?.hostAddress?.let { add(AddressCandidate("IPv6", it)) }
                    }

                    if (baseIndex == 0) {
                        lines += "--- TLS-only SNI controls on the same Cloudflare IPs ---"
                        publish(lines)
                        for (candidate in candidates) {
                            val controls = listOf(
                                SniControl("benign_cloudflare", BENIGN_CONTROL_SNI),
                                SniControl("proxy_zone_root", base),
                            )
                            for (control in controls) {
                                controlAttempts += 1
                                lines += "CONTROL ${candidate.family} ${candidate.address} sni=${control.sni} kind=${control.kind}"
                                publish(lines)

                                val trace = mutableListOf<String>()
                                val factory = TracedTrustAllTlsTransportFactory.traced { event ->
                                    synchronized(trace) { trace += event }
                                }
                                try {
                                    val transport = factory.connect(
                                        candidate.address,
                                        443,
                                        control.sni,
                                        WS_TIMEOUT_MS,
                                    )
                                    appendTrace(lines, trace)
                                    controlTlsCompleted += 1
                                    if (control.kind == "benign_cloudflare") benignControlCompleted += 1
                                    if (control.kind == "proxy_zone_root") zoneRootControlCompleted += 1
                                    lines += "OK   CONTROL TLS completed"
                                    publish(lines)
                                    runCatching { transport.close() }
                                } catch (error: TracedTlsDiagnosticTimeoutException) {
                                    appendTrace(lines, trace)
                                    lines += "FAIL CONTROL [${error.stage}]: ${errorSummary(error)}"
                                    publish(lines)
                                } catch (error: Throwable) {
                                    appendTrace(lines, trace)
                                    lines += "FAIL CONTROL: ${errorSummary(error)}"
                                    publish(lines)
                                }
                            }
                        }
                        lines += "--- end TLS-only SNI controls ---"
                        publish(lines)
                    }

                    for (candidate in candidates) {
                        attempts += 1
                        lines += "--- ${candidate.family} ${candidate.address} proxySni=$logicalHost ---"
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

                lines += "RESULT: CF PROXY FAMILY/STAGE + SNI CONTROL COMPLETE"
                lines += "DNS hosts: $dnsHosts"
                lines += "TLS control attempts: $controlAttempts"
                lines += "TLS controls completed: $controlTlsCompleted (benign=$benignControlCompleted zoneRoot=$zoneRootControlCompleted)"
                lines += "Proxy address-family attempts: $attempts"
                lines += "Proxy TCP reached: $tcpReached"
                lines += "Proxy TLS completed: $tlsReached"
                lines += "Proxy TLS timeouts: $tlsTimeouts"
                lines += "HTTP upgrade timeouts after TLS: $httpTimeouts"
                lines += "HTTP responses without 101: $httpResponses"
                lines += "WebSocket HTTP 101: $wsOk (IPv4=$ipv4WsOk, IPv6=$ipv6WsOk)"
                lines += when {
                    tlsReached == 0 && benignControlCompleted > 0 -> "VERDICT: the same Cloudflare IP and Android TLS stack complete a benign-SNI handshake while proxy SNI never completes. This is strong evidence of SNI-sensitive path filtering; stop tuning IPv4/IPv6/timeouts and test a private Custom Domain or ECH-capable transport."
                    tlsReached == 0 && zoneRootControlCompleted > 0 -> "VERDICT: the proxy-zone root completes TLS on the same edge, but kws2.* does not. This strongly isolates filtering to the proxy hostname/subdomain rather than Cloudflare reachability."
                    tlsReached == 0 && controlTlsCompleted == 0 -> "VERDICT: even benign/root SNI controls fail with the same raw Android TLS path. Do not attribute the failure to the proxy hostname alone; investigate TLS fingerprint/network-stack differences (for example Chromium/Cronet) before another domain rotation."
                    ipv4WsOk > 0 && ipv6WsOk == 0 -> "VERDICT: CF proxy works over forced IPv4 but not IPv6; Android routing should prefer IPv4 for this fallback on this network."
                    ipv6WsOk > 0 && ipv4WsOk == 0 -> "VERDICT: CF proxy works over IPv6 but not IPv4; keep family selection network-specific."
                    wsOk > 0 -> "VERDICT: at least one CF proxy route works; use successful family/domain as runtime fallback evidence."
                    tlsReached > 0 && httpTimeouts > 0 -> "VERDICT: TLS succeeds but CF proxy hosts do not answer the WebSocket Upgrade within the upstream 10s window; this points past TCP/TLS and toward intermediary/service behavior or HTTP-path filtering."
                    tcpReached > 0 && tlsReached == 0 -> "VERDICT: TCP reaches CF addresses but TLS never completes; use the same-edge SNI controls above to distinguish hostname filtering from TLS fingerprint filtering."
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
        clipboard.setPrimaryClip(ClipData.newPlainText("CF proxy SNI control diagnostics", resultText.text))
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

    private data class SniControl(
        val kind: String,
        val sni: String,
    )

    companion object {
        private const val TEST_DC = 2
        private const val MAX_BASE_DOMAINS = 3
        private const val WS_TIMEOUT_MS = 10_000
        private const val BENIGN_CONTROL_SNI = "cloudflare.com"
    }
}