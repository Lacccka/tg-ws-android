package com.flowseal.tgwsandroid.telemetry

import android.content.Context
import android.os.Build
import com.flowseal.tgwsandroid.BuildConfig
import org.json.JSONObject

data class DeviceContext(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val androidSdk: Int,
    val androidRelease: String,
    val securityPatch: String,
    val appVersion: String,
    val buildType: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("manufacturer", manufacturer)
        put("brand", brand)
        put("model", model)
        put("android_sdk", androidSdk)
        put("android_release", androidRelease)
        put("security_patch", securityPatch)
        put("app_version", appVersion)
        put("build_type", buildType)
    }

    companion object {
        fun collect(context: Context): DeviceContext = DeviceContext(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidSdk = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH.orEmpty() else "",
            appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty(),
            buildType = BuildConfig.BUILD_TYPE,
        )
    }
}
