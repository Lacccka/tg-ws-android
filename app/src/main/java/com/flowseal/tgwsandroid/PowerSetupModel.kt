package com.flowseal.tgwsandroid

import android.os.Build

enum class BatteryOptimizationStatus { Granted, NeedsAction, UnsupportedOrUnknown }
enum class BackgroundRestrictionStatus { Allowed, Restricted, UnsupportedOrUnknown }
enum class AppBootPreferenceStatus { EnabledInApp, DisabledInApp }
enum class OemAutostartStatus { Unknown, NeedsManualCheck, OpenedSettingsButNotVerified, NotSupportedOrUnavailable }
enum class QuickSettingsTileStatus { Available, AlreadyAddedOrLikelyAvailable, NeedsAction, Unsupported, Unknown }
enum class PowerSettingsAction { Battery, Autostart, BackgroundData, AppSettings, QuickSettingsTile, None }
enum class PowerSettingsOpenResult { Exact, Fallback, Failed, AlreadyGranted }

data class PowerSetupStatus(
    val batteryOptimization: BatteryOptimizationStatus,
    val backgroundRestriction: BackgroundRestrictionStatus,
    val appBootPreference: AppBootPreferenceStatus,
    val oemAutostart: OemAutostartStatus,
    val quickSettingsTile: QuickSettingsTileStatus,
)

object PowerSetupUiMapper {
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
        QuickSettingsTileStatus.Available -> "Можно добавить"
        QuickSettingsTileStatus.AlreadyAddedOrLikelyAvailable -> "Уже добавлено"
        QuickSettingsTileStatus.NeedsAction -> "Откройте шторку и добавьте TG WS"
        QuickSettingsTileStatus.Unsupported -> "Недоступно на этой версии Android"
        QuickSettingsTileStatus.Unknown -> "Неизвестно"
    }

    fun quickSettingsStatusForSdk(sdk: Int): QuickSettingsTileStatus = when {
        sdk >= Build.VERSION_CODES.TIRAMISU -> QuickSettingsTileStatus.Available
        sdk >= Build.VERSION_CODES.N -> QuickSettingsTileStatus.NeedsAction
        else -> QuickSettingsTileStatus.Unsupported
    }
}
