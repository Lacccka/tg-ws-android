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
        assertOrderedBefore(
            candidates,
            { it.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS },
            { it.action == Settings.ACTION_BATTERY_SAVER_SETTINGS },
        )
        assertEquals(Settings.ACTION_SETTINGS, candidates.last().action)
    }

    @Test
    fun xiaomiBatteryPlanKeepsAppSpecificBatteryBeforeStandardFallbacks() {
        val candidates = BatterySettingsIntentPlan.candidates("com.flowseal.tgwsandroid", "Xiaomi")

        val appBattery = candidates[0]
        assertEquals("com.miui.powerkeeper", appBattery.packageName)
        assertEquals("com.miui.powerkeeper.ui.HiddenAppsConfigActivity", appBattery.className)
        assertEquals(Intent.ACTION_MAIN, appBattery.action)
        assertEquals("com.flowseal.tgwsandroid", appBattery.extras["package_name"])

        assertOrderedBefore(
            candidates,
            { it.packageName == "com.miui.powerkeeper" && it.className == "com.miui.powerkeeper.ui.HiddenAppsConfigActivity" },
            { it.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS },
        )
        assertOrderedBefore(
            candidates,
            { it.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS },
            { it.packageName == "com.miui.securitycenter" && it.className == "com.miui.permcenter.autostart.AutoStartManagementActivity" },
        )
    }

    @Test
    fun xiaomiBatteryButtonDoesNotPreferAutostart() {
        val candidates = BatterySettingsIntentPlan.candidates("com.flowseal.tgwsandroid", "Redmi")
        val autostartIndex = candidates.indexOfFirst {
            it.packageName == "com.miui.securitycenter" &&
                it.className == "com.miui.permcenter.autostart.AutoStartManagementActivity"
        }

        assertTrue(autostartIndex > 0)
        assertFalse("Autostart must not be the first battery-settings candidate", autostartIndex == 0)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, candidates[autostartIndex - 3].action)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, candidates[autostartIndex - 2].action)
        assertEquals(Settings.ACTION_BATTERY_SAVER_SETTINGS, candidates[autostartIndex - 1].action)
    }

    @Test
    fun appDetailsFallbackIsBeforeAutostartWhenMiuiBatteryScreenIsUnavailable() {
        val candidates = BatterySettingsIntentPlan.candidates("com.flowseal.tgwsandroid", "POCO")
        val appDetailsIndex = candidates.indexOfFirst { it.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS }
        val autostartIndex = candidates.indexOfFirst {
            it.packageName == "com.miui.securitycenter" &&
                it.className == "com.miui.permcenter.autostart.AutoStartManagementActivity"
        }

        assertTrue(appDetailsIndex >= 0)
        assertTrue(autostartIndex >= 0)
        assertTrue("App details must be tried before MIUI autostart fallback", appDetailsIndex < autostartIndex)
        assertEquals("com.flowseal.tgwsandroid", candidates[appDetailsIndex].dataPackageName)
    }

    @Test
    fun xiaomiSpecsUseValidPackageAndClassShape() {
        val spec = BatterySettingsIntentPlan.xiaomiAppBatteryCandidates("com.flowseal.tgwsandroid").first()

        assertEquals("com.miui.powerkeeper", spec.packageName)
        assertEquals("com.miui.powerkeeper.ui.HiddenAppsConfigActivity", spec.className)
        assertNull(spec.dataPackageName)
    }

    @Test
    fun xiaomiAutostartIsSeparateFallbackCandidate() {
        val spec = BatterySettingsIntentPlan.xiaomiAutostartCandidate()

        assertEquals(Intent.ACTION_MAIN, spec.action)
        assertEquals("com.miui.securitycenter", spec.packageName)
        assertEquals("com.miui.permcenter.autostart.AutoStartManagementActivity", spec.className)
        assertTrue(spec.extras.isEmpty())
    }

    @Test
    fun helperTextDiffersForXiaomiAndGenericDevices() {
        assertTrue(SettingsUiText.BATTERY_XIAOMI_AUTOSTART_TEXT.contains("автозапуск"))
        assertTrue(SettingsUiText.BATTERY_XIAOMI_AUTOSTART_TEXT.contains("Питание"))
        assertTrue(SettingsUiText.BATTERY_XIAOMI_AUTOSTART_TEXT.contains("Без ограничений"))
        assertFalse(SettingsUiText.BATTERY_BUTTON_HELP_TEXT.contains("автозапуск"))
        assertTrue(SettingsUiText.BATTERY_BUTTON_HELP_TEXT.contains("Питание"))
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

    private fun assertOrderedBefore(
        candidates: List<BatterySettingsIntentSpec>,
        first: (BatterySettingsIntentSpec) -> Boolean,
        second: (BatterySettingsIntentSpec) -> Boolean,
    ) {
        val firstIndex = candidates.indexOfFirst(first)
        val secondIndex = candidates.indexOfFirst(second)

        assertTrue(firstIndex >= 0)
        assertTrue(secondIndex >= 0)
        assertTrue(firstIndex < secondIndex)
    }
}
