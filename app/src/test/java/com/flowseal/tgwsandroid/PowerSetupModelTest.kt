package com.flowseal.tgwsandroid

import android.app.StatusBarManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSetupModelTest {
    @Test fun batteryOptimizationGrantedShowsGranted() {
        assertEquals("Без ограничений", PowerSetupUiMapper.batteryStatus(BatteryOptimizationStatus.Granted))
    }

    @Test fun batteryOptimizationGrantedRemainsActionable() {
        assertTrue(PowerSetupUiMapper.isBatteryActionEnabled(BatteryOptimizationStatus.Granted))
    }

    @Test fun batteryOptimizationNotGrantedShowsNeedsAction() {
        assertEquals("Нужно разрешить", PowerSetupUiMapper.batteryStatus(BatteryOptimizationStatus.NeedsAction))
    }

    @Test fun batteryOptimizationNotGrantedRemainsActionable() {
        assertTrue(PowerSetupUiMapper.isBatteryActionEnabled(BatteryOptimizationStatus.NeedsAction))
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

    @Test fun quickSettingsApi33WithoutAddedPrefShowsCanAdd() {
        assertEquals(QuickSettingsTileStatus.NeedsAction, PowerSetupUiMapper.quickSettingsStatusForSdk(Build.VERSION_CODES.TIRAMISU, false))
        assertEquals("Можно добавить", PowerSetupUiMapper.quickSettingsStatus(QuickSettingsTileStatus.NeedsAction))
    }

    @Test fun quickSettingsApi33WithAddedPrefShowsAdded() {
        assertEquals(QuickSettingsTileStatus.AlreadyAddedOrLikelyAvailable, PowerSetupUiMapper.quickSettingsStatusForSdk(Build.VERSION_CODES.TIRAMISU, true))
        assertEquals("Добавлено", PowerSetupUiMapper.quickSettingsStatus(QuickSettingsTileStatus.AlreadyAddedOrLikelyAvailable))
    }

    @Test fun quickSettingsApi24To32WithoutAddedPrefShowsManualAdd() {
        assertEquals(QuickSettingsTileStatus.AvailableManualAdd, PowerSetupUiMapper.quickSettingsStatusForSdk(Build.VERSION_CODES.N, false))
        assertEquals("Добавьте вручную", PowerSetupUiMapper.quickSettingsStatus(QuickSettingsTileStatus.AvailableManualAdd))
        assertEquals(QuickSettingsTileStatus.AvailableManualAdd, PowerSetupUiMapper.quickSettingsStatusForSdk(Build.VERSION_CODES.S_V2, false))
    }

    @Test fun quickSettingsUnsupportedShowsUnavailableState() {
        assertEquals(QuickSettingsTileStatus.Unsupported, PowerSetupUiMapper.quickSettingsStatusForSdk(Build.VERSION_CODES.M, false))
        assertEquals("Недоступно", PowerSetupUiMapper.quickSettingsStatus(QuickSettingsTileStatus.Unsupported))
    }

    @Test fun quickSettingsClickDoesNotBlindlyMarkAdded() {
        assertNotEquals("Добавлено", PowerSetupUiMapper.quickSettingsStatus(QuickSettingsTileStatus.NeedsAction))
    }

    @Test fun quickSettingsAddedCallbackPersistsAdded() {
        assertTrue(QuickSettingsTileAddResultMapper.shouldPersistAdded(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED))
    }

    @Test fun quickSettingsAlreadyAddedCallbackPersistsAdded() {
        assertTrue(QuickSettingsTileAddResultMapper.shouldPersistAdded(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED))
    }

    @Test fun quickSettingsNotAddedCallbackDoesNotPersistAdded() {
        assertFalse(QuickSettingsTileAddResultMapper.shouldPersistAdded(StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED))
    }

    @Test fun recommendationCooldownAloneMustNotMarkTileAsAdded() {
        assertEquals(QuickSettingsTileStatus.NeedsAction, PowerSetupUiMapper.quickSettingsStatusForSdk(Build.VERSION_CODES.TIRAMISU, tileAddedOrLikelyAdded = false))
    }
}
