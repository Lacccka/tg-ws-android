package com.flowseal.tgwsandroid.service

import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DiagnosticSnapshot(
    val generated: LocalDateTime,
    val status: String,
    val endpoint: String,
    val secret: String,
    val secretSource: String = "unknown",
    val proxyLinkCurrent: Boolean = true,
    val dcSummary: String,
    val batteryOptimization: String,
    val network: String,
    val routeMode: String = "unknown",
    val effectiveRouteMode: String = "unknown",
    val previousEffectiveRouteMode: String? = null,
    val lastRouteChangeReason: String = "unknown",
    val lastRouteChangeTimeMs: Long? = null,
    val networkAtLastRouteChange: String = "unknown",
    val stats: ProxyServerStats?,
    val logs: List<RuntimeLogEntry>,
)

object DiagnosticReportFormatter {
    private val GENERATED_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun snapshot(
        status: String,
        endpoint: String,
        secret: String,
        secretSource: String = "unknown",
        proxyLinkCurrent: Boolean = true,
        dcSummary: String,
        batteryOptimization: String = "unknown",
        network: String = "unknown",
        routeMode: String = "unknown",
        effectiveRouteMode: String = "unknown",
        previousEffectiveRouteMode: String? = null,
        lastRouteChangeReason: String = "unknown",
        lastRouteChangeTimeMs: Long? = null,
        networkAtLastRouteChange: String = "unknown",
        stats: ProxyServerStats? = null,
        logs: List<RuntimeLogEntry>,
        clock: Clock = Clock.systemDefaultZone(),
    ): DiagnosticSnapshot = DiagnosticSnapshot(
        generated = LocalDateTime.now(clock),
        status = status.ifBlank { "unknown" },
        endpoint = endpoint.ifBlank { "unknown" },
        secret = secret.ifBlank { "unknown" },
        secretSource = secretSource.ifBlank { "unknown" },
        proxyLinkCurrent = proxyLinkCurrent,
        dcSummary = dcSummary.ifBlank { "unknown" },
        batteryOptimization = batteryOptimization.ifBlank { "unknown" },
        network = network.ifBlank { "unknown" },
        routeMode = routeMode.ifBlank { "unknown" },
        effectiveRouteMode = effectiveRouteMode.ifBlank { "unknown" },
        previousEffectiveRouteMode = previousEffectiveRouteMode,
        lastRouteChangeReason = lastRouteChangeReason.ifBlank { "unknown" },
        lastRouteChangeTimeMs = lastRouteChangeTimeMs,
        networkAtLastRouteChange = networkAtLastRouteChange.ifBlank { "unknown" },
        stats = stats,
        logs = logs,
    )

    fun format(snapshot: DiagnosticSnapshot): String = buildString {
        appendLine("TG WS Android diagnostics")
        appendLine("Generated: ${snapshot.generated.format(GENERATED_FORMATTER)}")
        appendLine("Status: ${snapshot.status}")
        appendLine("Endpoint: ${snapshot.endpoint}")
        appendLine("Secret: ${snapshot.secret}")
        appendLine("Secret source: ${snapshot.secretSource}")
        appendLine("Proxy link current: ${snapshot.proxyLinkCurrent}")
        appendLine("DCs: ${snapshot.dcSummary}")
        appendLine("Battery optimization: ${snapshot.batteryOptimization}")
        appendLine("Network: ${snapshot.network}")
        appendLine("Configured route mode: ${snapshot.routeMode}")
        appendLine("Effective route mode: ${snapshot.effectiveRouteMode}")
        appendLine("Previous effective route mode: ${snapshot.previousEffectiveRouteMode ?: "none"}")
        appendLine("Last route change reason: ${snapshot.lastRouteChangeReason}")
        appendLine("Last route change source: ${snapshot.stats?.lastRouteChangeSource ?: "unknown"}")
        appendLine("Last route change time: ${snapshot.lastRouteChangeTimeMs?.toString() ?: "unknown"}")
        appendLine("Network at last route change: ${snapshot.networkAtLastRouteChange}")
        appendLine("Stats: ${formatStats(snapshot.stats)}")
        appendCfHealth(snapshot.stats)
        appendLine("---")
        snapshot.logs.forEach { appendLine(it.formatLine()) }
    }.trimEnd()

    fun formatStats(stats: ProxyServerStats?): String = if (stats == null) {
        "unknown"
    } else {
        "total=${stats.connectionsTotal}, active=${stats.connectionsActive}, bad=${stats.connectionsBad}, " +
            "Invalid MTProto handshake stormRecent=${stats.badHandshakeStormRecent}, stormCumulative=${stats.badHandshakeStormCumulative}, " +
            "badHandshakeRatio=${String.format(Locale.US, "%.3f", stats.badHandshakeRatio)}, " +
            "recentBadHandshakeRatio=${String.format(Locale.US, "%.3f", stats.recentBadHandshakeRatio)}, " +
            "recentInvalidHandshakeCount=${stats.recentInvalidHandshakeCount}, recentAcceptedHandshakeCount=${stats.recentAcceptedHandshakeCount}, " +
            "lastInvalidHandshakeTimeMs=${stats.lastInvalidHandshakeTimeMs}, lastAcceptedHandshakeTimeMs=${stats.lastAcceptedHandshakeTimeMs}, " +
            "lastSuccessfulRouteTimeMs=${stats.lastSuccessfulRouteTimeMs}, " +
            "secondsSinceLastAcceptedHandshake=${stats.secondsSinceLastAcceptedHandshake?.toString() ?: "unknown"}, " +
            "secondsSinceLastSuccessfulRoute=${stats.secondsSinceLastSuccessfulRoute?.toString() ?: "unknown"}, " +
            "handshakeDiagnosticState=${stats.handshakeDiagnosticState}, " +
            "handshakeDiagnosticReason=${stats.handshakeDiagnosticReason}, " +
            "recentHandshakeDiagnostic=${stats.handshakeDiagnosticReason}, " +
            "badHandshakeRecommendation=${stats.badHandshakeRecommendation}, " +
            "wsErrors=${stats.wsConnectErrors}, cfConnections=${stats.cfProxyConnections}, " +
            "cfErrors=${stats.cfProxyErrors}, bytesUp=${stats.bytesUp}, bytesDown=${stats.bytesDown}, " +
            "sessionTimeouts=${stats.sessionTimeouts}, sessionEof=${stats.sessionEof}, " +
            "sessionClientClosed=${stats.sessionClientClosed}, sessionSocketClosed=${stats.sessionSocketClosed}, " +
            "sessionUnexpectedErrors=${stats.sessionUnexpectedErrors}, configuredRouteMode=${stats.routeMode}, " +
            "effectiveRouteMode=${stats.effectiveRouteMode}, previousEffectiveRouteMode=${stats.previousEffectiveRouteMode ?: "none"}, " +
            "lastRouteChangeReason=${stats.lastRouteChangeReason}, lastRouteChangeSource=${stats.lastRouteChangeSource}, " +
            "lastRouteChangeTimeMs=${stats.lastRouteChangeTimeMs ?: "unknown"}, " +
            "networkAtLastRouteChange=${stats.networkAtLastRouteChange}, lastRouteUsed=${stats.lastRouteUsed ?: "none"}, " +
            "directAttempts=${stats.directAttempts}, directAttemptsSkippedBecauseRoute=${stats.directAttemptsSkippedBecauseRoute}, " +
            "directTimeouts=${stats.directTimeouts}, cfConnections=${stats.cfProxyConnections}, " +
            "cfErrors=${stats.cfProxyErrors}, lastCfDomain=${stats.lastCfDomain ?: "none"}, poolHits=${stats.poolHits}, " +
            "poolMisses=${stats.poolMisses}, poolRefillErrors=${stats.poolRefillErrors}, poolStale=${stats.poolStale}, " +
            "poolRefillsCancelled=${stats.poolRefillsCancelled}, " +
            "poolResultsDiscardedAfterRouteChange=${stats.poolResultsDiscardedAfterRouteChange}, " +
            "routeChangesImmediate=${stats.routeChangesImmediate}, networkNoneEvents=${stats.networkNoneEvents}, " +
            "directHealthState=${stats.directHealthState}, directHealthSuccesses=${stats.directHealthSuccesses}, " +
            "directHealthFailures=${stats.directHealthFailures}, directDowngrades=${stats.directDowngrades}, " +
            "directPromotions=${stats.directPromotions}, directCooldownUntil=${stats.directCooldownUntil}, " +
            "routeSettlingUntil=${stats.routeSettlingUntil}, directProbeLastError=${stats.directProbeLastError ?: "none"}, " +
            "directProbeLastSuccessTime=${stats.directProbeLastSuccessTime ?: "none"}, " +
            "cfHealthEnabled=${stats.cfHealthEnabled}, cfDomainsTotal=${stats.cfDomainsTotal}, " +
            "cfDomainsInCooldown=${stats.cfDomainsInCooldown}, cfLastSelectedDomain=${stats.cfLastSelectedDomain ?: "none"}, " +
            "cfLastSelectedReason=${stats.cfLastSelectedReason ?: "none"}, " +
            "cfLastConnectLatencyMs=${stats.cfLastConnectLatencyMs ?: "unknown"}, " +
            "cfBestDomainByDc=${formatBestDomainByDc(stats.cfBestDomainByDc)}, cf429Count=${stats.cf429Count}, " +
            "cf503Count=${stats.cf503Count}, cfUnknownHostCount=${stats.cfUnknownHostCount}, " +
            "cfTimeoutCount=${stats.cfTimeoutCount}, cfCooldownSkips=${stats.cfCooldownSkips}, " +
            "cfAllDomainsInCooldownFallbacks=${stats.cfAllDomainsInCooldownFallbacks}, " +
            "cfInflightSkips=${stats.cfInflightSkips}, cfInflightWaits=${stats.cfInflightWaits}, " +
            "cfMaxInflightPerDomainReached=${stats.cfMaxInflightPerDomainReached}, " +
            "cfActiveConnectsByDc=${formatIntByDc(stats.cfActiveConnectsByDc)}, " +
            "cfConnectQueueWaits=${stats.cfConnectQueueWaits}, cfConnectQueueTimeouts=${stats.cfConnectQueueTimeouts}, " +
            "cfQueueControlledFailures=${stats.cfQueueControlledFailures}, cfQueueWaitMs=${stats.cfQueueWaitMs}, " +
            "cfMaxConcurrentConnectsByDc=${formatIntByDc(stats.cfMaxConcurrentConnectsByDc)}, " +
            "cf429BackoffCount=${stats.cf429BackoffCount}, cfAllCooldownWaits=${stats.cfAllCooldownWaits}, " +
            "cfAllCooldownWaitMs=${stats.cfAllCooldownWaitMs}, " +
            "cfAllCooldownCircuitOpenCount=${stats.cfAllCooldownCircuitOpenCount}, " +
            "cfAllCooldownAttemptsAllowed=${stats.cfAllCooldownAttemptsAllowed}, " +
            "cfAllCooldownAttemptsSuppressed=${stats.cfAllCooldownAttemptsSuppressed}, " +
            "cfAllCooldownControlledFailures=${stats.cfAllCooldownControlledFailures}, " +
            "cfAllCooldownCircuitOpenByDc=${formatLongByDc(stats.cfAllCooldownCircuitOpenByDc)}, " +
            "cfAllCooldownSingleAttempts=${stats.cfAllCooldownSingleAttempts}, " +
            "cfAllCooldownSingleAttemptFailures=${stats.cfAllCooldownSingleAttemptFailures}, " +
            "cfAllCooldownStoppedCycles=${stats.cfAllCooldownStoppedCycles}, " +
            "cfPressureLevelByDc=${formatStringByDc(stats.cfPressureLevelByDc)}, " +
            "cfPressureScoreByDc=${formatLongByDc(stats.cfPressureScoreByDc)}, " +
            "cfPressureRecentSuccessByDc=${formatLongByDc(stats.cfPressureRecentSuccessByDc)}, " +
            "cfPressureRecent429ByDc=${formatLongByDc(stats.cfPressureRecent429ByDc)}, " +
            "cfPressureRecentTimeoutByDc=${formatLongByDc(stats.cfPressureRecentTimeoutByDc)}, " +
            "cfPressureRecentUnknownHostByDc=${formatLongByDc(stats.cfPressureRecentUnknownHostByDc)}, " +
            "cfPressureRecentQueueFailureByDc=${formatLongByDc(stats.cfPressureRecentQueueFailureByDc)}, " +
            "cfPressureRecentAllCooldownSuppressedByDc=${formatLongByDc(stats.cfPressureRecentAllCooldownSuppressedByDc)}, " +
            "cfPressureRecentMaxInflightByDc=${formatLongByDc(stats.cfPressureRecentMaxInflightByDc)}, " +
            "cfPressureRecentRouteFailureAfterCfByDc=${formatLongByDc(stats.cfPressureRecentRouteFailureAfterCfByDc)}, " +
            "cfPressureProbeAllowed=${stats.cfPressureProbeAllowed}, " +
            "cfPressureProbeSuppressed=${stats.cfPressureProbeSuppressed}, " +
            "cfPressureControlledFailures=${stats.cfPressureControlledFailures}, " +
            "cfPressureLimitedAttempts=${stats.cfPressureLimitedAttempts}, " +
            "cfPressureLevelChanges=${stats.cfPressureLevelChanges}, " +
            "cfPressureNextProbeAtByDc=${formatLongByDc(stats.cfPressureNextProbeAtByDc)}, " +
            "cfTransientNetworkFailures=${stats.cfTransientNetworkFailures}, " +
            "cfFailuresIgnoredBecauseNetworkChanged=${stats.cfFailuresIgnoredBecauseNetworkChanged}, " +
            "cfCooldownsSkippedBecauseNetworkSettling=${stats.cfCooldownsSkippedBecauseNetworkSettling}, " +
            "cfTransientCooldownsClearedOnNetworkAvailable=${stats.cfTransientCooldownsClearedOnNetworkAvailable}, " +
            "networkSettlingWaits=${stats.networkSettlingWaits}, networkSettlingWaitMs=${stats.networkSettlingWaitMs}, " +
            "networkSettlingResumedAfterAvailable=${stats.networkSettlingResumedAfterAvailable}, " +
            "networkSettlingControlledFailures=${stats.networkSettlingControlledFailures}, " +
            "networkSettlingStaleAttemptsIgnored=${stats.networkSettlingStaleAttemptsIgnored}, " +
            "networkSettlingUntilMs=${stats.networkSettlingUntilMs}, lastNetworkLostAtMs=${stats.lastNetworkLostAtMs}, " +
            "lastNetworkAvailableAtMs=${stats.lastNetworkAvailableAtMs}, networkGeneration=${stats.networkGeneration}"
    }

    private fun StringBuilder.appendCfHealth(stats: ProxyServerStats?) {
        if (stats == null || !stats.cfHealthEnabled) return
        appendLine("CF health:")
        if (stats.cfHealthDomains.isEmpty()) {
            appendLine("  none")
            return
        }
        stats.cfHealthDomains
            .groupBy { it.dcId }
            .toSortedMap()
            .forEach { (dcId, domains) ->
                val best = stats.cfBestDomainByDc[dcId] ?: "none"
                val bestRow = domains.firstOrNull { it.fullDomain == best }
                val cooldownCount = domains.count { it.cooldownUntilMs > 0 }
                appendLine("DC$dcId:")
                appendLine(
                    "  cfPressureLevel=${stats.cfPressureLevelByDc[dcId] ?: "normal"} " +
                        "score=${stats.cfPressureScoreByDc[dcId] ?: 0L} " +
                        "recentSuccess=${stats.cfPressureRecentSuccessByDc[dcId] ?: 0L} " +
                        "recent429=${stats.cfPressureRecent429ByDc[dcId] ?: 0L} " +
                        "recentTimeout=${stats.cfPressureRecentTimeoutByDc[dcId] ?: 0L} " +
                        "recentQueueFailure=${stats.cfPressureRecentQueueFailureByDc[dcId] ?: 0L} " +
                        "recentAllCooldownSuppressed=${stats.cfPressureRecentAllCooldownSuppressedByDc[dcId] ?: 0L} " +
                        "nextProbeAt=${stats.cfPressureNextProbeAtByDc[dcId] ?: 0L}",
                )
                appendLine(
                    "  best=$best latency=${bestRow?.ewmaLatencyMs ?: bestRow?.lastLatencyMs ?: "unknown"} " +
                        "cooldown=$cooldownCount 429=${domains.sumOf { it.total429 }} " +
                        "503=${domains.sumOf { it.total503 }} unknownHost=${domains.sumOf { it.totalUnknownHost }} " +
                        "timeouts=${domains.sumOf { it.totalTimeouts }}",
                )
                appendLine("  topSuccess=${formatTopDomains(domains.sortedByDescending { it.successes }) { it.successes }}")
                appendLine("  top429=${formatTopDomains(domains.sortedByDescending { it.total429 }) { it.total429 }}")
                appendLine("  topLatency=${formatTopDomains(domains.filter { it.ewmaLatencyMs != null || it.lastLatencyMs != null }.sortedBy { it.ewmaLatencyMs ?: it.lastLatencyMs ?: Long.MAX_VALUE }) { it.ewmaLatencyMs ?: it.lastLatencyMs ?: 0L }}")
                appendLine("  topCooldown=${formatTopDomains(domains.filter { it.cooldownUntilMs > 0 }.sortedBy { it.cooldownUntilMs }) { it.cooldownUntilMs }}")
                appendLine("  cf429BackoffLevel=${formatTopDomains(domains.filter { it.backoffLevel > 0 }.sortedByDescending { it.backoffLevel }) { it.backoffLevel }}")
                appendLine("  cf429BackoffUntil=${formatTopDomains(domains.filter { it.backoffUntilMs > 0 }.sortedBy { it.backoffUntilMs }) { it.backoffUntilMs }}")
                appendLine("  cf429ConsecutiveByDomain=${formatTopDomains(domains.filter { it.consecutive429 > 0 }.sortedByDescending { it.consecutive429 }) { it.consecutive429 }}")
                appendLine("  cfSuccessStreakByDomain=${formatTopDomains(domains.filter { it.successfulStreak > 0 }.sortedByDescending { it.successfulStreak }) { it.successfulStreak }}")
            }
    }

    private fun formatBestDomainByDc(bestDomainByDc: Map<Int, String>): String =
        if (bestDomainByDc.isEmpty()) {
            "none"
        } else {
            bestDomainByDc.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (dcId, domain) -> "DC$dcId=$domain" }
        }

    private fun formatIntByDc(valuesByDc: Map<Int, Int>): String =
        if (valuesByDc.isEmpty()) {
            "none"
        } else {
            valuesByDc.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (dcId, value) -> "DC$dcId=$value" }
        }

    private fun formatStringByDc(valuesByDc: Map<Int, String>): String =
        if (valuesByDc.isEmpty()) {
            "none"
        } else {
            valuesByDc.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (dcId, value) -> "DC$dcId=$value" }
        }

    private fun formatLongByDc(valuesByDc: Map<Int, Long>): String =
        if (valuesByDc.isEmpty()) {
            "none"
        } else {
            valuesByDc.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { (dcId, value) -> "DC$dcId=$value" }
        }

    private fun formatTopDomains(domains: List<com.flowseal.tgwsandroid.proxy.CfDomainSnapshot>, value: (com.flowseal.tgwsandroid.proxy.CfDomainSnapshot) -> Long): String =
        domains
            .take(3)
            .filter { value(it) > 0L }
            .joinToString(prefix = "[", postfix = "]") { "${it.fullDomain}=${value(it)}" }
            .ifEmpty { "none" }

}
