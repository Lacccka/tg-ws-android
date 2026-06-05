package com.flowseal.tgwsandroid.proxy

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.max

/** In-memory, per-DC Cloudflare proxy domain health and ordering state. */
class CfDomainHealth(
    domains: List<String> = CfProxyDomains.defaults,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var domains: List<String> = CfProxyDomains.normalize(domains)
    private val states = mutableMapOf<CfDomainKey, MutableCfDomainState>()
    private var lastSelectedDomain: String? = null
    private var lastSelectedReason: String? = null
    private var lastConnectLatencyMs: Long? = null
    private var cooldownSkips: Long = 0
    private var allDomainsInCooldownFallbacks: Long = 0

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
            )
        }
        val available = entries.filterNot { it.inCooldown }
        if (available.isNotEmpty()) {
            val skipped = entries.filter { it.inCooldown }
            cooldownSkips += skipped.size.toLong()
            return CfDomainSelectionPlan(
                ordered = available.sortedWith(domainComparator()),
                skippedCooldown = skipped,
                allDomainsInCooldownFallback = false,
            )
        }

        allDomainsInCooldownFallbacks += 1
        return CfDomainSelectionPlan(
            ordered = entries
                .sortedWith(cooldownFallbackComparator())
                .map { it.copy(reason = "all_cooldown_least_bad") },
            skippedCooldown = emptyList(),
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
        val cooldownMs = cooldownMsFor(kind)
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
        return CfDomainFailureDecision(kind, counted = true, cooldownUntilMs = state.cooldownUntilMs)
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

        fun cooldownMsFor(kind: CfDomainErrorKind): Long = when (kind) {
            CfDomainErrorKind.HTTP_429 -> 60_000L
            CfDomainErrorKind.HTTP_503 -> 45_000L
            CfDomainErrorKind.UNKNOWN_HOST -> 60_000L
            CfDomainErrorKind.ENETUNREACH -> 15_000L
            CfDomainErrorKind.TIMEOUT -> 20_000L
            CfDomainErrorKind.OTHER -> 0L
        }
    }
}

data class CfDomainSelectionPlan(
    val ordered: List<CfDomainSelection>,
    val skippedCooldown: List<CfDomainSelection>,
    val allDomainsInCooldownFallback: Boolean,
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
)

data class CfDomainFailureDecision(
    val kind: CfDomainErrorKind,
    val counted: Boolean,
    val cooldownUntilMs: Long,
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
        total503 = total503,
        totalUnknownHost = totalUnknownHost,
        totalTimeouts = totalTimeouts,
    )
}
