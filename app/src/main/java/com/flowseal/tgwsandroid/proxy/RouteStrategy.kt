package com.flowseal.tgwsandroid.proxy

/** Persisted and effective routing modes for Telegram WebSocket upstream selection. */
enum class NetworkRouteMode(
    val configValue: String,
    val displayName: String,
) {
    AUTO("auto", "Auto"),
    DIRECT_FIRST("direct_first", "Direct first"),
    CF_FIRST("cf_first", "CF first"),
    CF_ONLY("cf_only", "CF only"),
    ;

    companion object {
        fun fromConfigValue(value: String): NetworkRouteMode =
            entries.firstOrNull { it.configValue.equals(value, ignoreCase = true) } ?: AUTO
    }
}

object RouteStrategy {
    fun resolve(
        configuredMode: NetworkRouteMode,
        networkStatus: String,
    ): NetworkRouteMode = when (configuredMode) {
        NetworkRouteMode.AUTO -> when {
            networkStatus.equals("mobile", ignoreCase = true) -> NetworkRouteMode.CF_FIRST
            networkStatus.equals("cellular", ignoreCase = true) -> NetworkRouteMode.CF_FIRST
            networkStatus.equals("Wi-Fi", ignoreCase = true) -> NetworkRouteMode.DIRECT_FIRST
            networkStatus.equals("wifi", ignoreCase = true) -> NetworkRouteMode.DIRECT_FIRST
            else -> NetworkRouteMode.DIRECT_FIRST
        }
        else -> configuredMode
    }
}
