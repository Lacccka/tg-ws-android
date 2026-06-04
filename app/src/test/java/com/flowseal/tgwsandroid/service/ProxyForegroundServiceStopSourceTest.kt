package com.flowseal.tgwsandroid.service

import org.junit.Assert.assertEquals
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
    fun legacyStopActionLogsUnknownSource() {
        val source = ProxyForegroundService.stopSourceForAction(ProxyForegroundService.ACTION_STOP_LEGACY)

        assertEquals("legacy_unknown", source.markerReason)
        assertEquals("stop command received from legacy/unknown action", source.logMessage)
    }
}
