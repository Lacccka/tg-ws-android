package com.flowseal.tgwsandroid.telemetry

import com.flowseal.tgwsandroid.BuildConfig
import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import org.json.JSONArray
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
    private val breadcrumbs = ArrayDeque<JSONObject>()
    private var windowStartMs: Long = nowMs()
    private var lastNormalFlushAtMs: Long? = null
    private var lastForceFlushAtMs: Long? = null
    private var droppedEventsCount: Long = 0L
    private var droppedSnapshotsCount: Long = 0L
    private var lastStats: ProxyServerStats? = null
    private var routeMode: String? = null
    private var effectiveRoute: String? = null
    private var previousEffectiveRoute: String? = null
    private var networkType: String? = null
    private var previousNetworkType: String? = null
    private var directHealthState: String? = null
    private var lastRouteChangeReason: String? = null
    private var activeSessions: Int? = null
    private var lastUnsupportedDc: Long? = null
    private var lastNetworkTransitionType: String? = null
    private var lastNetworkGapMs: Long? = null
    private var previousNetworkGeneration: Long? = null
    private var telemetryLastSendResult: String = "unknown"
    private var telemetryLastHttpStatusClass: String = "unknown"
    private var telemetryLastErrorClass: String = "unknown"
    private var telemetryFailedSendCount: Long = 0L
    private var telemetrySuccessSendCount: Long = 0L

    @Synchronized fun recordCounter(name: String, delta: Long = 1L) {
        if (!telemetryEnabled()) return
        if (delta <= 0L) return
        counters[name] = (counters[name] ?: 0L) + delta
    }

    @Synchronized fun recordFlag(name: String, value: Boolean = true) {
        if (!telemetryEnabled()) return
        flags[name] = (flags[name] == true) || value
    }

    @Synchronized fun recordTelemetryDelivery(success: Boolean) {
        if (!telemetryEnabled()) return
        if (success) {
            telemetryLastSendResult = "success"
            telemetryLastHttpStatusClass = "2xx"
            telemetryLastErrorClass = "unknown"
            telemetrySuccessSendCount++
            addBreadcrumb("telemetry_send_success")
        } else {
            telemetryLastSendResult = "network_error"
            telemetryLastHttpStatusClass = "unknown"
            telemetryLastErrorClass = "unknown"
            telemetryFailedSendCount++
            addBreadcrumb("telemetry_send_failed")
        }
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
        val previous = lastStats
        val newNetworkType = stats.networkAtLastRouteEvaluation.ifBlank { stats.networkAtLastRouteChange }
        val oldNetworkType = networkType
        val oldEffectiveRoute = effectiveRoute
        routeMode = stats.routeMode.uppercase()
        effectiveRoute = stats.effectiveRouteMode
        networkType = newNetworkType
        directHealthState = stats.directHealthState
        lastRouteChangeReason = stats.lastRouteChangeReason
        activeSessions = stats.connectionsActive
        previousNetworkGeneration = previous?.networkGeneration
        if (oldNetworkType != null && oldNetworkType != newNetworkType) {
            previousNetworkType = oldNetworkType
            lastNetworkTransitionType = "${oldNetworkType}_to_${newNetworkType}"
            lastNetworkGapMs = if (stats.lastNetworkLostAtMs > 0L && stats.lastNetworkAvailableAtMs > 0L) {
                (stats.lastNetworkAvailableAtMs - stats.lastNetworkLostAtMs).coerceAtLeast(0L)
            } else null
            addBreadcrumb("network_changed", networkBefore = oldNetworkType, networkAfter = newNetworkType, generation = stats.networkGeneration)
        }
        if (oldEffectiveRoute != null && oldEffectiveRoute != stats.effectiveRouteMode) {
            previousEffectiveRoute = oldEffectiveRoute
            addBreadcrumb("route_changed", routeBefore = oldEffectiveRoute, routeAfter = stats.effectiveRouteMode, generation = stats.networkGeneration, reason = safeReason(stats.lastRouteChangeReason))
        }
        recordFlag("foreground_service_active", foregroundServiceActive)
        recordFlag("wake_lock_active_at_last_marker", wakeLockActive)
        if (batteryRestrictionDetected) recordFlag("battery_restriction_detected")
        if (previousRunEndedUnexpectedly) recordFlag("previous_run_ended_unexpectedly")
        if (unexpectedStopDetected || previousRunEndedUnexpectedly) recordFlag("unexpected_stop_detected")

        if (previous != null) {
            recordDelta("direct_timeout", previous.directTimeouts, stats.directTimeouts)
            recordDelta("pool_miss", previous.poolMisses, stats.poolMisses)
            recordDelta("pool_stale", previous.poolStale, stats.poolStale)
            recordDelta("pool_refill_error", previous.poolRefillErrors, stats.poolRefillErrors)
            recordDelta("cf_429", previous.cf429Count, stats.cf429Count)
            recordDelta("cf_unknown_host", previous.cfUnknownHostCount, stats.cfUnknownHostCount)
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
            recordDelta("none_to_mobile_recovery_attempts", previous.noneToMobileRecoveryAttempts, stats.noneToMobileRecoveryAttempts)
            recordDelta("none_to_mobile_recovery_successes", previous.noneToMobileRecoverySuccesses, stats.noneToMobileRecoverySuccesses)
            recordDelta("mobile_to_mobile_recovery_attempts", previous.mobileToMobileRecoveryAttempts, stats.mobileToMobileRecoveryAttempts)
            recordDelta("mobile_to_mobile_recovery_successes", previous.mobileToMobileRecoverySuccesses, stats.mobileToMobileRecoverySuccesses)
            recordDelta("mobile_to_mobile_recovery_failures", previous.mobileToMobileRecoveryFailures, stats.mobileToMobileRecoveryFailures)
            recordDelta("mobile_direct_rescue_attempts", previous.mobileDirectRescueAttempts, stats.mobileDirectRescueAttempts)
            recordDelta("mobile_direct_rescue_failures", previous.mobileDirectRescueFailures, stats.mobileDirectRescueFailures)
            recordDelta("route_attempts_failed_due_to_network_none", previous.networkSettlingControlledFailures, stats.networkSettlingControlledFailures)
            recordDelta("route_attempts_resumed_after_mobile_available", previous.routeWaitResumedAfterMobileAvailable, stats.routeWaitResumedAfterMobileAvailable)
            recordDelta("mobile_recovery_cf_pressure_reset_count", previous.mobileRecoveryCfPressureResetCount, stats.mobileRecoveryCfPressureResetCount)
            recordDelta("mobile_recovery_direct_cooldown_reset_count", previous.mobileRecoveryDirectCooldownResetCount, stats.mobileRecoveryDirectCooldownResetCount)
        }
        if ((counters["mobile_direct_rescue_attempts"] ?: 0L) > 0L) addBreadcrumb("mobile_direct_rescue_started", generation = stats.networkGeneration, reason = stats.lastMobileRecoveryReason?.let(::safeReason))
        if ((counters["mobile_direct_rescue_failures"] ?: 0L) > 0L) addBreadcrumb("mobile_direct_rescue_failed", generation = stats.networkGeneration, reason = stats.lastMobileRecoveryReason?.let(::safeReason))
        if ((counters["no_route"] ?: 0L) > 0L) addBreadcrumb("no_route", generation = stats.networkGeneration, network = newNetworkType)
        if ((counters["unsupported_dc"] ?: 0L) > 0L) addBreadcrumb("unsupported_dc")
        if (stats.cfPressureLevelByDc.isNotEmpty()) addBreadcrumb("cf_pressure_changed", reason = stats.cfPressureReasonByDc.values.firstOrNull()?.let(::safeReason))
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
        val stats = lastStats
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
            put("diagnostic_episodes", buildDiagnosticEpisodes(stats, now))
            put("safe_breadcrumbs", JSONArray(breadcrumbs.toList()))
            put("client_quality", buildClientQuality(stats))
            put("route_quality", buildRouteQuality(stats, now))
            put("pool_readiness", buildPoolReadiness(stats))
            put("cf_quality", buildCfQuality(stats))
            put("network_transition", buildNetworkTransition(stats))
            put("telemetry_delivery", buildTelemetryDelivery())
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

    private fun buildDiagnosticEpisodes(stats: ProxyServerStats?, now: Long): JSONArray = JSONArray().apply {
        fun add(type: String, severity: String, confidence: String, result: String, reason: String, evidenceKeys: List<String>, durationMs: Long? = null, ttfSuccessMs: Long? = null, affectedDcs: Collection<Int> = emptyList()) {
            put(JSONObject().apply {
                put("episode_type", type); put("severity", severity); put("confidence", confidence); put("result", result)
                put("affected_dcs", JSONArray(affectedDcs.toList())); put("primary_reason", reason); put("secondary_reasons", JSONArray())
                durationMs?.let { put("duration_ms", it) }; ttfSuccessMs?.let { put("time_to_first_success_ms", it) }
                put("evidence", JSONObject().apply { evidenceKeys.forEach { key -> counters[key]?.let { put(key, it) } } })
            })
        }
        if ((counters["none_to_mobile_recovery_attempts"] ?: 0L) > 0L) add("none_to_mobile_recovery", "info", "medium", if ((counters["none_to_mobile_recovery_successes"] ?: 0L) > 0L) "recovered" else "ongoing", "network_recovered_to_mobile", listOf("none_to_mobile_recovery_attempts", "none_to_mobile_recovery_successes"), ttfSuccessMs = stats?.mobileRecoveryFirstSuccessLatencyMs)
        if ((counters["mobile_to_mobile_recovery_attempts"] ?: 0L) > 0L) add("mobile_to_mobile_recovery", "warning", "medium", if ((counters["mobile_to_mobile_recovery_successes"] ?: 0L) > 0L) "recovered" else "failed", "mobile_profile_changed", listOf("mobile_to_mobile_recovery_attempts", "mobile_to_mobile_recovery_successes", "mobile_to_mobile_recovery_failures"), ttfSuccessMs = stats?.mobileRecoveryFirstSuccessLatencyMs)
        if (flags["reconnect_burst_detected"] == true) add("reconnect_burst", "warning", "high", "unknown", "many_short_or_closed_sessions", listOf("handshake_accepted", "client_closed", "very_short_session", "connection_reset", "route_changed"))
        if (flags["telegram_likely_disabled_proxy"] == true) add("telegram_likely_disabled_proxy", "warning", "medium", "unknown", "client_accepts_then_closes", listOf("handshake_accepted", "client_closed", "very_short_session"))
        if (flags["cf_queue_degraded"] == true) add("cf_pressure_degradation", "warning", "high", "degraded", "cf_pressure_or_queue_failures", listOf("cf_429", "cf_unknown_host", "cf_queue_failure"), affectedDcs = stats?.cfPressureLevelByDc?.keys ?: emptySet())
        if ((counters["no_route"] ?: 0L) >= 3L) add("no_route_storm", "critical", "high", "failed", "network_none_or_settling", listOf("no_route", "route_attempts_failed_due_to_network_none"), affectedDcs = stats?.clientExperience?.recentNoRouteByDc?.keys ?: emptySet())
        if ((counters["unsupported_dc"] ?: 0L) > 0L) add("unsupported_dc", "warning", "high", "failed", "unsupported_dc", listOf("unsupported_dc"), affectedDcs = stats?.clientExperience?.recentUnsupportedDcByDc?.keys ?: emptySet())
        if (flags["unexpected_stop_detected"] == true || flags["previous_run_ended_unexpectedly"] == true) add("process_death", "warning", "medium", "unknown", "unexpected_stop_marker", emptyList(), durationMs = now - windowStartMs)
        if (telemetryFailedSendCount > 0L) add("telemetry_delivery_failure", "warning", "high", if (telemetryLastSendResult == "success") "recovered" else "failed", "telemetry_send_failed", emptyList())
    }

    private fun buildClientQuality(stats: ProxyServerStats?) = JSONObject().apply {
        put("likely_reconnect_burst", flags["reconnect_burst_detected"] == true || stats?.clientExperience?.likelyReconnectBurst == true)
        put("likely_telegram_disabled_proxy", flags["telegram_likely_disabled_proxy"] == true || stats?.clientExperience?.likelyTelegramDisabledProxy == true)
        stats?.clientExperience?.timeToFirstSuccessfulRouteAfterIdleMs?.let { put("time_to_first_success_after_idle_ms", it) }
        stats?.mobileRecoveryFirstSuccessLatencyMs?.let { put("time_to_first_success_after_network_change_ms", it) }
        put("recent_accepted", counters["handshake_accepted"] ?: stats?.clientExperience?.recentAcceptedHandshakes ?: 0L)
        put("recent_client_closed", counters["client_closed"] ?: stats?.clientExperience?.recentClientClosedSessions ?: 0L)
        put("recent_very_short", counters["very_short_session"] ?: stats?.clientExperience?.recentVeryShortClientClosedSessions ?: 0L)
        put("recent_no_route", counters["no_route"] ?: 0L)
        put("recent_unsupported_dc", counters["unsupported_dc"] ?: 0L)
    }

    private fun buildRouteQuality(stats: ProxyServerStats?, now: Long) = JSONObject().apply {
        put("effective_route_mode", effectiveRoute ?: "unknown")
        put("previous_effective_route_mode", previousEffectiveRoute ?: stats?.previousEffectiveRouteMode ?: "unknown")
        put("last_route_change_reason", lastRouteChangeReason?.let(::safeReason) ?: "unknown")
        put("last_successful_route_kind", stats?.lastRouteUsed ?: "unknown")
        stats?.lastSuccessfulRouteTimeMs?.takeIf { it > 0L }?.let { put("last_successful_route_age_ms", (now - it).coerceAtLeast(0L)) }
        put("route_attempts_failed_due_to_network_none", counters["route_attempts_failed_due_to_network_none"] ?: 0L)
        put("route_attempts_resumed_after_mobile_available", counters["route_attempts_resumed_after_mobile_available"] ?: 0L)
    }

    private fun buildPoolReadiness(stats: ProxyServerStats?) = JSONObject().apply {
        put("direct_health_state", directHealthState ?: "unknown")
        put("pool_hits", stats?.poolHits ?: counters["pool_hit"] ?: 0L)
        put("pool_misses", stats?.poolMisses ?: counters["pool_miss"] ?: 0L)
        put("pool_refill_errors", stats?.poolRefillErrors ?: counters["pool_refill_error"] ?: 0L)
        put("pool_stale", stats?.poolStale ?: counters["pool_stale"] ?: 0L)
        put("pool_not_ready_burst_count", counters["pool_miss"] ?: 0L)
    }

    private fun buildCfQuality(stats: ProxyServerStats?) = JSONObject().apply {
        put("cf_pressure_level_by_dc", mapToJson(stats?.cfPressureLevelByDc ?: emptyMap<Int, String>()))
        put("cf_pressure_reason_by_dc", mapToJson((stats?.cfPressureReasonByDc ?: emptyMap<Int, String>()).mapValues { safeReason(it.value) }))
        put("cf_429_by_dc", mapToJson(stats?.cfPressureRecent429ByDc ?: emptyMap<Int, Long>()))
        put("cf429Count", stats?.cf429Count ?: counters["cf_429"] ?: 0L)
        put("cf_unknown_host_by_dc", mapToJson(stats?.cfPressureRecentUnknownHostByDc ?: emptyMap<Int, Long>()))
        put("cfUnknownHostCount", stats?.cfUnknownHostCount ?: counters["cf_unknown_host"] ?: 0L)
        put("cf_queue_failures_by_dc", mapToJson(stats?.cfPressureRecentQueueFailureByDc ?: emptyMap<Int, Long>()))
        put("cfQueueControlledFailures", stats?.cfQueueControlledFailures ?: counters["cf_queue_failure"] ?: 0L)
        put("cf_success_by_dc", mapToJson(stats?.cfPressureRecentSuccessByDc ?: emptyMap<Int, Long>()))
    }

    private fun buildNetworkTransition(stats: ProxyServerStats?) = JSONObject().apply {
        put("current_network_type", networkType ?: "unknown")
        put("previous_network_type", previousNetworkType ?: "unknown")
        put("last_network_transition_type", lastNetworkTransitionType ?: "unknown")
        lastNetworkGapMs?.let { put("last_network_gap_ms", it) }
        put("network_generation", stats?.networkGeneration ?: 0L)
        put("previous_network_generation", previousNetworkGeneration ?: 0L)
        put("network_validated", true)
        put("network_has_internet", networkType != "none")
        put("network_not_suspended", true)
        put("network_not_metered", JSONObject.NULL)
        put("active_cellular_profile_alias", "unknown")
        put("previous_cellular_profile_alias", "unknown")
    }

    private fun buildTelemetryDelivery() = JSONObject().apply {
        put("telemetry_enabled", telemetryEnabled())
        put("telemetry_endpoint_configured", BuildConfig.TELEMETRY_ENDPOINT.isNotBlank())
        put("telemetry_token_present", BuildConfig.TELEMETRY_TOKEN.isNotBlank())
        put("telemetry_last_send_result", telemetryLastSendResult)
        put("telemetry_last_http_status_class", telemetryLastHttpStatusClass)
        put("telemetry_last_error_class", telemetryLastErrorClass)
        put("telemetry_failed_send_count", telemetryFailedSendCount)
        put("telemetry_success_send_count", telemetrySuccessSendCount)
    }

    private fun addBreadcrumb(type: String, dc: Int? = null, network: String? = null, networkBefore: String? = null, networkAfter: String? = null, routeBefore: String? = null, routeAfter: String? = null, generation: Long? = null, reason: String? = null) {
        if (breadcrumbs.size >= MAX_BREADCRUMBS) breadcrumbs.removeFirst()
        breadcrumbs.addLast(JSONObject().apply {
            put("type", type); put("time_ms", nowMs())
            dc?.let { put("dc", it) }; network?.let { put("network", it) }; networkBefore?.let { put("network_before", it) }; networkAfter?.let { put("network_after", it) }
            routeBefore?.let { put("route_before", it) }; routeAfter?.let { put("route_after", it) }; generation?.let { put("generation", it) }; reason?.let { put("reason", it) }
        })
    }

    private fun safeReason(value: String): String = value.lowercase().replace(Regex("[^a-z0-9_:-]"), "_").take(80)
    private fun mapToJson(map: Map<*, *>): JSONObject = JSONObject().apply { map.forEach { (key, value) -> put(key.toString(), value) } }

    companion object {
        const val DEFAULT_WINDOW_MS = 15 * 60 * 1000L
        const val DEFAULT_FORCE_FLUSH_MIN_INTERVAL_MS = 60 * 1000L
        const val DEFAULT_MAX_QUEUE_SIZE = 100
        private const val MAX_BREADCRUMBS = 30
    }
}
