package com.flowseal.tgwsandroid.service

import com.flowseal.tgwsandroid.config.AppConfig
import com.flowseal.tgwsandroid.proxy.ProxyServerConfig

/** Pure Kotlin runtime configuration used by the first Android smoke-test UI/service milestone. */
object ProxyRuntimeConfig {
    const val HOST = "127.0.0.1"
    const val PORT = 1443
    const val SECRET_HEX = "4014e15dd34e4b05c42413eab68c3da8"
    const val TELEGRAM_SECRET_HEX = "dd4014e15dd34e4b05c42413eab68c3da8"
    const val BUF_KB = 256
    const val POOL_SIZE = 4
    const val CFPROXY_ENABLED = true
    const val VERBOSE = true

    val dcIp: List<String> = listOf("2:149.154.167.220", "3:149.154.167.220", "4:149.154.167.220")

    fun appConfig(): AppConfig =
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

    fun proxyServerConfig(): ProxyServerConfig = ProxyServerConfig.fromAppConfig(appConfig())

    fun endpointSummary(): String = "$HOST:$PORT"

    fun dcSummary(): String = dcIp.joinToString(",") { entry ->
        val parts = entry.split(':', limit = 2)
        if (parts.size == 2) parts[0] else entry
    } + " via " + (dcIp.firstOrNull()?.substringAfter(':', "unknown") ?: "unknown")

    fun partialSecret(): String = "${SECRET_HEX.take(4)}...${SECRET_HEX.takeLast(4)}"

    fun partialTelegramSecret(): String = "${TELEGRAM_SECRET_HEX.take(4)}...${TELEGRAM_SECRET_HEX.takeLast(4)}"

    fun telegramProxyUri(): String =
        "tg://proxy?server=$HOST&port=$PORT&secret=$TELEGRAM_SECRET_HEX"

    fun telegramProxyUrl(): String =
        "https://t.me/proxy?server=$HOST&port=$PORT&secret=$TELEGRAM_SECRET_HEX"
}
