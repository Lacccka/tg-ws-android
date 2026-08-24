package com.flowseal.tgwsandroid.service

import java.util.ArrayDeque

data class TorFallbackWarmupDecision(
    val triggered: Boolean,
    val newlyTriggered: Boolean,
    val recentFailureCount: Int,
    val reason: String? = null,
    val triggeredAtMs: Long? = null,
)

data class TorFallbackWarmupPolicySnapshot(
    val networkStatus: String = "unknown",
    val recentFailureCount: Int = 0,
    val triggered: Boolean = false,
    val reason: String? = null,
    val triggeredAtMs: Long? = null,
)

/**
 * Starts expensive Tor/Snowflake warmup only after ordinary mobile routing has
 * been exhausted repeatedly in a short window. A single transient CF/direct
 * failure must not pay the Tor battery/memory cost.
 */
class TorFallbackWarmupPolicy(
    private val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    private val failureWindowMs: Long = DEFAULT_FAILURE_WINDOW_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val failureTimesMs = ArrayDeque<Long>()
    private var networkStatus: String = "unknown"
    private var triggered: Boolean = false
    private var triggerReason: String? = null
    private var triggeredAtMs: Long? = null

    init {
        require(failureThreshold >= 1) { "failureThreshold must be >= 1" }
        require(failureWindowMs > 0L) { "failureWindowMs must be > 0" }
    }

    @Synchronized
    fun onNetworkChanged(status: String) {
        val normalized = status.ifBlank { "unknown" }
        if (normalized.equals(networkStatus, ignoreCase = true)) return
        networkStatus = normalized
        if (!isMobile(normalized)) resetWindowLocked()
    }

    @Synchronized
    fun recordOrdinaryRouteExhausted(reason: String): TorFallbackWarmupDecision {
        val now = clockMs()
        if (!isMobile(networkStatus)) {
            return TorFallbackWarmupDecision(
                triggered = triggered,
                newlyTriggered = false,
                recentFailureCount = failureTimesMs.size,
                reason = triggerReason,
                triggeredAtMs = triggeredAtMs,
            )
        }

        pruneLocked(now)
        failureTimesMs.addLast(now)
        if (!triggered && failureTimesMs.size >= failureThreshold) {
            triggered = true
            triggerReason = reason.ifBlank { "ordinary routes repeatedly exhausted" }
            triggeredAtMs = now
            return TorFallbackWarmupDecision(
                triggered = true,
                newlyTriggered = true,
                recentFailureCount = failureTimesMs.size,
                reason = triggerReason,
                triggeredAtMs = triggeredAtMs,
            )
        }

        return TorFallbackWarmupDecision(
            triggered = triggered,
            newlyTriggered = false,
            recentFailureCount = failureTimesMs.size,
            reason = triggerReason,
            triggeredAtMs = triggeredAtMs,
        )
    }

    @Synchronized
    fun snapshot(): TorFallbackWarmupPolicySnapshot {
        val now = clockMs()
        pruneLocked(now)
        return TorFallbackWarmupPolicySnapshot(
            networkStatus = networkStatus,
            recentFailureCount = failureTimesMs.size,
            triggered = triggered,
            reason = triggerReason,
            triggeredAtMs = triggeredAtMs,
        )
    }

    @Synchronized
    fun reset() {
        resetWindowLocked()
    }

    private fun pruneLocked(now: Long) {
        val cutoff = now - failureWindowMs
        while (failureTimesMs.isNotEmpty() && failureTimesMs.first() < cutoff) {
            failureTimesMs.removeFirst()
        }
    }

    private fun resetWindowLocked() {
        failureTimesMs.clear()
        triggered = false
        triggerReason = null
        triggeredAtMs = null
    }

    private fun isMobile(value: String): Boolean =
        value.equals("mobile", ignoreCase = true) || value.equals("cellular", ignoreCase = true)

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 3
        const val DEFAULT_FAILURE_WINDOW_MS = 10_000L
    }
}
