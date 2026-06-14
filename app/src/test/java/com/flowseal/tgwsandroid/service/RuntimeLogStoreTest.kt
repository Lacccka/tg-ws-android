package com.flowseal.tgwsandroid.service

import com.flowseal.tgwsandroid.proxy.CfDomainSnapshot
import com.flowseal.tgwsandroid.proxy.CfFirstRecoveryDiagnostics
import com.flowseal.tgwsandroid.proxy.ConnectionSocketEndDiagnostics
import com.flowseal.tgwsandroid.proxy.EmergencyDirectFallbackDiagnostics
import com.flowseal.tgwsandroid.proxy.ProxyRecoveryDiagnostics
import com.flowseal.tgwsandroid.proxy.ProxySessionEndDiagnostics
import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuntimeLogStoreTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-06-04T02:45:30Z"), ZoneOffset.UTC)


    @Test
    fun metadataOnEmptyStoreReturnsEmptyCoverage() {
        val store = RuntimeLogStore(maxLines = 3, maxChars = 123, clock = fixedClock)

        val metadata = store.metadataSnapshot()

        assertEquals(null, metadata.oldestLogTimeMs)
        assertEquals(null, metadata.newestLogTimeMs)
        assertEquals(null, metadata.logCoverageDurationMs)
        assertEquals(0, metadata.currentLogEntryCount)
        assertEquals(0, metadata.currentLogApproxChars)
        assertEquals(3, metadata.maxLogLines)
        assertEquals(123, metadata.maxLogChars)
        assertEquals(0L, metadata.totalLogEntriesAccepted)
        assertEquals(0L, metadata.totalLogEntriesDroppedDueToLimit)
        assertEquals(0L, metadata.restoredLogEntries)
    }

    @Test
    fun metadataAfterAppendsReturnsCurrentCoverageAndLimits() {
        val clock = MutableClock(Instant.parse("2026-06-04T02:45:30Z"), ZoneOffset.UTC)
        val store = RuntimeLogStore(maxLines = 10, maxChars = 1_000, clock = clock)

        store.append("first", LogSeverity.INFO, "service")
        clock.advanceMillis(2_500L)
        store.append("second", LogSeverity.WARN, "proxy")

        val metadata = store.metadataSnapshot()

        assertEquals(2, metadata.currentLogEntryCount)
        assertEquals(store.lines().sumOf { it.length }, metadata.currentLogApproxChars)
        assertEquals(10, metadata.maxLogLines)
        assertEquals(1_000, metadata.maxLogChars)
        assertEquals(Instant.parse("2026-06-04T02:45:30Z").toEpochMilli(), metadata.oldestLogTimeMs)
        assertEquals(Instant.parse("2026-06-04T02:45:32.500Z").toEpochMilli(), metadata.newestLogTimeMs)
        assertEquals(2_500L, metadata.logCoverageDurationMs)
        assertEquals(2L, metadata.totalLogEntriesAccepted)
        assertEquals(0L, metadata.totalLogEntriesDroppedDueToLimit)
    }

    @Test
    fun metadataMaxLinesTrimIncrementsDroppedCounter() {
        val store = RuntimeLogStore(maxLines = 3, clock = fixedClock)

        repeat(5) { store.append("line-$it", LogSeverity.DEBUG, "proxy") }

        val metadata = store.metadataSnapshot()
        assertEquals(3, metadata.currentLogEntryCount)
        assertEquals(5L, metadata.totalLogEntriesAccepted)
        assertEquals(2L, metadata.totalLogEntriesDroppedDueToLimit)
    }

    @Test
    fun metadataMaxCharsTrimIncrementsDroppedCounter() {
        val store = RuntimeLogStore(maxLines = 10, maxChars = 80, clock = fixedClock)

        repeat(10) { store.append("long-message-$it", LogSeverity.DEBUG, "proxy") }

        val metadata = store.metadataSnapshot()
        assertEquals(store.lines().size, metadata.currentLogEntryCount)
        assertEquals(store.lines().sumOf { it.length }, metadata.currentLogApproxChars)
        assertTrue(metadata.currentLogApproxChars <= 80)
        assertEquals(10L, metadata.totalLogEntriesAccepted)
        assertEquals(10L - metadata.currentLogEntryCount.toLong(), metadata.totalLogEntriesDroppedDueToLimit)
    }

    @Test
    fun clearKeepsLifetimeCountersAndNextLogContinuesAcceptedCounter() {
        val store = RuntimeLogStore(clock = fixedClock)
        store.append("before", LogSeverity.INFO, "service")

        store.clear()
        store.append("Logs cleared", LogSeverity.INFO, "ui")

        val metadata = store.metadataSnapshot()
        assertEquals(1, metadata.currentLogEntryCount)
        assertEquals(2L, metadata.totalLogEntriesAccepted)
        assertEquals(0L, metadata.totalLogEntriesDroppedDueToLimit)
        assertEquals(listOf("02:45:30 INFO ui Logs cleared"), store.lines())
    }

    @Test
    fun appendLogLineIncludesTimestampSeveritySourceAndMessage() {
        val store = RuntimeLogStore(clock = fixedClock)

        store.append("Proxy started", LogSeverity.INFO, "service")

        assertEquals(listOf("02:45:30 INFO service Proxy started"), store.lines())
    }

    @Test
    fun boundedTrimmingByMaxLinesKeepsRecentEntries() {
        val store = RuntimeLogStore(maxLines = 3, clock = fixedClock)

        repeat(5) { store.append("line-$it", LogSeverity.DEBUG, "proxy") }

        assertEquals(
            listOf(
                "02:45:30 DEBUG proxy line-2",
                "02:45:30 DEBUG proxy line-3",
                "02:45:30 DEBUG proxy line-4",
            ),
            store.lines(),
        )
    }

    @Test
    fun boundedTrimmingByMaxCharsKeepsRecentEntries() {
        val store = RuntimeLogStore(maxLines = 10, maxChars = 80, clock = fixedClock)

        repeat(10) { store.append("long-message-$it", LogSeverity.DEBUG, "proxy") }

        assertTrue(store.lines().joinToString("").length <= 80)
        assertTrue(store.lines().last().contains("long-message-9"))
    }

    @Test
    fun clearLogsRemovesCurrentBuffer() {
        val store = RuntimeLogStore(clock = fixedClock)
        store.append("before", LogSeverity.INFO, "service")

        store.clear()

        assertTrue(store.lines().isEmpty())
    }

    @Test
    fun afterClearNewLogsCanBeAdded() {
        val store = RuntimeLogStore(clock = fixedClock)
        store.append("before", LogSeverity.INFO, "service")
        store.clear()

        store.append("after", LogSeverity.INFO, "ui")

        assertEquals(listOf("02:45:30 INFO ui after"), store.lines())
    }

    @Test
    fun appendWritesToPersistenceBestEffort() {
        val persistence = InMemoryRuntimeLogPersistence()
        val store = RuntimeLogStore(clock = fixedClock, persistence = persistence)

        store.append("persist me", LogSeverity.INFO, "service")

        assertEquals(listOf("02:45:30 INFO service persist me"), persistence.lines)
    }

    @Test
    fun loadRestoresPersistedLines() {
        val persistence = InMemoryRuntimeLogPersistence(
            mutableListOf("02:45:30 INFO service before reset"),
        )
        val store = RuntimeLogStore(clock = fixedClock)

        val restored = store.configurePersistence(persistence)

        assertEquals(1, restored)
        assertEquals(listOf("02:45:30 INFO service before reset"), store.lines())
        assertEquals(1L, store.metadataSnapshot().restoredLogEntries)
    }

    @Test
    fun clearAlsoClearsPersistence() {
        val persistence = InMemoryRuntimeLogPersistence(mutableListOf("02:45:30 INFO service old"))
        val store = RuntimeLogStore(clock = fixedClock, persistence = persistence)
        store.append("new", LogSeverity.INFO, "service")

        store.clear()

        assertTrue(store.lines().isEmpty())
        assertTrue(persistence.lines.isEmpty())
    }

    @Test
    fun persistenceWriteFailureDoesNotThrow() {
        val store = RuntimeLogStore(clock = fixedClock, persistence = ThrowingRuntimeLogPersistence())

        store.append("still in memory", LogSeverity.INFO, "service")

        assertEquals(listOf("02:45:30 INFO service still in memory"), store.lines())
    }

    @Test
    fun filePersistenceTrimsToBoundedSize() {
        val file = createTempFile(prefix = "runtime", suffix = ".log")
        try {
            val persistence = FileRuntimeLogPersistence(file, maxBytes = 80)

            repeat(10) { persistence.appendLine("02:45:30 INFO service line-$it with padding") }

            assertTrue(file.length() <= 80)
            assertTrue(persistence.readTailLines().last().contains("line-9"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun exportTextIncludesHeaderStatusEndpointAndLogs() {
        val store = RuntimeLogStore(clock = fixedClock)
        store.append("ProxyServer listening", LogSeverity.INFO, "proxy")
        val stats = ProxyServerStats(
            connectionsTotal = 7,
            connectionsActive = 0,
            connectionsBad = 0,
            wsConnectErrors = 2,
            cfProxyConnections = 3,
            cfProxyErrors = 4,
            bytesUp = 5,
            bytesDown = 6,
            poolHits = 8,
            poolMisses = 9,
            poolRefillErrors = 10,
            poolStale = 11,
            sessionEndDiagnostics = ProxySessionEndDiagnostics(
                connectionReset = ConnectionSocketEndDiagnostics(
                    count = 2,
                    lastTimeMs = 1_717_469_130_000,
                    lastRoute = "direct-cold",
                    lastDc = 2,
                    lastMedia = false,
                ),
                connectionTimedOut = ConnectionSocketEndDiagnostics(
                    count = 3,
                    lastTimeMs = 1_717_469_131_000,
                    lastRoute = "cfproxy:kws2.one.example",
                    lastDc = 4,
                    lastMedia = true,
                ),
            ),
            cfHealthEnabled = true,
            cfDomainsTotal = 2,
            cfDomainsInCooldown = 1,
            cfLastSelectedDomain = "kws2.one.example",
            cfLastSelectedReason = "last_good",
            cfLastConnectLatencyMs = 123,
            cfBestDomainByDc = mapOf(2 to "kws2.one.example"),
            cf429Count = 12,
            cf503Count = 13,
            cfUnknownHostCount = 14,
            cfTimeoutCount = 15,
            cfCooldownSkips = 16,
            cfAllDomainsInCooldownFallbacks = 17,
            cfInflightSkips = 18,
            cfInflightWaits = 19,
            cfMaxInflightPerDomainReached = 20,
            cfActiveConnectsByDc = mapOf(2 to 1),
            cfConnectQueueWaits = 21,
            cfConnectQueueTimeouts = 22,
            cfQueueControlledFailures = 23,
            cfQueueWaitMs = 250,
            cfMaxConcurrentConnectsByDc = mapOf(2 to 2),
            cf429BackoffCount = 24,
            cfAllCooldownWaits = 25,
            cfAllCooldownWaitMs = 250,
            cfAllCooldownCircuitOpenCount = 26,
            cfAllCooldownAttemptsAllowed = 27,
            cfAllCooldownAttemptsSuppressed = 28,
            cfAllCooldownControlledFailures = 29,
            cfAllCooldownCircuitOpenByDc = mapOf(2 to 123_456L),
            cfAllCooldownSingleAttempts = 30,
            cfAllCooldownSingleAttemptFailures = 31,
            cfAllCooldownStoppedCycles = 32,
            cfPressureLevelByDc = mapOf(2 to "degraded"),
            cfPressureScoreByDc = mapOf(2 to 42),
            cfPressureRecentSuccessByDc = mapOf(2 to 1),
            cfPressureRecent429ByDc = mapOf(2 to 4),
            cfPressureRecentTimeoutByDc = mapOf(2 to 2),
            cfPressureRecentQueueFailureByDc = mapOf(2 to 3),
            cfPressureRecentAllCooldownSuppressedByDc = mapOf(2 to 1),
            cfPressureProbeAllowed = 5,
            cfPressureProbeSuppressed = 6,
            cfPressureControlledFailures = 7,
            cfPressureLimitedAttempts = 8,
            cfPressureLevelChanges = 9,
            cfPressureNextProbeAtByDc = mapOf(2 to 654_321L),
            recentInvalidHandshakeCount = 120,
            recentAcceptedHandshakeCount = 0,
            lastInvalidHandshakeTimeMs = 1_717_469_129_000,
            lastAcceptedHandshakeTimeMs = 0,
            lastSuccessfulRouteTimeMs = 0,
            cfHealthDomains = listOf(
                CfDomainSnapshot(
                    dcId = 2,
                    isMedia = false,
                    domain = "one.example",
                    successes = 1,
                    failures = 0,
                    lastSuccessTimeMs = 1,
                    lastFailureTimeMs = 0,
                    lastLatencyMs = 123,
                    ewmaLatencyMs = 123,
                    consecutiveFailures = 0,
                    cooldownUntilMs = 0,
                    lastErrorKind = null,
                    total429 = 12,
                    consecutive429 = 1,
                    backoffUntilMs = 0,
                    backoffLevel = 1,
                    total503 = 13,
                    totalUnknownHost = 14,
                    totalTimeouts = 15,
                    successfulStreak = 2,
                ),
            ),
        )

        val report = DiagnosticReportFormatter.format(
            DiagnosticReportFormatter.snapshot(
                status = "proxy running on 127.0.0.1:1443",
                endpoint = "127.0.0.1:1443",
                secret = "dd40...3da8",
                dcSummary = "2,4 via 149.154.167.220",
                batteryOptimization = "optimized",
                network = "Wi-Fi",
                stats = stats,
                logs = store.snapshot(),
                clock = fixedClock,
            ),
        )

        assertTrue(report.contains("TG WS Android diagnostics"))
        assertTrue(report.contains("Generated: 2026-06-04 02:45:30"))
        assertTrue(report.contains("Status: proxy running on 127.0.0.1:1443"))
        assertTrue(report.contains("Endpoint: 127.0.0.1:1443"))
        assertTrue(report.contains("Stats: total=7, active=0"))
        assertTrue(report.contains("poolStale=11"))
        assertTrue(report.contains("sessionConnectionReset=2"))
        assertTrue(report.contains("lastConnectionResetRoute=direct-cold"))
        assertTrue(report.contains("lastConnectionResetDc=2"))
        assertTrue(report.contains("lastConnectionResetMedia=false"))
        assertTrue(report.contains("sessionConnectionTimedOut=3"))
        assertTrue(report.contains("lastConnectionTimedOutRoute=cfproxy:kws2.one.example"))
        assertTrue(report.contains("lastConnectionTimedOutDc=4"))
        assertTrue(report.contains("lastConnectionTimedOutMedia=true"))
        assertTrue(report.contains("cfHealthEnabled=true"))
        assertTrue(report.contains("cf429Count=12"))
        assertTrue(report.contains("cfInflightSkips=18"))
        assertTrue(report.contains("cfConnectQueueWaits=21"))
        assertTrue(report.contains("cf429BackoffCount=24"))
        assertTrue(report.contains("cfQueueControlledFailures=23"))
        assertTrue(report.contains("cfAllCooldownAttemptsAllowed=27"))
        assertTrue(report.contains("cfAllCooldownAttemptsSuppressed=28"))
        assertTrue(report.contains("cfAllCooldownControlledFailures=29"))
        assertTrue(report.contains("cfAllCooldownCircuitOpenByDc={DC2=123456}"))
        assertTrue(report.contains("cfAllCooldownSingleAttempts=30"))
        assertTrue(report.contains("cfPressureLevelByDc={DC2=degraded}"))
        assertTrue(report.contains("cfPressureProbeSuppressed=6"))
        assertTrue(report.contains("cfPressureLevel=degraded score=42"))
        assertTrue(report.contains("handshakeDiagnosticState=fatal_secret_mismatch"))
        assertTrue(report.contains("handshakeDiagnosticReason=many_recent_invalid_handshakes_without_accepted_handshake_or_successful_route"))
        assertTrue(report.contains("badHandshakeRecommendation=Telegram подключается с неправильным secret"))
        assertTrue(report.contains("secondsSinceLastAcceptedHandshake=unknown"))
        assertTrue(report.contains("CF health:"))
        assertTrue(report.contains("best=kws2.one.example latency=123"))
        assertTrue(report.contains("02:45:30 INFO proxy ProxyServer listening"))
    }

    @Test
    fun diagnosticReportIncludesTesterBundleAndKeepsSecretMasked() {
        val rawSecret = "0123456789abcdeffedcba9876543210"
        val stats = ProxyServerStats(
            connectionsTotal = 2,
            connectionsActive = 1,
            connectionsBad = 0,
            wsConnectErrors = 0,
            cfProxyConnections = 1,
            cfProxyErrors = 0,
            bytesUp = 10,
            bytesDown = 20,
            poolHits = 1,
            poolMisses = 1,
            poolRefillErrors = 0,
            sessionRemoteEof = 1,
            effectiveRouteMode = "direct_first",
            lastRouteUsed = "cf-proxy",
            cfHealthEnabled = true,
            cfDomainsTotal = 1,
            networkGeneration = 3,
            lastNetworkAvailableAtMs = 1_000L,
            lastNetworkLostAtMs = 2_000L,
        )

        val report = DiagnosticReportFormatter.format(
            DiagnosticReportFormatter.snapshot(
                status = "Proxy running",
                endpoint = "127.0.0.1:1443",
                secret = rawSecret,
                dcSummary = "2,4 via direct",
                batteryOptimization = "optimized",
                network = "Wi-Fi",
                declaredForegroundServiceStrategy = "specialUse",
                runtimeForegroundServiceType = "specialUse",
                foregroundServiceType = "specialUse",
                applicationId = "com.flowseal.tgwsandroid",
                versionName = "0.1.0",
                versionCode = 1,
                buildType = "sideload",
                flavor = "sideload",
                debuggable = true,
                gitCommitSha = "abcdef123456",
                androidSdkInt = 35,
                androidRelease = "15",
                manufacturer = "Google",
                model = "Pixel",
                notificationPermissionStatus = "granted",
                normalizedNetworkType = "Wi-Fi",
                activeNetworkMetered = "unmetered",
                networkCapabilitySummary = "available=true validated=true captive=false",
                reportGeneratedTimeMs = 1_717_469_130_000L,
                serviceUptimeMs = 10_000L,
                proxyUptimeMs = 9_000L,
                stats = stats,
                logs = emptyList(),
                clock = fixedClock,
            ),
        )

        assertTrue(report.contains("Build:"))
        assertTrue(report.contains("applicationId: com.flowseal.tgwsandroid"))
        assertTrue(report.contains("versionName: 0.1.0"))
        assertTrue(report.contains("versionCode: 1"))
        assertTrue(report.contains("buildType: sideload"))
        assertTrue(report.contains("flavor/variant: sideload"))
        assertTrue(report.contains("debuggable: true"))
        assertTrue(report.contains("gitCommitSha: abcdef123456"))
        assertTrue(report.contains("Foreground service:"))
        assertTrue(report.contains("declaredForegroundServiceStrategy: specialUse"))
        assertTrue(report.contains("runtimeForegroundServiceStrategy: specialUse"))
        assertTrue(report.contains("foregroundServiceTypeLabel: specialUse"))
        assertTrue(report.contains("Device:"))
        assertTrue(report.contains("manufacturer: Google"))
        assertTrue(report.contains("model: Pixel"))
        assertTrue(report.contains("notificationPermissionStatus: granted"))
        assertTrue(report.contains("Network:"))
        assertTrue(report.contains("normalizedNetworkType: Wi-Fi"))
        assertTrue(report.contains("activeNetworkMetered: unmetered"))
        assertTrue(report.contains("networkCapabilitySummary: available=true validated=true captive=false"))
        assertTrue(report.contains("networkGeneration: 3"))
        assertTrue(report.contains("Timing:"))
        assertTrue(report.contains("reportGeneratedTimeMs: 1717469130000"))
        assertTrue(report.contains("serviceUptimeMs: 10000"))
        assertTrue(report.contains("proxyUptimeMs: 9000"))
        assertTrue(report.contains("Secret: 0123...3210"))
        assertFalse(report.contains(rawSecret))
        assertTrue(report.contains("effectiveRouteMode=direct_first"))
        assertTrue(report.contains("lastRouteUsed=cf-proxy"))
        assertTrue(report.contains("sessionRemoteEof=1"))
        assertTrue(report.contains("cfHealthEnabled=true"))
    }

    @Test
    fun diagnosticReportShowsForegroundServiceLifecycleFieldsAndWarning() {
        val previous = PreviousRunCheck(
            hadMarker = true,
            wasRunning = true,
            wasUnexpected = true,
            runId = "run-1",
            startedAt = "2026-06-04T01:00:00Z",
            lastHeartbeatAt = "2026-06-04T06:59:00Z",
            lastServiceEvent = "watchdog_heartbeat",
            lastForegroundStartedAt = "2026-06-04T01:00:01Z",
            lastStopReason = "foreground_service_timeout",
            stoppedAt = "2026-06-04T07:00:00Z",
        )

        val report = DiagnosticReportFormatter.format(
            DiagnosticReportFormatter.snapshot(
                status = "Proxy stopped",
                endpoint = "127.0.0.1:1443",
                secret = "dd40...3da8",
                dcSummary = "2,4 via direct",
                foregroundServiceType = "dataSync",
                targetSdk = 35,
                serviceStartTime = "2026-06-04T01:00:00Z",
                foregroundStartTime = "2026-06-04T01:00:01Z",
                lastWatchdogHeartbeat = "2026-06-04T06:59:00Z",
                wakeLockHeld = true,
                previousRun = previous,
                logs = emptyList(),
                clock = fixedClock,
            ),
        )

        assertTrue(report.contains("Declared foreground service strategy: dataSync"))
        assertTrue(report.contains("Runtime foreground service type: dataSync"))
        assertTrue(report.contains("Foreground service type from manifest/build strategy: dataSync"))
        assertTrue(report.contains("Target SDK: 35"))
        assertTrue(report.contains("Service start time: 2026-06-04T01:00:00Z"))
        assertTrue(report.contains("Foreground start time: 2026-06-04T01:00:01Z"))
        assertTrue(report.contains("Last watchdog heartbeat: 2026-06-04T06:59:00Z"))
        assertTrue(report.contains("WakeLock held: true"))
        assertTrue(report.contains("Previous run was unexpected: true"))
        assertTrue(report.contains("Previous run last heartbeat: 2026-06-04T06:59:00Z"))
        assertTrue(report.contains("Previous run last stop reason: foreground_service_timeout"))
        assertTrue(report.contains("Previous run stopped at: 2026-06-04T07:00:00Z"))
        assertTrue(report.contains("lastServiceEvent=watchdog_heartbeat"))
        assertTrue(report.contains("Android 15 dataSync warning applies: true"))
        assertTrue(report.contains("dataSync foreground service on Android 15+ targetSdk 35 is limited to 6 hours per 24 hours"))
    }

    @Test
    fun diagnosticReportShowsSpecialUseStrategyWithoutDataSyncWarning() {
        val report = DiagnosticReportFormatter.format(
            DiagnosticReportFormatter.snapshot(
                status = "Proxy running",
                endpoint = "127.0.0.1:1443",
                secret = "dd40...3da8",
                dcSummary = "2,4 via direct",
                declaredForegroundServiceStrategy = "specialUse",
                runtimeForegroundServiceType = "specialUse",
                targetSdk = 35,
                logs = emptyList(),
                clock = fixedClock,
            ),
        )

        assertTrue(report.contains("Declared foreground service strategy: specialUse"))
        assertTrue(report.contains("Runtime foreground service type: specialUse"))
        assertTrue(report.contains("Android 15 dataSync warning applies: false"))
        assertFalse(report.contains("dataSync foreground service on Android 15+ targetSdk 35 is limited to 6 hours per 24 hours"))
    }

    @Test
    fun diagnosticReportShowsDebugDataSyncWarning() {
        val report = DiagnosticReportFormatter.format(
            DiagnosticReportFormatter.snapshot(
                status = "Proxy running",
                endpoint = "127.0.0.1:1443",
                secret = "dd40...3da8",
                dcSummary = "2,4 via direct",
                declaredForegroundServiceStrategy = "dataSync",
                runtimeForegroundServiceType = "dataSync",
                foregroundServiceType = "dataSync",
                targetSdk = 35,
                logs = emptyList(),
                clock = fixedClock,
            ),
        )

        assertTrue(report.contains("Declared foreground service strategy: dataSync"))
        assertTrue(report.contains("Android 15 dataSync warning applies: true"))
    }

    @Test
    fun diagnosticReportShowsReleaseDataSyncWarning() {
        val report = DiagnosticReportFormatter.format(
            DiagnosticReportFormatter.snapshot(
                status = "Proxy running",
                endpoint = "127.0.0.1:1443",
                secret = "dd40...3da8",
                dcSummary = "2,4 via direct",
                declaredForegroundServiceStrategy = "dataSync",
                runtimeForegroundServiceType = "dataSync",
                foregroundServiceType = "dataSync",
                targetSdk = 35,
                logs = emptyList(),
                clock = fixedClock,
            ),
        )

        assertTrue(report.contains("Declared foreground service strategy: dataSync"))
        assertTrue(report.contains("Android 15 dataSync warning applies: true"))
    }

    @Test
    fun diagnosticReportShowsCurrentHeartbeatAsUnknownWhenNotYetRecorded() {
        val report = DiagnosticReportFormatter.format(
            DiagnosticReportFormatter.snapshot(
                status = "Proxy running",
                endpoint = "127.0.0.1:1443",
                secret = "dd40...3da8",
                dcSummary = "2,4 via direct",
                declaredForegroundServiceStrategy = "dataSync",
                runtimeForegroundServiceType = "dataSync",
                foregroundServiceType = "dataSync",
                targetSdk = 35,
                lastWatchdogHeartbeat = null,
                previousRun = PreviousRunCheck(
                    hadMarker = true,
                    wasRunning = false,
                    wasUnexpected = false,
                    runId = "run-previous",
                    startedAt = "2026-06-04T01:00:00Z",
                    lastHeartbeatAt = "2026-06-04T06:59:00Z",
                    lastServiceEvent = "stopped",
                    lastForegroundStartedAt = "2026-06-04T01:00:01Z",
                    lastStopReason = "foreground_service_timeout",
                    stoppedAt = "2026-06-04T07:00:00Z",
                ),
                logs = emptyList(),
                clock = fixedClock,
            ),
        )

        assertTrue(report.contains("Last watchdog heartbeat: unknown"))
        assertTrue(report.contains("Previous run last heartbeat: 2026-06-04T06:59:00Z"))
    }


    @Test
    fun diagnosticReportPrintsLogCoverageMetadata() {
        val store = RuntimeLogStore(maxLines = 2, maxChars = 200, clock = fixedClock)
        repeat(3) { store.append("line-$it", LogSeverity.INFO, "service") }

        val report = DiagnosticReportFormatter.format(
            DiagnosticReportFormatter.snapshot(
                status = "Proxy running",
                endpoint = "127.0.0.1:1443",
                secret = "0123456789abcdeffedcba9876543210",
                dcSummary = "2,4 via direct",
                batteryOptimization = "optimized",
                network = "Wi-Fi",
                logMetadata = store.metadataSnapshot(),
                logs = store.snapshot(),
                clock = fixedClock,
            ),
        )

        assertTrue(report.contains("Log coverage:"))
        assertTrue(report.contains("currentLogEntryCount: 2"))
        assertTrue(report.contains("currentLogApproxChars: ${store.lines().sumOf { it.length }}"))
        assertTrue(report.contains("maxLogLines: 2"))
        assertTrue(report.contains("maxLogChars: 200"))
        assertTrue(report.contains("totalLogEntriesAccepted: 3"))
        assertTrue(report.contains("totalLogEntriesDroppedDueToLimit: 1"))
        assertTrue(report.contains("restoredLogEntries: 0"))
        assertTrue(report.contains("Secret: 0123...3210"))
        assertFalse(report.contains("Secret: 0123456789abcdeffedcba9876543210"))
    }

    @Test
    fun classifySeverityUsesDeterministicRules() {
        assertEquals(LogSeverity.WARN, RuntimeLogStore.classifySeverity("WebSocket connect failed"))
        assertEquals(LogSeverity.WARN, RuntimeLogStore.classifySeverity("SocketTimeoutException: timeout"))
        assertEquals(LogSeverity.WARN, RuntimeLogStore.classifySeverity("CF proxy failed via example"))
        assertEquals(LogSeverity.ERROR, RuntimeLogStore.classifySeverity("proxy start failed with exception"))
        assertEquals(LogSeverity.INFO, RuntimeLogStore.classifySeverity("WebSocket connected"))
        assertEquals(LogSeverity.INFO, RuntimeLogStore.classifySeverity("ProxyServer listening"))
    }

    @Test
    fun threadSafetySmokeTestKeepsBoundedSize() {
        val store = RuntimeLogStore(maxLines = 100, clock = fixedClock)
        val threads = 8
        val perThread = 100
        val executor = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        repeat(threads) { threadIndex ->
            executor.execute {
                start.await()
                repeat(perThread) { messageIndex ->
                    store.append("$threadIndex-$messageIndex", LogSeverity.DEBUG, "proxy")
                }
            }
        }

        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(store.lines().size <= 100)
        val metadata = store.metadataSnapshot()
        assertTrue(metadata.currentLogEntryCount <= 100)
        assertEquals(threads * perThread.toLong(), metadata.totalLogEntriesAccepted)
        assertEquals(metadata.totalLogEntriesAccepted - metadata.currentLogEntryCount.toLong(), metadata.totalLogEntriesDroppedDueToLimit)
    }
    private class InMemoryRuntimeLogPersistence(
        val lines: MutableList<String> = mutableListOf(),
    ) : RuntimeLogPersistence {
        override fun readTailLines(): List<String> = lines.toList()
        override fun appendLine(line: String) {
            lines += line
        }
        override fun clear() {
            lines.clear()
        }
    }

    private class ThrowingRuntimeLogPersistence : RuntimeLogPersistence {
        override fun readTailLines(): List<String> = emptyList()
        override fun appendLine(line: String) {
            throw RuntimeException("disk full")
        }
        override fun clear() {
            throw RuntimeException("disk full")
        }
    }

    private class MutableClock(
        private var currentInstant: Instant,
        private val currentZone: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = currentZone
        override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)
        override fun instant(): Instant = currentInstant
        fun advanceMillis(millis: Long) {
            currentInstant = currentInstant.plusMillis(millis)
        }
    }


    @Test
    fun diagnosticReportShowsSnapshotTimingAndStatsAfterIncludedLogTail() {
        val store = RuntimeLogStore(clock = fixedClock)
        store.append("effective route changed: cf_first -> direct_first because direct health probe success", LogSeverity.INFO, "proxy")
        val stats = ProxyServerStats(
            connectionsTotal = 1,
            connectionsActive = 0,
            connectionsBad = 0,
            wsConnectErrors = 0,
            cfProxyConnections = 0,
            cfProxyErrors = 0,
            bytesUp = 0,
            bytesDown = 0,
            poolHits = 0,
            poolMisses = 0,
            poolRefillErrors = 0,
            effectiveRouteMode = "direct_first",
            previousEffectiveRouteMode = "cf_first",
            lastRouteUsed = "direct-cold",
            statsSnapshotTimeMs = 1_800L,
            lastEffectiveRouteModeUpdateTimeMs = 1_700L,
            lastRouteUsedUpdateTimeMs = 1_750L,
            wifiDirectRecoveryAttempts = 1,
            wifiDirectRecoverySuccesses = 1,
            recoveryDiagnostics = ProxyRecoveryDiagnostics(
                cfFirst = CfFirstRecoveryDiagnostics(
                    wifiAttempts = 2,
                    wifiSuccesses = 1,
                    lastReason = "test recovery",
                ),
                emergencyDirectFallback = EmergencyDirectFallbackDiagnostics(
                    suppressed = 1,
                    lastReason = "direct cooldown",
                ),
            ),
        )

        val report = DiagnosticReportFormatter.format(
            DiagnosticReportFormatter.snapshot(
                status = "Proxy running",
                endpoint = "127.0.0.1:1443",
                secret = "dd40...3da8",
                dcSummary = "2,4 via direct",
                batteryOptimization = "optimized",
                network = "Wi-Fi",
                effectiveRouteMode = stats.effectiveRouteMode,
                previousEffectiveRouteMode = stats.previousEffectiveRouteMode,
                lastRouteChangeReason = stats.lastRouteChangeReason,
                lastRouteChangeTimeMs = stats.lastRouteChangeTimeMs,
                networkAtLastRouteChange = stats.networkAtLastRouteChange,
                stats = stats,
                runtimeLogTailUntilMs = 1_600L,
                logs = store.snapshot(),
                clock = fixedClock,
            ),
        )

        assertTrue(report.contains("Effective route mode: direct_first"))
        assertTrue(report.contains("Stats snapshot time ms: 1800"))
        assertTrue(report.contains("Runtime log tail until ms: 1600"))
        assertTrue(report.contains("Last effective route mode update time ms: 1700"))
        assertTrue(report.contains("Last route used update time ms: 1750"))
        assertTrue(report.contains("effectiveRouteMode=direct_first"))
        assertTrue(report.contains("lastRouteUsed=direct-cold"))
        assertTrue(report.contains("wifiDirectRecoveryAttempts=1"))
        assertTrue(report.contains("wifiDirectRecoverySuccesses=1"))
        assertTrue(report.contains("wifiCfFirstRecoveryAttempts=2"))
        assertTrue(report.contains("lastCfFirstRecoveryReason=test recovery"))
        assertTrue(report.contains("emergencyDirectFallbackSuppressed=1"))
        assertTrue(report.contains("lastEmergencyDirectFallbackReason=direct cooldown"))
    }

    @Test
    fun processDeathDiagnosticsRenderUnexpectedRunAndHeartbeatDelta() {
        val report = DiagnosticReportFormatter.format(
            DiagnosticReportFormatter.snapshot(
                status = "Proxy stopped",
                endpoint = "127.0.0.1:1443",
                secret = "super-secret-token",
                dcSummary = "unknown",
                batteryOptimization = "unknown",
                previousRun = PreviousRunCheck(
                    hadMarker = true,
                    wasRunning = true,
                    wasUnexpected = true,
                    runId = "run-1",
                    startedAt = "2026-06-04T06:58:00Z",
                    lastHeartbeatAt = "2026-06-04T06:59:00Z",
                    lastServiceEvent = "watchdog_heartbeat",
                    lastServiceEventAt = "2026-06-04T06:59:00Z",
                    lastForegroundStartedAt = "2026-06-04T06:58:05Z",
                    lastStopReason = null,
                    stoppedAt = null,
                    hadWakeLockAtLastMarker = true,
                    wasForegroundAtLastMarker = true,
                    networkAtLastMarker = "Wi-Fi",
                    routeAtLastMarker = "direct_first",
                ),
                reportGeneratedTimeMs = Instant.parse("2026-06-04T07:00:30Z").toEpochMilli(),
                logs = emptyList(),
                clock = fixedClock,
            ),
        )

        assertTrue(report.contains("Process death diagnostics:"))
        assertTrue(report.contains("previousRunWasUnexpected: true"))
        assertTrue(report.contains("previousRunId: run-1"))
        assertTrue(report.contains("previousRunDiedAfterLastHeartbeatMs: 90000"))
        assertTrue(report.contains("previousRunDiedAfterLastHeartbeatHumanReadable: 1m 30s"))
        assertTrue(report.contains("previousRunLikelyDiedAtApprox: 2026-06-04T06:59:00Z"))
        assertTrue(report.contains("previousRunHadWakeLockAtLastMarker: true"))
        assertTrue(report.contains("previousRunWasForegroundAtLastMarker: true"))
        assertTrue(report.contains("previousRunNetworkAtLastMarker: Wi-Fi"))
        assertTrue(report.contains("previousRunRouteAtLastMarker: direct_first"))
        assertTrue(report.contains("Secret: supe...oken"))
    }

    @Test
    fun processDeathDiagnosticsRenderGracefulStopAsNotUnexpected() {
        val report = DiagnosticReportFormatter.format(
            DiagnosticReportFormatter.snapshot(
                status = "Proxy stopped",
                endpoint = "127.0.0.1:1443",
                secret = "dd40...3da8",
                dcSummary = "unknown",
                batteryOptimization = "unknown",
                previousRun = PreviousRunCheck(
                    hadMarker = true,
                    wasRunning = false,
                    wasUnexpected = false,
                    runId = "run-1",
                    startedAt = "2026-06-04T06:58:00Z",
                    lastHeartbeatAt = "2026-06-04T06:59:00Z",
                    lastServiceEvent = "stopped",
                    lastStopReason = "ui",
                    stoppedAt = "2026-06-04T07:00:00Z",
                ),
                logs = emptyList(),
                clock = fixedClock,
            ),
        )

        assertTrue(report.contains("previousRunWasUnexpected: false"))
        assertTrue(report.contains("previousRunLastStopReason: ui"))
        assertTrue(report.contains("previousRunDiedAfterLastHeartbeatMs: unknown"))
    }

    @Test
    fun historicalExitProviderFailureAndTrimMemoryCountersRender() {
        val report = DiagnosticReportFormatter.format(
            DiagnosticReportFormatter.snapshot(
                status = "Proxy stopped",
                endpoint = "127.0.0.1:1443",
                secret = "dd40...3da8",
                dcSummary = "unknown",
                batteryOptimization = "unknown",
                historicalExitReasons = ProcessExitReasonDiagnostics(
                    available = false,
                    errorClass = "SecurityException",
                    errorMessage = "permission denied",
                ),
                trimMemory = TrimMemoryDiagnostics(
                    lastTrimMemoryLevel = 5,
                    lastTrimMemoryTimeMs = 1234L,
                    trimMemoryCountByLevel = mapOf(5 to 2L, 10 to 1L),
                    lastLowMemoryTimeMs = 5678L,
                ),
                logs = emptyList(),
                clock = fixedClock,
            ),
        )

        assertTrue(report.contains("historicalExitReasonsAvailable: false"))
        assertTrue(report.contains("historicalExitReasonsErrorClass: SecurityException"))
        assertTrue(report.contains("historicalExitReasonsErrorMessage: permission denied"))
        assertTrue(report.contains("lastTrimMemoryLevel: 5"))
        assertTrue(report.contains("trimMemoryCountByLevel: {5=2, 10=1}"))
        assertTrue(report.contains("lastLowMemoryTimeMs: 5678"))
    }

    @Test
    fun processExitReasonMappingUsesReadableLabels() {
        assertEquals("UNKNOWN", ProxyForegroundService.State.applicationExitReasonLabel(0))
        assertEquals("EXIT_SELF", ProxyForegroundService.State.applicationExitReasonLabel(1))
        assertEquals("SIGNALED", ProxyForegroundService.State.applicationExitReasonLabel(2))
        assertEquals("LOW_MEMORY", ProxyForegroundService.State.applicationExitReasonLabel(3))
        assertEquals("CRASH", ProxyForegroundService.State.applicationExitReasonLabel(4))
        assertEquals("CRASH_NATIVE", ProxyForegroundService.State.applicationExitReasonLabel(5))
        assertEquals("ANR", ProxyForegroundService.State.applicationExitReasonLabel(6))
        assertEquals("INITIALIZATION_FAILURE", ProxyForegroundService.State.applicationExitReasonLabel(7))
        assertEquals("PERMISSION_CHANGE", ProxyForegroundService.State.applicationExitReasonLabel(8))
        assertEquals("EXCESSIVE_RESOURCE_USAGE", ProxyForegroundService.State.applicationExitReasonLabel(9))
        assertEquals("USER_REQUESTED", ProxyForegroundService.State.applicationExitReasonLabel(10))
        assertEquals("USER_STOPPED", ProxyForegroundService.State.applicationExitReasonLabel(11))
        assertEquals("DEPENDENCY_DIED", ProxyForegroundService.State.applicationExitReasonLabel(12))
        assertEquals("OTHER", ProxyForegroundService.State.applicationExitReasonLabel(13))
        assertEquals("FREEZER", ProxyForegroundService.State.applicationExitReasonLabel(14))
        assertEquals("PACKAGE_STATE_CHANGE", ProxyForegroundService.State.applicationExitReasonLabel(15))
        assertEquals("PACKAGE_UPDATED", ProxyForegroundService.State.applicationExitReasonLabel(16))
        assertEquals("UNKNOWN_99", ProxyForegroundService.State.applicationExitReasonLabel(99))
    }

    @Test
    fun processDeathDiagnosticsDetectCleanerKillAndSigkill() {
        val report = DiagnosticReportFormatter.format(
            DiagnosticReportFormatter.snapshot(
                status = "Proxy stopped",
                endpoint = "127.0.0.1:1443",
                secret = "dd40...3da8",
                dcSummary = "unknown",
                batteryOptimization = "unknown",
                historicalExitReasons = ProcessExitReasonDiagnostics(
                    available = true,
                    entries = listOf(
                        ProcessExitReasonEntry(
                            timestamp = 1_000L,
                            reasonCode = 13,
                            reasonLabel = "OTHER",
                            status = 0,
                            importance = 0,
                            pss = 0L,
                            rss = 0L,
                            description = "GarbageClean",
                            processName = "com.flowseal.tgwsandroid",
                            pid = 123,
                            traceInputStreamPresent = false,
                        ),
                        ProcessExitReasonEntry(
                            timestamp = 900L,
                            reasonCode = 2,
                            reasonLabel = "SIGNALED",
                            status = 9,
                            importance = 0,
                            pss = 0L,
                            rss = 0L,
                            description = null,
                            processName = "com.flowseal.tgwsandroid",
                            pid = 122,
                            traceInputStreamPresent = false,
                        ),
                    ),
                ),
                logs = emptyList(),
                clock = fixedClock,
            ),
        )

        assertTrue(report.contains("previousRunLikelyKilledByCleaner: true"))
        assertTrue(report.contains("previousRunCleanerDescription: GarbageClean"))
        assertTrue(report.contains("previousRunCleanerRecommendation: Lock app in recents / add to Cleaner exceptions / keep battery unrestricted / enable autostart"))
        assertTrue(report.contains("previousRunLikelyKilledBySigkill: true"))
        assertTrue(report.contains("previousRunSigkillRecommendation: check OEM battery/cleaner/task-killer restrictions"))
        assertTrue(report.contains("Diagnostics warning: Process was killed by device cleaner. Foreground service and WakeLock do not protect from manual/OEM cleaner. Lock app in Recents and add it to cleaner exceptions."))
    }

}
