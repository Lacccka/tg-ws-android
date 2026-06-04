package com.flowseal.tgwsandroid.service

import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
}
