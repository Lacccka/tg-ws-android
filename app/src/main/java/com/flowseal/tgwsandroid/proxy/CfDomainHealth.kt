package com.flowseal.tgwsandroid.proxy

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.ArrayDeque
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max

/** In-memory, per-DC Cloudflare proxy domain health and ordering state. */
class CfDomainHealth(
    domains: List<String> = CfProxyDomains.defaults,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val jitterRatio: () -> Double = { ThreadLocalRandom.current().nextDouble(-HTTP_429_JITTER_RATIO, HTTP_429_JITTER_RATIO) },
    private val maxConcurrentConnectsForDc: (Int) -> Int = { DEFAULT_MAX_CONCURRENT_CF_CONNECTS_PER_DC },
) {
    private var domains: List<String> = CfProxyDomains.normalize(domains)
    private val states = mutableMapOf<CfDomainKey, MutableCfDomainState>()
    private var lastSelectedDomain: String? = null
    private var lastSelectedReason: String? = null
    private var lastConnectLatencyMs: Long? = null
    private var cooldownSkips: Long = 0
    private var allDomainsInCooldownFallbacks: Long = 0
    private var inflightSkips: Long = 0
    private var inflightWaits: Long = 0
    private var maxInflightPerDomainReached: Long = 0
    private var connectQueueWaits: Long = 0
    private var connectQueueTimeouts: Long = 0
    private var queueControlledFailures: Long = 0
    private var queueWaitMs: Long = 0
    private var allCooldownWaits: Long = 0
    private var allCooldownWaitMs: Long = 0
    private var allCooldownCircuitOpenCount: Long = 0
    private var allCooldownAttemptsAllowed: Long = 0
    private var allCooldownAttemptsSuppressed: Long = 0
    private var allCooldownControlledFailures: Long = 0
    private var allCooldownSingleAttempts: Long = 0
    private var allCooldownSingleAttemptFailures: Long = 0
    private var allCooldownStoppedCycles: Long = 0
    private var transientNetworkFailures: Long = 0
    private var failuresIgnoredBecauseNetworkChanged: Long = 0
    private var cooldownsSkippedBecauseNetworkSettling: Long = 0
    private var transientCooldownsClearedOnNetworkAvailable: Long = 0
    private var mobileRecoveryProbeWindowUntilMs: Long = 0
    @Suppress("unused") private var mobileRecoveryProbeWindowGeneration: Long = 0
    private val mobileRecoveryProbeStartsByDc = mutableMapOf<Int, Int>()
    private val activeConnectsByDc = mutableMapOf<Int, Int>()
    private val maxConcurrentConnectsByDc = mutableMapOf<Int, Int>()
    private val inFlightByDomain = mutableMapOf<CfDomainKey, Int>()
    private val allCooldownCircuitByDc = mutableMapOf<Int, MutableCfAllCooldownCircuitState>()
    private val pressureByDc = mutableMapOf<Int, MutableCfPressureState>()
    private val pendingPressureLevelChanges = ArrayDeque<CfPressureLevelChange>()
    private var pressureProbeAllowed: Long = 0
    private var pressureProbeSuppressed: Long = 0
    private var pressureControlledFailures: Long = 0
    private var pressureLimitedAttempts: Long = 0
    private var pressureLevelChanges: Long = 0

    @Synchronized
    fun updateDomainsList(domainsList: List<String>) {
        val normalized = CfProxyDomains.normalize(domainsList)
        if (domains == normalized) return
        domains = normalized
        states.keys.removeAll { it.domain !in normalized }
    }

    @Synchronized
    fun selectDomains(
        dcId: Int,
        isMedia: Boolean = false,
        cycleState: CfDomainFallbackCycleState = CfDomainFallbackCycleState(),
    ): CfDomainSelectionPlan {
        val now = nowMs()
        val activeDomains = domains
        if (activeDomains.isEmpty()) {
            return CfDomainSelectionPlan(emptyList(), emptyList(), allDomainsInCooldownFallback = false)
        }

        val entries = activeDomains.mapIndexed { index, domain ->
            val state = states[CfDomainKey(dcId, isMedia, domain)] ?: MutableCfDomainState()
            CfDomainSelection(
                dcId = dcId,
                isMedia = isMedia,
                domain = domain,
                reason = selectionReason(state, now),
                latencyMs = state.ewmaLatencyMs?.toLong(),
                inCooldown = state.cooldownUntilMs > now,
                cooldownUntilMs = state.cooldownUntilMs,
                failureScore = failureScore(state),
                originalIndex = index,
                inFlight = ((inFlightByDomain[CfDomainKey(dcId, isMedia, domain)] ?: 0) > 0),
            )
        }
        val available = entries.filterNot { it.inCooldown }
        if (available.isNotEmpty()) {
            val skippedCooldown = entries.filter { it.inCooldown }
            val skippedInflight = available.filter { it.inFlight }
            val ready = available.filterNot { it.inFlight }
            cooldownSkips += skippedCooldown.size.toLong()
            if (ready.isNotEmpty()) {
                inflightSkips += skippedInflight.size.toLong()
                return CfDomainSelectionPlan(
                    ordered = ready.sortedWith(domainComparator()),
                    skippedCooldown = skippedCooldown,
                    skippedInflight = skippedInflight,
                    allDomainsInCooldownFallback = false,
                )
            }

            maxInflightPerDomainReached += available.size.toLong()
            repeat(available.size) { recordPressureEventLocked(dcId, CfPressureEventKind.MAX_INFLIGHT, now) }
            return CfDomainSelectionPlan(
                ordered = available
                    .sortedWith(domainComparator())
                    .map { it.copy(reason = "inflight_least_bad") },
                skippedCooldown = skippedCooldown,
                skippedInflight = emptyList(),
                allDomainsInCooldownFallback = false,
            )
        }

        val soonestCooldown = entries.minOfOrNull { it.cooldownUntilMs } ?: 0L
        val waitMs = (soonestCooldown - now).coerceAtLeast(0L)
        val circuitState = allCooldownCircuitByDc.getOrPut(dcId) { MutableCfAllCooldownCircuitState() }
        if (waitMs in 1..ALL_COOLDOWN_WAIT_THRESHOLD_MS) {
            val waitWithJitterMs = waitMs + ALL_COOLDOWN_WAIT_JITTER_MS
            allCooldownWaits += 1
            allCooldownWaitMs += waitWithJitterMs
            return CfDomainSelectionPlan(
                ordered = emptyList(),
                skippedCooldown = entries.sortedWith(cooldownFallbackComparator()),
                skippedInflight = emptyList(),
                allDomainsInCooldownFallback = false,
                allDomainsInCooldownWaitMs = waitWithJitterMs,
            )
        }

        if (cycleState.allCooldownFallbackUsed) {
            allCooldownStoppedCycles += 1
            return CfDomainSelectionPlan(
                ordered = emptyList(),
                skippedCooldown = entries.sortedWith(cooldownFallbackComparator()),
                skippedInflight = emptyList(),
                allDomainsInCooldownFallback = false,
                allDomainsInCooldownStoppedCycle = true,
            )
        }

        allDomainsInCooldownFallbacks += 1
        recordPressureEventLocked(dcId, CfPressureEventKind.ALL_DOMAINS_COOLDOWN, now)
        val nextAllowedAtMs = circuitState.nextAllowedAttemptAtMs
        if (nextAllowedAtMs > now) {
            allCooldownAttemptsSuppressed += 1
            allCooldownControlledFailures += 1
            recordPressureEventLocked(dcId, CfPressureEventKind.ALL_COOLDOWN_SUPPRESSED, now)
            return CfDomainSelectionPlan(
                ordered = emptyList(),
                skippedCooldown = entries.sortedWith(cooldownFallbackComparator()),
                skippedInflight = emptyList(),
                allDomainsInCooldownFallback = false,
                allDomainsInCooldownCircuitSuppressed = true,
                allDomainsInCooldownCircuitRetryAtMs = minOf(nextAllowedAtMs, soonestCooldown.takeIf { it > now } ?: nextAllowedAtMs),
            )
        }

        cycleState.allCooldownFallbackUsed = true
        allCooldownCircuitOpenCount += 1
        allCooldownAttemptsAllowed += 1
        allCooldownSingleAttempts += 1
        val openUntilMs = minOf(now + ALL_COOLDOWN_SINGLE_ATTEMPT_WINDOW_MS, soonestCooldown.takeIf { it > now } ?: Long.MAX_VALUE)
        circuitState.nextAllowedAttemptAtMs = if (openUntilMs == Long.MAX_VALUE) now + ALL_COOLDOWN_SINGLE_ATTEMPT_WINDOW_MS else openUntilMs
        circuitState.lastOpenedAtMs = now
        return CfDomainSelectionPlan(
            ordered = entries
                .sortedWith(cooldownFallbackComparator())
                .take(1)
                .map { it.copy(reason = "all_cooldown_least_bad") },
            skippedCooldown = emptyList(),
            skippedInflight = emptyList(),
            allDomainsInCooldownFallback = true,
            allDomainsInCooldownCircuitOpened = true,
            allDomainsInCooldownCircuitRetryAtMs = circuitState.nextAllowedAttemptAtMs,
        )
    }

    @Synchronized
    fun recordSelected(dcId: Int, isMedia: Boolean, baseDomain: String, fullDomain: String, reason: String) {
        normalizeKnownDomain(baseDomain) ?: return
        lastSelectedDomain = fullDomain
        lastSelectedReason = reason
    }

    @Synchronized
    fun recordSuccess(dcId: Int, isMedia: Boolean, baseDomain: String, latencyMs: Long): Boolean {
        val normalized = normalizeKnownDomain(baseDomain) ?: return false
        val state = stateFor(dcId, isMedia, normalized)
        val now = nowMs()
        state.successes += 1
        state.successfulStreak += 1
        state.consecutiveFailures = 0
        state.consecutive429 = when {
            state.consecutive429 <= 0L -> 0L
            state.successfulStreak >= SUCCESS_STREAK_STRONG_BACKOFF_RESET -> (state.consecutive429 - SUCCESS_STREAK_STRONG_BACKOFF_DECREMENT).coerceAtLeast(0L)
            else -> (state.consecutive429 - 1L).coerceAtLeast(0L)
        }
        state.lastSuccessTimeMs = now
        state.lastLatencyMs = latencyMs
        state.ewmaLatencyMs = state.ewmaLatencyMs?.let { (it * 0.7) + (latencyMs * 0.3) } ?: latencyMs.toDouble()
        state.cooldownUntilMs = 0
        state.lastErrorKind = null
        recordPressureEventLocked(dcId, CfPressureEventKind.SUCCESS, now)
        val circuitReset = allCooldownCircuitByDc.remove(dcId) != null
        lastConnectLatencyMs = latencyMs
        return circuitReset
    }

    @Synchronized
    fun recordFailure(
        dcId: Int,
        isMedia: Boolean,
        baseDomain: String,
        error: Throwable,
        networkStatus: String,
        routeSettling: Boolean,
        networkGenerationChanged: Boolean = false,
    ): CfDomainFailureDecision {
        val normalized = normalizeKnownDomain(baseDomain) ?: return CfDomainFailureDecision(CfDomainErrorKind.OTHER, false, 0)
        val kind = classifyError(error)
        if (networkGenerationChanged) {
            failuresIgnoredBecauseNetworkChanged += 1
            return CfDomainFailureDecision(kind, counted = false, cooldownUntilMs = 0)
        }
        if (shouldIgnoreTransientNetworkError(kind, networkStatus, routeSettling)) {
            transientNetworkFailures += 1
            if (routeSettling || networkStatus.equals("none", ignoreCase = true)) cooldownsSkippedBecauseNetworkSettling += 1
            return CfDomainFailureDecision(kind, counted = false, cooldownUntilMs = 0)
        }

        val state = stateFor(dcId, isMedia, normalized)
        val now = nowMs()
        state.successfulStreak = 0
        if (kind == CfDomainErrorKind.HTTP_429) {
            state.consecutive429 += 1
        } else {
            state.consecutive429 = 0
        }
        val backoffLevel = if (kind == CfDomainErrorKind.HTTP_429) state.consecutive429 else 0
        val baseCooldownMs = cooldownMsFor(kind, backoffLevel)
        val cooldownMs = if (kind == CfDomainErrorKind.HTTP_429 && baseCooldownMs > 0) {
            (baseCooldownMs * (1.0 + jitterRatio().coerceIn(-HTTP_429_JITTER_RATIO, HTTP_429_JITTER_RATIO))).toLong().coerceAtLeast(1L)
        } else {
            baseCooldownMs
        }
        val cooldownUntil = if (cooldownMs > 0) now + cooldownMs else 0
        state.failures += 1
        state.consecutiveFailures += 1
        state.lastFailureTimeMs = now
        state.lastErrorKind = kind.configValue
        state.cooldownUntilMs = max(state.cooldownUntilMs, cooldownUntil)
        when (kind) {
            CfDomainErrorKind.HTTP_429 -> {
                state.total429 += 1
                recordPressureEventLocked(dcId, CfPressureEventKind.HTTP_429, now)
            }
            CfDomainErrorKind.HTTP_503 -> state.total503 += 1
            CfDomainErrorKind.UNKNOWN_HOST -> {
                state.totalUnknownHost += 1
                recordPressureEventLocked(dcId, CfPressureEventKind.UNKNOWN_HOST, now)
            }
            CfDomainErrorKind.TIMEOUT -> {
                state.totalTimeouts += 1
                recordPressureEventLocked(dcId, CfPressureEventKind.TIMEOUT, now)
            }
            else -> Unit
        }
        return CfDomainFailureDecision(
            kind = kind,
            counted = true,
            cooldownUntilMs = state.cooldownUntilMs,
            backoffLevel = backoffLevel,
        )
    }

    fun acquireConnect(dcId: Int, isMedia: Boolean, baseDomain: String, waitMs: Long = CONNECT_QUEUE_WAIT_MS): Boolean =
        acquireConnectDecision(dcId, isMedia, baseDomain, waitMs) == CfConnectAcquireResult.ACQUIRED

    @Synchronized
    fun acquirePrewarmConnectDecision(
        dcId: Int,
        isMedia: Boolean,
        baseDomain: String,
    ): CfConnectAcquireResult {
        val normalized = normalizeKnownDomain(baseDomain) ?: return CfConnectAcquireResult.UNAVAILABLE
        val key = CfDomainKey(dcId, isMedia, normalized)
        val state = states[key]
        if (state != null && state.cooldownUntilMs > nowMs()) return CfConnectAcquireResult.UNAVAILABLE
        if ((inFlightByDomain[key] ?: 0) > 0) return CfConnectAcquireResult.DOMAIN_IN_FLIGHT
        return acquireConnectDecision(dcId, isMedia, normalized, waitMs = 0L)
    }


    fun acquireConnectDecision(
        dcId: Int,
        isMedia: Boolean,
        baseDomain: String,
        waitMs: Long = CONNECT_QUEUE_WAIT_MS,
    ): CfConnectAcquireResult {
        val normalized = normalizeKnownDomain(baseDomain) ?: return CfConnectAcquireResult.UNAVAILABLE
        val key = CfDomainKey(dcId, isMedia, normalized)
        val startedMs = nowMs()
        val deadline = startedMs + waitMs
        var countedQueueWait = false
        var countedInflightWait = false
        synchronized(this) {
            while (true) {
                val activeForDc = activeConnectsByDc[dcId] ?: 0
                val domainInFlight = (inFlightByDomain[key] ?: 0) > 0
                if (activeForDc < maxConcurrentConnectsForDc(dcId).coerceAtLeast(1) && !domainInFlight) {
                    val nextDcActive = activeForDc + 1
                    activeConnectsByDc[dcId] = nextDcActive
                    maxConcurrentConnectsByDc[dcId] = max(maxConcurrentConnectsByDc[dcId] ?: 0, nextDcActive)
                    inFlightByDomain[key] = (inFlightByDomain[key] ?: 0) + 1
                    return CfConnectAcquireResult.ACQUIRED
                }
                if (!countedQueueWait && activeForDc >= maxConcurrentConnectsForDc(dcId).coerceAtLeast(1)) {
                    connectQueueWaits += 1
                    countedQueueWait = true
                }
                if (!countedInflightWait && domainInFlight) {
                    inflightWaits += 1
                    countedInflightWait = true
                }
                val remainingMs = deadline - nowMs()
                if (remainingMs <= 0) {
                    if (activeForDc >= maxConcurrentConnectsForDc(dcId).coerceAtLeast(1)) {
                        connectQueueTimeouts += 1
                        queueControlledFailures += 1
                        queueWaitMs += (nowMs() - startedMs).coerceAtLeast(0L)
                        recordPressureEventLocked(dcId, CfPressureEventKind.QUEUE_FAILURE, nowMs())
                        return CfConnectAcquireResult.QUEUE_TIMEOUT
                    }
                    if (domainInFlight) {
                        maxInflightPerDomainReached += 1
                        recordPressureEventLocked(dcId, CfPressureEventKind.MAX_INFLIGHT, nowMs())
                        return CfConnectAcquireResult.DOMAIN_IN_FLIGHT
                    }
                    return CfConnectAcquireResult.UNAVAILABLE
                }
                try {
                    (this as java.lang.Object).wait(remainingMs.coerceAtMost(waitMs).coerceAtLeast(1L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    connectQueueTimeouts += 1
                    queueControlledFailures += 1
                    queueWaitMs += (nowMs() - startedMs).coerceAtLeast(0L)
                    recordPressureEventLocked(dcId, CfPressureEventKind.QUEUE_FAILURE, nowMs())
                    return CfConnectAcquireResult.QUEUE_TIMEOUT
                }
            }
        }
    }

    @Synchronized
    fun releaseConnect(dcId: Int, isMedia: Boolean, baseDomain: String) {
        val normalized = normalizeKnownDomain(baseDomain) ?: return
        val key = CfDomainKey(dcId, isMedia, normalized)
        val domainCount = ((inFlightByDomain[key] ?: 0) - 1).coerceAtLeast(0)
        if (domainCount == 0) {
            inFlightByDomain.remove(key)
        } else {
            inFlightByDomain[key] = domainCount
        }
        val dcCount = ((activeConnectsByDc[dcId] ?: 0) - 1).coerceAtLeast(0)
        if (dcCount == 0) {
            activeConnectsByDc.remove(dcId)
        } else {
            activeConnectsByDc[dcId] = dcCount
        }
        (this as java.lang.Object).notifyAll()
    }

    @Synchronized
    fun hasInFlightConnectsForDc(dcId: Int): Boolean = (activeConnectsByDc[dcId] ?: 0) > 0

    fun waitForInFlightConnectReleaseForDc(dcId: Int, waitMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + waitMs.coerceAtLeast(0L)
        synchronized(this) {
            val initial = activeConnectsByDc[dcId] ?: 0
            if (initial <= 0) return false
            while ((activeConnectsByDc[dcId] ?: 0) >= initial) {
                val remainingMs = deadline - System.currentTimeMillis()
                if (remainingMs <= 0) return false
                try {
                    (this as java.lang.Object).wait(remainingMs.coerceAtLeast(1L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
        }
    }

    @Synchronized
    fun recordTransientNetworkFailure() {
        transientNetworkFailures += 1
    }

    @Synchronized
    fun recordFailureIgnoredBecauseNetworkChanged() {
        failuresIgnoredBecauseNetworkChanged += 1
    }

    @Synchronized
    fun clearTransientNetworkCooldowns(): Int {
        val now = nowMs()
        var cleared = 0
        for (state in states.values) {
            if (state.cooldownUntilMs > now && state.lastErrorKind in TRANSIENT_NETWORK_ERROR_VALUES) {
                state.cooldownUntilMs = 0
                state.lastErrorKind = null
                state.consecutiveFailures = 0
                cleared += 1
            }
        }
        if (cleared > 0) transientCooldownsClearedOnNetworkAvailable += cleared.toLong()
        return cleared
    }

    @Synchronized
    fun recordAllCooldownSingleAttemptFailure() {
        allCooldownSingleAttemptFailures += 1
        allCooldownStoppedCycles += 1
    }


    @Synchronized
    fun resetPressureForMobileNetworkGenerationChange(): Int {
        val count = pressureByDc.size
        pressureByDc.clear()
        allCooldownCircuitByDc.clear()
        return count
    }

    @Synchronized
    fun startMobileRecoveryProbeWindow(generation: Long) {
        mobileRecoveryProbeWindowGeneration = generation
        mobileRecoveryProbeWindowUntilMs = nowMs() + MOBILE_RECOVERY_PROBE_WINDOW_MS
        mobileRecoveryProbeStartsByDc.clear()
    }

    @Synchronized
    fun beginPressureManagedCycle(dcId: Int, networkStatus: String): CfPressureDecision {
        val now = nowMs()
        val state = pressureStateFor(dcId)
        val counts = pressureCountsLocked(dcId, now)
        val level = evaluatePressureLevel(counts)
        updatePressureLevelLocked(dcId, state, level, now)
        if ((networkStatus.equals("mobile", ignoreCase = true) || networkStatus.equals("cellular", ignoreCase = true)) &&
            now <= mobileRecoveryProbeWindowUntilMs &&
            (mobileRecoveryProbeStartsByDc[dcId] ?: 0) < MOBILE_RECOVERY_PROBE_MAX_STARTS_PER_DC
        ) {
            mobileRecoveryProbeStartsByDc[dcId] = (mobileRecoveryProbeStartsByDc[dcId] ?: 0) + 1
            pressureLimitedAttempts += 1
            return CfPressureDecision(
                level = level,
                maxAttempts = MOBILE_RECOVERY_PROBE_MAX_ATTEMPTS_PER_CYCLE,
                connectQueueWaitMs = CONNECT_QUEUE_WAIT_MS,
                probeAllowed = true,
                nextProbeAtMs = mobileRecoveryProbeWindowUntilMs,
            )
        }
        return when (level) {
            CfPressureLevel.NORMAL -> CfPressureDecision(level = level, maxAttempts = Int.MAX_VALUE, connectQueueWaitMs = CONNECT_QUEUE_WAIT_MS)
            CfPressureLevel.DEGRADED -> {
                pressureLimitedAttempts += 1
                CfPressureDecision(
                    level = level,
                    maxAttempts = if (networkStatus.equals("mobile", ignoreCase = true) || networkStatus.equals("cellular", ignoreCase = true)) {
                        DEGRADED_MOBILE_MAX_ATTEMPTS_PER_CYCLE
                    } else {
                        DEGRADED_DEFAULT_MAX_ATTEMPTS_PER_CYCLE
                    },
                    connectQueueWaitMs = DEGRADED_CONNECT_QUEUE_WAIT_MS,
                )
            }
            CfPressureLevel.SATURATED -> {
                if (state.nextProbeAtMs <= now) {
                    state.nextProbeAtMs = now + SATURATED_PROBE_WINDOW_MS
                    pressureProbeAllowed += 1
                    pressureLimitedAttempts += 1
                    CfPressureDecision(
                        level = level,
                        maxAttempts = 1,
                        connectQueueWaitMs = SATURATED_CONNECT_QUEUE_WAIT_MS,
                        probeAllowed = true,
                        nextProbeAtMs = state.nextProbeAtMs,
                    )
                } else {
                    pressureProbeSuppressed += 1
                    pressureControlledFailures += 1
                    CfPressureDecision(
                        level = level,
                        maxAttempts = 0,
                        connectQueueWaitMs = 0L,
                        controlledFailure = true,
                        nextProbeAtMs = state.nextProbeAtMs,
                    )
                }
            }
            CfPressureLevel.EXHAUSTED -> {
                pressureProbeSuppressed += 1
                pressureControlledFailures += 1
                state.nextProbeAtMs = maxOf(state.nextProbeAtMs, now + SATURATED_PROBE_WINDOW_MS)
                CfPressureDecision(
                    level = level,
                    maxAttempts = 0,
                    connectQueueWaitMs = 0L,
                    controlledFailure = true,
                    nextProbeAtMs = state.nextProbeAtMs,
                )
            }
        }
    }

    @Synchronized
    fun recordCfRouteFailureAfterConnect(dcId: Int) {
        recordPressureEventLocked(dcId, CfPressureEventKind.ROUTE_FAILURE_AFTER_CF, nowMs())
    }

    @Synchronized
    fun recordPressureLimitedAttempt(dcId: Int) {
        pressureLimitedAttempts += 1
        pressureControlledFailures += 1
        recordPressureEventLocked(dcId, CfPressureEventKind.QUEUE_FAILURE, nowMs())
    }

    @Synchronized
    fun drainPressureLevelChanges(): List<CfPressureLevelChange> {
        val result = pendingPressureLevelChanges.toList()
        pendingPressureLevelChanges.clear()
        return result
    }

    @Synchronized
    fun snapshot(): CfDomainHealthSnapshot {
        val now = nowMs()
        val rows = states.entries.map { (key, state) -> state.snapshot(key, now) }
        val bestByDc = rows
            .groupBy { it.dcId }
            .mapValues { (_, dcRows) -> dcRows.sortedWith(snapshotComparator(now)).firstOrNull()?.fullDomain.orEmpty() }
            .filterValues { it.isNotEmpty() }
        return CfDomainHealthSnapshot(
            enabled = true,
            domainsTotal = domains.size,
            domainsInCooldown = rows.count { it.cooldownUntilMs > 0 },
            lastSelectedDomain = lastSelectedDomain,
            lastSelectedReason = lastSelectedReason,
            lastConnectLatencyMs = lastConnectLatencyMs,
            bestDomainByDc = bestByDc,
            total429 = rows.sumOf { it.total429 },
            total503 = rows.sumOf { it.total503 },
            totalUnknownHost = rows.sumOf { it.totalUnknownHost },
            totalTimeouts = rows.sumOf { it.totalTimeouts },
            cooldownSkips = cooldownSkips,
            allDomainsInCooldownFallbacks = allDomainsInCooldownFallbacks,
            inflightSkips = inflightSkips,
            inflightWaits = inflightWaits,
            maxInflightPerDomainReached = maxInflightPerDomainReached,
            activeConnectsByDc = activeConnectsByDc.toSortedMap(),
            connectQueueWaits = connectQueueWaits,
            connectQueueTimeouts = connectQueueTimeouts,
            queueControlledFailures = queueControlledFailures,
            queueWaitMs = queueWaitMs,
            maxConcurrentConnectsByDc = maxConcurrentConnectsByDc.toSortedMap(),
            backoffCount = rows.sumOf { it.total429 },
            allCooldownWaits = allCooldownWaits,
            allCooldownWaitMs = allCooldownWaitMs,
            allCooldownCircuitOpenCount = allCooldownCircuitOpenCount,
            allCooldownAttemptsAllowed = allCooldownAttemptsAllowed,
            allCooldownAttemptsSuppressed = allCooldownAttemptsSuppressed,
            allCooldownControlledFailures = allCooldownControlledFailures,
            allCooldownCircuitOpenByDc = allCooldownCircuitByDc
                .filterValues { it.nextAllowedAttemptAtMs > now }
                .mapValues { (_, state) -> state.nextAllowedAttemptAtMs }
                .toSortedMap(),
            allCooldownSingleAttempts = allCooldownSingleAttempts,
            allCooldownSingleAttemptFailures = allCooldownSingleAttemptFailures,
            allCooldownStoppedCycles = allCooldownStoppedCycles,
            transientNetworkFailures = transientNetworkFailures,
            failuresIgnoredBecauseNetworkChanged = failuresIgnoredBecauseNetworkChanged,
            cooldownsSkippedBecauseNetworkSettling = cooldownsSkippedBecauseNetworkSettling,
            transientCooldownsClearedOnNetworkAvailable = transientCooldownsClearedOnNetworkAvailable,
            pressure = pressureSnapshotLocked(now),
            allDomainsCooldownByDc = allDomainsCooldownByDcLocked(rows, now),
            domains = rows.sortedWith(compareBy<CfDomainSnapshot> { it.dcId }.thenBy { it.domain }),
        )
    }


    @Synchronized
    fun mobileRescueRecommended(dcId: Int): Boolean {
        val now = nowMs()
        val state = pressureStateFor(dcId)
        prunePressureEventsLocked(state, now)
        val counts = countPressureEvents(state.events)
        val level = evaluatePressureLevel(counts)
        updatePressureLevelLocked(dcId, state, level, now)
        val allDomainsCooldown = allDomainsCooldownByDcLocked(
            states.entries.map { (key, domainState) -> domainState.snapshot(key, now) },
            now,
        )[dcId] == true
        return level >= CfPressureLevel.SATURATED ||
            allDomainsCooldown ||
            counts.allCooldownSuppressed >= MOBILE_RESCUE_MIN_ALL_COOLDOWN_SUPPRESSIONS
    }

    private fun pressureStateFor(dcId: Int): MutableCfPressureState =
        pressureByDc.getOrPut(dcId) { MutableCfPressureState() }

    private fun recordPressureEventLocked(dcId: Int, kind: CfPressureEventKind, now: Long) {
        val state = pressureStateFor(dcId)
        state.events.addLast(CfPressureEvent(now, kind))
        prunePressureEventsLocked(state, now)
        val level = evaluatePressureLevel(countPressureEvents(state.events))
        updatePressureLevelLocked(dcId, state, level, now)
    }

    private fun pressureCountsLocked(dcId: Int, now: Long): CfPressureCounts {
        val state = pressureStateFor(dcId)
        prunePressureEventsLocked(state, now)
        return countPressureEvents(state.events)
    }

    private fun prunePressureEventsLocked(state: MutableCfPressureState, now: Long) {
        val cutoff = now - PRESSURE_WINDOW_MS
        while (state.events.isNotEmpty() && state.events.first().timeMs < cutoff) {
            state.events.removeFirst()
        }
        while (state.events.size > PRESSURE_MAX_EVENTS_PER_DC) {
            state.events.removeFirst()
        }
    }

    private fun countPressureEvents(events: Iterable<CfPressureEvent>): CfPressureCounts {
        var success = 0L
        var http429 = 0L
        var timeout = 0L
        var unknownHost = 0L
        var queue = 0L
        var allCooldown = 0L
        var allDomainsCooldown = 0L
        var maxInflight = 0L
        var routeFailure = 0L
        for (event in events) {
            when (event.kind) {
                CfPressureEventKind.SUCCESS -> success += 1
                CfPressureEventKind.HTTP_429 -> http429 += 1
                CfPressureEventKind.TIMEOUT -> timeout += 1
                CfPressureEventKind.UNKNOWN_HOST -> unknownHost += 1
                CfPressureEventKind.QUEUE_FAILURE -> queue += 1
                CfPressureEventKind.ALL_COOLDOWN_SUPPRESSED -> allCooldown += 1
                CfPressureEventKind.ALL_DOMAINS_COOLDOWN -> allDomainsCooldown += 1
                CfPressureEventKind.MAX_INFLIGHT -> maxInflight += 1
                CfPressureEventKind.ROUTE_FAILURE_AFTER_CF -> routeFailure += 1
            }
        }
        return CfPressureCounts(success, http429, timeout, unknownHost, queue, allCooldown, allDomainsCooldown, maxInflight, routeFailure)
    }

    private fun evaluatePressureLevel(counts: CfPressureCounts): CfPressureLevel {
        val failures = counts.failureTotal
        val total = failures + counts.success
        if (counts.allDomainsCooldown > 0L && counts.allCooldownSuppressed >= EXHAUSTED_MIN_ALL_COOLDOWN_SUPPRESSIONS) return CfPressureLevel.EXHAUSTED
        if (total < DEGRADED_MIN_TOTAL && failures < DEGRADED_MIN_FAILURES && counts.allDomainsCooldown == 0L) return CfPressureLevel.NORMAL
        val failureRatio = if (total > 0L) failures.toDouble() / total.toDouble() else 0.0
        val hardFailures = counts.http429 + counts.timeout + counts.queueFailure + counts.allCooldownSuppressed + counts.allDomainsCooldown
        val saturated = (failures >= SATURATED_MIN_FAILURES && failureRatio >= SATURATED_MIN_FAILURE_RATIO && counts.success <= SATURATED_MAX_RECENT_SUCCESSES &&
            (hardFailures >= SATURATED_MIN_HARD_FAILURES || counts.allCooldownSuppressed >= SATURATED_MIN_ALL_COOLDOWN_SUPPRESSIONS || counts.queueFailure >= SATURATED_MIN_QUEUE_FAILURES)) ||
            (counts.allDomainsCooldown > 0L && counts.allCooldownSuppressed >= SATURATED_MIN_ALL_COOLDOWN_SUPPRESSIONS)
        if (saturated) return CfPressureLevel.SATURATED
        val degraded = failures >= DEGRADED_MIN_FAILURES && failureRatio >= DEGRADED_MIN_FAILURE_RATIO &&
            (hardFailures >= DEGRADED_MIN_HARD_FAILURES || counts.maxInflight >= DEGRADED_MIN_MAX_INFLIGHT_HITS)
        return if (degraded) CfPressureLevel.DEGRADED else CfPressureLevel.NORMAL
    }

    private fun pressureReason(counts: CfPressureCounts): String? = when {
        counts.allDomainsCooldown > 0L && counts.allCooldownSuppressed >= EXHAUSTED_MIN_ALL_COOLDOWN_SUPPRESSIONS -> "all_domains_cooldown/all_cooldown_suppressed"
        counts.allDomainsCooldown > 0L -> "all_domains_cooldown"
        counts.allCooldownSuppressed >= SATURATED_MIN_ALL_COOLDOWN_SUPPRESSIONS -> "all_cooldown_suppressed"
        counts.http429 > 0L -> "http_429"
        counts.queueFailure > 0L -> "queue_failure"
        counts.timeout > 0L -> "timeout"
        else -> null
    }

    private fun allDomainsCooldownByDcLocked(rows: List<CfDomainSnapshot>, now: Long): Map<Int, Boolean> = rows
        .groupBy { it.dcId }
        .filterValues { dcRows -> dcRows.isNotEmpty() && dcRows.all { it.cooldownUntilMs > now } }
        .mapValues { true }
        .toSortedMap()

    private fun updatePressureLevelLocked(dcId: Int, state: MutableCfPressureState, level: CfPressureLevel, now: Long) {
        if (state.level == level) return
        val previous = state.level
        state.level = level
        state.lastChangedAtMs = now
        pressureLevelChanges += 1
        pendingPressureLevelChanges.addLast(CfPressureLevelChange(dcId, previous, level, now))
        if (level < CfPressureLevel.SATURATED) {
            state.nextProbeAtMs = 0L
        }
    }

    private fun pressureSnapshotLocked(now: Long): CfPressureSnapshot {
        val levels = mutableMapOf<Int, String>()
        val scores = mutableMapOf<Int, Long>()
        val successes = mutableMapOf<Int, Long>()
        val http429 = mutableMapOf<Int, Long>()
        val timeouts = mutableMapOf<Int, Long>()
        val unknownHosts = mutableMapOf<Int, Long>()
        val queueFailures = mutableMapOf<Int, Long>()
        val allCooldownSuppressed = mutableMapOf<Int, Long>()
        val maxInflight = mutableMapOf<Int, Long>()
        val routeFailures = mutableMapOf<Int, Long>()
        val allDomainsCooldown = mutableMapOf<Int, Long>()
        val reasons = mutableMapOf<Int, String>()
        val nextProbeAt = mutableMapOf<Int, Long>()
        for ((dcId, state) in pressureByDc) {
            prunePressureEventsLocked(state, now)
            val counts = countPressureEvents(state.events)
            val level = evaluatePressureLevel(counts)
            updatePressureLevelLocked(dcId, state, level, now)
            levels[dcId] = state.level.configValue
            scores[dcId] = counts.score
            successes[dcId] = counts.success
            http429[dcId] = counts.http429
            timeouts[dcId] = counts.timeout
            unknownHosts[dcId] = counts.unknownHost
            queueFailures[dcId] = counts.queueFailure
            allCooldownSuppressed[dcId] = counts.allCooldownSuppressed
            maxInflight[dcId] = counts.maxInflight
            routeFailures[dcId] = counts.routeFailureAfterCf
            allDomainsCooldown[dcId] = counts.allDomainsCooldown
            pressureReason(counts)?.let { reasons[dcId] = it }
            if (state.nextProbeAtMs > now) nextProbeAt[dcId] = state.nextProbeAtMs
        }
        return CfPressureSnapshot(
            levelByDc = levels.toSortedMap(),
            scoreByDc = scores.toSortedMap(),
            recentSuccessByDc = successes.toSortedMap(),
            recent429ByDc = http429.toSortedMap(),
            recentTimeoutByDc = timeouts.toSortedMap(),
            recentUnknownHostByDc = unknownHosts.toSortedMap(),
            recentQueueFailureByDc = queueFailures.toSortedMap(),
            recentAllCooldownSuppressedByDc = allCooldownSuppressed.toSortedMap(),
            recentMaxInflightByDc = maxInflight.toSortedMap(),
            recentRouteFailureAfterCfByDc = routeFailures.toSortedMap(),
            allDomainsCooldownByDc = allDomainsCooldown.toSortedMap(),
            reasonByDc = reasons.toSortedMap(),
            probeAllowed = pressureProbeAllowed,
            probeSuppressed = pressureProbeSuppressed,
            controlledFailures = pressureControlledFailures,
            limitedAttempts = pressureLimitedAttempts,
            levelChanges = pressureLevelChanges,
            nextProbeAtByDc = nextProbeAt.toSortedMap(),
        )
    }

    private fun stateFor(dcId: Int, isMedia: Boolean, domain: String): MutableCfDomainState =
        states.getOrPut(CfDomainKey(dcId, isMedia, domain)) { MutableCfDomainState() }

    private fun normalizeKnownDomain(domain: String): String? {
        val normalized = domain.trim().lowercase()
        if (normalized in domains) return normalized
        return domains
            .filter { baseDomain -> normalized.endsWith(".$baseDomain") }
            .maxByOrNull { it.length }
    }

    private fun selectionReason(state: MutableCfDomainState, now: Long): String = when {
        state.cooldownUntilMs > now -> "cooldown"
        state.successes > 0 -> "last_good"
        state.failures == 0L -> "fresh"
        else -> "least_failure_score"
    }

    private fun domainComparator(): Comparator<CfDomainSelection> =
        compareBy<CfDomainSelection> { if (it.reason == "last_good") 0 else 1 }
            .thenBy { it.latencyMs ?: Long.MAX_VALUE }
            .thenBy { it.originalIndex }
            .thenBy { it.failureScore }

    private fun cooldownFallbackComparator(): Comparator<CfDomainSelection> =
        compareBy<CfDomainSelection> { it.failureScore }
            .thenBy { it.cooldownUntilMs }
            .thenBy { it.latencyMs ?: Long.MAX_VALUE }
            .thenBy { it.originalIndex }

    private fun snapshotComparator(now: Long): Comparator<CfDomainSnapshot> =
        compareBy<CfDomainSnapshot> { if (it.cooldownUntilMs > now) 1 else 0 }
            .thenBy { if (it.successes > 0) 0 else 1 }
            .thenBy { failureScore(it) }
            .thenBy { it.ewmaLatencyMs ?: Long.MAX_VALUE }
            .thenBy { it.domain }

    private fun failureScore(state: MutableCfDomainState): Long =
        state.consecutiveFailures * 1_000L +
            state.failures * 100L +
            state.total429 * 50L +
            state.total503 * 30L +
            state.totalUnknownHost * 20L +
            state.totalTimeouts * 10L +
            ((state.ewmaLatencyMs ?: 0.0) / 10.0).toLong()

    private fun failureScore(snapshot: CfDomainSnapshot): Long =
        snapshot.consecutiveFailures * 1_000L +
            snapshot.failures * 100L +
            snapshot.total429 * 50L +
            snapshot.total503 * 30L +
            snapshot.totalUnknownHost * 20L +
            snapshot.totalTimeouts * 10L +
            ((snapshot.ewmaLatencyMs ?: 0L) / 10L)

    companion object {
        fun classifyError(error: Throwable): CfDomainErrorKind {
            val detail = error.message.orEmpty()
            return when {
                error is UnknownHostException -> CfDomainErrorKind.UNKNOWN_HOST
                error is SocketTimeoutException -> CfDomainErrorKind.TIMEOUT
                error is ConnectException && detail.contains("ENETUNREACH", ignoreCase = true) -> CfDomainErrorKind.ENETUNREACH
                error is SocketException && detail.contains("ENETUNREACH", ignoreCase = true) -> CfDomainErrorKind.ENETUNREACH
                detail.contains("429") -> CfDomainErrorKind.HTTP_429
                detail.contains("503") -> CfDomainErrorKind.HTTP_503
                else -> CfDomainErrorKind.OTHER
            }
        }

        fun shouldIgnoreTransientNetworkError(kind: CfDomainErrorKind, networkStatus: String, routeSettling: Boolean): Boolean =
            (kind == CfDomainErrorKind.UNKNOWN_HOST || kind == CfDomainErrorKind.ENETUNREACH || kind == CfDomainErrorKind.TIMEOUT) &&
                (routeSettling || networkStatus.equals("none", ignoreCase = true))

        fun cooldownMsFor(kind: CfDomainErrorKind, backoffLevel: Long = 0L): Long = when (kind) {
            CfDomainErrorKind.HTTP_429 -> http429BackoffBaseMs(backoffLevel)
            CfDomainErrorKind.HTTP_503 -> 45_000L
            CfDomainErrorKind.UNKNOWN_HOST -> 60_000L
            CfDomainErrorKind.ENETUNREACH -> 15_000L
            CfDomainErrorKind.TIMEOUT -> 20_000L
            CfDomainErrorKind.OTHER -> 0L
        }

        private const val SUCCESS_STREAK_STRONG_BACKOFF_RESET: Long = 3L
        private const val SUCCESS_STREAK_STRONG_BACKOFF_DECREMENT: Long = 2L

        fun http429BackoffBaseMs(backoffLevel: Long): Long = when {
            backoffLevel <= 1L -> 30_000L
            backoffLevel == 2L -> 60_000L
            backoffLevel == 3L -> 120_000L
            else -> 300_000L
        }

        const val PRESSURE_WINDOW_MS: Long = 30_000L
        const val SATURATED_PROBE_WINDOW_MS: Long = 3_000L
        const val DEGRADED_MOBILE_MAX_ATTEMPTS_PER_CYCLE: Int = 1
        const val MOBILE_RECOVERY_PROBE_WINDOW_MS: Long = 2_500L
        const val MOBILE_RECOVERY_PROBE_MAX_STARTS_PER_DC: Int = 2
        const val MOBILE_RECOVERY_PROBE_MAX_ATTEMPTS_PER_CYCLE: Int = 2
        const val DEGRADED_DEFAULT_MAX_ATTEMPTS_PER_CYCLE: Int = 2
        const val DEGRADED_CONNECT_QUEUE_WAIT_MS: Long = 50L
        const val SATURATED_CONNECT_QUEUE_WAIT_MS: Long = 25L
        private const val PRESSURE_MAX_EVENTS_PER_DC: Int = 200
        private const val DEGRADED_MIN_TOTAL: Long = 6L
        private const val DEGRADED_MIN_FAILURES: Long = 5L
        private const val DEGRADED_MIN_HARD_FAILURES: Long = 4L
        private const val DEGRADED_MIN_MAX_INFLIGHT_HITS: Long = 4L
        private const val DEGRADED_MIN_FAILURE_RATIO: Double = 0.60
        private const val SATURATED_MIN_FAILURES: Long = 8L
        private const val SATURATED_MIN_HARD_FAILURES: Long = 8L
        private const val SATURATED_MIN_QUEUE_FAILURES: Long = 5L
        private const val SATURATED_MIN_ALL_COOLDOWN_SUPPRESSIONS: Long = 3L
        private const val EXHAUSTED_MIN_ALL_COOLDOWN_SUPPRESSIONS: Long = 3L
        private const val MOBILE_RESCUE_MIN_ALL_COOLDOWN_SUPPRESSIONS: Long = 3L
        private const val SATURATED_MAX_RECENT_SUCCESSES: Long = 0L
        private const val SATURATED_MIN_FAILURE_RATIO: Double = 0.85

        const val DEFAULT_MAX_CONCURRENT_CF_CONNECTS_PER_DC: Int = 2
        const val CONNECT_QUEUE_WAIT_MS: Long = 250L
        const val ALL_COOLDOWN_WAIT_THRESHOLD_MS: Long = 500L
        const val ALL_COOLDOWN_SINGLE_ATTEMPT_WINDOW_MS: Long = 3_000L
        private const val ALL_COOLDOWN_WAIT_JITTER_MS: Long = 25L
        private val TRANSIENT_NETWORK_ERROR_VALUES = setOf(
            CfDomainErrorKind.UNKNOWN_HOST.configValue,
            CfDomainErrorKind.ENETUNREACH.configValue,
            CfDomainErrorKind.TIMEOUT.configValue,
        )
        private const val HTTP_429_JITTER_RATIO: Double = 0.2
    }
}

data class CfDomainSelectionPlan(
    val ordered: List<CfDomainSelection>,
    val skippedCooldown: List<CfDomainSelection>,
    val allDomainsInCooldownFallback: Boolean,
    val skippedInflight: List<CfDomainSelection> = emptyList(),
    val allDomainsInCooldownWaitMs: Long = 0L,
    val allDomainsInCooldownStoppedCycle: Boolean = false,
    val allDomainsInCooldownCircuitOpened: Boolean = false,
    val allDomainsInCooldownCircuitSuppressed: Boolean = false,
    val allDomainsInCooldownCircuitRetryAtMs: Long = 0L,
)

class CfDomainFallbackCycleState {
    internal var allCooldownFallbackUsed: Boolean = false
}

enum class CfConnectAcquireResult {
    ACQUIRED,
    DOMAIN_IN_FLIGHT,
    QUEUE_TIMEOUT,
    UNAVAILABLE,
}

data class CfDomainSelection(
    val dcId: Int,
    val isMedia: Boolean,
    val domain: String,
    val reason: String,
    val latencyMs: Long?,
    val inCooldown: Boolean,
    val cooldownUntilMs: Long,
    val failureScore: Long,
    val originalIndex: Int,
    val inFlight: Boolean = false,
)

data class CfDomainFailureDecision(
    val kind: CfDomainErrorKind,
    val counted: Boolean,
    val cooldownUntilMs: Long,
    val backoffLevel: Long = 0L,
)


enum class CfPressureLevel(val configValue: String) {
    NORMAL("normal"),
    DEGRADED("degraded"),
    SATURATED("saturated"),
    EXHAUSTED("exhausted"),
}

data class CfPressureDecision(
    val level: CfPressureLevel,
    val maxAttempts: Int,
    val connectQueueWaitMs: Long,
    val probeAllowed: Boolean = false,
    val controlledFailure: Boolean = false,
    val nextProbeAtMs: Long = 0L,
)

data class CfPressureLevelChange(
    val dcId: Int,
    val from: CfPressureLevel,
    val to: CfPressureLevel,
    val changedAtMs: Long,
)

data class CfPressureSnapshot(
    val levelByDc: Map<Int, String> = emptyMap(),
    val scoreByDc: Map<Int, Long> = emptyMap(),
    val recentSuccessByDc: Map<Int, Long> = emptyMap(),
    val recent429ByDc: Map<Int, Long> = emptyMap(),
    val recentTimeoutByDc: Map<Int, Long> = emptyMap(),
    val recentUnknownHostByDc: Map<Int, Long> = emptyMap(),
    val recentQueueFailureByDc: Map<Int, Long> = emptyMap(),
    val recentAllCooldownSuppressedByDc: Map<Int, Long> = emptyMap(),
    val recentMaxInflightByDc: Map<Int, Long> = emptyMap(),
    val recentRouteFailureAfterCfByDc: Map<Int, Long> = emptyMap(),
    val allDomainsCooldownByDc: Map<Int, Long> = emptyMap(),
    val reasonByDc: Map<Int, String> = emptyMap(),
    val probeAllowed: Long = 0,
    val probeSuppressed: Long = 0,
    val controlledFailures: Long = 0,
    val limitedAttempts: Long = 0,
    val levelChanges: Long = 0,
    val nextProbeAtByDc: Map<Int, Long> = emptyMap(),
)

data class CfDomainHealthSnapshot(
    val enabled: Boolean,
    val domainsTotal: Int,
    val domainsInCooldown: Int,
    val lastSelectedDomain: String?,
    val lastSelectedReason: String?,
    val lastConnectLatencyMs: Long?,
    val bestDomainByDc: Map<Int, String>,
    val total429: Long,
    val total503: Long,
    val totalUnknownHost: Long,
    val totalTimeouts: Long,
    val cooldownSkips: Long,
    val allDomainsInCooldownFallbacks: Long,
    val inflightSkips: Long,
    val inflightWaits: Long,
    val maxInflightPerDomainReached: Long,
    val activeConnectsByDc: Map<Int, Int>,
    val connectQueueWaits: Long,
    val connectQueueTimeouts: Long,
    val queueControlledFailures: Long,
    val queueWaitMs: Long,
    val maxConcurrentConnectsByDc: Map<Int, Int>,
    val backoffCount: Long,
    val allCooldownWaits: Long,
    val allCooldownWaitMs: Long,
    val allCooldownCircuitOpenCount: Long,
    val allCooldownAttemptsAllowed: Long,
    val allCooldownAttemptsSuppressed: Long,
    val allCooldownControlledFailures: Long,
    val allCooldownCircuitOpenByDc: Map<Int, Long>,
    val allCooldownSingleAttempts: Long,
    val allCooldownSingleAttemptFailures: Long,
    val allCooldownStoppedCycles: Long,
    val transientNetworkFailures: Long,
    val failuresIgnoredBecauseNetworkChanged: Long,
    val cooldownsSkippedBecauseNetworkSettling: Long,
    val transientCooldownsClearedOnNetworkAvailable: Long,
    val pressure: CfPressureSnapshot = CfPressureSnapshot(),
    val allDomainsCooldownByDc: Map<Int, Boolean> = emptyMap(),
    val domains: List<CfDomainSnapshot>,
)

data class CfDomainSnapshot(
    val dcId: Int,
    val isMedia: Boolean,
    val domain: String,
    val successes: Long,
    val failures: Long,
    val lastSuccessTimeMs: Long,
    val lastFailureTimeMs: Long,
    val lastLatencyMs: Long?,
    val ewmaLatencyMs: Long?,
    val consecutiveFailures: Long,
    val cooldownUntilMs: Long,
    val lastErrorKind: String?,
    val total429: Long,
    val consecutive429: Long,
    val backoffUntilMs: Long,
    val backoffLevel: Long,
    val total503: Long,
    val totalUnknownHost: Long,
    val totalTimeouts: Long,
    val successfulStreak: Long,
) {
    val fullDomain: String = "kws$dcId.$domain"
}

enum class CfDomainErrorKind(val configValue: String) {
    HTTP_429("429"),
    HTTP_503("503"),
    UNKNOWN_HOST("unknown_host"),
    ENETUNREACH("enetunreach"),
    TIMEOUT("timeout"),
    OTHER("other"),
}

private data class CfDomainKey(
    val dcId: Int,
    val isMedia: Boolean,
    val domain: String,
)


private enum class CfPressureEventKind {
    SUCCESS,
    HTTP_429,
    TIMEOUT,
    UNKNOWN_HOST,
    QUEUE_FAILURE,
    ALL_COOLDOWN_SUPPRESSED,
    ALL_DOMAINS_COOLDOWN,
    MAX_INFLIGHT,
    ROUTE_FAILURE_AFTER_CF,
}

private data class CfPressureEvent(
    val timeMs: Long,
    val kind: CfPressureEventKind,
)

private data class CfPressureCounts(
    val success: Long,
    val http429: Long,
    val timeout: Long,
    val unknownHost: Long,
    val queueFailure: Long,
    val allCooldownSuppressed: Long,
    val allDomainsCooldown: Long,
    val maxInflight: Long,
    val routeFailureAfterCf: Long,
) {
    val failureTotal: Long = http429 + timeout + unknownHost + queueFailure + allCooldownSuppressed + allDomainsCooldown + maxInflight + routeFailureAfterCf
    val score: Long = http429 * 3L + timeout * 2L + unknownHost + queueFailure * 2L + allCooldownSuppressed * 2L + allDomainsCooldown * 2L + maxInflight + routeFailureAfterCf * 2L - success * 3L
}

private class MutableCfPressureState {
    val events: ArrayDeque<CfPressureEvent> = ArrayDeque()
    var level: CfPressureLevel = CfPressureLevel.NORMAL
    var lastChangedAtMs: Long = 0L
    var nextProbeAtMs: Long = 0L
}

private class MutableCfAllCooldownCircuitState {
    var nextAllowedAttemptAtMs: Long = 0
    var lastOpenedAtMs: Long = 0
}

private class MutableCfDomainState {
    var successes: Long = 0
    var failures: Long = 0
    var lastSuccessTimeMs: Long = 0
    var lastFailureTimeMs: Long = 0
    var lastLatencyMs: Long? = null
    var ewmaLatencyMs: Double? = null
    var consecutiveFailures: Long = 0
    var cooldownUntilMs: Long = 0
    var lastErrorKind: String? = null
    var total429: Long = 0
    var consecutive429: Long = 0
    var total503: Long = 0
    var totalUnknownHost: Long = 0
    var totalTimeouts: Long = 0
    var successfulStreak: Long = 0

    fun snapshot(key: CfDomainKey, now: Long): CfDomainSnapshot = CfDomainSnapshot(
        dcId = key.dcId,
        isMedia = key.isMedia,
        domain = key.domain,
        successes = successes,
        failures = failures,
        lastSuccessTimeMs = lastSuccessTimeMs,
        lastFailureTimeMs = lastFailureTimeMs,
        lastLatencyMs = lastLatencyMs,
        ewmaLatencyMs = ewmaLatencyMs?.toLong(),
        consecutiveFailures = consecutiveFailures,
        cooldownUntilMs = cooldownUntilMs.takeIf { it > now } ?: 0,
        lastErrorKind = lastErrorKind,
        total429 = total429,
        consecutive429 = consecutive429,
        backoffUntilMs = if (consecutive429 > 0 && cooldownUntilMs > now) cooldownUntilMs else 0,
        backoffLevel = consecutive429,
        total503 = total503,
        totalUnknownHost = totalUnknownHost,
        totalTimeouts = totalTimeouts,
        successfulStreak = successfulStreak,
    )
}
