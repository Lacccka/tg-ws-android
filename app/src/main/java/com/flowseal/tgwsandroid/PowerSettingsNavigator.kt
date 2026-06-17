package com.flowseal.tgwsandroid

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale

class PowerSettingsNavigator(
    private val context: Context,
    private val starter: (Intent) -> Boolean = { intent -> context.safeStartPowerIntent(intent) },
) {
    fun openAppSettings(): PowerSettingsOpenResult = openFallback(appSettingsIntent(context.packageName))

    fun openBatteryOptimizationSettings(): PowerSettingsOpenResult =
        openExactOrFallback(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appSettingsIntent(context.packageName))

    fun requestIgnoreBatteryOptimizations(activity: Activity): PowerSettingsOpenResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return PowerSettingsOpenResult.AlreadyGranted
        val powerManager = activity.getSystemService(PowerManager::class.java)
        if (powerManager?.isIgnoringBatteryOptimizations(activity.packageName) == true) return PowerSettingsOpenResult.AlreadyGranted
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))
        return openExactOrFallback(request, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appSettingsIntent(activity.packageName))
    }

    fun openBackgroundDataSettings(): PowerSettingsOpenResult {
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, Uri.parse("package:${context.packageName}"))
        } else null
        return if (exact == null) openFallback(appSettingsIntent(context.packageName)) else openExactOrFallback(exact, appSettingsIntent(context.packageName))
    }

    fun openBatterySaverSettings(): PowerSettingsOpenResult =
        openExactOrFallback(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), appSettingsIntent(context.packageName))

    fun openAutostartSettings(): PowerSettingsOpenResult {
        for (intent in AutostartIntentPlan.candidates(Build.MANUFACTURER.orEmpty())) {
            if (starter(intent)) return PowerSettingsOpenResult.Exact
        }
        return openFallback(appSettingsIntent(context.packageName))
    }

    private fun openExactOrFallback(exact: Intent, vararg fallbacks: Intent): PowerSettingsOpenResult {
        if (starter(exact)) return PowerSettingsOpenResult.Exact
        for (fallback in fallbacks) if (starter(fallback)) return PowerSettingsOpenResult.Fallback
        return PowerSettingsOpenResult.Failed
    }

    private fun openFallback(fallback: Intent): PowerSettingsOpenResult =
        if (starter(fallback)) PowerSettingsOpenResult.Fallback else PowerSettingsOpenResult.Failed

    companion object {
        fun appSettingsIntent(packageName: String): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    }
}

object AutostartIntentPlan {
    fun candidates(manufacturer: String): List<Intent> {
        val m = manufacturer.lowercase(Locale.US)
        return when {
            listOf("xiaomi", "redmi", "poco", "miui", "hyperos").any(m::contains) -> listOf(
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                component("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
            )
            listOf("huawei", "honor").any(m::contains) -> listOf(
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            )
            listOf("oppo", "realme").any(m::contains) -> listOf(
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            )
            listOf("vivo", "iqoo").any(m::contains) -> listOf(
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            )
            m.contains("oneplus") -> listOf(component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
            m.contains("samsung") -> listOf(component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))
            m.contains("asus") -> listOf(component("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity"))
            else -> emptyList()
        }
    }

    private fun component(pkg: String, cls: String): Intent = Intent(Intent.ACTION_MAIN).setClassName(pkg, cls)
}

private fun Context.safeStartPowerIntent(intent: Intent): Boolean = try {
    if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(packageManager) == null) false else {
        startActivity(intent)
        true
    }
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
} catch (_: IllegalArgumentException) {
    false
}
