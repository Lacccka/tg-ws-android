package com.flowseal.tgwsandroid.proxy

/** Production RawWebSocketConnector that can override TLS SNI for fronting. */
internal object DefaultRawWebSocketConnector : RawWebSocketConnector {
    override fun connect(
        targetHost: String,
        domain: String,
        path: String,
        timeoutMs: Int,
    ): WebSocketBinaryStream =
        RawWebSocketBinaryStream(
            RawWebSocket.connect(
                host = targetHost,
                domain = domain,
                path = path,
                timeoutMs = timeoutMs,
            ),
        )

    override fun connectWithSni(
        targetHost: String,
        domain: String,
        path: String,
        timeoutMs: Int,
        sniHost: String,
    ): WebSocketBinaryStream =
        RawWebSocketBinaryStream(
            RawWebSocket.connect(
                host = targetHost,
                domain = domain,
                path = path,
                timeoutMs = timeoutMs,
                sni = sniHost,
            ),
        )
}
