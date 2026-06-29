package com.flowseal.tgwsandroid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
                "notelega.co.uk",
                "ebally.co.uk",
                "nebally.co.uk",
                "havegreatday.co.uk",
                "pomogite.co.uk",
                "fixtelega.co.uk",
                "sadnews.co.uk",
                "onedaychamp.co.uk",
                "stopblocking.co.uk",
                "nothingthere.co.uk",
            ),
            CfProxyDomains.defaults,
        )
        assertEquals("example.org", CfProxyDomains.decodeDefaultDomain("example.org"))
    }

    @Test
    fun defaultCfProxyDomainsIncludeUpstreamV181Domains() {
        assertTrue(
            CfProxyDomains.defaults.containsAll(
                listOf(
                    "fixtelega.co.uk",
                    "sadnews.co.uk",
                    "onedaychamp.co.uk",
                    "stopblocking.co.uk",
                    "nothingthere.co.uk",
                ),
            ),
        )
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

        val decision = health.recordFailure(
            2,
            false,
            "one.example",
            RuntimeException("HTTP 429"),
            "mobile",
            routeSettling = false,
        )
        val plan = health.selectDomains(2)

        assertTrue(decision.cooldownUntilMs > now)
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
    fun ifAllDomainsAreCooldownSelectorReturnsSingleLeastBadFallback() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L })

        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", routeSettling = false)
        health.recordFailure(2, false, "two.example", RuntimeException("HTTP 503"), "mobile", routeSettling = false)
        val plan = health.selectDomains(2)

        assertTrue(plan.allDomainsInCooldownFallback)
        assertEquals(1, plan.ordered.size)
        assertEquals("two.example", plan.ordered.first().domain)
        assertEquals(1L, health.snapshot().allDomainsInCooldownFallbacks)
        assertEquals(1L, health.snapshot().allCooldownSingleAttempts)
    }

    @Test
    fun allCooldownSelectorPerformsOnlyOneLeastBadAttemptPerFallbackCycle() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L }, jitterRatio = { 0.0 })
        val cycleState = CfDomainFallbackCycleState()
        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", routeSettling = false)
        health.recordFailure(2, false, "two.example", RuntimeException("HTTP 503"), "mobile", routeSettling = false)

        val first = health.selectDomains(2, cycleState = cycleState)
        val second = health.selectDomains(2, cycleState = cycleState)

        assertTrue(first.allDomainsInCooldownFallback)
        assertEquals(1, first.ordered.size)
        assertFalse(second.allDomainsInCooldownFallback)
        assertTrue(second.allDomainsInCooldownStoppedCycle)
        assertTrue(second.ordered.isEmpty())
        assertEquals(1L, health.snapshot().allCooldownSingleAttempts)
        assertEquals(1L, health.snapshot().allCooldownStoppedCycles)
    }

    @Test
    fun afterAllCooldownSingleAttemptFailsFallbackCycleStops() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L }, jitterRatio = { 0.0 })
        val cycleState = CfDomainFallbackCycleState()
        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", routeSettling = false)
        health.recordFailure(2, false, "two.example", RuntimeException("HTTP 503"), "mobile", routeSettling = false)

        val first = health.selectDomains(2, cycleState = cycleState)
        health.recordAllCooldownSingleAttemptFailure()
        val second = health.selectDomains(2, cycleState = cycleState)

        assertTrue(first.allDomainsInCooldownFallback)
        assertTrue(second.ordered.isEmpty())
        assertTrue(second.allDomainsInCooldownStoppedCycle)
        assertEquals(1L, health.snapshot().allCooldownSingleAttemptFailures)
        assertEquals(2L, health.snapshot().allCooldownStoppedCycles)
    }


    @Test
    fun allCooldownCircuitSuppressesRepeatedLeastBadAttemptsWithinWindow() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { now }, jitterRatio = { 0.0 })
        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        health.recordFailure(2, false, "two.example", RuntimeException("HTTP 503"), "mobile", false)

        val first = health.selectDomains(2)
        val second = health.selectDomains(2)

        assertTrue(first.allDomainsInCooldownFallback)
        assertTrue(first.allDomainsInCooldownCircuitOpened)
        assertEquals(1, first.ordered.size)
        assertTrue(second.allDomainsInCooldownCircuitSuppressed)
        assertTrue(second.ordered.isEmpty())
        val snapshot = health.snapshot()
        assertEquals(1L, snapshot.allCooldownAttemptsAllowed)
        assertEquals(1L, snapshot.allCooldownAttemptsSuppressed)
        assertEquals(1L, snapshot.allCooldownControlledFailures)
        assertEquals(1L, snapshot.allCooldownCircuitOpenCount)
        assertEquals(1_000L + CfDomainHealth.ALL_COOLDOWN_SINGLE_ATTEMPT_WINDOW_MS, snapshot.allCooldownCircuitOpenByDc[2])
    }

    @Test
    fun allCooldownCircuitAllowsNewAttemptAfterWindow() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { now }, jitterRatio = { 0.0 })
        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        health.recordFailure(2, false, "two.example", RuntimeException("HTTP 503"), "mobile", false)

        assertTrue(health.selectDomains(2).allDomainsInCooldownFallback)
        now += CfDomainHealth.ALL_COOLDOWN_SINGLE_ATTEMPT_WINDOW_MS + 1
        val second = health.selectDomains(2)

        assertTrue(second.allDomainsInCooldownFallback)
        assertEquals(2L, health.snapshot().allCooldownAttemptsAllowed)
    }

    @Test
    fun allCooldownCircuitResetsAfterCfSuccess() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { now }, jitterRatio = { 0.0 })
        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        health.recordFailure(2, false, "two.example", RuntimeException("HTTP 503"), "mobile", false)
        assertTrue(health.selectDomains(2).allDomainsInCooldownFallback)

        health.recordSuccess(2, false, "one.example", latencyMs = 100)

        assertFalse(health.snapshot().allCooldownCircuitOpenByDc.containsKey(2))
        assertEquals("one.example", health.selectDomains(2).ordered.first().domain)
    }

    @Test
    fun allCooldownCircuitIsPerDcNotGlobal() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L }, jitterRatio = { 0.0 })
        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        health.recordFailure(2, false, "two.example", RuntimeException("HTTP 503"), "mobile", false)
        health.recordFailure(4, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        health.recordFailure(4, false, "two.example", RuntimeException("HTTP 503"), "mobile", false)

        val dc2 = health.selectDomains(2)
        val dc4 = health.selectDomains(4)
        val dc2Suppressed = health.selectDomains(2)

        assertTrue(dc2.allDomainsInCooldownFallback)
        assertTrue(dc4.allDomainsInCooldownFallback)
        assertTrue(dc2Suppressed.allDomainsInCooldownCircuitSuppressed)
        assertEquals(2L, health.snapshot().allCooldownAttemptsAllowed)
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


    @Test
    fun unknownHostWhileNetworkNoneDoesNotCreateCooldown() {
        val health = CfDomainHealth(listOf("one.example"), nowMs = { 1_000L })

        val decision = health.recordFailure(2, false, "one.example", UnknownHostException("one.example"), "none", routeSettling = false)
        val snapshot = health.snapshot()

        assertFalse(decision.counted)
        assertEquals(0, snapshot.domainsInCooldown)
        assertEquals(1L, snapshot.transientNetworkFailures)
        assertEquals(1L, snapshot.cooldownsSkippedBecauseNetworkSettling)
    }

    @Test
    fun timeoutWhileRouteSettlingDoesNotCreateCooldown() {
        val health = CfDomainHealth(listOf("one.example"), nowMs = { 1_000L })

        val decision = health.recordFailure(2, false, "one.example", SocketTimeoutException("timeout"), "mobile", routeSettling = true)

        assertFalse(decision.counted)
        assertEquals(0, health.snapshot().domainsInCooldown)
        assertEquals(1L, health.snapshot().cooldownsSkippedBecauseNetworkSettling)
    }

    @Test
    fun networkGenerationChangedFailureDoesNotPoisonDomainHealth() {
        val health = CfDomainHealth(listOf("one.example"), nowMs = { 1_000L })

        val decision = health.recordFailure(
            2,
            false,
            "one.example",
            UnknownHostException("one.example"),
            "mobile",
            routeSettling = false,
            networkGenerationChanged = true,
        )

        assertFalse(decision.counted)
        assertEquals(0, health.snapshot().domainsInCooldown)
        assertEquals(1L, health.snapshot().failuresIgnoredBecauseNetworkChanged)
    }

    @Test
    fun clearTransientNetworkCooldownsPreservesHttp429() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { now }, jitterRatio = { 0.0 })
        health.recordFailure(2, false, "one.example", UnknownHostException("one.example"), "mobile", routeSettling = false)
        health.recordFailure(2, false, "two.example", RuntimeException("HTTP 429"), "mobile", routeSettling = false)
        now = 2_000L

        val cleared = health.clearTransientNetworkCooldowns()
        val rows = health.snapshot().domains.associateBy { it.domain }

        assertEquals(1, cleared)
        assertEquals(0L, rows.getValue("one.example").cooldownUntilMs)
        assertTrue(rows.getValue("two.example").cooldownUntilMs > 0L)
        assertEquals(1L, health.snapshot().transientCooldownsClearedOnNetworkAvailable)
    }

    @Test
    fun selectorSkipsInflightDomainWhenAlternativeExists() {
        val health = CfDomainHealth(listOf("one.example", "two.example"))
        assertTrue(health.acquireConnect(2, false, "one.example", waitMs = 1))

        val plan = health.selectDomains(2)

        assertEquals(listOf("two.example"), plan.ordered.map { it.domain })
        assertEquals(listOf("one.example"), plan.skippedInflight.map { it.domain })
        assertEquals(1L, health.snapshot().inflightSkips)
        health.releaseConnect(2, false, "one.example")
    }

    @Test
    fun inflightDomainCanBeSelectedOnlyWhenAllAlternativesUnavailable() {
        val health = CfDomainHealth(listOf("one.example"))
        assertTrue(health.acquireConnect(2, false, "one.example", waitMs = 1))

        val plan = health.selectDomains(2)

        assertEquals(listOf("one.example"), plan.ordered.map { it.domain })
        assertEquals("inflight_least_bad", plan.ordered.first().reason)
        assertTrue(health.snapshot().maxInflightPerDomainReached > 0L)
        health.releaseConnect(2, false, "one.example")
    }

    @Test
    fun perDcConcurrencyLimitPreventsMoreThanConfiguredActiveCfAttempts() {
        val health = CfDomainHealth(
            listOf("one.example", "two.example", "three.example"),
            maxConcurrentConnectsForDc = { 2 },
        )
        assertTrue(health.acquireConnect(2, false, "one.example", waitMs = 1))
        assertTrue(health.acquireConnect(2, false, "two.example", waitMs = 1))

        assertFalse(health.acquireConnect(2, false, "three.example", waitMs = 10))

        val snapshot = health.snapshot()
        assertEquals(2, snapshot.activeConnectsByDc[2])
        assertEquals(2, snapshot.maxConcurrentConnectsByDc[2])
        assertEquals(1L, snapshot.connectQueueTimeouts)
        health.releaseConnect(2, false, "one.example")
        health.releaseConnect(2, false, "two.example")
    }

    @Test
    fun http429AppliesExponentialBackoff() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example"), nowMs = { now }, jitterRatio = { 0.0 })

        val first = health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        now = first.cooldownUntilMs + 1
        val second = health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        now = second.cooldownUntilMs + 1
        val third = health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        now = third.cooldownUntilMs + 1
        val fourth = health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)

        assertEquals(1L, first.backoffLevel)
        assertEquals(30_000L, first.cooldownUntilMs - 1_000L)
        assertEquals(2L, second.backoffLevel)
        assertEquals(60_000L, second.cooldownUntilMs - (first.cooldownUntilMs + 1))
        assertEquals(3L, third.backoffLevel)
        assertEquals(120_000L, third.cooldownUntilMs - (second.cooldownUntilMs + 1))
        assertEquals(4L, fourth.backoffLevel)
        assertEquals(300_000L, fourth.cooldownUntilMs - (third.cooldownUntilMs + 1))
    }

    @Test
    fun successDecrementsConsecutive429Backoff() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example"), nowMs = { now }, jitterRatio = { 0.0 })
        val first = health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        now = first.cooldownUntilMs + 1
        val second = health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        now = second.cooldownUntilMs + 1

        health.recordSuccess(2, false, "one.example", latencyMs = 100)

        val row = health.snapshot().domains.first()
        assertEquals(1L, row.consecutive429)
        assertEquals(1L, row.backoffLevel)
        assertEquals(1L, row.successfulStreak)
    }

    @Test
    fun multipleSuccessStreakReduces429BackoffFurther() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example"), nowMs = { now }, jitterRatio = { 0.0 })
        val first = health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        now = first.cooldownUntilMs + 1
        val second = health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        now = second.cooldownUntilMs + 1
        val third = health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        now = third.cooldownUntilMs + 1

        health.recordSuccess(2, false, "one.example", latencyMs = 100)
        health.recordSuccess(2, false, "one.example", latencyMs = 100)
        health.recordSuccess(2, false, "one.example", latencyMs = 100)

        val row = health.snapshot().domains.first()
        assertEquals(0L, row.consecutive429)
        assertEquals(0L, row.backoffLevel)
        assertEquals(3L, row.successfulStreak)
    }

    @Test
    fun jitterDoesNotMakeCooldownNegativeOrZero() {
        val health = CfDomainHealth(listOf("one.example"), nowMs = { 1_000L }, jitterRatio = { -100.0 })

        val decision = health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)

        assertTrue(decision.cooldownUntilMs > 1_000L)
    }

    @Test
    fun allCooldownStateWaitsForSoonestCooldownWhenSoonEnough() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { now }, jitterRatio = { 0.0 })
        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        health.recordFailure(2, false, "two.example", RuntimeException("HTTP 503"), "mobile", false)
        now = 30_700L

        val plan = health.selectDomains(2)

        assertTrue(plan.ordered.isEmpty())
        assertEquals(325L, plan.allDomainsInCooldownWaitMs)
        assertEquals(1L, health.snapshot().allCooldownWaits)
        assertEquals(325L, health.snapshot().allCooldownWaitMs)
    }

    @Test
    fun noWaitIfNearestCooldownIsTooFarThenSingleLeastBadAttempt() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L }, jitterRatio = { 0.0 })
        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        health.recordFailure(2, false, "two.example", RuntimeException("HTTP 503"), "mobile", false)

        val plan = health.selectDomains(2)

        assertTrue(plan.allDomainsInCooldownFallback)
        assertEquals(1, plan.ordered.size)
        assertEquals("two.example", plan.ordered.first().domain)
        assertEquals(0L, health.snapshot().allCooldownWaits)
        assertEquals(0L, health.snapshot().allCooldownWaitMs)
    }

    @Test
    fun queueLimitTimeoutCreatesControlledFailureNotDomainStorm() {
        val health = CfDomainHealth(
            listOf("one.example", "two.example", "three.example"),
            maxConcurrentConnectsForDc = { 1 },
        )
        assertTrue(health.acquireConnect(2, false, "one.example", waitMs = 1))

        val result = health.acquireConnectDecision(2, false, "two.example", waitMs = 10)

        assertEquals(CfConnectAcquireResult.QUEUE_TIMEOUT, result)
        val snapshot = health.snapshot()
        assertEquals(1L, snapshot.connectQueueTimeouts)
        assertEquals(1L, snapshot.queueControlledFailures)
        assertTrue(snapshot.queueWaitMs >= 0L)
        assertEquals(1, snapshot.activeConnectsByDc[2])
        health.releaseConnect(2, false, "one.example")
    }

    @Test
    fun concurrentAttemptsReleaseInflightMarkerOnSuccess() {
        val health = CfDomainHealth(listOf("one.example", "two.example"))
        assertTrue(health.acquireConnect(2, false, "one.example", waitMs = 1))
        health.releaseConnect(2, false, "one.example")

        assertEquals("one.example", health.selectDomains(2).ordered.first().domain)
        assertTrue(health.snapshot().activeConnectsByDc.isEmpty())
    }

    @Test
    fun concurrentAttemptsReleaseInflightMarkerOnFailure() {
        val health = CfDomainHealth(listOf("one.example", "two.example"))
        assertTrue(health.acquireConnect(2, false, "one.example", waitMs = 1))
        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 503"), "mobile", false)
        health.releaseConnect(2, false, "one.example")

        assertEquals(listOf("two.example"), health.selectDomains(2).ordered.map { it.domain })
        assertTrue(health.snapshot().activeConnectsByDc.isEmpty())
    }

    @Test
    fun releaseWakesWaitingConnectAttempt() {
        val health = CfDomainHealth(listOf("one.example"), maxConcurrentConnectsForDc = { 1 })
        val started = CountDownLatch(1)
        val acquiredAfterRelease = AtomicBoolean(false)
        assertTrue(health.acquireConnect(2, false, "one.example", waitMs = 1))

        val waiter = Thread {
            started.countDown()
            acquiredAfterRelease.set(health.acquireConnect(2, false, "one.example", waitMs = 500))
            if (acquiredAfterRelease.get()) health.releaseConnect(2, false, "one.example")
        }
        waiter.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        Thread.sleep(50)
        health.releaseConnect(2, false, "one.example")
        waiter.join(1_000)

        assertTrue(acquiredAfterRelease.get())
        assertTrue(health.snapshot().activeConnectsByDc.isEmpty())
    }


    @Test
    fun pressureRecentFailuresMoveDcToDegradedThenSaturated() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { now }, jitterRatio = { 0.0 })

        repeat(5) { index ->
            health.recordFailure(2, false, if (index % 2 == 0) "one.example" else "two.example", RuntimeException("HTTP 429"), "mobile", false)
            now += 1
        }
        assertEquals(CfPressureLevel.DEGRADED, health.beginPressureManagedCycle(2, "mobile").level)

        repeat(3) { index ->
            health.recordFailure(2, false, if (index % 2 == 0) "one.example" else "two.example", SocketTimeoutException("timeout"), "mobile", false)
            now += 1
        }

        val saturated = health.beginPressureManagedCycle(2, "mobile")
        assertEquals(CfPressureLevel.SATURATED, saturated.level)
        assertTrue(saturated.probeAllowed)
        val snapshot = health.snapshot().pressure
        assertEquals("saturated", snapshot.levelByDc[2])
        assertEquals(5L, snapshot.recent429ByDc[2])
        assertEquals(3L, snapshot.recentTimeoutByDc[2])
    }



    @Test
    fun allCooldownHighSuppressionClassifiesExhaustedDespiteRecentSuccess() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example"), nowMs = { now }, jitterRatio = { 0.0 })
        health.recordSuccess(2, false, "one.example", latencyMs = 80)
        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)

        health.selectDomains(2)
        repeat(3) { health.selectDomains(2) }

        val decision = health.beginPressureManagedCycle(2, "mobile")
        val snapshot = health.snapshot().pressure
        assertEquals(CfPressureLevel.EXHAUSTED, decision.level)
        assertEquals("exhausted", snapshot.levelByDc[2])
        assertEquals("all_domains_cooldown/all_cooldown_suppressed", snapshot.reasonByDc[2])
        assertTrue((snapshot.allDomainsCooldownByDc[2] ?: 0L) >= 1L)
        assertTrue((snapshot.recentAllCooldownSuppressedByDc[2] ?: 0L) >= 3L)
    }

    @Test
    fun pressureNormalKeepsCfSelectionBehavior() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L })
        health.recordSuccess(2, false, "two.example", latencyMs = 120)

        val decision = health.beginPressureManagedCycle(2, "mobile")
        val plan = health.selectDomains(2)

        assertEquals(CfPressureLevel.NORMAL, decision.level)
        assertEquals(Int.MAX_VALUE, decision.maxAttempts)
        assertEquals("two.example", plan.ordered.first().domain)
    }

    @Test
    fun pressureDegradedLimitsMobileAttemptsPerClientCycle() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { now }, jitterRatio = { 0.0 })
        health.recordSuccess(2, false, "one.example", latencyMs = 90)
        repeat(5) {
            now += 1
            health.recordFailure(2, false, "two.example", RuntimeException("HTTP 429"), "mobile", false)
        }

        val mobile = health.beginPressureManagedCycle(2, "mobile")
        val wifi = health.beginPressureManagedCycle(2, "Wi-Fi")

        assertEquals(CfPressureLevel.DEGRADED, mobile.level)
        assertEquals(CfDomainHealth.DEGRADED_MOBILE_MAX_ATTEMPTS_PER_CYCLE, mobile.maxAttempts)
        assertEquals(CfDomainHealth.DEGRADED_DEFAULT_MAX_ATTEMPTS_PER_CYCLE, wifi.maxAttempts)
        assertEquals(CfDomainHealth.DEGRADED_CONNECT_QUEUE_WAIT_MS, mobile.connectQueueWaitMs)
    }

    @Test
    fun pressureSaturatedSuppressesNormalConnectsAndAllowsOneProbePerWindow() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { now }, jitterRatio = { 0.0 })
        repeat(8) { index ->
            health.recordFailure(2, false, if (index % 2 == 0) "one.example" else "two.example", RuntimeException("HTTP 429"), "mobile", false)
            now += 1
        }

        val probe = health.beginPressureManagedCycle(2, "mobile")
        val suppressed = health.beginPressureManagedCycle(2, "mobile")
        now = probe.nextProbeAtMs + 1
        val nextProbe = health.beginPressureManagedCycle(2, "mobile")

        assertEquals(CfPressureLevel.SATURATED, probe.level)
        assertTrue(probe.probeAllowed)
        assertFalse(probe.controlledFailure)
        assertEquals(1, probe.maxAttempts)
        assertTrue(suppressed.controlledFailure)
        assertFalse(suppressed.probeAllowed)
        assertTrue(nextProbe.probeAllowed)
        val snapshot = health.snapshot().pressure
        assertEquals(2L, snapshot.probeAllowed)
        assertEquals(1L, snapshot.probeSuppressed)
        assertEquals(1L, snapshot.controlledFailures)
    }

    @Test
    fun pressureProbeSuccessRelaxesSaturatedDcButProbeFailureDelaysNextProbe() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { now }, jitterRatio = { 0.0 })
        repeat(8) { index ->
            health.recordFailure(2, false, if (index % 2 == 0) "one.example" else "two.example", RuntimeException("HTTP 429"), "mobile", false)
            now += 1
        }

        val firstProbe = health.beginPressureManagedCycle(2, "mobile")
        health.recordFailure(2, false, "one.example", SocketTimeoutException("timeout"), "mobile", false)
        val delayed = health.beginPressureManagedCycle(2, "mobile")
        now = firstProbe.nextProbeAtMs + 1
        assertTrue(health.beginPressureManagedCycle(2, "mobile").probeAllowed)
        health.recordSuccess(2, false, "one.example", latencyMs = 80)
        val relaxed = health.beginPressureManagedCycle(2, "mobile")

        assertTrue(firstProbe.probeAllowed)
        assertTrue(delayed.controlledFailure)
        assertTrue(relaxed.level == CfPressureLevel.DEGRADED || relaxed.level == CfPressureLevel.NORMAL)
        assertFalse(relaxed.controlledFailure)
    }

    @Test
    fun pressureStateIsPerDcNotGlobal() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L }, jitterRatio = { 0.0 })
        repeat(8) { index ->
            health.recordFailure(2, false, if (index % 2 == 0) "one.example" else "two.example", RuntimeException("HTTP 429"), "mobile", false)
        }

        val dc2 = health.beginPressureManagedCycle(2, "mobile")
        val dc4 = health.beginPressureManagedCycle(4, "mobile")

        assertEquals(CfPressureLevel.SATURATED, dc2.level)
        assertEquals(CfPressureLevel.NORMAL, dc4.level)
        assertEquals("saturated", health.snapshot().pressure.levelByDc[2])
        assertEquals("normal", health.snapshot().pressure.levelByDc[4])
    }

    @Test
    fun mobileNetworkGenerationResetClearsCfPressureForFreshProbe() {
        val health = CfDomainHealth(listOf("one.example", "two.example"), nowMs = { 1_000L }, jitterRatio = { 0.0 })
        repeat(20) { health.recordPressureLimitedAttempt(2) }

        val pressured = health.beginPressureManagedCycle(2, "mobile")
        assertTrue(pressured.level >= CfPressureLevel.SATURATED)

        assertEquals(1, health.resetPressureForMobileNetworkGenerationChange())
        val afterReset = health.beginPressureManagedCycle(2, "mobile")

        assertFalse(afterReset.controlledFailure)
        assertEquals(CfPressureLevel.NORMAL, afterReset.level)
    }

    @Test
    fun inFlightDcWaitReturnsWhenSameDcConnectCompletes() {
        val health = CfDomainHealth(listOf("one.example"), maxConcurrentConnectsForDc = { 1 })
        assertEquals(CfConnectAcquireResult.ACQUIRED, health.acquireConnectDecision(2, false, "one.example", 0L))
        assertTrue(health.hasInFlightConnectsForDc(2))

        val waitResult = AtomicReference<Boolean>()
        val waiter = Thread { waitResult.set(health.waitForInFlightConnectReleaseForDc(2, 1_000L)) }
        waiter.start()
        Thread.sleep(50L)
        health.releaseConnect(2, false, "one.example")
        waiter.join(1_000L)

        assertFalse(waiter.isAlive)
        assertEquals(true, waitResult.get())
        assertFalse(health.hasInFlightConnectsForDc(2))
    }

    @Test
    fun inFlightDcWaitRemainsBoundedWhenConnectDoesNotComplete() {
        val health = CfDomainHealth(listOf("one.example"), maxConcurrentConnectsForDc = { 1 })
        assertEquals(CfConnectAcquireResult.ACQUIRED, health.acquireConnectDecision(2, false, "one.example", 0L))

        val started = System.nanoTime()
        val completed = health.waitForInFlightConnectReleaseForDc(2, 75L)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertFalse(completed)
        assertTrue("wait should be bounded, elapsedMs=$elapsedMs", elapsedMs < 1_000L)
        assertTrue(health.hasInFlightConnectsForDc(2))
        health.releaseConnect(2, false, "one.example")
    }

    @Test
    fun inFlightDcWaitDoesNotChange429BackoffState() {
        var now = 1_000L
        val health = CfDomainHealth(listOf("one.example"), nowMs = { now }, jitterRatio = { 0.0 }, maxConcurrentConnectsForDc = { 1 })
        health.recordFailure(2, false, "one.example", RuntimeException("HTTP 429"), "mobile", false)
        val before = health.snapshot().backoffCount
        assertEquals(CfConnectAcquireResult.ACQUIRED, health.acquireConnectDecision(2, false, "one.example", 0L))

        now += 100L
        assertFalse(health.waitForInFlightConnectReleaseForDc(2, 1L))

        assertEquals(before, health.snapshot().backoffCount)
        health.releaseConnect(2, false, "one.example")
    }

}
