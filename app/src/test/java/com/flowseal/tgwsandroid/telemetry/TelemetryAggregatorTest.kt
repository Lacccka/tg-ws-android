package com.flowseal.tgwsandroid.telemetry

import com.flowseal.tgwsandroid.proxy.ClientExperienceDiagnostics
import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TelemetryAggregatorTest {
    private var enabled = true
    private var now = 1_000L
    private fun aggregator() = TelemetryAggregator({ enabled }, { now }, windowMs = WINDOW_MS, forceFlushMinIntervalMs = FORCE_WINDOW_MS)

    @Before fun setUp() {
        enabled = true
        now = 1_000L
    }

    private fun flagsFromSnapshot(event: JSONObject?): JSONObject {
        val snapshot = requireNotNull(event) {
            "Expected diagnostics_snapshot event"
        }
        assertEquals("diagnostics_snapshot", snapshot.getString("name"))
        val payload = requireNotNull(snapshot.optJSONObject("payload")) {
            "diagnostics_snapshot payload is missing: $snapshot"
        }
        return requireNotNull(payload.optJSONObject("flags")) {
            "diagnostics_snapshot payload.flags is missing: $payload"
        }
    }

    @Test fun hundredDirectTimeoutsBecomeOneSnapshotCounter() {
        val agg = aggregator()
        repeat(100) { agg.recordCounter("direct_timeout") }
        val event = agg.maybeFlush()!!
        assertEquals("diagnostics_snapshot", event.getString("name"))
        assertEquals(100L, event.getJSONObject("payload").getJSONObject("counters").getLong("direct_timeout"))
    }

    @Test fun booleanFlagsCollapseToTrue() {
        val agg = aggregator()
        repeat(3) { agg.recordFlag("reconnect_burst_detected") }
        val flags = agg.maybeFlush()!!.getJSONObject("payload").getJSONObject("flags")
        assertTrue(flags.getBoolean("reconnect_burst_detected"))
    }

    @Test fun firstNormalFlushCreatesOneSnapshot() {
        val agg = aggregator()
        agg.recordCounter("direct_timeout")

        assertNotNull(agg.maybeFlush())

        assertEquals(1, agg.queueSize())
    }

    @Test fun secondNormalFlushBeforeFifteenMinutesCreatesNoAdditionalSnapshot() {
        val agg = aggregator()
        agg.recordCounter("direct_timeout")
        assertNotNull(agg.maybeFlush())

        agg.recordCounter("pool_miss")
        now += WINDOW_MS - 1L

        assertNull(agg.maybeFlush())
        assertEquals(1, agg.queueSize())
    }

    @Test fun blockedNormalFlushKeepsCountersAndFlagsAccumulatedUntilWindowExpires() {
        val agg = aggregator()
        agg.recordCounter("direct_timeout")
        assertNotNull(agg.maybeFlush())

        agg.recordCounter("pool_miss", 2L)
        agg.recordFlag("reconnect_burst_detected")
        now += WINDOW_MS - 1L
        assertNull(agg.maybeFlush())
        agg.recordCounter("pool_miss", 3L)

        now += 1L
        val event = agg.maybeFlush()!!
        val payload = event.getJSONObject("payload")
        assertEquals(5L, payload.getJSONObject("counters").getLong("pool_miss"))
        assertTrue(payload.getJSONObject("flags").getBoolean("reconnect_burst_detected"))
        assertEquals(2, agg.queueSize())
    }

    @Test fun normalFlushAfterFifteenMinutesCreatesNextSnapshot() {
        val agg = aggregator()
        agg.recordCounter("direct_timeout")
        assertNotNull(agg.maybeFlush())

        agg.recordCounter("pool_miss")
        now += WINDOW_MS

        assertNotNull(agg.maybeFlush())
        assertEquals(2, agg.queueSize())
    }

    @Test fun rateLimitBlocksSnapshotsBeforeWindow() {
        val agg = aggregator()
        agg.recordCounter("direct_timeout")
        assertNotNull(agg.maybeFlush())

        agg.recordCounter("pool_miss")
        now += WINDOW_MS - 1L
        assertNull(agg.maybeFlush())
        assertEquals(1, agg.queueSize())
        now += 1L
        assertNotNull(agg.maybeFlush())
    }

    @Test fun forceFlushBeforeOneMinuteIsBlocked() {
        val agg = aggregator()
        agg.recordCounter("direct_timeout")
        assertNotNull(agg.forceFlushCritical())

        agg.recordCounter("direct_timeout")
        now += FORCE_WINDOW_MS - 1L

        assertNull(agg.forceFlushCritical())
        assertEquals(1, agg.queueSize())
    }

    @Test fun forceFlushAfterOneMinuteIsAllowed() {
        val agg = aggregator()
        agg.recordCounter("direct_timeout")
        assertNotNull(agg.forceFlushCritical())

        agg.recordCounter("direct_timeout")
        now += FORCE_WINDOW_MS

        assertNotNull(agg.forceFlushCritical())
        assertEquals(2, agg.queueSize())
    }

    @Test fun forceFlushDoesNotResetNormalFlushRateLimit() {
        val agg = aggregator()
        agg.recordCounter("direct_timeout")
        assertNotNull(agg.maybeFlush())

        now += FORCE_WINDOW_MS
        agg.recordCounter("pool_miss")
        assertNotNull(agg.forceFlushCritical())

        agg.recordCounter("pool_stale")
        now += WINDOW_MS - FORCE_WINDOW_MS - 1L
        assertNull(agg.maybeFlush())
        assertEquals(2, agg.queueSize())

        now += 1L
        assertNotNull(agg.maybeFlush())
        assertEquals(3, agg.queueSize())
    }

    @Test fun criticalForceFlushIsLimitedToOneMinute() {
        val agg = aggregator()
        agg.recordCounter("direct_timeout")
        assertNotNull(agg.forceFlushCritical())
        agg.recordCounter("direct_timeout")
        now += FORCE_WINDOW_MS - 1L
        assertNull(agg.forceFlushCritical())
        now += 1L
        assertNotNull(agg.forceFlushCritical())
    }

    @Test fun boundedQueueDoesNotGrowPastHundredAndCountsDrops() {
        val agg = aggregator()
        repeat(101) {
            agg.recordCounter("direct_timeout")
            now += WINDOW_MS
            agg.maybeFlush()
        }
        assertEquals(100, agg.queueSize())
        assertEquals(1L, agg.currentDroppedSnapshotsCount())
    }

    @Test fun redactorIsAppliedToDiagnosticsSnapshot() {
        val original = org.json.JSONObject().apply {
            put("name", "diagnostics_snapshot")
            put("payload", org.json.JSONObject().apply {
                put("secret", "sensitive")
                put("raw_logs", "do not send")
                put("counters", org.json.JSONObject().put("direct_timeout", 1))
            })
        }
        val redacted = TelemetryRedactor.redact(original)
        val payload = redacted.getJSONObject("payload")
        assertFalse(payload.has("secret"))
        assertFalse(payload.has("raw_logs"))
        assertEquals(1, payload.getJSONObject("counters").getInt("direct_timeout"))
    }

    @Test fun disabledTelemetryIgnoresEventsAndFlushes() {
        enabled = false
        val agg = aggregator()
        repeat(10) { agg.recordCounter("direct_timeout") }
        agg.recordFlag("reconnect_burst_detected")
        assertNull(agg.forceFlushCritical())
        now += WINDOW_MS
        assertNull(agg.maybeFlush())
        assertEquals(0, agg.queueSize())

        enabled = true
        assertNull(agg.maybeFlush())
        assertEquals(0, agg.queueSize())
    }

    @Test fun telemetryDisabledIgnoresAndDoesNotAccumulate() {
        enabled = false
        val agg = aggregator()
        repeat(10) { agg.recordCounter("direct_timeout") }
        now += WINDOW_MS
        assertNull(agg.maybeFlush())
        assertEquals(0, agg.queueSize())
    }


    @Test fun cfQueueFailureSetsDegradedFlag() {
        val agg = aggregator()
        agg.recordStats(stats(cfQueue = 0), true, false)
        agg.pollSnapshot()
        agg.recordStats(stats(cfQueue = 1), true, false)
        val flags = agg.forceFlushCritical()!!.getJSONObject("payload").getJSONObject("flags")
        assertTrue(flags.getBoolean("cf_queue_degraded"))
    }

    @Test fun cf429SetsDegradedFlag() {
        val agg = aggregator()
        agg.recordStats(stats(cf429 = 0), true, false)
        agg.pollSnapshot()
        agg.recordStats(stats(cf429 = 1), true, false)
        val flags = agg.forceFlushCritical()!!.getJSONObject("payload").getJSONObject("flags")
        assertTrue(flags.getBoolean("cf_queue_degraded"))
    }

    @Test fun reconnectBurstHeuristicSetsFlag() {
        val agg = aggregator()
        agg.recordStats(stats(total = 0, clientClosed = 0), true, false)
        agg.pollSnapshot()
        agg.recordStats(stats(total = 10, clientClosed = 5), true, false)
        val flags = agg.forceFlushCritical()!!.getJSONObject("payload").getJSONObject("flags")
        assertTrue(flags.getBoolean("reconnect_burst_detected"))
    }

    @Test fun lowNormalTrafficDoesNotSetReconnectBurst() {
        val agg = aggregator()
        agg.recordStats(stats(total = 0), true, false)
        agg.pollSnapshot()
        agg.recordStats(stats(total = 2, active = 2), true, false)
        val flags = agg.forceFlushCritical()!!.getJSONObject("payload").getJSONObject("flags")
        assertFalse(flags.has("reconnect_burst_detected"))
    }

    @Test fun veryShortSessionPatternCanSetTelegramLikelyDisabledProxy() {
        val agg = aggregator()
        agg.recordStats(stats(total = 0, short = 0), true, false)
        agg.pollSnapshot()
        agg.recordStats(stats(total = 1, short = 3, active = 0), true, false)
        val flags = agg.forceFlushCritical()!!.getJSONObject("payload").getJSONObject("flags")
        assertTrue(flags.getBoolean("telegram_likely_disabled_proxy"))
    }

    @Test fun normalActiveSessionsDoNotSetTelegramLikelyDisabledProxy() {
        val agg = aggregator()
        agg.recordStats(stats(total = 0, clientClosed = 0), true, false)
        agg.pollSnapshot()
        agg.recordStats(stats(total = 1, clientClosed = 5, active = 3), true, false)
        val flags = agg.forceFlushCritical()!!.getJSONObject("payload").getJSONObject("flags")
        assertFalse(flags.has("telegram_likely_disabled_proxy"))
    }

    @Test fun previousRunUnexpectedFlagsAreIncluded() {
        val agg = aggregator()
        agg.recordStats(stats(), true, false, previousRunEndedUnexpectedly = true)
        val flags = flagsFromSnapshot(agg.pollSnapshot())
        assertTrue(flags.getBoolean("previous_run_ended_unexpectedly"))
        assertTrue(flags.getBoolean("unexpected_stop_detected"))
    }

    @Test fun batteryRestrictionFlagIsIncluded() {
        val agg = aggregator()
        agg.recordStats(stats(), true, false, batteryRestrictionDetected = true)
        val flags = flagsFromSnapshot(agg.pollSnapshot())
        assertTrue(flags.getBoolean("battery_restriction_detected"))
    }

    @Test fun unsupportedDcCounterUsesRealUnsupportedDcSource() {
        val agg = aggregator()
        agg.recordStats(stats(unsupportedDcCount = 0), true, false)
        agg.pollSnapshot()
        agg.recordStats(stats(unsupportedDcCount = 2), true, false)
        val counters = agg.forceFlushCritical()!!.getJSONObject("payload").getJSONObject("counters")
        assertEquals(2L, counters.getLong("unsupported_dc"))
    }

    @Test fun telemetryDisabledIgnoresNewFlagsAndCounters() {
        enabled = false
        val agg = aggregator()
        agg.recordStats(stats(), true, false, batteryRestrictionDetected = true, previousRunEndedUnexpectedly = true)
        agg.recordCounter("unsupported_dc")
        assertNull(agg.maybeFlush())
        assertEquals(0, agg.queueSize())
    }


    @Test fun diagnosticsSnapshotKeepsLegacyFieldsAndAddsNewSections() {
        val agg = aggregator()
        agg.recordStats(stats(total = 1), true, false)
        val payload = agg.pollSnapshot()!!.getJSONObject("payload")
        assertTrue(payload.has("network_type"))
        assertTrue(payload.has("effective_route"))
        assertTrue(payload.has("counters"))
        assertTrue(payload.has("flags"))
        listOf("diagnostic_episodes", "safe_breadcrumbs", "client_quality", "route_quality", "pool_readiness", "cf_quality", "network_transition", "telemetry_delivery").forEach {
            assertTrue("missing $it in $payload", payload.has(it))
        }
    }

    @Test fun cfPressureDegradationEpisodeIsIncluded() {
        val agg = aggregator()
        agg.recordStats(stats(cf429 = 0), true, false)
        agg.pollSnapshot()
        agg.recordStats(stats(cf429 = 1).apply {
            cfPressureLevelByDc = mapOf(2 to "high")
            cfPressureReasonByDc = mapOf(2 to "queue_degraded")
        }, true, false)
        val episodes = agg.forceFlushCritical()!!.getJSONObject("payload").getJSONArray("diagnostic_episodes")
        assertTrue((0 until episodes.length()).any { episodes.getJSONObject(it).getString("episode_type") == "cf_pressure_degradation" })
    }

    @Test fun reconnectBurstEpisodeIsIncluded() {
        val agg = aggregator()
        agg.recordStats(stats(total = 0, clientClosed = 0), true, false)
        agg.pollSnapshot()
        agg.recordStats(stats(total = 10, clientClosed = 5), true, false)
        val episodes = agg.forceFlushCritical()!!.getJSONObject("payload").getJSONArray("diagnostic_episodes")
        assertTrue((0 until episodes.length()).any { episodes.getJSONObject(it).getString("episode_type") == "reconnect_burst" })
    }

    @Test fun redactorRemovesForbiddenDataFromNewSections() {
        val original = org.json.JSONObject().apply {
            put("payload", org.json.JSONObject().apply {
                put("safe_breadcrumbs", org.json.JSONArray().put(org.json.JSONObject().put("ssid", "wifi").put("type", "network_changed")))
                put("telemetry_delivery", org.json.JSONObject().put("endpoint", "https://example.test/path").put("token", "secret-token"))
                put("client_quality", org.json.JSONObject().put("phone_number", "+123").put("recent_accepted", 1))
            })
        }
        val payload = TelemetryRedactor.redact(original).getJSONObject("payload")
        assertFalse(payload.getJSONArray("safe_breadcrumbs").getJSONObject(0).has("ssid"))
        assertFalse(payload.getJSONObject("telemetry_delivery").has("endpoint"))
        assertFalse(payload.getJSONObject("telemetry_delivery").has("token"))
        assertFalse(payload.getJSONObject("client_quality").has("phone_number"))
        assertEquals(1, payload.getJSONObject("client_quality").getInt("recent_accepted"))
    }

    @Test fun telemetryDeliveryDoesNotLeakEndpointUrlOrToken() {
        val agg = aggregator()
        agg.recordCounter("direct_timeout")
        agg.recordTelemetryDelivery(false)
        val delivery = agg.forceFlushCritical()!!.getJSONObject("payload").getJSONObject("telemetry_delivery")
        assertTrue(delivery.has("telemetry_endpoint_configured"))
        assertTrue(delivery.has("telemetry_token_present"))
        assertFalse(delivery.toString().contains("https://"))
        assertFalse(delivery.has("telemetry_token"))
        assertFalse(delivery.has("telemetry_endpoint"))
    }

    private fun stats(
        total: Long = 0,
        active: Int = 0,
        clientClosed: Long = 0,
        short: Long = 0,
        cfQueue: Long = 0,
        cf429: Long = 0,
        unsupportedDcCount: Long = 0,
    ) = ProxyServerStats().apply {
        connectionsTotal = total
        connectionsActive = active
        connectionsBad = 0
        wsConnectErrors = 0
        cfProxyConnections = 0
        cfProxyErrors = 0
        bytesUp = 0
        bytesDown = 0
        poolHits = 0
        poolMisses = 0
        poolRefillErrors = 0
        sessionClientClosed = clientClosed
        sessionRemoteEofShort = short
        cfQueueControlledFailures = cfQueue
        cf429Count = cf429
        unsupportedDc = unsupportedDcCount
        clientExperience = ClientExperienceDiagnostics()
    }

    private companion object {
        const val WINDOW_MS = 900_000L
        const val FORCE_WINDOW_MS = 60_000L
    }
}
