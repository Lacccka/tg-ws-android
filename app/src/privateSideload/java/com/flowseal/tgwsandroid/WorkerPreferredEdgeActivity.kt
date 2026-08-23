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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * Follow-up diagnostic for mobile networks where DNS/TCP to workers.dev works
 * but TLS to the DNS-selected Cloudflare edge is black-holed.
 *
 * This deliberately tests only a small representative set of Cloudflare
 * anycast IPv4 addresses. It is not a general-purpose scanner.
 */
class WorkerPreferredEdgeActivity : Activity() {
    private lateinit var networkText: TextView
    private lateinit var resultText: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SE Edge Test"

        networkText = TextView(this).apply { text = "Сеть: ${currentNetworkLabel()}" }
        resultText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Тест проверяет обычный Cloudflare TLS, HTTP/80 до Worker и несколько альтернативных Cloudflare edge IPv4."
        }
        runButton = Button(this).apply {
            text = "Проверить Cloudflare edge"
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
            addView(TextView(this@WorkerPreferredEdgeActivity).apply {
                text = "Cloudflare preferred edge"
                textSize = 25f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@WorkerPreferredEdgeActivity).apply {
                text = "Диагностика случая TCP OK → TLS timeout. AUTO-маршрутизация прокси не меняется."
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
        thread(name = "WorkerPreferredEdge") {
            val lines = mutableListOf(
                "SE preferred-edge diagnostics",
                "Network: $network",
                "Worker: $domain",
                "Target: $target:443",
                "",
            )

            fun stage(name: String, block: () -> String): Boolean {
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

            stage("TLS control cloudflare.com normal DNS") {
                probeTlsByHostname(CONTROL_TLS_HOST)
            }

            stage("HTTP worker port 80") {
                probeHttp80(domain)
            }

            lines += ""
            lines += "Preferred Cloudflare IPv4 candidates:"
            publish(lines)

            var workingEdge: String? = null
            for (candidate in PREFERRED_EDGE_CANDIDATES) {
                val ok = stage("TLS worker via $candidate") {
                    probeTlsAtAddress(domain, candidate, EDGE_TIMEOUT_MS)
                }
                if (ok) {
                    workingEdge = candidate
                    break
                }
            }

            if (workingEdge == null) {
                lines += ""
                lines += "RESULT: NO WORKING PREFERRED EDGE IN QUICK SAMPLE"
                lines += "The workers.dev DNS edges and this small cross-range Cloudflare sample all fail TLS with the Worker SNI."
                lines += "Next candidates: Worker Custom Domain, TLS ClientHello fragmentation, or a non-Cloudflare relay."
                finish(lines)
                return@thread
            }

            val edge = workingEdge
            lines += ""
            lines += "Candidate found: $edge"
            publish(lines)

            val wsOk = stage("WebSocket Worker via preferred edge $edge") {
                probeWorkerWebSocket(domain, target, edge)
            }

            lines += ""
            if (wsOk) {
                lines += "RESULT: WORKER END-TO-END PROBE OK VIA PREFERRED EDGE $edge"
                lines += "Use $edge only as the physical Cloudflare connection address; keep Worker SNI/Host as $domain."
            } else {
                lines += "RESULT: TLS EDGE FOUND, BUT WORKER WEBSOCKET FAILED"
                lines += "The edge bypasses TLS blocking, but Worker routing/WS needs separate investigation."
            }
            finish(lines)
        }
    }

    private fun probeTlsByHostname(host: String): String {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = factory.createSocket() as SSLSocket
        socket.use {
            it.soTimeout = CONNECT_TIMEOUT_MS
            val params = it.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            params.serverNames = listOf(SNIHostName(host))
            it.sslParameters = params
            it.connect(InetSocketAddress(host, 443), CONNECT_TIMEOUT_MS)
            it.startHandshake()
            return "remote=${it.inetAddress.hostAddress}:443 ${it.session.protocol} ${it.session.cipherSuite}"
        }
    }

    private fun probeTlsAtAddress(domain: String, ip: String, timeoutMs: Int): String {
        val address = InetAddress.getByName(ip)
        val raw = Socket()
        try {
            raw.soTimeout = timeoutMs
            raw.connect(InetSocketAddress(address, 443), timeoutMs)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, domain, 443, true) as SSLSocket
            ssl.use {
                it.soTimeout = timeoutMs
                val params = it.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                params.serverNames = listOf(SNIHostName(domain))
                it.sslParameters = params
                it.startHandshake()
                return "local=${it.localAddress.hostAddress}:${it.localPort} ${it.session.protocol} ${it.session.cipherSuite}"
            }
        } catch (error: Throwable) {
            runCatching { raw.close() }
            throw error
        }
    }

    private fun probeHttp80(domain: String): String {
        Socket().use { socket ->
            socket.soTimeout = CONNECT_TIMEOUT_MS
            socket.connect(InetSocketAddress(domain, 80), CONNECT_TIMEOUT_MS)
            val request = buildString {
                append("GET /health HTTP/1.1\r\n")
                append("Host: $domain\r\n")
                append("Connection: close\r\n")
                append("User-Agent: tg-ws-android-edge-probe\r\n\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val status = reader.readLine().orEmpty()
            val body = buildString {
                var line: String?
                var blankSeen = false
                while (reader.readLine().also { line = it } != null) {
                    if (!blankSeen) {
                        if (line!!.isEmpty()) blankSeen = true
                    } else if (length < 180) {
                        append(line)
                    }
                }
            }
            if (!status.startsWith("HTTP/1.1 200") && !status.startsWith("HTTP/1.0 200")) {
                error("$status body=${body.take(120)}")
            }
            return "$status ${body.take(120)}"
        }
    }

    private fun probeWorkerWebSocket(domain: String, target: String, physicalHost: String): String {
        val encodedTarget = URLEncoder.encode(target, Charsets.UTF_8.name())
        val path = "/apiws?dst=$encodedTarget&dc=2&probe=1"
        val ws = RawWebSocket.connect(
            host = physicalHost,
            domain = domain,
            timeoutMs = CONNECT_TIMEOUT_MS,
            path = path,
        )
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
                error("unexpected probe response: ${text.take(220)}")
            }
            return text.take(220)
        } finally {
            ws.close()
            executor.shutdownNow()
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
        runOnUiThread { resultText.text = lines.joinToString("\n") }
    }

    private fun copyResult() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Preferred edge diagnostics", resultText.text))
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
        val message = root.message?.replace('\n', ' ')?.take(220).orEmpty()
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
        private const val EDGE_TIMEOUT_MS = 2_500
        private const val PROBE_RESPONSE_TIMEOUT_MS = 6_000
        private const val CONTROL_TLS_HOST = "cloudflare.com"

        // Small cross-range sample based on Cloudflare's published IPv4 anycast
        // space. This intentionally avoids exhaustive scanning on a phone.
        private val PREFERRED_EDGE_CANDIDATES = listOf(
            "104.16.123.96",
            "104.24.0.10",
            "172.64.0.10",
            "172.67.0.10",
            "162.158.0.10",
            "198.41.128.10",
            "188.114.96.10",
            "141.101.64.10",
            "173.245.48.10",
            "131.0.72.10",
        )
    }
}
