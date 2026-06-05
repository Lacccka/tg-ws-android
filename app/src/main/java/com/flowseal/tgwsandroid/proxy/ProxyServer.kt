package com.flowseal.tgwsandroid.proxy

import com.flowseal.tgwsandroid.config.AppConfig
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal Android-independent runtime configuration for the local proxy core.
 *
 * Mirrors the local runtime fields consumed by upstream
 * `proxy/config.py::ProxyConfig` and `proxy/tg_ws_proxy.py::run_proxy` without
 * Android UI or Service dependencies.
 */
data class ProxyServerConfig(
    val host: String,
    val port: Int,
    val secretHex: String,
    val dcRedirects: Map<Int, String>,
    val bufferSizeBytes: Int = BridgeSession.DEFAULT_BUFFER_SIZE,
    /** Number of idle direct WebSocket connections to keep warm per DC/media route. */
    val poolSize: Int = 0,
    /** Enables bundled Cloudflare-proxy fallback when direct WebSocket routing is unavailable. */
    val cfproxyEnabled: Boolean = false,
    /** Bundled or caller-provided CF proxy base domains. Remote refresh is intentionally not ported yet. */
    val cfProxyDomains: List<String> = CfProxyDomains.defaults,
    val routeMode: NetworkRouteMode = NetworkRouteMode.AUTO,
    val networkStatus: String = "unknown",
    val directFallbackTimeoutMs: Int = 2_000,
) {
    val effectiveRouteMode: NetworkRouteMode = RouteStrategy.resolve(routeMode, networkStatus)
    companion object {
        fun fromAppConfig(
            appConfig: AppConfig,
            networkStatus: String = "unknown",
        ): ProxyServerConfig = ProxyServerConfig(
            host = appConfig.host,
            port = appConfig.port,
            secretHex = appConfig.secret,
            dcRedirects = appConfig.dcIp.toDcRedirects(),
            bufferSizeBytes = appConfig.bufKb * 1024,
            poolSize = appConfig.poolSize,
            cfproxyEnabled = appConfig.cfproxy,
            cfProxyDomains = appConfig.cfproxyUserDomain.ifEmpty { CfProxyDomains.defaults },
            routeMode = appConfig.routeMode,
            networkStatus = networkStatus,
        )

        private fun List<String>.toDcRedirects(): Map<Int, String> = buildMap {
            for (entry in this@toDcRedirects) {
                val parts = entry.split(':', limit = 2)
                if (parts.size != 2) continue
                val dcId = parts[0].trim().toIntOrNull() ?: continue
                val host = parts[1].trim()
                if (host.isNotEmpty()) put(dcId, host)
            }
        }
    }
}

/** Immutable snapshot of lightweight local proxy counters. */
data class ProxyServerStats(
    val connectionsTotal: Long,
    val connectionsActive: Int,
    val connectionsBad: Long,
    val wsConnectErrors: Long,
    val cfProxyConnections: Long,
    val cfProxyErrors: Long,
    val bytesUp: Long,
    val bytesDown: Long,
    val poolHits: Long,
    val poolMisses: Long,
    val poolRefillErrors: Long,
    val poolStale: Long = 0,
    val sessionTimeouts: Long = 0,
    val sessionEof: Long = 0,
    val sessionClientClosed: Long = 0,
    val sessionSocketClosed: Long = 0,
    val sessionUnexpectedErrors: Long = 0,
    val routeMode: String = NetworkRouteMode.AUTO.configValue,
    val effectiveRouteMode: String = NetworkRouteMode.DIRECT_FIRST.configValue,
    val previousEffectiveRouteMode: String? = null,
    val lastRouteChangeReason: String = "initial",
    val lastRouteChangeSource: String = "initial",
    val lastRouteChangeTimeMs: Long? = null,
    val networkAtLastRouteChange: String = "unknown",
    val lastRouteUsed: String? = null,
    val directTimeouts: Long = 0,
    val lastCfDomain: String? = null,
    val directAttempts: Long = 0,
    val directAttemptsSkippedBecauseRoute: Long = 0,
    val poolRefillsCancelled: Long = 0,
    val poolResultsDiscardedAfterRouteChange: Long = 0,
    val routeChangesImmediate: Long = 0,
    val networkNoneEvents: Long = 0,
    val directHealthState: String = DirectHealthState.UNKNOWN.configValue,
    val directHealthSuccesses: Long = 0,
    val directHealthFailures: Long = 0,
    val directDowngrades: Long = 0,
    val directPromotions: Long = 0,
    val directCooldownUntil: Long = 0,
    val routeSettlingUntil: Long = 0,
    val directProbeLastError: String? = null,
    val directProbeLastSuccessTime: Long? = null,
)

fun interface ProxyLogger {
    fun log(message: String)
}

/** Blocking server socket abstraction so unit tests can run without real networking. */
interface TcpServerTransport {
    fun bind(host: String, port: Int)
    fun accept(): TcpClientTransport?
    fun close()
}

/** Blocking client socket abstraction used by the proxy runtime and bridge. */
interface TcpClientTransport : ClientByteStream {
    val remoteLabel: String
    fun readExact(byteCount: Int): ByteArray?
    override fun read(bufferSize: Int): ByteArray?
    override fun write(data: ByteArray)
    override fun close()
}

fun interface RawWebSocketConnector {
    fun connect(targetHost: String, domain: String, path: String, timeoutMs: Int): WebSocketBinaryStream
}

private data class WebSocketRoute(
    val stream: WebSocketBinaryStream,
    val type: String,
)

private data class WebSocketRouteResult(
    val failed: Boolean,
    val failedBeforeBridge: Boolean,
    val stalePooled: Boolean,
    val retryableStalePooled: Boolean,
)

fun interface ProxyBridgeRunner {
    fun run(
        client: TcpClientTransport,
        webSocket: WebSocketBinaryStream,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
        counters: BridgeSessionCounters,
    )
}

/**
 * Blocking local TCP proxy runtime that wires the already-ported MTProto, relay,
 * crypto, splitter, RawWebSocket, and BridgeSession pieces together.
 *
 * Mirrors the minimal non-fake-TLS path in upstream
 * `proxy/tg_ws_proxy.py::_handle_client`, `_read_client_init`,
 * `_try_handshake`, `_generate_relay_init`, `_build_crypto_ctx`, and direct
 * `RawWebSocket.connect` usage, then delegates bridge work equivalent to
 * `proxy/bridge.py::bridge_ws_reencrypt`.
 *
 * Future work intentionally excluded from this milestone: CF remote refresh,
 * CF worker/TCP fallback, fake TLS, proxy_protocol, Android
 * ForegroundService/UI/lifecycle, and autostart.
 */
class ProxyServer(
    private val config: ProxyServerConfig,
    private val serverTransport: TcpServerTransport = JavaTcpServerTransport(),
    private val webSocketConnector: RawWebSocketConnector = RawWebSocketConnector { targetHost, domain, path, timeoutMs ->
        RawWebSocketBinaryStream(RawWebSocket.connect(host = targetHost, domain = domain, path = path, timeoutMs = timeoutMs))
    },
    private val bridgeRunner: ProxyBridgeRunner = ProxyBridgeRunner { client, webSocket, cryptoContext, splitter, counters ->
        BridgeSession(client, webSocket, cryptoContext, splitter, counters, config.bufferSizeBytes).runBlocking()
    },
    private val cfProxyBalancer: CfProxyBalancer = CfProxyBalancer(config.cfProxyDomains),
    private val routeState: RouteState = RouteState(config.routeMode, config.networkStatus),
    private val randomBytes: RelayInit.RandomBytes = RelayInit.SecureRandomBytes,
    private val logger: ProxyLogger = ProxyLogger {},
) {
    private val running = AtomicBoolean(false)
    private val activeClients: MutableSet<TcpClientTransport> = Collections.newSetFromMap(ConcurrentHashMap<TcpClientTransport, Boolean>())
    private val connectionsTotal = AtomicLong(0)
    private val connectionsActive = AtomicInteger(0)
    private val connectionsBad = AtomicLong(0)
    private val wsConnectErrors = AtomicLong(0)
    private val cfProxyConnections = AtomicLong(0)
    private val cfProxyErrors = AtomicLong(0)
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    private val sessionTimeouts = AtomicLong(0)
    private val sessionEof = AtomicLong(0)
    private val sessionClientClosed = AtomicLong(0)
    private val sessionSocketClosed = AtomicLong(0)
    private val sessionUnexpectedErrors = AtomicLong(0)
    private val poolHits = AtomicLong(0)
    private val poolMisses = AtomicLong(0)
    private val poolRefillErrors = AtomicLong(0)
    private val poolStale = AtomicLong(0)
    private val directTimeouts = AtomicLong(0)
    private val directAttempts = AtomicLong(0)
    private val directAttemptsSkippedBecauseRoute = AtomicLong(0)
    private val poolRefillsCancelled = AtomicLong(0)
    private val poolResultsDiscardedAfterRouteChange = AtomicLong(0)
    private val routeChangesImmediate = AtomicLong(0)
    private val networkNoneEvents = AtomicLong(0)
    private val routeGeneration = AtomicLong(0)
    @Volatile private var currentNetworkStatus: String = config.networkStatus.ifBlank { "unknown" }
    @Volatile private var directPoolStaleInWindow: Long = 0
    @Volatile private var lastDirectPoolStaleWindowStartMs: Long = 0
    @Volatile private var lastRouteUsed: String? = null
    @Volatile private var lastCfDomain: String? = null
    private val webSocketPool = WebSocketPool(
        poolSize = config.poolSize,
        connector = webSocketConnector,
        logger = logger,
        onRefillError = { poolRefillErrors.incrementAndGet() },
        onRefillAttempt = { directAttempts.incrementAndGet() },
        onRefillCancelled = { count -> poolRefillsCancelled.addAndGet(count.toLong()) },
        onResultDiscardedAfterRouteChange = { poolResultsDiscardedAfterRouteChange.incrementAndGet() },
    )
    private val directRouteHealth = DirectRouteHealth(
        connector = webSocketConnector,
        dcRedirects = config.dcRedirects,
        wsDomainsProvider = ::wsDomains,
        logger = logger,
    )
    private var acceptThread: Thread? = null

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverTransport.bind(config.host, config.port)
        if (isDirectPoolEnabled() && !directRouteHealth.isSettling()) {
            startDirectPoolWarmup("startup")
        } else if (config.poolSize > 0) {
            webSocketPool.disableAndClear()
            logger.log("Direct WS pool warmup skipped because effective route mode ${effectiveRouteMode().configValue}")
        }
        maybeStartAutoWifiDirectProbe(currentNetworkStatus)
        acceptThread = Thread(::acceptLoop, "ProxyServer-accept-${config.host}:${config.port}").also {
            it.isDaemon = true
            it.start()
        }
        logger.log("ProxyServer listening on ${config.host}:${config.port}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverTransport.close()
        } catch (_: Throwable) {
            // Best-effort stop.
        }
        for (client in activeClients.toList()) {
            closeClient(client)
        }
        webSocketPool.closeAll()
        joinAcceptThreadBestEffort()
        logger.log("ProxyServer stopped")
    }

    fun stats(): ProxyServerStats {
        val routeSnapshot = routeState.snapshot()
        val directHealthSnapshot = directRouteHealth.snapshot()
        return ProxyServerStats(
            connectionsTotal = connectionsTotal.get(),
            connectionsActive = connectionsActive.get(),
            connectionsBad = connectionsBad.get(),
            wsConnectErrors = wsConnectErrors.get(),
            cfProxyConnections = cfProxyConnections.get(),
            cfProxyErrors = cfProxyErrors.get(),
            bytesUp = bytesUp.get(),
            bytesDown = bytesDown.get(),
            sessionTimeouts = sessionTimeouts.get(),
            sessionEof = sessionEof.get(),
            sessionClientClosed = sessionClientClosed.get(),
            sessionSocketClosed = sessionSocketClosed.get(),
            sessionUnexpectedErrors = sessionUnexpectedErrors.get(),
            poolHits = poolHits.get(),
            poolMisses = poolMisses.get(),
            poolRefillErrors = poolRefillErrors.get(),
            poolStale = poolStale.get(),
            routeMode = routeSnapshot.configuredRouteMode.configValue,
            effectiveRouteMode = routeSnapshot.effectiveRouteMode.configValue,
            previousEffectiveRouteMode = routeSnapshot.previousEffectiveRouteMode?.configValue,
            lastRouteChangeReason = routeSnapshot.lastRouteChangeReason,
            lastRouteChangeSource = routeSnapshot.lastRouteChangeSource,
            lastRouteChangeTimeMs = routeSnapshot.lastRouteChangeTimeMs,
            networkAtLastRouteChange = routeSnapshot.networkAtLastRouteChange,
            lastRouteUsed = lastRouteUsed,
            directTimeouts = directTimeouts.get(),
            lastCfDomain = lastCfDomain,
            directAttempts = directAttempts.get(),
            directAttemptsSkippedBecauseRoute = directAttemptsSkippedBecauseRoute.get(),
            poolRefillsCancelled = poolRefillsCancelled.get(),
            poolResultsDiscardedAfterRouteChange = poolResultsDiscardedAfterRouteChange.get(),
            routeChangesImmediate = routeChangesImmediate.get(),
            networkNoneEvents = networkNoneEvents.get(),
            directHealthState = directHealthSnapshot.state.configValue,
            directHealthSuccesses = directHealthSnapshot.successes,
            directHealthFailures = directHealthSnapshot.failures,
            directDowngrades = directHealthSnapshot.downgrades,
            directPromotions = directHealthSnapshot.promotions,
            directCooldownUntil = directHealthSnapshot.cooldownUntilMs,
            routeSettlingUntil = directHealthSnapshot.settlingUntilMs,
            directProbeLastError = directHealthSnapshot.lastError,
            directProbeLastSuccessTime = directHealthSnapshot.lastSuccessTimeMs,
        )
    }

    fun applyNetworkRoute(networkStatus: String): RouteChangeResult =
        applyNetworkRoute(networkStatus, immediate = false)

    fun applyNetworkRouteImmediately(networkStatus: String): RouteChangeResult =
        applyNetworkRoute(networkStatus, immediate = true)

    private fun applyNetworkRoute(networkStatus: String, immediate: Boolean): RouteChangeResult {
        val normalized = networkStatus.ifBlank { "unknown" }
        currentNetworkStatus = normalized
        routeGeneration.incrementAndGet()
        directRouteHealth.markSettling(ROUTE_SETTLING_WINDOW_MS)
        if (normalized.equals("none", ignoreCase = true)) {
            networkNoneEvents.incrementAndGet()
            directRouteHealth.resetForSafeRoute()
            webSocketPool.disableAndClear()
            if (immediate) logger.log("network lost: applying safe route immediately")
        }
        if (immediate) routeChangesImmediate.incrementAndGet()
        val result = applyEffectiveRouteMode(
            routeState.desiredEffectiveRouteMode(normalized),
            "network=$normalized",
            normalized,
            source = if (immediate) "immediate" else "debounce",
        )
        maybeStartAutoWifiDirectProbe(normalized)
        return result
    }

    fun applyEffectiveRouteMode(
        desiredEffectiveRouteMode: NetworkRouteMode,
        reason: String,
        networkStatus: String,
        source: String = "manual",
    ): RouteChangeResult {
        currentNetworkStatus = networkStatus.ifBlank { "unknown" }
        val result = routeState.applyEffectiveRouteMode(desiredEffectiveRouteMode, reason, networkStatus, source)
        if (result.changed) {
            logger.log(
                "effective route changed: ${result.previous.configValue} -> ${result.current.configValue} because $reason",
            )
            routeGeneration.incrementAndGet()
            handlePoolForRouteChange(result.previous, result.current)
        } else {
            logger.log("effective route unchanged: ${result.current.configValue} because $reason")
        }
        return result
    }

    fun routeSnapshot(): RouteSnapshot = routeState.snapshot()

    private fun acceptLoop() {
        while (running.get()) {
            val client = try {
                serverTransport.accept()
            } catch (error: Throwable) {
                if (running.get()) logger.log("Accept failed: ${error.message ?: error::class.java.simpleName}")
                null
            } ?: continue

            connectionsTotal.incrementAndGet()
            activeClients.add(client)
            connectionsActive.incrementAndGet()
            Thread({ handleClientAndClose(client) }, "ProxyServer-client-${client.remoteLabel}").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun handleClientAndClose(client: TcpClientTransport) {
        try {
            handleClient(client)
        } catch (error: Throwable) {
            sessionUnexpectedErrors.incrementAndGet()
            logger.log("${client.remoteLabel} client handler failed: ${failureDetail(error)}")
        } finally {
            activeClients.remove(client)
            connectionsActive.decrementAndGet()
            closeClient(client)
        }
    }

    private fun handleClient(client: TcpClientTransport) {
        val handshake = try {
            client.readExact(MtprotoHandshake.HANDSHAKE_LEN)
        } catch (error: Throwable) {
            markBad("Failed to read MTProto handshake from ${client.remoteLabel}: ${error.message ?: error::class.java.simpleName}")
            return
        }
        if (handshake == null) {
            markBad("Client ${client.remoteLabel} closed before MTProto handshake")
            return
        }

        val parsed = try {
            MtprotoHandshake.parse(handshake, config.secretHex)
        } catch (error: IllegalArgumentException) {
            markBad("Invalid proxy configuration or handshake for ${client.remoteLabel}: ${error.message}")
            return
        }
        if (parsed == null) {
            markBad("Invalid MTProto handshake from ${client.remoteLabel}")
            return
        }

        val targetHost = config.dcRedirects[parsed.dcId]

        val protoInt = protoIntForProtoTag(parsed.protoTag)
        logger.log(
            "${client.remoteLabel} handshake accepted: DC${parsed.dcId} " +
                "media=${parsed.isMedia} proto=$protoInt/${protoTagLabel(parsed.protoTag)}",
        )

        val relayDcIdx = if (parsed.isMedia) -parsed.dcId else parsed.dcId
        val relayInit = RelayInit.generate(parsed.protoTag, relayDcIdx, randomBytes)
        val cryptoContext = CryptoContext.build(
            clientDecPrekeyIv = parsed.clientDecPrekeyIv,
            secret = config.secretHex.hexToBytes(),
            relayInit = relayInit,
        )
        val splitter = MsgSplitter(relayInit, protoInt)

        if (targetHost == null) {
            if (config.cfproxyEnabled) {
                logger.log("DC${parsed.dcId} has no direct redirect configured; trying CF fallback")
            }
            if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)) {
                return
            }
            markBad("Unsupported DC ${parsed.dcId} from ${client.remoteLabel}; no direct redirect or CF proxy route available")
            return
        }

        when (effectiveRouteMode()) {
            NetworkRouteMode.CF_ONLY -> {
                if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)) return
                logger.log("DC${parsed.dcId} no route available after CF-only attempts")
            }
            NetworkRouteMode.CF_FIRST -> {
                if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)) return
                if (isDirectAttemptAllowedForCurrentRoute()) {
                    logger.log("DC${parsed.dcId} trying short direct fallback after CF-first failure")
                    if (tryDirectRoute(client, parsed, targetHost, relayInit, cryptoContext, splitter, usePool = false, timeoutMs = config.directFallbackTimeoutMs)) return
                } else {
                    directAttemptsSkippedBecauseRoute.incrementAndGet()
                    logger.log("DC${parsed.dcId} direct fallback skipped because effective route mode ${effectiveRouteMode().configValue}")
                }
                logger.log("DC${parsed.dcId} no route available after CF-first attempts")
            }
            NetworkRouteMode.DIRECT_FIRST, NetworkRouteMode.AUTO -> {
                if (tryDirectRoute(client, parsed, targetHost, relayInit, cryptoContext, splitter, usePool = true, timeoutMs = RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS)) return
                if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)) return
                logger.log("DC${parsed.dcId} no route available after direct WebSocket attempts")
                downgradeDirectRouteBecauseHealthDegraded("no route available after direct attempts")
            }
        }
    }


    private fun tryDirectRoute(
        client: TcpClientTransport,
        parsed: MtprotoHandshake.Result,
        targetHost: String,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
        usePool: Boolean,
        timeoutMs: Int,
    ): Boolean {
        val attemptGeneration = routeGeneration.get()
        val directRoute = getPooledOrConnectWebSocket(parsed, targetHost, usePool, timeoutMs, attemptGeneration)
        if (directRoute != null) {
            val directResult = runWebSocketRoute(client, parsed, directRoute, relayInit, cryptoContext, splitter)
            if (!directResult.failed) return true
            if (directResult.retryableStalePooled) {
                if (!directRouteContextAllowsAttempt(attemptGeneration)) {
                    directAttemptsSkippedBecauseRoute.incrementAndGet()
                    logger.log("DC${parsed.dcId} cold direct retry skipped after stale pool because route/network changed")
                    return false
                }
                logger.log("DC${parsed.dcId} retrying with cold direct route after stale pool")
                val coldRoute = guardedConnectWebSocket(parsed, targetHost, timeoutMs, attemptGeneration)?.let { WebSocketRoute(it, "direct-cold") }
                if (coldRoute != null) {
                    val coldResult = runWebSocketRoute(client, parsed, coldRoute, relayInit, cryptoContext, splitter)
                    if (!coldResult.failed) return true
                    if (!coldResult.failedBeforeBridge) return true
                }
                if (config.cfproxyEnabled) {
                    logger.log("DC${parsed.dcId} trying CF fallback after stale pool/cold direct failure")
                }
            } else if (!directResult.failedBeforeBridge || directResult.stalePooled) {
                return true
            }
        }
        return false
    }

    private fun getPooledOrConnectWebSocket(
        parsed: MtprotoHandshake.Result,
        targetHost: String,
        usePool: Boolean,
        timeoutMs: Int,
        expectedGeneration: Long? = null,
    ): WebSocketRoute? {
        if (expectedGeneration != null && routeGeneration.get() != expectedGeneration) {
            directAttemptsSkippedBecauseRoute.incrementAndGet()
            logger.log("DC${parsed.dcId} direct route skipped because route generation changed")
            return null
        }
        if (!isDirectAttemptAllowedForCurrentRoute()) {
            directAttemptsSkippedBecauseRoute.incrementAndGet()
            logger.log("DC${parsed.dcId} direct route skipped because effective route mode ${effectiveRouteMode().configValue}")
            return null
        }
        if (usePool && isDirectPoolEnabled()) {
            val pooled = webSocketPool.get(parsed.dcId, parsed.isMedia, targetHost, wsDomains(parsed.dcId, parsed.isMedia))
            if (pooled != null) {
                poolHits.incrementAndGet()
                logger.log("DC${parsed.dcId} direct WS pool hit")
                return WebSocketRoute(pooled, "direct-pool")
            }
            poolMisses.incrementAndGet()
            logger.log("DC${parsed.dcId} direct WS pool miss")
        }
        return guardedConnectWebSocket(parsed, targetHost, timeoutMs, expectedGeneration)?.let { WebSocketRoute(it, "direct-cold") }
    }

    private fun guardedConnectWebSocket(
        parsed: MtprotoHandshake.Result,
        targetHost: String,
        timeoutMs: Int,
        expectedGeneration: Long? = null,
    ): WebSocketBinaryStream? {
        if (expectedGeneration != null && routeGeneration.get() != expectedGeneration) {
            directAttemptsSkippedBecauseRoute.incrementAndGet()
            logger.log("DC${parsed.dcId} direct connect skipped because route generation changed")
            return null
        }
        if (!isDirectAttemptAllowedForCurrentRoute()) {
            directAttemptsSkippedBecauseRoute.incrementAndGet()
            logger.log("DC${parsed.dcId} direct connect skipped because effective route mode ${effectiveRouteMode().configValue}")
            return null
        }
        return connectWebSocket(parsed, targetHost, timeoutMs)
    }

    private fun connectWebSocket(
        parsed: MtprotoHandshake.Result,
        targetHost: String,
        timeoutMs: Int,
    ): WebSocketBinaryStream? {
        val failures = mutableListOf<String>()
        for (domain in wsDomains(parsed.dcId, parsed.isMedia)) {
            if (!isDirectAttemptAllowedForCurrentRoute()) {
                directAttemptsSkippedBecauseRoute.incrementAndGet()
                logger.log("DC${parsed.dcId} direct WebSocket attempt skipped before $domain because route/network changed")
                break
            }
            logger.log(
                "DC${parsed.dcId} media=${parsed.isMedia} -> wss://$domain$DEFAULT_WS_PATH via $targetHost",
            )
            try {
                directAttempts.incrementAndGet()
                val webSocket = webSocketConnector.connect(targetHost, domain, DEFAULT_WS_PATH, timeoutMs)
                logger.log("DC${parsed.dcId} WebSocket connected via $domain")
                return webSocket
            } catch (error: Throwable) {
                wsConnectErrors.incrementAndGet()
                if (isTimeout(error)) directTimeouts.incrementAndGet()
                val detail = websocketFailureDetail(error)
                failures.add("$domain ($detail)")
                logger.log("DC${parsed.dcId} WebSocket attempt via $domain failed: $detail")
                if (error is SocketException || detail.contains("ENETUNREACH", ignoreCase = true)) {
                    downgradeDirectRouteBecauseHealthDegraded(detail)
                }
            }
        }
        logger.log("DC${parsed.dcId} WebSocket connect failed after attempts: ${failures.joinToString()}")
        return null
    }


    private fun tryCfProxyFallback(
        client: TcpClientTransport,
        parsed: MtprotoHandshake.Result,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
    ): Boolean {
        var attempted = false
        for (baseDomain in cfProxyBalancer.getDomainsForDc(parsed.dcId)) {
            attempted = true
            val domain = "kws${parsed.dcId}.$baseDomain"
            logger.log("DC${parsed.dcId} -> trying CF proxy wss://$domain$DEFAULT_WS_PATH")
            val webSocket = try {
                webSocketConnector.connect(domain, domain, DEFAULT_WS_PATH, RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS)
            } catch (error: Throwable) {
                cfProxyErrors.incrementAndGet()
                val detail = websocketFailureDetail(error)
                logger.log("DC${parsed.dcId} CF proxy failed via $baseDomain: $detail")
                null
            } ?: continue

            try {
                cfProxyConnections.incrementAndGet()
                lastCfDomain = domain
                logger.log("DC${parsed.dcId} CF proxy connected via $domain")
                cfProxyBalancer.updateDomainForDc(parsed.dcId, baseDomain)
                val result = runWebSocketRoute(client, parsed, WebSocketRoute(webSocket, "cf"), relayInit, cryptoContext, splitter)
                if (!result.failed) return true
                cfProxyErrors.incrementAndGet()
                logger.log("DC${parsed.dcId} CF proxy route failed via $baseDomain")
            } catch (error: Throwable) {
                cfProxyErrors.incrementAndGet()
                logger.log("DC${parsed.dcId} CF proxy failed via $baseDomain: ${websocketFailureDetail(error)}")
            }
        }
        if (!attempted) {
            logger.log("DC${parsed.dcId} CF proxy has no bundled base domains configured")
        } else {
            logger.log("DC${parsed.dcId} all CF proxy fallback attempts failed")
        }
        return false
    }

    private fun runWebSocketRoute(
        client: TcpClientTransport,
        parsed: MtprotoHandshake.Result,
        route: WebSocketRoute,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
    ): WebSocketRouteResult {
        val counters = BridgeSessionCounters()
        val startedAtNs = System.nanoTime()
        var routeFailure: Throwable? = null
        var bridgeStarted = false
        var durationMs = 0L
        var reason = "completed"
        try {
            lastRouteUsed = route.type
            route.stream.send(relayInit)
            bridgeStarted = true
            bridgeRunner.run(client, route.stream, cryptoContext, splitter, counters)
        } catch (error: Throwable) {
            routeFailure = error
            counters.finish(bridgeExceptionReason(error))
            if (!bridgeStarted) {
                logger.log("DC${parsed.dcId} ${route.type} route failed before bridge: ${failureDetail(error)}")
                if (route.type.startsWith("direct") && (error is SocketException || error.message.orEmpty().contains("ENETUNREACH", ignoreCase = true))) {
                    downgradeDirectRouteBecauseHealthDegraded(failureDetail(error))
                }
            } else {
                logger.log("DC${parsed.dcId} ${route.type} route failed: ${failureDetail(error)}")
            }
        } finally {
            bytesUp.addAndGet(counters.bytesUp)
            bytesDown.addAndGet(counters.bytesDown)
            durationMs = (System.nanoTime() - startedAtNs) / 1_000_000
            reason = counters.closeReason
                ?: routeFailure?.let { bridgeExceptionReason(it) }
                ?: "completed"
            recordSessionEnd(reason)
            logger.log(
                "${client.remoteLabel} session ended: DC${parsed.dcId} media=${parsed.isMedia} " +
                    "route=${route.type} durationMs=$durationMs bytesUp=${counters.bytesUp} " +
                    "bytesDown=${counters.bytesDown} reason=$reason",
            )
            try {
                route.stream.close()
            } catch (_: Throwable) {
                // Best-effort close.
            }
        }
        val failedBeforeBridge = routeFailure != null && !bridgeStarted
        val stalePooled = isStalePooledRouteFailure(route, routeFailure, reason, durationMs, counters, failedBeforeBridge)
        val retryableStalePooled = stalePooled && isRetrySafeStalePooledRoute(counters, failedBeforeBridge)
        if (stalePooled) {
            poolStale.incrementAndGet()
            recordDirectPoolStaleForHealth()
            logger.log(
                "DC${parsed.dcId} direct-pool stale route detected: $reason " +
                    "durationMs=$durationMs bytesUp=${counters.bytesUp} bytesDown=${counters.bytesDown}",
            )
            if (!retryableStalePooled) {
                logger.log("DC${parsed.dcId} stale direct-pool route not retried after bridge state advanced")
            }
        }
        return WebSocketRouteResult(
            failed = routeFailure != null || stalePooled,
            failedBeforeBridge = failedBeforeBridge,
            stalePooled = stalePooled,
            retryableStalePooled = retryableStalePooled,
        )
    }

    private fun isStalePooledRouteFailure(
        route: WebSocketRoute,
        routeFailure: Throwable?,
        reason: String,
        durationMs: Long,
        counters: BridgeSessionCounters,
        failedBeforeBridge: Boolean,
    ): Boolean {
        if (route.type != "direct-pool") return false
        if (!isStalePoolFailure(routeFailure, reason)) return false
        if (failedBeforeBridge) return true
        return durationMs < STALE_POOL_MAX_DURATION_MS || counters.bytesDown == 0L
    }

    private fun isRetrySafeStalePooledRoute(
        counters: BridgeSessionCounters,
        failedBeforeBridge: Boolean,
    ): Boolean {
        if (failedBeforeBridge) return true
        // After bridge startup, retry is only safe while no MTProto payload has
        // moved in either direction. Once bytesUp/bytesDown advance, the shared
        // AES-CTR streams and message splitter may have advanced too, so the
        // client session must not be replayed on a fresh route.
        return counters.bytesUp == 0L && counters.bytesDown == 0L
    }

    private fun isStalePoolFailure(error: Throwable?, reason: String): Boolean {
        if (error != null) {
            if (error is EOFException || error is SocketException) return true
            val message = error.message.orEmpty().lowercase()
            if (message.contains("broken pipe") || message.contains("websocket closed")) return true
        }
        val lowerReason = reason.lowercase()
        return lowerReason.contains("eofexception") ||
            lowerReason.contains("broken pipe") ||
            lowerReason.contains("websocket closed") ||
            lowerReason.contains("socketexception")
    }

    private fun recordSessionEnd(reason: String) {
        val lower = reason.lowercase()
        when {
            lower.contains("sockettimeoutexception") || lower.contains("read timed out") -> sessionTimeouts.incrementAndGet()
            lower.contains("eofexception") || lower.contains("eof") -> sessionEof.incrementAndGet()
            lower.contains("client closed") -> sessionClientClosed.incrementAndGet()
            lower.contains("websocket closed") || lower.contains("socket closed") -> sessionSocketClosed.incrementAndGet()
            lower.contains("exception:") -> sessionUnexpectedErrors.incrementAndGet()
        }
    }

    private fun markBad(message: String) {
        connectionsBad.incrementAndGet()
        logger.log(message)
    }

    private fun effectiveRouteMode(): NetworkRouteMode = routeState.effectiveRouteMode

    private fun isDirectPoolEnabled(): Boolean = isDirectPoolEnabledFor(effectiveRouteMode())

    private fun isDirectAttemptAllowedForCurrentRoute(): Boolean {
        val snapshot = routeState.snapshot()
        if (currentNetworkStatus.equals("none", ignoreCase = true) || currentNetworkStatus.equals("mobile", ignoreCase = true) ||
            currentNetworkStatus.equals("cellular", ignoreCase = true) || directRouteHealth.isSettling()
        ) {
            return false
        }
        return when (snapshot.effectiveRouteMode) {
            NetworkRouteMode.DIRECT_FIRST, NetworkRouteMode.AUTO -> true
            NetworkRouteMode.CF_FIRST -> snapshot.configuredRouteMode == NetworkRouteMode.CF_FIRST
            NetworkRouteMode.CF_ONLY -> false
        }
    }

    private fun handlePoolForRouteChange(previous: NetworkRouteMode, current: NetworkRouteMode) {
        if (isDirectPoolEnabledFor(current) && isWifi(currentNetworkStatus)) {
            if (!isDirectPoolEnabledFor(previous)) startDirectPoolWarmup("route change") else webSocketPool.enable()
        } else {
            webSocketPool.disableAndClear()
            if (config.poolSize > 0) {
                logger.log("Direct WS pool warmup skipped because effective route mode ${current.configValue}")
            }
        }
    }

    private fun startDirectPoolWarmup(reason: String) {
        if (config.poolSize <= 0) return
        logger.log("Direct WS pool warmup started because effective route mode ${effectiveRouteMode().configValue} ($reason)")
        webSocketPool.warmup(config.dcRedirects, ::wsDomains)
    }


    private fun maybeStartAutoWifiDirectProbe(networkStatus: String) {
        if (routeState.configuredRouteMode != NetworkRouteMode.AUTO) return
        if (!isWifi(networkStatus)) return
        if (effectiveRouteMode() == NetworkRouteMode.DIRECT_FIRST) return
        if (directRouteHealth.currentState() == DirectHealthState.COOLDOWN) {
            logger.log("direct promotion skipped: direct route in cooldown")
            return
        }
        directRouteHealth.startPromotionProbe(
            shouldContinue = {
                running.get() &&
                    routeState.configuredRouteMode == NetworkRouteMode.AUTO &&
                    isWifi(currentNetworkStatus) &&
                    effectiveRouteMode() == NetworkRouteMode.CF_FIRST
            },
            onPromote = {
                val before = effectiveRouteMode()
                val result = applyEffectiveRouteMode(
                    NetworkRouteMode.DIRECT_FIRST,
                    "direct health probe success",
                    currentNetworkStatus,
                    source = "direct-health",
                )
                if (result.changed) {
                    directRouteHealth.recordPromotion()
                    logger.log("direct promoted: ${before.configValue} -> ${NetworkRouteMode.DIRECT_FIRST.configValue}")
                }
            },
        )
    }

    private fun downgradeDirectRouteBecauseHealthDegraded(reason: String) {
        if (routeState.configuredRouteMode != NetworkRouteMode.AUTO) return
        if (effectiveRouteMode() != NetworkRouteMode.DIRECT_FIRST) return
        directRouteHealth.startCooldown(DIRECT_HEALTH_COOLDOWN_MS, reason)
        val result = applyEffectiveRouteMode(
            NetworkRouteMode.CF_FIRST,
            "direct health degraded: $reason",
            currentNetworkStatus,
            source = "direct-health",
        )
        if (result.changed) {
            webSocketPool.disableAndClear()
            logger.log("direct route downgraded to cf_first because health degraded")
        }
    }

    private fun recordDirectPoolStaleForHealth() {
        val now = System.currentTimeMillis()
        if (now - lastDirectPoolStaleWindowStartMs > DIRECT_HEALTH_DEGRADE_WINDOW_MS) {
            lastDirectPoolStaleWindowStartMs = now
            directPoolStaleInWindow = 0
        }
        directPoolStaleInWindow += 1
        if (directPoolStaleInWindow >= DIRECT_POOL_STALE_DOWNGRADE_THRESHOLD) {
            downgradeDirectRouteBecauseHealthDegraded("poolStale >= $DIRECT_POOL_STALE_DOWNGRADE_THRESHOLD")
        }
    }

    private fun directRouteContextAllowsAttempt(expectedGeneration: Long? = null): Boolean {
        if (expectedGeneration != null && routeGeneration.get() != expectedGeneration) return false
        if (effectiveRouteMode() != NetworkRouteMode.DIRECT_FIRST) return false
        if (!isWifi(currentNetworkStatus)) return false
        if (directRouteHealth.isSettling()) return false
        return true
    }

    private fun isWifi(networkStatus: String): Boolean =
        networkStatus.equals("Wi-Fi", ignoreCase = true) || networkStatus.equals("wifi", ignoreCase = true)

    private fun closeClient(client: TcpClientTransport) {
        try {
            client.close()
        } catch (_: Throwable) {
            // Best-effort close.
        }
    }

    private fun joinAcceptThreadBestEffort() {
        val thread = acceptThread ?: return
        if (Thread.currentThread() == thread) return
        try {
            thread.join(STOP_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val DEFAULT_WS_PATH = "/apiws"
        const val DEFAULT_MOBILE_DIRECT_FALLBACK_TIMEOUT_MS = 2_000
        private const val STOP_JOIN_TIMEOUT_MS = 1_000L
        private const val STALE_POOL_MAX_DURATION_MS = 2_000L
        private const val ROUTE_SETTLING_WINDOW_MS = 3_000L
        private const val DIRECT_HEALTH_COOLDOWN_MS = 45_000L
        private const val DIRECT_HEALTH_DEGRADE_WINDOW_MS = 10_000L
        private const val DIRECT_POOL_STALE_DOWNGRADE_THRESHOLD = 3L

        private fun isDirectPoolEnabledFor(mode: NetworkRouteMode): Boolean =
            mode == NetworkRouteMode.DIRECT_FIRST || mode == NetworkRouteMode.AUTO

        fun protoIntForProtoTag(protoTag: ByteArray): Int = when {
            protoTag.contentEquals(RelayInit.PROTO_TAG_ABRIDGED) -> MsgSplitter.PROTO_ABRIDGED_INT
            protoTag.contentEquals(RelayInit.PROTO_TAG_INTERMEDIATE) -> MsgSplitter.PROTO_INTERMEDIATE_INT
            protoTag.contentEquals(RelayInit.PROTO_TAG_SECURE) -> MsgSplitter.PROTO_PADDED_INTERMEDIATE_INT
            else -> throw IllegalArgumentException("Unknown MTProto protocol tag")
        }

        private fun protoTagLabel(protoTag: ByteArray): String = when {
            protoTag.contentEquals(RelayInit.PROTO_TAG_ABRIDGED) -> "abridged"
            protoTag.contentEquals(RelayInit.PROTO_TAG_INTERMEDIATE) -> "intermediate"
            protoTag.contentEquals(RelayInit.PROTO_TAG_SECURE) -> "secure"
            else -> "unknown"
        }

        private fun failureDetail(error: Throwable): String =
            "${error::class.java.simpleName}: ${error.message ?: "no message"}"

        private fun isTimeout(error: Throwable): Boolean =
            error::class.java.simpleName.contains("SocketTimeoutException") ||
                error.message.orEmpty().contains("timeout", ignoreCase = true)

        private fun websocketFailureDetail(error: Throwable): String {
            val base = failureDetail(error)
            if (error is RawWebSocket.WsHandshakeException) {
                val location = error.location?.let { " location=$it" }.orEmpty()
                return "$base status=${error.statusCode}$location"
            }
            return base
        }
    }
}

class JavaTcpServerTransport : TcpServerTransport {
    private var serverSocket: ServerSocket? = null

    override fun bind(host: String, port: Int) {
        serverSocket = ServerSocket().also { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(host, port))
        }
    }

    override fun accept(): TcpClientTransport? {
        val socket = serverSocket?.accept() ?: return null
        return JavaTcpClientTransport(socket)
    }

    override fun close() {
        serverSocket?.close()
        serverSocket = null
    }
}

class JavaTcpClientTransport(private val socket: Socket) : TcpClientTransport {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override val remoteLabel: String
        get() = socket.remoteSocketAddress?.toString() ?: "unknown"

    override fun readExact(byteCount: Int): ByteArray? {
        require(byteCount >= 0) { "byteCount must be non-negative" }
        val data = ByteArray(byteCount)
        var offset = 0
        while (offset < byteCount) {
            val read = input.read(data, offset, byteCount - offset)
            if (read < 0) return null
            offset += read
        }
        return data
    }

    override fun read(bufferSize: Int): ByteArray? {
        require(bufferSize > 0) { "bufferSize must be positive" }
        val buffer = ByteArray(bufferSize)
        val count = try {
            input.read(buffer)
        } catch (error: EOFException) {
            -1
        }
        if (count < 0) return null
        return buffer.copyOf(count)
    }

    override fun write(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    override fun close() = socket.close()
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must contain an even number of characters" }
    return ByteArray(length / 2) { index ->
        val high = Character.digit(this[index * 2], 16)
        val low = Character.digit(this[index * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "hex string contains a non-hex character" }
        ((high shl 4) or low).toByte()
    }
}
