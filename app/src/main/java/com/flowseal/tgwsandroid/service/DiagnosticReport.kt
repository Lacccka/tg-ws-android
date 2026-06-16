package com.flowseal.tgwsandroid.service

import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ProcessExitReasonDiagnostics(
    val available: Boolean,
    val errorClass: String? = null,
    val errorMessage: String? = null,
    val entries: List<ProcessExitReasonEntry> = emptyList(),
)

data class ProcessExitReasonEntry(
    val timestamp: Long,
    val reasonCode: Int,
    val reasonLabel: String,
    val status: Int,
    val importance: Int,
    val pss: Long,
    val rss: Long,
    val description: String?,
    val processName: String?,
    val pid: Int,
    val traceInputStreamPresent: Boolean,
)

data class TrimMemoryDiagnostics(
    val lastTrimMemoryLevel: Int? = null,
    val lastTrimMemoryTimeMs: Long? = null,
    val trimMemoryCountByLevel: Map<Int, Long> = emptyMap(),
    val lastLowMemoryTimeMs: Long? = null,
)

data class DiagnosticSnapshot(
    val generated: LocalDateTime,
    val reportGeneratedTimeMs: Long,
    val applicationId: String = "unknown",
    val versionName: String = "unknown",
    val versionCode: Long = 0,
    val buildType: String = "unknown",
    val flavor: String = "unknown",
    val debuggable: Boolean = false,
    val gitCommitSha: String = "unknown",
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
    val declaredForegroundServiceStrategy: String = "unknown",
    val runtimeForegroundServiceType: String = "unknown",
    val foregroundServiceType: String = "unknown",
    val targetSdk: Int = 0,
    val serviceStartTime: String? = null,
    val foregroundStartTime: String? = null,
    val lastStopReason: String? = null,
    val lastForegroundTimeoutTimeMs: Long? = null,
    val lastForegroundTimeoutReason: String? = null,
    val androidSdkInt: Int = 0,
    val androidRelease: String = "unknown",
    val manufacturer: String = "unknown",
    val model: String = "unknown",
    val notificationPermissionStatus: String = "unknown",
    val normalizedNetworkType: String = "unknown",
    val activeNetworkMetered: String = "unknown",
    val networkCapabilitySummary: String = "unknown",
    val networkGeneration: Long? = null,
    val lastNetworkAvailableAtMs: Long? = null,
    val lastNetworkLostAtMs: Long? = null,
    val serviceUptimeMs: Long? = null,
    val proxyUptimeMs: Long? = null,
    val oldestLogTimeMs: Long? = null,
    val newestLogTimeMs: Long? = null,
    val logCoverageDurationMs: Long? = null,
    val currentLogEntryCount: Int = 0,
    val currentLogApproxChars: Int = 0,
    val maxLogLines: Int = RuntimeLogStore.DEFAULT_MAX_LINES,
    val maxLogChars: Int = RuntimeLogStore.DEFAULT_MAX_CHARS,
    val totalLogEntriesAccepted: Long = 0,
    val totalLogEntriesDroppedDueToLimit: Long = 0,
    val restoredLogEntries: Long = 0,
    val lastWatchdogHeartbeat: String? = null,
    val wakeLockHeld: Boolean = false,
    val previousRun: PreviousRunCheck? = null,
    val currentProcessStartTime: String? = null,
    val currentProcessStartReason: String? = null,
    val historicalExitReasons: ProcessExitReasonDiagnostics = ProcessExitReasonDiagnostics(available = false),
    val trimMemory: TrimMemoryDiagnostics = TrimMemoryDiagnostics(),
    val previousEffectiveRouteMode: String? = null,
    val lastRouteChangeReason: String = "unknown",
    val lastRouteChangeTimeMs: Long? = null,
    val networkAtLastRouteChange: String = "unknown",
    val lastRouteEvaluationReason: String = "unknown",
    val lastRouteEvaluationTimeMs: Long? = null,
    val networkAtLastRouteEvaluation: String = "unknown",
    val statsSnapshotTimeMs: Long? = null,
    val runtimeLogTailUntilMs: Long? = null,
    val lastEffectiveRouteModeUpdateTimeMs: Long? = null,
    val lastRouteUsedUpdateTimeMs: Long? = null,
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
        foregroundServiceType: String = "unknown",
        declaredForegroundServiceStrategy: String = foregroundServiceType,
        runtimeForegroundServiceType: String = declaredForegroundServiceStrategy,
        targetSdk: Int = 0,
        serviceStartTime: String? = null,
        foregroundStartTime: String? = null,
        lastWatchdogHeartbeat: String? = null,
        wakeLockHeld: Boolean = false,
        previousRun: PreviousRunCheck? = null,
        currentProcessStartTime: String? = null,
        currentProcessStartReason: String? = null,
        historicalExitReasons: ProcessExitReasonDiagnostics = ProcessExitReasonDiagnostics(available = false),
        trimMemory: TrimMemoryDiagnostics = TrimMemoryDiagnostics(),
        previousEffectiveRouteMode: String? = null,
        lastRouteChangeReason: String = "unknown",
        lastRouteChangeTimeMs: Long? = null,
        networkAtLastRouteChange: String = "unknown",
        lastRouteEvaluationReason: String = "",
        lastRouteEvaluationTimeMs: Long? = null,
        networkAtLastRouteEvaluation: String = "",
        stats: ProxyServerStats? = null,
        statsSnapshotTimeMs: Long? = stats?.statsSnapshotTimeMs?.takeIf { it > 0L },
        runtimeLogTailUntilMs: Long? = null,
        lastEffectiveRouteModeUpdateTimeMs: Long? = stats?.lastEffectiveRouteModeUpdateTimeMs,
        lastRouteUsedUpdateTimeMs: Long? = stats?.lastRouteUsedUpdateTimeMs,
        logs: List<RuntimeLogEntry>,
        clock: Clock = Clock.systemDefaultZone(),
        reportGeneratedTimeMs: Long = clock.millis(),
        applicationId: String = "unknown",
        versionName: String = "unknown",
        versionCode: Long = 0,
        buildType: String = "unknown",
        flavor: String = "unknown",
        debuggable: Boolean = false,
        gitCommitSha: String = "unknown",
        lastStopReason: String? = null,
        lastForegroundTimeoutTimeMs: Long? = null,
        lastForegroundTimeoutReason: String? = null,
        androidSdkInt: Int = 0,
        androidRelease: String = "unknown",
        manufacturer: String = "unknown",
        model: String = "unknown",
        notificationPermissionStatus: String = "unknown",
        normalizedNetworkType: String = network,
        activeNetworkMetered: String = "unknown",
        networkCapabilitySummary: String = "unknown",
        networkGeneration: Long? = stats?.networkGeneration,
        lastNetworkAvailableAtMs: Long? = stats?.lastNetworkAvailableAtMs?.takeIf { it > 0L },
        lastNetworkLostAtMs: Long? = stats?.lastNetworkLostAtMs?.takeIf { it > 0L },
        serviceUptimeMs: Long? = null,
        proxyUptimeMs: Long? = null,
        logMetadata: RuntimeLogMetadata? = null,
        oldestLogTimeMs: Long? = logMetadata?.oldestLogTimeMs ?: logs.firstOrNull()?.timestamp?.atZone(clock.zone)?.toInstant()?.toEpochMilli(),
        newestLogTimeMs: Long? = logMetadata?.newestLogTimeMs ?: logs.lastOrNull()?.timestamp?.atZone(clock.zone)?.toInstant()?.toEpochMilli(),
        logCoverageDurationMs: Long? = logMetadata?.logCoverageDurationMs ?: if (logs.size >= 2) {
            val oldest = logs.first().timestamp.atZone(clock.zone).toInstant().toEpochMilli()
            val newest = logs.last().timestamp.atZone(clock.zone).toInstant().toEpochMilli()
            (newest - oldest).coerceAtLeast(0L)
        } else {
            null
        },
        currentLogEntryCount: Int = logMetadata?.currentLogEntryCount ?: logs.size,
        currentLogApproxChars: Int = logMetadata?.currentLogApproxChars ?: logs.sumOf { it.formatLine().length },
        maxLogLines: Int = logMetadata?.maxLogLines ?: RuntimeLogStore.DEFAULT_MAX_LINES,
        maxLogChars: Int = logMetadata?.maxLogChars ?: RuntimeLogStore.DEFAULT_MAX_CHARS,
        totalLogEntriesAccepted: Long = logMetadata?.totalLogEntriesAccepted ?: logs.size.toLong(),
        totalLogEntriesDroppedDueToLimit: Long = logMetadata?.totalLogEntriesDroppedDueToLimit ?: 0L,
        restoredLogEntries: Long = logMetadata?.restoredLogEntries ?: 0L,
    ): DiagnosticSnapshot = DiagnosticSnapshot(
        generated = LocalDateTime.now(clock),
        reportGeneratedTimeMs = reportGeneratedTimeMs,
        applicationId = applicationId.ifBlank { "unknown" },
        versionName = versionName.ifBlank { "unknown" },
        versionCode = versionCode,
        buildType = buildType.ifBlank { "unknown" },
        flavor = flavor.ifBlank { "unknown" },
        debuggable = debuggable,
        gitCommitSha = gitCommitSha.ifBlank { "unknown" },
        status = status.ifBlank { "unknown" },
        endpoint = endpoint.ifBlank { "unknown" },
        secret = maskSecret(secret.ifBlank { "unknown" }),
        secretSource = secretSource.ifBlank { "unknown" },
        proxyLinkCurrent = proxyLinkCurrent,
        dcSummary = dcSummary.ifBlank { "unknown" },
        batteryOptimization = batteryOptimization.ifBlank { "unknown" },
        network = network.ifBlank { "unknown" },
        routeMode = routeMode.ifBlank { "unknown" },
        effectiveRouteMode = effectiveRouteMode.ifBlank { "unknown" },
        declaredForegroundServiceStrategy = declaredForegroundServiceStrategy.ifBlank { "unknown" },
        runtimeForegroundServiceType = runtimeForegroundServiceType.ifBlank { "unknown" },
        foregroundServiceType = foregroundServiceType.ifBlank { runtimeForegroundServiceType.ifBlank { "unknown" } },
        targetSdk = targetSdk,
        serviceStartTime = serviceStartTime,
        foregroundStartTime = foregroundStartTime,
        lastStopReason = lastStopReason,
        lastForegroundTimeoutTimeMs = lastForegroundTimeoutTimeMs,
        lastForegroundTimeoutReason = lastForegroundTimeoutReason,
        androidSdkInt = androidSdkInt,
        androidRelease = androidRelease.ifBlank { "unknown" },
        manufacturer = manufacturer.ifBlank { "unknown" },
        model = model.ifBlank { "unknown" },
        notificationPermissionStatus = notificationPermissionStatus.ifBlank { "unknown" },
        normalizedNetworkType = normalizedNetworkType.ifBlank { "unknown" },
        activeNetworkMetered = activeNetworkMetered.ifBlank { "unknown" },
        networkCapabilitySummary = networkCapabilitySummary.ifBlank { "unknown" },
        networkGeneration = networkGeneration,
        lastNetworkAvailableAtMs = lastNetworkAvailableAtMs,
        lastNetworkLostAtMs = lastNetworkLostAtMs,
        serviceUptimeMs = serviceUptimeMs,
        proxyUptimeMs = proxyUptimeMs,
        oldestLogTimeMs = oldestLogTimeMs,
        newestLogTimeMs = newestLogTimeMs,
        logCoverageDurationMs = logCoverageDurationMs,
        currentLogEntryCount = currentLogEntryCount,
        currentLogApproxChars = currentLogApproxChars,
        maxLogLines = maxLogLines,
        maxLogChars = maxLogChars,
        totalLogEntriesAccepted = totalLogEntriesAccepted,
        totalLogEntriesDroppedDueToLimit = totalLogEntriesDroppedDueToLimit,
        restoredLogEntries = restoredLogEntries,
        lastWatchdogHeartbeat = lastWatchdogHeartbeat,
        wakeLockHeld = wakeLockHeld,
        previousRun = previousRun,
        currentProcessStartTime = currentProcessStartTime,
        currentProcessStartReason = currentProcessStartReason,
        historicalExitReasons = historicalExitReasons,
        trimMemory = trimMemory,
        previousEffectiveRouteMode = previousEffectiveRouteMode,
        lastRouteChangeReason = lastRouteChangeReason.ifBlank { "unknown" },
        lastRouteChangeTimeMs = lastRouteChangeTimeMs,
        networkAtLastRouteChange = networkAtLastRouteChange.ifBlank { "unknown" },
        lastRouteEvaluationReason = lastRouteEvaluationReason.ifBlank { stats?.lastRouteEvaluationReason ?: "unknown" },
        lastRouteEvaluationTimeMs = lastRouteEvaluationTimeMs ?: stats?.lastRouteEvaluationTimeMs,
        networkAtLastRouteEvaluation = networkAtLastRouteEvaluation.ifBlank { stats?.networkAtLastRouteEvaluation ?: "unknown" },
        statsSnapshotTimeMs = statsSnapshotTimeMs,
        runtimeLogTailUntilMs = runtimeLogTailUntilMs,
        lastEffectiveRouteModeUpdateTimeMs = lastEffectiveRouteModeUpdateTimeMs,
        lastRouteUsedUpdateTimeMs = lastRouteUsedUpdateTimeMs,
        stats = stats,
        logs = logs,
    )

    fun format(snapshot: DiagnosticSnapshot): String = buildString {
        appendLine("TG WS Android diagnostics")
        appendLine("Generated: ${snapshot.generated.format(GENERATED_FORMATTER)}")
        appendLine("Build:")
        appendLine("  applicationId: ${snapshot.applicationId}")
        appendLine("  versionName: ${snapshot.versionName}")
        appendLine("  versionCode: ${snapshot.versionCode.takeIf { it > 0 }?.toString() ?: "unknown"}")
        appendLine("  buildType: ${snapshot.buildType}")
        appendLine("  flavor/variant: ${snapshot.flavor}")
        appendLine("  debuggable: ${snapshot.debuggable}")
        appendLine("  gitCommitSha: ${snapshot.gitCommitSha}")
        appendLine("Foreground service:")
        appendLine("  declaredForegroundServiceStrategy: ${snapshot.declaredForegroundServiceStrategy}")
        appendLine("  runtimeForegroundServiceStrategy: ${snapshot.runtimeForegroundServiceType}")
        appendLine("  foregroundServiceTypeLabel: ${snapshot.foregroundServiceType}")
        appendLine("  lastStopReason: ${snapshot.lastStopReason ?: snapshot.previousRun?.lastStopReason ?: "unknown"}")
        appendLine("  lastForegroundTimeoutTimeMs: ${snapshot.lastForegroundTimeoutTimeMs?.toString() ?: "unknown"}")
        appendLine("  lastForegroundTimeoutReason: ${snapshot.lastForegroundTimeoutReason ?: "unknown"}")
        appendLine("Device:")
        appendLine("  androidSdkInt: ${snapshot.androidSdkInt.takeIf { it > 0 }?.toString() ?: "unknown"}")
        appendLine("  androidRelease: ${snapshot.androidRelease}")
        appendLine("  manufacturer: ${snapshot.manufacturer}")
        appendLine("  model: ${snapshot.model}")
        appendLine("  batteryOptimizationStatus: ${snapshot.batteryOptimization}")
        appendLine("  notificationPermissionStatus: ${snapshot.notificationPermissionStatus}")
        appendLine("Network:")
        appendLine("  normalizedNetworkType: ${snapshot.normalizedNetworkType}")
        appendLine("  activeNetworkMetered: ${snapshot.activeNetworkMetered}")
        appendLine("  networkCapabilitySummary: ${snapshot.networkCapabilitySummary}")
        appendLine("  networkGeneration: ${snapshot.networkGeneration?.toString() ?: "unknown"}")
        appendLine("  lastNetworkAvailableAtMs: ${snapshot.lastNetworkAvailableAtMs?.toString() ?: "unknown"}")
        appendLine("  lastNetworkLostAtMs: ${snapshot.lastNetworkLostAtMs?.toString() ?: "unknown"}")
        appendLine("Timing:")
        appendLine("  reportGeneratedTimeMs: ${snapshot.reportGeneratedTimeMs}")
        appendLine("  serviceUptimeMs: ${snapshot.serviceUptimeMs?.toString() ?: "unknown"}")
        appendLine("  proxyUptimeMs: ${snapshot.proxyUptimeMs?.toString() ?: "unknown"}")
        appendLine("  oldestLogTimeMs: ${snapshot.oldestLogTimeMs?.toString() ?: "unknown"}")
        appendLine("  newestLogTimeMs: ${snapshot.newestLogTimeMs?.toString() ?: "unknown"}")
        appendLine("  logCoverageDurationMs: ${snapshot.logCoverageDurationMs?.toString() ?: "unknown"}")
        appendLine("Log coverage:")
        appendLine("  oldestLogTimeMs: ${snapshot.oldestLogTimeMs?.toString() ?: "unknown"}")
        appendLine("  newestLogTimeMs: ${snapshot.newestLogTimeMs?.toString() ?: "unknown"}")
        appendLine("  logCoverageDurationMs: ${snapshot.logCoverageDurationMs?.toString() ?: "unknown"}")
        appendLine("  currentLogEntryCount: ${snapshot.currentLogEntryCount}")
        appendLine("  currentLogApproxChars: ${snapshot.currentLogApproxChars}")
        appendLine("  maxLogLines: ${snapshot.maxLogLines}")
        appendLine("  maxLogChars: ${snapshot.maxLogChars}")
        appendLine("  totalLogEntriesAccepted: ${snapshot.totalLogEntriesAccepted}")
        appendLine("  totalLogEntriesDroppedDueToLimit: ${snapshot.totalLogEntriesDroppedDueToLimit}")
        appendLine("  restoredLogEntries: ${snapshot.restoredLogEntries}")
        appendLine("Status: ${snapshot.status}")
        if (snapshot.latestHistoricalExitLooksLikeCleanerKill()) {
            appendLine("Diagnostics warning: Process was killed by device cleaner. Foreground service and WakeLock do not protect from manual/OEM cleaner. Lock app in Recents and add it to cleaner exceptions.")
        }
        appendLine("Endpoint: ${snapshot.endpoint}")
        appendLine("Secret: ${snapshot.secret}")
        appendLine("Secret source: ${snapshot.secretSource}")
        appendLine("Proxy link current: ${snapshot.proxyLinkCurrent}")
        appendLine("DCs: ${snapshot.dcSummary}")
        appendLine("Battery optimization: ${snapshot.batteryOptimization}")
        appendLine("Network: ${snapshot.network}")
        appendLine("Configured route mode: ${snapshot.routeMode}")
        appendLine("Effective route mode: ${snapshot.effectiveRouteMode}")
        appendLine("Declared foreground service strategy: ${snapshot.declaredForegroundServiceStrategy}")
        appendLine("Runtime foreground service type: ${snapshot.runtimeForegroundServiceType}")
        appendLine("Foreground service type from manifest/build strategy: ${snapshot.foregroundServiceType}")
        appendLine("Target SDK: ${snapshot.targetSdk.takeIf { it > 0 }?.toString() ?: "unknown"}")
        appendLine("Service start time: ${snapshot.serviceStartTime ?: "unknown"}")
        appendLine("Foreground start time: ${snapshot.foregroundStartTime ?: "unknown"}")
        appendLine("Last watchdog heartbeat: ${snapshot.lastWatchdogHeartbeat ?: "unknown"}")
        appendLine("WakeLock held: ${snapshot.wakeLockHeld}")
        appendLine("Previous run was unexpected: ${snapshot.previousRun?.wasUnexpected?.toString() ?: "unknown"}")
        appendLine("Previous run last heartbeat: ${snapshot.previousRun?.lastHeartbeatAt ?: "unknown"}")
        appendLine("Previous run last stop reason: ${snapshot.previousRun?.lastStopReason ?: "unknown"}")
        appendLine("Previous run stopped at: ${snapshot.previousRun?.stoppedAt ?: "unknown"}")
        appendLine("Previous run marker: ${formatPreviousRun(snapshot.previousRun)}")
        appendProcessDeathDiagnostics(snapshot)
        val dataSyncWarningApplies = snapshot.declaredForegroundServiceStrategy == "dataSync" && snapshot.targetSdk >= 35
        appendLine("Android 15 dataSync warning applies: $dataSyncWarningApplies")
        if (dataSyncWarningApplies) {
            appendLine("Diagnostics warning: dataSync foreground service on Android 15+ targetSdk 35 is limited to 6 hours per 24 hours; specialUse may be evaluated for debug/sideload builds with android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE.")
        }
        appendLine("Previous effective route mode: ${snapshot.previousEffectiveRouteMode ?: "none"}")
        appendLine("Last route change reason: ${snapshot.lastRouteChangeReason}")
        appendLine("Last route change source: ${snapshot.stats?.lastRouteChangeSource ?: "unknown"}")
        appendLine("Last route change time: ${snapshot.lastRouteChangeTimeMs?.toString() ?: "unknown"}")
        appendLine("Network at last route change: ${snapshot.networkAtLastRouteChange}")
        appendLine("Last route evaluation reason: ${snapshot.lastRouteEvaluationReason}")
        appendLine("Last route evaluation source: ${snapshot.stats?.lastRouteEvaluationSource ?: "unknown"}")
        appendLine("Last route evaluation time: ${snapshot.lastRouteEvaluationTimeMs?.toString() ?: "unknown"}")
        appendLine("Network at last route evaluation: ${snapshot.networkAtLastRouteEvaluation}")
        appendLine("Route evaluations: ${snapshot.stats?.routeEvaluations?.toString() ?: "unknown"}")
        appendLine("Route noop evaluations: ${snapshot.stats?.routeNoopEvaluations?.toString() ?: "unknown"}")
        appendLine("Stats snapshot time ms: ${snapshot.statsSnapshotTimeMs?.toString() ?: "unknown"}")
        appendLine("Runtime log tail until ms: ${snapshot.runtimeLogTailUntilMs?.toString() ?: "unknown"}")
        appendLine("Last effective route mode update time ms: ${snapshot.lastEffectiveRouteModeUpdateTimeMs?.toString() ?: "unknown"}")
        appendLine("Last route used update time ms: ${snapshot.lastRouteUsedUpdateTimeMs?.toString() ?: "unknown"}")
        appendClientExperienceDiagnostics(snapshot.stats)
        appendDirectPoolReadiness(snapshot.stats)
        appendLine("Stats: ${formatStats(snapshot.stats)}")
        appendCfHealth(snapshot.stats)
        appendLine("---")
        snapshot.logs.forEach { appendLine(it.formatLine()) }
    }.trimEnd()

    private fun formatPreviousRun(previous: PreviousRunCheck?): String = if (previous == null) {
        "unknown"
    } else {
        "hadMarker=${previous.hadMarker}, wasRunning=${previous.wasRunning}, wasUnexpected=${previous.wasUnexpected}, " +
            "runId=${previous.runId ?: "unknown"}, startedAt=${previous.startedAt ?: "unknown"}, " +
            "lastHeartbeatAt=${previous.lastHeartbeatAt ?: "unknown"}, lastServiceEvent=${previous.lastServiceEvent ?: "unknown"}, " +
            "lastServiceEventAt=${previous.lastServiceEventAt ?: "unknown"}, lastForegroundStartedAt=${previous.lastForegroundStartedAt ?: "unknown"}, " +
            "lastStopReason=${previous.lastStopReason ?: "unknown"}, stoppedAt=${previous.stoppedAt ?: "unknown"}, " +
            "wakeLock=${previous.hadWakeLockAtLastMarker?.toString() ?: "unknown"}, foreground=${previous.wasForegroundAtLastMarker?.toString() ?: "unknown"}, " +
            "network=${previous.networkAtLastMarker ?: "unknown"}, route=${previous.routeAtLastMarker ?: "unknown"}"
    }

    private fun String?.toEpochMsOrNull(): Long? = runCatching {
        this?.let { Instant.parse(it).toEpochMilli() }
    }.getOrNull()

    private fun StringBuilder.appendProcessDeathDiagnostics(snapshot: DiagnosticSnapshot) {
        val previous = snapshot.previousRun
        val heartbeatMs = previous?.lastHeartbeatAt.toEpochMsOrNull()
        val stoppedMs = previous?.stoppedAt.toEpochMsOrNull()
        val likelyDiedAtMs = stoppedMs ?: heartbeatMs
        val diedAfterHeartbeatMs = if (previous?.wasUnexpected == true && heartbeatMs != null) {
            (stoppedMs ?: snapshot.reportGeneratedTimeMs) - heartbeatMs
        } else {
            null
        }?.coerceAtLeast(0L)
        appendLine("Process death diagnostics:")
        appendLine("  currentProcessStartTime: ${snapshot.currentProcessStartTime ?: "unknown"}")
        appendLine("  currentProcessStartReason: ${snapshot.currentProcessStartReason ?: "unknown"}")
        appendLine("  previousRunWasUnexpected: ${previous?.wasUnexpected?.toString() ?: "unknown"}")
        appendLine("  previousRunId: ${previous?.runId ?: "unknown"}")
        appendLine("  previousRunStartedAt: ${previous?.startedAt ?: "unknown"}")
        appendLine("  previousRunLastHeartbeatAt: ${previous?.lastHeartbeatAt ?: "unknown"}")
        appendLine("  previousRunLastServiceEvent: ${previous?.lastServiceEvent ?: "unknown"}")
        appendLine("  previousRunLastServiceEventAt: ${previous?.lastServiceEventAt ?: "unknown"}")
        appendLine("  previousRunLastForegroundStartedAt: ${previous?.lastForegroundStartedAt ?: "unknown"}")
        appendLine("  previousRunLastStopReason: ${previous?.lastStopReason ?: "unknown"}")
        appendLine("  previousRunStoppedAt: ${previous?.stoppedAt ?: "unknown"}")
        appendLine("  previousRunDiedAfterLastHeartbeatMs: ${diedAfterHeartbeatMs?.toString() ?: "unknown"}")
        appendLine("  previousRunDiedAfterLastHeartbeatHumanReadable: ${diedAfterHeartbeatMs?.let(::formatDurationMs) ?: "unknown"}")
        appendLine("  previousRunLikelyDiedAtApprox: ${likelyDiedAtMs?.let { Instant.ofEpochMilli(it).toString() } ?: "unknown"}")
        appendLine("  previousRunHadWakeLockAtLastMarker: ${previous?.hadWakeLockAtLastMarker?.toString() ?: "unknown"}")
        appendLine("  previousRunWasForegroundAtLastMarker: ${previous?.wasForegroundAtLastMarker?.toString() ?: "unknown"}")
        appendLine("  previousRunNetworkAtLastMarker: ${previous?.networkAtLastMarker ?: "unknown"}")
        appendLine("  previousRunRouteAtLastMarker: ${previous?.routeAtLastMarker ?: "unknown"}")
        appendLine("  previousRunLastCrashClass: ${previous?.lastCrashClass ?: "unknown"}")
        appendLine("  previousRunLastCrashMessage: ${maskSecret(previous?.lastCrashMessage ?: "unknown")}")
        appendLine("  previousRunLastCrashTopFrame: ${previous?.lastCrashTopFrame ?: "unknown"}")
        val cleanerEntry = snapshot.historicalExitReasons.entries.firstOrNull { it.looksLikeCleanerKill() }
        appendLine("  previousRunLikelyKilledByCleaner: ${cleanerEntry != null}")
        appendLine("  previousRunCleanerDescription: ${cleanerEntry?.description ?: "none"}")
        appendLine("  previousRunCleanerRecommendation: ${if (cleanerEntry != null) CLEANER_RECOMMENDATION else "none"}")
        val sigkillEntry = snapshot.historicalExitReasons.entries.firstOrNull { it.looksLikeSigkill() }
        appendLine("  previousRunLikelyKilledBySigkill: ${sigkillEntry != null}")
        appendLine("  previousRunSigkillRecommendation: ${if (sigkillEntry != null) SIGKILL_RECOMMENDATION else "none"}")
        appendLine("  lastTrimMemoryLevel: ${snapshot.trimMemory.lastTrimMemoryLevel?.toString() ?: "unknown"}")
        appendLine("  lastTrimMemoryTimeMs: ${snapshot.trimMemory.lastTrimMemoryTimeMs?.toString() ?: "unknown"}")
        appendLine("  trimMemoryCountByLevel: ${snapshot.trimMemory.trimMemoryCountByLevel.toSortedMap()}")
        appendLine("  lastLowMemoryTimeMs: ${snapshot.trimMemory.lastLowMemoryTimeMs?.toString() ?: "unknown"}")
        appendLine("  historicalExitReasonsAvailable: ${snapshot.historicalExitReasons.available}")
        appendLine("  historicalExitReasonsErrorClass: ${snapshot.historicalExitReasons.errorClass ?: "none"}")
        appendLine("  historicalExitReasonsErrorMessage: ${snapshot.historicalExitReasons.errorMessage ?: "none"}")
        snapshot.historicalExitReasons.entries.forEachIndexed { index, entry ->
            appendLine("  historicalExitReason[$index]: timestamp=${entry.timestamp}, reasonCode=${entry.reasonCode}, reason=${entry.reasonLabel}, status=${entry.status}, importance=${entry.importance}, pss=${entry.pss}, rss=${entry.rss}, description=${entry.description ?: "none"}, processName=${entry.processName ?: "unknown"}, pid=${entry.pid}, traceInputStreamPresent=${entry.traceInputStreamPresent}")
        }
    }


    private fun DiagnosticSnapshot.latestHistoricalExitLooksLikeCleanerKill(): Boolean =
        historicalExitReasons.entries.firstOrNull()?.looksLikeCleanerKill() == true

    private fun ProcessExitReasonEntry.looksLikeCleanerKill(): Boolean =
        reasonCode == 13 && description?.containsCleanerMarker() == true

    private fun ProcessExitReasonEntry.looksLikeSigkill(): Boolean =
        reasonCode == 2 && status == 9

    private fun String.containsCleanerMarker(): Boolean {
        val normalized = lowercase(Locale.US)
        return listOf("garbageclean", "cleaner", "clean", "security", "boost", "memory clean").any(normalized::contains)
    }

    private const val CLEANER_RECOMMENDATION = "Lock app in recents / add to Cleaner exceptions / keep battery unrestricted / enable autostart"
    private const val SIGKILL_RECOMMENDATION = "check OEM battery/cleaner/task-killer restrictions"

    private fun formatDurationMs(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return when {
            hours > 0 -> "${hours}h ${minutes % 60}m ${seconds % 60}s"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }

    private fun maskSecret(secret: String): String {
        if (secret == "unknown" || secret.contains("...")) return secret
        if (secret.length <= 8) return "***"
        return "${secret.take(4)}...${secret.takeLast(4)}"
    }

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
            "statsSnapshotTimeMs=${stats.statsSnapshotTimeMs}, " +
            "lastEffectiveRouteModeUpdateTimeMs=${stats.lastEffectiveRouteModeUpdateTimeMs ?: "unknown"}, " +
            "lastRouteUsedUpdateTimeMs=${stats.lastRouteUsedUpdateTimeMs ?: "unknown"}, " +
            "secondsSinceLastAcceptedHandshake=${stats.secondsSinceLastAcceptedHandshake?.toString() ?: "unknown"}, " +
            "secondsSinceLastSuccessfulRoute=${stats.secondsSinceLastSuccessfulRoute?.toString() ?: "unknown"}, " +
            "clientExperienceLikelyReconnectBurst=${stats.clientExperience.likelyReconnectBurst}, " +
            "clientExperienceLikelyTelegramDisabledProxy=${stats.clientExperience.likelyTelegramDisabledProxy}, " +
            "clientExperienceRecentAcceptedHandshakes=${stats.clientExperience.recentAcceptedHandshakes}, " +
            "clientExperienceRecentClientClosedSessions=${stats.clientExperience.recentClientClosedSessions}, " +
            "clientExperienceRecentVeryShortClientClosedSessions=${stats.clientExperience.recentVeryShortClientClosedSessions}, " +
            "clientExperienceRecentShortRemoteEofSessions=${stats.clientExperience.recentShortRemoteEofSessions}, " +
            "clientExperienceRecentConnectionResetSessions=${stats.clientExperience.recentConnectionResetSessions}, " +
            "clientExperienceRecentDirectTimeouts=${stats.clientExperience.recentDirectTimeouts}, " +
            "clientExperienceRecentPoolMisses=${stats.clientExperience.recentPoolMisses}, " +
            "clientExperienceRecentPoolRefillErrors=${stats.clientExperience.recentPoolRefillErrors}, " +
            "clientExperienceRecentPoolStale=${stats.clientExperience.recentPoolStale}, " +
            "clientExperienceRecentCfQueueControlledFailures=${stats.clientExperience.recentCfQueueControlledFailures}, " +
            "clientExperienceRecentCfConnectQueueTimeouts=${stats.clientExperience.recentCfConnectQueueTimeouts}, " +
            "clientExperienceRecentUnsupportedDcByDc=${formatLongByDc(stats.clientExperience.recentUnsupportedDcByDc)}, " +
            "clientExperienceRecentNoRouteByDc=${formatLongByDc(stats.clientExperience.recentNoRouteByDc)}, " +
            "clientExperienceTimeToFirstSuccessfulRouteAfterIdleMs=${stats.clientExperience.timeToFirstSuccessfulRouteAfterIdleMs ?: "unknown"}, " +
            "wakeBurstPrewarmTriggers=${stats.clientExperience.wakeBurstPrewarmTriggers}, " +
            "wakeBurstPrewarmSkippedNoDirectRedirect=${stats.clientExperience.wakeBurstPrewarmSkippedNoDirectRedirect}, " +
            "wakeBurstPrewarmSkippedCooldown=${stats.clientExperience.wakeBurstPrewarmSkippedCooldown}, " +
            "wakeBurstPrewarmAttempts=${stats.clientExperience.wakeBurstPrewarmAttempts}, " +
            "wakeBurstPrewarmSuccesses=${stats.clientExperience.wakeBurstPrewarmSuccesses}, " +
            "wakeBurstPrewarmFailures=${stats.clientExperience.wakeBurstPrewarmFailures}, " +
            "lastWakeBurstPrewarmTimeMs=${stats.clientExperience.lastWakeBurstPrewarmTimeMs}, " +
            "lastWakeBurstPrewarmDc=${stats.clientExperience.lastWakeBurstPrewarmDc ?: "unknown"}, " +
            "lastWakeBurstPrewarmError=${stats.clientExperience.lastWakeBurstPrewarmError ?: "none"}, " +
            "idlePoolMaintenanceRuns=${stats.clientExperience.idlePoolMaintenanceRuns}, " +
            "idlePoolMaintenanceSkippedNetwork=${stats.clientExperience.idlePoolMaintenanceSkippedNetwork}, " +
            "idlePoolMaintenanceSkippedRoute=${stats.clientExperience.idlePoolMaintenanceSkippedRoute}, " +
            "idlePoolMaintenanceSkippedDirectHealth=${stats.clientExperience.idlePoolMaintenanceSkippedDirectHealth}, " +
            "idlePoolMaintenanceSkippedActiveSessions=${stats.clientExperience.idlePoolMaintenanceSkippedActiveSessions}, " +
            "idlePoolMaintenanceAttempts=${stats.clientExperience.idlePoolMaintenanceAttempts}, " +
            "idlePoolMaintenanceSuccesses=${stats.clientExperience.idlePoolMaintenanceSuccesses}, " +
            "idlePoolMaintenanceFailures=${stats.clientExperience.idlePoolMaintenanceFailures}, " +
            "lastIdlePoolMaintenanceTimeMs=${stats.clientExperience.lastIdlePoolMaintenanceTimeMs}, " +
            "lastIdlePoolMaintenanceError=${stats.clientExperience.lastIdlePoolMaintenanceError ?: "none"}, " +
            "handshakeDiagnosticState=${stats.handshakeDiagnosticState}, " +
            "handshakeDiagnosticReason=${stats.handshakeDiagnosticReason}, " +
            "recentHandshakeDiagnostic=${stats.handshakeDiagnosticReason}, " +
            "badHandshakeRecommendation=${stats.badHandshakeRecommendation}, " +
            "wsErrors=${stats.wsConnectErrors}, cfConnections=${stats.cfProxyConnections}, " +
            "cfErrors=${stats.cfProxyErrors}, bytesUp=${stats.bytesUp}, bytesDown=${stats.bytesDown}, " +
            "sessionTimeouts=${stats.sessionTimeouts}, sessionEof=${stats.sessionEof}, " +
            "sessionRemoteEof=${stats.sessionRemoteEof}, sessionRemoteIdleEof=${stats.sessionRemoteIdleEof}, " +
            "sessionRemoteEofShort=${stats.sessionRemoteEofShort}, lastRemoteEofTimeMs=${stats.lastRemoteEofTimeMs}, " +
            "lastRemoteEofDurationMs=${stats.lastRemoteEofDurationMs}, lastRemoteEofRoute=${stats.lastRemoteEofRoute ?: "none"}, " +
            "lastRemoteEofDc=${stats.lastRemoteEofDc?.toString() ?: "none"}, lastRemoteEofMedia=${stats.lastRemoteEofMedia?.toString() ?: "none"}, " +
            "sessionClientClosed=${stats.sessionClientClosed}, sessionSocketClosed=${stats.sessionSocketClosed}, " +
            "sessionConnectionReset=${stats.sessionEndDiagnostics.connectionReset.count}, lastConnectionResetTimeMs=${stats.sessionEndDiagnostics.connectionReset.lastTimeMs}, " +
            "lastConnectionResetRoute=${stats.sessionEndDiagnostics.connectionReset.lastRoute ?: "none"}, lastConnectionResetDc=${stats.sessionEndDiagnostics.connectionReset.lastDc?.toString() ?: "none"}, " +
            "lastConnectionResetMedia=${stats.sessionEndDiagnostics.connectionReset.lastMedia?.toString() ?: "none"}, " +
            "sessionConnectionTimedOut=${stats.sessionEndDiagnostics.connectionTimedOut.count}, lastConnectionTimedOutTimeMs=${stats.sessionEndDiagnostics.connectionTimedOut.lastTimeMs}, " +
            "lastConnectionTimedOutRoute=${stats.sessionEndDiagnostics.connectionTimedOut.lastRoute ?: "none"}, lastConnectionTimedOutDc=${stats.sessionEndDiagnostics.connectionTimedOut.lastDc?.toString() ?: "none"}, " +
            "lastConnectionTimedOutMedia=${stats.sessionEndDiagnostics.connectionTimedOut.lastMedia?.toString() ?: "none"}, " +
            "sessionUnexpectedErrors=${stats.sessionUnexpectedErrors}, configuredRouteMode=${stats.routeMode}, " +
            "effectiveRouteMode=${stats.effectiveRouteMode}, previousEffectiveRouteMode=${stats.previousEffectiveRouteMode ?: "none"}, " +
            "lastRouteChangeReason=${stats.lastRouteChangeReason}, lastRouteChangeSource=${stats.lastRouteChangeSource}, " +
            "lastRouteChangeTimeMs=${stats.lastRouteChangeTimeMs ?: "unknown"}, " +
            "networkAtLastRouteChange=${stats.networkAtLastRouteChange}, " +
            "lastRouteEvaluationReason=${stats.lastRouteEvaluationReason}, lastRouteEvaluationSource=${stats.lastRouteEvaluationSource}, " +
            "lastRouteEvaluationTimeMs=${stats.lastRouteEvaluationTimeMs ?: "unknown"}, " +
            "networkAtLastRouteEvaluation=${stats.networkAtLastRouteEvaluation}, " +
            "routeEvaluations=${stats.routeEvaluations}, routeNoopEvaluations=${stats.routeNoopEvaluations}, " +
            "lastRouteUsed=${stats.lastRouteUsed ?: "none"}, " +
            "directAttempts=${stats.directAttempts}, directAttemptsSkippedBecauseRoute=${stats.directAttemptsSkippedBecauseRoute}, " +
            "directTimeouts=${stats.directTimeouts}, cfConnections=${stats.cfProxyConnections}, " +
            "cfErrors=${stats.cfProxyErrors}, lastCfDomain=${stats.lastCfDomain ?: "none"}, poolHits=${stats.poolHits}, " +
            "poolMisses=${stats.poolMisses}, poolRefillErrors=${stats.poolRefillErrors}, poolStale=${stats.poolStale}, " +
            "directPoolReadyByKey=${formatCompactMap(stats.poolReadyByKey)}, " +
            "directPoolHitsByKey=${formatCompactMap(stats.poolHitsByKey)}, " +
            "directPoolMissesByKey=${formatCompactMap(stats.poolMissesByKey)}, " +
            "directPoolRefillErrorsByKey=${formatCompactMap(stats.poolRefillErrorsByKey)}, " +
            "directPoolStaleByKey=${formatCompactMap(stats.poolStaleByKey)}, " +
            "poolRefillsCancelled=${stats.poolRefillsCancelled}, " +
            "poolResultsDiscardedAfterRouteChange=${stats.poolResultsDiscardedAfterRouteChange}, " +
            "routeChangesImmediate=${stats.routeChangesImmediate}, networkNoneEvents=${stats.networkNoneEvents}, " +
            "directHealthState=${stats.directHealthState}, directHealthSuccesses=${stats.directHealthSuccesses}, " +
            "directHealthFailures=${stats.directHealthFailures}, directDowngrades=${stats.directDowngrades}, " +
            "directPromotions=${stats.directPromotions}, directCooldownUntil=${stats.directCooldownUntil}, " +
            "routeSettlingUntil=${stats.routeSettlingUntil}, directProbeLastError=${stats.directProbeLastError ?: "none"}, " +
            "directProbeLastSuccessTime=${stats.directProbeLastSuccessTime ?: "none"}, " +
            "mobileDirectRescueAttempts=${stats.mobileDirectRescueAttempts}, " +
            "mobileDirectRescueSuccesses=${stats.mobileDirectRescueSuccesses}, " +
            "mobileDirectRescueFailures=${stats.mobileDirectRescueFailures}, " +
            "mobileDirectRescueSuppressed=${stats.mobileDirectRescueSuppressed}, " +
            "mobileRescueSkippedBecauseNetworkChanged=${stats.mobileRescueSkippedBecauseNetworkChanged}, " +
            "wifiDirectRecoveryAttempts=${stats.wifiDirectRecoveryAttempts}, " +
            "wifiDirectRecoverySuccesses=${stats.wifiDirectRecoverySuccesses}, " +
            "wifiDirectRecoveryFailures=${stats.wifiDirectRecoveryFailures}, " +
            "wifiCfFirstRecoveryAttempts=${stats.recoveryDiagnostics.cfFirst.wifiAttempts}, " +
            "wifiCfFirstRecoverySuccesses=${stats.recoveryDiagnostics.cfFirst.wifiSuccesses}, " +
            "wifiCfFirstRecoveryFailures=${stats.recoveryDiagnostics.cfFirst.wifiFailures}, " +
            "lastWifiCfFirstRecoveryError=${stats.recoveryDiagnostics.cfFirst.lastWifiError ?: "none"}, " +
            "lastWifiCfFirstRecoveryTimeMs=${stats.recoveryDiagnostics.cfFirst.lastWifiTimeMs ?: "unknown"}, " +
            "cfFirstRecoveryAttempts=${stats.recoveryDiagnostics.cfFirst.attempts}, " +
            "cfFirstRecoverySuccesses=${stats.recoveryDiagnostics.cfFirst.successes}, " +
            "cfFirstRecoveryFailures=${stats.recoveryDiagnostics.cfFirst.failures}, " +
            "lastCfFirstRecoveryReason=${stats.recoveryDiagnostics.cfFirst.lastReason ?: "none"}, " +
            "emergencyDirectFallbackAttempts=${stats.recoveryDiagnostics.emergencyDirectFallback.attempts}, " +
            "emergencyDirectFallbackSuccesses=${stats.recoveryDiagnostics.emergencyDirectFallback.successes}, " +
            "emergencyDirectFallbackFailures=${stats.recoveryDiagnostics.emergencyDirectFallback.failures}, " +
            "emergencyDirectFallbackSuppressed=${stats.recoveryDiagnostics.emergencyDirectFallback.suppressed}, " +
            "lastEmergencyDirectFallbackReason=${stats.recoveryDiagnostics.emergencyDirectFallback.lastReason ?: "none"}, " +
            "lastEmergencyDirectFallbackTimeMs=${stats.recoveryDiagnostics.emergencyDirectFallback.lastTimeMs ?: "unknown"}, " +
            "lastNetworkTypeAtRouteAttempt=${stats.lastNetworkTypeAtRouteAttempt}, " +
            "routeAttemptNetworkGeneration=${stats.routeAttemptNetworkGeneration}, " +
            "routeAttemptNetworkChangedBeforeSelection=${stats.routeAttemptNetworkChangedBeforeSelection}, " +
            "mobileDirectRescueCooldownUntil=${formatLongByDc(stats.mobileDirectRescueCooldownUntil)}, " +
            "mobileDirectRescueLastError=${formatStringByDc(stats.mobileDirectRescueLastError)}, " +
            "mobileDirectRescueLastSuccessTime=${formatLongByDc(stats.mobileDirectRescueLastSuccessTime)}, " +
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
            "cfPressureAllDomainsCooldownByDc=${formatLongByDc(stats.cfPressureAllDomainsCooldownByDc)}, " +
            "cfPressureReasonByDc=${formatStringByDc(stats.cfPressureReasonByDc)}, " +
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

    private fun StringBuilder.appendDirectPoolReadiness(stats: ProxyServerStats?) {
        appendLine("Direct pool readiness:")
        appendLine("  readyByKey: ${stats?.poolReadyByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  inFlightRefillsByKey: ${stats?.poolInFlightRefillsByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  hitsByKey: ${stats?.poolHitsByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  missesByKey: ${stats?.poolMissesByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  refillAttemptsByKey: ${stats?.poolRefillAttemptsByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  refillSuccessesByKey: ${stats?.poolRefillSuccessesByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  refillErrorsByKey: ${stats?.poolRefillErrorsByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  staleByKey: ${stats?.poolStaleByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  lastRefillErrorByKey: ${stats?.poolLastRefillErrorByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  lastRefillTimeMsByKey: ${stats?.poolLastRefillTimeMsByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  lastHitTimeMsByKey: ${stats?.poolLastHitTimeMsByKey?.let(::formatCompactMap) ?: "unknown"}")
        appendLine("  lastMissTimeMsByKey: ${stats?.poolLastMissTimeMsByKey?.let(::formatCompactMap) ?: "unknown"}")
    }

    private fun formatCompactMap(values: Map<*, *>): String =
        if (values.isEmpty()) "none" else values.entries.joinToString(";") { "${it.key}=${it.value}" }

    private fun StringBuilder.appendClientExperienceDiagnostics(stats: ProxyServerStats?) {
        val experience = stats?.clientExperience
        appendLine("Client experience diagnostics:")
        appendLine("  likelyReconnectBurst: ${experience?.likelyReconnectBurst?.toString() ?: "unknown"}")
        appendLine("  likelyTelegramDisabledProxy: ${experience?.likelyTelegramDisabledProxy?.toString() ?: "unknown"}")
        appendLine("  recentAcceptedHandshakes: ${experience?.recentAcceptedHandshakes?.toString() ?: "unknown"}")
        appendLine("  recentClientClosedSessions: ${experience?.recentClientClosedSessions?.toString() ?: "unknown"}")
        appendLine("  recentVeryShortClientClosedSessions: ${experience?.recentVeryShortClientClosedSessions?.toString() ?: "unknown"}")
        appendLine("  recentShortRemoteEofSessions: ${experience?.recentShortRemoteEofSessions?.toString() ?: "unknown"}")
        appendLine("  recentConnectionResetSessions: ${experience?.recentConnectionResetSessions?.toString() ?: "unknown"}")
        appendLine("  recentDirectTimeouts: ${experience?.recentDirectTimeouts?.toString() ?: "unknown"}")
        appendLine("  recentPoolMisses: ${experience?.recentPoolMisses?.toString() ?: "unknown"}")
        appendLine("  recentPoolRefillErrors: ${experience?.recentPoolRefillErrors?.toString() ?: "unknown"}")
        appendLine("  recentPoolStale: ${experience?.recentPoolStale?.toString() ?: "unknown"}")
        appendLine("  recentUnsupportedDcByDc: ${experience?.recentUnsupportedDcByDc?.let(::formatLongByDc) ?: "unknown"}")
        appendLine("  recentNoRouteByDc: ${experience?.recentNoRouteByDc?.let(::formatLongByDc) ?: "unknown"}")
        appendLine("  timeToFirstSuccessfulRouteAfterIdleMs: ${experience?.timeToFirstSuccessfulRouteAfterIdleMs?.toString() ?: "unknown"}")
        appendLine("  wakeBurstPrewarmTriggers: ${experience?.wakeBurstPrewarmTriggers?.toString() ?: "unknown"}")
        appendLine("  wakeBurstPrewarmSkippedNoDirectRedirect: ${experience?.wakeBurstPrewarmSkippedNoDirectRedirect?.toString() ?: "unknown"}")
        appendLine("  wakeBurstPrewarmSkippedCooldown: ${experience?.wakeBurstPrewarmSkippedCooldown?.toString() ?: "unknown"}")
        appendLine("  wakeBurstPrewarmAttempts: ${experience?.wakeBurstPrewarmAttempts?.toString() ?: "unknown"}")
        appendLine("  wakeBurstPrewarmSuccesses: ${experience?.wakeBurstPrewarmSuccesses?.toString() ?: "unknown"}")
        appendLine("  wakeBurstPrewarmFailures: ${experience?.wakeBurstPrewarmFailures?.toString() ?: "unknown"}")
        appendLine("  lastWakeBurstPrewarmTimeMs: ${experience?.lastWakeBurstPrewarmTimeMs?.toString() ?: "unknown"}")
        appendLine("  lastWakeBurstPrewarmDc: ${experience?.lastWakeBurstPrewarmDc?.toString() ?: "unknown"}")
        appendLine("  lastWakeBurstPrewarmError: ${experience?.lastWakeBurstPrewarmError ?: "none"}")
        appendLine("  idlePoolMaintenanceRuns: ${experience?.idlePoolMaintenanceRuns?.toString() ?: "unknown"}")
        appendLine("  idlePoolMaintenanceSkippedNetwork: ${experience?.idlePoolMaintenanceSkippedNetwork?.toString() ?: "unknown"}")
        appendLine("  idlePoolMaintenanceSkippedRoute: ${experience?.idlePoolMaintenanceSkippedRoute?.toString() ?: "unknown"}")
        appendLine("  idlePoolMaintenanceSkippedDirectHealth: ${experience?.idlePoolMaintenanceSkippedDirectHealth?.toString() ?: "unknown"}")
        appendLine("  idlePoolMaintenanceSkippedActiveSessions: ${experience?.idlePoolMaintenanceSkippedActiveSessions?.toString() ?: "unknown"}")
        appendLine("  idlePoolMaintenanceAttempts: ${experience?.idlePoolMaintenanceAttempts?.toString() ?: "unknown"}")
        appendLine("  idlePoolMaintenanceSuccesses: ${experience?.idlePoolMaintenanceSuccesses?.toString() ?: "unknown"}")
        appendLine("  idlePoolMaintenanceFailures: ${experience?.idlePoolMaintenanceFailures?.toString() ?: "unknown"}")
        appendLine("  lastIdlePoolMaintenanceTimeMs: ${experience?.lastIdlePoolMaintenanceTimeMs?.toString() ?: "unknown"}")
        appendLine("  lastIdlePoolMaintenanceError: ${experience?.lastIdlePoolMaintenanceError ?: "none"}")
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
                        "allDomainsCooldown=${stats.cfPressureAllDomainsCooldownByDc[dcId] ?: 0L} " +
                        "reason=${stats.cfPressureReasonByDc[dcId] ?: "none"} " +
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
