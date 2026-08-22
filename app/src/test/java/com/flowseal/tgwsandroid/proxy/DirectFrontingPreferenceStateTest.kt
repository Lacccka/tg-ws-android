package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectFrontingPreferenceStateTest {
    @Test
    fun staleGenerationCannotRollPreferenceStateBackwards() {
        val state = DirectFrontingPreferenceState()
        val oldKey = DirectFrontingRouteKey(2, false, "149.154.167.220")
        val newKey = DirectFrontingRouteKey(4, true, "149.154.167.220")

        state.recordFrontingSuccess(oldKey, 10L)
        assertTrue(state.shouldTryFrontingFirst(oldKey, 10L))

        state.recordFrontingSuccess(newKey, 11L)
        assertFalse(state.shouldTryFrontingFirst(oldKey, 11L))
        assertTrue(state.shouldTryFrontingFirst(newKey, 11L))

        // Simulates a refill that started on generation 10 and completed after
        // generation 11 had already become current.
        state.recordFrontingSuccess(oldKey, 10L)
        state.recordNormalDirectSuccess(newKey, 10L)
        state.clearForNetworkGeneration(10L)

        assertFalse(state.shouldTryFrontingFirst(oldKey, 11L))
        assertTrue(state.shouldTryFrontingFirst(newKey, 11L))
        assertTrue(state.preferredSnapshot(10L).isEmpty())
        assertTrue(state.preferredSnapshot(11L).contains(newKey))
    }
}
