package com.flowseal.tgwsandroid.service

import android.content.Context
import com.flowseal.tgwsandroid.config.AppConfig
import com.flowseal.tgwsandroid.config.AppConfigStore
import com.flowseal.tgwsandroid.proxy.ProxyServerConfig

<<<<<<< ours
/** Runtime configuration facade backed by persistent AppConfigStore values. */
=======
/** Runtime configuration backed by persisted app settings when Android context is available. */
>>>>>>> theirs
object ProxyRuntimeConfig {
    fun appConfig(context: Context): AppConfig = AppConfigStore.getConfig(context.applicationContext)

    fun proxyServerConfig(context: Context): ProxyServerConfig = proxyServerConfig(appConfig(context))

<<<<<<< ours
    fun endpointSummary(context: Context): String = endpointSummary(appConfig(context))
=======
    @Volatile
    private var config: AppConfig = defaultAppConfig()

    fun initialize(context: Context) {
        config = AppConfigStore.from(context).loadConfig().withValidSecret()
    }

    fun resetSecret(context: Context): AppConfig {
        val updated = AppConfigStore.from(context).resetSecret()
        config = updated
        return updated
    }

    fun appConfig(): AppConfig = config
>>>>>>> theirs

    fun dcSummary(context: Context): String = dcSummary(appConfig(context))

<<<<<<< ours
    fun cfFallbackSummary(context: Context): String = cfFallbackSummary(appConfig(context))

    fun partialSecret(context: Context): String = partialSecret(appConfig(context))

    fun partialTelegramSecret(context: Context): String = partialTelegramSecret(appConfig(context))

    fun telegramProxyUri(context: Context): String = telegramProxyUri(appConfig(context))

    fun telegramProxyUrl(context: Context): String = telegramProxyUrl(appConfig(context))

    internal fun proxyServerConfig(config: AppConfig): ProxyServerConfig = ProxyServerConfig.fromAppConfig(config)

    internal fun endpointSummary(config: AppConfig): String = "${config.host}:${config.port}"

    internal fun dcSummary(config: AppConfig): String {
        val dcIp = config.dcIp
        return dcIp.joinToString(",") { entry ->
            val parts = entry.split(':', limit = 2)
            if (parts.size == 2) parts[0] else entry
        } + " via " + (dcIp.firstOrNull()?.substringAfter(':', "unknown") ?: "unknown")
    }

    internal fun cfFallbackSummary(config: AppConfig): String = if (config.cfproxy) "enabled" else "disabled"

    internal fun partialSecret(config: AppConfig): String = config.secret.toPartialSecret()

    internal fun partialTelegramSecret(config: AppConfig): String = telegramSecretHex(config).toPartialSecret()

    internal fun telegramProxyUri(config: AppConfig): String =
        "tg://proxy?server=${config.host}&port=${config.port}&secret=${telegramSecretHex(config)}"

    internal fun telegramProxyUrl(config: AppConfig): String =
        "https://t.me/proxy?server=${config.host}&port=${config.port}&secret=${telegramSecretHex(config)}"

    internal fun telegramSecretHex(config: AppConfig): String = "dd${config.secret.lowercase()}"

    private fun String.toPartialSecret(): String = "${take(4)}...${takeLast(4)}"
=======
    fun endpointSummary(): String = "${config.host}:${config.port}"

    fun dcSummary(): String = config.dcIp.joinToString(",") { entry ->
        val parts = entry.split(':', limit = 2)
        if (parts.size == 2) parts[0] else entry
    } + " via " + (config.dcIp.firstOrNull()?.substringAfter(':', "unknown") ?: "unknown")

    fun cfFallbackSummary(): String = if (config.cfproxy) "enabled" else "disabled"

    fun partialSecret(): String = "${config.secret.take(4)}...${config.secret.takeLast(4)}"

    fun telegramSecretHex(): String = AppConfigStore.telegramSecret(config.secret)

    fun partialTelegramSecret(): String = "${telegramSecretHex().take(4)}...${telegramSecretHex().takeLast(4)}"

    fun telegramProxyUri(): String =
        "tg://proxy?server=${config.host}&port=${config.port}&secret=${telegramSecretHex()}"

    fun telegramProxyUrl(): String =
        "https://t.me/proxy?server=${config.host}&port=${config.port}&secret=${telegramSecretHex()}"

    private fun defaultAppConfig(): AppConfig =
        AppConfig(
            host = HOST,
            port = PORT,
            secret = SECRET_HEX,
            dcIp = dcIp,
            verbose = VERBOSE,
            bufKb = BUF_KB,
            poolSize = POOL_SIZE,
            cfproxy = CFPROXY_ENABLED,
        )

    private fun AppConfig.withValidSecret(): AppConfig =
        if (AppConfigStore.isValidSecretHex(secret)) this else copy(secret = SECRET_HEX)
>>>>>>> theirs
}
