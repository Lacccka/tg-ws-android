package com.flowseal.tgwsandroid.proxy

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Health state used to gate AUTO Wi-Fi promotion to direct routing. */
enum class DirectHealthState(val configValue: String) {
    UNKNOWN("unknown"),
    CHECKING("checking"),
    HEALTHY("healthy"),
    UNHEALTHY("unhealthy"),
    COOLDOWN("cooldown"),
}

data class DirectRouteHealthSnapshot(
    val state: DirectHealthState,
    val successes: Long,
    val failures: Long,
    val downgrades: Long,
    val promotions: Long,
    val cooldownUntilMs: Long,
    val settlingUntilMs: Long,
    val lastError: String?,
    val lastSuccessTimeMs: Long?,
    val probeThrottleUntilMs: Long,
)

class DirectRouteHealth(
    private val connector: RawWebSocketConnector,
    private val dcRedirects: Map<Int, String>,
    private val wsDomainsProvider: (dc: Int, isMedia: Boolean) -> List<String>,
    private val logger: ProxyLogger,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val probeTimeoutMs: Int = DEFAULT_PROBE_TIMEOUT_MS,
    private val requiredConsecutiveSuccesses: Int = REQUIRED_CONSECUTIVE_SUCCESSES,
) {
    private val state = AtomicReference(DirectHealthState.UNKNOWN)
    private val checking = AtomicBoolean(false)
    private val successes = AtomicLong(0)
    private val failures = AtomicLong(0)
    private val downgrades = AtomicLong(0)
    private val promotions = AtomicLong(0)
    private val cooldownUntilMs = AtomicLong(0)
    private val settlingUntilMs = AtomicLong(0)
    private val lastSuccessTimeMs = AtomicLong(0)
    private val probeThrottleUntilMs = AtomicLong(0)
    @Volatile private var lastError: String? = null
    @Volatile private var consecutiveSuccesses: Int = 0

    fun snapshot(): DirectRouteHealthSnapshot = DirectRouteHealthSnapshot(
        state = currentState(),
        successes = successes.get(),
        failures = failures.get(),
        downgrades = downgrades.get(),
        promotions = promotions.get(),
        cooldownUntilMs = cooldownUntilMs.get(),
        settlingUntilMs = settlingUntilMs.get(),
        lastError = lastError,
        lastSuccessTimeMs = lastSuccessTimeMs.get().takeIf { it > 0L },
        probeThrottleUntilMs = probeThrottleUntilMs.get(),
    )

    fun currentState(): DirectHealthState {
        val cooldown = cooldownUntilMs.get()
        if (cooldown > nowMs()) return DirectHealthState.COOLDOWN
        if (state.get() == DirectHealthState.COOLDOWN) state.compareAndSet(DirectHealthState.COOLDOWN, DirectHealthState.UNKNOWN)
        return state.get()
    }

    fun markSettling(durationMs: Long) {
        settlingUntilMs.set(nowMs() + durationMs)
    }

    fun isSettling(): Boolean = settlingUntilMs.get() > nowMs()

    fun settlingUntilMs(): Long = settlingUntilMs.get()

    fun resetForSafeRoute() {
        consecutiveSuccesses = 0
        probeThrottleUntilMs.set(0)
        state.set(DirectHealthState.UNKNOWN)
    }

    fun isProbeThrottled(): Boolean = probeThrottleUntilMs.get() > nowMs()

    fun probeThrottleUntilMs(): Long = probeThrottleUntilMs.get()

    fun recordPromotion() {
        promotions.incrementAndGet()
        state.set(DirectHealthState.HEALTHY)
    }

    fun startCooldown(durationMs: Long, error: String) {
        downgrades.incrementAndGet()
        consecutiveSuccesses = 0
        lastError = error
        cooldownUntilMs.set(nowMs() + durationMs)
        state.set(DirectHealthState.COOLDOWN)
    }

    fun canPromote(): Boolean = currentState() != DirectHealthState.COOLDOWN

    fun startPromotionProbe(
        shouldContinue: () -> Boolean,
        onPromote: () -> Unit,
        throttleMs: Long = 0L,
    ) {
        if (isProbeThrottled()) {
            logger.log("direct promotion skipped: direct health probe throttled until ${probeThrottleUntilMs.get()}")
            return
        }
        if (!checking.compareAndSet(false, true)) {
            logger.log("direct promotion skipped: direct health probe already checking")
            return
        }
        Thread({
            try {
                state.set(DirectHealthState.CHECKING)
                while (shouldContinue() && canPromote() && consecutiveSuccesses < requiredConsecutiveSuccesses) {
                    if (!probeOnce(throttleMs)) break
                }
                if (shouldContinue() && canPromote() && consecutiveSuccesses >= requiredConsecutiveSuccesses) {
                    onPromote()
                } else {
                    logger.log("direct promotion skipped")
                }
            } finally {
                checking.set(false)
                if (state.get() == DirectHealthState.CHECKING) {
                    state.set(if (consecutiveSuccesses > 0) DirectHealthState.HEALTHY else DirectHealthState.UNHEALTHY)
                }
            }
        }, "DirectRouteHealth-probe").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun probeOnce(throttleMs: Long = 0L): Boolean {
        logger.log("direct health probe started")
        val targets = listOf(2, 4).mapNotNull { dc -> dcRedirects[dc]?.let { dc to it } }
            .ifEmpty { dcRedirects.entries.map { it.key to it.value } }
        var lastFailure: String? = null
        for ((dc, targetHost) in targets) {
            for (domain in wsDomainsProvider(dc, false)) {
                var socket: WebSocketBinaryStream? = null
                try {
                    socket = connector.connect(targetHost, domain, ProxyServer.DEFAULT_WS_PATH, probeTimeoutMs.coerceIn(1_500, 2_500))
                    successes.incrementAndGet()
                    consecutiveSuccesses += 1
                    val successTime = nowMs()
                    lastSuccessTimeMs.set(successTime)
                    if (throttleMs > 0L) probeThrottleUntilMs.set(successTime + throttleMs)
                    lastError = null
                    state.set(DirectHealthState.HEALTHY)
                    logger.log("direct health probe success: DC$dc via $domain")
                    return true
                } catch (error: Throwable) {
                    lastFailure = "DC$dc $domain ${failureDetail(error)}"
                } finally {
                    try {
                        socket?.close()
                    } catch (_: Throwable) {
                        // Best-effort probe cleanup.
                    }
                }
            }
        }
        failures.incrementAndGet()
        consecutiveSuccesses = 0
        lastError = lastFailure ?: "no direct probe targets"
        state.set(DirectHealthState.UNHEALTHY)
        logger.log("direct health probe failed: ${lastError ?: "unknown"}")
        return false
    }

    companion object {
        const val REQUIRED_CONSECUTIVE_SUCCESSES = 2
        const val DEFAULT_PROBE_TIMEOUT_MS = 2_000
        private fun failureDetail(error: Throwable): String =
            "${error::class.java.simpleName}: ${error.message ?: "no message"}"
    }
}
