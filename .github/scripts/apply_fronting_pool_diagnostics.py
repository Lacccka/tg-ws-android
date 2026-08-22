from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match in {path}, got {count}: {old[:120]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


POOL = "app/src/main/java/com/flowseal/tgwsandroid/proxy/WebSocketPool.kt"
SERVER = "app/src/main/java/com/flowseal/tgwsandroid/proxy/ProxyServer.kt"
REPORT = "app/src/main/java/com/flowseal/tgwsandroid/service/DiagnosticReport.kt"
WORKFLOW = ".github/workflows/upstream-parity-check.yml"

replace_once(
    POOL,
    "import java.util.concurrent.TimeUnit\n\n/**",
    """import java.util.concurrent.TimeUnit

/** Strategy hook used by direct pool refill when routing needs DC/media context. */
fun interface DirectPoolRefillConnector {
    fun connect(
        dc: Int,
        isMedia: Boolean,
        targetHost: String,
        domain: String,
        path: String,
        timeoutMs: Int,
    ): WebSocketBinaryStream
}

/**""",
)

replace_once(
    POOL,
    """    private val poolSize: Int,
    private val connector: RawWebSocketConnector,
    private val logger: ProxyLogger = ProxyLogger {},""",
    """    private val poolSize: Int,
    private val connector: RawWebSocketConnector,
    private val refillConnector: DirectPoolRefillConnector? = null,
    private val logger: ProxyLogger = ProxyLogger {},""",
)

replace_once(
    POOL,
    "connected = connector.connect(targetHost, domain, path, RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS)",
    """connected = refillConnector?.connect(
                        key.dc,
                        key.isMedia,
                        targetHost,
                        domain,
                        path,
                        RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS,
                    ) ?: connector.connect(targetHost, domain, path, RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS)""",
)

replace_once(
    POOL,
    """    fun refillBackoffUntilSnapshot(): Map<Key, Long> = synchronized(lock) { refillAfterMs.toMap() }

    fun closedIdlePrunedSnapshot(): Map<Key, Long> = synchronized(lock) { closedIdlePrunedByKey.toMap() }""",
    """    fun refillBackoffUntilSnapshot(): Map<Key, Long> = synchronized(lock) { refillAfterMs.toMap() }

    fun refillBackoffRemainingSnapshot(): Map<Key, Long> = synchronized(lock) {
        val now = nowMs()
        refillAfterMs
            .mapValues { (_, untilMs) -> (untilMs - now).coerceAtLeast(0L) }
            .filterValues { it > 0L }
    }

    fun closedIdlePrunedSnapshot(): Map<Key, Long> = synchronized(lock) { closedIdlePrunedByKey.toMap() }""",
)

replace_once(
    SERVER,
    """    val refillSuccessesByKey: Map<String, Long> = emptyMap(),
    val refillErrorsByKey: Map<String, Long> = emptyMap(),
    val staleByKey: Map<String, Long> = emptyMap(),""",
    """    val refillSuccessesByKey: Map<String, Long> = emptyMap(),
    val refillErrorsByKey: Map<String, Long> = emptyMap(),
    val refillFailureWavesByKey: Map<String, Int> = emptyMap(),
    val refillBackoffRemainingMsByKey: Map<String, Long> = emptyMap(),
    val refillBackoffSuppressedByKey: Map<String, Long> = emptyMap(),
    val closedIdlePrunedByKey: Map<String, Long> = emptyMap(),
    val staleByKey: Map<String, Long> = emptyMap(),""",
)

replace_once(
    SERVER,
    """    var directTimeouts: Long = 0
    var lastCfDomain: String? = null""",
    """    var directTimeouts: Long = 0
    var frontingAttempts: Long = 0
    var frontingSuccesses: Long = 0
    var frontingFailures: Long = 0
    var frontingFirstAttempts: Long = 0
    var frontingFallbackAttempts: Long = 0
    var frontingPreferredKeys: List<String> = emptyList()
    var lastFrontingError: String? = null
    var lastFrontingTimeMs: Long = 0
    var lastCfDomain: String? = null""",
)

replace_once(
    SERVER,
    """    private val poolRefillErrorsByKey = ConcurrentHashMap<String, AtomicLong>()
    private val poolStaleByKey = ConcurrentHashMap<String, AtomicLong>()""",
    """    private val poolRefillErrorsByKey = ConcurrentHashMap<String, AtomicLong>()
    private val poolRefillBackoffSuppressedByKey = ConcurrentHashMap<String, AtomicLong>()
    private val poolStaleByKey = ConcurrentHashMap<String, AtomicLong>()""",
)

replace_once(
    SERVER,
    """    private val frontingAttempts = AtomicLong(0)
    private val frontingSuccesses = AtomicLong(0)
    private val frontingFailures = AtomicLong(0)
    private val directFrontingConnector = DirectFrontingConnector(""",
    """    private val frontingAttempts = AtomicLong(0)
    private val frontingSuccesses = AtomicLong(0)
    private val frontingFailures = AtomicLong(0)
    private val frontingFirstAttempts = AtomicLong(0)
    private val frontingFallbackAttempts = AtomicLong(0)
    private val lastFrontingError = AtomicReference<String?>(null)
    private val lastFrontingTimeMs = AtomicLong(0)
    private val directFrontingPreferenceState = DirectFrontingPreferenceState()
    private val directFrontingConnector = DirectFrontingConnector(""",
)

replace_once(
    SERVER,
    """        frontedConnect = { targetHost, domain, path, timeoutMs, sniHost ->
            webSocketConnector.connectWithSni(targetHost, domain, path, timeoutMs, sniHost)
        },
        onFrontingAttempt = { key, frontingFirst ->""",
    """        frontedConnect = { targetHost, domain, path, timeoutMs, sniHost ->
            webSocketConnector.connectWithSni(targetHost, domain, path, timeoutMs, sniHost)
        },
        state = directFrontingPreferenceState,
        onFrontingAttempt = { key, frontingFirst ->""",
)

replace_once(
    SERVER,
    """        onFrontingAttempt = { key, frontingFirst ->
            frontingAttempts.incrementAndGet()
            logger.log(""",
    """        onFrontingAttempt = { key, frontingFirst ->
            frontingAttempts.incrementAndGet()
            if (frontingFirst) frontingFirstAttempts.incrementAndGet() else frontingFallbackAttempts.incrementAndGet()
            lastFrontingTimeMs.set(System.currentTimeMillis())
            logger.log(""",
)

replace_once(
    SERVER,
    """        onFrontingSuccess = { key, frontingFirst ->
            frontingSuccesses.incrementAndGet()
            logger.log("DC${key.dc} media=${key.isMedia} fronting success first=$frontingFirst target=${key.targetHost}")
        },""",
    """        onFrontingSuccess = { key, frontingFirst ->
            frontingSuccesses.incrementAndGet()
            lastFrontingError.set(null)
            lastFrontingTimeMs.set(System.currentTimeMillis())
            logger.log("DC${key.dc} media=${key.isMedia} fronting success first=$frontingFirst target=${key.targetHost}")
        },""",
)

replace_once(
    SERVER,
    """        onFrontingFailure = { key, frontingFirst, error ->
            frontingFailures.incrementAndGet()
            logger.log("DC${key.dc} media=${key.isMedia} fronting failed first=$frontingFirst: ${failureDetail(error)}")
        },""",
    """        onFrontingFailure = { key, frontingFirst, error ->
            frontingFailures.incrementAndGet()
            lastFrontingError.set(failureDetail(error))
            lastFrontingTimeMs.set(System.currentTimeMillis())
            logger.log("DC${key.dc} media=${key.isMedia} fronting failed first=$frontingFirst: ${failureDetail(error)}")
        },""",
)

replace_once(
    SERVER,
    """    private val webSocketPool = WebSocketPool(
        poolSize = config.poolSize,
        connector = webSocketConnector,
        logger = logger,""",
    """    private val webSocketPool = WebSocketPool(
        poolSize = config.poolSize,
        connector = webSocketConnector,
        refillConnector = DirectPoolRefillConnector { dc, isMedia, targetHost, domain, path, timeoutMs ->
            directFrontingConnector.connect(
                dc = dc,
                isMedia = isMedia,
                targetHost = targetHost,
                domain = domain,
                path = path,
                normalTimeoutMs = timeoutMs,
                networkGeneration = routeGeneration.get(),
            ).stream
        },
        logger = logger,""",
)

replace_once(
    SERVER,
    """        onSkippedTarget = { key, targetHost, source ->
            directPoolSkippedBecauseTargetIpCooldown.incrementAndGet()
            directTargetIpCooldownHits.incrementAndGet()
            logger.log("DC${key.dc} direct target $targetHost in cooldown; direct pool $source skipped")
        },
    )""",
    """        onSkippedTarget = { key, targetHost, source ->
            directPoolSkippedBecauseTargetIpCooldown.incrementAndGet()
            directTargetIpCooldownHits.incrementAndGet()
            logger.log("DC${key.dc} direct target $targetHost in cooldown; direct pool $source skipped")
        },
        onRefillBackoffSuppressed = { key, source, remainingMs ->
            incrementPoolCounter(poolRefillBackoffSuppressedByKey, key, source)
            logger.log("DC${key.dc} direct WS pool $source suppressed by refill backoff for ${remainingMs}ms")
        },
    )""",
)

replace_once(
    SERVER,
    """                refillSuccessesByKey = snapshotPoolLongMap(poolRefillSuccessesByKey),
                refillErrorsByKey = snapshotPoolLongMap(poolRefillErrorsByKey),
                staleByKey = snapshotPoolLongMap(poolStaleByKey),""",
    """                refillSuccessesByKey = snapshotPoolLongMap(poolRefillSuccessesByKey),
                refillErrorsByKey = snapshotPoolLongMap(poolRefillErrorsByKey),
                refillFailureWavesByKey = webSocketPool.refillFailuresSnapshot().mapKeys { poolDiagnosticKey(it.key) }.toSortedMap(),
                refillBackoffRemainingMsByKey = webSocketPool.refillBackoffRemainingSnapshot().mapKeys { poolDiagnosticKey(it.key) }.toSortedMap(),
                refillBackoffSuppressedByKey = snapshotPoolLongMap(poolRefillBackoffSuppressedByKey),
                closedIdlePrunedByKey = webSocketPool.closedIdlePrunedSnapshot().mapKeys { poolDiagnosticKey(it.key) }.toSortedMap(),
                staleByKey = snapshotPoolLongMap(poolStaleByKey),""",
)

replace_once(
    SERVER,
    """        snapshot.directTimeouts = directTimeouts.get()
        snapshot.lastCfDomain = lastCfDomain""",
    """        snapshot.directTimeouts = directTimeouts.get()
        snapshot.frontingAttempts = frontingAttempts.get()
        snapshot.frontingSuccesses = frontingSuccesses.get()
        snapshot.frontingFailures = frontingFailures.get()
        snapshot.frontingFirstAttempts = frontingFirstAttempts.get()
        snapshot.frontingFallbackAttempts = frontingFallbackAttempts.get()
        snapshot.frontingPreferredKeys = directFrontingPreferenceState
            .preferredSnapshot(routeGeneration.get())
            .map { key -> "dc${key.dc}${if (key.isMedia) "m" else ""}@${key.targetHost}" }
            .sorted()
        snapshot.lastFrontingError = lastFrontingError.get()
        snapshot.lastFrontingTimeMs = lastFrontingTimeMs.get()
        snapshot.lastCfDomain = lastCfDomain""",
)

replace_once(
    REPORT,
    """        appendClientExperienceDiagnostics(snapshot.stats)
        appendDirectPoolReadiness(snapshot.stats)
        appendLine("Stats: ${formatStats(snapshot.stats)}")""",
    """        appendClientExperienceDiagnostics(snapshot.stats)
        appendFrontingDiagnostics(snapshot.stats)
        appendDirectPoolReadiness(snapshot.stats)
        appendLine("Stats: ${formatStats(snapshot.stats)}")""",
)

replace_once(
    REPORT,
    """    private fun StringBuilder.appendDirectPoolReadiness(stats: ProxyServerStats?) {
        appendLine("Direct pool readiness:")""",
    """    private fun StringBuilder.appendFrontingDiagnostics(stats: ProxyServerStats?) {
        appendLine("Fronting:")
        appendLine("  attempts: ${stats?.frontingAttempts ?: "unknown"}")
        appendLine("  successes: ${stats?.frontingSuccesses ?: "unknown"}")
        appendLine("  failures: ${stats?.frontingFailures ?: "unknown"}")
        appendLine("  frontingFirstAttempts: ${stats?.frontingFirstAttempts ?: "unknown"}")
        appendLine("  fallbackAttempts: ${stats?.frontingFallbackAttempts ?: "unknown"}")
        appendLine("  preferredKeys: ${stats?.frontingPreferredKeys ?: "unknown"}")
        appendLine("  lastError: ${stats?.lastFrontingError ?: "none"}")
        appendLine("  lastTimeMs: ${stats?.lastFrontingTimeMs?.takeIf { it > 0L }?.toString() ?: "unknown"}")
    }

    private fun StringBuilder.appendDirectPoolReadiness(stats: ProxyServerStats?) {
        appendLine("Direct pool readiness:")""",
)

replace_once(
    REPORT,
    """        appendLine("  refillErrorsByKey: ${stats?.directPoolDiagnostics?.refillErrorsByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  staleByKey: ${stats?.directPoolDiagnostics?.staleByKey?.let(::formatCompactMap) ?: "unknown"}")""",
    """        appendLine("  refillErrorsByKey: ${stats?.directPoolDiagnostics?.refillErrorsByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  refillFailureWavesByKey: ${stats?.directPoolDiagnostics?.refillFailureWavesByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  refillBackoffRemainingMsByKey: ${stats?.directPoolDiagnostics?.refillBackoffRemainingMsByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  refillBackoffSuppressedByKey: ${stats?.directPoolDiagnostics?.refillBackoffSuppressedByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  closedIdlePrunedByKey: ${stats?.directPoolDiagnostics?.closedIdlePrunedByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  staleByKey: ${stats?.directPoolDiagnostics?.staleByKey?.let(::formatCompactMap) ?: "unknown"}")""",
)

replace_once(
    REPORT,
    """            "directTimeouts=${stats.directTimeouts}, cfConnections=${stats.cfProxyConnections}, " +
            "cfErrors=${stats.cfProxyErrors}, lastCfDomain=${stats.lastCfDomain ?: "none"}, poolHits=${stats.poolHits}, " +""",
    """            "directTimeouts=${stats.directTimeouts}, frontingAttempts=${stats.frontingAttempts}, " +
            "frontingSuccesses=${stats.frontingSuccesses}, frontingFailures=${stats.frontingFailures}, " +
            "frontingFirstAttempts=${stats.frontingFirstAttempts}, frontingFallbackAttempts=${stats.frontingFallbackAttempts}, " +
            "frontingPreferredKeys=${stats.frontingPreferredKeys}, lastFrontingError=${stats.lastFrontingError ?: "none"}, " +
            "lastFrontingTimeMs=${stats.lastFrontingTimeMs}, cfConnections=${stats.cfProxyConnections}, " +
            "cfErrors=${stats.cfProxyErrors}, lastCfDomain=${stats.lastCfDomain ?: "none"}, poolHits=${stats.poolHits}, " +""",
)

replace_once(
    REPORT,
    """            "directPoolMissesByKey=${formatCompactMap(stats.directPoolDiagnostics.missesByKey)}, " +
            "directPoolRefillErrorsByKey=${formatCompactMap(stats.directPoolDiagnostics.refillErrorsByKey)}, " +
            "directPoolStaleByKey=${formatCompactMap(stats.directPoolDiagnostics.staleByKey)}, " +""",
    """            "directPoolMissesByKey=${formatCompactMap(stats.directPoolDiagnostics.missesByKey)}, " +
            "directPoolRefillErrorsByKey=${formatCompactMap(stats.directPoolDiagnostics.refillErrorsByKey)}, " +
            "directPoolRefillFailureWavesByKey=${formatCompactMap(stats.directPoolDiagnostics.refillFailureWavesByKey)}, " +
            "directPoolRefillBackoffRemainingMsByKey=${formatCompactMap(stats.directPoolDiagnostics.refillBackoffRemainingMsByKey)}, " +
            "directPoolRefillBackoffSuppressedByKey=${formatCompactMap(stats.directPoolDiagnostics.refillBackoffSuppressedByKey)}, " +
            "directPoolClosedIdlePrunedByKey=${formatCompactMap(stats.directPoolDiagnostics.closedIdlePrunedByKey)}, " +
            "directPoolStaleByKey=${formatCompactMap(stats.directPoolDiagnostics.staleByKey)}, " +""",
)

replace_once(
    WORKFLOW,
    """      - upstream-v1.10-fronting-runtime
  pull_request:""",
    """      - upstream-v1.10-fronting-runtime
      - upstream-v1.10-fronting-pool-diagnostics
  pull_request:""",
)

replace_once(
    WORKFLOW,
    """      - upstream-v1.10-fronting

jobs:""",
    """      - upstream-v1.10-fronting
      - upstream-v1.10-fronting-runtime

jobs:""",
)

replace_once(
    WORKFLOW,
    """            --tests 'com.flowseal.tgwsandroid.proxy.WebSocketPoolTest' \\
            --tests 'com.flowseal.tgwsandroid.proxy.BridgeSessionTest' \\""",
    """            --tests 'com.flowseal.tgwsandroid.proxy.WebSocketPoolTest' \\
            --tests 'com.flowseal.tgwsandroid.proxy.WebSocketPoolFrontingTest' \\
            --tests 'com.flowseal.tgwsandroid.proxy.BridgeSessionTest' \\""",
)

replace_once(
    WORKFLOW,
    """            --tests 'com.flowseal.tgwsandroid.proxy.ProxyServerFrontingTest' \\
            --tests 'com.flowseal.tgwsandroid.proxy.ProxyServerTest.validHandshakeConnectsToDcSendsRelayInitThenRunsBridge' \\""",
    """            --tests 'com.flowseal.tgwsandroid.proxy.ProxyServerFrontingTest' \\
            --tests 'com.flowseal.tgwsandroid.proxy.ProxyServerFrontingPoolDiagnosticsTest' \\
            --tests 'com.flowseal.tgwsandroid.service.FrontingDiagnosticReportTest' \\
            --tests 'com.flowseal.tgwsandroid.proxy.ProxyServerTest.validHandshakeConnectsToDcSendsRelayInitThenRunsBridge' \\""",
)

print("Applied fronting-aware direct pool and diagnostics edits")
