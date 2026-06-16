package com.flowseal.tgwsandroid.telemetry

import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import org.json.JSONObject
import java.util.ArrayDeque

class TelemetryAggregator(
    private val telemetryEnabled: () -> Boolean,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val forceFlushMinIntervalMs: Long = DEFAULT_FORCE_FLUSH_MIN_INTERVAL_MS,
    private val maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
) {
    private val counters = linkedMapOf<String, Long>()
    private val flags = linkedMapOf<String, Boolean>()
    private val queue = ArrayDeque<JSONObject>()
    private var windowStartMs: Long = nowMs()
    private var lastNormalFlushAtMs: Long? = null
    private var lastForceFlushAtMs: Long? = null
    private var droppedEventsCount: Long = 0L
    private var droppedSnapshotsCount: Long = 0L
    private var lastStats: ProxyServerStats? = null
    private var routeMode: String? = null
    private var effectiveRoute: String? = null
    private var networkType: String? = null
    private var directHealthState: String? = null
    private var lastRouteChangeReason: String? = null
    private var activeSessions: Int? = null
    private var lastUnsupportedDc: Long? = null

    @Synchronized fun recordCounter(name: String, delta: Long = 1L) {
        if (!telemetryEnabled()) return
        if (delta <= 0L) return
        counters[name] = (counters[name] ?: 0L) + delta
    }

    @Synchronized fun recordFlag(name: String, value: Boolean = true) {
        if (!telemetryEnabled()) return
        flags[name] = (flags[name] == true) || value
    }

    @Synchronized fun recordStats(
        stats: ProxyServerStats,
        foregroundServiceActive: Boolean,
        wakeLockActive: Boolean,
        batteryRestrictionDetected: Boolean = false,
        previousRunEndedUnexpectedly: Boolean = false,
        unexpectedStopDetected: Boolean = false,
    ) {
        if (!telemetryEnabled()) return
        routeMode = stats.routeMode.uppercase()
        effectiveRoute = stats.effectiveRouteMode
        networkType = stats.networkAtLastRouteEvaluation.ifBlank { stats.networkAtLastRouteChange }
        directHealthState = stats.directHealthState
        lastRouteChangeReason = stats.lastRouteChangeReason
        activeSessions = stats.connectionsActive
        recordFlag("foreground_service_active", foregroundServiceActive)
        recordFlag("wake_lock_active_at_last_marker", wakeLockActive)
        if (batteryRestrictionDetected) recordFlag("battery_restriction_detected")
        if (previousRunEndedUnexpectedly) recordFlag("previous_run_ended_unexpectedly")
        if (unexpectedStopDetected || previousRunEndedUnexpectedly) recordFlag("unexpected_stop_detected")

        val previous = lastStats
        if (previous != null) {
            recordDelta("direct_timeout", previous.directTimeouts, stats.directTimeouts)
            recordDelta("pool_miss", previous.poolMisses, stats.poolMisses)
            recordDelta("pool_stale", previous.poolStale, stats.poolStale)
            recordDelta("pool_refill_error", previous.poolRefillErrors, stats.poolRefillErrors)
            recordDelta("cf_429", previous.cf429Count, stats.cf429Count)
            recordDelta("cf_queue_failure", previous.cfQueueControlledFailures, stats.cfQueueControlledFailures)
            recordDelta("connection_reset", previous.sessionEndDiagnostics.connectionReset.count, stats.sessionEndDiagnostics.connectionReset.count)
            recordDelta("client_closed", previous.sessionClientClosed, stats.sessionClientClosed)
            recordDelta("very_short_session", previous.sessionRemoteEofShort, stats.sessionRemoteEofShort)
            recordDelta("route_changed", previous.routeChangesImmediate, stats.routeChangesImmediate)
            recordDelta("no_route", previous.networkNoneEvents, stats.networkNoneEvents)
            recordDelta("handshake_accepted", previous.connectionsTotal, stats.connectionsTotal)
            recordDelta("session_unexpected_error", previous.sessionUnexpectedErrors, stats.sessionUnexpectedErrors)
            recordDelta("unsupported_dc", previous.unsupportedDc, stats.unsupportedDc)
            recordDelta("mobile_network_generation_changes", previous.mobileNetworkGenerationChanges, stats.mobileNetworkGenerationChanges)
            recordDelta("mobile_to_mobile_recovery_attempts", previous.mobileToMobileRecoveryAttempts, stats.mobileToMobileRecoveryAttempts)
            recordDelta("mobile_to_mobile_recovery_successes", previous.mobileToMobileRecoverySuccesses, stats.mobileToMobileRecoverySuccesses)
            recordDelta("mobile_to_mobile_recovery_failures", previous.mobileToMobileRecoveryFailures, stats.mobileToMobileRecoveryFailures)
            recordDelta("mobile_recovery_cf_pressure_reset_count", previous.mobileRecoveryCfPressureResetCount, stats.mobileRecoveryCfPressureResetCount)
            recordDelta("mobile_recovery_direct_cooldown_reset_count", previous.mobileRecoveryDirectCooldownResetCount, stats.mobileRecoveryDirectCooldownResetCount)
        }
        deriveInferredDiagnostics(stats)
        val previousUnsupported = lastUnsupportedDc
        if (previous == null && previousUnsupported != null) recordCounter("unsupported_dc", (stats.unsupportedDc - previousUnsupported).coerceAtLeast(0L))
        lastUnsupportedDc = stats.unsupportedDc
        lastStats = stats
        maybeFlush()
    }

    @Synchronized fun maybeFlush(): JSONObject? {
        if (!telemetryEnabled()) return null
        val now = nowMs()
        val lastFlush = lastNormalFlushAtMs
        if (lastFlush != null && now - lastFlush < windowMs) return null
        return flushLocked(now)?.also { lastNormalFlushAtMs = now }
    }

    @Synchronized fun forceFlushCritical(): JSONObject? {
        if (!telemetryEnabled()) return null
        val now = nowMs()
        val lastFlush = lastForceFlushAtMs
        if (lastFlush != null && now - lastFlush < forceFlushMinIntervalMs) return null
        return flushLocked(now)?.also { lastForceFlushAtMs = now }
    }

    @Synchronized fun flushOnStop(): JSONObject? = if (telemetryEnabled()) flushLocked(nowMs()) else null

    @Synchronized fun pollSnapshot(): JSONObject? = queue.pollFirst()
    @Synchronized fun queueSize(): Int = queue.size
    @Synchronized fun currentDroppedSnapshotsCount(): Long = droppedSnapshotsCount

    private fun recordDelta(name: String, previous: Long, current: Long) = recordCounter(name, (current - previous).coerceAtLeast(0L))

    private fun deriveInferredDiagnostics(stats: ProxyServerStats) {
        val cfQueueDegraded = (counters["cf_queue_failure"] ?: 0L) > 0L ||
            (counters["cf_429"] ?: 0L) > 0L ||
            stats.clientExperience.recentCfQueueControlledFailures > 0L ||
            stats.clientExperience.recentCfConnectQueueTimeouts > 0L ||
            stats.cfPressureReasonByDc.values.any { it.contains("degraded", ignoreCase = true) || it.contains("queue", ignoreCase = true) }
        if (cfQueueDegraded) recordFlag("cf_queue_degraded")

        val handshakeAccepted = counters["handshake_accepted"] ?: 0L
        val clientClosed = counters["client_closed"] ?: 0L
        val veryShortSession = counters["very_short_session"] ?: 0L
        val connectionReset = counters["connection_reset"] ?: 0L
        val routeChanged = counters["route_changed"] ?: 0L
        val heuristicReconnectBurst = handshakeAccepted >= 10L &&
            (clientClosed >= 5L || veryShortSession >= 3L || connectionReset >= 3L || routeChanged >= 2L)
        if (stats.clientExperience.likelyReconnectBurst || heuristicReconnectBurst) recordFlag("reconnect_burst_detected")

        val noFullProxyFailure = foregroundServiceActive() && stats.connectionsActive <= 1 && stats.sessionUnexpectedErrors == 0L
        val conservativeTelegramDisabled = noFullProxyFailure && handshakeAccepted > 0L &&
            (veryShortSession >= 3L || clientClosed >= 5L) && stats.connectionsActive <= 1
        if (stats.clientExperience.likelyTelegramDisabledProxy || conservativeTelegramDisabled) {
            recordFlag("telegram_likely_disabled_proxy")
        }
    }

    private fun foregroundServiceActive(): Boolean = flags["foreground_service_active"] == true

    private fun flushLocked(now: Long): JSONObject? {
        if (counters.isEmpty() && flags.isEmpty()) return null
        val payload = JSONObject().apply {
            put("window_start_ms", windowStartMs)
            put("window_end_ms", now)
            put("window_ms", windowMs)
            routeMode?.let { put("route_mode", it) }
            effectiveRoute?.let { put("effective_route", it) }
            networkType?.let { put("network_type", it) }
            directHealthState?.let { put("direct_health_state", it) }
            lastRouteChangeReason?.let { put("last_route_change_reason", it) }
            activeSessions?.let { put("active_sessions", it) }
            put("counters", JSONObject(counters.toMap()))
            put("flags", JSONObject(flags.toMap()))
            put("dropped_events_count", droppedEventsCount)
            put("dropped_snapshots_count", droppedSnapshotsCount)
        }
        val event = TelemetryRedactor.redact(JSONObject().apply {
            put("name", "diagnostics_snapshot")
            put("time_ms", now)
            put("payload", payload)
        })
        if (queue.size >= maxQueueSize) droppedSnapshotsCount++ else queue.addLast(event)
        counters.clear(); flags.clear(); droppedEventsCount = 0L; windowStartMs = now
        return event
    }

    companion object {
        // TODO: refine unexpected_stop_detected when Android lifecycle exposes a current-session non-user stop signal beyond previous-run marker.
        const val DEFAULT_WINDOW_MS = 15 * 60 * 1000L
        const val DEFAULT_FORCE_FLUSH_MIN_INTERVAL_MS = 60 * 1000L
        const val DEFAULT_MAX_QUEUE_SIZE = 100
    }
}
