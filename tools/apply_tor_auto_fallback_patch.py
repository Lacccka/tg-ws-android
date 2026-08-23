from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


proxy_path = Path("app/src/main/java/com/flowseal/tgwsandroid/proxy/ProxyServer.kt")
p = proxy_path.read_text()

p = replace_once(p,
'''    val networkStatus: String = "unknown",
    val directFallbackTimeoutMs: Int = 2_000,
) {''',
'''    val networkStatus: String = "unknown",
    val directFallbackTimeoutMs: Int = 2_000,
    /** Enables an independently managed Tor/Snowflake outbound after ordinary routes fail. */
    val torSnowflakeFallbackEnabled: Boolean = false,
    val torSnowflakeConnectTimeoutMs: Int = 15_000,
) {''', "config fields")

p = replace_once(p,
'''    var lastCfDomain: String? = null
    var directAttempts: Long = 0
    var directAttemptsSkippedBecauseRoute: Long = 0''',
'''    var lastCfDomain: String? = null
    var torSnowflakeAttempts: Long = 0
    var torSnowflakeSuccesses: Long = 0
    var torSnowflakeFailures: Long = 0
    var torSnowflakeUnavailable: Long = 0
    var lastTorSnowflakeError: String? = null
    var lastTorSnowflakeTimeMs: Long = 0
    var directAttempts: Long = 0
    var directAttemptsSkippedBecauseRoute: Long = 0''', "stats fields")

p = replace_once(p,
'''    private val serverTransport: TcpServerTransport = JavaTcpServerTransport(),
    private val webSocketConnector: RawWebSocketConnector = DefaultRawWebSocketConnector,
    private val bridgeRunner:''',
'''    private val serverTransport: TcpServerTransport = JavaTcpServerTransport(),
    private val webSocketConnector: RawWebSocketConnector = DefaultRawWebSocketConnector,
    /** Separate connector so Tor success never contaminates direct/CF health or pooling. */
    private val torSnowflakeConnector: RawWebSocketConnector? = null,
    private val bridgeRunner:''', "constructor connector")

p = replace_once(p,
'''    private val directTimeouts = AtomicLong(0)
    private val directAttempts = AtomicLong(0)''',
'''    private val directTimeouts = AtomicLong(0)
    private val torSnowflakeAttempts = AtomicLong(0)
    private val torSnowflakeSuccesses = AtomicLong(0)
    private val torSnowflakeFailures = AtomicLong(0)
    private val torSnowflakeUnavailable = AtomicLong(0)
    private val lastTorSnowflakeError = AtomicReference<String?>(null)
    private val lastTorSnowflakeTimeMs = AtomicLong(0)
    private val directAttempts = AtomicLong(0)''', "atomic counters")

p = replace_once(p,
'''        snapshot.lastCfDomain = lastCfDomain
        snapshot.directAttempts = directAttempts.get()''',
'''        snapshot.lastCfDomain = lastCfDomain
        snapshot.torSnowflakeAttempts = torSnowflakeAttempts.get()
        snapshot.torSnowflakeSuccesses = torSnowflakeSuccesses.get()
        snapshot.torSnowflakeFailures = torSnowflakeFailures.get()
        snapshot.torSnowflakeUnavailable = torSnowflakeUnavailable.get()
        snapshot.lastTorSnowflakeError = lastTorSnowflakeError.get()
        snapshot.lastTorSnowflakeTimeMs = lastTorSnowflakeTimeMs.get()
        snapshot.directAttempts = directAttempts.get()''', "stats assignments")

p = replace_once(p,
'''                if (tryEmergencyDirectFallback(client, parsed, targetHost, relayInit, cryptoContext, splitter, routeAttemptStartGeneration, cfFirstDirectFallbackAttempted)) return true
                if (tryCfInflightWaitBeforeNoRoute(client, parsed, relayInit, cryptoContext, splitter)) return true
                recordNoRoute(parsed.dcId)
                logger.log("DC${parsed.dcId} no route available after CF-first attempts")''',
'''                if (tryEmergencyDirectFallback(client, parsed, targetHost, relayInit, cryptoContext, splitter, routeAttemptStartGeneration, cfFirstDirectFallbackAttempted)) return true
                if (tryCfInflightWaitBeforeNoRoute(client, parsed, relayInit, cryptoContext, splitter)) return true
                if (tryTorSnowflakeFallback(client, parsed, targetHost, relayInit, cryptoContext, splitter)) return true
                recordNoRoute(parsed.dcId)
                logger.log("DC${parsed.dcId} no route available after CF-first/Tor attempts")''', "cf first fallback")

p = replace_once(p,
'''            NetworkRouteMode.DIRECT_FIRST, NetworkRouteMode.AUTO -> {
                if (tryDirectRoute(client, parsed, targetHost, relayInit, cryptoContext, splitter, usePool = true, timeoutMs = RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS)) return true
                if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)) return true
                recordNoRoute(parsed.dcId)
                logger.log("DC${parsed.dcId} no route available after direct WebSocket attempts")
                downgradeDirectRouteBecauseHealthDegraded("no route available after direct attempts")
            }''',
'''            NetworkRouteMode.DIRECT_FIRST, NetworkRouteMode.AUTO -> {
                if (tryDirectRoute(client, parsed, targetHost, relayInit, cryptoContext, splitter, usePool = true, timeoutMs = RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS)) return true
                if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)) return true
                if (tryTorSnowflakeFallback(client, parsed, targetHost, relayInit, cryptoContext, splitter)) return true
                recordNoRoute(parsed.dcId)
                logger.log("DC${parsed.dcId} no route available after direct/CF/Tor attempts")
                downgradeDirectRouteBecauseHealthDegraded("no route available after direct attempts")
            }''', "direct first fallback")

p = replace_once(p,
'''\n\n    private fun tryCfInflightWaitBeforeNoRoute(\n''',
'''\n\n    private fun tryTorSnowflakeFallback(
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
            } catch (error: TorSnowflakeUnavailableException) {
                torSnowflakeUnavailable.incrementAndGet()
                lastTorSnowflakeError.set(error.message)
                logger.log("DC${parsed.dcId} Tor/Snowflake fallback warming/unavailable: ${error.message}")
                return false
            } catch (error: Throwable) {
                torSnowflakeFailures.incrementAndGet()
                lastTorSnowflakeError.set(websocketFailureDetail(error))
                logger.log("DC${parsed.dcId} Tor/Snowflake WebSocket via $domain failed: ${websocketFailureDetail(error)}")
                continue
            }

            torSnowflakeSuccesses.incrementAndGet()
            lastTorSnowflakeError.set(null)
            logger.log("DC${parsed.dcId} Tor/Snowflake WebSocket connected via $domain")
            val result = runWebSocketRoute(
                client,
                parsed,
                WebSocketRoute(webSocket, TOR_SNOWFLAKE_ROUTE_TYPE),
                relayInit,
                cryptoContext,
                splitter,
            )
            if (!result.failed) return true
            if (!result.failedBeforeBridge) return true
            torSnowflakeFailures.incrementAndGet()
            lastTorSnowflakeError.set("route failed before bridge")
        }
        return false
    }

    private fun tryCfInflightWaitBeforeNoRoute(
''', "tor function insertion")
proxy_path.write_text(p)

service_path = Path("app/src/main/java/com/flowseal/tgwsandroid/service/ProxyForegroundService.kt")
s = service_path.read_text()
s = replace_once(s,
'''    private var proxyServer: ProxyServer? = null
    private var networkCallback:''',
'''    private var proxyServer: ProxyServer? = null
    private var torFallbackRuntime: TorFallbackRuntime? = null
    private var networkCallback:''', "service field")

s = replace_once(s,
'''            val logger = ProxyLogger { message -> State.addProxyLog(message) }
            val server = ProxyServer(ProxyRuntimeConfig.proxyServerConfig(applicationContext, State.networkStatus), logger = logger)
            proxyServer = server''',
'''            val logger = ProxyLogger { message -> State.addProxyLog(message) }
            val runtime = TorFallbackRuntimeLoader.create(applicationContext, logger)
            torFallbackRuntime = runtime
            runtime?.onNetworkChanged(State.networkStatus)
            val serverConfig = ProxyRuntimeConfig.proxyServerConfig(applicationContext, State.networkStatus).copy(
                torSnowflakeFallbackEnabled = runtime != null,
            )
            val server = ProxyServer(
                config = serverConfig,
                torSnowflakeConnector = runtime?.connector,
                logger = logger,
            )
            proxyServer = server''', "service startup")

s = replace_once(s,
'''    private fun applyRouteForNetwork(networkStatus: String, immediate: Boolean = false) {
        val server = synchronized(lock) { proxyServer }''',
'''    private fun applyRouteForNetwork(networkStatus: String, immediate: Boolean = false) {
        torFallbackRuntime?.onNetworkChanged(networkStatus)
        val server = synchronized(lock) { proxyServer }''', "service network hook")

s = replace_once(s,
'''        State.setLiveStatsProvider(null)
        if (server != null) {''',
'''        State.setLiveStatsProvider(null)
        val torRuntime = synchronized(lock) {
            torFallbackRuntime.also { torFallbackRuntime = null }
        }
        torRuntime?.stop()
        if (server != null) {''', "service stop runtime")
service_path.write_text(s)

test_path = Path("app/src/test/java/com/flowseal/tgwsandroid/proxy/ProxyServerTest.kt")
t = test_path.read_text()
t = replace_once(t,
'''        connector: RawWebSocketConnector = RecordingConnector(FakeWebSocketBinaryStream()),
        runner: ProxyBridgeRunner = ProxyBridgeRunner { _, _, _, _, _ -> },''',
'''        connector: RawWebSocketConnector = RecordingConnector(FakeWebSocketBinaryStream()),
        torSnowflakeConnector: RawWebSocketConnector? = null,
        runner: ProxyBridgeRunner = ProxyBridgeRunner { _, _, _, _, _ -> },''', "test helper arg")
t = replace_once(t,
'''            webSocketConnector = connector,
            bridgeRunner = runner,''',
'''            webSocketConnector = connector,
            torSnowflakeConnector = torSnowflakeConnector,
            bridgeRunner = runner,''', "test helper pass")

t = replace_once(t,
'''    @Test
    fun protoTagsMapToExpectedSplitterProtoInts() {''',
'''    @Test
    fun autoMobileUsesTorSnowflakeAfterOrdinaryRoutesAreUnavailable() {
        val server = FakeTcpServerTransport()
        val direct = RecordingConnector(FakeWebSocketBinaryStream())
        val tor = RecordingConnector(FakeWebSocketBinaryStream())
        val proxy = newProxy(
            server = server,
            connector = direct,
            torSnowflakeConnector = tor,
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfproxyEnabled = false,
                torSnowflakeFallbackEnabled = true,
            ),
        )

        proxy.start()
        server.enqueue(FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes()))
        waitUntil("Tor/Snowflake route") { proxy.stats().lastRouteUsed == TOR_SNOWFLAKE_ROUTE_TYPE }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(TOR_SNOWFLAKE_ROUTE_TYPE, stats.lastRouteUsed)
        assertEquals(0L, stats.directAttempts)
        assertEquals(1L, stats.torSnowflakeAttempts)
        assertEquals(1L, stats.torSnowflakeSuccesses)
        assertEquals(0L, stats.torSnowflakeFailures)
        assertTrue(direct.domains.isEmpty())
        assertEquals(listOf("kws2.web.telegram.org"), tor.domains)
    }

    @Test
    fun warmingTorFallbackDoesNotCountAsTorNetworkFailure() {
        val server = FakeTcpServerTransport()
        val proxy = newProxy(
            server = server,
            torSnowflakeConnector = RawWebSocketConnector { _, _, _, _ ->
                throw TorSnowflakeUnavailableException("bootstrap=72%")
            },
            config = baseConfig().copy(
                routeMode = NetworkRouteMode.AUTO,
                networkStatus = "mobile",
                cfproxyEnabled = false,
                torSnowflakeFallbackEnabled = true,
            ),
        )

        proxy.start()
        val client = FakeTcpClientTransport(handshakeVector("abridged_dc2").getString("handshake_hex").hexToBytes())
        server.enqueue(client)
        waitUntil("client closes after warming Tor fallback") { client.closed }
        proxy.stop()

        val stats = proxy.stats()
        assertEquals(1L, stats.torSnowflakeAttempts)
        assertEquals(0L, stats.torSnowflakeSuccesses)
        assertEquals(0L, stats.torSnowflakeFailures)
        assertEquals(1L, stats.torSnowflakeUnavailable)
        assertTrue(stats.lastTorSnowflakeError.orEmpty().contains("72%"))
    }

    @Test
    fun protoTagsMapToExpectedSplitterProtoInts() {''', "tests insertion")
test_path.write_text(t)
