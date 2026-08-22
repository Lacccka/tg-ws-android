from pathlib import Path

path = Path("app/src/main/java/com/flowseal/tgwsandroid/proxy/ProxyServer.kt")
text = path.read_text(encoding="utf-8")


def replace_once(label: str, old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "RawWebSocketConnector custom SNI",
    '''fun interface RawWebSocketConnector {
    fun connect(targetHost: String, domain: String, path: String, timeoutMs: Int): WebSocketBinaryStream
}''',
    '''fun interface RawWebSocketConnector {
    fun connect(targetHost: String, domain: String, path: String, timeoutMs: Int): WebSocketBinaryStream

    fun connectWithSni(
        targetHost: String,
        domain: String,
        path: String,
        timeoutMs: Int,
        sniHost: String,
    ): WebSocketBinaryStream {
        if (sniHost == domain) return connect(targetHost, domain, path, timeoutMs)
        throw UnsupportedOperationException("custom TLS SNI is not supported by this connector")
    }
}''',
)

replace_once(
    "ProxyServer production connector",
    '''    private val serverTransport: TcpServerTransport = JavaTcpServerTransport(),
    private val webSocketConnector: RawWebSocketConnector = RawWebSocketConnector { targetHost, domain, path, timeoutMs ->
        RawWebSocketBinaryStream(RawWebSocket.connect(host = targetHost, domain = domain, path = path, timeoutMs = timeoutMs))
    },
    private val bridgeRunner: ProxyBridgeRunner = ProxyBridgeRunner { client, webSocket, cryptoContext, splitter, counters ->''',
    '''    private val serverTransport: TcpServerTransport = JavaTcpServerTransport(),
    private val webSocketConnector: RawWebSocketConnector = DefaultRawWebSocketConnector,
    private val bridgeRunner: ProxyBridgeRunner = ProxyBridgeRunner { client, webSocket, cryptoContext, splitter, counters ->''',
)

replace_once(
    "ProxyServer fronting strategy",
    '''    @Volatile private var lastCfDomain: String? = null
    private val invalidHandshakeLogLimiter = InvalidHandshakeLogLimiter()
    private val webSocketPool = WebSocketPool(''',
    '''    @Volatile private var lastCfDomain: String? = null
    private val invalidHandshakeLogLimiter = InvalidHandshakeLogLimiter()
    private val frontingAttempts = AtomicLong(0)
    private val frontingSuccesses = AtomicLong(0)
    private val frontingFailures = AtomicLong(0)
    private val directFrontingConnector = DirectFrontingConnector(
        normalConnect = { targetHost, domain, path, timeoutMs ->
            webSocketConnector.connect(targetHost, domain, path, timeoutMs)
        },
        frontedConnect = { targetHost, domain, path, timeoutMs, sniHost ->
            webSocketConnector.connectWithSni(targetHost, domain, path, timeoutMs, sniHost)
        },
        onFrontingAttempt = { key, frontingFirst ->
            frontingAttempts.incrementAndGet()
            logger.log(
                "DC${key.dc} media=${key.isMedia} fronting ${if (frontingFirst) "first" else "fallback"} " +
                    "attempt SNI=${DirectFrontingConnector.DEFAULT_FRONTING_SNI} target=${key.targetHost}",
            )
        },
        onFrontingSuccess = { key, frontingFirst ->
            frontingSuccesses.incrementAndGet()
            logger.log("DC${key.dc} media=${key.isMedia} fronting success first=$frontingFirst target=${key.targetHost}")
        },
        onFrontingFailure = { key, frontingFirst, error ->
            frontingFailures.incrementAndGet()
            logger.log("DC${key.dc} media=${key.isMedia} fronting failed first=$frontingFirst: ${failureDetail(error)}")
        },
    )
    private val webSocketPool = WebSocketPool(''',
)

replace_once(
    "mobile direct rescue",
    '''        return try {
            directAttempts.incrementAndGet()
            logger.log("DC${parsed.dcId} mobile direct rescue trying wss://$domain$DEFAULT_WS_PATH via $targetHost")
            val webSocket = webSocketConnector.connect(targetHost, domain, DEFAULT_WS_PATH, config.directFallbackTimeoutMs)
            synchronized(state) {
                state.inFlight = false
                state.cooldownUntilMs = System.currentTimeMillis() + MOBILE_DIRECT_RESCUE_SUCCESS_REUSE_MS
                state.lastError = null
                state.lastSuccessTimeMs = System.currentTimeMillis()
                state.generation = expectedGeneration
            }
            mobileDirectRescueSuccesses.incrementAndGet()
            recordMobileGenerationRecoveryResult(expectedGeneration, success = true)
            logger.log("DC${parsed.dcId} mobile direct rescue success; direct route allowed for this client")
            webSocket
        } catch (error: Throwable) {''',
    '''        return try {
            directAttempts.incrementAndGet()
            logger.log("DC${parsed.dcId} mobile direct rescue trying direct/fronting wss://$domain$DEFAULT_WS_PATH via $targetHost")
            val connectResult = directFrontingConnector.connect(
                dc = parsed.dcId,
                isMedia = parsed.isMedia,
                targetHost = targetHost,
                domain = domain,
                path = DEFAULT_WS_PATH,
                normalTimeoutMs = config.directFallbackTimeoutMs,
                networkGeneration = expectedGeneration,
            )
            val webSocket = connectResult.stream
            synchronized(state) {
                state.inFlight = false
                state.cooldownUntilMs = System.currentTimeMillis() + MOBILE_DIRECT_RESCUE_SUCCESS_REUSE_MS
                state.lastError = null
                state.lastSuccessTimeMs = System.currentTimeMillis()
                state.generation = expectedGeneration
            }
            mobileDirectRescueSuccesses.incrementAndGet()
            recordMobileGenerationRecoveryResult(expectedGeneration, success = true)
            logger.log(
                "DC${parsed.dcId} mobile direct rescue success; direct route allowed for this client " +
                    "fronted=${connectResult.fronted} frontingFirst=${connectResult.frontingTriedFirst}",
            )
            webSocket
        } catch (error: Throwable) {''',
)

replace_once(
    "cold direct connect",
    '''            try {
                directAttempts.incrementAndGet()
                val webSocket = webSocketConnector.connect(targetHost, domain, DEFAULT_WS_PATH, timeoutMs)
                clearDirectTargetIpCooldownAfterSuccess(targetHost)
                logger.log("DC${parsed.dcId} WebSocket connected via $domain")
                return webSocket
            } catch (error: Throwable) {''',
    '''            try {
                directAttempts.incrementAndGet()
                val connectResult = directFrontingConnector.connect(
                    dc = parsed.dcId,
                    isMedia = parsed.isMedia,
                    targetHost = targetHost,
                    domain = domain,
                    path = DEFAULT_WS_PATH,
                    normalTimeoutMs = timeoutMs,
                    networkGeneration = routeGeneration.get(),
                )
                val webSocket = connectResult.stream
                clearDirectTargetIpCooldownAfterSuccess(targetHost)
                logger.log(
                    "DC${parsed.dcId} WebSocket connected via $domain " +
                        "fronted=${connectResult.fronted} frontingFirst=${connectResult.frontingTriedFirst}",
                )
                return webSocket
            } catch (error: Throwable) {''',
)

path.write_text(text, encoding="utf-8")
print("Applied exact fronting runtime edits to ProxyServer.kt")
