package com.flowseal.tgwsandroid

import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import com.flowseal.tgwsandroid.proxy.ProxyServerStats

object RussianUiText {
    const val ROUTE_AUTO_RECOMMENDED = "Автоматически — рекомендуется"
    const val ROUTE_FAST_WIFI = "Быстрый Wi-Fi"
    const val ROUTE_COMPATIBLE = "Совместимый Wi-Fi + Mobile"
}

data class UserRouteModeOption(
    val title: String,
    val description: String,
    val routeMode: NetworkRouteMode,
)

object UserRouteModes {
    val normalOptions: List<UserRouteModeOption> = listOf(
        UserRouteModeOption(
            title = RussianUiText.ROUTE_AUTO_RECOMMENDED,
            description = "Приложение само выбирает лучший маршрут: быстрый на Wi-Fi и совместимый на мобильной сети.",
            routeMode = NetworkRouteMode.AUTO,
        ),
        UserRouteModeOption(
            title = RussianUiText.ROUTE_FAST_WIFI,
            description = "Использует быстрый прямой маршрут. Лучше подходит для Wi-Fi. На мобильной сети может не работать.",
            routeMode = NetworkRouteMode.DIRECT_FIRST,
        ),
        UserRouteModeOption(
            title = RussianUiText.ROUTE_COMPATIBLE,
            description = "Использует совместимый маршрут через резервные домены. Работает на Wi-Fi и мобильной сети, но ping может быть выше.",
            routeMode = NetworkRouteMode.CF_FIRST,
        ),
    )

    fun labelFor(mode: NetworkRouteMode): String = normalOptions.firstOrNull { it.routeMode == mode }?.title
        ?: mode.configValue
}

object DeveloperUiModel {
    const val DEFAULT_DEVELOPER_MODE_ENABLED = false

    val developerActions: List<String> = listOf(
        "Логи",
        "Копировать диагностику",
        "Очистить логи",
        "Копировать ссылку прокси",
        "Подробности маршрута",
        "Состояние резервных доменов",
        "Состояние прямого маршрута",
    )

    fun routeModeValue(mode: NetworkRouteMode, developerModeEnabled: Boolean): String = if (developerModeEnabled) {
        mode.name
    } else {
        UserRouteModes.labelFor(mode)
    }
}

object ConnectionQualityMapper {
    fun quality(
        running: Boolean,
        networkStatus: String,
        stats: ProxyServerStats?,
    ): String {
        if (!running) return "Неактивно"
        if (networkStatus.equals("none", ignoreCase = true)) return "Нет маршрута"
        if (stats == null) return "Неизвестно"
        if (stats.networkNoneEvents > 0 && networkStatus.equals("none", ignoreCase = true)) return "Нет маршрута"
        if (stats.connectionsBad > 0 && stats.connectionsBad >= stats.connectionsTotal.coerceAtLeast(1)) return "Нет маршрута"

        val congestionSignals = stats.cf429Count + stats.cf429BackoffCount + stats.cfCooldownSkips +
            stats.cfConnectQueueTimeouts + stats.cfQueueControlledFailures + stats.cfAllDomainsInCooldownFallbacks
        if (congestionSignals >= 5) return "Перегружено"

        val effective = stats.effectiveRouteMode
        val hasSessionErrors = stats.wsConnectErrors + stats.sessionTimeouts + stats.sessionUnexpectedErrors > 0
        val isWifi = networkStatus.equals("Wi-Fi", ignoreCase = true) || networkStatus.equals("wifi", ignoreCase = true)
        val isMobile = networkStatus.equals("mobile", ignoreCase = true) || networkStatus.equals("cellular", ignoreCase = true)
        val direct = effective.equals(NetworkRouteMode.DIRECT_FIRST.configValue, ignoreCase = true) ||
            effective.equals(NetworkRouteMode.DIRECT_FIRST.name, ignoreCase = true)
        val compatible = effective.equals(NetworkRouteMode.CF_FIRST.configValue, ignoreCase = true) ||
            effective.equals(NetworkRouteMode.CF_ONLY.configValue, ignoreCase = true) ||
            effective.equals(NetworkRouteMode.CF_FIRST.name, ignoreCase = true) ||
            effective.equals(NetworkRouteMode.CF_ONLY.name, ignoreCase = true)

        if (isWifi && direct && !hasSessionErrors) return "Хорошее"
        if (isMobile && compatible) return "Среднее"
        if (!hasSessionErrors && stats.connectionsTotal > 0) return "Хорошее"
        return "Неизвестно"
    }
}
