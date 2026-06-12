package com.flowseal.tgwsandroid.proxy

import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/** Persisted and effective routing modes for Telegram WebSocket upstream selection. */
enum class NetworkRouteMode(
    val configValue: String,
    val displayName: String,
) {
    AUTO("auto", "Auto"),
    DIRECT_FIRST("direct_first", "Direct first"),
    CF_FIRST("cf_first", "CF first"),
    CF_ONLY("cf_only", "CF only"),
    ;

    companion object {
        fun fromConfigValue(value: String): NetworkRouteMode =
            entries.firstOrNull { it.configValue.equals(value, ignoreCase = true) } ?: AUTO
    }
}

data class RouteSnapshot(
    val configuredRouteMode: NetworkRouteMode,
    val effectiveRouteMode: NetworkRouteMode,
    val previousEffectiveRouteMode: NetworkRouteMode? = null,
    val lastRouteChangeReason: String = "initial",
    val lastRouteChangeSource: String = "initial",
    val lastRouteChangeTimeMs: Long? = null,
    val networkAtLastRouteChange: String = "unknown",
    val lastRouteEvaluationReason: String = "initial",
    val lastRouteEvaluationSource: String = "initial",
    val lastRouteEvaluationTimeMs: Long? = null,
    val networkAtLastRouteEvaluation: String = "unknown",
    val routeEvaluations: Long = 0,
    val routeNoopEvaluations: Long = 0,
)

class RouteState(
    configuredRouteMode: NetworkRouteMode,
    initialNetworkStatus: String = "unknown",
    private val clock: Clock = Clock.systemUTC(),
) {
    private val state = AtomicReference(
        RouteSnapshot(
            configuredRouteMode = configuredRouteMode,
            effectiveRouteMode = RouteStrategy.resolve(configuredRouteMode, initialNetworkStatus),
            previousEffectiveRouteMode = null,
            lastRouteChangeReason = "initial network=$initialNetworkStatus",
            lastRouteChangeSource = "initial",
            lastRouteChangeTimeMs = clock.millis(),
            networkAtLastRouteChange = initialNetworkStatus.ifBlank { "unknown" },
            lastRouteEvaluationReason = "initial network=$initialNetworkStatus",
            lastRouteEvaluationSource = "initial",
            lastRouteEvaluationTimeMs = clock.millis(),
            networkAtLastRouteEvaluation = initialNetworkStatus.ifBlank { "unknown" },
        ),
    )

    val configuredRouteMode: NetworkRouteMode get() = state.get().configuredRouteMode
    val effectiveRouteMode: NetworkRouteMode get() = state.get().effectiveRouteMode

    fun snapshot(): RouteSnapshot = state.get()

    fun desiredEffectiveRouteMode(networkStatus: String): NetworkRouteMode =
        RouteStrategy.resolve(configuredRouteMode, networkStatus)

    fun applyNetwork(
        networkStatus: String,
        reason: String = "network=$networkStatus",
    ): RouteChangeResult {
        val desired = desiredEffectiveRouteMode(networkStatus)
        return applyEffectiveRouteMode(desired, reason, networkStatus)
    }

    fun applyEffectiveRouteMode(
        desiredEffectiveRouteMode: NetworkRouteMode,
        reason: String,
        networkStatus: String,
        source: String = "debounce",
    ): RouteChangeResult {
        while (true) {
            val current = state.get()
            val now = clock.millis()
            val normalizedNetwork = networkStatus.ifBlank { "unknown" }
            if (current.effectiveRouteMode == desiredEffectiveRouteMode) {
                val updated = current.copy(
                    lastRouteEvaluationReason = reason,
                    lastRouteEvaluationSource = source,
                    lastRouteEvaluationTimeMs = now,
                    networkAtLastRouteEvaluation = normalizedNetwork,
                    routeEvaluations = current.routeEvaluations + 1,
                    routeNoopEvaluations = current.routeNoopEvaluations + 1,
                )
                if (state.compareAndSet(current, updated)) {
                    return RouteChangeResult(
                        changed = false,
                        previous = current.effectiveRouteMode,
                        current = current.effectiveRouteMode,
                        snapshot = updated,
                        reason = reason,
                        networkStatus = networkStatus,
                        source = source,
                    )
                }
                continue
            }
            val updated = current.copy(
                effectiveRouteMode = desiredEffectiveRouteMode,
                previousEffectiveRouteMode = current.effectiveRouteMode,
                lastRouteChangeReason = reason,
                lastRouteChangeSource = source,
                lastRouteChangeTimeMs = now,
                networkAtLastRouteChange = normalizedNetwork,
                lastRouteEvaluationReason = reason,
                lastRouteEvaluationSource = source,
                lastRouteEvaluationTimeMs = now,
                networkAtLastRouteEvaluation = normalizedNetwork,
                routeEvaluations = current.routeEvaluations + 1,
            )
            if (state.compareAndSet(current, updated)) {
                return RouteChangeResult(
                    changed = true,
                    previous = current.effectiveRouteMode,
                    current = desiredEffectiveRouteMode,
                    snapshot = updated,
                    reason = reason,
                    networkStatus = networkStatus,
                    source = source,
                )
            }
        }
    }
}

data class RouteChangeResult(
    val changed: Boolean,
    val previous: NetworkRouteMode,
    val current: NetworkRouteMode,
    val snapshot: RouteSnapshot,
    val reason: String,
    val networkStatus: String,
    val source: String = "debounce",
)

object RouteStrategy {
    fun resolve(
        configuredMode: NetworkRouteMode,
        networkStatus: String,
    ): NetworkRouteMode = when (configuredMode) {
        NetworkRouteMode.AUTO -> when {
            networkStatus.equals("mobile", ignoreCase = true) -> NetworkRouteMode.CF_FIRST
            networkStatus.equals("cellular", ignoreCase = true) -> NetworkRouteMode.CF_FIRST
            networkStatus.equals("Wi-Fi", ignoreCase = true) -> NetworkRouteMode.CF_FIRST
            networkStatus.equals("wifi", ignoreCase = true) -> NetworkRouteMode.CF_FIRST
            networkStatus.equals("none", ignoreCase = true) -> NetworkRouteMode.CF_FIRST
            else -> NetworkRouteMode.CF_FIRST
        }
        else -> configuredMode
    }
}
