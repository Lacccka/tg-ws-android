package com.flowseal.tgwsandroid.telemetry

import android.content.Context
import com.flowseal.tgwsandroid.config.AppConfig
import org.json.JSONArray
import org.json.JSONObject

object Telemetry {
    fun testEvent(context: Context, config: AppConfig, nowMs: Long = System.currentTimeMillis()): JSONObject = JSONObject().apply {
        put("install_id", InstallIdStore.from(context).getOrCreateInstallId())
        put("sent_at_ms", nowMs)
        put("device", DeviceContext.collect(context).toJson())
        put("events", JSONArray().put(JSONObject().apply {
            put("name", "test_telemetry")
            put("time_ms", nowMs)
            put("payload", JSONObject().apply {
                put("source", "android_app")
                put("telemetry_enabled", config.telemetryEnabled)
            })
        }))
    }

    fun sendTestEvent(context: Context, config: AppConfig): Boolean = TelemetryClient().postJson(testEvent(context, config))
}
