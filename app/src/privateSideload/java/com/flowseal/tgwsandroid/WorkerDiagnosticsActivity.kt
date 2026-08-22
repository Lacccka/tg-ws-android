package com.flowseal.tgwsandroid

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.flowseal.tgwsandroid.config.AppConfigStore
import com.flowseal.tgwsandroid.proxy.RawWebSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * Private-sideload-only probe for proving Cloudflare Worker reachability before
 * wiring Worker fallback into ProxyServer routing.
 *
 * The probe intentionally tests each boundary separately:
 * Android -> DNS -> TCP -> TLS -> Worker HTTP -> Worker outbound TCP ->
 * WebSocket upgrade -> Worker outbound TCP acknowledgement.
 */
class WorkerDiagnosticsActivity : Activity() {
    private lateinit var domainInput: EditText
    private lateinit var targetInput: EditText
    private lateinit var runButton: Button
    private lateinit var saveButton: Button
    private lateinit var copyButton: Button
    private lateinit var resultText: TextView
    private lateinit var networkText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SE Worker Test"

        val config = AppConfigStore.getConfig(applicationContext)
        val configuredDomain = config.cfproxyWorkerDomain.firstOrNull().orEmpty()
        val dc2Target = config.dcIp
            .firstOrNull { it.substringBefore(':').trim() == "2" }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "149.154.167.220"

        domainInput = EditText(this).apply {
            hint = "example.username.workers.dev"
            setSingleLine(true)
            setText(configuredDomain)
        }
        targetInput = EditText(this).apply {
            hint = "Telegram IP"
            setSingleLine(true)
            setText(dc2Target)
        }
        networkText = TextView(this).apply {
            text = "Сеть: ${currentNetworkLabel()}"
        }
        resultText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Укажите домен Worker и нажмите «Проверить».\n\n" +
                "Тест не меняет AUTO-маршрутизацию прокси."
        }
        saveButton = Button(this).apply {
            text = "Сохранить домен"
            setOnClickListener { saveWorkerDomain() }
        }
        runButton = Button(this).apply {
            text = "Проверить Worker"
            setOnClickListener { runProbe() }
        }
        copyButton = Button(this).apply {
            text = "Копировать результат"
            setOnClickListener { copyResult() }
        }

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val gap = (10 * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@WorkerDiagnosticsActivity).apply {
                text = "Cloudflare Worker"
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@WorkerDiagnosticsActivity).apply {
                text = "Изолированный тест доступности Worker с текущей сети телефона."
            }, matchWrap(gap))
            addView(networkText, matchWrap(gap))
            addView(TextView(this@WorkerDiagnosticsActivity).apply { text = "Домен Worker" }, matchWrap(gap))
            addView(domainInput, matchWrap())
            addView(TextView(this@WorkerDiagnosticsActivity).apply { text = "Telegram target (DC2)" }, matchWrap(gap))
            addView(targetInput, matchWrap())
            addView(saveButton, matchWrap(gap))
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
        if (::networkText.isInitialized) networkText.text = "Сеть: ${currentNetworkLabel()}"
    }

    private fun runProbe() {
        val domain = normalizeDomain(domainInput.text.toString())
        val target = targetInput.text.toString().trim()
        if (domain.isEmpty()) {
            toast("Укажите домен Worker")
            return
        }
        if (!isIpv4(target)) {
            toast("Укажите IPv4 Telegram target")
            return
        }

        domainInput.setText(domain)
        saveWorkerDomain(domain)
        runButton.isEnabled = false
        resultText.text = "Сеть: ${currentNetworkLabel()}\nWorker: $domain\nTarget: $target:443\n\nЗапуск…"

        thread(name = "WorkerDiagnostics") {
            val lines = mutableListOf<String>()
            lines += "SE Worker diagnostics"
            lines += "Network: ${currentNetworkLabel()}"
            lines += "Worker: $domain"
            lines += "Target: $target:443"
            lines += ""

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

            if (!stage("DNS") {
                    InetAddress.getAllByName(domain)
                        .mapNotNull { it.hostAddress }
                        .distinct()
                        .joinToString(", ")
                        .ifBlank { "resolved" }
                }) return@thread finishProbe(lines)

            if (!stage("TCP worker:443") {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(domain, 443), CONNECT_TIMEOUT_MS)
                        "local=${socket.localAddress.hostAddress}:${socket.localPort}"
                    }
                }) return@thread finishProbe(lines)

            if (!stage("TLS worker") { probeTls(domain) }) return@thread finishProbe(lines)

            if (!stage("HTTP /health") {
                    val response = httpGet("https://$domain/health")
                    if (response.code != 200 || !response.body.contains("tg-ws-worker")) {
                        error("HTTP ${response.code}: ${response.body.take(160)}")
                    }
                    response.body.take(160)
                }) return@thread finishProbe(lines)

            if (!stage("Worker -> Telegram TCP") {
                    val encodedTarget = URLEncoder.encode(target, Charsets.UTF_8.name())
                    val response = httpGet("https://$domain/probe?dst=$encodedTarget")
                    if (response.code != 200 || !response.body.contains("\"ok\":true")) {
                        error("HTTP ${response.code}: ${response.body.take(220)}")
                    }
                    response.body.take(220)
                }) return@thread finishProbe(lines)

            if (!stage("WebSocket /apiws") {
                    val encodedTarget = URLEncoder.encode(target, Charsets.UTF_8.name())
                    val path = "/apiws?dst=$encodedTarget&dc=2&probe=1"
                    val ws = RawWebSocket.connect(
                        host = domain,
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
                        text.take(220)
                    } finally {
                        ws.close()
                        executor.shutdownNow()
                    }
                }) return@thread finishProbe(lines)

            lines += ""
            lines += "RESULT: WORKER END-TO-END PROBE OK"
            lines += "Android -> Cloudflare Worker -> Telegram TCP is reachable on this network."
            finishProbe(lines)
        }
    }

    private fun probeTls(domain: String): String {
        val socket = SSLSocketFactory.getDefault().createSocket() as SSLSocket
        socket.use {
            it.soTimeout = CONNECT_TIMEOUT_MS
            val parameters = it.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            parameters.serverNames = listOf(SNIHostName(domain))
            it.sslParameters = parameters
            it.connect(InetSocketAddress(domain, 443), CONNECT_TIMEOUT_MS)
            it.startHandshake()
            return "${it.session.protocol} ${it.session.cipherSuite}"
        }
    }

    private fun httpGet(url: String): HttpResult {
        val connection = URL(url).openConnection() as HttpsURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = CONNECT_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun finishProbe(lines: MutableList<String>) {
        if (lines.none { it.startsWith("RESULT:") }) {
            lines += ""
            lines += "RESULT: FAILED"
            lines += "Первый FAIL выше показывает границу, на которой обрывается путь."
        }
        publish(lines)
        runOnUiThread { runButton.isEnabled = true }
    }

    private fun publish(lines: List<String>) {
        runOnUiThread { resultText.text = lines.joinToString("\n") }
    }

    private fun saveWorkerDomain() {
        val domain = normalizeDomain(domainInput.text.toString())
        if (domain.isEmpty()) {
            toast("Укажите домен Worker")
            return
        }
        domainInput.setText(domain)
        saveWorkerDomain(domain)
        toast("Домен сохранён в cfproxy_worker_domain")
    }

    private fun saveWorkerDomain(domain: String) {
        val store = AppConfigStore.from(applicationContext)
        val config = store.loadConfig()
        store.saveConfig(config.copy(cfproxyWorkerDomain = listOf(domain)))
    }

    private fun copyResult() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Worker diagnostics", resultText.text))
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

    private fun normalizeDomain(raw: String): String = raw
        .trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBefore('?')
        .trim()
        .trimEnd('.')

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
        }
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

    private data class HttpResult(val code: Int, val body: String)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val PROBE_RESPONSE_TIMEOUT_MS = 6_000
    }
}
