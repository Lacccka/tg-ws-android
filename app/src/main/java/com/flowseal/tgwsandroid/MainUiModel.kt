package com.flowseal.tgwsandroid

import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import com.flowseal.tgwsandroid.proxy.ProxyServerStats

object RussianUiText {
    const val ROUTE_AUTO_RECOMMENDED = "Автоматически — рекомендуется"
    const val ROUTE_FAST_WIFI = "Быстрый Wi-Fi"
    const val ROUTE_COMPATIBLE = "Совместимый Wi-Fi + мобильная сеть"
    const val ROUTE_AUTO_SELECTION = "Автоматический выбор"
    const val ROUTE_COMPATIBLE_SHORT = "Совместимый"
    const val MOBILE_COMPATIBLE_ROUTE_HELPER = "На мобильной сети используется совместимый маршрут. Ping может быть выше."
}

data class UserRouteModeOption(
    val title: String,
    val description: String,
    val routeMode: NetworkRouteMode,
)


object SettingsUiText {
    const val BATTERY_BACKGROUND_TITLE = "Работа в фоне"
    const val BATTERY_BACKGROUND_TEXT = "Чтобы прокси не останавливался, разрешите приложению работу без ограничений батареи."
    const val BATTERY_XIAOMI_AUTOSTART_TEXT = "На Xiaomi также включите автозапуск для приложения."
    const val BATTERY_BUTTON_HELP_TEXT = "Выберите «Батарея» → «Без ограничений». На Xiaomi также проверьте «Автозапуск»."
    const val QS_TILE_TITLE = "Кнопка в шторке"
    const val QS_TILE_TEXT = "Добавьте «TG Proxy» в быстрые настройки Android, чтобы запускать и останавливать прокси из шторки."
    const val QS_TILE_HELP_BUTTON = "Как добавить"
    const val QS_TILE_HELP_TITLE = "Как добавить кнопку"
    const val QS_TILE_HELP_MESSAGE = "Откройте шторку быстрых настроек, нажмите «Изменить» или значок карандаша, найдите «TG Proxy» и перетащите её наверх."
}

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
            description = "Использует совместимый маршрут через резервные домены. Подходит для Wi-Fi и мобильной сети, но ping может быть выше.",
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

object ConnectionStatusMapper {
    fun status(
        running: Boolean,
        networkStatus: String,
        stats: ProxyServerStats?,
        checking: Boolean = false,
    ): String {
        if (checking) return "Проверяется"
        if (!running) return "Прокси остановлен"
        if (networkStatus.equals("none", ignoreCase = true)) return "Нет сети"
        if (stats == null) return "Проверяется"

        val activeSessions = stats.connectionsActive > 0
        val hasSuccessfulConnection = stats.connectionsTotal > stats.connectionsBad ||
            stats.cfProxyConnections > 0 ||
            stats.directHealthSuccesses > 0
        val hasRouteUsed = !stats.lastRouteUsed.isNullOrBlank() &&
            !stats.lastRouteUsed.equals("none", ignoreCase = true)
        val hasCurrentSessionErrors = stats.wsConnectErrors + stats.sessionTimeouts + stats.sessionUnexpectedErrors > 0
        val allConnectionsFailed = stats.connectionsTotal > 0 && stats.connectionsBad >= stats.connectionsTotal
        val hasFailuresWithoutSuccess = stats.connectionsTotal > 0 &&
            !hasSuccessfulConnection &&
            (stats.connectionsBad > 0 || hasCurrentSessionErrors || stats.directHealthState.equals("unhealthy", ignoreCase = true))

        return when {
            activeSessions -> "Подключён"
            hasSuccessfulConnection -> "Подключён"
            hasRouteUsed && !hasCurrentSessionErrors && !allConnectionsFailed -> "Подключён"
            hasFailuresWithoutSuccess -> "Нестабильное соединение"
            else -> "Ожидает подключения"
        }
    }
}

object HomeRouteLabelMapper {
    fun label(
        networkStatus: String,
        configuredRouteMode: NetworkRouteMode,
        stats: ProxyServerStats?,
        fallbackEffectiveRouteMode: String? = null,
    ): String {
        if (networkStatus.equals("none", ignoreCase = true)) return "Нет сети"

        val effective = stats?.effectiveRouteMode ?: fallbackEffectiveRouteMode
        val routeToShow = when {
            configuredRouteMode == NetworkRouteMode.AUTO && effective.isNullOrBlank() -> null
            configuredRouteMode == NetworkRouteMode.AUTO && effective.equals(NetworkRouteMode.AUTO.configValue, ignoreCase = true) -> null
            configuredRouteMode == NetworkRouteMode.AUTO && effective.equals(NetworkRouteMode.AUTO.name, ignoreCase = true) -> null
            !effective.isNullOrBlank() -> effective
            else -> configuredRouteMode.configValue
        }

        return when {
            routeToShow == null -> RussianUiText.ROUTE_AUTO_SELECTION
            routeToShow.isDirectRoute() -> RussianUiText.ROUTE_FAST_WIFI
            routeToShow.isCompatibleRoute() -> RussianUiText.ROUTE_COMPATIBLE_SHORT
            configuredRouteMode == NetworkRouteMode.AUTO -> RussianUiText.ROUTE_AUTO_SELECTION
            else -> RussianUiText.ROUTE_AUTO_SELECTION
        }
    }

    fun mobileCompatibleHelper(networkStatus: String, routeLabel: String): String? = if (
        (networkStatus.equals("mobile", ignoreCase = true) || networkStatus.equals("cellular", ignoreCase = true)) &&
        routeLabel == RussianUiText.ROUTE_COMPATIBLE_SHORT
    ) {
        RussianUiText.MOBILE_COMPATIBLE_ROUTE_HELPER
    } else {
        null
    }

    private fun String.isDirectRoute(): Boolean = equals(NetworkRouteMode.DIRECT_FIRST.configValue, ignoreCase = true) ||
        equals(NetworkRouteMode.DIRECT_FIRST.name, ignoreCase = true) ||
        equals("direct", ignoreCase = true) ||
        startsWith("direct-", ignoreCase = true)

    private fun String.isCompatibleRoute(): Boolean = equals(NetworkRouteMode.CF_FIRST.configValue, ignoreCase = true) ||
        equals(NetworkRouteMode.CF_ONLY.configValue, ignoreCase = true) ||
        equals(NetworkRouteMode.CF_FIRST.name, ignoreCase = true) ||
        equals(NetworkRouteMode.CF_ONLY.name, ignoreCase = true) ||
        equals("cf", ignoreCase = true) ||
        startsWith("cf-", ignoreCase = true)
}


object SecretUpdatedMessageModel {
    const val MESSAGE = "Секрет обновлён. Подключите Telegram заново."

    fun visibleByDefault(): Boolean = false
    fun visibleAfterRouteModeChange(): Boolean = false
    fun visibleAfterConfirmedUpdateSecret(secretUpdated: Boolean): Boolean = secretUpdated
    fun visibleAfterConsumed(): Boolean = false
}

object PendingRestartModel {
    const val RESTART_WARNING = "Изменения применятся после перезагрузки прокси."
    const val RESTART_DISABLED_HINT = "Доступно после запуска прокси"

    fun pendingAfterRouteModeChange(proxyRunning: Boolean, restartRequiredForRunningProxy: Boolean = true): Boolean =
        proxyRunning && restartRequiredForRunningProxy

    fun restartActionEnabled(proxyRunning: Boolean): Boolean = proxyRunning
}
