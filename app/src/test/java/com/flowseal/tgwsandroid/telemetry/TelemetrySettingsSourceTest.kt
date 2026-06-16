package com.flowseal.tgwsandroid.telemetry

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TelemetrySettingsSourceTest {
    private val source = File("src/main/java/com/flowseal/tgwsandroid/MainActivity.kt").readText()

    @Test fun openingSettingsWithTelemetryEnabledDoesNotAutoSendTelemetry() {
        val settingsBlock = source.substringAfter("private fun buildSettingsScreen()").substringBefore("telemetryTestButton =")
        assertFalse(settingsBlock.contains("sendTestTelemetry()"))
    }

    @Test fun settingsReloadDoesNotSendDuplicateTestTelemetry() {
        val refreshBlock = source.substringAfter("private fun refreshState()").substringBefore("private fun")
        assertFalse(refreshBlock.contains("sendTestTelemetry()"))
    }

    @Test fun clickingSendTestTelemetryIsOnlyManualSender() {
        assertTrue(source.contains("telemetryTestButton = createButton(\"Отправить тестовую телеметрию\") { sendTestTelemetry() }"))
        assertEquals(1, Regex("createButton\\(\\\"Отправить тестовую телеметрию\\\"\\) \\{ sendTestTelemetry\\(\\) \\}").findAll(source).count())
    }

    @Test fun telemetryDisabledBlocksManualSendBeforeNetworkCall() {
        val sendBlock = source.substringAfter("private fun sendTestTelemetry()").substringBefore("thread(name = \"test-telemetry\"")
        assertTrue(sendBlock.contains("!config.telemetryEnabled"))
        assertTrue(sendBlock.contains("return"))
    }
}
