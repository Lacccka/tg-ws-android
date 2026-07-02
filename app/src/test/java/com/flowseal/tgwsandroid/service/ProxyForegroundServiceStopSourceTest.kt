package com.flowseal.tgwsandroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

class ProxyForegroundServiceStopSourceTest {
    @Test
    fun uiStopActionLogsUiSource() {
        val source = ProxyForegroundService.stopSourceForAction(ProxyForegroundService.ACTION_STOP_FROM_UI)

        assertEquals("ui", source.markerReason)
        assertEquals("stop command received from UI", source.logMessage)
    }

    @Test
    fun notificationStopActionLogsNotificationSource() {
        val source = ProxyForegroundService.stopSourceForAction(ProxyForegroundService.ACTION_STOP_FROM_NOTIFICATION)

        assertEquals("notification", source.markerReason)
        assertEquals("stop command received from notification", source.logMessage)
    }

    @Test
    fun tileStopActionLogsQuickSettingsSource() {
        val source = ProxyForegroundService.stopSourceForAction(ProxyForegroundService.ACTION_STOP_FROM_TILE)

        assertEquals("quick_settings", source.markerReason)
        assertEquals("stop command received from quick settings tile", source.logMessage)
    }

    @Test
    fun legacyStopActionLogsUnknownSource() {
        val source = ProxyForegroundService.stopSourceForAction(ProxyForegroundService.ACTION_STOP_LEGACY)

        assertEquals("legacy_unknown", source.markerReason)
        assertEquals("stop command received from legacy/unknown action", source.logMessage)
    }
    @Test
    fun restartActionIsDistinctFromStopAction() {
        assertNotEquals(ProxyForegroundService.ACTION_STOP_FROM_UI, ProxyForegroundService.ACTION_RESTART_FROM_UI)
        assertEquals("com.flowseal.tgwsandroid.action.RESTART_PROXY_FROM_UI", ProxyForegroundService.ACTION_RESTART_FROM_UI)
    }

    @Test
    fun stopActionRemainsTerminalWhileRestartUsesDedicatedPath() {
        val service = readRepoFile("app/src/main/java/com/flowseal/tgwsandroid/service/ProxyForegroundService.kt")
        val stopPath = service.substringBetween("private fun stopProxyAsync", "private fun stopProxyBlocking")
        val restartPath = service.substringBetween("private fun restartProxyAsync", "private fun startOrRefreshForegroundNotification")

        assertTrue(stopPath.contains("stopProxyBlocking(stopReason)"))
        assertTrue(stopPath.contains("stopForegroundCompat()"))
        assertTrue(stopPath.contains("stopSelf()"))
        assertTrue(restartPath.contains("stopProxyBlocking(\"restart\")"))
        assertTrue(restartPath.contains("startProxyBlocking("))
        assertTrue(restartPath.contains("failureEvent = \"restart_failed_foreground_refresh\""))
        assertTrue(restartPath.contains("preserveRunningStateOnFailure = true"))
        assertTrue(!restartPath.contains("stopForegroundCompat()"))
        assertTrue(!restartPath.contains("stopSelf()"))
    }

    @Test
    fun restartForegroundRefreshFailurePreservesRunningServerState() {
        val service = readRepoFile("app/src/main/java/com/flowseal/tgwsandroid/service/ProxyForegroundService.kt")
        val foregroundRefresh = service.substringBetween("private fun startOrRefreshForegroundNotification", "private fun startProxyBlocking")

        assertTrue(foregroundRefresh.contains("preserveRunningStateOnFailure"))
        assertTrue(foregroundRefresh.contains("proxyServer?.isRunning == true"))
        assertTrue(foregroundRefresh.contains("State.setRunning(serverStillRunning, message)"))
        assertTrue(foregroundRefresh.contains("State.markServiceEvent(\"restart_failed\")"))
        assertTrue(foregroundRefresh.contains("failureEvent?.let { State.markServiceEvent(it) }"))
        assertTrue(foregroundRefresh.contains("State.setRunning(false, message)"))
    }

    private fun readRepoFile(relativePath: String): String = File(repoRoot(), relativePath).readText()

    private fun String.substringBetween(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "Missing start marker: $startMarker" }
        val end = indexOf(endMarker, start + startMarker.length)
        require(end >= 0) { "Missing end marker: $endMarker" }
        return substring(start, end)
    }

    private fun repoRoot(): File {
        val userDir = requireNotNull(System.getProperty("user.dir")) { "Missing user.dir system property" }
        return generateSequence(File(userDir)) { current -> current.parentFile }
            .first { candidate -> File(candidate, "app/build.gradle.kts").isFile }
    }

}
