package com.flowseal.tgwsandroid.proxy

import com.flowseal.tgwsandroid.config.AppConfig
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal Android-independent runtime configuration for the local proxy core.
 *
 * Mirrors the local runtime fields consumed by upstream
 * `proxy/config.py::ProxyConfig` and `proxy/tg_ws_proxy.py::run_proxy` without
 * Android UI or Service dependencies.
 */
data class ProxyServerConfig(
    val host: String,
    val port: Int,
    val secretHex: String,
    val dcRedirects: Map<Int, String>,
    val bufferSizeBytes: Int = BridgeSession.DEFAULT_BUFFER_SIZE,
    /** Number of idle direct WebSocket connections to keep warm per DC/media route. */
    val poolSize: Int = 0,
    /** Enables bundled Cloudflare-proxy fallback when direct WebSocket routing is unavailable. */
    val cfproxyEnabled: Boolean = false,
    /** Enables conservative mobile cf_first CF WebSocket prewarm pooling. */
    val cfPoolEnabled: Boolean = true,
    /** Bundled or caller-provided CF proxy base domains. Remote refresh is intentionally not ported yet. */
    val cfProxyDomains: List<String> = CfProxyDomains.defaults,
    val routeMode: NetworkRouteMode = NetworkRouteMode.AUTO,
    val networkStatus: String = "unknown",
    val directFallbackTimeoutMs: Int = 2_000,
) {
    val effectiveRouteMode: NetworkRouteMode = RouteStrategy.resolve(routeMode, networkStatus)
    companion object {
        fun fromAppConfig(
            appConfig: AppConfig,
            networkStatus: String = "unknown",
        ): ProxyServerConfig = ProxyServerConfig(
            host = appConfig.host,
            port = appConfig.port,
            secretHex = appConfig.secret,
            dcRedirects = appConfig.dcIp.toDcRedirects(),
            bufferSizeBytes = appConfig.bufKb * 1024,
            poolSize = appConfig.poolSize,
            cfproxyEnabled = appConfig.cfproxy,
            cfPoolEnabled = true,
            cfProxyDomains = appConfig.cfproxyUserDomain.ifEmpty { CfProxyDomains.defaults },
            routeMode = appConfig.routeMode,
            networkStatus = networkStatus,
        )

        private fun List<String>.toDcRedirects(): Map<Int, String> = buildMap {
            for (entry in this@toDcRedirects) {
                val parts = entry.split(':', limit = 2)
                if (parts.size != 2) continue
                val dcId = parts[0].trim().toIntOrNull() ?: continue
                val host = parts[1].trim()
                if (host.isNotEmpty()) put(dcId, host)
            }
        }
    }
}


/** User-facing classification for invalid MTProto handshake diagnostics. */
enum class HandshakeDiagnosticState(val configValue: String) {
    NORMAL("normal"),
    BACKGROUND_NOISE("background_noise"),
    SUSPECTED_SECRET_MISMATCH("suspected_secret_mismatch"),
    FATAL_SECRET_MISMATCH("fatal_secret_mismatch"),
}

data class HandshakeDiagnostic(
    val state: HandshakeDiagnosticState,
    val reason: String,
    val recommendation: String? = null,
    val secondsSinceLastAcceptedHandshake: Long? = null,
    val secondsSinceLastSuccessfulRoute: Long? = null,
)

/** Immutable snapshot of lightweight local proxy counters. */

data class SessionStats(
    val acceptedHandshakes: Long = 0,
    val clientClosedSessions: Long = 0,
    val veryShortSessions: Long = 0,
    val connectionResets: Long = 0,
    val sessionUnexpectedErrors: Long = 0,
)

data class PoolStats(
    val poolHits: Long = 0,
    val poolMisses: Long = 0,
    val poolStale: Long = 0,
    val poolRefillErrors: Long = 0,
)

data class CfStats(
    val cf429: Long = 0,
    val cfQueueFailures: Long = 0,
    val recentCfQueueFailures: Long = 0,
    val recentCfTimeouts: Long = 0,
    val cfPressureReason: String? = null,
)

data class RouteStats(
    val routeChanged: Long = 0,
    val noRoute: Long = 0,
    val unsupportedDc: Long = 0,
    val effectiveRoute: String? = null,
    val routeMode: String? = null,
    val lastRouteChangeReason: String? = null,
)

data class ClientExperienceStats(
    val likelyReconnectBurst: Boolean = false,
    val likelyTelegramDisabledProxy: Boolean = false,
)

data class TelemetryDiagnosticStats(
    val foregroundServiceActive: Boolean = false,
    val wakeLockActiveAtLastMarker: Boolean = false,
    val previousRunEndedUnexpectedly: Boolean = false,
    val unexpectedStopDetected: Boolean = false,
    val batteryRestrictionDetected: Boolean = false,
)

data class ClientExperienceDiagnostics(
    val likelyReconnectBurst: Boolean = false,
    val likelyTelegramDisabledProxy: Boolean = false,
    val recentAcceptedHandshakes: Long = 0,
    val recentClientClosedSessions: Long = 0,
    val recentVeryShortClientClosedSessions: Long = 0,
    val recentShortRemoteEofSessions: Long = 0,
    val recentConnectionResetSessions: Long = 0,
    val recentDirectTimeouts: Long = 0,
    val recentPoolMisses: Long = 0,
    val recentPoolRefillErrors: Long = 0,
    val recentPoolStale: Long = 0,
    val clientExperienceDirectDowngrades: Long = 0,
    val lastClientExperienceDirectDowngradeReason: String? = null,
    val lastClientExperienceDirectDowngradeTimeMs: Long = 0,
    val recentUnsupportedDcByDc: Map<Int, Long> = emptyMap(),
    val recentNoRouteByDc: Map<Int, Long> = emptyMap(),
    val recentCfQueueControlledFailures: Long = 0,
    val recentCfConnectQueueTimeouts: Long = 0,
    val timeToFirstSuccessfulRouteAfterIdleMs: Long? = null,
    val wakeBurstPrewarmTriggers: Long = 0,
    val wakeBurstPrewarmSkippedNoDirectRedirect: Long = 0,
    val wakeBurstPrewarmSkippedCooldown: Long = 0,
    val wakeBurstPrewarmAttempts: Long = 0,
    val wakeBurstPrewarmSuccesses: Long = 0,
    val wakeBurstPrewarmFailures: Long = 0,
    val lastWakeBurstPrewarmTimeMs: Long = 0,
    val lastWakeBurstPrewarmDc: Int? = null,
    val lastWakeBurstPrewarmError: String? = null,
    val idlePoolMaintenanceRuns: Long = 0,
    val idlePoolMaintenanceSkippedNetwork: Long = 0,
    val idlePoolMaintenanceSkippedRoute: Long = 0,
    val idlePoolMaintenanceSkippedDirectHealth: Long = 0,
    val idlePoolMaintenanceSkippedActiveSessions: Long = 0,
    val idlePoolMaintenanceAttempts: Long = 0,
    val idlePoolMaintenanceSuccesses: Long = 0,
    val idlePoolMaintenanceFailures: Long = 0,
    val lastIdlePoolMaintenanceTimeMs: Long = 0,
    val lastIdlePoolMaintenanceError: String? = null,
)

data class CfFirstRecoveryDiagnostics(
    val wifiAttempts: Long = 0,
    val wifiSuccesses: Long = 0,
    val wifiFailures: Long = 0,
    val lastWifiError: String? = null,
    val lastWifiTimeMs: Long? = null,
    val attempts: Long = 0,
    val successes: Long = 0,
    val failures: Long = 0,
    val lastReason: String? = null,
)

data class EmergencyDirectFallbackDiagnostics(
    val attempts: Long = 0,
    val successes: Long = 0,
    val failures: Long = 0,
    val suppressed: Long = 0,
    val lastReason: String? = null,
    val lastTimeMs: Long? = null,
)

data class ProxyRecoveryDiagnostics(
    val cfFirst: CfFirstRecoveryDiagnostics = CfFirstRecoveryDiagnostics(),
    val emergencyDirectFallback: EmergencyDirectFallbackDiagnostics = EmergencyDirectFallbackDiagnostics(),
)

data class ConnectionSocketEndDiagnostics(
    val count: Long = 0,
    val lastTimeMs: Long = 0,
    val lastRoute: String? = null,
    val lastDc: Int? = null,
    val lastMedia: Boolean? = null,
)

data class ProxySessionEndDiagnostics(
    val connectionReset: ConnectionSocketEndDiagnostics = ConnectionSocketEndDiagnostics(),
    val connectionTimedOut: ConnectionSocketEndDiagnostics = ConnectionSocketEndDiagnostics(),
)

data class DirectPoolDiagnosticsSnapshot(
    val readyByKey: Map<String, Int> = emptyMap(),
    val inFlightRefillsByKey: Map<String, Int> = emptyMap(),
    val hitsByKey: Map<String, Long> = emptyMap(),
    val missesByKey: Map<String, Long> = emptyMap(),
    val refillAttemptsByKey: Map<String, Long> = emptyMap(),
    val refillSuccessesByKey: Map<String, Long> = emptyMap(),
    val refillErrorsByKey: Map<String, Long> = emptyMap(),
    val refillFailureWavesByKey: Map<String, Int> = emptyMap(),
    val refillBackoffRemainingMsByKey: Map<String, Long> = emptyMap(),
    val refillBackoffSuppressedByKey: Map<String, Long> = emptyMap(),
    val closedIdlePrunedByKey: Map<String, Long> = emptyMap(),
    val staleByKey: Map<String, Long> = emptyMap(),
    val lastRefillErrorByKey: Map<String, String> = emptyMap(),
    val lastRefillTimeMsByKey: Map<String, Long> = emptyMap(),
    val lastHitTimeMsByKey: Map<String, Long> = emptyMap(),
    val lastMissTimeMsByKey: Map<String, Long> = emptyMap(),
)

class ProxyServerStats {
    var connectionsTotal: Long = 0
    var connectionsActive: Int = 0
    var connectionsBad: Long = 0
    var wsConnectErrors: Long = 0
    var cfProxyConnections: Long = 0
    var cfProxyErrors: Long = 0
    var bytesUp: Long = 0
    var bytesDown: Long = 0
    var poolHits: Long = 0
    var poolMisses: Long = 0
    var poolRefillErrors: Long = 0
    var poolStale: Long = 0
    var directPoolDiagnostics: DirectPoolDiagnosticsSnapshot = DirectPoolDiagnosticsSnapshot()
    var cfPoolDiagnostics: DirectPoolDiagnosticsSnapshot = DirectPoolDiagnosticsSnapshot()
    var cfPoolHits: Long = 0
    var cfPoolMisses: Long = 0
    var cfPoolRefillAttempts: Long = 0
    var cfPoolRefillSuccesses: Long = 0
    var cfPoolRefillErrors: Long = 0
    var cfPoolStale: Long = 0
    var cfPoolLastDomainByKey: Map<String, String> = emptyMap()
    var sessionTimeouts: Long = 0
    var sessionEof: Long = 0
    var sessionClientClosed: Long = 0
    var sessionSocketClosed: Long = 0
    var sessionUnexpectedErrors: Long = 0
    var sessionEndDiagnostics: ProxySessionEndDiagnostics = ProxySessionEndDiagnostics()
    var sessionRemoteEof: Long = 0
    var sessionRemoteIdleEof: Long = 0
    var sessionRemoteEofShort: Long = 0
    var lastRemoteEofTimeMs: Long = 0
    var lastRemoteEofDurationMs: Long = 0
    var lastRemoteEofRoute: String? = null
    var lastRemoteEofDc: Int? = null
    var lastRemoteEofMedia: Boolean? = null
    var routeMode: String = NetworkRouteMode.AUTO.configValue
    var effectiveRouteMode: String = NetworkRouteMode.DIRECT_FIRST.configValue
    var previousEffectiveRouteMode: String? = null
    var lastRouteChangeReason: String = "initial"
    var lastRouteChangeSource: String = "initial"
    var lastRouteChangeTimeMs: Long? = null
    var networkAtLastRouteChange: String = "unknown"
    var lastRouteEvaluationReason: String = "initial"
    var lastRouteEvaluationSource: String = "initial"
    var lastRouteEvaluationTimeMs: Long? = null
    var networkAtLastRouteEvaluation: String = "unknown"
    var routeEvaluations: Long = 0
    var routeNoopEvaluations: Long = 0
    var lastRouteUsed: String? = null
    var statsSnapshotTimeMs: Long = 0
    var lastEffectiveRouteModeUpdateTimeMs: Long? = null
    var lastRouteUsedUpdateTimeMs: Long? = null
    var directTimeouts: Long = 0
    var frontingAttempts: Long = 0
    var frontingSuccesses: Long = 0
    var frontingFailures: Long = 0
    var frontingFirstAttempts: Long = 0
    var frontingFallbackAttempts: Long = 0
    var frontingPreferredKeys: List<String> = emptyList()
    var lastFrontingError: String? = null
    var lastFrontingTimeMs: Long = 0
    var lastCfDomain: String? = null
    var directAttempts: Long = 0
    var directAttemptsSkippedBecauseRoute: Long = 0
    var directTargetIpCooldownHits: Long = 0
    var directTargetIpCooldownSets: Long = 0
    var directTargetIpCooldownClears: Long = 0
    var directAttemptsSkippedBecauseTargetIpCooldown: Long = 0
    var directPoolSkippedBecauseTargetIpCooldown: Long = 0
    var lastDirectTargetIpCooldownTarget: String? = null
    var lastDirectTargetIpCooldownReason: String? = null
    var lastDirectTargetIpCooldownSetTimeMs: Long = 0
    var directTargetIpCooldownUntilByTarget: Map<String, Long> = emptyMap()
    var poolRefillsCancelled: Long = 0
    var poolResultsDiscardedAfterRouteChange: Long = 0
    var routeChangesImmediate: Long = 0
    var networkNoneEvents: Long = 0
    var directHealthState: String = DirectHealthState.UNKNOWN.configValue
    var directHealthSuccesses: Long = 0
    var directHealthFailures: Long = 0
    var directDowngrades: Long = 0
    var directPromotions: Long = 0
    var directCooldownUntil: Long = 0
    var routeSettlingUntil: Long = 0
    var directProbeLastError: String? = null
    var directProbeLastSuccessTime: Long? = null
    var directProbeSkippedBecauseAlreadyHealthy: Long = 0
    var wifiCapabilityEventsIgnored: Long = 0
    var routeChurnAvoided: Long = 0
    var directProbeThrottleUntil: Long = 0
    var mobileDirectRescueAttempts: Long = 0
    var mobileDirectRescueSuccesses: Long = 0
    var mobileDirectRescueFailures: Long = 0
    var mobileDirectRescueSuppressed: Long = 0
    var mobileRescueSkippedBecauseNetworkChanged: Long = 0
    var mobileNetworkGenerationChanges: Long = 0
    var mobileToMobileRecoveryAttempts: Long = 0
    var mobileToMobileRecoverySuccesses: Long = 0
    var mobileToMobileRecoveryFailures: Long = 0
    var noneToMobileRecoveryAttempts: Long = 0
    var noneToMobileRecoverySuccesses: Long = 0
    var noneToMobileRouteWaitStaleCount: Long = 0
    var routeWaitResumedAfterMobileAvailable: Long = 0
    var routeWaitRecheckedNetworkGeneration: Long = 0
    var mobileRecoveryCfPressureResetCount: Long = 0
    var mobileRecoveryDirectCooldownResetCount: Long = 0
    var mobileRecoveryFirstSuccessLatencyMs: Long? = null
    var mobileRecoveryCfFirstSuccessLatencyMs: Long? = null
    var mobileRecoveryDirectRescueTimeoutCount: Long = 0
    var mobileRecoveryNoRouteDuringSettlingCount: Long = 0
    var lastMobileRecoveryReason: String? = null
    var lastMobileRecoveryNetworkGeneration: Long = 0
    var lastMobileRecoveryResult: String? = null
    var wifiDirectRecoveryAttempts: Long = 0
    var wifiDirectRecoverySuccesses: Long = 0
    var wifiDirectRecoveryFailures: Long = 0
    var recoveryDiagnostics: ProxyRecoveryDiagnostics = ProxyRecoveryDiagnostics()
    var lastNetworkTypeAtRouteAttempt: String = "unknown"
    var routeAttemptNetworkGeneration: Long = 0
    var routeAttemptNetworkChangedBeforeSelection: Long = 0
    var mobileDirectRescueCooldownUntil: Map<Int, Long> = emptyMap()
    var mobileDirectRescueLastError: Map<Int, String> = emptyMap()
    var mobileDirectRescueLastSuccessTime: Map<Int, Long> = emptyMap()
    var cfHealthEnabled: Boolean = false
    var cfDomainsTotal: Int = 0
    var cfDomainsInCooldown: Int = 0
    var cfLastSelectedDomain: String? = null
    var cfLastSelectedReason: String? = null
    var cfLastConnectLatencyMs: Long? = null
    var cfBestDomainByDc: Map<Int, String> = emptyMap()
    var cf429Count: Long = 0
    var cf503Count: Long = 0
    var cfUnknownHostCount: Long = 0
    var cfTimeoutCount: Long = 0
    var cfCooldownSkips: Long = 0
    var cfAllDomainsInCooldownFallbacks: Long = 0
    var cfInflightSkips: Long = 0
    var cfInflightWaits: Long = 0
    var cfMaxInflightPerDomainReached: Long = 0
    var cfActiveConnectsByDc: Map<Int, Int> = emptyMap()
    var cfConnectQueueWaits: Long = 0
    var cfConnectQueueTimeouts: Long = 0
    var cfQueueControlledFailures: Long = 0
    var cfQueueWaitMs: Long = 0
    var cfNoRouteAvoidedByInflightWait: Long = 0
    var cfInflightWaitBeforeNoRoute: Long = 0
    var cfInflightWaitBeforeNoRouteMs: Long = 0
    var cfInflightRetrySuccesses: Long = 0
    var cfInflightRetryFailures: Long = 0
    var cfMaxConcurrentConnectsByDc: Map<Int, Int> = emptyMap()
    var cf429BackoffCount: Long = 0
    var cfAllCooldownWaits: Long = 0
    var cfAllCooldownWaitMs: Long = 0
    var cfAllCooldownCircuitOpenCount: Long = 0
    var cfAllCooldownAttemptsAllowed: Long = 0
    var cfAllCooldownAttemptsSuppressed: Long = 0
    var cfAllCooldownControlledFailures: Long = 0
    var cfAllCooldownCircuitOpenByDc: Map<Int, Long> = emptyMap()
    var cfAllCooldownSingleAttempts: Long = 0
    var cfAllCooldownSingleAttemptFailures: Long = 0
    var cfAllCooldownStoppedCycles: Long = 0
    var cfPressureLevelByDc: Map<Int, String> = emptyMap()
    var cfPressureScoreByDc: Map<Int, Long> = emptyMap()
    var cfPressureRecentSuccessByDc: Map<Int, Long> = emptyMap()
    var cfPressureRecent429ByDc: Map<Int, Long> = emptyMap()
    var cfPressureRecentTimeoutByDc: Map<Int, Long> = emptyMap()
    var cfPressureRecentUnknownHostByDc: Map<Int, Long> = emptyMap()
    var cfPressureRecentQueueFailureByDc: Map<Int, Long> = emptyMap()
    var cfPressureRecentAllCooldownSuppressedByDc: Map<Int, Long> = emptyMap()
    var cfPressureRecentMaxInflightByDc: Map<Int, Long> = emptyMap()
    var cfPressureRecentRouteFailureAfterCfByDc: Map<Int, Long> = emptyMap()
    var cfPressureAllDomainsCooldownByDc: Map<Int, Long> = emptyMap()
    var cfPressureReasonByDc: Map<Int, String> = emptyMap()
    var cfPressureProbeAllowed: Long = 0
    var cfPressureProbeSuppressed: Long = 0
    var cfPressureControlledFailures: Long = 0
    var cfPressureLimitedAttempts: Long = 0
    var cfPressureLevelChanges: Long = 0
    var cfPressureNextProbeAtByDc: Map<Int, Long> = emptyMap()
    var cfTransientNetworkFailures: Long = 0
    var cfFailuresIgnoredBecauseNetworkChanged: Long = 0
    var cfCooldownsSkippedBecauseNetworkSettling: Long = 0
    var cfTransientCooldownsClearedOnNetworkAvailable: Long = 0
    var networkSettlingWaits: Long = 0
    var networkSettlingWaitMs: Long = 0
    var networkSettlingResumedAfterAvailable: Long = 0
    var networkSettlingControlledFailures: Long = 0
    var networkSettlingStaleAttemptsIgnored: Long = 0
    var networkSettlingUntilMs: Long = 0
    var lastNetworkLostAtMs: Long = 0
    var lastNetworkAvailableAtMs: Long = 0
    var cfHealthDomains: List<CfDomainSnapshot> = emptyList()
    var recentInvalidHandshakeCount: Long = 0
    var recentAcceptedHandshakeCount: Long = 0
    var lastInvalidHandshakeTimeMs: Long = 0
    var lastAcceptedHandshakeTimeMs: Long = 0
    var lastSuccessfulRouteTimeMs: Long = 0
    var networkGeneration: Long = 0
    var clientExperience: ClientExperienceDiagnostics = ClientExperienceDiagnostics()
    var unsupportedDc: Long = 0

    val sessions: SessionStats
        get() = SessionStats(
            acceptedHandshakes = connectionsTotal,
            clientClosedSessions = sessionClientClosed,
            veryShortSessions = sessionRemoteEofShort,
            connectionResets = sessionEndDiagnostics.connectionReset.count,
            sessionUnexpectedErrors = sessionUnexpectedErrors,
        )

    val pool: PoolStats
        get() = PoolStats(
            poolHits = poolHits,
            poolMisses = poolMisses,
            poolStale = poolStale,
            poolRefillErrors = poolRefillErrors,
        )

    val cf: CfStats
        get() = CfStats(
            cf429 = cf429Count,
            cfQueueFailures = cfQueueControlledFailures,
            recentCfQueueFailures = clientExperience.recentCfQueueControlledFailures,
            recentCfTimeouts = clientExperience.recentCfConnectQueueTimeouts,
            cfPressureReason = cfPressureReasonByDc.values.firstOrNull(),
        )

    val routes: RouteStats
        get() = RouteStats(
            routeChanged = routeChangesImmediate,
            noRoute = networkNoneEvents,
            unsupportedDc = unsupportedDc,
            effectiveRoute = effectiveRouteMode,
            routeMode = routeMode,
            lastRouteChangeReason = lastRouteChangeReason,
        )

    val clientExperienceStats: ClientExperienceStats
        get() = ClientExperienceStats(
            likelyReconnectBurst = clientExperience.likelyReconnectBurst,
            likelyTelegramDisabledProxy = clientExperience.likelyTelegramDisabledProxy,
        )

    val telemetryDiagnostics: TelemetryDiagnosticStats = TelemetryDiagnosticStats()

    val badHandshakeRatio: Double
        get() = if (connectionsTotal > 0L) connectionsBad.toDouble() / connectionsTotal.toDouble() else 0.0

    val badHandshakeStormCumulative: Boolean
        get() = connectionsTotal >= BAD_HANDSHAKE_STORM_MIN_TOTAL &&
            connectionsBad >= BAD_HANDSHAKE_STORM_MIN_BAD &&
            badHandshakeRatio >= BAD_HANDSHAKE_STORM_MIN_RATIO

    val recentBadHandshakeRatio: Double
        get() {
            val total = recentInvalidHandshakeCount + recentAcceptedHandshakeCount
            return if (total > 0L) recentInvalidHandshakeCount.toDouble() / total.toDouble() else 0.0
        }

    val handshakeDiagnostic: HandshakeDiagnostic
        get() = classifyHandshakeDiagnostics(System.currentTimeMillis())

    val handshakeDiagnosticState: String
        get() = handshakeDiagnostic.state.configValue

    val handshakeDiagnosticReason: String
        get() = handshakeDiagnostic.reason

    val badHandshakeRecommendation: String
        get() = handshakeDiagnostic.recommendation ?: "none"

    val secondsSinceLastAcceptedHandshake: Long?
        get() = secondsSince(lastAcceptedHandshakeTimeMs, System.currentTimeMillis())

    val secondsSinceLastSuccessfulRoute: Long?
        get() = secondsSince(lastSuccessfulRouteTimeMs, System.currentTimeMillis())

    val badHandshakeStormRecent: Boolean
        get() = handshakeDiagnostic.state == HandshakeDiagnosticState.SUSPECTED_SECRET_MISMATCH ||
            handshakeDiagnostic.state == HandshakeDiagnosticState.FATAL_SECRET_MISMATCH

    /** Diagnostics/compat only; normal UI must use badHandshakeStormRecent explicitly. */
    val badHandshakeStorm: Boolean
        get() = badHandshakeStormRecent

    private fun classifyHandshakeDiagnostics(now: Long): HandshakeDiagnostic {
        val acceptedAgeMs = ageMs(lastAcceptedHandshakeTimeMs, now)
        val routeAgeMs = ageMs(lastSuccessfulRouteTimeMs, now)
        val secondsSinceAccepted = secondsSince(lastAcceptedHandshakeTimeMs, now)
        val secondsSinceRoute = secondsSince(lastSuccessfulRouteTimeMs, now)
        val activeRouteSession = connectionsActive > 0 && !lastRouteUsed.isNullOrBlank() &&
            !lastRouteUsed.equals("none", ignoreCase = true)
        val freshAccepted = acceptedAgeMs?.let { it <= BAD_HANDSHAKE_FRESH_SUCCESS_MS } == true
        val freshRoute = routeAgeMs?.let { it <= BAD_HANDSHAKE_FRESH_SUCCESS_MS } == true
        val recentAccepted = recentAcceptedHandshakeCount > 0L
        val recentRouteHealth = directHealthState.equals("healthy", ignoreCase = true) ||
            directHealthSuccesses > 0L || cfProxyConnections > 0L ||
            (!lastRouteUsed.isNullOrBlank() && !lastRouteUsed.equals("none", ignoreCase = true))

        if (recentInvalidHandshakeCount <= 0L) {
            return HandshakeDiagnostic(
                state = HandshakeDiagnosticState.NORMAL,
                reason = "no_recent_invalid_handshakes",
                secondsSinceLastAcceptedHandshake = secondsSinceAccepted,
                secondsSinceLastSuccessfulRoute = secondsSinceRoute,
            )
        }

        if (freshAccepted || freshRoute || activeRouteSession || recentAccepted) {
            return HandshakeDiagnostic(
                state = HandshakeDiagnosticState.BACKGROUND_NOISE,
                reason = when {
                    activeRouteSession -> "invalid_handshake_noise_with_active_route_session"
                    freshRoute -> "invalid_handshake_noise_with_recent_successful_route"
                    freshAccepted || recentAccepted -> "invalid_handshake_noise_with_recent_accepted_handshake"
                    else -> "invalid_handshake_noise_with_recent_success"
                },
                secondsSinceLastAcceptedHandshake = secondsSinceAccepted,
                secondsSinceLastSuccessfulRoute = secondsSinceRoute,
            )
        }

        val recentInvalidDominates = recentInvalidHandshakeCount >= BAD_HANDSHAKE_RECENT_MIN_INVALID &&
            recentInvalidHandshakeCount >= (recentAcceptedHandshakeCount * BAD_HANDSHAKE_RECENT_INVALID_TO_ACCEPTED_MULTIPLIER) + BAD_HANDSHAKE_RECENT_INVALID_MARGIN &&
            recentBadHandshakeRatio >= BAD_HANDSHAKE_STORM_MIN_RATIO
        val noAcceptedForThreshold = acceptedAgeMs == null || acceptedAgeMs >= BAD_HANDSHAKE_NO_SUCCESS_RECOMMENDATION_MS
        val noRouteForThreshold = routeAgeMs == null || routeAgeMs >= BAD_HANDSHAKE_NO_SUCCESS_RECOMMENDATION_MS
        val noWorkingSession = connectionsActive <= 0

        if (recentInvalidDominates && recentAcceptedHandshakeCount == 0L && noAcceptedForThreshold && noRouteForThreshold && noWorkingSession) {
            return HandshakeDiagnostic(
                state = HandshakeDiagnosticState.FATAL_SECRET_MISMATCH,
                reason = "many_recent_invalid_handshakes_without_accepted_handshake_or_successful_route",
                recommendation = BAD_HANDSHAKE_RECONNECT_RECOMMENDATION,
                secondsSinceLastAcceptedHandshake = secondsSinceAccepted,
                secondsSinceLastSuccessfulRoute = secondsSinceRoute,
            )
        }

        if (recentInvalidDominates && noWorkingSession && !recentRouteHealth) {
            return HandshakeDiagnostic(
                state = HandshakeDiagnosticState.SUSPECTED_SECRET_MISMATCH,
                reason = "recent_invalid_handshakes_dominate_without_confirmed_route_health",
                recommendation = BAD_HANDSHAKE_RECONNECT_RECOMMENDATION,
                secondsSinceLastAcceptedHandshake = secondsSinceAccepted,
                secondsSinceLastSuccessfulRoute = secondsSinceRoute,
            )
        }

        return HandshakeDiagnostic(
            state = HandshakeDiagnosticState.BACKGROUND_NOISE,
            reason = "invalid_handshake_noise_without_actionable_secret_mismatch_signal",
            secondsSinceLastAcceptedHandshake = secondsSinceAccepted,
            secondsSinceLastSuccessfulRoute = secondsSinceRoute,
        )
    }

    private fun ageMs(timestampMs: Long, now: Long): Long? = if (timestampMs > 0L) (now - timestampMs).coerceAtLeast(0L) else null

    private fun secondsSince(timestampMs: Long, now: Long): Long? = ageMs(timestampMs, now)?.div(1_000L)

    companion object {
        const val BAD_HANDSHAKE_STORM_MIN_TOTAL: Long = 100
        const val BAD_HANDSHAKE_STORM_MIN_BAD: Long = 50
        const val BAD_HANDSHAKE_STORM_MIN_RATIO: Double = 0.5
        const val BAD_HANDSHAKE_RECENT_WINDOW_MS: Long = 15_000
        const val BAD_HANDSHAKE_RECENT_MIN_INVALID: Long = 100
        const val BAD_HANDSHAKE_RECENT_INVALID_TO_ACCEPTED_MULTIPLIER: Long = 3
        const val BAD_HANDSHAKE_RECENT_INVALID_MARGIN: Long = 50
        const val BAD_HANDSHAKE_FRESH_SUCCESS_MS: Long = 15_000
        const val BAD_HANDSHAKE_NO_SUCCESS_RECOMMENDATION_MS: Long = 45_000
        const val BAD_HANDSHAKE_RECONNECT_RECOMMENDATION: String = "Telegram подключается с неправильным secret. Отключите proxy в Telegram, закройте Telegram и подключите заново по актуальной ссылке."
    }
}


internal const val SESSION_REMOTE_IDLE_EOF_MIN_DURATION_MS: Long = 85_000L

internal data class SessionEndClassification(
    val reason: String,
    val debugDetail: String? = null,
    val remoteEof: Boolean = false,
    val remoteIdleEof: Boolean = false,
)

internal fun classifySessionEnd(
    rawReason: String,
    error: Throwable?,
    durationMs: Long,
): SessionEndClassification {
    val lower = rawReason.lowercase()
    return when {
        lower.contains("client closed") -> SessionEndClassification("client_closed", rawReason.takeUnless { it == "client closed" })
        lower.contains("sockettimeoutexception") || lower.contains("read timed out") || lower == "timeout" ->
            SessionEndClassification("timeout", rawReason)
        lower.contains("websocket closed") || lower.contains("socket closed") ->
            SessionEndClassification("socket_closed", rawReason.takeUnless { it == "websocket closed" || it == "socket closed" })
        error is SocketException && error.message.orEmpty().contains("Connection reset", ignoreCase = true) ||
            lower.contains("socketexception: connection reset") ->
            SessionEndClassification("connection_reset", rawReason)
        error is SocketException && error.message.orEmpty().contains("Connection timed out", ignoreCase = true) ||
            lower.contains("socketexception: connection timed out") ->
            SessionEndClassification("connection_timed_out", rawReason)
        error is EOFException || lower.contains("eofexception") || lower.contains("eof") -> {
            val idle = durationMs >= SESSION_REMOTE_IDLE_EOF_MIN_DURATION_MS
            SessionEndClassification(
                reason = if (idle) "remote_idle_eof" else "remote_eof",
                debugDetail = rawReason,
                remoteEof = true,
                remoteIdleEof = idle,
            )
        }
        lower.contains("exception:") -> SessionEndClassification("unexpected_error", rawReason)
        rawReason == "completed" -> SessionEndClassification("completed")
        else -> SessionEndClassification(rawReason)
    }
}

fun interface ProxyLogger {
    fun log(message: String)
}

internal class InvalidHandshakeLogLimiter(
    private val firstMessagesLimit: Long = 5,
    private val aggregateWindowMs: Long = 5_000,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val seen = AtomicLong(0)
    private val suppressed = AtomicLong(0)
    private val lastAggregateLogMs = AtomicLong(0)

    fun log(
        message: String,
        logger: ProxyLogger,
        diagnostic: () -> HandshakeDiagnostic = { HandshakeDiagnostic(HandshakeDiagnosticState.NORMAL, "unknown") },
    ) {
        val count = seen.incrementAndGet()
        if (count <= firstMessagesLimit) {
            logger.log(message)
            return
        }
        val hidden = suppressed.incrementAndGet()
        val now = nowMs()
        val previous = lastAggregateLogMs.get()
        if (previous == 0L || now - previous >= aggregateWindowMs) {
            if (lastAggregateLogMs.compareAndSet(previous, now)) {
                val repeated = suppressed.getAndSet(0)
                val snapshot = diagnostic()
                val severity = when (snapshot.state) {
                    HandshakeDiagnosticState.SUSPECTED_SECRET_MISMATCH,
                    HandshakeDiagnosticState.FATAL_SECRET_MISMATCH -> "warn"
                    HandshakeDiagnosticState.NORMAL,
                    HandshakeDiagnosticState.BACKGROUND_NOISE -> "debug"
                }
                logger.log(
                    "Invalid MTProto handshake repeated $repeated times in last 5s; " +
                        "severity=$severity classified=${snapshot.state.configValue} reason=${snapshot.reason}",
                )
            }
        } else if (hidden == Long.MAX_VALUE) {
            suppressed.set(0)
        }
    }
}

/** Blocking server socket abstraction so unit tests can run without real networking. */
interface TcpServerTransport {
    fun bind(host: String, port: Int)
    fun accept(): TcpClientTransport?
    fun close()
}

/** Blocking client socket abstraction used by the proxy runtime and bridge. */
interface TcpClientTransport : ClientByteStream {
    val remoteLabel: String
    fun readExact(byteCount: Int): ByteArray?
    override fun read(bufferSize: Int): ByteArray?
    override fun write(data: ByteArray)
    override fun close()
}

fun interface RawWebSocketConnector {
    fun connect(targetHost: String, domain: String, path: String, timeoutMs: Int): WebSocketBinaryStream

    fun connectWithSni(
        targetHost: String,
        domain: String,
        path: String,
        timeoutMs: Int,
        sniHost: String,
    ): WebSocketBinaryStream {
        if (sniHost == domain) return connect(targetHost, domain, path, timeoutMs)
        throw UnsupportedOperationException("custom TLS SNI is not supported by this connector")
    }
}

private data class WebSocketRoute(
    val stream: WebSocketBinaryStream,
    val type: String,
)

private data class WebSocketRouteResult(
    val failed: Boolean,
    val failedBeforeBridge: Boolean,
    val stalePooled: Boolean,
    val retryableStalePooled: Boolean,
)

internal data class CfProxyConnectTarget(
    val targetHost: String,
    val domain: String,
    val path: String,
    val timeoutMs: Int,
) {
    companion object {
        fun forDcBaseDomain(dcId: Int, baseDomain: String): CfProxyConnectTarget {
            val fullDomain = "kws$dcId.$baseDomain"
            return CfProxyConnectTarget(
                targetHost = fullDomain,
                domain = fullDomain,
                path = ProxyServer.DEFAULT_WS_PATH,
                timeoutMs = RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS,
            )
        }
    }
}

fun interface ProxyBridgeRunner {
    fun run(
        client: TcpClientTransport,
        webSocket: WebSocketBinaryStream,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
        counters: BridgeSessionCounters,
    )
}

/**
 * Blocking local TCP proxy runtime that wires the already-ported MTProto, relay,
 * crypto, splitter, RawWebSocket, and BridgeSession pieces together.
 *
 * Mirrors the minimal non-fake-TLS path in upstream
 * `proxy/tg_ws_proxy.py::_handle_client`, `_read_client_init`,
 * `_try_handshake`, `_generate_relay_init`, `_build_crypto_ctx`, and direct
 * `RawWebSocket.connect` usage, then delegates bridge work equivalent to
 * `proxy/bridge.py::bridge_ws_reencrypt`.
 *
 * Future work intentionally excluded from this milestone: CF remote refresh,
 * CF worker/TCP fallback, fake TLS, proxy_protocol, Android
 * ForegroundService/UI/lifecycle, and autostart.
 */
class ProxyServer(
    private val config: ProxyServerConfig,
    private val serverTransport: TcpServerTransport = JavaTcpServerTransport(),
    private val webSocketConnector: RawWebSocketConnector = DefaultRawWebSocketConnector,
    private val bridgeRunner: ProxyBridgeRunner = ProxyBridgeRunner { client, webSocket, cryptoContext, splitter, counters ->
        BridgeSession(
            client = client,
            webSocket = webSocket,
            cryptoContext = cryptoContext,
            splitter = splitter,
            counters = counters,
            bufferSize = config.bufferSizeBytes,
        ).runBlocking()
    },
    private val cfProxyBalancer: CfProxyBalancer = CfProxyBalancer(config.cfProxyDomains),
    private val cfDomainHealth: CfDomainHealth = CfDomainHealth(config.cfProxyDomains),
    private val routeState: RouteState = RouteState(config.routeMode, config.networkStatus),
    private val randomBytes: RelayInit.RandomBytes = RelayInit.SecureRandomBytes,
    private val logger: ProxyLogger = ProxyLogger {},
) {
    private val running = AtomicBoolean(false)
    private val activeClients: MutableSet<TcpClientTransport> = Collections.newSetFromMap(ConcurrentHashMap<TcpClientTransport, Boolean>())
    private val connectionsTotal = AtomicLong(0)
    private val connectionsActive = AtomicInteger(0)
    private val clientExperienceActiveSessions = AtomicInteger(0)
    private val clientExperienceLastIdleAtMs = AtomicLong(System.currentTimeMillis() - WAKE_BURST_IDLE_THRESHOLD_MS)
    private val connectionsBad = AtomicLong(0)
    private val recentInvalidHandshakeTimes = ConcurrentLinkedDeque<Long>()
    private val recentAcceptedHandshakeTimes = ConcurrentLinkedDeque<Long>()
    private val recentIdleWaveAcceptedTimes = ConcurrentLinkedDeque<Long>()
    private val recentClientClosedTimes = ConcurrentLinkedDeque<Long>()
    private val recentVeryShortClientClosedTimes = ConcurrentLinkedDeque<Long>()
    private val recentShortRemoteEofTimes = ConcurrentLinkedDeque<Long>()
    private val recentConnectionResetTimes = ConcurrentLinkedDeque<Long>()
    private val recentDirectTimeoutTimes = ConcurrentLinkedDeque<Long>()
    private val recentPoolMissTimes = ConcurrentLinkedDeque<Long>()
    private val recentPoolRefillErrorTimes = ConcurrentLinkedDeque<Long>()
    private val recentPoolStaleTimes = ConcurrentLinkedDeque<Long>()
    private val recentCfQueueControlledFailureTimes = ConcurrentLinkedDeque<Long>()
    private val recentCfConnectQueueTimeoutTimes = ConcurrentLinkedDeque<Long>()
    private val recentUnsupportedDcTimes = ConcurrentHashMap<Int, ConcurrentLinkedDeque<Long>>()
    private val recentNoRouteTimes = ConcurrentHashMap<Int, ConcurrentLinkedDeque<Long>>()
    private val unsupportedDc = AtomicLong(0)
    private val currentIdleWaveStartMs = AtomicLong(0)
    private val lastTimeToFirstSuccessfulRouteAfterIdleMs = AtomicLong(-1)
    private val lastInvalidHandshakeTimeMs = AtomicLong(0)
    private val lastAcceptedHandshakeTimeMs = AtomicLong(0)
    private val lastSuccessfulRouteTimeMs = AtomicLong(0)
    private val wakeBurstPrewarmTriggers = AtomicLong(0)
    private val wakeBurstPrewarmSkippedNoDirectRedirect = AtomicLong(0)
    private val wakeBurstPrewarmSkippedCooldown = AtomicLong(0)
    private val wakeBurstPrewarmAttempts = AtomicLong(0)
    private val wakeBurstPrewarmSuccesses = AtomicLong(0)
    private val wakeBurstPrewarmFailures = AtomicLong(0)
    private val lastWakeBurstPrewarmTimeMs = AtomicLong(0)
    private val lastWakeBurstPrewarmDc = AtomicReference<Int?>(null)
    private val lastWakeBurstPrewarmError = AtomicReference<String?>(null)
    private val wakeBurstPrewarmCooldownUntilMs = AtomicLong(0)
    private val idlePoolMaintenanceRuns = AtomicLong(0)
    private val idlePoolMaintenanceSkippedNetwork = AtomicLong(0)
    private val idlePoolMaintenanceSkippedRoute = AtomicLong(0)
    private val idlePoolMaintenanceSkippedDirectHealth = AtomicLong(0)
    private val idlePoolMaintenanceSkippedActiveSessions = AtomicLong(0)
    private val idlePoolMaintenanceAttempts = AtomicLong(0)
    private val idlePoolMaintenanceSuccesses = AtomicLong(0)
    private val idlePoolMaintenanceFailures = AtomicLong(0)
    private val lastIdlePoolMaintenanceTimeMs = AtomicLong(0)
    private val lastIdlePoolMaintenanceError = AtomicReference<String?>(null)
    private val idlePoolMaintenanceCooldownUntilMs = AtomicLong(0)
    private val wsConnectErrors = AtomicLong(0)
    private val cfProxyConnections = AtomicLong(0)
    private val cfProxyErrors = AtomicLong(0)
    private val cfNoRouteAvoidedByInflightWait = AtomicLong(0)
    private val cfInflightWaitBeforeNoRoute = AtomicLong(0)
    private val cfInflightWaitBeforeNoRouteMs = AtomicLong(0)
    private val cfInflightRetrySuccesses = AtomicLong(0)
    private val cfInflightRetryFailures = AtomicLong(0)
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)
    private val sessionTimeouts = AtomicLong(0)
    private val sessionEof = AtomicLong(0)
    private val sessionClientClosed = AtomicLong(0)
    private val sessionSocketClosed = AtomicLong(0)
    private val sessionUnexpectedErrors = AtomicLong(0)
    private val sessionConnectionReset = AtomicLong(0)
    private val sessionConnectionTimedOut = AtomicLong(0)
    private val lastConnectionResetTimeMs = AtomicLong(0)
    private val lastConnectionResetRoute = AtomicReference<String?>(null)
    private val lastConnectionResetDc = AtomicReference<Int?>(null)
    private val lastConnectionResetMedia = AtomicReference<Boolean?>(null)
    private val lastConnectionTimedOutTimeMs = AtomicLong(0)
    private val lastConnectionTimedOutRoute = AtomicReference<String?>(null)
    private val lastConnectionTimedOutDc = AtomicReference<Int?>(null)
    private val lastConnectionTimedOutMedia = AtomicReference<Boolean?>(null)
    private val sessionRemoteEof = AtomicLong(0)
    private val sessionRemoteIdleEof = AtomicLong(0)
    private val sessionRemoteEofShort = AtomicLong(0)
    private val lastRemoteEofTimeMs = AtomicLong(0)
    private val lastRemoteEofDurationMs = AtomicLong(0)
    private val lastRemoteEofRoute = AtomicReference<String?>(null)
    private val lastRemoteEofDc = AtomicReference<Int?>(null)
    private val lastRemoteEofMedia = AtomicReference<Boolean?>(null)
    private val poolHits = AtomicLong(0)
    private val poolMisses = AtomicLong(0)
    private val poolRefillErrors = AtomicLong(0)
    private val poolStale = AtomicLong(0)
    private val poolHitsByKey = ConcurrentHashMap<String, AtomicLong>()
    private val poolMissesByKey = ConcurrentHashMap<String, AtomicLong>()
    private val poolRefillAttemptsByKey = ConcurrentHashMap<String, AtomicLong>()
    private val poolRefillSuccessesByKey = ConcurrentHashMap<String, AtomicLong>()
    private val poolRefillErrorsByKey = ConcurrentHashMap<String, AtomicLong>()
    private val poolRefillBackoffSuppressedByKey = ConcurrentHashMap<String, AtomicLong>()
    private val poolStaleByKey = ConcurrentHashMap<String, AtomicLong>()
    private val poolLastRefillErrorByKey = ConcurrentHashMap<String, String>()
    private val poolLastRefillTimeMsByKey = ConcurrentHashMap<String, AtomicLong>()
    private val poolLastHitTimeMsByKey = ConcurrentHashMap<String, AtomicLong>()
    private val poolLastMissTimeMsByKey = ConcurrentHashMap<String, AtomicLong>()
    private val cfPoolHits = AtomicLong(0)
    private val cfPoolMisses = AtomicLong(0)
    private val cfPoolRefillAttempts = AtomicLong(0)
    private val cfPoolRefillSuccesses = AtomicLong(0)
    private val cfPoolRefillErrors = AtomicLong(0)
    private val cfPoolStale = AtomicLong(0)
    private val cfPoolRefillAttemptsByKey = ConcurrentHashMap<String, AtomicLong>()
    private val cfPoolRefillSuccessesByKey = ConcurrentHashMap<String, AtomicLong>()
    private val cfPoolRefillErrorsByKey = ConcurrentHashMap<String, AtomicLong>()
    private val directTimeouts = AtomicLong(0)
    private val directAttempts = AtomicLong(0)
    private val directAttemptsSkippedBecauseRoute = AtomicLong(0)
    private val directTargetIpCooldownHits = AtomicLong(0)
    private val directTargetIpCooldownSets = AtomicLong(0)
    private val directTargetIpCooldownClears = AtomicLong(0)
    private val directAttemptsSkippedBecauseTargetIpCooldown = AtomicLong(0)
    private val directPoolSkippedBecauseTargetIpCooldown = AtomicLong(0)
    private val lastDirectTargetIpCooldownTarget = AtomicReference<String?>(null)
    private val lastDirectTargetIpCooldownReason = AtomicReference<String?>(null)
    private val lastDirectTargetIpCooldownSetTimeMs = AtomicLong(0)
    private val directTargetIpCooldownUntilByTarget = ConcurrentHashMap<String, AtomicLong>()
    private val directTargetIpCooldownReasonByTarget = ConcurrentHashMap<String, String>()
    private val directTargetIpCooldownLoggedUntilByTarget = ConcurrentHashMap<String, AtomicLong>()
    private val directProbeSkippedBecauseAlreadyHealthy = AtomicLong(0)
    private val mobileDirectRescueAttempts = AtomicLong(0)
    private val mobileDirectRescueSuccesses = AtomicLong(0)
    private val mobileDirectRescueFailures = AtomicLong(0)
    private val mobileDirectRescueSuppressed = AtomicLong(0)
    private val mobileRescueSkippedBecauseNetworkChanged = AtomicLong(0)
    private val mobileNetworkGenerationChanges = AtomicLong(0)
    private val mobileToMobileRecoveryAttempts = AtomicLong(0)
    private val mobileToMobileRecoverySuccesses = AtomicLong(0)
    private val mobileToMobileRecoveryFailures = AtomicLong(0)
    private val noneToMobileRecoveryAttempts = AtomicLong(0)
    private val noneToMobileRecoverySuccesses = AtomicLong(0)
    private val noneToMobileRouteWaitStaleCount = AtomicLong(0)
    private val routeWaitResumedAfterMobileAvailable = AtomicLong(0)
    private val routeWaitRecheckedNetworkGeneration = AtomicLong(0)
    private val mobileRecoveryCfPressureResetCount = AtomicLong(0)
    private val mobileRecoveryDirectCooldownResetCount = AtomicLong(0)
    private val mobileRecoveryFirstSuccessLatencyMs = AtomicLong(0)
    private val mobileRecoveryCfFirstSuccessLatencyMs = AtomicLong(0)
    private val mobileRecoveryDirectRescueTimeoutCount = AtomicLong(0)
    private val mobileRecoveryNoRouteDuringSettlingCount = AtomicLong(0)
    private val lastMobileRecoveryReason = AtomicReference<String?>(null)
    private val lastMobileRecoveryNetworkGeneration = AtomicLong(0)
    private val lastMobileRecoveryResult = AtomicReference<String?>(null)
    private val wifiDirectRecoveryAttempts = AtomicLong(0)
    private val wifiDirectRecoverySuccesses = AtomicLong(0)
    private val wifiDirectRecoveryFailures = AtomicLong(0)
    private val wifiCfFirstRecoveryAttempts = AtomicLong(0)
    private val wifiCfFirstRecoverySuccesses = AtomicLong(0)
    private val wifiCfFirstRecoveryFailures = AtomicLong(0)
    private val cfFirstRecoveryAttempts = AtomicLong(0)
    private val cfFirstRecoverySuccesses = AtomicLong(0)
    private val cfFirstRecoveryFailures = AtomicLong(0)
    private val emergencyDirectFallbackAttempts = AtomicLong(0)
    private val emergencyDirectFallbackSuccesses = AtomicLong(0)
    private val emergencyDirectFallbackFailures = AtomicLong(0)
    private val emergencyDirectFallbackSuppressed = AtomicLong(0)
    private val lastWifiCfFirstRecoveryError = AtomicReference<String?>(null)
    private val lastWifiCfFirstRecoveryTimeMs = AtomicLong(0)
    private val lastCfFirstRecoveryReason = AtomicReference<String?>(null)
    private val lastEmergencyDirectFallbackReason = AtomicReference<String?>(null)
    private val emergencyDirectFallbackCooldownUntilMs = AtomicLong(0)
    private val routeAttemptNetworkChangedBeforeSelection = AtomicLong(0)
    private val mobileDirectRescueByDc = ConcurrentHashMap<Int, MobileDirectRescueState>()
    private val wifiCapabilityEventsIgnored = AtomicLong(0)
    private val routeChurnAvoided = AtomicLong(0)
    private val poolRefillsCancelled = AtomicLong(0)
    private val poolResultsDiscardedAfterRouteChange = AtomicLong(0)
    private val routeChangesImmediate = AtomicLong(0)
    private val networkNoneEvents = AtomicLong(0)
    private val networkSettlingWaits = AtomicLong(0)
    private val networkSettlingWaitMs = AtomicLong(0)
    private val networkSettlingResumedAfterAvailable = AtomicLong(0)
    private val networkSettlingControlledFailures = AtomicLong(0)
    private val networkSettlingStaleAttemptsIgnored = AtomicLong(0)
    private val routeGeneration = AtomicLong(0)
    private val networkStateMonitor = Object()
    private val networkSettlingUntilMs = AtomicLong(0)
    private val lastNetworkLostAtMs = AtomicLong(0)
    private val lastNetworkAvailableAtMs = AtomicLong(0)
    @Volatile private var currentNetworkStatus: String = config.networkStatus.ifBlank { "unknown" }
    @Volatile private var lastNetworkTypeAtRouteAttempt: String = config.networkStatus.ifBlank { "unknown" }
    @Volatile private var routeAttemptNetworkGeneration: Long = 0
    @Volatile private var wifiDirectRecoveryUntilMs: Long = 0
    @Volatile private var lastDirectDowngradeTimeMs: Long = 0
    @Volatile private var lastDirectDowngradeReason: String? = null
    private val clientExperienceDirectDowngrades = AtomicLong(0)
    private val lastClientExperienceDirectDowngradeTimeMs = AtomicLong(0)
    private val lastClientExperienceDirectDowngradeReason = AtomicReference<String?>(null)
    @Volatile private var directPoolStaleInWindow: Long = 0
    @Volatile private var lastDirectPoolStaleWindowStartMs: Long = 0
    @Volatile private var lastRouteUsed: String? = null
    private val lastRouteUsedUpdateTimeMs = AtomicLong(0)
    @Volatile private var lastCfDomain: String? = null
    private val invalidHandshakeLogLimiter = InvalidHandshakeLogLimiter()
    private val frontingAttempts = AtomicLong(0)
    private val frontingSuccesses = AtomicLong(0)
    private val frontingFailures = AtomicLong(0)
    private val frontingFirstAttempts = AtomicLong(0)
    private val frontingFallbackAttempts = AtomicLong(0)
    private val lastFrontingError = AtomicReference<String?>(null)
    private val lastFrontingTimeMs = AtomicLong(0)
    private val directFrontingPreferenceState = DirectFrontingPreferenceState()
    private val directFrontingConnector = DirectFrontingConnector(
        normalConnect = { targetHost, domain, path, timeoutMs ->
            webSocketConnector.connect(targetHost, domain, path, timeoutMs)
        },
        frontedConnect = { targetHost, domain, path, timeoutMs, sniHost ->
            webSocketConnector.connectWithSni(targetHost, domain, path, timeoutMs, sniHost)
        },
        state = directFrontingPreferenceState,
        onFrontingAttempt = { key, frontingFirst ->
            frontingAttempts.incrementAndGet()
            if (frontingFirst) frontingFirstAttempts.incrementAndGet() else frontingFallbackAttempts.incrementAndGet()
            lastFrontingTimeMs.set(System.currentTimeMillis())
            logger.log(
                "DC${key.dc} media=${key.isMedia} fronting ${if (frontingFirst) "first" else "fallback"} " +
                    "attempt SNI=${DirectFrontingConnector.DEFAULT_FRONTING_SNI} target=${key.targetHost}",
            )
        },
        onFrontingSuccess = { key, frontingFirst ->
            frontingSuccesses.incrementAndGet()
            lastFrontingError.set(null)
            lastFrontingTimeMs.set(System.currentTimeMillis())
            logger.log("DC${key.dc} media=${key.isMedia} fronting success first=$frontingFirst target=${key.targetHost}")
        },
        onFrontingFailure = { key, frontingFirst, error ->
            frontingFailures.incrementAndGet()
            lastFrontingError.set(failureDetail(error))
            lastFrontingTimeMs.set(System.currentTimeMillis())
            logger.log("DC${key.dc} media=${key.isMedia} fronting failed first=$frontingFirst: ${failureDetail(error)}")
        },
    )
    private val webSocketPool = WebSocketPool(
        poolSize = config.poolSize,
        connector = webSocketConnector,
        refillConnector = DirectPoolRefillConnector { dc, isMedia, targetHost, domain, path, timeoutMs ->
            directFrontingConnector.connect(
                dc = dc,
                isMedia = isMedia,
                targetHost = targetHost,
                domain = domain,
                path = path,
                normalTimeoutMs = timeoutMs,
                networkGeneration = routeGeneration.get(),
            ).stream
        },
        logger = logger,
        onRefillError = { key, source, error ->
            poolRefillErrors.incrementAndGet()
            incrementPoolCounter(poolRefillErrorsByKey, key, source)
            poolLastRefillErrorByKey[poolDiagnosticKey(key, source)] = failureDetail(error)
            poolLastRefillTimeMsByKey.getOrPut(poolDiagnosticKey(key, source)) { AtomicLong(0) }.set(System.currentTimeMillis())
            recordRecentEvent(recentPoolRefillErrorTimes)
            maybeDowngradeDirectRouteForClientExperience()
        },
        onRefillAttempt = { key, source ->
            directAttempts.incrementAndGet()
            incrementPoolCounter(poolRefillAttemptsByKey, key, source)
            poolLastRefillTimeMsByKey.getOrPut(poolDiagnosticKey(key, source)) { AtomicLong(0) }.set(System.currentTimeMillis())
        },
        onRefillSuccess = { key, source ->
            incrementPoolCounter(poolRefillSuccessesByKey, key, source)
            poolLastRefillTimeMsByKey.getOrPut(poolDiagnosticKey(key, source)) { AtomicLong(0) }.set(System.currentTimeMillis())
        },
        onRefillCancelled = { count -> poolRefillsCancelled.addAndGet(count.toLong()) },
        onResultDiscardedAfterRouteChange = { poolResultsDiscardedAfterRouteChange.incrementAndGet() },
        shouldSkipTarget = { key, targetHost -> isDirectTargetIpCooldownActive(targetHost) && config.cfproxyEnabled },
        onSkippedTarget = { key, targetHost, source ->
            directPoolSkippedBecauseTargetIpCooldown.incrementAndGet()
            directTargetIpCooldownHits.incrementAndGet()
            logger.log("DC${key.dc} direct target $targetHost in cooldown; direct pool $source skipped")
        },
        onRefillBackoffSuppressed = { key, source, remainingMs ->
            incrementPoolCounter(poolRefillBackoffSuppressedByKey, key, source)
            logger.log("DC${key.dc} direct WS pool $source suppressed by refill backoff for ${remainingMs}ms")
        },
    )
    private val cfWebSocketPool = CfWebSocketPool(
        connector = webSocketConnector,
        cfDomainHealth = cfDomainHealth,
        logger = logger,
        onAttempt = { key, _ ->
            cfPoolRefillAttempts.incrementAndGet()
            incrementPoolCounter(cfPoolRefillAttemptsByKey, key)
        },
        onSuccess = { key, _ ->
            cfPoolRefillSuccesses.incrementAndGet()
            incrementPoolCounter(cfPoolRefillSuccessesByKey, key)
        },
        onError = { key, _, _ ->
            cfPoolRefillErrors.incrementAndGet()
            incrementPoolCounter(cfPoolRefillErrorsByKey, key)
        },
    )
    private val directRouteHealth = DirectRouteHealth(
        connector = webSocketConnector,
        dcRedirects = config.dcRedirects,
        wsDomainsProvider = ::wsDomains,
        logger = logger,
    )
    private var acceptThread: Thread? = null
    private var idlePoolMaintenanceThread: Thread? = null

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverTransport.bind(config.host, config.port)
        if (isDirectPoolEnabled() && !directRouteHealth.isSettling()) {
            startDirectPoolWarmup("startup")
        } else if (config.poolSize > 0) {
            webSocketPool.disableAndClear()
            logger.log("Direct WS pool warmup skipped because effective route mode ${effectiveRouteMode().configValue}")
        }
        maybeStartAutoWifiDirectProbe(currentNetworkStatus)
        startIdlePoolMaintenanceThread()
        acceptThread = Thread(::acceptLoop, "ProxyServer-accept-${config.host}:${config.port}").also {
            it.isDaemon = true
            it.start()
        }
        logger.log("ProxyServer listening on ${config.host}:${config.port}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        synchronized(networkStateMonitor) { networkStateMonitor.notifyAll() }
        try {
            serverTransport.close()
        } catch (_: Throwable) {
            // Best-effort stop.
        }
        for (client in activeClients.toList()) {
            closeClient(client)
        }
        webSocketPool.closeAll()
        cfWebSocketPool.closeAll()
        joinAcceptThreadBestEffort()
        joinIdlePoolMaintenanceThreadBestEffort()
        logger.log("ProxyServer stopped")
    }

    fun stats(): ProxyServerStats {
        val snapshotTimeMs = System.currentTimeMillis()
        pruneRecentHandshakeWindows(snapshotTimeMs)
        val routeSnapshot = routeState.snapshot()
        val directHealthSnapshot = directRouteHealth.snapshot()
        val cfHealthSnapshot = cfDomainHealth.snapshot()
        val snapshot = ProxyServerStats()
        snapshot.connectionsTotal = connectionsTotal.get()
        snapshot.connectionsActive = connectionsActive.get()
        snapshot.connectionsBad = connectionsBad.get()
        snapshot.wsConnectErrors = wsConnectErrors.get()
        snapshot.cfProxyConnections = cfProxyConnections.get()
        snapshot.cfProxyErrors = cfProxyErrors.get()
        snapshot.bytesUp = bytesUp.get()
        snapshot.bytesDown = bytesDown.get()
        snapshot.sessionTimeouts = sessionTimeouts.get()
        snapshot.sessionEof = sessionEof.get()
        snapshot.sessionClientClosed = sessionClientClosed.get()
        snapshot.sessionSocketClosed = sessionSocketClosed.get()
        snapshot.sessionUnexpectedErrors = sessionUnexpectedErrors.get()
        snapshot.sessionEndDiagnostics = ProxySessionEndDiagnostics(
                connectionReset = ConnectionSocketEndDiagnostics(
                    count = sessionConnectionReset.get(),
                    lastTimeMs = lastConnectionResetTimeMs.get(),
                    lastRoute = lastConnectionResetRoute.get(),
                    lastDc = lastConnectionResetDc.get(),
                    lastMedia = lastConnectionResetMedia.get(),
                ),
                connectionTimedOut = ConnectionSocketEndDiagnostics(
                    count = sessionConnectionTimedOut.get(),
                    lastTimeMs = lastConnectionTimedOutTimeMs.get(),
                    lastRoute = lastConnectionTimedOutRoute.get(),
                    lastDc = lastConnectionTimedOutDc.get(),
                    lastMedia = lastConnectionTimedOutMedia.get(),
                ),
            )
        snapshot.sessionRemoteEof = sessionRemoteEof.get()
        snapshot.sessionRemoteIdleEof = sessionRemoteIdleEof.get()
        snapshot.sessionRemoteEofShort = sessionRemoteEofShort.get()
        snapshot.lastRemoteEofTimeMs = lastRemoteEofTimeMs.get()
        snapshot.lastRemoteEofDurationMs = lastRemoteEofDurationMs.get()
        snapshot.lastRemoteEofRoute = lastRemoteEofRoute.get()
        snapshot.lastRemoteEofDc = lastRemoteEofDc.get()
        snapshot.lastRemoteEofMedia = lastRemoteEofMedia.get()
        snapshot.poolHits = poolHits.get()
        snapshot.poolMisses = poolMisses.get()
        snapshot.poolRefillErrors = poolRefillErrors.get()
        snapshot.poolStale = poolStale.get()
        snapshot.directPoolDiagnostics = DirectPoolDiagnosticsSnapshot(
                readyByKey = webSocketPool.readySnapshot().mapKeys { poolDiagnosticKey(it.key) }.toSortedMap(),
                inFlightRefillsByKey = webSocketPool.pendingRefillsSnapshot().mapKeys { poolDiagnosticKey(it.key) }.toSortedMap(),
                hitsByKey = snapshotPoolLongMap(poolHitsByKey),
                missesByKey = snapshotPoolLongMap(poolMissesByKey),
                refillAttemptsByKey = snapshotPoolLongMap(poolRefillAttemptsByKey),
                refillSuccessesByKey = snapshotPoolLongMap(poolRefillSuccessesByKey),
                refillErrorsByKey = snapshotPoolLongMap(poolRefillErrorsByKey),
                refillFailureWavesByKey = webSocketPool.refillFailuresSnapshot().mapKeys { poolDiagnosticKey(it.key) }.toSortedMap(),
                refillBackoffRemainingMsByKey = webSocketPool.refillBackoffRemainingSnapshot().mapKeys { poolDiagnosticKey(it.key) }.toSortedMap(),
                refillBackoffSuppressedByKey = snapshotPoolLongMap(poolRefillBackoffSuppressedByKey),
                closedIdlePrunedByKey = webSocketPool.closedIdlePrunedSnapshot().mapKeys { poolDiagnosticKey(it.key) }.toSortedMap(),
                staleByKey = snapshotPoolLongMap(poolStaleByKey),
                lastRefillErrorByKey = poolLastRefillErrorByKey.toSortedMap(),
                lastRefillTimeMsByKey = snapshotPoolLongMap(poolLastRefillTimeMsByKey),
                lastHitTimeMsByKey = snapshotPoolLongMap(poolLastHitTimeMsByKey),
                lastMissTimeMsByKey = snapshotPoolLongMap(poolLastMissTimeMsByKey),
            )
        snapshot.cfPoolHits = cfPoolHits.get()
        snapshot.cfPoolMisses = cfPoolMisses.get()
        snapshot.cfPoolRefillAttempts = cfPoolRefillAttempts.get()
        snapshot.cfPoolRefillSuccesses = cfPoolRefillSuccesses.get()
        snapshot.cfPoolRefillErrors = cfPoolRefillErrors.get()
        snapshot.cfPoolStale = cfPoolStale.get()
        snapshot.cfPoolLastDomainByKey = cfWebSocketPool.lastDomainSnapshot().mapKeys { poolDiagnosticKey(it.key) }.toSortedMap()
        snapshot.cfPoolDiagnostics = DirectPoolDiagnosticsSnapshot(
                readyByKey = cfWebSocketPool.readySnapshot().mapKeys { poolDiagnosticKey(it.key) }.toSortedMap(),
                inFlightRefillsByKey = cfWebSocketPool.pendingSnapshot().mapKeys { poolDiagnosticKey(it.key) }.toSortedMap(),
                refillAttemptsByKey = snapshotPoolLongMap(cfPoolRefillAttemptsByKey),
                refillSuccessesByKey = snapshotPoolLongMap(cfPoolRefillSuccessesByKey),
                refillErrorsByKey = snapshotPoolLongMap(cfPoolRefillErrorsByKey),
            )
        snapshot.routeMode = routeSnapshot.configuredRouteMode.configValue
        snapshot.effectiveRouteMode = routeSnapshot.effectiveRouteMode.configValue
        snapshot.previousEffectiveRouteMode = routeSnapshot.previousEffectiveRouteMode?.configValue
        snapshot.lastRouteChangeReason = routeSnapshot.lastRouteChangeReason
        snapshot.lastRouteChangeSource = routeSnapshot.lastRouteChangeSource
        snapshot.lastRouteChangeTimeMs = routeSnapshot.lastRouteChangeTimeMs
        snapshot.networkAtLastRouteChange = routeSnapshot.networkAtLastRouteChange
        snapshot.lastRouteEvaluationReason = routeSnapshot.lastRouteEvaluationReason
        snapshot.lastRouteEvaluationSource = routeSnapshot.lastRouteEvaluationSource
        snapshot.lastRouteEvaluationTimeMs = routeSnapshot.lastRouteEvaluationTimeMs
        snapshot.networkAtLastRouteEvaluation = routeSnapshot.networkAtLastRouteEvaluation
        snapshot.routeEvaluations = routeSnapshot.routeEvaluations
        snapshot.routeNoopEvaluations = routeSnapshot.routeNoopEvaluations
        snapshot.lastRouteUsed = lastRouteUsed
        snapshot.statsSnapshotTimeMs = snapshotTimeMs
        snapshot.lastEffectiveRouteModeUpdateTimeMs = routeSnapshot.lastRouteChangeTimeMs
        snapshot.lastRouteUsedUpdateTimeMs = lastRouteUsedUpdateTimeMs.get().takeIf { it > 0L }
        snapshot.directTimeouts = directTimeouts.get()
        snapshot.frontingAttempts = frontingAttempts.get()
        snapshot.frontingSuccesses = frontingSuccesses.get()
        snapshot.frontingFailures = frontingFailures.get()
        snapshot.frontingFirstAttempts = frontingFirstAttempts.get()
        snapshot.frontingFallbackAttempts = frontingFallbackAttempts.get()
        snapshot.frontingPreferredKeys = directFrontingPreferenceState
            .preferredSnapshot(routeGeneration.get())
            .map { key -> "dc${key.dc}${if (key.isMedia) "m" else ""}@${key.targetHost}" }
            .sorted()
        snapshot.lastFrontingError = lastFrontingError.get()
        snapshot.lastFrontingTimeMs = lastFrontingTimeMs.get()
        snapshot.lastCfDomain = lastCfDomain
        snapshot.directAttempts = directAttempts.get()
        snapshot.directAttemptsSkippedBecauseRoute = directAttemptsSkippedBecauseRoute.get()
        snapshot.directTargetIpCooldownHits = directTargetIpCooldownHits.get()
        snapshot.directTargetIpCooldownSets = directTargetIpCooldownSets.get()
        snapshot.directTargetIpCooldownClears = directTargetIpCooldownClears.get()
        snapshot.directAttemptsSkippedBecauseTargetIpCooldown = directAttemptsSkippedBecauseTargetIpCooldown.get()
        snapshot.directPoolSkippedBecauseTargetIpCooldown = directPoolSkippedBecauseTargetIpCooldown.get()
        snapshot.lastDirectTargetIpCooldownTarget = lastDirectTargetIpCooldownTarget.get()
        snapshot.lastDirectTargetIpCooldownReason = lastDirectTargetIpCooldownReason.get()
        snapshot.lastDirectTargetIpCooldownSetTimeMs = lastDirectTargetIpCooldownSetTimeMs.get()
        snapshot.directTargetIpCooldownUntilByTarget = directTargetIpCooldownSnapshotForStats(snapshotTimeMs)
        snapshot.poolRefillsCancelled = poolRefillsCancelled.get()
        snapshot.poolResultsDiscardedAfterRouteChange = poolResultsDiscardedAfterRouteChange.get()
        snapshot.routeChangesImmediate = routeChangesImmediate.get()
        snapshot.networkNoneEvents = networkNoneEvents.get()
        snapshot.directHealthState = directHealthSnapshot.state.configValue
        snapshot.directHealthSuccesses = directHealthSnapshot.successes
        snapshot.directHealthFailures = directHealthSnapshot.failures
        snapshot.directDowngrades = directHealthSnapshot.downgrades
        snapshot.directPromotions = directHealthSnapshot.promotions
        snapshot.directCooldownUntil = directHealthSnapshot.cooldownUntilMs
        snapshot.routeSettlingUntil = directHealthSnapshot.settlingUntilMs
        snapshot.directProbeLastError = directHealthSnapshot.lastError
        snapshot.directProbeLastSuccessTime = directHealthSnapshot.lastSuccessTimeMs
        snapshot.directProbeSkippedBecauseAlreadyHealthy = directProbeSkippedBecauseAlreadyHealthy.get()
        snapshot.wifiCapabilityEventsIgnored = wifiCapabilityEventsIgnored.get()
        snapshot.routeChurnAvoided = routeChurnAvoided.get()
        snapshot.directProbeThrottleUntil = directHealthSnapshot.probeThrottleUntilMs
        snapshot.mobileDirectRescueAttempts = mobileDirectRescueAttempts.get()
        snapshot.mobileDirectRescueSuccesses = mobileDirectRescueSuccesses.get()
        snapshot.mobileDirectRescueFailures = mobileDirectRescueFailures.get()
        snapshot.mobileDirectRescueSuppressed = mobileDirectRescueSuppressed.get()
        snapshot.mobileRescueSkippedBecauseNetworkChanged = mobileRescueSkippedBecauseNetworkChanged.get()
        snapshot.mobileNetworkGenerationChanges = mobileNetworkGenerationChanges.get()
        snapshot.mobileToMobileRecoveryAttempts = mobileToMobileRecoveryAttempts.get()
        snapshot.mobileToMobileRecoverySuccesses = mobileToMobileRecoverySuccesses.get()
        snapshot.mobileToMobileRecoveryFailures = mobileToMobileRecoveryFailures.get()
        snapshot.noneToMobileRecoveryAttempts = noneToMobileRecoveryAttempts.get()
        snapshot.noneToMobileRecoverySuccesses = noneToMobileRecoverySuccesses.get()
        snapshot.noneToMobileRouteWaitStaleCount = noneToMobileRouteWaitStaleCount.get()
        snapshot.routeWaitResumedAfterMobileAvailable = routeWaitResumedAfterMobileAvailable.get()
        snapshot.routeWaitRecheckedNetworkGeneration = routeWaitRecheckedNetworkGeneration.get()
        snapshot.mobileRecoveryCfPressureResetCount = mobileRecoveryCfPressureResetCount.get()
        snapshot.mobileRecoveryDirectCooldownResetCount = mobileRecoveryDirectCooldownResetCount.get()
        snapshot.mobileRecoveryFirstSuccessLatencyMs = mobileRecoveryFirstSuccessLatencyMs.get().takeIf { it > 0L }
        snapshot.mobileRecoveryCfFirstSuccessLatencyMs = mobileRecoveryCfFirstSuccessLatencyMs.get().takeIf { it > 0L }
        snapshot.mobileRecoveryDirectRescueTimeoutCount = mobileRecoveryDirectRescueTimeoutCount.get()
        snapshot.mobileRecoveryNoRouteDuringSettlingCount = mobileRecoveryNoRouteDuringSettlingCount.get()
        snapshot.lastMobileRecoveryReason = lastMobileRecoveryReason.get()
        snapshot.lastMobileRecoveryNetworkGeneration = lastMobileRecoveryNetworkGeneration.get()
        snapshot.lastMobileRecoveryResult = lastMobileRecoveryResult.get()
        snapshot.wifiDirectRecoveryAttempts = wifiDirectRecoveryAttempts.get()
        snapshot.wifiDirectRecoverySuccesses = wifiDirectRecoverySuccesses.get()
        snapshot.wifiDirectRecoveryFailures = wifiDirectRecoveryFailures.get()
        snapshot.recoveryDiagnostics = ProxyRecoveryDiagnostics(
                cfFirst = CfFirstRecoveryDiagnostics(
                    wifiAttempts = wifiCfFirstRecoveryAttempts.get(),
                    wifiSuccesses = wifiCfFirstRecoverySuccesses.get(),
                    wifiFailures = wifiCfFirstRecoveryFailures.get(),
                    lastWifiError = lastWifiCfFirstRecoveryError.get(),
                    lastWifiTimeMs = lastWifiCfFirstRecoveryTimeMs.get().takeIf { it > 0L },
                    attempts = cfFirstRecoveryAttempts.get(),
                    successes = cfFirstRecoverySuccesses.get(),
                    failures = cfFirstRecoveryFailures.get(),
                    lastReason = lastCfFirstRecoveryReason.get(),
                ),
                emergencyDirectFallback = EmergencyDirectFallbackDiagnostics(
                    attempts = emergencyDirectFallbackAttempts.get(),
                    successes = emergencyDirectFallbackSuccesses.get(),
                    failures = emergencyDirectFallbackFailures.get(),
                    suppressed = emergencyDirectFallbackSuppressed.get(),
                    lastReason = lastEmergencyDirectFallbackReason.get(),
                ),
            )
        snapshot.lastNetworkTypeAtRouteAttempt = lastNetworkTypeAtRouteAttempt
        snapshot.routeAttemptNetworkGeneration = routeAttemptNetworkGeneration
        snapshot.routeAttemptNetworkChangedBeforeSelection = routeAttemptNetworkChangedBeforeSelection.get()
        snapshot.mobileDirectRescueCooldownUntil = mobileDirectRescueByDc.mapValues { (_, state) -> state.cooldownUntilMs }.filterValues { it > 0L }.toSortedMap()
        snapshot.mobileDirectRescueLastError = mobileDirectRescueByDc.mapNotNull { (dc, state) -> state.lastError?.let { dc to it } }.toMap().toSortedMap()
        snapshot.mobileDirectRescueLastSuccessTime = mobileDirectRescueByDc.mapValues { (_, state) -> state.lastSuccessTimeMs }.filterValues { it > 0L }.toSortedMap()
        snapshot.cfHealthEnabled = cfHealthSnapshot.enabled
        snapshot.cfDomainsTotal = cfHealthSnapshot.domainsTotal
        snapshot.cfDomainsInCooldown = cfHealthSnapshot.domainsInCooldown
        snapshot.cfLastSelectedDomain = cfHealthSnapshot.lastSelectedDomain
        snapshot.cfLastSelectedReason = cfHealthSnapshot.lastSelectedReason
        snapshot.cfLastConnectLatencyMs = cfHealthSnapshot.lastConnectLatencyMs
        snapshot.cfBestDomainByDc = cfHealthSnapshot.bestDomainByDc
        snapshot.cf429Count = cfHealthSnapshot.total429
        snapshot.cf503Count = cfHealthSnapshot.total503
        snapshot.cfUnknownHostCount = cfHealthSnapshot.totalUnknownHost
        snapshot.cfTimeoutCount = cfHealthSnapshot.totalTimeouts
        snapshot.cfCooldownSkips = cfHealthSnapshot.cooldownSkips
        snapshot.cfAllDomainsInCooldownFallbacks = cfHealthSnapshot.allDomainsInCooldownFallbacks
        snapshot.cfInflightSkips = cfHealthSnapshot.inflightSkips
        snapshot.cfInflightWaits = cfHealthSnapshot.inflightWaits
        snapshot.cfMaxInflightPerDomainReached = cfHealthSnapshot.maxInflightPerDomainReached
        snapshot.cfActiveConnectsByDc = cfHealthSnapshot.activeConnectsByDc
        snapshot.cfConnectQueueWaits = cfHealthSnapshot.connectQueueWaits
        snapshot.cfConnectQueueTimeouts = cfHealthSnapshot.connectQueueTimeouts
        snapshot.cfQueueControlledFailures = cfHealthSnapshot.queueControlledFailures
        snapshot.cfQueueWaitMs = cfHealthSnapshot.queueWaitMs
        snapshot.cfNoRouteAvoidedByInflightWait = cfNoRouteAvoidedByInflightWait.get()
        snapshot.cfInflightWaitBeforeNoRoute = cfInflightWaitBeforeNoRoute.get()
        snapshot.cfInflightWaitBeforeNoRouteMs = cfInflightWaitBeforeNoRouteMs.get()
        snapshot.cfInflightRetrySuccesses = cfInflightRetrySuccesses.get()
        snapshot.cfInflightRetryFailures = cfInflightRetryFailures.get()
        snapshot.cfMaxConcurrentConnectsByDc = cfHealthSnapshot.maxConcurrentConnectsByDc
        snapshot.cf429BackoffCount = cfHealthSnapshot.backoffCount
        snapshot.cfAllCooldownWaits = cfHealthSnapshot.allCooldownWaits
        snapshot.cfAllCooldownWaitMs = cfHealthSnapshot.allCooldownWaitMs
        snapshot.cfAllCooldownCircuitOpenCount = cfHealthSnapshot.allCooldownCircuitOpenCount
        snapshot.cfAllCooldownAttemptsAllowed = cfHealthSnapshot.allCooldownAttemptsAllowed
        snapshot.cfAllCooldownAttemptsSuppressed = cfHealthSnapshot.allCooldownAttemptsSuppressed
        snapshot.cfAllCooldownControlledFailures = cfHealthSnapshot.allCooldownControlledFailures
        snapshot.cfAllCooldownCircuitOpenByDc = cfHealthSnapshot.allCooldownCircuitOpenByDc
        snapshot.cfAllCooldownSingleAttempts = cfHealthSnapshot.allCooldownSingleAttempts
        snapshot.cfAllCooldownSingleAttemptFailures = cfHealthSnapshot.allCooldownSingleAttemptFailures
        snapshot.cfAllCooldownStoppedCycles = cfHealthSnapshot.allCooldownStoppedCycles
        snapshot.cfPressureLevelByDc = cfHealthSnapshot.pressure.levelByDc
        snapshot.cfPressureScoreByDc = cfHealthSnapshot.pressure.scoreByDc
        snapshot.cfPressureRecentSuccessByDc = cfHealthSnapshot.pressure.recentSuccessByDc
        snapshot.cfPressureRecent429ByDc = cfHealthSnapshot.pressure.recent429ByDc
        snapshot.cfPressureRecentTimeoutByDc = cfHealthSnapshot.pressure.recentTimeoutByDc
        snapshot.cfPressureRecentUnknownHostByDc = cfHealthSnapshot.pressure.recentUnknownHostByDc
        snapshot.cfPressureRecentQueueFailureByDc = cfHealthSnapshot.pressure.recentQueueFailureByDc
        snapshot.cfPressureRecentAllCooldownSuppressedByDc = cfHealthSnapshot.pressure.recentAllCooldownSuppressedByDc
        snapshot.cfPressureRecentMaxInflightByDc = cfHealthSnapshot.pressure.recentMaxInflightByDc
        snapshot.cfPressureRecentRouteFailureAfterCfByDc = cfHealthSnapshot.pressure.recentRouteFailureAfterCfByDc
        snapshot.cfPressureAllDomainsCooldownByDc = cfHealthSnapshot.pressure.allDomainsCooldownByDc
        snapshot.cfPressureReasonByDc = cfHealthSnapshot.pressure.reasonByDc
        snapshot.cfPressureProbeAllowed = cfHealthSnapshot.pressure.probeAllowed
        snapshot.cfPressureProbeSuppressed = cfHealthSnapshot.pressure.probeSuppressed
        snapshot.cfPressureControlledFailures = cfHealthSnapshot.pressure.controlledFailures
        snapshot.cfPressureLimitedAttempts = cfHealthSnapshot.pressure.limitedAttempts
        snapshot.cfPressureLevelChanges = cfHealthSnapshot.pressure.levelChanges
        snapshot.cfPressureNextProbeAtByDc = cfHealthSnapshot.pressure.nextProbeAtByDc
        snapshot.cfTransientNetworkFailures = cfHealthSnapshot.transientNetworkFailures
        snapshot.cfFailuresIgnoredBecauseNetworkChanged = cfHealthSnapshot.failuresIgnoredBecauseNetworkChanged
        snapshot.cfCooldownsSkippedBecauseNetworkSettling = cfHealthSnapshot.cooldownsSkippedBecauseNetworkSettling
        snapshot.cfTransientCooldownsClearedOnNetworkAvailable = cfHealthSnapshot.transientCooldownsClearedOnNetworkAvailable
        snapshot.networkSettlingWaits = networkSettlingWaits.get()
        snapshot.networkSettlingWaitMs = networkSettlingWaitMs.get()
        snapshot.networkSettlingResumedAfterAvailable = networkSettlingResumedAfterAvailable.get()
        snapshot.networkSettlingControlledFailures = networkSettlingControlledFailures.get()
        snapshot.networkSettlingStaleAttemptsIgnored = networkSettlingStaleAttemptsIgnored.get()
        snapshot.networkSettlingUntilMs = networkSettlingUntilMs.get()
        snapshot.lastNetworkLostAtMs = lastNetworkLostAtMs.get()
        snapshot.lastNetworkAvailableAtMs = lastNetworkAvailableAtMs.get()
        snapshot.cfHealthDomains = cfHealthSnapshot.domains
        snapshot.recentInvalidHandshakeCount = recentInvalidHandshakeTimes.size.toLong()
        snapshot.recentAcceptedHandshakeCount = recentAcceptedHandshakeTimes.size.toLong()
        snapshot.lastInvalidHandshakeTimeMs = lastInvalidHandshakeTimeMs.get()
        snapshot.lastAcceptedHandshakeTimeMs = lastAcceptedHandshakeTimeMs.get()
        snapshot.lastSuccessfulRouteTimeMs = lastSuccessfulRouteTimeMs.get()
        snapshot.networkGeneration = routeGeneration.get()
        snapshot.clientExperience = buildClientExperienceDiagnostics(snapshotTimeMs)
        snapshot.unsupportedDc = unsupportedDc.get()

        return snapshot
    }

    fun applyNetworkRoute(networkStatus: String): RouteChangeResult =
        applyNetworkRoute(networkStatus, immediate = false)

    fun applyNetworkRouteImmediately(networkStatus: String): RouteChangeResult =
        applyNetworkRoute(networkStatus, immediate = true)

    private fun applyNetworkRoute(networkStatus: String, immediate: Boolean): RouteChangeResult {
        val normalized = networkStatus.ifBlank { "unknown" }
        val previousNetworkStatus = currentNetworkStatus
        if (shouldIgnoreHealthyWifiCapabilityEvent(previousNetworkStatus, normalized)) {
            wifiCapabilityEventsIgnored.incrementAndGet()
            routeChurnAvoided.incrementAndGet()
            directProbeSkippedBecauseAlreadyHealthy.incrementAndGet()
            currentNetworkStatus = normalized
            if (!normalized.equals("none", ignoreCase = true)) {
                lastNetworkAvailableAtMs.set(System.currentTimeMillis())
                networkSettlingUntilMs.set(0L)
                synchronized(networkStateMonitor) { networkStateMonitor.notifyAll() }
            }
            logger.log("route evaluation noop: ${NetworkRouteMode.DIRECT_FIRST.configValue} unchanged because Wi-Fi capabilities changed; direct route already healthy")
            return routeState.applyEffectiveRouteMode(
                NetworkRouteMode.DIRECT_FIRST,
                "Wi-Fi capabilities changed; direct route already healthy",
                normalized,
                source = if (immediate) "immediate" else "debounce",
            )
        }
        currentNetworkStatus = normalized
        val newGeneration = routeGeneration.incrementAndGet()
        if (isMobileGenerationRecoveryTransition(previousNetworkStatus, normalized)) {
            prepareMobileNetworkGenerationRecovery(previousNetworkStatus, normalized, newGeneration)
        }
        if (!isWifi(previousNetworkStatus) || !isWifi(normalized)) {
            directRouteHealth.markSettling(ROUTE_SETTLING_WINDOW_MS)
        }
        if (normalized.equals("none", ignoreCase = true)) {
            networkNoneEvents.incrementAndGet()
            val now = System.currentTimeMillis()
            lastNetworkLostAtMs.set(now)
            networkSettlingUntilMs.set(now + NETWORK_SETTLING_WINDOW_MS)
            logger.log("network settling started after network lost until ${networkSettlingUntilMs.get()}")
            directRouteHealth.resetForSafeRoute()
            webSocketPool.disableAndClear()
            if (immediate) logger.log("network lost: applying safe route immediately")
        } else {
            lastNetworkAvailableAtMs.set(System.currentTimeMillis())
            networkSettlingUntilMs.set(0L)
            synchronized(networkStateMonitor) { networkStateMonitor.notifyAll() }
            if (previousNetworkStatus.equals("none", ignoreCase = true)) {
                val cleared = cfDomainHealth.clearTransientNetworkCooldowns()
                if (cleared > 0) logger.log("CF transient DNS cooldowns cleared after network available: $cleared")
            }
            if (isMobile(previousNetworkStatus) && isWifi(normalized)) {
                wifiDirectRecoveryUntilMs = System.currentTimeMillis() + WIFI_DIRECT_RECOVERY_WINDOW_MS
                logger.log("network changed from mobile to Wi-Fi; mobile rescue cooldown and CF pressure are ignored for Wi-Fi direct recovery")
            }
        }
        if (immediate) routeChangesImmediate.incrementAndGet()
        val result = applyEffectiveRouteMode(
            routeState.desiredEffectiveRouteMode(normalized),
            "network=$normalized",
            normalized,
            source = if (immediate) "immediate" else "debounce",
        )
        maybeStartAutoWifiDirectProbe(normalized, previousNetworkStatus)
        return result
    }

    private fun isMobileGenerationRecoveryTransition(previousNetworkStatus: String, networkStatus: String): Boolean {
        val newIsMobileLike = isMobileLike(networkStatus) && !networkStatus.equals("none", ignoreCase = true)
        return newIsMobileLike && (isMobile(previousNetworkStatus) || previousNetworkStatus.equals("none", ignoreCase = true))
    }

    private fun prepareMobileNetworkGenerationRecovery(previousNetworkStatus: String, networkStatus: String, newGeneration: Long) {
        mobileNetworkGenerationChanges.incrementAndGet()
        mobileToMobileRecoveryAttempts.incrementAndGet()
        if (previousNetworkStatus.equals("none", ignoreCase = true) && isMobile(networkStatus)) {
            noneToMobileRecoveryAttempts.incrementAndGet()
        }
        lastMobileRecoveryReason.set("$previousNetworkStatus->$networkStatus")
        lastMobileRecoveryNetworkGeneration.set(newGeneration)
        lastMobileRecoveryResult.set("prepared")
        val now = System.currentTimeMillis()
        var directReset = 0L
        for ((_, state) in mobileDirectRescueByDc) {
            synchronized(state) {
                if (state.cooldownUntilMs > now || state.generation < newGeneration) {
                    state.cooldownUntilMs = 0L
                    state.inFlight = false
                    state.generation = newGeneration
                    directReset += 1
                }
            }
        }
        val pressureReset = cfDomainHealth.resetPressureForMobileNetworkGenerationChange()
        if (previousNetworkStatus.equals("none", ignoreCase = true) && isMobile(networkStatus)) {
            cfDomainHealth.startMobileRecoveryProbeWindow(newGeneration)
        }
        mobileRecoveryDirectCooldownResetCount.addAndGet(directReset)
        mobileRecoveryCfPressureResetCount.addAndGet(pressureReset.toLong())
        logger.log("mobile network generation changed $previousNetworkStatus->$networkStatus generation=$newGeneration; reset direct cooldowns=$directReset cfPressure=$pressureReset")
    }

    private fun shouldIgnoreHealthyWifiCapabilityEvent(previousNetworkStatus: String, networkStatus: String): Boolean =
        routeState.configuredRouteMode == NetworkRouteMode.AUTO &&
            isWifi(previousNetworkStatus) &&
            isWifi(networkStatus) &&
            effectiveRouteMode() == NetworkRouteMode.DIRECT_FIRST &&
            directRouteHealth.currentState() == DirectHealthState.HEALTHY

    fun applyEffectiveRouteMode(
        desiredEffectiveRouteMode: NetworkRouteMode,
        reason: String,
        networkStatus: String,
        source: String = "manual",
    ): RouteChangeResult {
        currentNetworkStatus = networkStatus.ifBlank { "unknown" }
        val result = routeState.applyEffectiveRouteMode(desiredEffectiveRouteMode, reason, networkStatus, source)
        if (result.changed) {
            logger.log(
                "effective route changed: ${result.previous.configValue} -> ${result.current.configValue} because $reason",
            )
            routeGeneration.incrementAndGet()
            handlePoolForRouteChange(result.previous, result.current)
        } else {
            logger.log("route evaluation noop: ${result.current.configValue} unchanged because $reason")
        }
        return result
    }

    fun routeSnapshot(): RouteSnapshot = routeState.snapshot()

    private fun acceptLoop() {
        while (running.get()) {
            val client = try {
                serverTransport.accept()
            } catch (error: Throwable) {
                if (running.get()) logger.log("Accept failed: ${error.message ?: error::class.java.simpleName}")
                null
            } ?: continue

            connectionsTotal.incrementAndGet()
            activeClients.add(client)
            connectionsActive.incrementAndGet()
            Thread({ handleClientAndClose(client) }, "ProxyServer-client-${client.remoteLabel}").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun handleClientAndClose(client: TcpClientTransport) {
        var clientExperienceStarted = false
        try {
            clientExperienceStarted = handleClient(client)
        } catch (error: Throwable) {
            sessionUnexpectedErrors.incrementAndGet()
            logger.log("${client.remoteLabel} client handler failed: ${failureDetail(error)}")
        } finally {
            if (clientExperienceStarted && clientExperienceActiveSessions.decrementAndGet() == 0) {
                clientExperienceLastIdleAtMs.set(System.currentTimeMillis())
            }
            activeClients.remove(client)
            connectionsActive.decrementAndGet()
            closeClient(client)
        }
    }

    private fun handleClient(client: TcpClientTransport): Boolean {
        val handshake = try {
            client.readExact(MtprotoHandshake.HANDSHAKE_LEN)
        } catch (error: Throwable) {
            markBad("Failed to read MTProto handshake from ${client.remoteLabel}: ${error.message ?: error::class.java.simpleName}")
            return false
        }
        if (handshake == null) {
            markBad("Client ${client.remoteLabel} closed before MTProto handshake")
            return false
        }

        val parsed = try {
            MtprotoHandshake.parse(handshake, config.secretHex)
        } catch (error: IllegalArgumentException) {
            markBad("Invalid proxy configuration or handshake for ${client.remoteLabel}: ${error.message}")
            return false
        }
        if (parsed == null) {
            markBad("Invalid MTProto handshake from ${client.remoteLabel}")
            return false
        }

        val wasClientExperienceIdle = clientExperienceActiveSessions.getAndIncrement() == 0
        val idleDurationMs = if (wasClientExperienceIdle) {
            (System.currentTimeMillis() - clientExperienceLastIdleAtMs.get()).coerceAtLeast(0L)
        } else {
            0L
        }
        recordAcceptedHandshake(wasClientExperienceIdle = wasClientExperienceIdle)
        val targetHost = config.dcRedirects[parsed.dcId]
        maybeScheduleWakeBurstPrewarm(parsed.dcId, wasClientExperienceIdle, idleDurationMs)

        val protoInt = protoIntForProtoTag(parsed.protoTag)
        logger.log(
            "${client.remoteLabel} handshake accepted: DC${parsed.dcId} " +
                "media=${parsed.isMedia} proto=$protoInt/${protoTagLabel(parsed.protoTag)}",
        )

        val relayDcIdx = if (parsed.isMedia) -parsed.dcId else parsed.dcId
        val relayInit = RelayInit.generate(parsed.protoTag, relayDcIdx, randomBytes)
        val cryptoContext = CryptoContext.build(
            clientDecPrekeyIv = parsed.clientDecPrekeyIv,
            secret = config.secretHex.hexToBytes(),
            relayInit = relayInit,
        )
        val splitter = MsgSplitter(relayInit, protoInt)

        if (!waitForNetworkSettlingBeforeRoute(parsed.dcId)) return true

        val routeAttemptStartGeneration = routeGeneration.get()
        val routeAttemptStartNetwork = currentNetworkStatus
        lastNetworkTypeAtRouteAttempt = routeAttemptStartNetwork
        routeAttemptNetworkGeneration = routeAttemptStartGeneration

        if (targetHost == null) {
            if (config.cfproxyEnabled) {
                logger.log("DC${parsed.dcId} has no direct redirect configured; trying CF fallback")
            }
            if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)) {
                return true
            }
            recordUnsupportedDc(parsed.dcId)
            recordNoRoute(parsed.dcId)
            markBad("Unsupported DC ${parsed.dcId} from ${client.remoteLabel}; no direct redirect or CF proxy route available")
            return true
        }

        when (effectiveRouteMode()) {
            NetworkRouteMode.CF_ONLY -> {
                if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)) return true
                if (cfDomainHealth.mobileRescueRecommended(parsed.dcId)) {
                    logger.log("DC${parsed.dcId} CF exhausted but CF_ONLY forbids direct rescue")
                }
                recordNoRoute(parsed.dcId)
                logger.log("DC${parsed.dcId} no route available after CF-only attempts")
            }
            NetworkRouteMode.CF_FIRST -> {
                val cfPooled = tryCfPoolRoute(client, parsed, relayInit, cryptoContext, splitter)
                if (cfPooled) return true
                if (shouldAttemptActiveWifiDirectRecoveryBeforeCf()) {
                    if (tryWifiDirectRecoveryRoute(client, parsed, targetHost, relayInit, cryptoContext, splitter)) return true
                }
                if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)) return true
                val currentGenerationBeforeFallback = routeGeneration.get()
                if (currentGenerationBeforeFallback != routeAttemptStartGeneration) {
                    routeAttemptNetworkChangedBeforeSelection.incrementAndGet()
                    logger.log(
                        "DC${parsed.dcId} route attempt network changed before fallback selection: " +
                            "$routeAttemptStartNetwork/$routeAttemptStartGeneration -> $currentNetworkStatus/$currentGenerationBeforeFallback; re-evaluating route policy",
                    )
                }
                if (shouldAttemptWifiDirectRecovery(routeAttemptStartGeneration)) {
                    if (tryWifiDirectRecoveryRoute(client, parsed, targetHost, relayInit, cryptoContext, splitter)) return true
                }
                if (routeState.snapshot().effectiveRouteMode == NetworkRouteMode.DIRECT_FIRST && isWifi(currentNetworkStatus)) {
                    logger.log("DC${parsed.dcId} route re-evaluated to direct_first after network change; trying cold direct")
                    if (tryDirectRoute(
                            client,
                            parsed,
                            targetHost,
                            relayInit,
                            cryptoContext,
                            splitter,
                            usePool = false,
                            timeoutMs = config.directFallbackTimeoutMs,
                            allowWifiDirectRecovery = true,
                        )
                    ) return true
                }
                var cfFirstDirectFallbackAttempted = false
                if (isDirectAttemptAllowedForCurrentRoute()) {
                    cfFirstDirectFallbackAttempted = true
                    logger.log("DC${parsed.dcId} trying short direct fallback after CF-first failure")
                    if (tryDirectRoute(client, parsed, targetHost, relayInit, cryptoContext, splitter, usePool = false, timeoutMs = config.directFallbackTimeoutMs)) return true
                } else {
                    val rescue = tryMobileDirectRescueIfAllowed(client, parsed, targetHost, relayInit, cryptoContext, splitter, routeAttemptStartGeneration)
                    if (rescue.routed) return true
                    if (!rescue.attempted) {
                        directAttemptsSkippedBecauseRoute.incrementAndGet()
                        logger.log("DC${parsed.dcId} direct fallback skipped because effective route mode ${effectiveRouteMode().configValue}")
                    }
                }
                if (tryEmergencyDirectFallback(client, parsed, targetHost, relayInit, cryptoContext, splitter, routeAttemptStartGeneration, cfFirstDirectFallbackAttempted)) return true
                if (tryCfInflightWaitBeforeNoRoute(client, parsed, relayInit, cryptoContext, splitter)) return true
                recordNoRoute(parsed.dcId)
                logger.log("DC${parsed.dcId} no route available after CF-first attempts")
            }
            NetworkRouteMode.DIRECT_FIRST, NetworkRouteMode.AUTO -> {
                if (tryDirectRoute(client, parsed, targetHost, relayInit, cryptoContext, splitter, usePool = true, timeoutMs = RawWebSocket.DEFAULT_CONNECT_TIMEOUT_MS)) return true
                if (config.cfproxyEnabled && tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)) return true
                recordNoRoute(parsed.dcId)
                logger.log("DC${parsed.dcId} no route available after direct WebSocket attempts")
                downgradeDirectRouteBecauseHealthDegraded("no route available after direct attempts")
            }
        }
        return true
    }


    private fun tryCfInflightWaitBeforeNoRoute(
        client: TcpClientTransport,
        parsed: MtprotoHandshake.Result,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
    ): Boolean {
        if (!config.cfproxyEnabled) return false
        if (effectiveRouteMode() != NetworkRouteMode.CF_FIRST) return false
        val mobileOrDirectSuppressed = isMobile(currentNetworkStatus) || !isDirectAttemptAllowedForCurrentRoute()
        if (!mobileOrDirectSuppressed) return false
        if (!cfDomainHealth.hasInFlightConnectsForDc(parsed.dcId)) return false

        val waitMs = CF_INFLIGHT_WAIT_BEFORE_NO_ROUTE_MS
        cfInflightWaitBeforeNoRoute.incrementAndGet()
        logger.log(
            "DC${parsed.dcId} waiting up to ${waitMs}ms for in-flight CF connect before CF-first no-route",
        )
        val startedAtNs = System.nanoTime()
        cfDomainHealth.waitForInFlightConnectReleaseForDc(parsed.dcId, waitMs)
        val elapsedMs = ((System.nanoTime() - startedAtNs) / 1_000_000).coerceAtLeast(0L)
        cfInflightWaitBeforeNoRouteMs.addAndGet(elapsedMs)
        logger.log("DC${parsed.dcId} in-flight CF wait before no-route ended after ${elapsedMs}ms; retrying CF once")

        val retried = tryCfProxyFallback(client, parsed, relayInit, cryptoContext, splitter)
        if (retried) {
            cfInflightRetrySuccesses.incrementAndGet()
            cfNoRouteAvoidedByInflightWait.incrementAndGet()
            logger.log("DC${parsed.dcId} CF-first no-route avoided by in-flight wait retry")
        } else {
            cfInflightRetryFailures.incrementAndGet()
            logger.log("DC${parsed.dcId} CF retry after in-flight wait did not find a route")
        }
        return retried
    }

    private fun waitForNetworkSettlingBeforeRoute(dcId: Int): Boolean {
        val initialGeneration = routeGeneration.get()
        val now = System.currentTimeMillis()
        val settlingUntil = networkSettlingUntilMs.get()
        if (!currentNetworkStatus.equals("none", ignoreCase = true)) return true
        if (now < settlingUntil) {
            val waitMs = (settlingUntil - now).coerceAtMost(NETWORK_SETTLING_CLIENT_WAIT_MAX_MS)
            networkSettlingWaits.incrementAndGet()
            networkSettlingWaitMs.addAndGet(waitMs)
            logger.log("DC$dcId client route waits ${waitMs}ms for network settling until $settlingUntil")
            synchronized(networkStateMonitor) {
                if (currentNetworkStatus.equals("none", ignoreCase = true) && System.currentTimeMillis() < networkSettlingUntilMs.get()) {
                    try {
                        networkStateMonitor.wait(waitMs.coerceAtLeast(1L))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
            val newGeneration = routeGeneration.get()
            routeWaitRecheckedNetworkGeneration.incrementAndGet()
            val current = currentNetworkStatus
            if (newGeneration != initialGeneration) {
                networkSettlingStaleAttemptsIgnored.incrementAndGet()
                if (isMobile(current)) noneToMobileRouteWaitStaleCount.incrementAndGet()
                logger.log("DC$dcId route wait rechecked network generation $initialGeneration -> $newGeneration after network settling wait")
            }
            if (!current.equals("none", ignoreCase = true)) {
                networkSettlingResumedAfterAvailable.incrementAndGet()
                if (isMobile(current)) routeWaitResumedAfterMobileAvailable.incrementAndGet()
                logger.log("DC$dcId network appeared during settling as $current; retrying route selection")
                return true
            }
            mobileRecoveryNoRouteDuringSettlingCount.incrementAndGet()
            networkSettlingControlledFailures.incrementAndGet()
            logger.log("DC$dcId network still none after settling wait; controlled no-route failure")
            return false
        }
        mobileRecoveryNoRouteDuringSettlingCount.incrementAndGet()
        networkSettlingControlledFailures.incrementAndGet()
        logger.log("DC$dcId network=none outside settling window; controlled no-route failure")
        return false
    }

    private fun setDirectTargetIpCooldown(targetHost: String, dcId: Int, reason: String) {
        val now = System.currentTimeMillis()
        val until = now + IP_FAIL_COOLDOWN_MS
        directTargetIpCooldownUntilByTarget.getOrPut(targetHost) { AtomicLong(0) }.set(until)
        directTargetIpCooldownReasonByTarget[targetHost] = reason
        lastDirectTargetIpCooldownTarget.set(targetHost)
        lastDirectTargetIpCooldownReason.set(reason)
        lastDirectTargetIpCooldownSetTimeMs.set(now)
        directTargetIpCooldownSets.incrementAndGet()
        logger.log("direct target $targetHost cooldown set for DC$dcId because $reason")
    }

    private fun isDirectTargetIpCooldownActive(targetHost: String, now: Long = System.currentTimeMillis()): Boolean {
        val until = directTargetIpCooldownUntilByTarget[targetHost]?.get() ?: return false
        if (until > now) return true
        directTargetIpCooldownUntilByTarget.remove(targetHost)
        directTargetIpCooldownReasonByTarget.remove(targetHost)
        directTargetIpCooldownLoggedUntilByTarget.remove(targetHost)
        return false
    }

    private fun logDirectTargetIpCooldownHit(dcId: Int, targetHost: String) {
        val until = directTargetIpCooldownUntilByTarget[targetHost]?.get() ?: 0L
        val previous = directTargetIpCooldownLoggedUntilByTarget.getOrPut(targetHost) { AtomicLong(0) }.getAndSet(until)
        if (previous != until) logger.log("DC$dcId direct target $targetHost in cooldown; trying CF fallback")
    }

    private fun clearDirectTargetIpCooldownAfterSuccess(targetHost: String) {
        val removed = directTargetIpCooldownUntilByTarget.remove(targetHost) != null
        directTargetIpCooldownReasonByTarget.remove(targetHost)
        directTargetIpCooldownLoggedUntilByTarget.remove(targetHost)
        if (removed) {
            directTargetIpCooldownClears.incrementAndGet()
            logger.log("direct target $targetHost cooldown cleared after successful direct route")
        }
    }

    private fun directTargetIpCooldownSnapshotForStats(now: Long): Map<String, Long> {
        return directTargetIpCooldownUntilByTarget.mapNotNull { (target, untilRef) ->
            val until = untilRef.get()
            if (until > now) target to until else null
        }.toMap().toSortedMap()
    }

    internal fun setDirectTargetIpCooldownForTest(target: String, untilMs: Long, reason: String) {
        directTargetIpCooldownUntilByTarget.getOrPut(target) { AtomicLong(0) }.set(untilMs)
        directTargetIpCooldownReasonByTarget[target] = reason
        lastDirectTargetIpCooldownTarget.set(target)
        lastDirectTargetIpCooldownReason.set(reason)
        lastDirectTargetIpCooldownSetTimeMs.set(System.currentTimeMillis())
    }

    internal fun directTargetIpCooldownSnapshotForTest(): Map<String, Long> = directTargetIpCooldownSnapshotForStats(System.currentTimeMillis())

    internal fun clearDirectTargetIpCooldownForTest(target: String) {
        clearDirectTargetIpCooldownAfterSuccess(target)
    }

    private fun isCooldownWorthyDirectFailure(error: Throwable): Boolean {
        if (error is SocketTimeoutException || error is NoRouteToHostException || error is ConnectException) return true
        if (error is SocketException) {
            val message = error.message.orEmpty()
            return listOf("ENETUNREACH", "EHOSTUNREACH", "Connection timed out", "Network is unreachable", "No route to host", "Connection refused").any {
                message.contains(it, ignoreCase = true)
            }
        }
        val message = error.message.orEmpty()
        return message.contains("connect timed out", ignoreCase = true) || message.contains("read timed out", ignoreCase = true)
    }

    private fun tryDirectRoute(
        client: TcpClientTransport,
        parsed: MtprotoHandshake.Result,
        targetHost: String,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
        usePool: Boolean,
        timeoutMs: Int,
        allowWifiDirectRecovery: Boolean = false,
    ): Boolean {
        val attemptGeneration = routeGeneration.get()
        val directRoute = getPooledOrConnectWebSocket(parsed, targetHost, usePool, timeoutMs, attemptGeneration, allowWifiDirectRecovery)
        if (directRoute != null) {
            val directResult = runWebSocketRoute(client, parsed, directRoute, relayInit, cryptoContext, splitter)
            if (!directResult.failed) return true
            if (directResult.retryableStalePooled) {
                if (!directRouteContextAllowsAttempt(attemptGeneration, allowWifiDirectRecovery)) {
                    directAttemptsSkippedBecauseRoute.incrementAndGet()
                    logger.log("DC${parsed.dcId} cold direct retry skipped after stale pool because route/network changed")
                    return false
                }
                logger.log("DC${parsed.dcId} retrying with cold direct route after stale pool")
                val coldRoute = guardedConnectWebSocket(parsed, targetHost, timeoutMs, attemptGeneration, allowWifiDirectRecovery = allowWifiDirectRecovery)?.let { WebSocketRoute(it, "direct-cold") }
                if (coldRoute != null) {
                    val coldResult = runWebSocketRoute(client, parsed, coldRoute, relayInit, cryptoContext, splitter)
                    if (!coldResult.failed) return true
                    if (!coldResult.failedBeforeBridge) return true
                }
                if (config.cfproxyEnabled) {
                    logger.log("DC${parsed.dcId} trying CF fallback after stale pool/cold direct failure")
                }
            } else if (!directResult.failedBeforeBridge || directResult.stalePooled) {
                return true
            }
        }
        return false
    }

    private fun tryWifiDirectRecoveryRoute(
        client: TcpClientTransport,
        parsed: MtprotoHandshake.Result,
        targetHost: String,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
    ): Boolean {
        wifiDirectRecoveryAttempts.incrementAndGet()
        wifiCfFirstRecoveryAttempts.incrementAndGet()
        cfFirstRecoveryAttempts.incrementAndGet()
        lastWifiCfFirstRecoveryTimeMs.set(System.currentTimeMillis())
        logger.log("DC${parsed.dcId} Wi-Fi direct recovery allowed (cf_first recovery) while effective route mode ${effectiveRouteMode().configValue}")
        val routed = tryDirectRoute(
            client,
            parsed,
            targetHost,
            relayInit,
            cryptoContext,
            splitter,
            usePool = false,
            timeoutMs = config.directFallbackTimeoutMs,
            allowWifiDirectRecovery = true,
        )
        if (routed) {
            wifiDirectRecoverySuccesses.incrementAndGet()
            wifiCfFirstRecoverySuccesses.incrementAndGet()
            cfFirstRecoverySuccesses.incrementAndGet()
            lastWifiCfFirstRecoveryError.set(null)
            promoteDirectRouteAfterColdSuccess("Wi-Fi cf_first recovery probe success")
            return true
        }
        val error = directRouteHealth.snapshot().lastError ?: "direct recovery route failed"
        wifiDirectRecoveryFailures.incrementAndGet()
        wifiCfFirstRecoveryFailures.incrementAndGet()
        cfFirstRecoveryFailures.incrementAndGet()
        lastWifiCfFirstRecoveryError.set(error)
        directRouteHealth.startCooldown(EMERGENCY_DIRECT_FALLBACK_FAILURE_COOLDOWN_MS, error)
        return false
    }

    private fun tryEmergencyDirectFallback(
        client: TcpClientTransport,
        parsed: MtprotoHandshake.Result,
        targetHost: String,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
        routeAttemptStartGeneration: Long,
        cfFirstDirectFallbackAttempted: Boolean,
    ): Boolean {
        val now = System.currentTimeMillis()
        val reason = when {
            routeState.snapshot().effectiveRouteMode != NetworkRouteMode.CF_FIRST -> "route is not cf_first"
            !isWifi(currentNetworkStatus) -> "network=$currentNetworkStatus"
            routeGeneration.get() != routeAttemptStartGeneration -> "network generation changed"
            cfFirstDirectFallbackAttempted -> "short direct fallback already attempted"
            directRouteHealth.snapshot().cooldownUntilMs > now -> "direct cooldown"
            emergencyDirectFallbackCooldownUntilMs.get() > now -> "emergency fallback cooldown"
            directRouteHealth.snapshot().lastError?.contains("timeout", ignoreCase = true) == true -> "recent direct timeout"
            else -> null
        }
        if (reason != null) {
            emergencyDirectFallbackSuppressed.incrementAndGet()
            lastEmergencyDirectFallbackReason.set(reason)
            logger.log("DC${parsed.dcId} emergency direct fallback suppressed: $reason")
            return false
        }
        emergencyDirectFallbackAttempts.incrementAndGet()
        lastEmergencyDirectFallbackReason.set("CF controlled failure/no route on Wi-Fi")
        logger.log("DC${parsed.dcId} emergency direct-cold fallback allowed after CF-first controlled failure/no route")
        val routed = tryDirectRoute(
            client,
            parsed,
            targetHost,
            relayInit,
            cryptoContext,
            splitter,
            usePool = false,
            timeoutMs = config.directFallbackTimeoutMs,
            allowWifiDirectRecovery = true,
        )
        if (routed) {
            emergencyDirectFallbackSuccesses.incrementAndGet()
            promoteDirectRouteAfterColdSuccess("emergency direct fallback success")
            return true
        }
        emergencyDirectFallbackFailures.incrementAndGet()
        emergencyDirectFallbackCooldownUntilMs.set(System.currentTimeMillis() + EMERGENCY_DIRECT_FALLBACK_FAILURE_COOLDOWN_MS)
        return false
    }

    private fun tryMobileDirectRescueIfAllowed(
        client: TcpClientTransport,
        parsed: MtprotoHandshake.Result,
        targetHost: String,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
        routeAttemptStartGeneration: Long,
    ): MobileDirectRescueRouteResult {
        val decision = mobileDirectRescueDecision(parsed.dcId, routeAttemptStartGeneration)
        if (!decision.allowed) {
            if (decision.logMessage != null) logger.log(decision.logMessage)
            return MobileDirectRescueRouteResult(attempted = false, routed = false)
        }
        logger.log("DC${parsed.dcId} mobile direct rescue allowed because CF exhausted (${decision.reason})")
        val route = connectMobileDirectRescue(parsed, targetHost, routeAttemptStartGeneration)?.let { WebSocketRoute(it, "direct-mobile-rescue") }
        if (route == null) return MobileDirectRescueRouteResult(attempted = true, routed = false)
        val result = runWebSocketRoute(client, parsed, route, relayInit, cryptoContext, splitter)
        if (!result.failed) return MobileDirectRescueRouteResult(attempted = true, routed = true)
        if (!result.failedBeforeBridge) return MobileDirectRescueRouteResult(attempted = true, routed = true)
        return MobileDirectRescueRouteResult(attempted = true, routed = false)
    }

    private fun mobileDirectRescueDecision(dcId: Int, routeAttemptStartGeneration: Long): MobileDirectRescueDecision {
        val currentGeneration = routeGeneration.get()
        val currentNetwork = currentNetworkStatus
        if (currentGeneration != routeAttemptStartGeneration && !isMobile(currentNetwork)) {
            mobileRescueSkippedBecauseNetworkChanged.incrementAndGet()
            return MobileDirectRescueDecision(
                false,
                "network_changed",
                "DC$dcId mobile direct rescue skipped because network changed before decision: generation $routeAttemptStartGeneration -> $currentGeneration network=$currentNetwork",
            )
        }
        val routeSnapshot = routeState.snapshot()
        val cfExhausted = cfDomainHealth.mobileRescueRecommended(dcId)
        if (!cfExhausted) return MobileDirectRescueDecision(false, "CF not exhausted")
        if (routeSnapshot.configuredRouteMode == NetworkRouteMode.CF_ONLY) {
            return MobileDirectRescueDecision(false, "CF_ONLY", "DC$dcId CF exhausted but CF_ONLY forbids direct rescue")
        }
        if (!isMobile(currentNetwork)) {
            return MobileDirectRescueDecision(false, "network", "DC$dcId CF exhausted but mobile direct rescue skipped because current network=$currentNetwork")
        }
        if (routeSnapshot.configuredRouteMode != NetworkRouteMode.AUTO || routeSnapshot.effectiveRouteMode != NetworkRouteMode.CF_FIRST) {
            return MobileDirectRescueDecision(false, "route", "DC$dcId CF exhausted on mobile and direct rescue not allowed by route mode")
        }
        val now = System.currentTimeMillis()
        val state = mobileDirectRescueByDc.getOrPut(dcId) { MobileDirectRescueState() }
        synchronized(state) {
            if (state.inFlight) {
                mobileDirectRescueSuppressed.incrementAndGet()
                return MobileDirectRescueDecision(false, "in_flight", "DC$dcId mobile direct rescue suppressed because another rescue is in flight")
            }
            if (state.cooldownUntilMs > now) {
                mobileDirectRescueSuppressed.incrementAndGet()
                return MobileDirectRescueDecision(false, "cooldown", "DC$dcId mobile direct rescue suppressed because cooldown until=${state.cooldownUntilMs}")
            }
            state.inFlight = true
        }
        mobileDirectRescueAttempts.incrementAndGet()
        return MobileDirectRescueDecision(true, cfDomainHealth.snapshot().pressure.reasonByDc[dcId] ?: "cf_exhausted")
    }

    private fun connectMobileDirectRescue(parsed: MtprotoHandshake.Result, targetHost: String, expectedGeneration: Long): WebSocketBinaryStream? {
        val state = mobileDirectRescueByDc.getOrPut(parsed.dcId) { MobileDirectRescueState() }
        val domain = wsDomains(parsed.dcId, parsed.isMedia).firstOrNull()
        if (domain == null) {
            finishMobileDirectRescueFailure(parsed.dcId, "no direct domain", expectedGeneration)
            return null
        }
        if (routeGeneration.get() != expectedGeneration || !isMobile(currentNetworkStatus) || routeState.configuredRouteMode != NetworkRouteMode.AUTO || effectiveRouteMode() != NetworkRouteMode.CF_FIRST) {
            finishMobileDirectRescueSkippedBecauseNetworkChanged(parsed.dcId)
            logger.log("DC${parsed.dcId} mobile direct rescue skipped because network changed before connect")
            return null
        }
        return try {
            directAttempts.incrementAndGet()
            logger.log("DC${parsed.dcId} mobile direct rescue trying direct/fronting wss://$domain$DEFAULT_WS_PATH via $targetHost")
            val connectResult = directFrontingConnector.connect(
                dc = parsed.dcId,
                isMedia = parsed.isMedia,
                targetHost = targetHost,
                domain = domain,
                path = DEFAULT_WS_PATH,
                normalTimeoutMs = config.directFallbackTimeoutMs,
                networkGeneration = expectedGeneration,
            )
            val webSocket = connectResult.stream
            synchronized(state) {
                state.inFlight = false
                state.cooldownUntilMs = System.currentTimeMillis() + MOBILE_DIRECT_RESCUE_SUCCESS_REUSE_MS
                state.lastError = null
                state.lastSuccessTimeMs = System.currentTimeMillis()
                state.generation = expectedGeneration
            }
            mobileDirectRescueSuccesses.incrementAndGet()
            recordMobileGenerationRecoveryResult(expectedGeneration, success = true)
            logger.log(
                "DC${parsed.dcId} mobile direct rescue success; direct route allowed for this client " +
                    "fronted=${connectResult.fronted} frontingFirst=${connectResult.frontingTriedFirst}",
            )
            webSocket
        } catch (error: Throwable) {
            wsConnectErrors.incrementAndGet()
            if (isTimeout(error)) {
                directTimeouts.incrementAndGet()
                mobileRecoveryDirectRescueTimeoutCount.incrementAndGet()
                recordRecentEvent(recentDirectTimeoutTimes)
                maybeDowngradeDirectRouteForClientExperience()
            }
            val detail = websocketFailureDetail(error)
            finishMobileDirectRescueFailure(parsed.dcId, detail, expectedGeneration)
            logger.log("DC${parsed.dcId} mobile direct rescue failed: $detail")
            null
        }
    }

    private fun finishMobileDirectRescueFailure(dcId: Int, detail: String, expectedGeneration: Long) {
        val state = mobileDirectRescueByDc.getOrPut(dcId) { MobileDirectRescueState() }
        synchronized(state) {
            state.inFlight = false
            if (routeGeneration.get() != expectedGeneration) {
                mobileRescueSkippedBecauseNetworkChanged.incrementAndGet()
                return
            }
            state.cooldownUntilMs = System.currentTimeMillis() + MOBILE_DIRECT_RESCUE_FAILURE_COOLDOWN_MS
            state.lastError = detail
            state.generation = expectedGeneration
        }
        mobileDirectRescueFailures.incrementAndGet()
        recordMobileGenerationRecoveryResult(expectedGeneration, success = false)
    }

    private fun recordMobileGenerationRecoveryResult(generation: Long, success: Boolean) {
        if (generation != lastMobileRecoveryNetworkGeneration.get()) return
        if (success) {
            if (lastMobileRecoveryResult.get() != "success") {
                mobileToMobileRecoverySuccesses.incrementAndGet()
                if (lastMobileRecoveryReason.get()?.startsWith("none->", ignoreCase = true) == true) {
                    noneToMobileRecoverySuccesses.incrementAndGet()
                }
                lastNetworkAvailableAtMs.get().takeIf { it > 0L }?.let { availableAt ->
                    val latency = (System.currentTimeMillis() - availableAt).coerceAtLeast(0L)
                    mobileRecoveryFirstSuccessLatencyMs.compareAndSet(0L, latency)
                    mobileRecoveryCfFirstSuccessLatencyMs.compareAndSet(0L, latency)
                }
            }
            lastMobileRecoveryResult.set("success")
        } else if (lastMobileRecoveryResult.get() != "success") {
            mobileToMobileRecoveryFailures.incrementAndGet()
            lastMobileRecoveryResult.set("failure")
        }
    }

    private fun incrementPoolCounter(
        counters: ConcurrentHashMap<String, AtomicLong>,
        key: WebSocketPool.Key,
        source: String? = null,
    ) {
        counters.getOrPut(poolDiagnosticKey(key, source)) { AtomicLong(0) }.incrementAndGet()
    }

    private fun snapshotPoolLongMap(counters: ConcurrentHashMap<String, AtomicLong>): Map<String, Long> =
        counters.mapValues { it.value.get() }.toSortedMap()

    private fun poolDiagnosticKey(key: WebSocketPool.Key, source: String? = null): String {
        val base = "DC${key.dc}.${if (key.isMedia) "media" else "normal"}"
        return if (source.isNullOrBlank()) base else "$base.$source"
    }

    private fun finishMobileDirectRescueSkippedBecauseNetworkChanged(dcId: Int) {
        val state = mobileDirectRescueByDc.getOrPut(dcId) { MobileDirectRescueState() }
        synchronized(state) {
            state.inFlight = false
        }
        mobileRescueSkippedBecauseNetworkChanged.incrementAndGet()
    }

    private fun getPooledOrConnectWebSocket(
        parsed: MtprotoHandshake.Result,
        targetHost: String,
        usePool: Boolean,
        timeoutMs: Int,
        expectedGeneration: Long? = null,
        allowWifiDirectRecovery: Boolean = false,
    ): WebSocketRoute? {
        if (isDirectTargetIpCooldownActive(targetHost) && config.cfproxyEnabled) {
            directAttemptsSkippedBecauseTargetIpCooldown.incrementAndGet()
            directTargetIpCooldownHits.incrementAndGet()
            logDirectTargetIpCooldownHit(parsed.dcId, targetHost)
            return null
        }
        if (expectedGeneration != null && routeGeneration.get() != expectedGeneration) {
            directAttemptsSkippedBecauseRoute.incrementAndGet()
            logger.log("DC${parsed.dcId} direct route skipped because route generation changed")
            return null
        }
        val allowColdDuringSettling = directColdAllowedDuringSettling()
        val skipReason = directAttemptSkipReasonForCurrentRoute(allowColdDuringSettling = allowColdDuringSettling, allowWifiDirectRecovery = allowWifiDirectRecovery)
        if (skipReason != null) {
            directAttemptsSkippedBecauseRoute.incrementAndGet()
            logger.log("DC${parsed.dcId} direct route skipped because $skipReason")
            return null
        }
        val settling = directRouteHealth.isSettling()
        if (usePool && isDirectPoolEnabled()) {
            if (settling && allowColdDuringSettling) {
                logger.log(
                    "DC${parsed.dcId} route settling prohibits direct pool until ${directRouteHealth.settlingUntilMs()} but allows cold direct",
                )
            } else {
                val pooled = webSocketPool.get(parsed.dcId, parsed.isMedia, targetHost, wsDomains(parsed.dcId, parsed.isMedia))
                if (pooled != null) {
                    poolHits.incrementAndGet()
                    val poolKey = WebSocketPool.Key(parsed.dcId, parsed.isMedia)
                    incrementPoolCounter(poolHitsByKey, poolKey)
                    poolLastHitTimeMsByKey.getOrPut(poolDiagnosticKey(poolKey)) { AtomicLong(0) }.set(System.currentTimeMillis())
                    logger.log("DC${parsed.dcId} direct WS pool hit")
                    return WebSocketRoute(pooled, "direct-pool")
                }
                poolMisses.incrementAndGet()
                val poolKey = WebSocketPool.Key(parsed.dcId, parsed.isMedia)
                incrementPoolCounter(poolMissesByKey, poolKey)
                poolLastMissTimeMsByKey.getOrPut(poolDiagnosticKey(poolKey)) { AtomicLong(0) }.set(System.currentTimeMillis())
                recordRecentEvent(recentPoolMissTimes)
                maybeDowngradeDirectRouteForClientExperience()
                logger.log("DC${parsed.dcId} direct WS pool miss")
            }
        }
        if (settling && allowColdDuringSettling) {
            logger.log("DC${parsed.dcId} client uses cold direct during route settling until ${directRouteHealth.settlingUntilMs()}")
        }
        return guardedConnectWebSocket(
            parsed,
            targetHost,
            timeoutMs,
            expectedGeneration,
            allowColdDuringSettling = allowColdDuringSettling,
            allowWifiDirectRecovery = allowWifiDirectRecovery,
        )?.let { WebSocketRoute(it, "direct-cold") }
    }

    private fun guardedConnectWebSocket(
        parsed: MtprotoHandshake.Result,
        targetHost: String,
        timeoutMs: Int,
        expectedGeneration: Long? = null,
        allowColdDuringSettling: Boolean = directColdAllowedDuringSettling(),
        allowWifiDirectRecovery: Boolean = false,
    ): WebSocketBinaryStream? {
        if (expectedGeneration != null && routeGeneration.get() != expectedGeneration) {
            directAttemptsSkippedBecauseRoute.incrementAndGet()
            logger.log("DC${parsed.dcId} direct connect skipped because route generation changed")
            return null
        }
        val skipReason = directAttemptSkipReasonForCurrentRoute(allowColdDuringSettling = allowColdDuringSettling, allowWifiDirectRecovery = allowWifiDirectRecovery)
        if (skipReason != null) {
            directAttemptsSkippedBecauseRoute.incrementAndGet()
            logger.log("DC${parsed.dcId} direct connect skipped because $skipReason")
            return null
        }
        return connectWebSocket(parsed, targetHost, timeoutMs, allowColdDuringSettling, allowWifiDirectRecovery)
    }

    private fun connectWebSocket(
        parsed: MtprotoHandshake.Result,
        targetHost: String,
        timeoutMs: Int,
        allowColdDuringSettling: Boolean = directColdAllowedDuringSettling(),
        allowWifiDirectRecovery: Boolean = false,
    ): WebSocketBinaryStream? {
        val failures = mutableListOf<String>()
        for (domain in wsDomains(parsed.dcId, parsed.isMedia)) {
            if (!isDirectAttemptAllowedForCurrentRoute(allowColdDuringSettling = allowColdDuringSettling, allowWifiDirectRecovery = allowWifiDirectRecovery)) {
                directAttemptsSkippedBecauseRoute.incrementAndGet()
                logger.log("DC${parsed.dcId} direct WebSocket attempt skipped before $domain because route/network changed")
                break
            }
            logger.log(
                "DC${parsed.dcId} media=${parsed.isMedia} -> wss://$domain$DEFAULT_WS_PATH via $targetHost",
            )
            try {
                directAttempts.incrementAndGet()
                val connectResult = directFrontingConnector.connect(
                    dc = parsed.dcId,
                    isMedia = parsed.isMedia,
                    targetHost = targetHost,
                    domain = domain,
                    path = DEFAULT_WS_PATH,
                    normalTimeoutMs = timeoutMs,
                    networkGeneration = routeGeneration.get(),
                )
                val webSocket = connectResult.stream
                clearDirectTargetIpCooldownAfterSuccess(targetHost)
                logger.log(
                    "DC${parsed.dcId} WebSocket connected via $domain " +
                        "fronted=${connectResult.fronted} frontingFirst=${connectResult.frontingTriedFirst}",
                )
                return webSocket
            } catch (error: Throwable) {
                wsConnectErrors.incrementAndGet()
                if (isTimeout(error)) {
                    directTimeouts.incrementAndGet()
                    recordRecentEvent(recentDirectTimeoutTimes)
                    maybeDowngradeDirectRouteForClientExperience()
                }
                val detail = websocketFailureDetail(error)
                failures.add("$domain ($detail)")
                logger.log("DC${parsed.dcId} WebSocket attempt via $domain failed: $detail")
                val cooldownWorthy = isCooldownWorthyDirectFailure(error)
                if (cooldownWorthy) {
                    setDirectTargetIpCooldown(targetHost, parsed.dcId, detail)
                }
                if (error is SocketException || detail.contains("ENETUNREACH", ignoreCase = true)) {
                    downgradeDirectRouteBecauseHealthDegraded(detail)
                }
                if (cooldownWorthy) {
                    logger.log("DC${parsed.dcId} direct target $targetHost marked failed; stop direct domain attempts")
                    break
                }
            }
        }
        logger.log("DC${parsed.dcId} WebSocket connect failed after attempts: ${failures.joinToString()}")
        return null
    }

        private fun tryCfPoolRoute(
        client: TcpClientTransport,
        parsed: MtprotoHandshake.Result,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
    ): Boolean {
        if (!isCfPoolEligible()) return false
        val pooled = cfWebSocketPool.get(parsed.dcId, parsed.isMedia)
        if (pooled == null) {
            cfPoolMisses.incrementAndGet()
            logger.log("DC${parsed.dcId} CF pool miss")
            return false
        }
        cfPoolHits.incrementAndGet()
        logger.log("DC${parsed.dcId} CF pool hit")
        val result = runWebSocketRoute(client, parsed, WebSocketRoute(pooled, "cf-pool"), relayInit, cryptoContext, splitter)
        if (!result.failed) return true
        if (result.stalePooled && result.retryableStalePooled) {
            logger.log("DC${parsed.dcId} retrying normal CF flow after stale CF pool")
            return false
        }
        return !result.failedBeforeBridge
    }

    private fun maybeScheduleCfPoolRefill(parsed: MtprotoHandshake.Result, baseDomain: String) {
        // This runs only after the demand route has completed successfully. That deliberately
        // makes the CF pool help the next demand wave instead of adding another CF connection
        // during the current parallel burst.
        if (!isCfPoolEligible()) return
        cfWebSocketPool.scheduleRefill(parsed.dcId, parsed.isMedia, baseDomain)
    }

    private fun isCfPoolEligible(): Boolean =
        config.cfproxyEnabled && config.cfPoolEnabled && effectiveRouteMode() == NetworkRouteMode.CF_FIRST && isMobile(currentNetworkStatus)


    private fun tryCfProxyFallback(
        client: TcpClientTransport,
        parsed: MtprotoHandshake.Result,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
    ): Boolean {
        val cycleState = CfDomainFallbackCycleState()
        return tryCfProxyFallbackCycle(client, parsed, relayInit, cryptoContext, splitter, cycleState)
    }

    private fun tryCfProxyFallbackCycle(
        client: TcpClientTransport,
        parsed: MtprotoHandshake.Result,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
        cycleState: CfDomainFallbackCycleState,
    ): Boolean {
        if (currentNetworkStatus.equals("none", ignoreCase = true)) {
            cfDomainHealth.recordTransientNetworkFailure()
            logger.log("DC${parsed.dcId} CF proxy skipped because network=none")
            return false
        }
        val attemptGeneration = routeGeneration.get()
        val pressureDecision = cfDomainHealth.beginPressureManagedCycle(parsed.dcId, currentNetworkStatus)
        logCfPressureLevelChanges()
        if (pressureDecision.controlledFailure) {
            logger.log(
                "CF pressure saturated: probe suppressed for DC${parsed.dcId}, " +
                    "nextProbeAt=${pressureDecision.nextProbeAtMs}; controlled failure instead of starting connect; " +
                    "existing active sessions untouched",
            )
            logger.log("CF pressure kept existing active sessions untouched for DC${parsed.dcId}")
            return false
        }
        if (pressureDecision.level == CfPressureLevel.DEGRADED) {
            logger.log(
                "CF pressure degraded: limiting attempts per client for DC${parsed.dcId} " +
                    "maxAttempts=${pressureDecision.maxAttempts} queueWaitMs=${pressureDecision.connectQueueWaitMs}",
            )
        }
        if (pressureDecision.probeAllowed) {
            logger.log(
                "CF pressure saturated: probe allowed for DC${parsed.dcId} " +
                    "nextProbeAt=${pressureDecision.nextProbeAtMs}",
            )
        }
        val selectionPlan = cfDomainHealth.selectDomains(parsed.dcId, parsed.isMedia, cycleState)
        for (skipped in selectionPlan.skippedCooldown) {
            logger.log(
                "CF domain skipped because cooldown DC${parsed.dcId} ${skipped.domain} until=${skipped.cooldownUntilMs}",
            )
        }
        for (skipped in selectionPlan.skippedInflight) {
            logger.log("CF domain skipped because in-flight DC${parsed.dcId} domain=${skipped.domain}")
        }
        if (selectionPlan.allDomainsInCooldownWaitMs > 0) {
            logger.log(
                "CF all domains in cooldown; client waits ${selectionPlan.allDomainsInCooldownWaitMs}ms " +
                    "for nearest cooldown on DC${parsed.dcId}",
            )
            sleepQuietly(selectionPlan.allDomainsInCooldownWaitMs)
            logger.log("CF all domains in cooldown wait ended; retry route selection for DC${parsed.dcId}")
            return tryCfProxyFallbackCycle(client, parsed, relayInit, cryptoContext, splitter, cycleState)
        }
        if (selectionPlan.allDomainsInCooldownCircuitSuppressed) {
            logCfPressureLevelChanges()
            logger.log(
                "CF all-cooldown circuit suppressed single least-bad attempt for DC${parsed.dcId}; " +
                    "retryAt=${selectionPlan.allDomainsInCooldownCircuitRetryAtMs}",
            )
            logger.log("CF all-cooldown controlled failure for DC${parsed.dcId} instead of starting another connect")
            return false
        }
        if (selectionPlan.allDomainsInCooldownStoppedCycle) {
            logger.log("CF all-cooldown single attempt failed; stop fallback cycle for DC${parsed.dcId}")
            return false
        }
        if (selectionPlan.allDomainsInCooldownFallback) {
            if (selectionPlan.allDomainsInCooldownCircuitOpened) {
                logger.log(
                    "CF all-cooldown circuit opened for DC${parsed.dcId}; " +
                        "nextAllowedAt=${selectionPlan.allDomainsInCooldownCircuitRetryAtMs}",
                )
            }
            logger.log("CF all-cooldown circuit allowed single least-bad attempt for DC${parsed.dcId}")
            logger.log("CF all domains in cooldown; single least-bad attempt for DC${parsed.dcId}")
        }

        var attempted = false
        var pressureAttempts = 0
        cfSelectionLoop@ for (selection in selectionPlan.ordered) {
            if (pressureAttempts >= pressureDecision.maxAttempts) {
                cfDomainHealth.recordPressureLimitedAttempt(parsed.dcId)
                logCfPressureLevelChanges()
                logger.log(
                    "CF pressure controlled failure instead of starting connect for DC${parsed.dcId}: " +
                        "attempt limit ${pressureDecision.maxAttempts} reached at level=${pressureDecision.level.configValue}",
                )
                break@cfSelectionLoop
            }
            val baseDomain = selection.domain
            attempted = true
            pressureAttempts += 1
            val cfConnectTarget = CfProxyConnectTarget.forDcBaseDomain(parsed.dcId, baseDomain)
            val domain = cfConnectTarget.domain
            when (cfDomainHealth.acquireConnectDecision(parsed.dcId, parsed.isMedia, baseDomain, pressureDecision.connectQueueWaitMs)) {
                CfConnectAcquireResult.ACQUIRED -> Unit
                CfConnectAcquireResult.QUEUE_TIMEOUT -> {
                    logCfPressureLevelChanges()
                    recordRecentEvent(recentCfConnectQueueTimeoutTimes)
                    recordRecentEvent(recentCfQueueControlledFailureTimes)
                    logger.log("CF connect queue controlled failure DC${parsed.dcId} domain=$baseDomain")
                    if (selectionPlan.allDomainsInCooldownFallback) {
                        cfDomainHealth.recordAllCooldownSingleAttemptFailure()
                        logger.log("CF all-cooldown single attempt failed; stop fallback cycle")
                    }
                    return false
                }
                CfConnectAcquireResult.DOMAIN_IN_FLIGHT, CfConnectAcquireResult.UNAVAILABLE -> {
                    logCfPressureLevelChanges()
                    logger.log("CF domain skipped because in-flight DC${parsed.dcId} domain=$baseDomain")
                    if (selectionPlan.allDomainsInCooldownFallback) {
                        cfDomainHealth.recordAllCooldownSingleAttemptFailure()
                        logger.log("CF all-cooldown single attempt failed; stop fallback cycle")
                        return false
                    }
                    continue@cfSelectionLoop
                }
            }
            cfDomainHealth.recordSelected(parsed.dcId, parsed.isMedia, baseDomain, domain, selection.reason)
            logger.log(
                "CF selector DC${parsed.dcId} chose $domain reason=${selection.reason} latency=${selection.latencyMs ?: "unknown"}",
            )
            logger.log("DC${parsed.dcId} -> trying CF proxy wss://$domain$DEFAULT_WS_PATH")
            if (routeGeneration.get() != attemptGeneration || currentNetworkStatus.equals("none", ignoreCase = true)) {
                cfDomainHealth.recordFailureIgnoredBecauseNetworkChanged()
                logger.log("DC${parsed.dcId} CF proxy attempt skipped for $baseDomain because route/network generation changed")
                cfDomainHealth.releaseConnect(parsed.dcId, parsed.isMedia, baseDomain)
                logger.log("CF domain released in-flight DC${parsed.dcId} domain=$baseDomain")
                continue@cfSelectionLoop
            }
            val startedAtNs = System.nanoTime()
            val webSocket = try {
                webSocketConnector.connect(
                    cfConnectTarget.targetHost,
                    cfConnectTarget.domain,
                    cfConnectTarget.path,
                    cfConnectTarget.timeoutMs,
                )
            } catch (error: Throwable) {
                cfProxyErrors.incrementAndGet()
                val detail = websocketFailureDetail(error)
                val decision = cfDomainHealth.recordFailure(
                    parsed.dcId,
                    parsed.isMedia,
                    baseDomain,
                    error,
                    currentNetworkStatus,
                    directRouteHealth.isSettling(),
                    routeGeneration.get() != attemptGeneration,
                )
                logCfPressureLevelChanges()
                logger.log("DC${parsed.dcId} CF proxy failed via $baseDomain: $detail")
                if (decision.counted && decision.cooldownUntilMs > 0) {
                    logger.log(
                        "CF domain cooldown DC${parsed.dcId} $baseDomain reason=${decision.kind.configValue} until=${decision.cooldownUntilMs}",
                    )
                }
                if (selectionPlan.allDomainsInCooldownFallback) {
                    cfDomainHealth.recordAllCooldownSingleAttemptFailure()
                    logger.log("CF all-cooldown single attempt failed; stop fallback cycle")
                }
                null
            } finally {
                cfDomainHealth.releaseConnect(parsed.dcId, parsed.isMedia, baseDomain)
                logger.log("CF domain released in-flight DC${parsed.dcId} domain=$baseDomain")
            }
            if (webSocket == null) {
                if (selectionPlan.allDomainsInCooldownFallback) return false
                continue@cfSelectionLoop
            }

            val latencyMs = (System.nanoTime() - startedAtNs) / 1_000_000
            try {
                cfProxyConnections.incrementAndGet()
                lastCfDomain = domain
                val circuitReset = cfDomainHealth.recordSuccess(parsed.dcId, parsed.isMedia, baseDomain, latencyMs)
                logCfPressureLevelChanges()
                recordSuccessfulRoute()
                recordMobileGenerationRecoveryResult(attemptGeneration, success = true)
                if (circuitReset) logger.log("CF all-cooldown circuit reset after success for DC${parsed.dcId}")
                logger.log("CF pressure reset/relaxed after success for DC${parsed.dcId} $baseDomain")
                logger.log("CF domain success DC${parsed.dcId} $baseDomain latencyMs=$latencyMs")
                logger.log("DC${parsed.dcId} CF proxy connected via $domain")
                cfProxyBalancer.updateDomainForDc(parsed.dcId, baseDomain)
                val result = runWebSocketRoute(client, parsed, WebSocketRoute(webSocket, "cf"), relayInit, cryptoContext, splitter)
                if (!result.failed) {
                    maybeScheduleCfPoolRefill(parsed, baseDomain)
                    return true
                }
                cfDomainHealth.recordCfRouteFailureAfterConnect(parsed.dcId)
                logCfPressureLevelChanges()
                cfProxyErrors.incrementAndGet()
                logger.log("DC${parsed.dcId} CF proxy route failed via $baseDomain")
            } catch (error: Throwable) {
                cfProxyErrors.incrementAndGet()
                logger.log("DC${parsed.dcId} CF proxy failed via $baseDomain: ${websocketFailureDetail(error)}")
            }
        }
        if (!attempted) {
            logger.log("DC${parsed.dcId} CF proxy has no bundled base domains configured")
        } else {
            logger.log("DC${parsed.dcId} all CF proxy fallback attempts failed")
        }
        return false
    }

    private fun logCfPressureLevelChanges() {
        for (change in cfDomainHealth.drainPressureLevelChanges()) {
            logger.log(
                "CF pressure level changed for DC${change.dcId}: " +
                    "${change.from.configValue} -> ${change.to.configValue}",
            )
        }
    }

    private fun sleepQuietly(delayMs: Long) {
        try {
            Thread.sleep(delayMs.coerceAtLeast(0L))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun runWebSocketRoute(
        client: TcpClientTransport,
        parsed: MtprotoHandshake.Result,
        route: WebSocketRoute,
        relayInit: ByteArray,
        cryptoContext: CryptoContext,
        splitter: MsgSplitter,
    ): WebSocketRouteResult {
        val counters = BridgeSessionCounters()
        val startedAtNs = System.nanoTime()
        var routeFailure: Throwable? = null
        var bridgeStarted = false
        var durationMs = 0L
        var reason = "completed"
        try {
            lastRouteUsed = route.type
            lastRouteUsedUpdateTimeMs.set(System.currentTimeMillis())
            route.stream.send(relayInit)
            recordSuccessfulRoute()
            bridgeStarted = true
            bridgeRunner.run(
                client,
                route.stream,
                cryptoContext,
                splitter,
                counters,
            )
        } catch (error: Throwable) {
            routeFailure = error
            counters.finish(bridgeExceptionReason(error))
            if (!bridgeStarted) {
                logger.log("DC${parsed.dcId} ${route.type} route failed before bridge: ${failureDetail(error)}")
                if (route.type.startsWith("direct") && (error is SocketException || error.message.orEmpty().contains("ENETUNREACH", ignoreCase = true))) {
                    downgradeDirectRouteBecauseHealthDegraded(failureDetail(error))
                }
            } else {
                logger.log("DC${parsed.dcId} ${route.type} route failed: ${failureDetail(error)}")
            }
        } finally {
            bytesUp.addAndGet(counters.bytesUp)
            bytesDown.addAndGet(counters.bytesDown)
            durationMs = (System.nanoTime() - startedAtNs) / 1_000_000
            val rawReason = counters.closeReason
                ?: routeFailure?.let { bridgeExceptionReason(it) }
                ?: "completed"
            val classification = classifySessionEnd(rawReason, routeFailure, durationMs)
            reason = classification.reason
            recordSessionEnd(classification, durationMs, route.type, parsed.dcId, parsed.isMedia)
            val debugDetail = classification.debugDetail?.let { " detail=$it" }.orEmpty()
            logger.log(
                "${client.remoteLabel} session ended: DC${parsed.dcId} media=${parsed.isMedia} " +
                    "route=${route.type} durationMs=$durationMs bytesUp=${counters.bytesUp} " +
                    "bytesDown=${counters.bytesDown} reason=$reason$debugDetail",
            )
            try {
                route.stream.close()
            } catch (_: Throwable) {
                // Best-effort close.
            }
        }
        val failedBeforeBridge = routeFailure != null && !bridgeStarted
        val stalePooled = isStalePooledRouteFailure(route, routeFailure, reason, durationMs, counters, failedBeforeBridge)
        val retryableStalePooled = stalePooled && isRetrySafeStalePooledRoute(counters, failedBeforeBridge)
        if (stalePooled) {
            if (route.type == "cf-pool") {
                cfPoolStale.incrementAndGet()
            } else {
                poolStale.incrementAndGet()
                incrementPoolCounter(poolStaleByKey, WebSocketPool.Key(parsed.dcId, parsed.isMedia))
            }
            recordRecentEvent(recentPoolStaleTimes)
            if (route.type == "direct-pool") {
                maybeDowngradeDirectRouteForClientExperience()
                recordDirectPoolStaleForHealth()
            }
            logger.log(
                "DC${parsed.dcId} ${route.type} stale route detected: $reason " +
                    "durationMs=$durationMs bytesUp=${counters.bytesUp} bytesDown=${counters.bytesDown}",
            )
            if (!retryableStalePooled) {
                logger.log("DC${parsed.dcId} stale ${route.type} route not retried after bridge state advanced")
            }
        }
        return WebSocketRouteResult(
            failed = routeFailure != null || stalePooled,
            failedBeforeBridge = failedBeforeBridge,
            stalePooled = stalePooled,
            retryableStalePooled = retryableStalePooled,
        )
    }

    private fun isStalePooledRouteFailure(
        route: WebSocketRoute,
        routeFailure: Throwable?,
        reason: String,
        durationMs: Long,
        counters: BridgeSessionCounters,
        failedBeforeBridge: Boolean,
    ): Boolean {
        if (route.type != "direct-pool" && route.type != "cf-pool") return false
        if (!isStalePoolFailure(routeFailure, reason)) return false
        if (failedBeforeBridge) return true
        return durationMs < STALE_POOL_MAX_DURATION_MS || counters.bytesDown == 0L
    }

    private fun isRetrySafeStalePooledRoute(
        counters: BridgeSessionCounters,
        failedBeforeBridge: Boolean,
    ): Boolean {
        if (failedBeforeBridge) return true
        // After bridge startup, retry is only safe while no MTProto payload has
        // moved in either direction. Once bytesUp/bytesDown advance, the shared
        // AES-CTR streams and message splitter may have advanced too, so the
        // client session must not be replayed on a fresh route.
        return counters.bytesUp == 0L && counters.bytesDown == 0L
    }

    private fun isStalePoolFailure(error: Throwable?, reason: String): Boolean {
        if (error != null) {
            if (error is EOFException || error is SocketException) return true
            val message = error.message.orEmpty().lowercase()
            if (message.contains("broken pipe") || message.contains("websocket closed")) return true
        }
        val lowerReason = reason.lowercase()
        return lowerReason.contains("eofexception") ||
            lowerReason.contains("remote_eof") ||
            lowerReason.contains("remote_idle_eof") ||
            lowerReason.contains("broken pipe") ||
            lowerReason.contains("websocket closed") ||
            lowerReason.contains("socketexception")
    }

    private fun recordSessionEnd(
        classification: SessionEndClassification,
        durationMs: Long,
        route: String,
        dc: Int,
        media: Boolean,
    ) {
        when (classification.reason) {
            "timeout" -> sessionTimeouts.incrementAndGet()
            "remote_eof", "remote_idle_eof" -> recordRemoteEof(classification, durationMs, route, dc, media)
            "client_closed" -> {
                sessionClientClosed.incrementAndGet()
                recordRecentEvent(recentClientClosedTimes)
                if (durationMs <= VERY_SHORT_SESSION_MS) recordRecentEvent(recentVeryShortClientClosedTimes)
            }
            "socket_closed" -> sessionSocketClosed.incrementAndGet()
            "connection_reset" -> recordConnectionReset(route, dc, media)
            "connection_timed_out" -> recordConnectionTimedOut(route, dc, media)
            "unexpected_error" -> sessionUnexpectedErrors.incrementAndGet()
        }
    }

    private fun recordConnectionReset(route: String, dc: Int, media: Boolean) {
        sessionConnectionReset.incrementAndGet()
        recordRecentEvent(recentConnectionResetTimes)
        lastConnectionResetTimeMs.set(System.currentTimeMillis())
        lastConnectionResetRoute.set(route)
        lastConnectionResetDc.set(dc)
        lastConnectionResetMedia.set(media)
    }

    private fun recordConnectionTimedOut(route: String, dc: Int, media: Boolean) {
        sessionConnectionTimedOut.incrementAndGet()
        lastConnectionTimedOutTimeMs.set(System.currentTimeMillis())
        lastConnectionTimedOutRoute.set(route)
        lastConnectionTimedOutDc.set(dc)
        lastConnectionTimedOutMedia.set(media)
    }

    private fun recordRemoteEof(
        classification: SessionEndClassification,
        durationMs: Long,
        route: String,
        dc: Int,
        media: Boolean,
    ) {
        sessionEof.incrementAndGet()
        sessionRemoteEof.incrementAndGet()
        if (classification.remoteIdleEof) {
            sessionRemoteIdleEof.incrementAndGet()
        } else {
            sessionRemoteEofShort.incrementAndGet()
            if (durationMs <= SHORT_REMOTE_EOF_SESSION_MS) {
                recordRecentEvent(recentShortRemoteEofTimes)
                maybeDowngradeDirectRouteForClientExperience()
            }
        }
        lastRemoteEofTimeMs.set(System.currentTimeMillis())
        lastRemoteEofDurationMs.set(durationMs)
        lastRemoteEofRoute.set(route)
        lastRemoteEofDc.set(dc)
        lastRemoteEofMedia.set(media)
    }

    private fun markBad(message: String) {
        connectionsBad.incrementAndGet()
        if (message.startsWith(INVALID_MTPROTO_HANDSHAKE_PREFIX)) {
            recordInvalidHandshake()
            invalidHandshakeLogLimiter.log(message, logger) { stats().handshakeDiagnostic }
        } else {
            logger.log(message)
        }
    }

    private fun recordInvalidHandshake(now: Long = System.currentTimeMillis()) {
        lastInvalidHandshakeTimeMs.set(now)
        recentInvalidHandshakeTimes.addLast(now)
        pruneRecentHandshakeWindows(now)
    }

    private fun recordAcceptedHandshake(
        now: Long = System.currentTimeMillis(),
        wasClientExperienceIdle: Boolean = false,
    ) {
        lastAcceptedHandshakeTimeMs.set(now)
        recentAcceptedHandshakeTimes.addLast(now)
        if (wasClientExperienceIdle) {
            recordRecentEvent(recentIdleWaveAcceptedTimes, now)
            currentIdleWaveStartMs.compareAndSet(0L, now)
            lastTimeToFirstSuccessfulRouteAfterIdleMs.set(-1L)
        }
        pruneRecentHandshakeWindows(now)
    }

    private fun recordSuccessfulRoute(now: Long = System.currentTimeMillis()) {
        lastSuccessfulRouteTimeMs.set(now)
        val idleStart = currentIdleWaveStartMs.get()
        if (idleStart > 0L && lastTimeToFirstSuccessfulRouteAfterIdleMs.compareAndSet(-1L, (now - idleStart).coerceAtLeast(0L))) {
            currentIdleWaveStartMs.set(0L)
        }
    }

    private fun recordRecentEvent(events: ConcurrentLinkedDeque<Long>, now: Long = System.currentTimeMillis()) {
        events.addLast(now)
        pruneDeque(events, now - CLIENT_EXPERIENCE_RECENT_WINDOW_MS)
    }

    private fun recordUnsupportedDc(dcId: Int, now: Long = System.currentTimeMillis()) {
        unsupportedDc.incrementAndGet()
        recordRecentEvent(recentUnsupportedDcTimes.getOrPut(dcId) { ConcurrentLinkedDeque() }, now)
    }

    private fun recordNoRoute(dcId: Int, now: Long = System.currentTimeMillis()) {
        recordRecentEvent(recentNoRouteTimes.getOrPut(dcId) { ConcurrentLinkedDeque() }, now)
    }

    private fun pruneRecentHandshakeWindows(now: Long) {
        val cutoff = now - ProxyServerStats.BAD_HANDSHAKE_RECENT_WINDOW_MS
        pruneDeque(recentInvalidHandshakeTimes, cutoff)
        pruneDeque(recentAcceptedHandshakeTimes, cutoff)
        pruneClientExperienceWindows(now)
    }

    private fun pruneClientExperienceWindows(now: Long) {
        val cutoff = now - CLIENT_EXPERIENCE_RECENT_WINDOW_MS
        listOf(
            recentIdleWaveAcceptedTimes, recentClientClosedTimes, recentVeryShortClientClosedTimes,
            recentShortRemoteEofTimes, recentConnectionResetTimes, recentDirectTimeoutTimes, recentPoolMissTimes,
            recentPoolRefillErrorTimes, recentPoolStaleTimes, recentCfQueueControlledFailureTimes, recentCfConnectQueueTimeoutTimes,
        ).forEach { pruneDeque(it, cutoff) }
        recentUnsupportedDcTimes.values.forEach { pruneDeque(it, cutoff) }
        recentNoRouteTimes.values.forEach { pruneDeque(it, cutoff) }
    }

    private fun pruneDeque(events: ConcurrentLinkedDeque<Long>, cutoff: Long) {
        while (events.peekFirst()?.let { it < cutoff } == true) events.pollFirst()
    }

    private fun buildClientExperienceDiagnostics(now: Long): ClientExperienceDiagnostics {
        pruneClientExperienceWindows(now)
        val unsupported = recentUnsupportedDcTimes.mapValues { it.value.size.toLong() }.filterValues { it > 0L }.toSortedMap()
        val noRoute = recentNoRouteTimes.mapValues { it.value.size.toLong() }.filterValues { it > 0L }.toSortedMap()
        val accepted = recentAcceptedHandshakeTimes.size.toLong()
        val qualitySignals = recentPoolMissTimes.isNotEmpty() || recentVeryShortClientClosedTimes.isNotEmpty() ||
            recentShortRemoteEofTimes.isNotEmpty() || recentDirectTimeoutTimes.isNotEmpty() || unsupported.isNotEmpty() || noRoute.isNotEmpty() ||
            recentCfQueueControlledFailureTimes.isNotEmpty() || recentCfConnectQueueTimeoutTimes.isNotEmpty()
        val hasAcceptedHandshake = accepted > 0L
        val likelyBurst = hasAcceptedHandshake && recentIdleWaveAcceptedTimes.isNotEmpty() && accepted >= RECONNECT_BURST_MIN_HANDSHAKES && qualitySignals
        val disruptiveEnds = recentVeryShortClientClosedTimes.size + recentShortRemoteEofTimes.size + recentConnectionResetTimes.size
        val routeAgeMs = lastSuccessfulRouteTimeMs.get().takeIf { it > 0L }?.let { (now - it).coerceAtLeast(0L) }
        val likelyDisabled = hasAcceptedHandshake && accepted >= TELEGRAM_DISABLED_MIN_HANDSHAKES && disruptiveEnds >= TELEGRAM_DISABLED_MIN_DISRUPTIVE_ENDS &&
            (routeAgeMs == null || routeAgeMs >= TELEGRAM_DISABLED_NO_ROUTE_MS) && clientExperienceActiveSessions.get() == 0 && running.get()
        return ClientExperienceDiagnostics(
            likelyReconnectBurst = likelyBurst,
            likelyTelegramDisabledProxy = likelyDisabled,
            recentAcceptedHandshakes = accepted,
            recentClientClosedSessions = recentClientClosedTimes.size.toLong(),
            recentVeryShortClientClosedSessions = recentVeryShortClientClosedTimes.size.toLong(),
            recentShortRemoteEofSessions = recentShortRemoteEofTimes.size.toLong(),
            recentConnectionResetSessions = recentConnectionResetTimes.size.toLong(),
            recentDirectTimeouts = recentDirectTimeoutTimes.size.toLong(),
            recentPoolMisses = recentPoolMissTimes.size.toLong(),
            recentPoolRefillErrors = recentPoolRefillErrorTimes.size.toLong(),
            recentPoolStale = recentPoolStaleTimes.size.toLong(),
            clientExperienceDirectDowngrades = clientExperienceDirectDowngrades.get(),
            lastClientExperienceDirectDowngradeReason = lastClientExperienceDirectDowngradeReason.get(),
            lastClientExperienceDirectDowngradeTimeMs = lastClientExperienceDirectDowngradeTimeMs.get(),
            recentUnsupportedDcByDc = unsupported,
            recentNoRouteByDc = noRoute,
            recentCfQueueControlledFailures = recentCfQueueControlledFailureTimes.size.toLong(),
            recentCfConnectQueueTimeouts = recentCfConnectQueueTimeoutTimes.size.toLong(),
            timeToFirstSuccessfulRouteAfterIdleMs = lastTimeToFirstSuccessfulRouteAfterIdleMs.get().takeIf { it >= 0L },
            wakeBurstPrewarmTriggers = wakeBurstPrewarmTriggers.get(),
            wakeBurstPrewarmSkippedNoDirectRedirect = wakeBurstPrewarmSkippedNoDirectRedirect.get(),
            wakeBurstPrewarmSkippedCooldown = wakeBurstPrewarmSkippedCooldown.get(),
            wakeBurstPrewarmAttempts = wakeBurstPrewarmAttempts.get(),
            wakeBurstPrewarmSuccesses = wakeBurstPrewarmSuccesses.get(),
            wakeBurstPrewarmFailures = wakeBurstPrewarmFailures.get(),
            lastWakeBurstPrewarmTimeMs = lastWakeBurstPrewarmTimeMs.get(),
            lastWakeBurstPrewarmDc = lastWakeBurstPrewarmDc.get(),
            lastWakeBurstPrewarmError = lastWakeBurstPrewarmError.get(),
            idlePoolMaintenanceRuns = idlePoolMaintenanceRuns.get(),
            idlePoolMaintenanceSkippedNetwork = idlePoolMaintenanceSkippedNetwork.get(),
            idlePoolMaintenanceSkippedRoute = idlePoolMaintenanceSkippedRoute.get(),
            idlePoolMaintenanceSkippedDirectHealth = idlePoolMaintenanceSkippedDirectHealth.get(),
            idlePoolMaintenanceSkippedActiveSessions = idlePoolMaintenanceSkippedActiveSessions.get(),
            idlePoolMaintenanceAttempts = idlePoolMaintenanceAttempts.get(),
            idlePoolMaintenanceSuccesses = idlePoolMaintenanceSuccesses.get(),
            idlePoolMaintenanceFailures = idlePoolMaintenanceFailures.get(),
            lastIdlePoolMaintenanceTimeMs = lastIdlePoolMaintenanceTimeMs.get(),
            lastIdlePoolMaintenanceError = lastIdlePoolMaintenanceError.get(),
        )
    }

    private fun effectiveRouteMode(): NetworkRouteMode = routeState.effectiveRouteMode

    private fun isMobile(networkStatus: String): Boolean = networkStatus.equals("mobile", ignoreCase = true) ||
        networkStatus.equals("cellular", ignoreCase = true)

    private fun isMobileLike(networkStatus: String): Boolean =
        isMobile(networkStatus) || networkStatus.equals("unknown", ignoreCase = true)

    private fun isDirectPoolEnabled(): Boolean = isDirectPoolEnabledFor(effectiveRouteMode())

    private fun isDirectAttemptAllowedForCurrentRoute(allowColdDuringSettling: Boolean = false, allowWifiDirectRecovery: Boolean = false): Boolean =
        directAttemptSkipReasonForCurrentRoute(
            allowColdDuringSettling = allowColdDuringSettling,
            allowWifiDirectRecovery = allowWifiDirectRecovery,
        ) == null

    private fun directAttemptSkipReasonForCurrentRoute(
        allowColdDuringSettling: Boolean = false,
        allowWifiDirectRecovery: Boolean = false,
    ): String? {
        val snapshot = routeState.snapshot()
        if (snapshot.configuredRouteMode == NetworkRouteMode.DIRECT_FIRST) {
            return if (snapshot.effectiveRouteMode == NetworkRouteMode.DIRECT_FIRST) null
                else "effective route mode ${snapshot.effectiveRouteMode.configValue}"
        }
        if (currentNetworkStatus.equals("none", ignoreCase = true) || isMobile(currentNetworkStatus)) {
            return "network=$currentNetworkStatus"
        }
        if (directRouteHealth.isSettling() && !allowColdDuringSettling && !allowWifiDirectRecovery) return "route settling"
        return when (snapshot.effectiveRouteMode) {
            NetworkRouteMode.DIRECT_FIRST, NetworkRouteMode.AUTO -> null
            NetworkRouteMode.CF_FIRST -> if (snapshot.configuredRouteMode == NetworkRouteMode.CF_FIRST || allowWifiDirectRecovery) null
                else "effective route mode ${snapshot.effectiveRouteMode.configValue}"
            NetworkRouteMode.CF_ONLY -> "effective route mode ${snapshot.effectiveRouteMode.configValue}"
        }
    }

    private fun shouldAttemptWifiDirectRecovery(routeAttemptStartGeneration: Long): Boolean {
        val snapshot = routeState.snapshot()
        if (snapshot.configuredRouteMode != NetworkRouteMode.AUTO) return false
        if (snapshot.effectiveRouteMode != NetworkRouteMode.CF_FIRST) return false
        if (!isWifi(currentNetworkStatus)) return false
        val generationChanged = routeGeneration.get() != routeAttemptStartGeneration
        if (generationChanged) {
            routeAttemptNetworkChangedBeforeSelection.incrementAndGet()
            lastCfFirstRecoveryReason.set("network generation changed on Wi-Fi")
            return true
        }
        val now = System.currentTimeMillis()
        if (now <= wifiDirectRecoveryUntilMs && (directRouteHealth.isChecking() || directRouteHealth.hasRecentSuccess(WIFI_DIRECT_RECOVERY_RECENT_SUCCESS_MS))) {
            lastCfFirstRecoveryReason.set("recent Wi-Fi direct probe success/checking")
            return true
        }
        val cooldownExpired = directRouteHealth.snapshot().cooldownUntilMs <= now
        val downgradeAgeMs = now - lastDirectDowngradeTimeMs
        val stalePoolDowngrade = isStalePoolRelated(lastDirectDowngradeReason) || isStalePoolRelated(directRouteHealth.snapshot().lastError)
        if (cooldownExpired && stalePoolDowngrade && downgradeAgeMs >= WIFI_CF_FIRST_RECOVERY_MIN_DOWNGRADE_AGE_MS) {
            lastCfFirstRecoveryReason.set("stale pool downgrade recovered after cooldown")
            return true
        }
        return false
    }

    private fun shouldAttemptActiveWifiDirectRecoveryBeforeCf(): Boolean {
        val snapshot = routeState.snapshot()
        if (snapshot.configuredRouteMode != NetworkRouteMode.AUTO) return false
        if (snapshot.effectiveRouteMode != NetworkRouteMode.CF_FIRST) return false
        if (!isWifi(currentNetworkStatus)) return false
        val now = System.currentTimeMillis()
        if (now > wifiDirectRecoveryUntilMs) return false
        if (!directRouteHealth.isChecking() && !directRouteHealth.hasRecentSuccess(WIFI_DIRECT_RECOVERY_RECENT_SUCCESS_MS)) return false
        lastCfFirstRecoveryReason.set("active Wi-Fi direct probe/checking before CF")
        return true
    }

    private fun isStalePoolRelated(reason: String?): Boolean =
        reason?.contains("poolStale", ignoreCase = true) == true ||
            reason?.contains("stale pool", ignoreCase = true) == true ||
            reason?.contains("stale-pool", ignoreCase = true) == true

    private fun directColdAllowedDuringSettling(): Boolean {
        val snapshot = routeState.snapshot()
        return snapshot.configuredRouteMode == NetworkRouteMode.AUTO &&
            snapshot.effectiveRouteMode == NetworkRouteMode.DIRECT_FIRST &&
            isWifi(currentNetworkStatus) &&
            directRouteHealth.currentState() == DirectHealthState.HEALTHY
    }

    private fun handlePoolForRouteChange(previous: NetworkRouteMode, current: NetworkRouteMode) {
        if (isDirectPoolEnabledFor(current) && isWifi(currentNetworkStatus)) {
            if (!isDirectPoolEnabledFor(previous)) startDirectPoolWarmup("route change") else webSocketPool.enable()
        } else {
            webSocketPool.disableAndClear()
            if (config.poolSize > 0) {
                logger.log("Direct WS pool warmup skipped because effective route mode ${current.configValue}")
            }
        }
    }

    private fun startDirectPoolWarmup(reason: String) {
        if (config.poolSize <= 0) return
        logger.log("Direct WS pool warmup started because effective route mode ${effectiveRouteMode().configValue} ($reason)")
        webSocketPool.warmup(config.dcRedirects, ::wsDomains)
    }

    private fun startIdlePoolMaintenanceThread() {
        if (config.poolSize <= 0 || config.dcRedirects.isEmpty()) return
        idlePoolMaintenanceThread = Thread(::idlePoolMaintenanceLoop, "ProxyServer-idle-pool-maintenance").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun idlePoolMaintenanceLoop() {
        while (running.get()) {
            try {
                Thread.sleep(IDLE_POOL_MAINTENANCE_POLL_MS)
                maybeRunIdlePoolMaintenance()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (error: Throwable) {
                idlePoolMaintenanceFailures.incrementAndGet()
                lastIdlePoolMaintenanceError.set(failureDetail(error))
                logger.log("idle direct WS pool maintenance failed: ${failureDetail(error)}")
            }
        }
    }

    private fun maybeRunIdlePoolMaintenance() {
        if (!running.get() || config.poolSize <= 0) return
        val now = System.currentTimeMillis()
        if (now < idlePoolMaintenanceCooldownUntilMs.get()) return
        if (clientExperienceActiveSessions.get() > 0) {
            idlePoolMaintenanceSkippedActiveSessions.incrementAndGet()
            idlePoolMaintenanceCooldownUntilMs.compareAndSet(idlePoolMaintenanceCooldownUntilMs.get(), now + IDLE_POOL_MAINTENANCE_COOLDOWN_MS)
            return
        }
        if (now - clientExperienceLastIdleAtMs.get() < IDLE_POOL_MAINTENANCE_IDLE_THRESHOLD_MS) return
        if (!isIdlePoolMaintenanceNetworkReady()) {
            idlePoolMaintenanceSkippedNetwork.incrementAndGet()
            idlePoolMaintenanceCooldownUntilMs.compareAndSet(idlePoolMaintenanceCooldownUntilMs.get(), now + IDLE_POOL_MAINTENANCE_COOLDOWN_MS)
            return
        }
        if (!isDirectPoolEnabled()) {
            idlePoolMaintenanceSkippedRoute.incrementAndGet()
            idlePoolMaintenanceCooldownUntilMs.compareAndSet(idlePoolMaintenanceCooldownUntilMs.get(), now + IDLE_POOL_MAINTENANCE_COOLDOWN_MS)
            return
        }
        val directHealth = directRouteHealth.currentState()
        if (directRouteHealth.isSettling() || directHealth == DirectHealthState.UNHEALTHY || directHealth == DirectHealthState.COOLDOWN) {
            idlePoolMaintenanceSkippedDirectHealth.incrementAndGet()
            idlePoolMaintenanceCooldownUntilMs.compareAndSet(idlePoolMaintenanceCooldownUntilMs.get(), now + IDLE_POOL_MAINTENANCE_COOLDOWN_MS)
            return
        }
        if (!idlePoolMaintenanceCooldownUntilMs.compareAndSet(idlePoolMaintenanceCooldownUntilMs.get(), now + IDLE_POOL_MAINTENANCE_COOLDOWN_MS)) return
        idlePoolMaintenanceRuns.incrementAndGet()
        lastIdlePoolMaintenanceTimeMs.set(now)
        lastIdlePoolMaintenanceError.set(null)
        for ((dcId, targetHost) in config.dcRedirects) {
            idlePoolMaintenanceAttempts.incrementAndGet()
            try {
                webSocketPool.ensureMinReadyForDc(dcId, targetHost, MIN_IDLE_WARM_POOL_PER_DC, ::wsDomains)
                idlePoolMaintenanceSuccesses.incrementAndGet()
            } catch (error: Throwable) {
                idlePoolMaintenanceFailures.incrementAndGet()
                lastIdlePoolMaintenanceError.set(failureDetail(error))
                logger.log("DC${dcId} idle direct WS pool maintenance failed: ${failureDetail(error)}")
            }
        }
    }

    private fun isIdlePoolMaintenanceNetworkReady(): Boolean =
        !currentNetworkStatus.equals("none", ignoreCase = true) &&
            !currentNetworkStatus.contains("unvalidated", ignoreCase = true) &&
            networkSettlingUntilMs.get() <= System.currentTimeMillis()

    private fun maybeScheduleWakeBurstPrewarm(
        dcId: Int,
        wasClientExperienceIdle: Boolean,
        idleDurationMs: Long,
    ) {
        if (!wasClientExperienceIdle || idleDurationMs < WAKE_BURST_IDLE_THRESHOLD_MS) return
        val targetHost = config.dcRedirects[dcId]
        if (targetHost == null) {
            wakeBurstPrewarmSkippedNoDirectRedirect.incrementAndGet()
            return
        }
        if (config.poolSize <= 0 || !isDirectPoolEnabled()) return
        val now = System.currentTimeMillis()
        val cooldownUntil = wakeBurstPrewarmCooldownUntilMs.get()
        if (now < cooldownUntil) {
            wakeBurstPrewarmSkippedCooldown.incrementAndGet()
            return
        }
        if (!wakeBurstPrewarmCooldownUntilMs.compareAndSet(cooldownUntil, now + WAKE_BURST_PREWARM_COOLDOWN_MS)) {
            wakeBurstPrewarmSkippedCooldown.incrementAndGet()
            return
        }
        wakeBurstPrewarmTriggers.incrementAndGet()
        wakeBurstPrewarmAttempts.incrementAndGet()
        lastWakeBurstPrewarmTimeMs.set(now)
        lastWakeBurstPrewarmDc.set(dcId)
        lastWakeBurstPrewarmError.set(null)
        try {
            webSocketPool.prewarmDc(dcId, targetHost, ::wsDomains)
            wakeBurstPrewarmSuccesses.incrementAndGet()
            logger.log("DC${dcId} wake-burst direct WS pool prewarm scheduled")
        } catch (error: Throwable) {
            wakeBurstPrewarmFailures.incrementAndGet()
            lastWakeBurstPrewarmError.set(failureDetail(error))
            logger.log("DC${dcId} wake-burst direct WS pool prewarm failed: ${failureDetail(error)}")
        }
    }


    private fun maybeStartAutoWifiDirectProbe(
        networkStatus: String,
        previousNetworkStatus: String = "unknown",
    ) {
        if (routeState.configuredRouteMode != NetworkRouteMode.AUTO) return
        if (!isWifi(networkStatus)) return
        val healthState = directRouteHealth.currentState()
        if (effectiveRouteMode() == NetworkRouteMode.DIRECT_FIRST && healthState == DirectHealthState.HEALTHY) {
            directProbeSkippedBecauseAlreadyHealthy.incrementAndGet()
            logger.log("direct promotion skipped: direct route already healthy")
            return
        }
        if (healthState == DirectHealthState.COOLDOWN) {
            logger.log("direct promotion skipped: direct route in cooldown")
            return
        }
        if (directRouteHealth.isProbeThrottled()) {
            logger.log("direct promotion skipped: direct health probe throttled until ${directRouteHealth.probeThrottleUntilMs()}")
            return
        }
        val transitionedToWifi = !isWifi(previousNetworkStatus) && isWifi(networkStatus)
        val needsProbe = transitionedToWifi || healthState == DirectHealthState.UNKNOWN || healthState == DirectHealthState.UNHEALTHY
        if (!needsProbe) {
            logger.log("direct promotion skipped: Wi-Fi capability event did not require health probe")
            return
        }
        if (effectiveRouteMode() == NetworkRouteMode.DIRECT_FIRST) return
        directRouteHealth.startPromotionProbe(
            shouldContinue = {
                running.get() &&
                    routeState.configuredRouteMode == NetworkRouteMode.AUTO &&
                    isWifi(currentNetworkStatus) &&
                    effectiveRouteMode() == NetworkRouteMode.CF_FIRST
            },
            onPromote = {
                val before = effectiveRouteMode()
                val result = applyEffectiveRouteMode(
                    NetworkRouteMode.DIRECT_FIRST,
                    "direct health probe success",
                    currentNetworkStatus,
                    source = "direct-health",
                )
                if (result.changed) {
                    directRouteHealth.recordPromotion()
                    logger.log("direct promoted: ${before.configValue} -> ${NetworkRouteMode.DIRECT_FIRST.configValue}")
                }
            },
            throttleMs = DIRECT_PROBE_THROTTLE_MS,
        )
    }

    private fun promoteDirectRouteAfterColdSuccess(reason: String) {
        if (routeState.configuredRouteMode != NetworkRouteMode.AUTO) return
        if (!isWifi(currentNetworkStatus)) return
        val before = effectiveRouteMode()
        if (before == NetworkRouteMode.DIRECT_FIRST) return
        val result = applyEffectiveRouteMode(NetworkRouteMode.DIRECT_FIRST, reason, currentNetworkStatus, source = "direct-recovery")
        if (result.changed) {
            directRouteHealth.recordPromotion()
            logger.log("direct promoted: ${before.configValue} -> ${NetworkRouteMode.DIRECT_FIRST.configValue}")
        }
    }

    private fun maybeDowngradeDirectRouteForClientExperience(now: Long = System.currentTimeMillis()) {
        pruneClientExperienceWindows(now)
        val reason = when {
            recentShortRemoteEofTimes.size >= CLIENT_EXPERIENCE_DIRECT_DOWNGRADE_SHORT_REMOTE_EOF_THRESHOLD ->
                "recentShortRemoteEofSessions >= $CLIENT_EXPERIENCE_DIRECT_DOWNGRADE_SHORT_REMOTE_EOF_THRESHOLD"
            recentPoolStaleTimes.size >= CLIENT_EXPERIENCE_DIRECT_DOWNGRADE_POOL_STALE_THRESHOLD &&
                recentDirectTimeoutTimes.size >= CLIENT_EXPERIENCE_DIRECT_DOWNGRADE_DIRECT_TIMEOUT_THRESHOLD ->
                "recentPoolStale >= $CLIENT_EXPERIENCE_DIRECT_DOWNGRADE_POOL_STALE_THRESHOLD and recentDirectTimeouts >= $CLIENT_EXPERIENCE_DIRECT_DOWNGRADE_DIRECT_TIMEOUT_THRESHOLD"
            recentPoolRefillErrorTimes.size >= CLIENT_EXPERIENCE_DIRECT_DOWNGRADE_POOL_REFILL_ERROR_THRESHOLD ->
                "recentPoolRefillErrors >= $CLIENT_EXPERIENCE_DIRECT_DOWNGRADE_POOL_REFILL_ERROR_THRESHOLD"
            else -> return
        }
        downgradeDirectRouteBecauseClientExperienceDegraded(reason, now)
    }

    private fun downgradeDirectRouteBecauseClientExperienceDegraded(reason: String, now: Long = System.currentTimeMillis()) {
        if (routeState.configuredRouteMode != NetworkRouteMode.AUTO) return
        if (!isWifi(currentNetworkStatus)) return
        if (effectiveRouteMode() != NetworkRouteMode.DIRECT_FIRST) return
        if (directRouteHealth.snapshot().cooldownUntilMs > now) return

        val downgradeReason = "client experience degraded: $reason"
        lastDirectDowngradeTimeMs = now
        lastDirectDowngradeReason = downgradeReason
        directRouteHealth.startCooldown(DIRECT_HEALTH_COOLDOWN_MS, downgradeReason)
        val result = applyEffectiveRouteMode(
            NetworkRouteMode.CF_FIRST,
            downgradeReason,
            currentNetworkStatus,
            source = "client-experience",
        )
        if (result.changed) {
            clientExperienceDirectDowngrades.incrementAndGet()
            lastClientExperienceDirectDowngradeTimeMs.set(now)
            lastClientExperienceDirectDowngradeReason.set(reason)
            webSocketPool.disableAndClear()
            logger.log("direct route downgraded to cf_first because client experience degraded: $reason")
        }
    }

    private fun downgradeDirectRouteBecauseHealthDegraded(reason: String) {
        if (routeState.configuredRouteMode != NetworkRouteMode.AUTO) return
        if (effectiveRouteMode() != NetworkRouteMode.DIRECT_FIRST) return
        if (directRouteHealth.isSettling() && reason.contains("no route available", ignoreCase = true)) {
            logger.log("direct downgrade skipped because no route available was caused by route settling: $reason")
            return
        }
        lastDirectDowngradeTimeMs = System.currentTimeMillis()
        lastDirectDowngradeReason = reason
        directRouteHealth.startCooldown(DIRECT_HEALTH_COOLDOWN_MS, reason)
        val result = applyEffectiveRouteMode(
            NetworkRouteMode.CF_FIRST,
            "direct health degraded: $reason",
            currentNetworkStatus,
            source = "direct-health",
        )
        if (result.changed) {
            webSocketPool.disableAndClear()
            logger.log("direct route downgraded to cf_first because health degraded")
        }
    }

    private fun recordDirectPoolStaleForHealth() {
        val now = System.currentTimeMillis()
        if (now - lastDirectPoolStaleWindowStartMs > DIRECT_HEALTH_DEGRADE_WINDOW_MS) {
            lastDirectPoolStaleWindowStartMs = now
            directPoolStaleInWindow = 0
        }
        directPoolStaleInWindow += 1
        if (directPoolStaleInWindow >= DIRECT_POOL_STALE_DOWNGRADE_THRESHOLD) {
            downgradeDirectRouteBecauseHealthDegraded("poolStale >= $DIRECT_POOL_STALE_DOWNGRADE_THRESHOLD")
        }
    }

    private fun directRouteContextAllowsAttempt(expectedGeneration: Long? = null, allowWifiDirectRecovery: Boolean = false): Boolean {
        if (expectedGeneration != null && routeGeneration.get() != expectedGeneration) return false
        val snapshot = routeState.snapshot()
        if (snapshot.effectiveRouteMode != NetworkRouteMode.DIRECT_FIRST && !allowWifiDirectRecovery) return false
        if (snapshot.configuredRouteMode == NetworkRouteMode.DIRECT_FIRST) return true
        if (!isWifi(currentNetworkStatus)) return false
        if (directRouteHealth.isSettling() && !directColdAllowedDuringSettling() && !allowWifiDirectRecovery) return false
        return snapshot.effectiveRouteMode == NetworkRouteMode.DIRECT_FIRST || allowWifiDirectRecovery
    }

    private fun isWifi(networkStatus: String): Boolean =
        networkStatus.equals("Wi-Fi", ignoreCase = true) || networkStatus.equals("wifi", ignoreCase = true)

    private fun closeClient(client: TcpClientTransport) {
        try {
            client.close()
        } catch (_: Throwable) {
            // Best-effort close.
        }
    }

    private fun joinAcceptThreadBestEffort() {
        val thread = acceptThread ?: return
        if (Thread.currentThread() == thread) return
        try {
            thread.join(STOP_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun joinIdlePoolMaintenanceThreadBestEffort() {
        val thread = idlePoolMaintenanceThread ?: return
        if (Thread.currentThread() == thread) return
        thread.interrupt()
        try {
            thread.join(STOP_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val DEFAULT_WS_PATH = "/apiws"
        private const val CF_INFLIGHT_WAIT_BEFORE_NO_ROUTE_MS: Long = 2_000L
        const val IP_FAIL_COOLDOWN_MS: Long = 60L * 60L * 1000L
        const val CLIENT_EXPERIENCE_RECENT_WINDOW_MS: Long = 60_000L
        const val RECONNECT_BURST_MIN_HANDSHAKES: Long = 4L
        const val VERY_SHORT_SESSION_MS: Long = 2_000L
        const val SHORT_REMOTE_EOF_SESSION_MS: Long = 5_000L
        const val TELEGRAM_DISABLED_MIN_HANDSHAKES: Long = 4L
        const val TELEGRAM_DISABLED_MIN_DISRUPTIVE_ENDS: Int = 3
        const val TELEGRAM_DISABLED_NO_ROUTE_MS: Long = 30_000L
        const val WAKE_BURST_IDLE_THRESHOLD_MS: Long = 30_000L
        const val WAKE_BURST_PREWARM_COOLDOWN_MS: Long = 30_000L
        const val MIN_IDLE_WARM_POOL_PER_DC: Int = 1
        const val IDLE_POOL_MAINTENANCE_IDLE_THRESHOLD_MS: Long = 30_000L
        const val IDLE_POOL_MAINTENANCE_COOLDOWN_MS: Long = 30_000L
        private const val IDLE_POOL_MAINTENANCE_POLL_MS: Long = 250L
        const val MOBILE_DIRECT_RESCUE_FAILURE_COOLDOWN_MS: Long = 45_000L
        const val MOBILE_DIRECT_RESCUE_SUCCESS_REUSE_MS: Long = 30_000L
        private const val INVALID_MTPROTO_HANDSHAKE_PREFIX = "Invalid MTProto handshake"
        const val DEFAULT_MOBILE_DIRECT_FALLBACK_TIMEOUT_MS = 2_000
        private const val STOP_JOIN_TIMEOUT_MS = 1_000L
        private const val STALE_POOL_MAX_DURATION_MS = 2_000L
        private const val ROUTE_SETTLING_WINDOW_MS = 3_000L
        private const val NETWORK_SETTLING_WINDOW_MS = 1_500L
        private const val NETWORK_SETTLING_CLIENT_WAIT_MAX_MS = 1_500L
        private const val DIRECT_HEALTH_COOLDOWN_MS = 45_000L
        private const val DIRECT_PROBE_THROTTLE_MS = 30_000L
        private const val WIFI_DIRECT_RECOVERY_RECENT_SUCCESS_MS = 30_000L
        private const val WIFI_DIRECT_RECOVERY_WINDOW_MS = 5_000L
        private const val WIFI_CF_FIRST_RECOVERY_MIN_DOWNGRADE_AGE_MS = 0L
        private const val EMERGENCY_DIRECT_FALLBACK_FAILURE_COOLDOWN_MS = 10_000L
        private const val DIRECT_HEALTH_DEGRADE_WINDOW_MS = 10_000L
        private const val DIRECT_POOL_STALE_DOWNGRADE_THRESHOLD = 3L
        private const val CLIENT_EXPERIENCE_DIRECT_DOWNGRADE_SHORT_REMOTE_EOF_THRESHOLD = 2
        private const val CLIENT_EXPERIENCE_DIRECT_DOWNGRADE_POOL_STALE_THRESHOLD = 1
        private const val CLIENT_EXPERIENCE_DIRECT_DOWNGRADE_DIRECT_TIMEOUT_THRESHOLD = 1
        private const val CLIENT_EXPERIENCE_DIRECT_DOWNGRADE_POOL_REFILL_ERROR_THRESHOLD = 2

        private fun isDirectPoolEnabledFor(mode: NetworkRouteMode): Boolean =
            mode == NetworkRouteMode.DIRECT_FIRST || mode == NetworkRouteMode.AUTO

        fun protoIntForProtoTag(protoTag: ByteArray): Int = when {
            protoTag.contentEquals(RelayInit.PROTO_TAG_ABRIDGED) -> MsgSplitter.PROTO_ABRIDGED_INT
            protoTag.contentEquals(RelayInit.PROTO_TAG_INTERMEDIATE) -> MsgSplitter.PROTO_INTERMEDIATE_INT
            protoTag.contentEquals(RelayInit.PROTO_TAG_SECURE) -> MsgSplitter.PROTO_PADDED_INTERMEDIATE_INT
            else -> throw IllegalArgumentException("Unknown MTProto protocol tag")
        }

        private fun protoTagLabel(protoTag: ByteArray): String = when {
            protoTag.contentEquals(RelayInit.PROTO_TAG_ABRIDGED) -> "abridged"
            protoTag.contentEquals(RelayInit.PROTO_TAG_INTERMEDIATE) -> "intermediate"
            protoTag.contentEquals(RelayInit.PROTO_TAG_SECURE) -> "secure"
            else -> "unknown"
        }

        private fun failureDetail(error: Throwable): String =
            "${error::class.java.simpleName}: ${error.message ?: "no message"}"

        private fun isTimeout(error: Throwable): Boolean =
            error::class.java.simpleName.contains("SocketTimeoutException") ||
                error.message.orEmpty().contains("timeout", ignoreCase = true)

        private fun websocketFailureDetail(error: Throwable): String {
            val base = failureDetail(error)
            if (error is RawWebSocket.WsHandshakeException) {
                val location = error.location?.let { " location=$it" }.orEmpty()
                return "$base status=${error.statusCode}$location"
            }
            return base
        }
    }
}

class JavaTcpServerTransport : TcpServerTransport {
    private var serverSocket: ServerSocket? = null

    override fun bind(host: String, port: Int) {
        serverSocket = ServerSocket().also { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(host, port))
        }
    }

    override fun accept(): TcpClientTransport? {
        val socket = serverSocket?.accept() ?: return null
        return JavaTcpClientTransport(socket)
    }

    override fun close() {
        serverSocket?.close()
        serverSocket = null
    }
}

class JavaTcpClientTransport(private val socket: Socket) : TcpClientTransport {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override val remoteLabel: String
        get() = socket.remoteSocketAddress?.toString() ?: "unknown"

    override fun readExact(byteCount: Int): ByteArray? {
        require(byteCount >= 0) { "byteCount must be non-negative" }
        val data = ByteArray(byteCount)
        var offset = 0
        while (offset < byteCount) {
            val read = input.read(data, offset, byteCount - offset)
            if (read < 0) return null
            offset += read
        }
        return data
    }

    override fun read(bufferSize: Int): ByteArray? {
        require(bufferSize > 0) { "bufferSize must be positive" }
        val buffer = ByteArray(bufferSize)
        val count = try {
            input.read(buffer)
        } catch (error: EOFException) {
            -1
        }
        if (count < 0) return null
        return buffer.copyOf(count)
    }

    override fun write(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    override fun close() = socket.close()
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must contain an even number of characters" }
    return ByteArray(length / 2) { index ->
        val high = Character.digit(this[index * 2], 16)
        val low = Character.digit(this[index * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "hex string contains a non-hex character" }
        ((high shl 4) or low).toByte()
    }
}


private data class MobileDirectRescueDecision(
    val allowed: Boolean,
    val reason: String,
    val logMessage: String? = null,
)

private data class MobileDirectRescueRouteResult(
    val attempted: Boolean,
    val routed: Boolean,
)

private class MobileDirectRescueState {
    var inFlight: Boolean = false
    var cooldownUntilMs: Long = 0L
    var lastError: String? = null
    var lastSuccessTimeMs: Long = 0L
    var generation: Long = 0L
}
