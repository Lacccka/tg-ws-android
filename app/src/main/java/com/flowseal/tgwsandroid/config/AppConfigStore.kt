package com.flowseal.tgwsandroid.config

import android.content.Context
import android.content.SharedPreferences
<<<<<<< ours
import java.security.SecureRandom

/** SharedPreferences-backed storage for user-editable proxy runtime config. */
object AppConfigStore {
    private const val PREFS_NAME = "app_config"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"
    private const val KEY_SECRET = "secret"
    private const val KEY_DC_IP = "dc_ip"
    private const val KEY_VERBOSE = "verbose"
    private const val KEY_AUTOSTART = "autostart"
    private const val KEY_BUF_KB = "buf_kb"
    private const val KEY_POOL_SIZE = "pool_size"
    private const val KEY_LOG_MAX_MB = "log_max_mb"
    private const val KEY_CHECK_UPDATES = "check_updates"
    private const val KEY_CFPROXY = "cfproxy"
    private const val KEY_CFPROXY_USER_DOMAIN = "cfproxy_user_domain"
    private const val KEY_CFPROXY_WORKER_DOMAIN = "cfproxy_worker_domain"
    private const val KEY_APPEARANCE = "appearance"

    private val secretRandom = SecureRandom()
    private val secretRegex = Regex("^[0-9a-fA-F]{32}$")

    fun getConfig(context: Context): AppConfig = getConfig(prefs(context))

    fun resetSecret(context: Context): AppConfig = resetSecret(prefs(context))

    fun resetConfig(context: Context): AppConfig = resetConfig(prefs(context))

    internal fun getConfig(prefs: SharedPreferences): AppConfig {
        val secret = prefs.getString(KEY_SECRET, null)
            ?.takeIf(::isValidSecret)
            ?.lowercase()
            ?: generateSecret().also { prefs.edit().putString(KEY_SECRET, it).apply() }

        return AppConfig(
            host = prefs.getString(KEY_HOST, AppConfig.DEFAULT_HOST) ?: AppConfig.DEFAULT_HOST,
            port = prefs.getInt(KEY_PORT, AppConfig.DEFAULT_PORT),
            secret = secret,
            dcIp = prefs.getStringList(KEY_DC_IP, AppConfig.DEFAULT_DC_IP),
            verbose = prefs.getBoolean(KEY_VERBOSE, false),
            autostart = prefs.getBoolean(KEY_AUTOSTART, false),
            bufKb = prefs.getInt(KEY_BUF_KB, AppConfig.DEFAULT_BUF_KB),
            poolSize = prefs.getInt(KEY_POOL_SIZE, AppConfig.DEFAULT_POOL_SIZE),
            logMaxMb = java.lang.Double.longBitsToDouble(
                prefs.getLong(KEY_LOG_MAX_MB, java.lang.Double.doubleToRawLongBits(AppConfig.DEFAULT_LOG_MAX_MB)),
            ),
            checkUpdates = prefs.getBoolean(KEY_CHECK_UPDATES, true),
            cfproxy = prefs.getBoolean(KEY_CFPROXY, true),
            cfproxyUserDomain = prefs.getStringList(KEY_CFPROXY_USER_DOMAIN, emptyList()),
            cfproxyWorkerDomain = prefs.getStringList(KEY_CFPROXY_WORKER_DOMAIN, emptyList()),
            appearance = Appearance.fromConfigValue(
                prefs.getString(KEY_APPEARANCE, Appearance.AUTO.configValue) ?: Appearance.AUTO.configValue,
            ),
        )
    }

    internal fun resetSecret(prefs: SharedPreferences): AppConfig {
        prefs.edit().putString(KEY_SECRET, generateSecret()).apply()
        return getConfig(prefs)
    }

    internal fun resetConfig(prefs: SharedPreferences): AppConfig {
        val secret = generateSecret()
        prefs.edit()
            .clear()
            .putString(KEY_SECRET, secret)
            .putString(KEY_HOST, AppConfig.DEFAULT_HOST)
            .putInt(KEY_PORT, AppConfig.DEFAULT_PORT)
            .putString(KEY_DC_IP, AppConfig.DEFAULT_DC_IP.joinToString("\n"))
            .putBoolean(KEY_VERBOSE, false)
            .putBoolean(KEY_AUTOSTART, false)
            .putInt(KEY_BUF_KB, AppConfig.DEFAULT_BUF_KB)
            .putInt(KEY_POOL_SIZE, AppConfig.DEFAULT_POOL_SIZE)
            .putLong(KEY_LOG_MAX_MB, java.lang.Double.doubleToRawLongBits(AppConfig.DEFAULT_LOG_MAX_MB))
            .putBoolean(KEY_CHECK_UPDATES, true)
            .putBoolean(KEY_CFPROXY, true)
            .putString(KEY_CFPROXY_USER_DOMAIN, "")
            .putString(KEY_CFPROXY_WORKER_DOMAIN, "")
            .putString(KEY_APPEARANCE, Appearance.AUTO.configValue)
            .apply()
        return getConfig(prefs)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun SharedPreferences.getStringList(key: String, defaultValue: List<String>): List<String> =
        getString(key, null)
            ?.split('\n')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?: defaultValue

    private fun isValidSecret(secret: String): Boolean = secretRegex.matches(secret)

    private fun generateSecret(): String {
        val bytes = ByteArray(16)
        secretRandom.nextBytes(bytes)
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
=======
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
        if (stored != null) return stored

        return defaultConfigWithSecret().also(::saveConfig)
    }

    fun saveConfig(config: AppConfig) {
        storage.putString(KEY_CONFIG_JSON, config.toJson().toString())
    }

    fun resetSecret(): AppConfig {
        val current = loadConfig()
        val updated = current.copy(secret = secretGenerator())
        saveConfig(updated)
        return updated
    }

    fun resetConfig(): AppConfig {
        val current = loadConfig()
        val secret = current.secret.takeIf(::isValidSecretHex) ?: secretGenerator()
        val updated = AppConfig(secret = secret)
        saveConfig(updated)
        return updated
    }

    private fun defaultConfigWithSecret(): AppConfig = AppConfig(secret = secretGenerator())

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

        fun isValidSecretHex(secret: String): Boolean = SECRET_REGEX.matches(secret)

        fun telegramSecret(secret: String): String = "dd$secret"
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
}

private fun generateSecretHex(): String {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
>>>>>>> theirs
}
