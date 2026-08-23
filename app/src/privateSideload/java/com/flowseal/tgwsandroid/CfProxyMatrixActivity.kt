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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * Private-sideload reachability matrix for the bundled/upstream CF proxy pool.
 *
 * Unlike direct fronting, these routes do not connect to the configured Telegram
 * IP first. A successful HTTP 101 therefore proves that the mobile network can
 * reach an intermediary WebSocket route even when direct Telegram TCP is blocked.
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
            text = "Проверяет DNS → TCP:443 → WebSocket HTTP 101 для текущего CF-proxy domain pool.\n\n" +
                "Production routing не меняется."
        }
        runButton = Button(this).apply {
            text = "Проверить CF proxy pool"
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
                text = "CF proxy matrix"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@CfProxyMatrixActivity).apply {
                text = "Ищет рабочий промежуточный WebSocket-маршрут без прямого TCP к Telegram IP и без workers.dev."
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

        thread(name = "CfProxyMatrix") {
            val lines = mutableListOf(
                "SE CF proxy matrix diagnostics",
                "Network: $network",
                "Pool source: ${if (configured.isNotEmpty()) "user-configured" else "bundled upstream defaults"}",
                "Base domains tested: ${domains.size}",
                "WebSocket path: /apiws",
                "Goal: find an intermediary route that does not require direct TCP to Telegram IP or workers.dev",
                "",
            )

            var dnsOk = 0
            var tcpOk = 0
            var wsOk = 0
            var httpResponses = 0

            try {
                for (dc in TEST_DCS) {
                    lines += "=== DC$dc ==="
                    publish(lines)

                    for (base in domains) {
                        val host = "kws$dc.$base"
                        lines += "--- $host ---"
                        publish(lines)

                        val ipv4 = try {
                            InetAddress.getAllByName(host)
                                .filterIsInstance<Inet4Address>()
                                .mapNotNull { it.hostAddress }
                                .distinct()
                        } catch (error: Throwable) {
                            lines += "FAIL DNS: ${errorSummary(error)}"
                            publish(lines)
                            continue
                        }

                        if (ipv4.isEmpty()) {
                            lines += "FAIL DNS: no IPv4 addresses"
                            publish(lines)
                            continue
                        }
                        dnsOk += 1
                        lines += "OK   DNS: ${ipv4.joinToString(", ")}"
                        publish(lines)

                        val tcp = try {
                            var detail = ""
                            val elapsed = measureTimeMillis {
                                Socket().use { socket ->
                                    socket.connect(InetSocketAddress(host, 443), TCP_TIMEOUT_MS)
                                    detail = "remote=${socket.inetAddress.hostAddress}:443 local=${socket.localAddress.hostAddress}:${socket.localPort}"
                                }
                            }
                            tcpOk += 1
                            lines += "OK   TCP (${elapsed}ms): $detail"
                            publish(lines)
                            true
                        } catch (error: Throwable) {
                            lines += "FAIL TCP: ${errorSummary(error)}"
                            publish(lines)
                            false
                        }
                        if (!tcp) continue

                        try {
                            var ws: RawWebSocket? = null
                            val elapsed = measureTimeMillis {
                                ws = RawWebSocket.connect(
                                    host = host,
                                    domain = host,
                                    timeoutMs = WS_TIMEOUT_MS,
                                    path = "/apiws",
                                )
                            }
                            wsOk += 1
                            lines += "OK   WebSocket (${elapsed}ms): HTTP 101"
                            publish(lines)
                            runCatching { ws?.close() }
                        } catch (error: RawWebSocket.WsHandshakeException) {
                            httpResponses += 1
                            lines += "RESP HTTP ${error.statusCode}: ${error.statusLine}" +
                                (error.location?.let { " location=$it" } ?: "")
                            publish(lines)
                        } catch (error: Throwable) {
                            lines += "FAIL WebSocket: ${errorSummary(error)}"
                            publish(lines)
                        }
                    }
                    lines += ""
                }

                lines += "RESULT: CF PROXY MATRIX COMPLETE"
                lines += "DNS reachable routes: $dnsOk"
                lines += "TCP reachable routes: $tcpOk"
                lines += "HTTP responses without 101: $httpResponses"
                lines += "WebSocket HTTP 101 routes: $wsOk"
                lines += when {
                    wsOk > 0 -> "VERDICT: at least one CF-proxy intermediary is reachable on this network; prioritize CF pool freshness/selection and runtime fallback diagnostics."
                    tcpOk > 0 -> "VERDICT: intermediary domains are TCP-reachable, but none completed WebSocket upgrade; inspect HTTP statuses and pool freshness."
                    dnsOk > 0 -> "VERDICT: domains resolve but TCP:443 is blocked/unreachable for all tested routes."
                    else -> "VERDICT: current tested CF-proxy pool is not DNS-usable on this network."
                }
            } catch (error: Throwable) {
                lines += ""
                lines += "RESULT: TEST ERROR: ${errorSummary(error)}"
            } finally {
                finish(lines)
            }
        }
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
        clipboard.setPrimaryClip(ClipData.newPlainText("CF proxy matrix diagnostics", resultText.text))
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
        private val TEST_DCS = listOf(2, 4)
        private const val MAX_BASE_DOMAINS = 6
        private const val TCP_TIMEOUT_MS = 3_000
        private const val WS_TIMEOUT_MS = 5_000
    }
}
