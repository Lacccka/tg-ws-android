package com.flowseal.tgwsandroid.telemetry

import com.flowseal.tgwsandroid.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TelemetryClient(
    private val endpoint: String = BuildConfig.TELEMETRY_ENDPOINT,
    private val token: String = BuildConfig.TELEMETRY_TOKEN,
    private val timeoutMs: Int = 10_000,
) {
    fun postJson(body: JSONObject): Boolean = runCatching {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Telemetry-Token", token)
        }
        connection.outputStream.use { it.write(TelemetryRedactor.redact(body).toString().toByteArray(Charsets.UTF_8)) }
        val ok = connection.responseCode in 200..299
        connection.disconnect()
        ok
    }.getOrDefault(false)
}
