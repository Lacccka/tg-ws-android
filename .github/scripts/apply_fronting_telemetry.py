from pathlib import Path

path = Path("app/src/main/java/com/flowseal/tgwsandroid/telemetry/TelemetryAggregator.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one telemetry match, got {count}: {old[:120]!r}")
    text = text.replace(old, new, 1)


replace_once(
    '            recordDelta("direct_timeout", previous.directTimeouts, stats.directTimeouts)\n            recordDelta("pool_miss", previous.poolMisses, stats.poolMisses)',
    '''            recordDelta("direct_timeout", previous.directTimeouts, stats.directTimeouts)
            recordDelta("fronting_attempt", previous.frontingAttempts, stats.frontingAttempts)
            recordDelta("fronting_success", previous.frontingSuccesses, stats.frontingSuccesses)
            recordDelta("fronting_failure", previous.frontingFailures, stats.frontingFailures)
            recordDelta("fronting_first_attempt", previous.frontingFirstAttempts, stats.frontingFirstAttempts)
            recordDelta("fronting_fallback_attempt", previous.frontingFallbackAttempts, stats.frontingFallbackAttempts)
            recordDelta(
                "pool_refill_backoff_suppressed",
                previous.directPoolDiagnostics.refillBackoffSuppressedByKey.values.sum(),
                stats.directPoolDiagnostics.refillBackoffSuppressedByKey.values.sum(),
            )
            recordDelta(
                "pool_closed_idle_pruned",
                previous.directPoolDiagnostics.closedIdlePrunedByKey.values.sum(),
                stats.directPoolDiagnostics.closedIdlePrunedByKey.values.sum(),
            )
            recordDelta("pool_miss", previous.poolMisses, stats.poolMisses)''',
)

replace_once(
    '''        put("last_route_change_reason", lastRouteChangeReason?.let(::safeReason) ?: "unknown")
        put("last_successful_route_kind", stats?.lastRouteUsed ?: "unknown")
        stats?.lastSuccessfulRouteTimeMs?.takeIf { it > 0L }?.let { put("last_successful_route_age_ms", (now - it).coerceAtLeast(0L)) }''',
    '''        put("last_route_change_reason", lastRouteChangeReason?.let(::safeReason) ?: "unknown")
        put("last_successful_route_kind", stats?.lastRouteUsed ?: "unknown")
        put("fronting_preferred_key_count", stats?.frontingPreferredKeys?.size ?: 0)
        stats?.lastFrontingTimeMs?.takeIf { it > 0L }?.let { put("last_fronting_event_age_ms", (now - it).coerceAtLeast(0L)) }
        stats?.lastSuccessfulRouteTimeMs?.takeIf { it > 0L }?.let { put("last_successful_route_age_ms", (now - it).coerceAtLeast(0L)) }''',
)

replace_once(
    '''        put("pool_refill_errors", stats?.poolRefillErrors ?: counters["pool_refill_error"] ?: 0L)
        put("pool_stale", stats?.poolStale ?: counters["pool_stale"] ?: 0L)
        put("pool_not_ready_burst_count", counters["pool_miss"] ?: 0L)''',
    '''        put("pool_refill_errors", stats?.poolRefillErrors ?: counters["pool_refill_error"] ?: 0L)
        put("pool_stale", stats?.poolStale ?: counters["pool_stale"] ?: 0L)
        put("refill_failure_waves_by_key", mapToJson(stats?.directPoolDiagnostics?.refillFailureWavesByKey ?: emptyMap<String, Int>()))
        put("refill_backoff_remaining_ms_by_key", mapToJson(stats?.directPoolDiagnostics?.refillBackoffRemainingMsByKey ?: emptyMap<String, Long>()))
        put("refill_backoff_suppressed_by_key", mapToJson(stats?.directPoolDiagnostics?.refillBackoffSuppressedByKey ?: emptyMap<String, Long>()))
        put("closed_idle_pruned_by_key", mapToJson(stats?.directPoolDiagnostics?.closedIdlePrunedByKey ?: emptyMap<String, Long>()))
        put("pool_not_ready_burst_count", counters["pool_miss"] ?: 0L)''',
)

path.write_text(text, encoding="utf-8")
print("Applied fronting telemetry aggregation edits")
