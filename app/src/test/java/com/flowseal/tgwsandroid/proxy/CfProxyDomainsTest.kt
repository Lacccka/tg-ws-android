package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class CfProxyDomainsTest {
    @Test
    fun defaultDomainsDecodeDeterministicallyLikeUpstream() {
        assertEquals(
            listOf(
                "pclead.co.uk",
                "offshor.co.uk",
                "cakeisalie.co.uk",
                "noskomnadzor.co.uk",
                "lovetrue.co.uk",
                "sorokdva.co.uk",
                "pyatdesyatdva.co.uk",
                "kartoshka.co.uk",
                "sorokodin.co.uk",
                "pyatdesyatodin.co.uk",
            ),
            CfProxyDomains.defaults,
        )
        assertEquals("example.org", CfProxyDomains.decodeDefaultDomain("example.org"))
    }

    @Test
    fun normalizeDropsInvalidAndDuplicateDomains() {
        assertEquals(
            listOf("example.com", "second-domain.co.uk"),
            CfProxyDomains.normalize(listOf(" Example.COM ", "example.com", "-bad.com", "second-domain.co.uk", "no-tld")),
        )
    }

    @Test
    fun balancerReturnsConfiguredOrderAndPromotesWorkingDomainPerDc() {
        val balancer = CfProxyBalancer(listOf("one.example", "two.example", "three.example"))

        assertEquals(listOf("one.example", "two.example", "three.example"), balancer.getDomainsForDc(5))
        assertTrue(balancer.updateDomainForDc(5, "three.example"))
        assertEquals(listOf("three.example", "one.example", "two.example"), balancer.getDomainsForDc(5))
        assertFalse(balancer.updateDomainForDc(5, "three.example"))
        assertEquals(listOf("one.example", "two.example", "three.example"), balancer.getDomainsForDc(2))
    }

    @Test
    fun successfulCfDomainBecomesPreferredForSameDc() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L })

        health.recordSuccess(2, isMedia = false, baseDomain = "two.example", latencyMs = 120)

        assertEquals("two.example", health.selectDomains(2).ordered.first().domain)
    }

    @Test
    fun http429PutsDomainIntoCooldownAndNextAttemptChoosesAnotherDomain() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { now })

        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", routeSettling = false)
        val plan = health.selectDomains(2)

        assertEquals("two.example", plan.ordered.first().domain)
        assertEquals(1L, health.snapshot().total429)
        assertEquals(1, health.snapshot().domainsInCooldown)
        assertTrue(health.snapshot().cooldownSkips > 0L)
        now = 62_000L
        assertEquals("one.example", health.selectDomains(2).ordered.first().domain)
    }

    @Test
    fun http503PutsDomainIntoCooldown() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L })

        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 503"), "Wi-Fi", routeSettling = false)

        assertEquals("two.example", health.selectDomains(2).ordered.first().domain)
        assertEquals(1L, health.snapshot().total503)
    }

    @Test
    fun unknownHostDuringNetworkNoneDoesNotPoisonDomain() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L })

        val decision = health.recordFailure(2, false, "one.example", UnknownHostException("one.example"), "none", routeSettling = false)

        assertFalse(decision.counted)
        assertEquals("one.example", health.selectDomains(2).ordered.first().domain)
        assertEquals(0L, health.snapshot().totalUnknownHost)
    }

    @Test
    fun unknownHostOnStableNetworkCooldownsDomain() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L })

        val decision = health.recordFailure(2, false, "one.example", UnknownHostException("one.example"), "mobile", routeSettling = false)

        assertTrue(decision.counted)
        assertEquals("two.example", health.selectDomains(2).ordered.first().domain)
        assertEquals(1L, health.snapshot().totalUnknownHost)
    }

    @Test
    fun lowerLatencyHealthyDomainIsPreferred() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L })

        health.recordSuccess(2, false, "one.example", latencyMs = 500)
        health.recordSuccess(2, false, "two.example", latencyMs = 100)

        assertEquals("two.example", health.selectDomains(2).ordered.first().domain)
    }

    @Test
    fun cooldownDomainIsSkippedWhenAlternativesExist() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L })

        health.recordFailure(2, false, "one.example", SocketTimeoutException("timeout"), "mobile", routeSettling = false)
        val plan = health.selectDomains(2)

        assertEquals(listOf("two.example"), plan.ordered.map { it.domain })
        assertEquals(listOf("one.example"), plan.skippedCooldown.map { it.domain })
    }

    @Test
    fun ifAllDomainsAreCooldownSelectorStillReturnsLeastBadFallback() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L })

        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", routeSettling = false)
        health.recordFailure(2, false, "two.example", RuntimeException("HTTP 503"), "mobile", routeSettling = false)
        val plan = health.selectDomains(2)

        assertTrue(plan.allDomainsInCooldownFallback)
        assertEquals(2, plan.ordered.size)
        assertEquals("two.example", plan.ordered.first().domain)
        assertEquals(1L, health.snapshot().allDomainsInCooldownFallbacks)
    }

    @Test
    fun cfOrderingIsPerDc() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L })

        health.recordSuccess(2, false, "two.example", latencyMs = 100)

        assertEquals("two.example", health.selectDomains(2).ordered.first().domain)
        assertEquals("one.example", health.selectDomains(4).ordered.first().domain)
    }

    @Test
    fun diagnosticsSnapshotIncludesCfHealthCounters() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L })

        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", routeSettling = false)
        health.recordFailure(2, false, "two.example", RuntimeException("HTTP 503"), "mobile", routeSettling = false)
        health.recordFailure(4, false, "one.example", UnknownHostException("one.example"), "Wi-Fi", routeSettling = false)
        health.recordFailure(4, false, "two.example", SocketTimeoutException("timeout"), "Wi-Fi", routeSettling = false)
        val snapshot = health.snapshot()

        assertTrue(snapshot.enabled)
        assertEquals(2, snapshot.domainsTotal)
        assertEquals(4, snapshot.domainsInCooldown)
        assertEquals(1L, snapshot.total429)
        assertEquals(1L, snapshot.total503)
        assertEquals(1L, snapshot.totalUnknownHost)
        assertEquals(1L, snapshot.totalTimeouts)
    }
}
