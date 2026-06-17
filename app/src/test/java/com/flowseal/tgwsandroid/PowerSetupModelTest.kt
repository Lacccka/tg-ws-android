package com.flowseal.tgwsandroid

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PowerSetupModelTest {
    @Test fun batteryOptimizationGrantedShowsGranted() {
        assertEquals("Без ограничений", PowerSetupUiMapper.batteryStatus(BatteryOptimizationStatus.Granted))
    }

    @Test fun batteryOptimizationNotGrantedShowsNeedsAction() {
        assertEquals("Нужно разрешить", PowerSetupUiMapper.batteryStatus(BatteryOptimizationStatus.NeedsAction))
    }

    @Test fun backgroundRestrictedShowsWarning() {
        assertEquals("Ограничены", PowerSetupUiMapper.backgroundStatus(BackgroundRestrictionStatus.Restricted))
    }

    @Test fun appAutostartEnabledWithOemUnknownIsHonest() {
        assertEquals(
            "В приложении включено, проверьте в системе",
            PowerSetupUiMapper.autostartStatus(AppBootPreferenceStatus.EnabledInApp, OemAutostartStatus.Unknown),
        )
    }

    @Test fun openedAutostartSettingsIsNotEnabled() {
        val status = PowerSetupUiMapper.autostartStatus(AppBootPreferenceStatus.DisabledInApp, OemAutostartStatus.OpenedSettingsButNotVerified)
        assertEquals("Проверьте вручную", status)
        assertNotEquals("Включено", status)
    }

    @Test fun unsupportedOemFallsBackToNoCandidates() {
        assertEquals(0, AutostartIntentPlan.candidates("Google").size)
    }

    @Test fun quickSettingsSupportedShowsSettingsActionState() {
        assertEquals(QuickSettingsTileStatus.Available, PowerSetupUiMapper.quickSettingsStatusForSdk(Build.VERSION_CODES.TIRAMISU))
        assertEquals("Можно добавить", PowerSetupUiMapper.quickSettingsStatus(QuickSettingsTileStatus.Available))
    }

    @Test fun quickSettingsUnsupportedShowsUnavailableOrManualState() {
        assertEquals(QuickSettingsTileStatus.Unsupported, PowerSetupUiMapper.quickSettingsStatusForSdk(Build.VERSION_CODES.M))
        assertEquals("Недоступно на этой версии Android", PowerSetupUiMapper.quickSettingsStatus(QuickSettingsTileStatus.Unsupported))
    }

    @Test fun quickSettingsClickDoesNotBlindlyMarkAdded() {
        assertNotEquals("Уже добавлено", PowerSetupUiMapper.quickSettingsStatus(QuickSettingsTileStatus.Available))
    }
}
