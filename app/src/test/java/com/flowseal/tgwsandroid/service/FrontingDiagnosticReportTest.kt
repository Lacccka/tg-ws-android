package com.flowseal.tgwsandroid.service

import com.flowseal.tgwsandroid.proxy.DirectPoolDiagnosticsSnapshot
import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontingDiagnosticReportTest {
    @Test
    fun reportIncludesFrontingAndPoolBackoffDiagnostics() {
        val stats = ProxyServerStats().apply {
            frontingAttempts = 7
            frontingSuccesses = 5
            frontingFailures = 2
            frontingFirstAttempts = 3
            frontingFallbackAttempts = 4
            frontingPreferredKeys = listOf("dc2@149.154.167.220")
            lastFrontingError = "SocketTimeoutException: timed out"
            lastFrontingTimeMs = 123456L
            directPoolDiagnostics = DirectPoolDiagnosticsSnapshot(
                refillFailureWavesByKey = mapOf("dc2" to 2),
                refillBackoffRemainingMsByKey = mapOf("dc2" to 60000L),
                refillBackoffSuppressedByKey = mapOf("dc2|maintenance" to 3L),
                closedIdlePrunedByKey = mapOf("dc2" to 1L),
            )
        }
        val snapshot = DiagnosticReportFormatter.snapshot(
            status = "running",
            endpoint = "127.0.0.1:1443",
            secret = "0123456789abcdeffedcba9876543210",
            dcSummary = "2:149.154.167.220",
            stats = stats,
            logs = emptyList(),
        )

        val report = DiagnosticReportFormatter.format(snapshot)

        assertTrue(report.contains("Fronting:"))
        assertTrue(report.contains("attempts: 7"))
        assertTrue(report.contains("successes: 5"))
        assertTrue(report.contains("failures: 2"))
        assertTrue(report.contains("preferredKeys: [dc2@149.154.167.220]"))
        assertTrue(report.contains("refillFailureWavesByKey: {dc2=2}"))
        assertTrue(report.contains("refillBackoffRemainingMsByKey: {dc2=60000}"))
        assertTrue(report.contains("refillBackoffSuppressedByKey: {dc2|maintenance=3}"))
        assertTrue(report.contains("closedIdlePrunedByKey: {dc2=1}"))
    }
}
