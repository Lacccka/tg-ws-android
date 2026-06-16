package com.flowseal.tgwsandroid.telemetry

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TelemetryRedactorTest {
    @Test
    fun `redacts blocked sensitive keys recursively`() {
        val body = JSONObject().apply {
            put("install_id", "safe")
            put("secret", "must-not-send")
            put("device", JSONObject().apply {
                put("model", "Pixel")
                put("android_id", "must-not-send")
                put("ssid", "must-not-send")
            })
            put("events", JSONArray().put(JSONObject().apply {
                put("name", "test_telemetry")
                put("payload", JSONObject().apply {
                    put("source", "android_app")
                    put("proxy_link", "must-not-send")
                    put("raw_logs", "must-not-send")
                    put("real_ip", "must-not-send")
                })
            }))
        }

        val redacted = TelemetryRedactor.redact(body)
        val device = redacted.getJSONObject("device")
        val payload = redacted.getJSONArray("events").getJSONObject(0).getJSONObject("payload")

        assertEquals("safe", redacted.getString("install_id"))
        assertEquals("Pixel", device.getString("model"))
        assertFalse(redacted.has("secret"))
        assertFalse(device.has("android_id"))
        assertFalse(device.has("ssid"))
        assertFalse(payload.has("proxy_link"))
        assertFalse(payload.has("raw_logs"))
        assertFalse(payload.has("real_ip"))
    }
}
