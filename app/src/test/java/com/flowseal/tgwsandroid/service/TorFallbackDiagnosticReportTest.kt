package com.flowseal.tgwsandroid.service

import org.junit.Assert.assertTrue
import org.junit.Test

class TorFallbackDiagnosticReportTest {
    @Test
    fun reportIncludesTorRuntimeSnapshotOutsideRollingLogs() {
        val snapshot = DiagnosticReportFormatter.snapshot(
            status = "Proxy running",
            endpoint = "127.0.0.1:1443",
            secret = "00112233445566778899aabbccddeeff",
            dcSummary = "2,4",
            torFallbackRuntime = TorFallbackRuntimeSnapshot(
                desired = true,
                running = true,
                ready = true,
                bootstrapProgress = 100,
                phase = "ready",
                lastError = null,
            ),
            logs = emptyList(),
        )

        val report = DiagnosticReportFormatter.format(snapshot)

        assertTrue(report.contains("Tor/Snowflake runtime:"))
        assertTrue(report.contains("  available: true"))
        assertTrue(report.contains("  desired: true"))
        assertTrue(report.contains("  running: true"))
        assertTrue(report.contains("  ready: true"))
        assertTrue(report.contains("  bootstrapProgress: 100"))
        assertTrue(report.contains("  phase: ready"))
        assertTrue(report.contains("  lastError: none"))
    }
}
