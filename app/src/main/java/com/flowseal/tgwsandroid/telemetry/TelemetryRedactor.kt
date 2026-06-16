package com.flowseal.tgwsandroid.telemetry

import org.json.JSONArray
import org.json.JSONObject

object TelemetryRedactor {
    private val blockedKeys = setOf(
        "secret", "proxy_link", "proxyLink", "proxy_url", "proxyUrl", "raw_logs", "rawLogs", "logs",
        "real_ip", "realIp", "ip", "ssid", "bssid", "android_id", "androidId", "imei", "serial", "mac", "mac_address", "macAddress",
        "phone", "phone_number", "phoneNumber", "username", "telegram_username", "carrier", "operator", "operator_name", "carrier_name",
        "iccid", "imsi", "subscriptionId", "subscription_id", "domain", "url", "endpoint", "token", "exception_message", "message",
    )

    fun redact(value: JSONObject): JSONObject = redactObject(value)

    private fun redactObject(source: JSONObject): JSONObject = JSONObject().apply {
        source.keys().forEach { key ->
            if (!blockedKeys.contains(key)) put(key, redactAny(source.opt(key)))
        }
    }

    private fun redactArray(source: JSONArray): JSONArray = JSONArray().apply {
        for (index in 0 until source.length()) put(redactAny(source.opt(index)))
    }

    private fun redactAny(value: Any?): Any? = when (value) {
        is JSONObject -> redactObject(value)
        is JSONArray -> redactArray(value)
        else -> value
    }
}
