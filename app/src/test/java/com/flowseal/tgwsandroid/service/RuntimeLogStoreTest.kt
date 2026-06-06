package com.flowseal.tgwsandroid.service

import com.flowseal.tgwsandroid.proxy.CfDomainSnapshot
import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuntimeLogStoreTest {
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2026-06-04T02:45:30Z"), ZoneOffset.UTC)

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
            connectionsActive = 1,
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
        assertTrue(report.contains("Stats: total=7, active=1"))
        assertTrue(report.contains("poolStale=11"))
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
        assertTrue(report.contains("CF health:"))
        assertTrue(report.contains("best=kws2.one.example latency=123"))
        assertTrue(report.contains("02:45:30 INFO proxy ProxyServer listening"))
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

}
