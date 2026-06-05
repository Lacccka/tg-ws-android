package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStateTest {
    @Test
    fun autoMobileResolvesToCfFirst() {
        val state = RouteState(NetworkRouteMode.AUTO, "mobile")

        assertEquals(NetworkRouteMode.CF_FIRST, state.effectiveRouteMode)
    }

    @Test
    fun autoWifiResolvesToDirectFirst() {
        val state = RouteState(NetworkRouteMode.AUTO, "Wi-Fi")

        assertEquals(NetworkRouteMode.DIRECT_FIRST, state.effectiveRouteMode)
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
    fun autoMobileToWifiRecordsPreviousAndReason() {
        val state = RouteState(NetworkRouteMode.AUTO, "mobile")

        val result = state.applyNetwork("Wi-Fi")

        assertTrue(result.changed)
        assertEquals(NetworkRouteMode.CF_FIRST, result.previous)
        assertEquals(NetworkRouteMode.DIRECT_FIRST, result.current)
        assertEquals(NetworkRouteMode.CF_FIRST, state.snapshot().previousEffectiveRouteMode)
        assertEquals("network=Wi-Fi", state.snapshot().lastRouteChangeReason)
        assertEquals("Wi-Fi", state.snapshot().networkAtLastRouteChange)
    }
}
