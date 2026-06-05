package com.flowseal.tgwsandroid.config

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

class AppConfigStore(
    private val storage: Storage,
    private val secretGenerator: () -> String = ::generateSecretHex,
) {
    fun loadConfig(): AppConfig {
        val stored = storage.getString(KEY_CONFIG_JSON)?.let { raw ->
            runCatching { AppConfig.fromJson(JSONObject(raw)) }.getOrNull()
        }
        if (stored != null) {
            return stored.withStoredValidSecret()
        }

        return defaultConfigWithSecret().also(::saveConfig)
    }

    fun saveConfig(config: AppConfig) {
        storage.putString(KEY_CONFIG_JSON, config.toJson().toString())
    }

    fun resetSecret(): AppConfig {
        val current = loadConfig()
        val updated = current.copy(secret = secretGenerator().lowercase())
        saveConfig(updated)
        return updated
    }

    fun resetConfig(): AppConfig {
        val current = loadConfig()
        val secret = current.secret
            .takeIf(::isValidSecretHex)
            ?.lowercase()
            ?: secretGenerator().lowercase()
        val updated = AppConfig(secret = secret)
        saveConfig(updated)
        return updated
    }

    private fun defaultConfigWithSecret(): AppConfig = AppConfig(secret = secretGenerator().lowercase())

    private fun AppConfig.withStoredValidSecret(): AppConfig {
        val normalizedSecret = secret.takeIf(::isValidSecretHex)?.lowercase() ?: secretGenerator().lowercase()
        return if (normalizedSecret == secret) this else copy(secret = normalizedSecret).also(::saveConfig)
    }

    interface Storage {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
    }

    private class SharedPreferencesStorage(
        private val preferences: SharedPreferences,
    ) : Storage {
        override fun getString(key: String): String? = preferences.getString(key, null)

        override fun putString(key: String, value: String) {
            preferences.edit().putString(key, value).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "app_config"
        private const val KEY_CONFIG_JSON = "config_json"
        private val SECRET_REGEX = Regex("^[0-9a-fA-F]{32}$")

        fun from(context: Context): AppConfigStore = AppConfigStore(
            SharedPreferencesStorage(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
        )

        fun getConfig(context: Context): AppConfig = from(context).loadConfig()

        fun resetSecret(context: Context): AppConfig = from(context).resetSecret()

        fun resetConfig(context: Context): AppConfig = from(context).resetConfig()

        fun isValidSecretHex(secret: String): Boolean = SECRET_REGEX.matches(secret)

        fun telegramSecret(secret: String): String = "dd${secret.lowercase()}"
    }
}

fun AppConfig.toJson(): JSONObject = JSONObject().apply {
    put("host", host)
    put("port", port)
    put("secret", secret)
    put("dc_ip", JSONArray(dcIp))
    put("verbose", verbose)
    put("autostart", autostart)
    put("buf_kb", bufKb)
    put("pool_size", poolSize)
    put("log_max_mb", logMaxMb)
    put("check_updates", checkUpdates)
    put("cfproxy", cfproxy)
    put("cfproxy_user_domain", JSONArray(cfproxyUserDomain))
    put("cfproxy_worker_domain", JSONArray(cfproxyWorkerDomain))
    put("appearance", appearance.configValue)
    put("route_mode", routeMode.configValue)
    put("routeMode", routeMode.configValue)
}

private fun generateSecretHex(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
