package com.flowseal.tgwsandroid.proxy

import java.net.SocketTimeoutException

/** Key for deciding whether a direct Telegram WebSocket route should prefer fronting. */
internal data class DirectFrontingRouteKey(
    val dc: Int,
    val isMedia: Boolean,
    val targetHost: String,
)

/** Successful direct connection together with the transport variant that produced it. */
internal data class DirectFrontingConnectResult(
    val stream: WebSocketBinaryStream,
    val fronted: Boolean,
    val frontingTriedFirst: Boolean,
)

/**
 * Android-aware fronting preference state.
 *
 * Upstream v1.9 keeps one global `try_fronting_first` boolean. Android changes
 * networks much more frequently, so carrying a preference learned on one Wi-Fi
 * or mobile network into another network is unsafe. This state is therefore
 * scoped to the current network generation and to DC/media/target-host.
 */
internal class DirectFrontingPreferenceState {
    private val lock = Any()
    private var generation: Long = Long.MIN_VALUE
    private val preferred = mutableSetOf<DirectFrontingRouteKey>()

    fun shouldTryFrontingFirst(
        key: DirectFrontingRouteKey,
        networkGeneration: Long,
    ): Boolean = synchronized(lock) {
        ensureGenerationLocked(networkGeneration)
        key in preferred
    }

    fun recordFrontingSuccess(
        key: DirectFrontingRouteKey,
        networkGeneration: Long,
    ) {
        synchronized(lock) {
            ensureGenerationLocked(networkGeneration)
            preferred.add(key)
        }
    }

    fun recordNormalDirectSuccess(
        key: DirectFrontingRouteKey,
        networkGeneration: Long,
    ) {
        synchronized(lock) {
            ensureGenerationLocked(networkGeneration)
            preferred.remove(key)
        }
    }

    fun clearForNetworkGeneration(networkGeneration: Long) {
        synchronized(lock) {
            if (generation != networkGeneration) {
                generation = networkGeneration
                preferred.clear()
            }
        }
    }

    fun preferredSnapshot(networkGeneration: Long): Set<DirectFrontingRouteKey> = synchronized(lock) {
        ensureGenerationLocked(networkGeneration)
        preferred.toSet()
    }

    private fun ensureGenerationLocked(networkGeneration: Long) {
        if (generation == networkGeneration) return
        generation = networkGeneration
        preferred.clear()
    }
}

/**
 * Implements upstream v1.9 fronting order without coupling it to ProxyServer.
 *
 * Normal direct is attempted first. Only a normal-direct timeout enables a
 * fronted retry with the same Telegram destination IP and HTTP Host but a
 * different TLS SNI (`sprinthost.ru`). Once fronting succeeds, that route key
 * prefers fronting first until a normal direct connection succeeds again or the
 * Android network generation changes. If fronting was already tried first and
 * normal direct then times out, fronting is not retried a second time.
 */
internal class DirectFrontingConnector(
    private val normalConnect: (
        targetHost: String,
        domain: String,
        path: String,
        timeoutMs: Int,
    ) -> WebSocketBinaryStream,
    private val frontedConnect: (
        targetHost: String,
        domain: String,
        path: String,
        timeoutMs: Int,
        sniHost: String,
    ) -> WebSocketBinaryStream,
    private val state: DirectFrontingPreferenceState = DirectFrontingPreferenceState(),
    private val frontingSniHost: String = DEFAULT_FRONTING_SNI,
    private val frontingTimeoutMs: Int = DEFAULT_FRONTING_TIMEOUT_MS,
) {
    fun connect(
        dc: Int,
        isMedia: Boolean,
        targetHost: String,
        domain: String,
        path: String,
        normalTimeoutMs: Int,
        networkGeneration: Long,
    ): DirectFrontingConnectResult {
        val key = DirectFrontingRouteKey(dc, isMedia, targetHost)
        val frontingFirst = state.shouldTryFrontingFirst(key, networkGeneration)
        var firstFrontingError: Throwable? = null

        if (frontingFirst) {
            try {
                val stream = frontedConnect(
                    targetHost,
                    domain,
                    path,
                    frontingTimeoutMs,
                    frontingSniHost,
                )
                state.recordFrontingSuccess(key, networkGeneration)
                return DirectFrontingConnectResult(stream, fronted = true, frontingTriedFirst = true)
            } catch (error: Throwable) {
                firstFrontingError = error
            }
        }

        try {
            val stream = normalConnect(targetHost, domain, path, normalTimeoutMs)
            state.recordNormalDirectSuccess(key, networkGeneration)
            return DirectFrontingConnectResult(stream, fronted = false, frontingTriedFirst = frontingFirst)
        } catch (normalError: Throwable) {
            firstFrontingError?.let(normalError::addSuppressed)
            if (!isTimeout(normalError) || frontingFirst) throw normalError

            try {
                val stream = frontedConnect(
                    targetHost,
                    domain,
                    path,
                    frontingTimeoutMs,
                    frontingSniHost,
                )
                state.recordFrontingSuccess(key, networkGeneration)
                return DirectFrontingConnectResult(stream, fronted = true, frontingTriedFirst = false)
            } catch (frontingError: Throwable) {
                normalError.addSuppressed(frontingError)
                throw normalError
            }
        }
    }

    fun preferredSnapshot(networkGeneration: Long): Set<DirectFrontingRouteKey> =
        state.preferredSnapshot(networkGeneration)

    companion object {
        const val DEFAULT_FRONTING_SNI: String = "sprinthost.ru"
        const val DEFAULT_FRONTING_TIMEOUT_MS: Int = RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS

        internal fun isTimeout(error: Throwable): Boolean {
            var current: Throwable? = error
            while (current != null) {
                if (current is SocketTimeoutException) return true
                val message = current.message.orEmpty()
                if (message.contains("timed out", ignoreCase = true) ||
                    message.contains("timeout", ignoreCase = true)
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }
    }
}
