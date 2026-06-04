package com.flowseal.tgwsandroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ProxyRunMarkerTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-06-04T02:45:30Z"), ZoneOffset.UTC)

    @Test
    fun markerRecordsRunningOnStart() {
        val marker = ProxyRunMarker(createTempFile(), fixedClock) { "run-1" }

        marker.markStarted()
        val previous = marker.inspectPreviousRun()

        assertTrue(previous.wasRunning)
        assertTrue(previous.wasUnexpected)
        assertEquals("run-1", previous.runId)
        assertEquals("2026-06-04T02:45:30Z", previous.startedAt)
    }

    @Test
    fun markerRecordsGracefulStopReason() {
        val marker = ProxyRunMarker(createTempFile(), fixedClock) { "run-1" }

        marker.markStarted()
        marker.markStopped("ui")
        val previous = marker.inspectPreviousRun()

        assertFalse(previous.wasRunning)
        assertFalse(previous.wasUnexpected)
        assertEquals("ui", previous.lastStopReason)
    }

    @Test
    fun previousRunningMarkerIsUnexpectedOnNextInit() {
        val file = createTempFile()
        ProxyRunMarker(file, fixedClock) { "run-1" }.markStarted()

        val previous = ProxyRunMarker(file, fixedClock) { "run-2" }.inspectPreviousRun()

        assertTrue(previous.wasUnexpected)
        assertEquals("run-1", previous.runId)
    }
}
