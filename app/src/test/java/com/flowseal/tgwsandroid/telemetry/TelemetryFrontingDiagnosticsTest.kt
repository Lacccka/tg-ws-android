package com.flowseal.tgwsandroid.telemetry

import com.flowseal.tgwsandroid.proxy.DirectPoolDiagnosticsSnapshot
import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryFrontingDiagnosticsTest {
    @Test
    fun snapshotCarriesFrontingCountersAndSafePoolDiagnostics() {
        var now = 1_000L
        val aggregator = TelemetryAggregator(
            telemetryEnabled = { true },
            nowMs = { now },
            windowMs = 15 * 60 * 1000L,
            forceFlushMinIntervalMs = 1L,
        )

        aggregator.recordStats(ProxyServerStats(), foregroundServiceActive = true, wakeLockActive = false)
        aggregator.pollSnapshot()

        now += 10L
        val stats = ProxyServerStats().apply {
            frontingAttempts = 4L
            frontingSuccesses = 3L
            frontingFailures = 1L
            frontingFirstAttempts = 2L
            frontingFallbackAttempts = 2L
            frontingPreferredKeys = listOf("dc2@149.154.167.220")
            lastFrontingError = "must-not-be-uploaded"
            lastFrontingTimeMs = now - 3L
            directPoolDiagnostics = DirectPoolDiagnosticsSnapshot(
                refillFailureWavesByKey = mapOf("dc2" to 2),
                refillBackoffRemainingMsByKey = mapOf("dc2" to 60_000L),
                refillBackoffSuppressedByKey = mapOf("dc2|maintenance" to 3L),
                closedIdlePrunedByKey = mapOf("dc2" to 5L),
            )
        }
        aggregator.recordStats(stats, foregroundServiceActive = true, wakeLockActive = false)

        val payload = aggregator.forceFlushCritical()!!.getJSONObject("payload")
        val counters = payload.getJSONObject("counters")
        assertEquals(4L, counters.getLong("fronting_attempt"))
        assertEquals(3L, counters.getLong("fronting_success"))
        assertEquals(1L, counters.getLong("fronting_failure"))
        assertEquals(2L, counters.getLong("fronting_first_attempt"))
        assertEquals(2L, counters.getLong("fronting_fallback_attempt"))
        assertEquals(3L, counters.getLong("pool_refill_backoff_suppressed"))
        assertEquals(5L, counters.getLong("pool_closed_idle_pruned"))

        val routeQuality = payload.getJSONObject("route_quality")
        assertEquals(1, routeQuality.getInt("fronting_preferred_key_count"))
        assertEquals(3L, routeQuality.getLong("last_fronting_event_age_ms"))
        assertFalse(routeQuality.toString().contains("149.154.167.220"))
        assertFalse(routeQuality.toString().contains("must-not-be-uploaded"))

        val poolReadiness = payload.getJSONObject("pool_readiness")
        assertEquals(2, poolReadiness.getJSONObject("refill_failure_waves_by_key").getInt("dc2"))
        assertEquals(60_000L, poolReadiness.getJSONObject("refill_backoff_remaining_ms_by_key").getLong("dc2"))
        assertEquals(3L, poolReadiness.getJSONObject("refill_backoff_suppressed_by_key").getLong("dc2|maintenance"))
        assertEquals(5L, poolReadiness.getJSONObject("closed_idle_pruned_by_key").getLong("dc2"))
        assertTrue(payload.toString().contains("fronting_attempt"))
    }
}
