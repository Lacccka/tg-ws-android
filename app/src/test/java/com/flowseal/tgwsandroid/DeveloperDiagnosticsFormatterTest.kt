package com.flowseal.tgwsandroid

import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperDiagnosticsFormatterTest {
    @Test
    fun directPoolDetailsContainsDirectAndPoolFields() {
        val stats = ProxyServerStats().apply {
            directHealthState = "healthy"
            directAttempts = 7
            directHealthSuccesses = 5
            directHealthFailures = 2
            directTimeouts = 1
            directCooldownUntil = 1234
            lastRouteUsed = "direct"
            poolHits = 10
            poolMisses = 3
            poolStale = 2
            poolRefillErrors = 1
            poolRefillsCancelled = 4
            poolResultsDiscardedAfterRouteChange = 6
        }

        val details = DeveloperDiagnosticsFormatter.directPoolDetails(stats)

        assertTrue(details.contains("direct health=healthy"))
        assertTrue(details.contains("attempts=7"))
        assertTrue(details.contains("successes=5"))
        assertTrue(details.contains("failures=2"))
        assertTrue(details.contains("timeouts=1"))
        assertTrue(details.contains("cooldownUntil=1234"))
        assertTrue(details.contains("lastRoute=direct"))
        assertTrue(details.contains("pool hits=10"))
        assertTrue(details.contains("misses=3"))
        assertTrue(details.contains("stale=2"))
        assertTrue(details.contains("refillErrors=1"))
        assertTrue(details.contains("refillsCancelled=4"))
        assertTrue(details.contains("discardedAfterRouteChange=6"))
        assertTrue(details.contains("readyByKey="))
        assertTrue(details.contains("inFlightRefillsByKey="))
        assertTrue(details.contains("lastRefillErrorByKey="))
    }

    @Test
    fun directPoolDetailsDoesNotContainHandshakeOnlyFields() {
        val details = DeveloperDiagnosticsFormatter.directPoolDetails(ProxyServerStats())

        assertFalse(details.contains("Invalid MTProto handshake storm"))
        assertFalse(details.contains("badHandshakeRatio"))
        assertFalse(details.contains("handshakeDiagnosticState"))
        assertFalse(details.contains("handshakeDiagnosticReason"))
        assertFalse(details.contains("badHandshakeRecommendation"))
    }

    @Test
    fun handshakeDetailsContainsHandshakeDiagnosticsFields() {
        val stats = ProxyServerStats().apply {
            connectionsTotal = 10
            connectionsBad = 2
            recentInvalidHandshakeCount = 4
            recentAcceptedHandshakeCount = 1
        }

        val details = DeveloperDiagnosticsFormatter.handshakeDetails(stats)

        assertTrue(details.contains("state="))
        assertTrue(details.contains("reason="))
        assertTrue(details.contains("recommendation="))
        assertTrue(details.contains("recentInvalid=4"))
        assertTrue(details.contains("recentAccepted=1"))
        assertTrue(details.contains("badHandshakeRatio=0.200"))
        assertTrue(details.contains("storm="))
        assertTrue(details.contains("secondsSinceLastAcceptedHandshake="))
        assertTrue(details.contains("secondsSinceLastSuccessfulRoute="))
    }

    @Test
    fun handshakeDetailsIsNotCopyOfDirectPoolDetails() {
        val stats = ProxyServerStats().apply {
            directAttempts = 7
            poolHits = 10
            recentInvalidHandshakeCount = 4
        }

        val direct = DeveloperDiagnosticsFormatter.directPoolDetails(stats)
        val handshake = DeveloperDiagnosticsFormatter.handshakeDetails(stats)

        assertNotEquals(direct, handshake)
        assertFalse(handshake.contains("direct health="))
        assertFalse(handshake.contains("pool hits="))
    }

    @Test
    fun formatterReturnsUnknownStringsForMissingStats() {
        assertEquals("direct/pool: unknown", DeveloperDiagnosticsFormatter.directPoolDetails(null))
        assertEquals("handshake: unknown", DeveloperDiagnosticsFormatter.handshakeDetails(null))
    }
}
