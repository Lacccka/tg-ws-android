package com.flowseal.tgwsandroid.telemetry

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

data class BatteryRestrictionDiagnostics(
    val ignoringBatteryOptimizations: Boolean? = null,
    val backgroundRestricted: Boolean? = null,
) {
    val restrictionDetected: Boolean
        get() = backgroundRestricted == true || ignoringBatteryOptimizations == false

    companion object {
        fun collect(context: Context): BatteryRestrictionDiagnostics {
            val appContext = context.applicationContext
            val ignoring = runCatching {
                appContext.getSystemService(PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(appContext.packageName)
            }.getOrNull()
            val backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    appContext.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
                }.getOrNull()
            } else {
                null
            }
            return BatteryRestrictionDiagnostics(
                ignoringBatteryOptimizations = ignoring,
                backgroundRestricted = backgroundRestricted,
            )
        }
    }
}
