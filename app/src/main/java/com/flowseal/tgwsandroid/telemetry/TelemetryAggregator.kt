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

    @Synchronized fun recordCounter(name: String, delta: Long = 1L) {
        if (!telemetryEnabled()) return
        if (delta <= 0L) return
        counters[name] = (counters[name] ?: 0L) + delta
    }

    @Synchronized fun recordFlag(name: String, value: Boolean = true) {
        if (!telemetryEnabled()) return
        flags[name] = (flags[name] == true) || value
    }

    @Synchronized fun recordStats(stats: ProxyServerStats, foregroundServiceActive: Boolean, wakeLockActive: Boolean) {
        if (!telemetryEnabled()) return
        routeMode = stats.routeMode.uppercase()
        effectiveRoute = stats.effectiveRouteMode
        networkType = stats.networkAtLastRouteEvaluation.ifBlank { stats.networkAtLastRouteChange }
        directHealthState = stats.directHealthState
        lastRouteChangeReason = stats.lastRouteChangeReason
        activeSessions = stats.connectionsActive
        recordFlag("foreground_service_active", foregroundServiceActive)
        recordFlag("wake_lock_active_at_last_marker", wakeLockActive)

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
        }
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
        // TODO: wire flags that do not yet have safe existing diagnostics sources: reconnect_burst_detected,
        // telegram_likely_disabled_proxy, cf_queue_degraded, battery_restriction_detected,
        // unexpected_stop_detected, previous_run_ended_unexpectedly.
        // TODO: wire unsupported_dc counter when a safe existing source is available.
        const val DEFAULT_WINDOW_MS = 15 * 60 * 1000L
        const val DEFAULT_FORCE_FLUSH_MIN_INTERVAL_MS = 60 * 1000L
        const val DEFAULT_MAX_QUEUE_SIZE = 100
    }
}
