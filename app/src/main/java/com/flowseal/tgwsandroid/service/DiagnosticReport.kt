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
        appendLine("DCs: ${snapshot.dcSummary}")
        appendLine("Battery optimization: ${snapshot.batteryOptimization}")
        appendLine("Network: ${snapshot.network}")
        appendLine("Configured route mode: ${snapshot.routeMode}")
        appendLine("Effective route mode: ${snapshot.effectiveRouteMode}")
        appendLine("Previous effective route mode: ${snapshot.previousEffectiveRouteMode ?: "none"}")
        appendLine("Last route change reason: ${snapshot.lastRouteChangeReason}")
        appendLine("Last route change time: ${snapshot.lastRouteChangeTimeMs?.toString() ?: "unknown"}")
        appendLine("Network at last route change: ${snapshot.networkAtLastRouteChange}")
        appendLine("Stats: ${formatStats(snapshot.stats)}")
        appendLine("---")
        snapshot.logs.forEach { appendLine(it.formatLine()) }
    }.trimEnd()

    fun formatStats(stats: ProxyServerStats?): String = if (stats == null) {
        "unknown"
    } else {
        "total=${stats.connectionsTotal}, active=${stats.connectionsActive}, bad=${stats.connectionsBad}, " +
            "wsErrors=${stats.wsConnectErrors}, cfConnections=${stats.cfProxyConnections}, " +
            "cfErrors=${stats.cfProxyErrors}, bytesUp=${stats.bytesUp}, bytesDown=${stats.bytesDown}, " +
            "sessionTimeouts=${stats.sessionTimeouts}, sessionEof=${stats.sessionEof}, " +
            "sessionClientClosed=${stats.sessionClientClosed}, sessionSocketClosed=${stats.sessionSocketClosed}, " +
            "sessionUnexpectedErrors=${stats.sessionUnexpectedErrors}, configuredRouteMode=${stats.routeMode}, " +
            "effectiveRouteMode=${stats.effectiveRouteMode}, previousEffectiveRouteMode=${stats.previousEffectiveRouteMode ?: "none"}, " +
            "lastRouteChangeReason=${stats.lastRouteChangeReason}, lastRouteChangeTimeMs=${stats.lastRouteChangeTimeMs ?: "unknown"}, " +
            "networkAtLastRouteChange=${stats.networkAtLastRouteChange}, lastRouteUsed=${stats.lastRouteUsed ?: "none"}, " +
            "directTimeouts=${stats.directTimeouts}, cfConnections=${stats.cfProxyConnections}, " +
            "cfErrors=${stats.cfProxyErrors}, lastCfDomain=${stats.lastCfDomain ?: "none"}, poolHits=${stats.poolHits}, " +
            "poolMisses=${stats.poolMisses}, poolRefillErrors=${stats.poolRefillErrors}, poolStale=${stats.poolStale}"
    }
}
