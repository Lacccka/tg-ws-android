package com.flowseal.tgwsandroid

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    }

    @Test
    fun xiaomiCandidatesAreBestEffortAfterStandardAppSettings() {
        val candidates = BatterySettingsIntentPlan.candidates("com.flowseal.tgwsandroid", "Xiaomi")

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, candidates[0].action)
        assertEquals("com.miui.powerkeeper", candidates[1].packageName)
        assertEquals("com.miui.securitycenter", candidates[2].packageName)
    }

    @Test
    fun xiaomiFamilyDetectionIncludesRedmiAndPoco() {
        assertTrue(BatterySettingsIntentPlan.isXiaomiFamily("Xiaomi"))
        assertTrue(BatterySettingsIntentPlan.isXiaomiFamily("Redmi"))
        assertTrue(BatterySettingsIntentPlan.isXiaomiFamily("POCO"))
        assertFalse(BatterySettingsIntentPlan.isXiaomiFamily("Google"))
    }
}
