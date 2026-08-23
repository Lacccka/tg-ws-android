from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, got {count}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) Unknown direct DCs may still use Tor via tunnel-side DNS. Keep CF_ONLY strict.
proxy_server = "app/src/main/java/com/flowseal/tgwsandroid/proxy/ProxyServer.kt"
replace_once(
    proxy_server,
    '''        if (targetHost == null) {
            if (config.cfproxyEnabled) {
                logger.log("DC${parsed.dcId} has no direct redirect configured; trying CF fallback")
            }
            if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)) {
                return true
            }
            recordUnsupportedDc(parsed.dcId)
            recordNoRoute(parsed.dcId)
            markBad("Unsupported DC ${parsed.dcId} from ${client.remoteLabel}; no direct redirect or CF proxy route available")
            return true
        }
''',
    '''        if (targetHost == null) {
            if (config.cfproxyEnabled) {
                logger.log("DC${parsed.dcId} has no direct redirect configured; trying CF fallback")
            }
            if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)) {
                return true
            }
            val torAllowedForUnknownDirectDc =
                effectiveRouteMode() != NetworkRouteMode.CF_ONLY &&
                    config.torSnowflakeFallbackEnabled &&
                    isMobile(currentNetworkStatus)
            if (torAllowedForUnknownDirectDc) {
                logger.log("DC${parsed.dcId} has no direct redirect; trying Tor/Snowflake with tunnel-side DNS")
                if (tryTorSnowflakeFallback(client, parsed, null, relayInit, cryptoContext, splitter)) return true
                recordNoRoute(parsed.dcId)
                logger.log("DC${parsed.dcId} no route available after CF/Tor attempts; no direct redirect configured")
                return true
            }
            recordUnsupportedDc(parsed.dcId)
            recordNoRoute(parsed.dcId)
            markBad("Unsupported DC ${parsed.dcId} from ${client.remoteLabel}; no direct redirect or allowed CF/Tor route available")
            return true
        }
''',
)
replace_once(
    proxy_server,
    '''    private fun tryTorSnowflakeFallback(
        client: TcpClientTransport,
        parsed: MtprotoHandshake.Result,
        targetHost: String,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
    ): Boolean {
        if (!config.torSnowflakeFallbackEnabled || !isMobile(currentNetworkStatus)) return false
        val connector = torSnowflakeConnector ?: return false
        for (domain in wsDomains(parsed.dcId, parsed.isMedia)) {
            lastTorSnowflakeTimeMs.set(System.currentTimeMillis())
            logger.log("DC${parsed.dcId} media=${parsed.isMedia} -> trying Tor/Snowflake wss://$domain$DEFAULT_WS_PATH via $targetHost")
            val webSocket = try {
                torSnowflakeAttempts.incrementAndGet()
                connector.connect(targetHost, domain, DEFAULT_WS_PATH, config.torSnowflakeConnectTimeoutMs)
''',
    '''    private fun tryTorSnowflakeFallback(
        client: TcpClientTransport,
        parsed: MtprotoHandshake.Result,
        targetHost: String?,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
    ): Boolean {
        if (!config.torSnowflakeFallbackEnabled || !isMobile(currentNetworkStatus)) return false
        val connector = torSnowflakeConnector ?: return false
        for (domain in wsDomains(parsed.dcId, parsed.isMedia)) {
            val outboundTarget = targetHost ?: domain
            lastTorSnowflakeTimeMs.set(System.currentTimeMillis())
            logger.log("DC${parsed.dcId} media=${parsed.isMedia} -> trying Tor/Snowflake wss://$domain$DEFAULT_WS_PATH via $outboundTarget")
            val webSocket = try {
                torSnowflakeAttempts.incrementAndGet()
                connector.connect(outboundTarget, domain, DEFAULT_WS_PATH, config.torSnowflakeConnectTimeoutMs)
''',
)

# 2) Expose Tor runtime state in watchdog and exported diagnostics.
service = "app/src/main/java/com/flowseal/tgwsandroid/service/ProxyForegroundService.kt"
replace_once(
    service,
    '''            val runtime = TorFallbackRuntimeLoader.create(applicationContext, logger)
            torFallbackRuntime = runtime
            runtime?.onNetworkChanged(State.networkStatus)
            val serverConfig = ProxyRuntimeConfig.proxyServerConfig(applicationContext, State.networkStatus).copy(
''',
    '''            val runtime = TorFallbackRuntimeLoader.create(applicationContext, logger)
            torFallbackRuntime = runtime
            runtime?.onNetworkChanged(State.networkStatus)
            State.updateTorFallbackRuntimeSnapshot(runCatching { runtime?.snapshot() }.getOrNull())
            val serverConfig = ProxyRuntimeConfig.proxyServerConfig(applicationContext, State.networkStatus).copy(
''',
)
replace_once(
    service,
    '''                torFallbackRuntime = null
                runCatching { runtime?.stop() }
                    .onFailure { State.addLog("Tor/Snowflake cleanup after start failure failed: ${it.javaClass.simpleName}: ${it.message.orEmpty()}", LogSeverity.WARN, "service") }
                State.setLiveStatsProvider(null)
''',
    '''                torFallbackRuntime = null
                runCatching { runtime?.stop() }
                    .onFailure { State.addLog("Tor/Snowflake cleanup after start failure failed: ${it.javaClass.simpleName}: ${it.message.orEmpty()}", LogSeverity.WARN, "service") }
                State.updateTorFallbackRuntimeSnapshot(null)
                State.setLiveStatsProvider(null)
''',
)
replace_once(
    service,
    '''        runCatching { torRuntime?.stop() }
            .onFailure { State.addLog("Tor/Snowflake stop failed: ${it.javaClass.simpleName}: ${it.message.orEmpty()}", LogSeverity.WARN, "service") }
        telemetryAggregator.flushOnStop()
''',
    '''        runCatching { torRuntime?.stop() }
            .onFailure { State.addLog("Tor/Snowflake stop failed: ${it.javaClass.simpleName}: ${it.message.orEmpty()}", LogSeverity.WARN, "service") }
        State.updateTorFallbackRuntimeSnapshot(null)
        telemetryAggregator.flushOnStop()
''',
)
replace_once(
    service,
    '''    private fun applyRouteForNetwork(networkStatus: String, immediate: Boolean = false) {
        torFallbackRuntime?.onNetworkChanged(networkStatus)
        val server = synchronized(lock) { proxyServer }
''',
    '''    private fun applyRouteForNetwork(networkStatus: String, immediate: Boolean = false) {
        torFallbackRuntime?.onNetworkChanged(networkStatus)
        State.updateTorFallbackRuntimeSnapshot(runCatching { torFallbackRuntime?.snapshot() }.getOrNull())
        val server = synchronized(lock) { proxyServer }
''',
)
replace_once(
    service,
    '''            val stats = try {
                server?.stats()
            } catch (_: Throwable) {
                null
            }
            State.updateStats(stats)
            State.setBatteryOptimizationStatus(detectBatteryOptimizationStatus())
''',
    '''            val stats = try {
                server?.stats()
            } catch (_: Throwable) {
                null
            }
            val torRuntimeSnapshot = runCatching { torFallbackRuntime?.snapshot() }.getOrNull()
            State.updateStats(stats)
            State.updateTorFallbackRuntimeSnapshot(torRuntimeSnapshot)
            State.setBatteryOptimizationStatus(detectBatteryOptimizationStatus())
''',
)
replace_once(
    service,
    '''            val line = "watchdog: running=${server?.isRunning == true} ${compactStats(stats)} " +
                "network=${State.networkStatus} route=${stats?.effectiveRouteMode ?: "unknown"} battery=${State.batteryOptimizationStatus}"
''',
    '''            val line = "watchdog: running=${server?.isRunning == true} ${compactStats(stats, torRuntimeSnapshot)} " +
                "network=${State.networkStatus} route=${stats?.effectiveRouteMode ?: "unknown"} battery=${State.batteryOptimizationStatus}"
''',
)
replace_once(
    service,
    '''    private fun compactStats(stats: ProxyServerStats?): String = if (stats == null) {
''',
    '''    private fun compactStats(stats: ProxyServerStats?, torRuntime: TorFallbackRuntimeSnapshot?): String = if (stats == null) {
''',
)
replace_once(
    service,
    '''            "torSnowflake=${stats.torSnowflakeSuccesses}/${stats.torSnowflakeAttempts}/${stats.torSnowflakeFailures} " +
            "torUnavailable=${stats.torSnowflakeUnavailable} " +
            "directHealth=${stats.directHealthState} route=${stats.effectiveRouteMode} lastRoute=${stats.lastRouteUsed ?: "none"}"
    }

    private fun compactMap(values: Map<*, *>): String =
''',
    '''            "torSnowflake=${stats.torSnowflakeSuccesses}/${stats.torSnowflakeAttempts}/${stats.torSnowflakeFailures} " +
            "torUnavailable=${stats.torSnowflakeUnavailable} " +
            "torRuntime=${compactTorRuntime(torRuntime)} " +
            "directHealth=${stats.directHealthState} route=${stats.effectiveRouteMode} lastRoute=${stats.lastRouteUsed ?: "none"}"
    }

    private fun compactTorRuntime(snapshot: TorFallbackRuntimeSnapshot?): String = if (snapshot == null) {
        "unavailable"
    } else {
        "desired=${snapshot.desired},running=${snapshot.running},ready=${snapshot.ready}," +
            "bootstrap=${snapshot.bootstrapProgress},phase=${snapshot.phase},lastError=${snapshot.lastError ?: "none"}"
    }

    private fun compactMap(values: Map<*, *>): String =
''',
)
replace_once(
    service,
    '''        @Volatile
        private var statsSnapshot: ProxyServerStats? = null
        @Volatile
        private var liveStatsProvider: (() -> ProxyServerStats?)? = null
''',
    '''        @Volatile
        private var statsSnapshot: ProxyServerStats? = null
        @Volatile
        private var torFallbackRuntimeSnapshot: TorFallbackRuntimeSnapshot? = null
        @Volatile
        private var liveStatsProvider: (() -> ProxyServerStats?)? = null
''',
)
replace_once(
    service,
    '''        fun updateStats(stats: ProxyServerStats?) {
            statsSnapshot = stats
        }

        fun setLiveStatsProvider(provider: (() -> ProxyServerStats?)?) {
''',
    '''        fun updateStats(stats: ProxyServerStats?) {
            statsSnapshot = stats
        }

        fun updateTorFallbackRuntimeSnapshot(snapshot: TorFallbackRuntimeSnapshot?) {
            torFallbackRuntimeSnapshot = snapshot
        }

        fun setLiveStatsProvider(provider: (() -> ProxyServerStats?)?) {
''',
)
replace_once(
    service,
    '''                    stats = stats,
                    statsSnapshotTimeMs = stats?.statsSnapshotTimeMs?.takeIf { it > 0L },
''',
    '''                    stats = stats,
                    torFallbackRuntime = torFallbackRuntimeSnapshot,
                    statsSnapshotTimeMs = stats?.statsSnapshotTimeMs?.takeIf { it > 0L },
''',
)

# 3) Diagnostic report gets a stable Tor runtime section independent of rolling log retention.
diag = "app/src/main/java/com/flowseal/tgwsandroid/service/DiagnosticReport.kt"
replace_once(
    diag,
    '''    val stats: ProxyServerStats?,
    val logs: List<RuntimeLogEntry>,
)
''',
    '''    val stats: ProxyServerStats?,
    val logs: List<RuntimeLogEntry>,
    val torFallbackRuntime: TorFallbackRuntimeSnapshot? = null,
)
''',
)
replace_once(
    diag,
    '''        stats: ProxyServerStats? = null,
        statsSnapshotTimeMs: Long? = stats?.statsSnapshotTimeMs?.takeIf { it > 0L },
''',
    '''        stats: ProxyServerStats? = null,
        torFallbackRuntime: TorFallbackRuntimeSnapshot? = null,
        statsSnapshotTimeMs: Long? = stats?.statsSnapshotTimeMs?.takeIf { it > 0L },
''',
)
replace_once(
    diag,
    '''        stats = stats,
        logs = logs,
    )
''',
    '''        stats = stats,
        logs = logs,
        torFallbackRuntime = torFallbackRuntime,
    )
''',
)
replace_once(
    diag,
    '''        appendLine("Last route used update time ms: ${snapshot.lastRouteUsedUpdateTimeMs?.toString() ?: "unknown"}")
        appendClientExperienceDiagnostics(snapshot.stats)
''',
    '''        appendLine("Last route used update time ms: ${snapshot.lastRouteUsedUpdateTimeMs?.toString() ?: "unknown"}")
        appendTorFallbackRuntime(snapshot.torFallbackRuntime)
        appendClientExperienceDiagnostics(snapshot.stats)
''',
)
replace_once(
    diag,
    '''    private fun formatPreviousRun(previous: PreviousRunCheck?): String = if (previous == null) {
''',
    '''    private fun StringBuilder.appendTorFallbackRuntime(runtime: TorFallbackRuntimeSnapshot?) {
        appendLine("Tor/Snowflake runtime:")
        appendLine("  available: ${runtime != null}")
        appendLine("  desired: ${runtime?.desired?.toString() ?: "unknown"}")
        appendLine("  running: ${runtime?.running?.toString() ?: "unknown"}")
        appendLine("  ready: ${runtime?.ready?.toString() ?: "unknown"}")
        appendLine("  bootstrapProgress: ${runtime?.bootstrapProgress?.toString() ?: "unknown"}")
        appendLine("  phase: ${runtime?.phase ?: "unknown"}")
        appendLine("  lastError: ${runtime?.lastError ?: "none"}")
    }

    private fun formatPreviousRun(previous: PreviousRunCheck?): String = if (previous == null) {
''',
)

# 4) A ready Tor transport remains running even though the bootstrap Future completed.
runtime = "app/src/privateSideload/java/com/flowseal/tgwsandroid/SnowflakeTorFallbackRuntime.kt"
replace_once(
    runtime,
    '''        running = task?.isDone == false,
''',
    '''        running = wanted.get() && (readyConnector.get() != null || task?.isDone == false || controller != null || torConnection != null),
''',
)

# 5) Regression tests for real DC5 fallback and strict CF_ONLY semantics.
tests = "app/src/test/java/com/flowseal/tgwsandroid/proxy/ProxyServerTest.kt"
insert = '''    @Test
    fun unknownDirectDcFallsBackToTorUsingTunnelDnsAfterCfFailure() {
        val server = FakeTcpServerTransport()
        val cfAttempts = CopyOnWriteArrayList<String>()
        val cfConnector = RawWebSocketConnector { _, domain, _, _ ->
            cfAttempts.add(domain)
            throw IOException("planned CF failure for $domain")
        }
        val tor = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = cfConnector,
            torSnowflakeConnector = tor,
            config = baseConfig().copy(
                dcRedirects = emptyMap(),
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfproxyEnabled = true,
                cfPoolEnabled = false,
                cfProxyDomains = listOf("cf.example"),
                torSnowflakeFallbackEnabled = true,
            ),
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(buildClientHandshake(dcIdx = -5, protoTag = RelayInit.PROTO_TAG_SECURE)))
        waitUntil("DC5 media Tor/Snowflake route") { proxy.stats().lastRouteUsed == TOR_SNOWFLAKE_ROUTE_TYPE }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(listOf("kws5.cf.example"), cfAttempts.toList())
        assertEquals(listOf("kws5-1.web.telegram.org"), tor.targetHosts.toList())
        assertEquals(listOf("kws5-1.web.telegram.org"), tor.domains.toList())
        assertEquals(TOR_SNOWFLAKE_ROUTE_TYPE, stats.lastRouteUsed)
        assertEquals(1L, stats.torSnowflakeAttempts)
        assertEquals(1L, stats.torSnowflakeSuccesses)
        assertEquals(0L, stats.torSnowflakeFailures)
        assertEquals(0L, stats.unsupportedDc)
        assertEquals(0L, stats.connectionsBad)
    }

    @Test
    fun cfOnlyUnknownDirectDcDoesNotUseTorFallback() {
        val server = FakeTcpServerTransport()
        val tor = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            torSnowflakeConnector = tor,
            config = baseConfig().copy(
                dcRedirects = emptyMap(),
                routeMode = NetworkRouteMode.CF_ONLY,
                networkStatus = "mobile",
                cfproxyEnabled = false,
                torSnowflakeFallbackEnabled = true,
            ),
        )

        proxy.start()
        val client = FakeTcpClientTransport(buildClientHandshake(dcIdx = -5, protoTag = RelayInit.PROTO_TAG_SECURE))
        server.enqueue(client)
        waitUntil("CF_ONLY unknown DC client closes") { client.closed }
        proxy.stop()

        assertTrue(tor.domains.isEmpty())
        assertEquals(0L, proxy.stats().torSnowflakeAttempts)
        assertEquals(1L, proxy.stats().unsupportedDc)
        assertEquals(1L, proxy.stats().connectionsBad)
    }

'''
replace_once(
    tests,
    '''    @Test
    fun warmingTorFallbackDoesNotCountAsTorNetworkFailure() {
''',
    insert + '''    @Test
    fun warmingTorFallbackDoesNotCountAsTorNetworkFailure() {
''',
)

# Dedicated report regression test is simpler and less brittle than patching a large existing test file.
report_test = Path("app/src/test/java/com/flowseal/tgwsandroid/service/TorFallbackDiagnosticReportTest.kt")
if report_test.exists():
    raise RuntimeError(f"{report_test}: already exists")
report_test.write_text('''package com.flowseal.tgwsandroid.service

import org.junit.Assert.assertTrue
import org.junit.Test

class TorFallbackDiagnosticReportTest {
    @Test
    fun reportIncludesTorRuntimeSnapshotOutsideRollingLogs() {
        val snapshot = DiagnosticReportFormatter.snapshot(
            status = "Proxy running",
            endpoint = "127.0.0.1:1443",
            secret = "00112233445566778899aabbccddeeff",
            dcSummary = "2,4",
            torFallbackRuntime = TorFallbackRuntimeSnapshot(
                desired = true,
                running = true,
                ready = true,
                bootstrapProgress = 100,
                phase = "ready",
                lastError = null,
            ),
            logs = emptyList(),
        )

        val report = DiagnosticReportFormatter.format(snapshot)

        assertTrue(report.contains("Tor/Snowflake runtime:"))
        assertTrue(report.contains("  available: true"))
        assertTrue(report.contains("  desired: true"))
        assertTrue(report.contains("  running: true"))
        assertTrue(report.contains("  ready: true"))
        assertTrue(report.contains("  bootstrapProgress: 100"))
        assertTrue(report.contains("  phase: ready"))
        assertTrue(report.contains("  lastError: none"))
    }
}
''', encoding="utf-8")

print("Tor fallback follow-up patch applied")
