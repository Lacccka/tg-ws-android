package com.flowseal.tgwsandroid.service

import android.content.Context
import com.flowseal.tgwsandroid.config.AppConfig
import com.flowseal.tgwsandroid.config.AppConfigStore
import com.flowseal.tgwsandroid.proxy.ProxyServerConfig

/** Runtime configuration backed by persisted app settings when Android context is available. */
object ProxyRuntimeConfig {
    private const val FALLBACK_SECRET_HEX = "4014e15dd34e4b05c42413eab68c3da8"

    @Volatile
    private var config: AppConfig = defaultAppConfig()

    fun initialize(context: Context) {
        config = AppConfigStore.from(context).loadConfig().withValidSecret()
    }

    fun resetSecret(context: Context): AppConfig {
        val updated = AppConfigStore.from(context).resetSecret().withValidSecret()
        config = updated
        return updated
    }

    fun appConfig(context: Context): AppConfig = AppConfigStore.getConfig(context.applicationContext).withValidSecret().also {
        config = it
    }

    fun appConfig(): AppConfig = config

    fun proxyServerConfig(context: Context, networkStatus: String = "unknown"): ProxyServerConfig =
        proxyServerConfig(appConfig(context), networkStatus)

    internal fun proxyServerConfig(config: AppConfig, networkStatus: String = "unknown"): ProxyServerConfig =
        ProxyServerConfig.fromAppConfig(config, networkStatus)

    fun endpointSummary(context: Context): String = endpointSummary(appConfig(context))

    fun endpointSummary(): String = endpointSummary(config)

    internal fun endpointSummary(config: AppConfig): String = "${config.host}:${config.port}"

    fun dcSummary(context: Context): String = dcSummary(appConfig(context))

    fun dcSummary(): String = dcSummary(config)

    internal fun dcSummary(config: AppConfig): String {
        val dcIp = config.dcIp
        return dcIp.joinToString(",") { entry ->
            val parts = entry.split(':', limit = 2)
            if (parts.size == 2) parts[0] else entry
        } + " via " + (dcIp.firstOrNull()?.substringAfter(':', "unknown") ?: "unknown")
    }

    fun routeModeSummary(context: Context, networkStatus: String = "unknown"): String =
        routeModeSummary(appConfig(context), networkStatus)

    fun routeModeSummary(networkStatus: String = "unknown"): String = routeModeSummary(config, networkStatus)

    internal fun routeModeSummary(config: AppConfig, networkStatus: String = "unknown"): String {
        val serverConfig = proxyServerConfig(config, networkStatus)
        return "${config.routeMode.displayName} (effective ${serverConfig.effectiveRouteMode.displayName})"
    }

    fun cfFallbackSummary(context: Context): String = cfFallbackSummary(appConfig(context))

    fun cfFallbackSummary(): String = cfFallbackSummary(config)

    internal fun cfFallbackSummary(config: AppConfig): String = if (config.cfproxy) "enabled" else "disabled"

    fun partialSecret(context: Context): String = partialSecret(appConfig(context))

    fun partialSecret(): String = partialSecret(config)

    internal fun partialSecret(config: AppConfig): String = config.secret.toPartialSecret()

    fun partialTelegramSecret(context: Context): String = partialTelegramSecret(appConfig(context))

    fun partialTelegramSecret(): String = partialTelegramSecret(config)

    fun secretSource(context: Context): String = AppConfigStore.getSecretSource(context.applicationContext).configValue

    fun proxyLinkCurrent(context: Context): Boolean {
        val inMemoryUri = telegramProxyUri()
        val persistedUri = telegramProxyUri(appConfig(context))
        return inMemoryUri == persistedUri
    }

    internal fun partialTelegramSecret(config: AppConfig): String = telegramSecretHex(config).toPartialSecret()

    fun telegramProxyUri(context: Context): String = telegramProxyUri(appConfig(context))

    fun telegramProxyUri(): String = telegramProxyUri(config)

    internal fun telegramProxyUri(config: AppConfig): String =
        "tg://proxy?server=${config.host}&port=${config.port}&secret=${telegramSecretHex(config)}"

    fun telegramProxyUrl(context: Context): String = telegramProxyUrl(appConfig(context))

    fun telegramProxyUrl(): String = telegramProxyUrl(config)

    internal fun telegramProxyUrl(config: AppConfig): String =
        "https://t.me/proxy?server=${config.host}&port=${config.port}&secret=${telegramSecretHex(config)}"

    fun telegramSecretHex(): String = telegramSecretHex(config)

    internal fun telegramSecretHex(config: AppConfig): String = AppConfigStore.telegramSecret(config.secret)

    private fun defaultAppConfig(): AppConfig = AppConfig(secret = FALLBACK_SECRET_HEX)

    private fun AppConfig.withValidSecret(): AppConfig =
        if (AppConfigStore.isValidSecretHex(secret)) this else copy(secret = FALLBACK_SECRET_HEX)

    private fun String.toPartialSecret(): String = "${take(4)}••••${takeLast(4)}"
}
