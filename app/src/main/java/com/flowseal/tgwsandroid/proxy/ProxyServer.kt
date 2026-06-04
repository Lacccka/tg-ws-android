package com.flowseal.tgwsandroid.proxy

import com.flowseal.tgwsandroid.config.AppConfig
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
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
) {
    companion object {
        fun fromAppConfig(appConfig: AppConfig): ProxyServerConfig = ProxyServerConfig(
            host = appConfig.host,
            port = appConfig.port,
            secretHex = appConfig.secret,
            dcRedirects = appConfig.dcIp.toDcRedirects(),
            bufferSizeBytes = appConfig.bufKb * 1024,
            poolSize = appConfig.poolSize,
            cfproxyEnabled = appConfig.cfproxy,
            cfProxyDomains = appConfig.cfproxyUserDomain.ifEmpty { CfProxyDomains.defaults },
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
    fun connect(targetHost: String, domain: String, path: String): WebSocketBinaryStream
}

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
    private val webSocketConnector: RawWebSocketConnector = RawWebSocketConnector { targetHost, domain, path ->
        RawWebSocketBinaryStream(RawWebSocket.connect(host = targetHost, domain = domain, path = path))
    },
    private val bridgeRunner: ProxyBridgeRunner = ProxyBridgeRunner { client, webSocket, cryptoContext, splitter, counters ->
        BridgeSession(client, webSocket, cryptoContext, splitter, counters, config.bufferSizeBytes).runBlocking()
    },
    private val cfProxyBalancer: CfProxyBalancer = CfProxyBalancer(config.cfProxyDomains),
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
    private val poolHits = AtomicLong(0)
    private val poolMisses = AtomicLong(0)
    private val poolRefillErrors = AtomicLong(0)
    private val webSocketPool = WebSocketPool(
        poolSize = config.poolSize,
        connector = webSocketConnector,
        logger = logger,
        onRefillError = { poolRefillErrors.incrementAndGet() },
    )
    private var acceptThread: Thread? = null

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverTransport.bind(config.host, config.port)
        if (config.poolSize > 0) {
            webSocketPool.warmup(config.dcRedirects, ::wsDomains)
        }
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

    fun stats(): ProxyServerStats = ProxyServerStats(
        connectionsTotal = connectionsTotal.get(),
        connectionsActive = connectionsActive.get(),
        connectionsBad = connectionsBad.get(),
        wsConnectErrors = wsConnectErrors.get(),
        cfProxyConnections = cfProxyConnections.get(),
        cfProxyErrors = cfProxyErrors.get(),
        bytesUp = bytesUp.get(),
        bytesDown = bytesDown.get(),
        poolHits = poolHits.get(),
        poolMisses = poolMisses.get(),
        poolRefillErrors = poolRefillErrors.get(),
    )

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
        val counters = BridgeSessionCounters()

        if (targetHost == null) {
            if (config.cfproxyEnabled) {
                logger.log("DC${parsed.dcId} has no direct redirect configured; trying CF fallback")
            }
            if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter, counters)) {
                return
            }
            markBad("Unsupported DC ${parsed.dcId} from ${client.remoteLabel}; no direct redirect or CF proxy route available")
            return
        }

        val directWebSocket = getPooledOrConnectWebSocket(parsed, targetHost)
        if (directWebSocket != null) {
            runWebSocketRoute(client, directWebSocket, relayInit, cryptoContext, splitter, counters)
            return
        }

        if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter, counters)) {
            return
        }

        logger.log("DC${parsed.dcId} no route available after direct WebSocket attempts")
    }


    private fun getPooledOrConnectWebSocket(
        parsed: MtprotoHandshake.Result,
        targetHost: String,
    ): WebSocketBinaryStream? {
        if (config.poolSize > 0) {
            val pooled = webSocketPool.get(parsed.dcId, parsed.isMedia, targetHost, wsDomains(parsed.dcId, parsed.isMedia))
            if (pooled != null) {
                poolHits.incrementAndGet()
                logger.log("DC${parsed.dcId} direct WS pool hit")
                return pooled
            }
            poolMisses.incrementAndGet()
            logger.log("DC${parsed.dcId} direct WS pool miss")
        }
        return connectWebSocket(parsed, targetHost)
    }

    private fun connectWebSocket(
        parsed: MtprotoHandshake.Result,
        targetHost: String,
    ): WebSocketBinaryStream? {
        val failures = mutableListOf<String>()
        for (domain in wsDomains(parsed.dcId, parsed.isMedia)) {
            logger.log(
                "DC${parsed.dcId} media=${parsed.isMedia} -> wss://$domain$DEFAULT_WS_PATH via $targetHost",
            )
            try {
                val webSocket = webSocketConnector.connect(targetHost, domain, DEFAULT_WS_PATH)
                logger.log("DC${parsed.dcId} WebSocket connected via $domain")
                return webSocket
            } catch (error: Throwable) {
                wsConnectErrors.incrementAndGet()
                val detail = websocketFailureDetail(error)
                failures.add("$domain ($detail)")
                logger.log("DC${parsed.dcId} WebSocket attempt via $domain failed: $detail")
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
        counters: BridgeSessionCounters,
    ): Boolean {
        var attempted = false
        for (baseDomain in cfProxyBalancer.getDomainsForDc(parsed.dcId)) {
            attempted = true
            val domain = "kws${parsed.dcId}.$baseDomain"
            logger.log("DC${parsed.dcId} -> trying CF proxy wss://$domain$DEFAULT_WS_PATH")
            val webSocket = try {
                webSocketConnector.connect(domain, domain, DEFAULT_WS_PATH)
            } catch (error: Throwable) {
                cfProxyErrors.incrementAndGet()
                val detail = websocketFailureDetail(error)
                logger.log("DC${parsed.dcId} CF proxy failed via $baseDomain: $detail")
                null
            } ?: continue

            try {
                cfProxyConnections.incrementAndGet()
                logger.log("DC${parsed.dcId} CF proxy connected via $domain")
                cfProxyBalancer.updateDomainForDc(parsed.dcId, baseDomain)
                runWebSocketRoute(client, webSocket, relayInit, cryptoContext, splitter, counters)
                return true
            } catch (error: Throwable) {
                cfProxyErrors.incrementAndGet()
                logger.log("DC${parsed.dcId} CF proxy failed via $baseDomain: ${websocketFailureDetail(error)}")
            }
        }
        if (!attempted) {
            logger.log("DC${parsed.dcId} CF proxy has no bundled base domains configured")
        } else {
            logger.log("DC${parsed.dcId} CF proxy connect failed after all attempts")
        }
        return false
    }

    private fun runWebSocketRoute(
        client: TcpClientTransport,
        webSocket: WebSocketBinaryStream,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
        counters: BridgeSessionCounters,
    ) {
        try {
            webSocket.send(relayInit)
            bridgeRunner.run(client, webSocket, cryptoContext, splitter, counters)
        } finally {
            bytesUp.addAndGet(counters.bytesUp)
            bytesDown.addAndGet(counters.bytesDown)
            try {
                webSocket.close()
            } catch (_: Throwable) {
                // Best-effort close.
            }
        }
    }

    private fun markBad(message: String) {
        connectionsBad.incrementAndGet()
        logger.log(message)
    }

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
        private const val STOP_JOIN_TIMEOUT_MS = 1_000L

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

        private fun websocketFailureDetail(error: Throwable): String {
            val base = "${error::class.java.simpleName}: ${error.message ?: "no message"}"
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
