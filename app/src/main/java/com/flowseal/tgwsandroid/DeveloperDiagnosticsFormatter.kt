package com.flowseal.tgwsandroid

import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import java.util.Locale

object DeveloperDiagnosticsFormatter {
    fun directPoolDetails(stats: ProxyServerStats?): String {
        stats ?: return "direct/pool: unknown"
        return buildString {
            append("direct health=${stats.directHealthState}")
            append(", attempts=${stats.directAttempts}")
            append(", successes=${stats.directHealthSuccesses}")
            append(", failures=${stats.directHealthFailures}")
            append(", timeouts=${stats.directTimeouts}")
            append(", cooldownUntil=${stats.directCooldownUntil}")
            append(", lastRoute=${stats.lastRouteUsed ?: "none"}")
            append('\n')
            append("fronting attempts=${stats.frontingAttempts}")
            append(", successes=${stats.frontingSuccesses}")
            append(", failures=${stats.frontingFailures}")
            append(", first=${stats.frontingFirstAttempts}")
            append(", fallback=${stats.frontingFallbackAttempts}")
            append(", preferred=${stats.frontingPreferredKeys}")
            append(", lastError=${stats.lastFrontingError ?: "none"}")
            append('\n')
            append("pool hits=${stats.poolHits}")
            append(", misses=${stats.poolMisses}")
            append(", stale=${stats.poolStale}")
            append(", refillErrors=${stats.poolRefillErrors}")
            append(", refillsCancelled=${stats.poolRefillsCancelled}")
            append(", discardedAfterRouteChange=${stats.poolResultsDiscardedAfterRouteChange}")
            append('\n')
            append("pool readyByKey=${stats.directPoolDiagnostics.readyByKey}")
            append(", inFlightRefillsByKey=${stats.directPoolDiagnostics.inFlightRefillsByKey}")
            append(", failureWavesByKey=${stats.directPoolDiagnostics.refillFailureWavesByKey}")
            append(", backoffRemainingMsByKey=${stats.directPoolDiagnostics.refillBackoffRemainingMsByKey}")
            append(", backoffSuppressedByKey=${stats.directPoolDiagnostics.refillBackoffSuppressedByKey}")
            append(", closedIdlePrunedByKey=${stats.directPoolDiagnostics.closedIdlePrunedByKey}")
            append(", lastRefillErrorByKey=${stats.directPoolDiagnostics.lastRefillErrorByKey}")
        }
    }

    fun handshakeDetails(stats: ProxyServerStats?): String {
        stats ?: return "handshake: unknown"
        return buildString {
            append("state=${stats.handshakeDiagnosticState}")
            append(", reason=${stats.handshakeDiagnosticReason}")
            append(", recommendation=${stats.badHandshakeRecommendation}")
            append('\n')
            append("recentInvalid=${stats.recentInvalidHandshakeCount}")
            append(", recentAccepted=${stats.recentAcceptedHandshakeCount}")
            append(", badHandshakeRatio=${String.format(Locale.US, "%.3f", stats.badHandshakeRatio)}")
            append(", storm=${stats.badHandshakeStormRecent}")
            append('\n')
            append("secondsSinceLastAcceptedHandshake=${stats.secondsSinceLastAcceptedHandshake ?: "unknown"}")
            append(", secondsSinceLastSuccessfulRoute=${stats.secondsSinceLastSuccessfulRoute ?: "unknown"}")
        }
    }
}
