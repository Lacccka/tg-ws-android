package com.flowseal.tgwsandroid.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Mirrors the tray runtime config emitted by upstream
 * `utils/default_config.py::default_tray_config`, normalized by
 * `utils/tray_common.py::apply_proxy_config`, and mapped onto
 * `proxy/config.py::ProxyConfig`.
 */
data class AppConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val secret: String = "",
    val dcIp: List<String> = DEFAULT_DC_IP,
    val verbose: Boolean = false,
    val autostart: Boolean = false,
    val bufKb: Int = DEFAULT_BUF_KB,
    val poolSize: Int = DEFAULT_POOL_SIZE,
    val logMaxMb: Double = DEFAULT_LOG_MAX_MB,
    val checkUpdates: Boolean = true,
    val cfproxy: Boolean = true,
    val cfproxyUserDomain: List<String> = emptyList(),
    val cfproxyWorkerDomain: List<String> = emptyList(),
    val appearance: Appearance = Appearance.AUTO,
) {
    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 1443
        const val DEFAULT_BUF_KB = 256
        const val DEFAULT_POOL_SIZE = 4
        const val DEFAULT_LOG_MAX_MB = 5.0
        val DEFAULT_DC_IP = listOf("2:149.154.167.220", "4:149.154.167.220")

        fun fromJson(json: JSONObject): AppConfig = AppConfig(
            host = json.optString("host", DEFAULT_HOST),
            port = json.optInt("port", DEFAULT_PORT),
            secret = json.optString("secret", ""),
            dcIp = json.optStringList("dc_ip", DEFAULT_DC_IP),
            verbose = json.optBoolean("verbose", false),
            autostart = json.optBoolean("autostart", false),
            bufKb = json.optInt("buf_kb", DEFAULT_BUF_KB),
            poolSize = json.optInt("pool_size", DEFAULT_POOL_SIZE),
            logMaxMb = json.optDouble("log_max_mb", DEFAULT_LOG_MAX_MB),
            checkUpdates = json.optBoolean("check_updates", true),
            cfproxy = json.optBoolean("cfproxy", true),
            cfproxyUserDomain = json.optStringList("cfproxy_user_domain", emptyList()),
            cfproxyWorkerDomain = json.optStringList("cfproxy_worker_domain", emptyList()),
            appearance = Appearance.fromConfigValue(json.optString("appearance", Appearance.AUTO.configValue)),
        )
    }
}

/**
 * Mirrors upstream `ui/ctk_theme.py::apply_ctk_appearance` values as persisted
 * by `ui/ctk_tray_ui.py::validate_config_form` under the `appearance` key.
 */
enum class Appearance(val configValue: String) {
    AUTO("auto"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromConfigValue(value: String): Appearance = entries.firstOrNull {
            it.configValue.equals(value, ignoreCase = true)
        } ?: AUTO
    }
}

private fun JSONObject.optStringList(name: String, defaultValue: List<String>): List<String> {
    if (!has(name) || isNull(name)) {
        return defaultValue
    }

    return when (val value = opt(name)) {
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                value.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
        is String -> value.split(',', ';', ' ', '\n', '\t')
            .map(String::trim)
            .filter(String::isNotEmpty)
        else -> defaultValue
    }
}
