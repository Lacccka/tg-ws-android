package com.flowseal.tgwsandroid.telemetry

import org.junit.Assert.*
import org.junit.Test

class TelemetryAggregatorTest {
    private var enabled = true
    private var now = 1_000L
    private fun aggregator() = TelemetryAggregator({ enabled }, { now }, windowMs = 900_000L, forceFlushMinIntervalMs = 60_000L)

    @Test fun hundredDirectTimeoutsBecomeOneSnapshotCounter() {
        val agg = aggregator()
        repeat(100) { agg.recordCounter("direct_timeout") }
        now += 900_000L
        val event = agg.maybeFlush()!!
        assertEquals("diagnostics_snapshot", event.getString("name"))
        assertEquals(100L, event.getJSONObject("payload").getJSONObject("counters").getLong("direct_timeout"))
    }

    @Test fun booleanFlagsCollapseToTrue() {
        val agg = aggregator()
        repeat(3) { agg.recordFlag("reconnect_burst_detected") }
        now += 900_000L
        val flags = agg.maybeFlush()!!.getJSONObject("payload").getJSONObject("flags")
        assertTrue(flags.getBoolean("reconnect_burst_detected"))
    }

    @Test fun rateLimitBlocksSnapshotsBeforeWindow() {
        val agg = aggregator()
        agg.recordCounter("direct_timeout")
        now += 899_999L
        assertNull(agg.maybeFlush())
        now += 1L
        assertNotNull(agg.maybeFlush())
    }

    @Test fun criticalForceFlushIsLimitedToOneMinute() {
        val agg = aggregator()
        agg.recordCounter("direct_timeout")
        now += 60_000L
        assertNotNull(agg.forceFlushCritical())
        agg.recordCounter("direct_timeout")
        now += 59_999L
        assertNull(agg.forceFlushCritical())
        now += 1L
        assertNotNull(agg.forceFlushCritical())
    }

    @Test fun boundedQueueDoesNotGrowPastHundredAndCountsDrops() {
        val agg = aggregator()
        repeat(101) {
            agg.recordCounter("direct_timeout")
            now += 900_000L
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

    @Test fun telemetryDisabledIgnoresAndDoesNotAccumulate() {
        enabled = false
        val agg = aggregator()
        repeat(10) { agg.recordCounter("direct_timeout") }
        now += 900_000L
        assertNull(agg.maybeFlush())
        assertEquals(0, agg.queueSize())
    }
}
