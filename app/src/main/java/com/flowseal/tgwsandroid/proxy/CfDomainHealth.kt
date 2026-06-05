package com.flowseal.tgwsandroid.proxy

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
    private var allCooldownWaits: Long = 0
    private var allCooldownWaitMs: Long = 0
    private val activeConnectsByDc = mutableMapOf<Int, Int>()
    private val maxConcurrentConnectsByDc = mutableMapOf<Int, Int>()
    private val inFlightByDomain = mutableMapOf<CfDomainKey, Int>()

    @Synchronized
    fun updateDomainsList(domainsList: List<String>) {
        val normalized = CfProxyDomains.normalize(domainsList)
        if (domains == normalized) return
        domains = normalized
        states.keys.removeAll { it.domain !in normalized }
    }

    @Synchronized
    fun selectDomains(dcId: Int, isMedia: Boolean = false): CfDomainSelectionPlan {
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
        if (waitMs in 1..ALL_COOLDOWN_WAIT_THRESHOLD_MS) {
            allCooldownWaits += 1
            allCooldownWaitMs += waitMs
            return CfDomainSelectionPlan(
                ordered = emptyList(),
                skippedCooldown = entries.sortedWith(cooldownFallbackComparator()),
                skippedInflight = emptyList(),
                allDomainsInCooldownFallback = false,
                allDomainsInCooldownWaitMs = waitMs,
            )
        }

        allDomainsInCooldownFallbacks += 1
        return CfDomainSelectionPlan(
            ordered = entries
                .sortedWith(cooldownFallbackComparator())
                .map { it.copy(reason = "all_cooldown_least_bad") },
            skippedCooldown = emptyList(),
            skippedInflight = emptyList(),
            allDomainsInCooldownFallback = true,
        )
    }

    @Synchronized
    fun recordSelected(dcId: Int, isMedia: Boolean, baseDomain: String, fullDomain: String, reason: String) {
        normalizeKnownDomain(baseDomain) ?: return
        lastSelectedDomain = fullDomain
        lastSelectedReason = reason
    }

    @Synchronized
    fun recordSuccess(dcId: Int, isMedia: Boolean, baseDomain: String, latencyMs: Long) {
        val normalized = normalizeKnownDomain(baseDomain) ?: return
        val state = stateFor(dcId, isMedia, normalized)
        val now = nowMs()
        state.successes += 1
        state.consecutiveFailures = 0
        state.consecutive429 = 0
        state.lastSuccessTimeMs = now
        state.lastLatencyMs = latencyMs
        state.ewmaLatencyMs = state.ewmaLatencyMs?.let { (it * 0.7) + (latencyMs * 0.3) } ?: latencyMs.toDouble()
        state.cooldownUntilMs = 0
        state.lastErrorKind = null
        lastConnectLatencyMs = latencyMs
    }

    @Synchronized
    fun recordFailure(
        dcId: Int,
        isMedia: Boolean,
        baseDomain: String,
        error: Throwable,
        networkStatus: String,
        routeSettling: Boolean,
    ): CfDomainFailureDecision {
        val normalized = normalizeKnownDomain(baseDomain) ?: return CfDomainFailureDecision(CfDomainErrorKind.OTHER, false, 0)
        val kind = classifyError(error)
        if (shouldIgnoreTransientNetworkError(kind, networkStatus, routeSettling)) {
            return CfDomainFailureDecision(kind, counted = false, cooldownUntilMs = 0)
        }

        val state = stateFor(dcId, isMedia, normalized)
        val now = nowMs()
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
            CfDomainErrorKind.HTTP_429 -> state.total429 += 1
            CfDomainErrorKind.HTTP_503 -> state.total503 += 1
            CfDomainErrorKind.UNKNOWN_HOST -> state.totalUnknownHost += 1
            CfDomainErrorKind.TIMEOUT -> state.totalTimeouts += 1
            else -> Unit
        }
        return CfDomainFailureDecision(
            kind = kind,
            counted = true,
            cooldownUntilMs = state.cooldownUntilMs,
            backoffLevel = backoffLevel,
        )
    }

    fun acquireConnect(dcId: Int, isMedia: Boolean, baseDomain: String, waitMs: Long = CONNECT_QUEUE_WAIT_MS): Boolean {
        val normalized = normalizeKnownDomain(baseDomain) ?: return false
        val key = CfDomainKey(dcId, isMedia, normalized)
        val deadline = nowMs() + waitMs
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
                    return true
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
                    if (activeForDc >= maxConcurrentConnectsForDc(dcId).coerceAtLeast(1)) connectQueueTimeouts += 1
                    if (domainInFlight) maxInflightPerDomainReached += 1
                    return false
                }
                try {
                    (this as java.lang.Object).wait(remainingMs.coerceAtMost(waitMs).coerceAtLeast(1L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    connectQueueTimeouts += 1
                    return false
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
            maxConcurrentConnectsByDc = maxConcurrentConnectsByDc.toSortedMap(),
            backoffCount = rows.sumOf { it.total429 },
            allCooldownWaits = allCooldownWaits,
            allCooldownWaitMs = allCooldownWaitMs,
            domains = rows.sortedWith(compareBy<CfDomainSnapshot> { it.dcId }.thenBy { it.domain }),
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
            (kind == CfDomainErrorKind.UNKNOWN_HOST || kind == CfDomainErrorKind.ENETUNREACH) &&
                (routeSettling || networkStatus.equals("none", ignoreCase = true))

        fun cooldownMsFor(kind: CfDomainErrorKind, backoffLevel: Long = 0L): Long = when (kind) {
            CfDomainErrorKind.HTTP_429 -> http429BackoffBaseMs(backoffLevel)
            CfDomainErrorKind.HTTP_503 -> 45_000L
            CfDomainErrorKind.UNKNOWN_HOST -> 60_000L
            CfDomainErrorKind.ENETUNREACH -> 15_000L
            CfDomainErrorKind.TIMEOUT -> 20_000L
            CfDomainErrorKind.OTHER -> 0L
        }

        fun http429BackoffBaseMs(backoffLevel: Long): Long = when {
            backoffLevel <= 1L -> 30_000L
            backoffLevel == 2L -> 60_000L
            backoffLevel == 3L -> 120_000L
            else -> 300_000L
        }

        const val DEFAULT_MAX_CONCURRENT_CF_CONNECTS_PER_DC: Int = 2
        const val CONNECT_QUEUE_WAIT_MS: Long = 250L
        const val ALL_COOLDOWN_WAIT_THRESHOLD_MS: Long = 500L
        private const val HTTP_429_JITTER_RATIO: Double = 0.2
    }
}

data class CfDomainSelectionPlan(
    val ordered: List<CfDomainSelection>,
    val skippedCooldown: List<CfDomainSelection>,
    val allDomainsInCooldownFallback: Boolean,
    val skippedInflight: List<CfDomainSelection> = emptyList(),
    val allDomainsInCooldownWaitMs: Long = 0L,
)

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
    val maxConcurrentConnectsByDc: Map<Int, Int>,
    val backoffCount: Long,
    val allCooldownWaits: Long,
    val allCooldownWaitMs: Long,
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
    )
}
