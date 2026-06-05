package com.flowseal.tgwsandroid

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

class BatterySettingsNavigator(
    private val context: Context,
    private val activityStarter: (Intent) -> Boolean = { intent -> context.tryStartBatterySettingsActivity(intent) },
) {
    fun open(): Boolean {
        val packageName = context.packageName
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val candidates = BatterySettingsIntentPlan.candidates(packageName, manufacturer)
        for (candidate in candidates) {
            if (activityStarter(candidate.toIntent())) return true
        }
        return false
    }

    private fun BatterySettingsIntentSpec.toIntent(): Intent {
        val intent = if (className != null) {
            Intent(action).setClassName(packageName, className)
        } else {
            Intent(action)
        }
        dataPackageName?.let { intent.data = Uri.parse("package:$it") }
        extras.forEach { (key, value) -> intent.putExtra(key, value) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }
}

data class BatterySettingsIntentSpec(
    val action: String,
    val packageName: String? = null,
    val className: String? = null,
    val dataPackageName: String? = null,
    val extras: Map<String, String> = emptyMap(),
)

object BatterySettingsIntentPlan {
    private const val MIUI_POWER_KEEPER_PACKAGE = "com.miui.powerkeeper"
    private const val MIUI_HIDDEN_APPS_ACTIVITY = "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
    private const val MIUI_AUTO_START_PACKAGE = "com.miui.securitycenter"
    private const val MIUI_AUTO_START_ACTIVITY = "com.miui.permcenter.autostart.AutoStartManagementActivity"
    private const val MIUI_EXTRA_PACKAGE_NAME = "package_name"
    private const val MIUI_EXTRA_PACKAGE_LABEL = "package_label"

    fun candidates(packageName: String, manufacturer: String): List<BatterySettingsIntentSpec> = buildList {
        add(appDetails(packageName))
        if (isXiaomiFamily(manufacturer)) {
            addAll(xiaomiCandidates(packageName))
        }
        add(ignoreBatteryOptimizationSettings())
    }

    fun appDetails(packageName: String): BatterySettingsIntentSpec = BatterySettingsIntentSpec(
        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        dataPackageName = packageName,
    )

    fun ignoreBatteryOptimizationSettings(): BatterySettingsIntentSpec = BatterySettingsIntentSpec(
        action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
    )

    fun isXiaomiFamily(manufacturer: String): Boolean {
        val normalized = manufacturer.lowercase(Locale.US)
        return normalized.contains("xiaomi") || normalized.contains("redmi") || normalized.contains("poco")
    }

    fun xiaomiCandidates(packageName: String): List<BatterySettingsIntentSpec> = listOf(
        BatterySettingsIntentSpec(
            action = Intent.ACTION_MAIN,
            packageName = MIUI_POWER_KEEPER_PACKAGE,
            className = MIUI_HIDDEN_APPS_ACTIVITY,
            extras = mapOf(
                MIUI_EXTRA_PACKAGE_NAME to packageName,
                MIUI_EXTRA_PACKAGE_LABEL to "TG Proxy",
            ),
        ),
        BatterySettingsIntentSpec(
            action = Intent.ACTION_MAIN,
            packageName = MIUI_AUTO_START_PACKAGE,
            className = MIUI_AUTO_START_ACTIVITY,
        ),
    )
}

private fun Context.tryStartBatterySettingsActivity(intent: Intent): Boolean = try {
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
} catch (_: IllegalArgumentException) {
    false
}
