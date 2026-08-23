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
import com.flowseal.tgwsandroid.proxy.RawWebSocket
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * Private-sideload diagnostic for upstream-style direct TLS SNI fronting.
 *
 * The TCP destination and HTTP Host stay on the configured Telegram direct WS
 * route. Only TLS SNI changes between the two attempts:
 *
 *  - normal:   SNI == HTTP Host (kwsN.web.telegram.org)
 *  - fronted:  SNI == sprinthost.ru, HTTP Host unchanged
 *
 * RawWebSocket intentionally uses the same trust-all TLS behavior as the
 * production/upstream parity transport. This screen is diagnostic only and does
 * not change ProxyServer routing.
 */
class DirectFrontingE2eActivity : Activity() {
    private lateinit var networkText: TextView
    private lateinit var resultText: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SE Direct Fronting E2E"

        networkText = TextView(this).apply { text = "Сеть: ${currentNetworkLabel()}" }
        resultText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Сравнивает обычный direct WebSocket с upstream-style TLS SNI fronting.\n\n" +
                "Production routing не меняется."
        }
        runButton = Button(this).apply {
            text = "Проверить direct fronting"
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
            addView(TextView(this@DirectFrontingE2eActivity).apply {
                text = "Direct fronting E2E"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@DirectFrontingE2eActivity).apply {
                text = "TCP/IP и HTTP Host одинаковые; меняется только TLS SNI: Telegram → sprinthost.ru."
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
        val routes = config.dcIp
            .mapNotNull(::parseRoute)
            .filter { it.dc in TEST_DCS }
            .distinctBy { it.dc }
            .take(MAX_ROUTES)

        if (routes.isEmpty()) {
            toast("В конфиге нет direct маршрутов DC2/DC4")
            return
        }

        val network = currentNetworkLabel()
        networkText.text = "Сеть: $network"
        runButton.isEnabled = false

        thread(name = "DirectFrontingE2E") {
            val lines = mutableListOf(
                "SE Direct fronting E2E diagnostics",
                "Network: $network",
                "Fronting SNI: $FRONTING_SNI",
                "WebSocket path: $WS_PATH",
                "TLS verification: RawWebSocket production/upstream parity transport (certificate + hostname verification disabled)",
                "Goal: determine whether an alternate TLS SNI restores direct Telegram WebSocket on this network",
                "",
            )

            try {
                var frontingCandidates = 0
                var frontingSuccesses = 0

                for (route in routes) {
                    val domain = directDomain(route.dc)
                    lines += "=== DC${route.dc} target=${route.host}:443 host=$domain ==="
                    publish(lines)

                    val tcp = tcpProbe(route.host)
                    lines += if (tcp.success) {
                        "OK   TCP ${route.host}:443 (${tcp.elapsedMs}ms): ${tcp.detail}"
                    } else {
                        "FAIL TCP ${route.host}:443 (${tcp.elapsedMs}ms): ${tcp.detail}"
                    }
                    publish(lines)
                    if (!tcp.success) {
                        lines += "VERDICT DC${route.dc}: target TCP is unreachable; SNI comparison skipped."
                        lines += ""
                        continue
                    }

                    val normal = wsProbe(
                        label = "normal_sni",
                        targetHost = route.host,
                        domain = domain,
                        sni = null,
                        timeoutMs = NORMAL_TIMEOUT_MS,
                    )
                    appendResult(lines, normal)
                    publish(lines)

                    val fronted = wsProbe(
                        label = "fronted_sni",
                        targetHost = route.host,
                        domain = domain,
                        sni = FRONTING_SNI,
                        timeoutMs = FRONTING_TIMEOUT_MS,
                    )
                    appendResult(lines, fronted)
                    publish(lines)

                    lines += "--- DC${route.dc} comparison ---"
                    lines += "normal_sni: http101=${normal.http101} httpStatus=${normal.httpStatus ?: "none"} failure=${normal.failureKind ?: "none"} elapsed=${normal.elapsedMs}ms"
                    lines += "fronted_sni: http101=${fronted.http101} httpStatus=${fronted.httpStatus ?: "none"} failure=${fronted.failureKind ?: "none"} elapsed=${fronted.elapsedMs}ms"
                    lines += when {
                        fronted.http101 && !normal.http101 -> {
                            frontingCandidates += 1
                            frontingSuccesses += 1
                            "VERDICT DC${route.dc}: FRONTING BYPASS CANDIDATE — same Telegram route fails normally but reaches HTTP 101 with SNI=$FRONTING_SNI."
                        }

                        fronted.http101 && normal.http101 ->
                            "VERDICT DC${route.dc}: both modes reach HTTP 101; fronting works but is not required on this route right now."

                        !fronted.http101 && normal.http101 ->
                            "VERDICT DC${route.dc}: normal direct works; SNI fronting is not usable/needed for this route."

                        else -> {
                            frontingCandidates += 1
                            "VERDICT DC${route.dc}: neither mode reaches HTTP 101; this fronting SNI does not restore the route."
                        }
                    }
                    lines += ""
                    publish(lines)
                }

                lines += "RESULT: DIRECT FRONTING MATRIX COMPLETE"
                lines += "Routes compared: ${routes.size}"
                lines += "Fronting bypass successes: $frontingSuccesses"
                lines += when {
                    frontingSuccesses > 0 ->
                        "At least one configured Telegram direct route is a production-runtime candidate for timeout-triggered SNI fronting."
                    frontingCandidates > 0 ->
                        "No tested route proved the sprinthost.ru SNI workaround on this network."
                    else ->
                        "No route required a fronting workaround in this run."
                }
                lines += "Do not enable fronting globally from this diagnostic alone; preference should be scoped per route and network generation."
            } catch (error: Throwable) {
                lines += ""
                lines += "RESULT: TEST ERROR: ${errorSummary(error)}"
            } finally {
                finish(lines)
            }
        }
    }

    private fun wsProbe(
        label: String,
        targetHost: String,
        domain: String,
        sni: String?,
        timeoutMs: Int,
    ): ProbeResult {
        var socket: RawWebSocket? = null
        var error: Throwable? = null
        val elapsed = measureTimeMillis {
            try {
                socket = RawWebSocket.connect(
                    host = targetHost,
                    domain = domain,
                    timeoutMs = timeoutMs,
                    path = WS_PATH,
                    sni = sni,
                )
            } catch (caught: Throwable) {
                error = caught
            }
        }

        socket?.let {
            runCatching { it.close() }
            return ProbeResult(
                label = label,
                http101 = true,
                elapsedMs = elapsed,
                detail = "HTTP 101 WebSocket upgrade received",
            )
        }

        val wsError = error?.let(::findWsHandshakeException)
        if (wsError != null) {
            return ProbeResult(
                label = label,
                http101 = false,
                httpStatus = wsError.statusCode,
                elapsedMs = elapsed,
                failureKind = "http_${wsError.statusCode}",
                detail = wsError.statusLine,
            )
        }

        return ProbeResult(
            label = label,
            http101 = false,
            elapsedMs = elapsed,
            failureKind = classifyFailure(error),
            detail = error?.let(::errorSummary) ?: "unknown failure",
        )
    }

    private fun appendResult(lines: MutableList<String>, result: ProbeResult) {
        lines += if (result.http101) {
            "OK   [${result.label}] (${result.elapsedMs}ms): ${result.detail}"
        } else {
            "FAIL [${result.label}] (${result.elapsedMs}ms) [${result.failureKind ?: "unknown"}]: ${result.detail}"
        }
    }

    private fun tcpProbe(host: String): TcpResult {
        var detail = ""
        var success = false
        val elapsed = measureTimeMillis {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, 443), TCP_TIMEOUT_MS)
                    detail = "local=${socket.localAddress.hostAddress}:${socket.localPort}"
                    success = true
                }
            } catch (error: Throwable) {
                detail = errorSummary(error)
            }
        }
        return TcpResult(success, elapsed, detail)
    }

    private fun parseRoute(entry: String): DirectRoute? {
        val parts = entry.split(':', limit = 2)
        if (parts.size != 2) return null
        val dc = parts[0].trim().toIntOrNull() ?: return null
        val host = parts[1].trim()
        if (host.isEmpty()) return null
        return DirectRoute(dc, host)
    }

    private fun directDomain(dc: Int): String {
        val normalized = if (dc == 203) 2 else dc
        return "kws$normalized.web.telegram.org"
    }

    private fun classifyFailure(error: Throwable?): String {
        if (error == null) return "unknown"
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.any { it is java.net.SocketTimeoutException }) return "timeout"
        if (chain.any { it is java.net.ConnectException }) return "connect_error"
        if (chain.any { it is javax.net.ssl.SSLException }) return "tls_error"
        val text = chain.joinToString(" ") { it.message.orEmpty() }
        if (text.contains("timed out", ignoreCase = true)) return "timeout"
        if (text.contains("reset", ignoreCase = true)) return "connection_reset"
        return chain.lastOrNull()?.javaClass?.simpleName ?: "unknown"
    }

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
        clipboard.setPrimaryClip(ClipData.newPlainText("Direct fronting E2E diagnostics", resultText.text))
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
        val message = root.message?.replace('\n', ' ')?.take(320).orEmpty()
        return if (message.isBlank()) root::class.java.simpleName else "${root::class.java.simpleName}: $message"
    }

    private fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = topMargin }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private data class DirectRoute(val dc: Int, val host: String)
    private data class TcpResult(val success: Boolean, val elapsedMs: Long, val detail: String)
    private data class ProbeResult(
        val label: String,
        val http101: Boolean,
        val httpStatus: Int? = null,
        val elapsedMs: Long,
        val failureKind: String? = null,
        val detail: String,
    )

    companion object {
        private const val FRONTING_SNI = "sprinthost.ru"
        private const val WS_PATH = "/apiws"
        private const val TCP_TIMEOUT_MS = 3_000
        private const val NORMAL_TIMEOUT_MS = 3_000
        private const val FRONTING_TIMEOUT_MS = 5_000
        private const val MAX_ROUTES = 2
        private val TEST_DCS = setOf(2, 4)
    }
}
