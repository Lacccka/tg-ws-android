package com.flowseal.tgwsandroid.telemetry

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

    private companion object {
        const val WINDOW_MS = 900_000L
        const val FORCE_WINDOW_MS = 60_000L
    }
}
