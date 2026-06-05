package com.flowseal.tgwsandroid

import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatterySettingsNavigatorTest {
    @Test
    fun appDetailsIntentUsesCurrentPackage() {
        val spec = BatterySettingsIntentPlan.appDetails("com.flowseal.tgwsandroid")

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, spec.action)
        assertEquals("com.flowseal.tgwsandroid", spec.dataPackageName)
    }

    @Test
    fun unknownManufacturerFallsBackToStandardAppSettingsFirst() {
        val candidates = BatterySettingsIntentPlan.candidates("com.flowseal.tgwsandroid", "Google")

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, candidates.first().action)
        assertEquals("com.flowseal.tgwsandroid", candidates.first().dataPackageName)
        assertTrue(candidates.any { it.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS })
        assertEquals(Settings.ACTION_SETTINGS, candidates.last().action)
    }

    @Test
    fun xiaomiCandidatesPreferPowerKeeperBeforeStandardAppSettings() {
        val candidates = BatterySettingsIntentPlan.candidates("com.flowseal.tgwsandroid", "Xiaomi")

        assertEquals("com.miui.powerkeeper", candidates[0].packageName)
        assertEquals("com.miui.powerkeeper.ui.HiddenAppsConfigActivity", candidates[0].className)
        assertEquals(Intent.ACTION_MAIN, candidates[0].action)
        assertEquals("com.flowseal.tgwsandroid", candidates[0].extras["package_name"])
        assertEquals("com.miui.securitycenter", candidates[1].packageName)
        assertEquals("com.miui.permcenter.autostart.AutoStartManagementActivity", candidates[1].className)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, candidates[2].action)
        assertEquals("com.flowseal.tgwsandroid", candidates[2].dataPackageName)
    }

    @Test
    fun xiaomiSpecsUseValidPackageAndClassShape() {
        val spec = BatterySettingsIntentPlan.xiaomiCandidates("com.flowseal.tgwsandroid").first()

        assertEquals("com.miui.powerkeeper", spec.packageName)
        assertEquals("com.miui.powerkeeper.ui.HiddenAppsConfigActivity", spec.className)
        assertNull(spec.dataPackageName)
    }

    @Test
    fun missingManufacturerIntentCanBeSkippedByBestEffortPlan() {
        val candidates = BatterySettingsIntentPlan.candidates("com.flowseal.tgwsandroid", "POCO")
        val firstGeneric = candidates.first { it.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS }

        assertEquals("com.flowseal.tgwsandroid", firstGeneric.dataPackageName)
    }

    @Test
    fun helperTextDiffersForXiaomiAndGenericDevices() {
        assertTrue(SettingsUiText.BATTERY_XIAOMI_AUTOSTART_TEXT.contains("автозапуск"))
        assertTrue(SettingsUiText.BATTERY_XIAOMI_AUTOSTART_TEXT.contains("Без ограничений"))
        assertFalse(SettingsUiText.BATTERY_BUTTON_HELP_TEXT.contains("автозапуск"))
        assertTrue(SettingsUiText.BATTERY_BUTTON_HELP_TEXT.contains("Без ограничений"))
    }

    @Test
    fun xiaomiFamilyDetectionIncludesRedmiPocoHyperOsAndMiui() {
        assertTrue(BatterySettingsIntentPlan.isXiaomiFamily("Xiaomi"))
        assertTrue(BatterySettingsIntentPlan.isXiaomiFamily("Redmi"))
        assertTrue(BatterySettingsIntentPlan.isXiaomiFamily("POCO"))
        assertTrue(BatterySettingsIntentPlan.isXiaomiFamily("HyperOS"))
        assertTrue(BatterySettingsIntentPlan.isXiaomiFamily("MIUI"))
        assertFalse(BatterySettingsIntentPlan.isXiaomiFamily("Google"))
    }
}
