package com.flowseal.tgwsandroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.nio.file.Files

class ProxyRunMarkerTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-06-04T02:45:30Z"), ZoneOffset.UTC)

    @Test
    fun markerRecordsRunningOnStart() {
        val marker = ProxyRunMarker(Files.createTempFile("proxy-run-marker", ".json").toFile(), fixedClock) { "run-1" }

        marker.markStarted()
        val previous = marker.inspectPreviousRun()

        assertTrue(previous.wasRunning)
        assertTrue(previous.wasUnexpected)
        assertEquals("run-1", previous.runId)
        assertEquals("2026-06-04T02:45:30Z", previous.startedAt)
        assertEquals(null, previous.lastHeartbeatAt)
        assertEquals("proxy_started", previous.lastServiceEvent)
    }

    @Test
    fun markerRecordsGracefulStopReason() {
        val marker = ProxyRunMarker(Files.createTempFile("proxy-run-marker", ".json").toFile(), fixedClock) { "run-1" }

        marker.markStarted()
        marker.markStopped("ui")
        val previous = marker.inspectPreviousRun()

        assertFalse(previous.wasRunning)
        assertFalse(previous.wasUnexpected)
        assertEquals("ui", previous.lastStopReason)
        assertEquals("2026-06-04T02:45:30Z", previous.stoppedAt)
    }

    @Test
    fun markerRecordsForegroundAndHeartbeat() {
        val marker = ProxyRunMarker(Files.createTempFile("proxy-run-marker", ".json").toFile(), fixedClock) { "run-1" }

        marker.markStarted()
        marker.markForegroundStarted()
        marker.markHeartbeat()
        val previous = marker.inspectPreviousRun()

        assertEquals("2026-06-04T02:45:30Z", previous.lastForegroundStartedAt)
        assertEquals("2026-06-04T02:45:30Z", previous.lastHeartbeatAt)
        assertEquals("watchdog_heartbeat", previous.lastServiceEvent)
    }

    @Test
    fun previousRunningMarkerIsUnexpectedOnNextInit() {
        val file = Files.createTempFile("proxy-run-marker", ".json").toFile()
        ProxyRunMarker(file, fixedClock) { "run-1" }.markStarted()

        val previous = ProxyRunMarker(file, fixedClock) { "run-2" }.inspectPreviousRun()

        assertTrue(previous.wasUnexpected)
        assertEquals("run-1", previous.runId)
    }

    @Test
    fun newRunDoesNotReusePreviousRunHeartbeat() {
        val file = Files.createTempFile("proxy-run-marker", ".json").toFile()
        val marker = ProxyRunMarker(file, fixedClock) { "run-1" }

        marker.markStarted()
        marker.markHeartbeat()
        marker.markStopped("foreground_service_timeout")
        ProxyRunMarker(file, fixedClock) { "run-2" }.markStarted()
        val current = ProxyRunMarker(file, fixedClock) { "unused" }.inspectPreviousRun()

        assertEquals("run-2", current.runId)
        assertEquals(null, current.lastHeartbeatAt)
        assertEquals("proxy_started", current.lastServiceEvent)
    }

    @Test
    fun completedTimeoutRunKeepsStopReasonAndIsNotUnexpected() {
        val marker = ProxyRunMarker(Files.createTempFile("proxy-run-marker", ".json").toFile(), fixedClock) { "run-1" }

        marker.markStarted()
        marker.markStopped("foreground_service_timeout")
        val previous = marker.inspectPreviousRun()

        assertFalse(previous.wasRunning)
        assertFalse(previous.wasUnexpected)
        assertEquals("foreground_service_timeout", previous.lastStopReason)
        assertEquals("2026-06-04T02:45:30Z", previous.stoppedAt)
    }

    @Test
    fun cleanStopDoesNotSetPreviousRunUnexpected() {
        val file = Files.createTempFile("proxy-run-marker", ".json").toFile()
        ProxyRunMarker(file, fixedClock) { "run-1" }.apply {
            markStarted()
            markStopped("ui")
        }

        val previous = ProxyRunMarker(file, fixedClock) { "run-2" }.inspectPreviousRun()

        assertFalse(previous.wasUnexpected)
        assertEquals("ui", previous.lastStopReason)
    }

}
