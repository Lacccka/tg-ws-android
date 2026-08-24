package com.flowseal.tgwsandroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorFallbackWarmupPolicyTest {
    @Test
    fun healthyMobileDoesNotTriggerWithoutFailures() {
        var now = 1_000L
        val policy = TorFallbackWarmupPolicy(clockMs = { now })
        policy.onNetworkChanged("mobile")

        val snapshot = policy.snapshot()
        assertFalse(snapshot.triggered)
        assertEquals(0, snapshot.recentFailureCount)
        assertNull(snapshot.reason)
    }

    @Test
    fun threeOrdinaryRouteFailuresInsideWindowTriggerOnce() {
        var now = 1_000L
        val policy = TorFallbackWarmupPolicy(clockMs = { now })
        policy.onNetworkChanged("mobile")

        assertFalse(policy.recordOrdinaryRouteExhausted("first").triggered)
        now += 1_000L
        assertFalse(policy.recordOrdinaryRouteExhausted("second").triggered)
        now += 1_000L
        val third = policy.recordOrdinaryRouteExhausted("cf/direct exhausted for DC2")

        assertTrue(third.triggered)
        assertTrue(third.newlyTriggered)
        assertEquals(3, third.recentFailureCount)
        assertEquals("cf/direct exhausted for DC2", third.reason)

        now += 100L
        val fourth = policy.recordOrdinaryRouteExhausted("again")
        assertTrue(fourth.triggered)
        assertFalse(fourth.newlyTriggered)
    }

    @Test
    fun isolatedFailuresAgeOutAndDoNotTrigger() {
        var now = 1_000L
        val policy = TorFallbackWarmupPolicy(failureWindowMs = 10_000L, clockMs = { now })
        policy.onNetworkChanged("mobile")

        assertFalse(policy.recordOrdinaryRouteExhausted("one").triggered)
        now += 11_000L
        assertFalse(policy.recordOrdinaryRouteExhausted("two").triggered)
        now += 11_000L
        assertFalse(policy.recordOrdinaryRouteExhausted("three").triggered)
        assertEquals(1, policy.snapshot().recentFailureCount)
    }

    @Test
    fun leavingMobileResetsFailureBurst() {
        var now = 1_000L
        val policy = TorFallbackWarmupPolicy(clockMs = { now })
        policy.onNetworkChanged("mobile")
        policy.recordOrdinaryRouteExhausted("one")
        now += 100L
        policy.recordOrdinaryRouteExhausted("two")

        policy.onNetworkChanged("Wi-Fi")
        assertEquals(0, policy.snapshot().recentFailureCount)
        assertFalse(policy.snapshot().triggered)

        policy.onNetworkChanged("mobile")
        now += 100L
        assertFalse(policy.recordOrdinaryRouteExhausted("new mobile generation").triggered)
    }
}
