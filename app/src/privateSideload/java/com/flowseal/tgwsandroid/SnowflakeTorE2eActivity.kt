package com.flowseal.tgwsandroid

import IPtProxy.Controller
import IPtProxy.IPtProxy
import IPtProxy.OnTransportEvents
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.IBinder
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.flowseal.tgwsandroid.config.AppConfig
import com.flowseal.tgwsandroid.config.AppConfigStore
import com.flowseal.tgwsandroid.proxy.RawWebSocket
import com.flowseal.tgwsandroid.proxy.SocksRawWebSocketConnector
import org.torproject.jni.TorService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

/**
 * Private-sideload zero-config censorship-circumvention proof of concept:
 * Snowflake (IPtProxy) -> embedded Tor -> local Tor SOCKS -> existing RawWebSocket -> Telegram.
 *
 * No VpnService, user-owned bridge/VPS, VLESS profile, domain, or secret is required.
 * Production ProxyServer routing is intentionally untouched until this path proves it can
 * reach a Telegram WebSocket HTTP 101 on the affected mobile network.
 */
class SnowflakeTorE2eActivity : Activity() {
    private lateinit var networkText: TextView
    private lateinit var resultText: TextView
    private lateinit var runButton: Button

    @Volatile
    private var activeController: Controller? = null

    @Volatile
    private var activeConnection: ServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "SE Snowflake/Tor E2E"

        networkText = TextView(this).apply {
            text = "Сеть: ${currentNetworkLabel()}"
        }
        resultText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = "Zero-config diagnostic. Никакой VLESS/VPS/bridge-конфигурации от пользователя не требуется."
        }
        runButton = Button(this).apply {
            text = "Запустить Snowflake → Tor → Telegram E2E"
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
            addView(TextView(this@SnowflakeTorE2eActivity).apply {
                text = "Snowflake/Tor tunnel E2E"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())
            addView(TextView(this@SnowflakeTorE2eActivity).apply {
                text = "Поднимает встроенный Snowflake pluggable transport, запускает Tor через публичный Snowflake bridge, ждёт построения Tor circuit и затем проверяет существующий Telegram WebSocket через локальный Tor SOCKS. Android VPN не используется."
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

    override fun onDestroy() {
        cleanupActiveTransport()
        super.onDestroy()
    }

    private fun runProbe() {
        if (!BuildConfig.SNOWFLAKE_TOR_PACKAGED) {
            toast("Snowflake/Tor не упакован в эту сборку")
            return
        }

        runButton.isEnabled = false
        val network = currentNetworkLabel()
        networkText.text = "Сеть: $network"
        resultText.text = "Запуск…"

        thread(name = "SnowflakeTorE2E") {
            val lines = mutableListOf(
                "SE Snowflake -> embedded Tor -> SOCKS -> Telegram WebSocket E2E",
                "Network: $network",
                "tor-android: 0.4.9.11",
                "IPtProxy: 5.5.1 (Snowflake 2.14.1)",
                "User-owned VPS/bridge/VLESS: not required",
                "Android VpnService: not used",
                "Production routing modified: no",
                "Telegram targets: current AppConfig.dcIp",
                "",
            )

            var controller: Controller? = null
            var connection: ServiceConnection? = null
            try {
                lines += "=== Start Snowflake pluggable transport ==="
                publish(lines)

                val stateDir = File(noBackupFilesDir, "snowflake-pt").apply { mkdirs() }
                val transportEvents = object : OnTransportEvents {
                    override fun connected(name: String?) {
                        appendAsync(lines, "PT EVENT connected=${name ?: "unknown"}")
                    }

                    override fun error(name: String?, error: Exception?) {
                        appendAsync(
                            lines,
                            "PT EVENT error=${name ?: "unknown"}: ${error?.javaClass?.simpleName ?: "unknown"}: ${sanitize(error?.message)}",
                        )
                    }

                    override fun stopped(name: String?, error: Exception?) {
                        val suffix = error?.let { ": ${it.javaClass.simpleName}: ${sanitize(it.message)}" }.orEmpty()
                        appendAsync(lines, "PT EVENT stopped=${name ?: "unknown"}$suffix")
                    }
                }

                controller = Controller(
                    stateDir.absolutePath,
                    true,
                    false,
                    "INFO",
                    transportEvents,
                ).also {
                    it.snowflakeBrokerUrl = SNOWFLAKE_BROKER_URL
                    it.snowflakeFrontDomains = SNOWFLAKE_FRONT_DOMAINS
                    it.snowflakeIceServers = SNOWFLAKE_ICE_SERVERS
                    it.snowflakeAmpCacheUrl = ""
                    it.snowflakeSqsUrl = ""
                    it.snowflakeSqsCreds = ""
                }
                activeController = controller

                val ptStartMs = measureTimeMillis {
                    controller.start(IPtProxy.Snowflake, null)
                }
                val ptPort = controller.port(IPtProxy.Snowflake).toInt()
                check(ptPort in 1..65535) { "Snowflake listener returned invalid port: $ptPort" }
                lines += "OK   Snowflake SOCKS listener started (${ptStartMs}ms)"
                lines += "Snowflake PT listener: 127.0.0.1:$ptPort"

                lines += ""
                lines += "=== Configure embedded Tor ==="
                val torrc = TorService.getTorrc(this@SnowflakeTorE2eActivity)
                torrc.parentFile?.mkdirs()
                torrc.writeText(buildTorrc(ptPort))
                lines += "OK   torrc written: UseBridges=1, ClientTransportPlugin=snowflake"
                lines += "Snowflake bridge count: ${SNOWFLAKE_BRIDGES.size}"
                publish(lines)

                val serviceLatch = CountDownLatch(1)
                var torService: TorService? = null
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        torService = (service as? TorService.LocalBinder)?.service
                        serviceLatch.countDown()
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        torService = null
                    }
                }
                activeConnection = connection

                lines += ""
                lines += "=== Start embedded Tor ==="
                publish(lines)
                val bound = bindService(
                    Intent(this@SnowflakeTorE2eActivity, TorService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
                check(bound) { "bindService(TorService) returned false" }
                check(serviceLatch.await(TOR_SERVICE_BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "TorService did not bind within ${TOR_SERVICE_BIND_TIMEOUT_SECONDS}s"
                }
                val service = checkNotNull(torService) { "TorService binder returned no service" }
                lines += "OK   TorService bound"

                val controlDeadline = System.nanoTime() + TOR_CONTROL_TIMEOUT_MS * 1_000_000L
                while (service.torControlConnection == null && System.nanoTime() < controlDeadline) {
                    Thread.sleep(100)
                }
                check(service.torControlConnection != null) {
                    "Tor control connection unavailable after ${TOR_CONTROL_TIMEOUT_MS}ms"
                }
                lines += "OK   Tor control connection ready"

                lines += ""
                lines += "=== Tor bootstrap through Snowflake ==="
                publish(lines)
                waitForTorBootstrap(service, lines)

                val torSocksPort = service.socksPort
                check(torSocksPort in 1..65535) { "Tor SOCKS returned invalid port: $torSocksPort" }
                lines += "OK   Tor circuit ready"
                lines += "Tor SOCKS: 127.0.0.1:$torSocksPort"

                lines += ""
                lines += "=== Telegram WebSocket through Tor SOCKS ==="
                publish(lines)
                val connector = SocksRawWebSocketConnector("127.0.0.1", torSocksPort)
                val targets = currentTelegramTargets()
                var successes = 0
                var failures = 0

                for (target in targets) {
                    lines += ""
                    lines += "--- ${target.label} ---"
                    lines += "target=${target.targetHost}:443 Host/SNI=${target.domain} path=/apiws"
                    publish(lines)

                    try {
                        var stream: com.flowseal.tgwsandroid.proxy.WebSocketBinaryStream? = null
                        val elapsed = measureTimeMillis {
                            stream = connector.connect(
                                targetHost = target.targetHost,
                                domain = target.domain,
                                path = "/apiws",
                                timeoutMs = RawWebSocket.MAX_CONNECT_TIMEOUT_MS,
                            )
                        }
                        successes += 1
                        lines += "OK   WebSocket HTTP 101 through Snowflake/Tor (${elapsed}ms)"
                        runCatching { stream?.close() }
                    } catch (error: Throwable) {
                        failures += 1
                        lines += "FAIL ${errorSummary(error)}"
                    }
                    publish(lines)
                }

                lines += ""
                lines += "RESULT: SNOWFLAKE/TOR E2E COMPLETE"
                lines += "Telegram WebSocket successes: $successes/${targets.size}"
                lines += "Failures: $failures"
                lines += if (successes > 0) {
                    "VERDICT: Snowflake + embedded Tor can carry the existing Telegram WebSocket transport on this mobile network. Next step: inject the same Tor SOCKS connector into a private ProxyServer session and test real Telegram traffic."
                } else {
                    "VERDICT: Tor bootstrapped through Snowflake, but Telegram WebSocket did not reach HTTP 101. The censorship-circumvention path itself works; next isolate Telegram-via-Tor versus CF-proxy-via-Tor before changing production routing."
                }
            } catch (error: TorBootstrapException) {
                lines += ""
                lines += "RESULT: TOR BOOTSTRAP FAILED: ${errorSummary(error)}"
                lines += "VERDICT: Snowflake/public bridge path did not produce a Tor circuit on this network. Do not return to Cloudflare TLS rotation; next public no-server candidates are WebTunnel/DNSTT."
            } catch (error: Throwable) {
                lines += ""
                lines += "RESULT: TEST ERROR: ${errorSummary(error)}"
            } finally {
                cleanupTransport(connection, controller)
                activeConnection = null
                activeController = null
                lines += ""
                lines += "Snowflake/Tor stopped"
                finish(lines)
            }
        }
    }

    private fun waitForTorBootstrap(service: TorService, lines: MutableList<String>) {
        val deadline = System.nanoTime() + TOR_BOOTSTRAP_TIMEOUT_MS * 1_000_000L
        var lastProgress = -1
        var lastPhase = ""

        while (System.nanoTime() < deadline) {
            val phase = service.getInfo("status/bootstrap-phase").orEmpty()
            val progress = BOOTSTRAP_PROGRESS_REGEX.find(phase)?.groupValues?.getOrNull(1)?.toIntOrNull()

            if (progress != null && progress != lastProgress) {
                lastProgress = progress
                lastPhase = sanitize(phase)
                lines += "Tor bootstrap: $progress%"
                publish(lines)
            }
            if (progress != null && progress >= 100) return

            Thread.sleep(500)
        }

        throw TorBootstrapException(
            "Tor bootstrap timeout after ${TOR_BOOTSTRAP_TIMEOUT_MS / 1000}s; lastProgress=$lastProgress phase=${sanitize(lastPhase)}",
        )
    }

    private fun buildTorrc(ptPort: Int): String = buildString {
        appendLine("UseBridges 1")
        appendLine("ClientTransportPlugin snowflake socks5 127.0.0.1:$ptPort")
        for (bridge in SNOWFLAKE_BRIDGES) appendLine("Bridge $bridge")
    }

    private fun currentTelegramTargets(): List<TelegramTarget> {
        val configured = AppConfigStore.getConfig(this).dcIp
            .mapNotNull(::parseDcRedirect)
            .distinctBy { it.dc }
        val redirects = if (configured.isNotEmpty()) configured else AppConfig.DEFAULT_DC_IP.mapNotNull(::parseDcRedirect)

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

    private fun cleanupActiveTransport() {
        val connection = activeConnection
        val controller = activeController
        activeConnection = null
        activeController = null
        cleanupTransport(connection, controller)
    }

    private fun cleanupTransport(connection: ServiceConnection?, controller: Controller?) {
        if (connection != null) {
            runCatching { unbindService(connection) }
        }
        runCatching { stopService(Intent(this, TorService::class.java)) }
        if (controller != null) {
            runCatching { controller.stop(IPtProxy.Snowflake) }
        }
    }

    private fun appendAsync(lines: MutableList<String>, line: String) {
        synchronized(lines) {
            lines += line
            publish(lines)
        }
    }

    private fun publish(lines: List<String>) {
        val snapshot = synchronized(lines) { lines.toList() }
        runOnUiThread { resultText.text = snapshot.joinToString("\n") }
    }

    private fun finish(lines: List<String>) {
        publish(lines)
        runOnUiThread {
            runButton.isEnabled = true
            networkText.text = "Сеть: ${currentNetworkLabel()}"
        }
    }

    private fun copyResult() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Snowflake Tor E2E diagnostics", resultText.text))
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
        val message = sanitize(root.message).take(600)
        return if (message.isBlank()) root::class.java.simpleName else "${root::class.java.simpleName}: $message"
    }

    private fun sanitize(value: String?): String = value.orEmpty()
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(1000)

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

    private class TorBootstrapException(message: String) : IllegalStateException(message)

    companion object {
        private const val SNOWFLAKE_BROKER_URL = "https://1098762253.rsc.cdn77.org/"
        private const val SNOWFLAKE_FRONT_DOMAINS = "app.datapacket.com,www.datapacket.com"
        private const val SNOWFLAKE_ICE_SERVERS =
            "stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478," +
                "stun:stun.mixvoip.com:3478,stun:stun.telnyx.com:3478,stun:stun.hot-chilli.net:3478," +
                "stun:stun.fitauto.ru:3478,stun:stun.m-online.net:3478"

        // Snapshot of Orbot's built-in Snowflake bridges (2026-08-04). These are
        // public Tor bridge descriptors, not user secrets. Keep both current
        // entries so Tor can retry another bridge identity without user input.
        private val SNOWFLAKE_BRIDGES = listOf(
            "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=$SNOWFLAKE_ICE_SERVERS utls-imitate=hellorandomizedalpn",
            "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=https://1098762253.rsc.cdn77.org/ fronts=app.datapacket.com,www.datapacket.com ice=$SNOWFLAKE_ICE_SERVERS utls-imitate=hellorandomizedalpn",
        )

        private val BOOTSTRAP_PROGRESS_REGEX = Regex("PROGRESS=(\\d+)")
        private const val TOR_SERVICE_BIND_TIMEOUT_SECONDS = 10L
        private const val TOR_CONTROL_TIMEOUT_MS = 15_000L
        private const val TOR_BOOTSTRAP_TIMEOUT_MS = 90_000L
    }
}
