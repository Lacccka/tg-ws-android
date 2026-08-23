package com.flowseal.tgwsandroid.proxy

/**
 * RawWebSocketConnector that keeps the existing WebSocket framing/handshake but
 * obtains the underlying TCP stream through a SOCKS5 outbound.
 *
 * Intended for embedded tunnel engines such as Xray: Telegram-facing MTProto,
 * crypto and BridgeSession stay unchanged while only the upstream dial path is
 * replaced.
 */
class SocksRawWebSocketConnector internal constructor(
    private val transportFactory: RawWebSocket.TransportFactory,
) : RawWebSocketConnector {
    constructor(
        socksHost: String,
        socksPort: Int,
        credentials: Socks5Credentials? = null,
    ) : this(Socks5TlsTransportFactory(socksHost, socksPort, credentials))

    override fun connect(
        targetHost: String,
        domain: String,
        path: String,
        timeoutMs: Int,
    ): WebSocketBinaryStream = RawWebSocketBinaryStream(
        RawWebSocket.connect(
            host = targetHost,
            domain = domain,
            path = path,
            timeoutMs = timeoutMs,
            transportFactory = transportFactory,
        ),
    )

    override fun connectWithSni(
        targetHost: String,
        domain: String,
        path: String,
        timeoutMs: Int,
        sniHost: String,
    ): WebSocketBinaryStream = RawWebSocketBinaryStream(
        RawWebSocket.connect(
            host = targetHost,
            domain = domain,
            path = path,
            timeoutMs = timeoutMs,
            sni = sniHost,
            transportFactory = transportFactory,
        ),
    )
}
