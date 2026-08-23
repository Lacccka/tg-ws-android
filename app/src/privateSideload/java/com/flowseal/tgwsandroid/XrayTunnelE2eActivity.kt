package com.flowseal.tgwsandroid

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.flowseal.tgwsandroid.config.AppConfig
import com.flowseal.tgwsandroid.config.AppConfigStore
import com.flowseal.tgwsandroid.proxy.RawWebSocket
import com.flowseal.tgwsandroid.proxy.Socks5TlsTransportFactory
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * Private-sideload proof of concept for:
 * VLESS/REALITY (libXray) -> local SOCKS5 -> existing RawWebSocket -> Telegram.
 *
 * This does not start VpnService and does not alter production ProxyServer routing.
 * The VLESS link is held only in this Activity instance and is never copied into
 * diagnostic output, logs or SharedPreferences. Error text is sanitized before
 * it can be copied from the diagnostic screen.
 */
class XrayTunnelE2eActivity : Activity() {
    private lateinit var networkText: TextView
    private lateinit var linkInput: EditText
    private lateinit var resultText: TextView
    private lateinit var runButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SE Xray Tunnel E2E"

        networkText = TextView(this).apply {
            text = "Сеть: ${currentNetworkLabel()}"
        }
        linkInput = EditText(this).apply {
            hint = "vless://…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_URI
            minLines = 3
            maxLines = 7
        }
        resultText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = initialText()
        }
        runButton = Button(this).apply {
            text = "Запустить VLESS → SOCKS → Telegram E2E"
            isAllCaps = false
            setOnClickListener { runProbe() }
        }
        stopButton = Button(this).apply {
            text = "Остановить Xray"
            isAllCaps = false
            setOnClickListener { stopXray() }
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
            addView(TextView(this@XrayTunnelE2eActivity).apply {
                text = "VLESS/REALITY tunnel E2E"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@XrayTunnelE2eActivity).apply {
                text = "Private diagnostic only. Поднимает локальный SOCKS через libXray без Android VPN и проверяет наш обычный WebSocket к Telegram через этот туннель. VLESS-ссылка и адрес сервера не сохраняются и не попадают в результат."
            }, matchWrap(gap))
            addView(networkText, matchWrap(gap))
            addView(linkInput, matchWrap(gap))
            addView(runButton, matchWrap(gap))
            addView(stopButton, matchWrap(gap))
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

    override fun onDestroy() {
        LibXrayCompat.stopBestEffort()
        super.onDestroy()
    }

    private fun runProbe() {
        val link = linkInput.text?.toString()?.trim().orEmpty()
        if (!link.startsWith("vless://", ignoreCase = true)) {
            toast("Нужна VLESS-ссылка")
            return
        }
        val sensitiveValues = sensitiveValues(link)
        val telegramTargets = currentTelegramTargets()

        runButton.isEnabled = false
        val network = currentNetworkLabel()
        networkText.text = "Сеть: $network"
        resultText.text = "Запуск…"

        thread(name = "XrayTunnelE2E") {
            val lines = mutableListOf(
                "SE Xray VLESS/REALITY -> SOCKS5 -> Telegram WebSocket E2E",
                "Network: $network",
                "libXray pinned tag: ${LibXrayCompat.PINNED_TAG}",
                "libXray AAR packaged by Gradle: ${BuildConfig.LIBXRAY_AAR_PACKAGED}",
                "Android VpnService: not used",
                "VLESS link: REDACTED",
                "VLESS endpoint: REDACTED",
                "Telegram targets: current AppConfig.dcIp (${telegramTargets.size})",
                "Upstream DNS mode: hostname=SOCKS5 ATYP=DOMAIN behind tunnel; numeric Telegram IP=no DNS",
                "WebSocket implementation: existing RawWebSocket",
                "",
            )

            try {
                if (!BuildConfig.LIBXRAY_AAR_PACKAGED || !LibXrayCompat.isAvailable()) {
                    lines += "RESULT: LIBXRAY NOT PACKAGED"
                    lines += "Run tools/build-libxray.ps1, then rebuild assemblePrivateSideload."
                    lines += "VERDICT: SOCKS transport code is present, but native Xray core is not inside this APK."
                    return@thread
                }

                val xrayVersion = LibXrayCompat.version().orEmpty()
                lines += "Xray version: ${xrayVersion.ifBlank { "unknown" }}"

                val socksPort = findFreeLoopbackPort()
                lines += "Local SOCKS: 127.0.0.1:$socksPort"
                lines += ""
                lines += "=== Parse VLESS link ==="
                publish(lines)

                val converted = LibXrayCompat.convertShareLink(link)
                val config = buildTunnelConfig(converted, socksPort)
                lines += "OK   libXray parsed VLESS and produced a buildable outbound"
                lines += "outbounds=${config.getJSONArray("outbounds").length()} inbounds=${config.getJSONArray("inbounds").length()}"

                LibXrayCompat.stopBestEffort()
                lines += ""
                lines += "=== Start Xray local SOCKS ==="
                publish(lines)
                val startElapsed = measureTimeMillis {
                    LibXrayCompat.runFromJson(config)
                }
                lines += "runXrayFromJson returned success (${startElapsed}ms)"
                lines += "managed state=${LibXrayCompat.runningState()}"

                if (!waitForPort("127.0.0.1", socksPort, 5_000)) {
                    lines += "FAIL local SOCKS did not start listening within 5000ms"
                    lines += "RESULT: XRAY STARTED BUT SOCKS INBOUND UNAVAILABLE"
                    lines += "VERDICT: inspect generated inbound compatibility before testing the mobile tunnel."
                    return@thread
                }
                lines += "OK   local SOCKS accepts TCP connections"

                val transportFactory = Socks5TlsTransportFactory("127.0.0.1", socksPort)
                var successes = 0
                var failures = 0

                for (target in telegramTargets) {
                    lines += ""
                    lines += "=== ${target.label} ==="
                    lines += "SOCKS CONNECT target=${target.targetHost}:443"
                    lines += "TLS/Host=${target.domain} path=/apiws"
                    publish(lines)

                    try {
                        var ws: RawWebSocket? = null
                        val elapsed = measureTimeMillis {
                            ws = RawWebSocket.connect(
                                host = target.targetHost,
                                domain = target.domain,
                                path = "/apiws",
                                timeoutMs = RawWebSocket.MAX_CONNECT_TIMEOUT_MS,
                                transportFactory = transportFactory,
                            )
                        }
                        successes += 1
                        lines += "OK   WebSocket HTTP 101 through VLESS/SOCKS (${elapsed}ms)"
                        runCatching { ws?.close() }
                    } catch (error: Throwable) {
                        failures += 1
                        lines += "FAIL ${errorSummary(error, sensitiveValues)}"
                    }
                    publish(lines)
                }

                lines += ""
                lines += "RESULT: XRAY TUNNEL E2E COMPLETE"
                lines += "Telegram WebSocket successes: $successes/${telegramTargets.size}"
                lines += "Failures: $failures"
                lines += if (successes > 0) {
                    "VERDICT: VLESS/REALITY + local SOCKS can carry the existing Telegram WebSocket transport on this network. Next step is injecting SocksRawWebSocketConnector into ProxyServer as the mobile fallback and testing a real Telegram session."
                } else {
                    "VERDICT: the VLESS core started, but no Telegram WebSocket reached HTTP 101 through the tunnel. Inspect the supplied VLESS endpoint/server reachability before production routing changes."
                }
            } catch (error: Throwable) {
                lines += ""
                lines += "RESULT: TEST ERROR: ${errorSummary(error, sensitiveValues)}"
            } finally {
                val stopError = LibXrayCompat.stopBestEffort()
                lines += ""
                lines += if (stopError == null) {
                    "Xray stopped"
                } else {
                    "Xray stop warning: ${sanitizeDiagnosticText(stopError, sensitiveValues)}"
                }
                finish(lines)
            }
        }
    }

    private fun buildTunnelConfig(converted: JSONObject, socksPort: Int): JSONObject {
        val outbounds = converted.optJSONArray("outbounds")
            ?: throw IllegalStateException("libXray config has no outbounds")
        if (outbounds.length() == 0) throw IllegalStateException("libXray config has no valid outbound")

        // This diagnostic accepts exactly one VLESS link and deliberately keeps
        // only its first validated outbound so no imported routing can bypass it.
        val firstOutbound = JSONObject(outbounds.getJSONObject(0).toString())
        val cleanOutbounds = JSONArray().put(firstOutbound)

        val socksInbound = JSONObject()
            .put("tag", "tgws-xray-socks")
            .put("listen", "127.0.0.1")
            .put("port", socksPort)
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject()
                    .put("auth", "noauth")
                    .put("udp", false),
            )

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(socksInbound))
            .put("outbounds", cleanOutbounds)
    }

    private fun currentTelegramTargets(): List<TelegramTarget> {
        val configured = AppConfigStore.getConfig(this).dcIp
            .mapNotNull(::parseDcRedirect)
            .distinctBy { it.dc }
        val redirects = if (configured.isNotEmpty()) {
            configured
        } else {
            AppConfig.DEFAULT_DC_IP.mapNotNull(::parseDcRedirect)
        }

        return redirects.map { redirect ->
            val websocketDc = if (redirect.dc == 203) 2 else redirect.dc
            TelegramTarget(
                label = "Telegram DC${redirect.dc} WebSocket",
                targetHost = redirect.host,
                domain = "kws$websocketDc.web.telegram.org",
            )
        }
    }

    private fun parseDcRedirect(value: String): DcRedirect? {
        val parts = value.split(':', limit = 2)
        if (parts.size != 2) return null
        val dc = parts[0].trim().toIntOrNull() ?: return null
        val host = parts[1].trim()
        if (host.isBlank()) return null
        return DcRedirect(dc, host)
    }

    private fun stopXray() {
        thread(name = "XrayStop") {
            val warning = LibXrayCompat.stopBestEffort()
            runOnUiThread {
                toast(if (warning == null) "Xray остановлен" else "Xray остановлен с предупреждением")
            }
        }
    }

    private fun findFreeLoopbackPort(): Int = ServerSocket(0, 1).use { it.localPort }

    private fun waitForPort(host: String, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 150)
                    return true
                }
            } catch (_: Throwable) {
                Thread.sleep(100)
            }
        }
        return false
    }

    private fun sensitiveValues(link: String): List<String> = buildList {
        add(link)
        runCatching { Uri.parse(link) }.getOrNull()?.let { uri ->
            uri.host?.takeIf { it.isNotBlank() }?.let(::add)
            uri.userInfo?.takeIf { it.isNotBlank() }?.let(::add)
        }
        UUID_REGEX.findAll(link).forEach { add(it.value) }
    }.distinct().sortedByDescending { it.length }

    private fun initialText(): String = if (BuildConfig.LIBXRAY_AAR_PACKAGED) {
        "libXray AAR обнаружен. Вставьте рабочую VLESS/REALITY ссылку и запустите E2E."
    } else {
        "libXray AAR пока не упакован. Сначала выполните tools/build-libxray.ps1 и пересоберите privateSideload."
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Xray tunnel E2E diagnostics", resultText.text))
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

    private fun errorSummary(error: Throwable, sensitiveValues: List<String>): String {
        val root = generateSequence(error) { it.cause }.last()
        val message = sanitizeDiagnosticText(root.message.orEmpty(), sensitiveValues)
            .replace('\n', ' ')
            .take(500)
        return if (message.isBlank()) root::class.java.simpleName else "${root::class.java.simpleName}: $message"
    }

    private fun sanitizeDiagnosticText(text: String, sensitiveValues: List<String>): String {
        var sanitized = VLESS_URL_REGEX.replace(text, "vless://<redacted>")
        for (value in sensitiveValues) {
            if (value.length >= 4) sanitized = sanitized.replace(value, "<redacted>")
        }
        sanitized = UUID_REGEX.replace(sanitized, "<uuid-redacted>")
        sanitized = VLESS_SECRET_QUERY_REGEX.replace(sanitized) { match ->
            "${match.groupValues[1]}=<redacted>"
        }
        return sanitized
    }

    private fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = topMargin }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private data class DcRedirect(
        val dc: Int,
        val host: String,
    )

    private data class TelegramTarget(
        val label: String,
        val targetHost: String,
        val domain: String,
    )

    companion object {
        private val UUID_REGEX = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
        private val VLESS_URL_REGEX = Regex("(?i)vless://\\S+")
        private val VLESS_SECRET_QUERY_REGEX = Regex("(?i)(pbk|password|sid|pqv|spx)=([^&\\s]+)")
    }
}
