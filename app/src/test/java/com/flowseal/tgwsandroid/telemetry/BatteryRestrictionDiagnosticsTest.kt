package com.flowseal.tgwsandroid.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryRestrictionDiagnosticsTest {
    @Test fun optimizedBatteryStateIsRestriction() {
        val snapshot = BatteryRestrictionDiagnostics(ignoringBatteryOptimizations = false, backgroundRestricted = null)

        assertTrue(snapshot.restrictionDetected)
    }

    @Test fun unsupportedApiUnknownFieldsDoNotCrashOrSetRestriction() {
        val snapshot = BatteryRestrictionDiagnostics(ignoringBatteryOptimizations = null, backgroundRestricted = null)

        assertFalse(snapshot.restrictionDetected)
    }
}
