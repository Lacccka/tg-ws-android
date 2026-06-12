package com.flowseal.tgwsandroid.proxy

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStateTest {
    @Test
    fun autoMobileResolvesToCfFirst() {
        val state = RouteState(NetworkRouteMode.AUTO, "mobile")

        assertEquals(NetworkRouteMode.CF_FIRST, state.effectiveRouteMode)
    }

    @Test
    fun autoWifiResolvesToCfFirstBeforeDirectHealthPromotion() {
        val state = RouteState(NetworkRouteMode.AUTO, "Wi-Fi")

        assertEquals(NetworkRouteMode.CF_FIRST, state.effectiveRouteMode)
    }

    @Test
    fun manualCfOnlyIgnoresWifiAndMobile() {
        val state = RouteState(NetworkRouteMode.CF_ONLY, "Wi-Fi")

        assertEquals(NetworkRouteMode.CF_ONLY, state.effectiveRouteMode)
        assertFalse(state.applyNetwork("mobile").changed)
        assertEquals(NetworkRouteMode.CF_ONLY, state.effectiveRouteMode)
    }

    @Test
    fun manualDirectFirstIgnoresWifiAndMobile() {
        val state = RouteState(NetworkRouteMode.DIRECT_FIRST, "mobile")

        assertEquals(NetworkRouteMode.DIRECT_FIRST, state.effectiveRouteMode)
        assertFalse(state.applyNetwork("Wi-Fi").changed)
        assertEquals(NetworkRouteMode.DIRECT_FIRST, state.effectiveRouteMode)
    }

    @Test
    fun autoMobileToWifiStaysCfFirstAndRecordsUnchangedSafeRouteEvaluationOnly() {
        val state = RouteState(NetworkRouteMode.AUTO, "mobile")

        val result = state.applyNetwork("Wi-Fi")

        assertFalse(result.changed)
        assertEquals(NetworkRouteMode.CF_FIRST, result.previous)
        assertEquals(NetworkRouteMode.CF_FIRST, result.current)
        assertNull(state.snapshot().previousEffectiveRouteMode)
        assertEquals("initial network=mobile", state.snapshot().lastRouteChangeReason)
        assertEquals("mobile", state.snapshot().networkAtLastRouteChange)
        assertEquals("network=Wi-Fi", state.snapshot().lastRouteEvaluationReason)
        assertEquals("Wi-Fi", state.snapshot().networkAtLastRouteEvaluation)
    }

    @Test
    fun changedApplyUpdatesRouteChangeAndEvaluationMetadata() {
        val clock = MutableClock(1_000L)
        val state = RouteState(NetworkRouteMode.AUTO, "mobile", clock)
        clock.nowMs = 2_000L

        val result = state.applyEffectiveRouteMode(
            NetworkRouteMode.DIRECT_FIRST,
            "direct health probe success",
            "Wi-Fi",
            source = "direct-health",
        )

        assertTrue(result.changed)
        val snapshot = state.snapshot()
        assertEquals("direct health probe success", snapshot.lastRouteChangeReason)
        assertEquals("direct-health", snapshot.lastRouteChangeSource)
        assertEquals(2_000L, snapshot.lastRouteChangeTimeMs)
        assertEquals("Wi-Fi", snapshot.networkAtLastRouteChange)
        assertEquals("direct health probe success", snapshot.lastRouteEvaluationReason)
        assertEquals("direct-health", snapshot.lastRouteEvaluationSource)
        assertEquals(2_000L, snapshot.lastRouteEvaluationTimeMs)
        assertEquals("Wi-Fi", snapshot.networkAtLastRouteEvaluation)
        assertEquals(1L, snapshot.routeEvaluations)
        assertEquals(0L, snapshot.routeNoopEvaluations)
    }

    @Test
    fun noopApplyDoesNotOverwriteLastRouteChangeButUpdatesEvaluationMetadata() {
        val clock = MutableClock(1_000L)
        val state = RouteState(NetworkRouteMode.AUTO, "mobile", clock)
        clock.nowMs = 2_000L
        state.applyEffectiveRouteMode(
            NetworkRouteMode.DIRECT_FIRST,
            "direct health probe success",
            "Wi-Fi",
            source = "direct-health",
        )
        clock.nowMs = 3_000L

        val result = state.applyEffectiveRouteMode(
            NetworkRouteMode.DIRECT_FIRST,
            "Wi-Fi capabilities changed; direct route already healthy",
            "Wi-Fi",
            source = "debounce",
        )

        assertFalse(result.changed)
        val snapshot = state.snapshot()
        assertEquals("direct health probe success", snapshot.lastRouteChangeReason)
        assertEquals("direct-health", snapshot.lastRouteChangeSource)
        assertEquals(2_000L, snapshot.lastRouteChangeTimeMs)
        assertEquals("Wi-Fi", snapshot.networkAtLastRouteChange)
        assertEquals("Wi-Fi capabilities changed; direct route already healthy", snapshot.lastRouteEvaluationReason)
        assertEquals("debounce", snapshot.lastRouteEvaluationSource)
        assertEquals(3_000L, snapshot.lastRouteEvaluationTimeMs)
        assertEquals("Wi-Fi", snapshot.networkAtLastRouteEvaluation)
        assertEquals(2L, snapshot.routeEvaluations)
        assertEquals(1L, snapshot.routeNoopEvaluations)
    }

    private class MutableClock(var nowMs: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
    }
}
