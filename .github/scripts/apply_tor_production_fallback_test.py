from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, got {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


service = "app/src/main/java/com/flowseal/tgwsandroid/service/ProxyForegroundService.kt"
replace_once(
    service,
    "import com.flowseal.tgwsandroid.proxy.ProxyLogger\n",
    "import com.flowseal.tgwsandroid.proxy.ProxyLogger\nimport com.flowseal.tgwsandroid.proxy.DefaultRawWebSocketConnector\nimport com.flowseal.tgwsandroid.proxy.RawWebSocketConnector\n",
)
replace_once(
    service,
    "import java.io.File\n",
    "import java.io.File\nimport java.net.ConnectException\n",
)
replace_once(
    service,
    "    private var torFallbackRuntime: TorFallbackRuntime? = null\n",
    "    private var torFallbackRuntime: TorFallbackRuntime? = null\n    @Volatile\n    private var forceOrdinaryRouteFailureForTorTest: Boolean = false\n",
)
replace_once(
    service,
    '''            ACTION_STOP_FROM_UI, ACTION_STOP_FROM_NOTIFICATION, ACTION_STOP_FROM_TILE, ACTION_STOP_LEGACY -> {\n                val stopSource = stopSourceForAction(intent.action)\n''',
    '''            ACTION_STOP_FROM_UI, ACTION_STOP_FROM_NOTIFICATION, ACTION_STOP_FROM_TILE, ACTION_STOP_LEGACY -> {\n                setTorFallbackTestOverride(false)\n                val stopSource = stopSourceForAction(intent.action)\n''',
)
replace_once(
    service,
    '''            ACTION_RESTART_FROM_UI -> {\n                State.markServiceEvent("restart_begin")\n''',
    '''            ACTION_RESTART_FROM_UI -> {\n                setTorFallbackTestOverride(torFallbackTestRequested(intent))\n                State.markServiceEvent("restart_begin")\n''',
)
replace_once(
    service,
    '''            ACTION_START, null -> {\n                State.addLog("=== Proxy start ${LocalDateTime.now().format(RuntimeLogStore.TIME_FORMATTER)} ===", LogSeverity.INFO, "ui")\n''',
    '''            ACTION_START, null -> {\n                setTorFallbackTestOverride(torFallbackTestRequested(intent))\n                State.addLog("=== Proxy start ${LocalDateTime.now().format(RuntimeLogStore.TIME_FORMATTER)} ===", LogSeverity.INFO, "ui")\n''',
)
replace_once(
    service,
    '''    private fun startProxyAsync() {\n''',
    '''    private fun torFallbackTestRequested(intent: Intent?): Boolean =\n        BuildConfig.SNOWFLAKE_TOR_PACKAGED &&\n            intent?.getBooleanExtra(EXTRA_FORCE_ORDINARY_ROUTE_FAILURE_FOR_TOR_TEST, false) == true\n\n    private fun setTorFallbackTestOverride(enabled: Boolean) {\n        forceOrdinaryRouteFailureForTorTest = enabled && BuildConfig.SNOWFLAKE_TOR_PACKAGED\n        State.setTorFallbackTestOverrideActive(forceOrdinaryRouteFailureForTorTest)\n        if (forceOrdinaryRouteFailureForTorTest) {\n            State.addLog(\n                "PRIVATE TEST: ordinary direct/CF outbound is forced unavailable on mobile so production AUTO must reach Tor/Snowflake",\n                LogSeverity.WARN,\n                "service",\n            )\n        } else {\n            State.addLog("PRIVATE TEST: Tor fallback ordinary-route override disabled", LogSeverity.INFO, "service")\n        }\n    }\n\n    private fun ordinaryWebSocketConnectorForCurrentRun(): RawWebSocketConnector {\n        if (!forceOrdinaryRouteFailureForTorTest) return DefaultRawWebSocketConnector\n        return object : RawWebSocketConnector {\n            override fun connect(targetHost: String, domain: String, path: String, timeoutMs: Int) =\n                if (shouldForceOrdinaryRouteFailureForTorTest()) {\n                    throw ConnectException("privateSideload test forced ordinary route unavailable for $domain")\n                } else {\n                    DefaultRawWebSocketConnector.connect(targetHost, domain, path, timeoutMs)\n                }\n\n            override fun connectWithSni(\n                targetHost: String,\n                domain: String,\n                path: String,\n                timeoutMs: Int,\n                sniHost: String,\n            ) = if (shouldForceOrdinaryRouteFailureForTorTest()) {\n                throw ConnectException("privateSideload test forced ordinary route unavailable for $domain (SNI=$sniHost)")\n            } else {\n                DefaultRawWebSocketConnector.connectWithSni(targetHost, domain, path, timeoutMs, sniHost)\n            }\n        }\n    }\n\n    private fun shouldForceOrdinaryRouteFailureForTorTest(): Boolean =\n        forceOrdinaryRouteFailureForTorTest &&\n            BuildConfig.SNOWFLAKE_TOR_PACKAGED &&\n            (State.networkStatus.equals("mobile", ignoreCase = true) || State.networkStatus.equals("cellular", ignoreCase = true))\n\n    private fun startProxyAsync() {\n''',
)
replace_once(
    service,
    '''            val server = ProxyServer(\n                config = serverConfig,\n                torSnowflakeConnector = runtime?.connector,\n                logger = logger,\n            )\n''',
    '''            val server = ProxyServer(\n                config = serverConfig,\n                webSocketConnector = ordinaryWebSocketConnectorForCurrentRun(),\n                torSnowflakeConnector = runtime?.connector,\n                logger = logger,\n            )\n''',
)
replace_once(
    service,
    '''            val line = "watchdog: running=${server?.isRunning == true} ${compactStats(stats, torRuntimeSnapshot)} " +\n                "network=${State.networkStatus} route=${stats?.effectiveRouteMode ?: "unknown"} battery=${State.batteryOptimizationStatus}"\n''',
    '''            val line = "watchdog: running=${server?.isRunning == true} ${compactStats(stats, torRuntimeSnapshot)} " +\n                "torTestOverride=${State.isTorFallbackTestOverrideActive()} " +\n                "network=${State.networkStatus} route=${stats?.effectiveRouteMode ?: "unknown"} battery=${State.batteryOptimizationStatus}"\n''',
)
replace_once(
    service,
    '''        const val ACTION_RESTART_FROM_UI = "com.flowseal.tgwsandroid.action.RESTART_PROXY_FROM_UI"\n''',
    '''        const val ACTION_RESTART_FROM_UI = "com.flowseal.tgwsandroid.action.RESTART_PROXY_FROM_UI"\n        const val EXTRA_FORCE_ORDINARY_ROUTE_FAILURE_FOR_TOR_TEST =\n            "com.flowseal.tgwsandroid.extra.FORCE_ORDINARY_ROUTE_FAILURE_FOR_TOR_TEST"\n''',
)
replace_once(
    service,
    '''        fun restartIntent(context: Context): Intent = Intent(context, ProxyForegroundService::class.java).setAction(ACTION_RESTART_FROM_UI)\n\n        fun stopFromTileIntent''',
    '''        fun restartIntent(context: Context): Intent = Intent(context, ProxyForegroundService::class.java).setAction(ACTION_RESTART_FROM_UI)\n\n        fun torFallbackProductionTestRestartIntent(context: Context, enabled: Boolean): Intent =\n            restartIntent(context).putExtra(EXTRA_FORCE_ORDINARY_ROUTE_FAILURE_FOR_TOR_TEST, enabled)\n\n        fun stopFromTileIntent''',
)
replace_once(
    service,
    '''        @Volatile\n        private var torFallbackRuntimeSnapshot: TorFallbackRuntimeSnapshot? = null\n''',
    '''        @Volatile\n        private var torFallbackRuntimeSnapshot: TorFallbackRuntimeSnapshot? = null\n        @Volatile\n        private var torFallbackTestOverrideActive: Boolean = false\n''',
)
replace_once(
    service,
    '''        fun updateTorFallbackRuntimeSnapshot(snapshot: TorFallbackRuntimeSnapshot?) {\n            torFallbackRuntimeSnapshot = snapshot\n        }\n\n        fun setLiveStatsProvider''',
    '''        fun updateTorFallbackRuntimeSnapshot(snapshot: TorFallbackRuntimeSnapshot?) {\n            torFallbackRuntimeSnapshot = snapshot\n        }\n\n        fun currentTorFallbackRuntimeSnapshot(): TorFallbackRuntimeSnapshot? = torFallbackRuntimeSnapshot\n\n        fun setTorFallbackTestOverrideActive(active: Boolean) {\n            torFallbackTestOverrideActive = active\n        }\n\n        fun isTorFallbackTestOverrideActive(): Boolean = torFallbackTestOverrideActive\n\n        fun setLiveStatsProvider''',
)
replace_once(
    service,
    '''                    stats = stats,\n                    torFallbackRuntime = torFallbackRuntimeSnapshot,\n''',
    '''                    stats = stats,\n                    torFallbackRuntime = torFallbackRuntimeSnapshot,\n                    torFallbackTestOverrideActive = torFallbackTestOverrideActive,\n''',
)

report = "app/src/main/java/com/flowseal/tgwsandroid/service/DiagnosticReport.kt"
replace_once(
    report,
    '''    val logs: List<RuntimeLogEntry>,\n    val torFallbackRuntime: TorFallbackRuntimeSnapshot? = null,\n)\n''',
    '''    val logs: List<RuntimeLogEntry>,\n    val torFallbackRuntime: TorFallbackRuntimeSnapshot? = null,\n    val torFallbackTestOverrideActive: Boolean = false,\n)\n''',
)
replace_once(
    report,
    '''        stats: ProxyServerStats? = null,\n        torFallbackRuntime: TorFallbackRuntimeSnapshot? = null,\n''',
    '''        stats: ProxyServerStats? = null,\n        torFallbackRuntime: TorFallbackRuntimeSnapshot? = null,\n        torFallbackTestOverrideActive: Boolean = false,\n''',
)
replace_once(
    report,
    '''        logs = logs,\n        torFallbackRuntime = torFallbackRuntime,\n    )\n''',
    '''        logs = logs,\n        torFallbackRuntime = torFallbackRuntime,\n        torFallbackTestOverrideActive = torFallbackTestOverrideActive,\n    )\n''',
)
replace_once(
    report,
    '''        appendTorFallbackRuntime(snapshot.torFallbackRuntime)\n''',
    '''        appendTorFallbackRuntime(snapshot.torFallbackRuntime, snapshot.torFallbackTestOverrideActive)\n''',
)
replace_once(
    report,
    '''    private fun StringBuilder.appendTorFallbackRuntime(runtime: TorFallbackRuntimeSnapshot?) {\n        appendLine("Tor/Snowflake runtime:")\n        appendLine("  available: ${runtime != null}")\n''',
    '''    private fun StringBuilder.appendTorFallbackRuntime(runtime: TorFallbackRuntimeSnapshot?, testOverrideActive: Boolean) {\n        appendLine("Tor/Snowflake runtime:")\n        appendLine("  available: ${runtime != null}")\n        appendLine("  productionFallbackTestOverrideActive: $testOverrideActive")\n''',
)

report_test = "app/src/test/java/com/flowseal/tgwsandroid/service/TorFallbackDiagnosticReportTest.kt"
replace_once(
    report_test,
    '''            torFallbackRuntime = TorFallbackRuntimeSnapshot(\n''',
    '''            torFallbackTestOverrideActive = true,\n            torFallbackRuntime = TorFallbackRuntimeSnapshot(\n''',
)
replace_once(
    report_test,
    '''        assertTrue(report.contains("  available: true"))\n''',
    '''        assertTrue(report.contains("  available: true"))\n        assertTrue(report.contains("  productionFallbackTestOverrideActive: true"))\n''',
)

proxy_test = "app/src/test/java/com/flowseal/tgwsandroid/proxy/ProxyServerTest.kt"
needle = '''    @Test\n    fun autoMobileUsesTorSnowflakeAfterOrdinaryRoutesAreUnavailable() {\n'''
insert = '''    @Test\n    fun autoMobileForcedOrdinaryConnectorFailuresReachTorThroughProductionRouting() {\n        val server = FakeTcpServerTransport()\n        val ordinaryDomains = CopyOnWriteArrayList<String>()\n        val ordinary = RawWebSocketConnector { _, domain, _, _ ->\n            ordinaryDomains.add(domain)\n            throw IOException("private test ordinary route unavailable for $domain")\n        }\n        val tor = RecordingConnector(FakeWebSocketBinaryStream())\n        val proxy = newProxy(\n            server = server,\n            connector = ordinary,\n            torSnowflakeConnector = tor,\n            config = baseConfig().copy(\n                routeMode = NetworkRouteMode.AUTO,\n                networkStatus = "mobile",\n                cfproxyEnabled = true,\n                cfPoolEnabled = false,\n                cfProxyDomains = listOf("cf.example"),\n                torSnowflakeFallbackEnabled = true,\n            ),\n        )\n\n        proxy.start()\n        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))\n        waitUntil("forced ordinary route failure should reach Tor/Snowflake") {\n            proxy.stats().lastRouteUsed == TOR_SNOWFLAKE_ROUTE_TYPE\n        }\n        proxy.stop()\n\n        val stats = proxy.stats()\n        assertTrue("production CF path should be attempted before Tor", ordinaryDomains.contains("kws2.cf.example"))\n        assertEquals(1L, stats.cfProxyErrors)\n        assertEquals(1L, stats.torSnowflakeAttempts)\n        assertEquals(1L, stats.torSnowflakeSuccesses)\n        assertEquals(0L, stats.torSnowflakeFailures)\n        assertEquals(TOR_SNOWFLAKE_ROUTE_TYPE, stats.lastRouteUsed)\n        assertEquals(listOf("kws2.web.telegram.org"), tor.domains)\n    }\n\n    @Test\n    fun autoMobileUsesTorSnowflakeAfterOrdinaryRoutesAreUnavailable() {\n'''
replace_once(proxy_test, needle, insert)

hub = "app/src/privateSideload/java/com/flowseal/tgwsandroid/DiagnosticsHubActivity.kt"
replace_once(
    hub,
    '''            addSection("Актуальные", gap)\n            addProbeButton(\n                label = "Snowflake/Tor — реальный Telegram",\n''',
    '''            addSection("Актуальные", gap)\n            addProbeButton(\n                label = "Production AUTO → Tor fallback",\n                description = "Главный production-тест: обычный ProxyForegroundService и обычный AUTO routing. В privateSideload direct/CF outbound намеренно получают ConnectException на мобильной сети, после чего штатный routing должен дойти до уже встроенного Tor/Snowflake. В экспортированной диагностике тестовый override отмечается явно.",\n                target = TorProductionFallbackTestActivity::class.java,\n                gap = gap,\n            )\n            addProbeButton(\n                label = "Snowflake/Tor — реальный Telegram",\n''',
)

manifest = "app/src/privateSideload/AndroidManifest.xml"
replace_once(
    manifest,
    '''        <activity android:name=".SnowflakeTorProxyActivity" android:exported="false" />\n''',
    '''        <activity android:name=".TorProductionFallbackTestActivity" android:exported="false" />\n        <activity android:name=".SnowflakeTorProxyActivity" android:exported="false" />\n''',
)

activity = Path("app/src/privateSideload/java/com/flowseal/tgwsandroid/TorProductionFallbackTestActivity.kt")
activity.write_text(r'''package com.flowseal.tgwsandroid

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import com.flowseal.tgwsandroid.service.ProxyForegroundService
import com.flowseal.tgwsandroid.service.ProxyRuntimeConfig

/**
 * Private-sideload controller for the decisive production fallback test.
 *
 * It does not run a second ProxyServer implementation. Instead it restarts the
 * ordinary ProxyForegroundService with a private-only outbound connector that
 * fails direct/CF connects on mobile. The unchanged production routing logic
 * must then reach the independently managed Tor/Snowflake connector.
 */
class TorProductionFallbackTestActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 750L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Production AUTO → Tor"

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val gap = (10 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)

            addView(TextView(this@TorProductionFallbackTestActivity).apply {
                text = "Production AUTO → Tor fallback"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }, matchWrap())

            addView(TextView(this@TorProductionFallbackTestActivity).apply {
                text = "Только privateSideload. На мобильной сети обычный direct/CF connector будет намеренно возвращать ConnectException. Сам ProxyServer, AUTO routing, локальный endpoint и Tor runtime остаются production-кодом. После запуска дождитесь Tor ready=true / bootstrap=100, затем подключите Telegram."
            }, matchWrap(gap))

            statusView = TextView(this@TorProductionFallbackTestActivity).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            addView(statusView, matchWrap(gap))

            addView(Button(this@TorProductionFallbackTestActivity).apply {
                text = "Запустить production fallback test"
                isAllCaps = false
                setOnClickListener { startForcedFallbackTest() }
            }, matchWrap(gap))

            addView(Button(this@TorProductionFallbackTestActivity).apply {
                text = "Подключить Telegram"
                isAllCaps = false
                setOnClickListener { openTelegramProxyLink() }
            }, matchWrap(gap))

            addView(Button(this@TorProductionFallbackTestActivity).apply {
                text = "Вернуть обычную маршрутизацию"
                isAllCaps = false
                setOnClickListener { restoreOrdinaryRouting() }
            }, matchWrap(gap))

            addView(TextView(this@TorProductionFallbackTestActivity).apply {
                text = "Ожидаемый успешный результат: testOverride=true, Tor ready=true, torSnowflakeSuccesses > 0 и lastRoute=tor-snowflake. При переходе на Wi‑Fi forced failure автоматически не применяется, но тестовый флаг останется виден до обычного restart/restore."
            }, matchWrap(gap))
        }

        setContentView(ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun startForcedFallbackTest() {
        ProxyRuntimeConfig.initialize(applicationContext)
        val config = ProxyRuntimeConfig.appConfig(applicationContext)
        if (config.routeMode != NetworkRouteMode.AUTO) {
            Toast.makeText(this, "Для этого теста выберите режим Авто (AUTO)", Toast.LENGTH_LONG).show()
            return
        }
        if (!ProxyForegroundService.State.networkStatus.equals("mobile", ignoreCase = true)) {
            Toast.makeText(this, "Выключите Wi‑Fi и оставьте мобильную сеть", Toast.LENGTH_LONG).show()
            return
        }
        startProxyService(ProxyForegroundService.torFallbackProductionTestRestartIntent(this, enabled = true))
        Toast.makeText(this, "Тест запущен. Дождитесь Tor ready=true", Toast.LENGTH_LONG).show()
    }

    private fun restoreOrdinaryRouting() {
        startProxyService(ProxyForegroundService.torFallbackProductionTestRestartIntent(this, enabled = false))
        Toast.makeText(this, "Обычная маршрутизация восстанавливается", Toast.LENGTH_LONG).show()
    }

    private fun startProxyService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun openTelegramProxyLink() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ProxyRuntimeConfig.telegramProxyUri(this)))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Не удалось открыть Telegram", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderStatus() {
        val runtime = ProxyForegroundService.State.currentTorFallbackRuntimeSnapshot()
        val stats = ProxyForegroundService.State.stats()
        statusView.text = buildString {
            appendLine("proxyRunning=${ProxyForegroundService.State.running}")
            appendLine("network=${ProxyForegroundService.State.networkStatus}")
            appendLine("testOverride=${ProxyForegroundService.State.isTorFallbackTestOverrideActive()}")
            appendLine("torAvailable=${runtime != null}")
            appendLine("torDesired=${runtime?.desired ?: false}")
            appendLine("torRunning=${runtime?.running ?: false}")
            appendLine("torReady=${runtime?.ready ?: false}")
            appendLine("torBootstrap=${runtime?.bootstrapProgress ?: 0}")
            appendLine("torPhase=${runtime?.phase ?: "unknown"}")
            appendLine("torLastError=${runtime?.lastError ?: "none"}")
            appendLine("torRoutes=${stats?.torSnowflakeSuccesses ?: 0}/${stats?.torSnowflakeAttempts ?: 0}/${stats?.torSnowflakeFailures ?: 0}")
            appendLine("torUnavailable=${stats?.torSnowflakeUnavailable ?: 0}")
            appendLine("lastRoute=${stats?.lastRouteUsed ?: "none"}")
            appendLine("cf=${stats?.cfProxyConnections ?: 0}/${stats?.cfProxyErrors ?: 0}")
        }.trimEnd()
    }

    private fun matchWrap(topMargin: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = topMargin }
}
''', encoding="utf-8")
