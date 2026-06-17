package com.flowseal.tgwsandroid

import android.app.StatusBarManager
import android.os.Build

enum class BatteryOptimizationStatus { Granted, NeedsAction, UnsupportedOrUnknown }
enum class BackgroundRestrictionStatus { Allowed, Restricted, UnsupportedOrUnknown }
enum class AppBootPreferenceStatus { EnabledInApp, DisabledInApp }
enum class OemAutostartStatus { Unknown, NeedsManualCheck, OpenedSettingsButNotVerified, NotSupportedOrUnavailable }
enum class QuickSettingsTileStatus { NeedsAction, AvailableManualAdd, AlreadyAddedOrLikelyAvailable, Unsupported, Unknown }
enum class PowerSettingsAction { Battery, Autostart, BackgroundData, AppSettings, QuickSettingsTile, None }
enum class PowerSettingsOpenResult { Exact, Fallback, Failed, AlreadyGranted }

data class PowerSetupStatus(
    val batteryOptimization: BatteryOptimizationStatus,
    val backgroundRestriction: BackgroundRestrictionStatus,
    val appBootPreference: AppBootPreferenceStatus,
    val oemAutostart: OemAutostartStatus,
    val quickSettingsTile: QuickSettingsTileStatus,
)

object QuickSettingsTileAddResultMapper {
    fun shouldPersistAdded(result: Int): Boolean =
        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
            result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
}

object PowerSetupUiMapper {
    fun isBatteryActionEnabled(status: BatteryOptimizationStatus): Boolean = when (status) {
        BatteryOptimizationStatus.Granted,
        BatteryOptimizationStatus.NeedsAction,
        BatteryOptimizationStatus.UnsupportedOrUnknown -> true
    }

    fun batteryStatus(status: BatteryOptimizationStatus): String = when (status) {
        BatteryOptimizationStatus.Granted -> "Без ограничений"
        BatteryOptimizationStatus.NeedsAction -> "Нужно разрешить"
        BatteryOptimizationStatus.UnsupportedOrUnknown -> "Неизвестно"
    }

    fun backgroundStatus(status: BackgroundRestrictionStatus): String = when (status) {
        BackgroundRestrictionStatus.Allowed -> "Разрешены"
        BackgroundRestrictionStatus.Restricted -> "Ограничены"
        BackgroundRestrictionStatus.UnsupportedOrUnknown -> "Неизвестно"
    }

    fun autostartStatus(app: AppBootPreferenceStatus, oem: OemAutostartStatus): String = when {
        oem == OemAutostartStatus.OpenedSettingsButNotVerified -> "Проверьте вручную"
        app == AppBootPreferenceStatus.EnabledInApp -> "В приложении включено, проверьте в системе"
        oem == OemAutostartStatus.NotSupportedOrUnavailable -> "Не удалось открыть"
        else -> "Проверьте в системе"
    }

    fun quickSettingsStatus(status: QuickSettingsTileStatus): String = when (status) {
        QuickSettingsTileStatus.Unsupported -> "Недоступно"
        QuickSettingsTileStatus.NeedsAction -> "Можно добавить"
        QuickSettingsTileStatus.AvailableManualAdd -> "Добавьте вручную"
        QuickSettingsTileStatus.AlreadyAddedOrLikelyAvailable -> "Добавлено"
        QuickSettingsTileStatus.Unknown -> "Неизвестно"
    }

    fun quickSettingsStatusForSdk(sdk: Int, tileAddedOrLikelyAdded: Boolean = false): QuickSettingsTileStatus = when {
        sdk < Build.VERSION_CODES.N -> QuickSettingsTileStatus.Unsupported
        tileAddedOrLikelyAdded -> QuickSettingsTileStatus.AlreadyAddedOrLikelyAvailable
        sdk >= Build.VERSION_CODES.TIRAMISU -> QuickSettingsTileStatus.NeedsAction
        else -> QuickSettingsTileStatus.AvailableManualAdd
    }
}
