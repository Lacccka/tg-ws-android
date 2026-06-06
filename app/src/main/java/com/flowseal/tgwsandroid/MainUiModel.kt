package com.flowseal.tgwsandroid

import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import com.flowseal.tgwsandroid.proxy.ProxyServerStats

object RussianUiText {
    const val ROUTE_AUTO = "Авто"
    const val ROUTE_AUTO_SUBTITLE = "Рекомендуется"
    const val ROUTE_FAST_WIFI = "Быстрый Wi-Fi"
    const val ROUTE_COMPATIBLE_SHORT = "Совместимый"
    const val ROUTE_AUTO_SELECTION = "Автоматический выбор"
    const val ROUTE_AUTO_HELPER = "Авто выбирает быстрый маршрут на Wi-Fi и совместимый на мобильной сети."
    const val ROUTE_FAST_WIFI_HELPER = "Подходит для Wi-Fi. На мобильной сети может не работать."
    const val ROUTE_COMPATIBLE_HELPER = "Подходит для Wi-Fi и мобильной сети, но ping может быть выше."
    const val MOBILE_COMPATIBLE_ROUTE_HELPER = "На мобильной сети используется совместимый маршрут. Ping может быть выше."
}

data class UserRouteModeOption(
    val title: String,
    val subtitle: String?,
    val routeMode: NetworkRouteMode,
)


object SettingsUiText {
    const val BATTERY_BACKGROUND_TITLE = "Работа в фоне"
    const val BATTERY_XIAOMI_AUTOSTART_TEXT = "Откройте «Питание» или «Батарея» и выберите «Без ограничений». На Xiaomi также проверьте автозапуск."
    const val BATTERY_BUTTON_HELP_TEXT = "Откройте «Питание» или «Батарея» и выберите «Без ограничений»."
    const val QS_TILE_TITLE = "Кнопка в шторке"
    const val QS_TILE_TEXT = "Добавьте «TG Proxy» в быстрые настройки Android, чтобы запускать и останавливать прокси из шторки."
    const val QS_TILE_HELP_BUTTON = "Как добавить"
    const val QS_TILE_HELP_TITLE = "Как добавить кнопку"
    const val QS_TILE_HELP_MESSAGE = "Откройте шторку быстрых настроек, нажмите «Изменить» или значок карандаша, найдите «TG Proxy» и перетащите её наверх."

    fun batteryStatusLine(status: String): String = "Статус: $status"
}

object UserRouteModes {
    val normalOptions: List<UserRouteModeOption> = listOf(
        UserRouteModeOption(
            title = RussianUiText.ROUTE_AUTO,
            subtitle = RussianUiText.ROUTE_AUTO_SUBTITLE,
            routeMode = NetworkRouteMode.AUTO,
        ),
        UserRouteModeOption(
            title = RussianUiText.ROUTE_FAST_WIFI,
            subtitle = null,
            routeMode = NetworkRouteMode.DIRECT_FIRST,
        ),
        UserRouteModeOption(
            title = RussianUiText.ROUTE_COMPATIBLE_SHORT,
            subtitle = null,
            routeMode = NetworkRouteMode.CF_FIRST,
        ),
    )

    fun labelFor(mode: NetworkRouteMode): String = normalOptions.firstOrNull { it.routeMode == mode }?.title
        ?: mode.configValue

    fun buttonText(option: UserRouteModeOption, selected: Boolean): String = buildString {
        if (selected) append("✓ ")
        append(option.title)
        if (option.subtitle != null) append("\n").append(option.subtitle)
    }

    fun helperFor(mode: NetworkRouteMode): String = when (mode) {
        NetworkRouteMode.AUTO -> RussianUiText.ROUTE_AUTO_HELPER
        NetworkRouteMode.DIRECT_FIRST -> RussianUiText.ROUTE_FAST_WIFI_HELPER
        NetworkRouteMode.CF_FIRST -> RussianUiText.ROUTE_COMPATIBLE_HELPER
        NetworkRouteMode.CF_ONLY -> RussianUiText.ROUTE_COMPATIBLE_HELPER
    }
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

object TelegramStatusUiText {
    const val RECONNECT_STATUS = "Нужно переподключить Telegram"
    const val RECONNECT_HELPER = "Telegram подключается с неправильным secret."
    const val RECONNECT_EXTRA_HELPER = "Отключите proxy в Telegram, закройте Telegram и подключите заново по актуальной ссылке."
    const val DEVELOPER_RECOMMENDATION = ProxyServerStats.BAD_HANDSHAKE_RECONNECT_RECOMMENDATION

    const val CONNECT_ACTION = "Подключить Telegram"
    const val RECONNECT_ACTION = "Подключить Telegram заново"

    fun showTelegramReconnectWarning(stats: ProxyServerStats?): Boolean =
        stats?.badHandshakeRecommendation != "none"

    fun actionText(stats: ProxyServerStats?): String = if (showTelegramReconnectWarning(stats)) {
        RECONNECT_ACTION
    } else {
        CONNECT_ACTION
    }

    fun helper(stats: ProxyServerStats?, routeHelper: String?): String? = when {
        showTelegramReconnectWarning(stats) -> listOf(RECONNECT_HELPER, RECONNECT_EXTRA_HELPER).joinToString("\n")
        else -> routeHelper
    }
}

object ConnectionStatusMapper {
    fun status(
        running: Boolean,
        networkStatus: String,
        stats: ProxyServerStats?,
        checking: Boolean = false,
    ): String {
        if (!running && !checking) return "Прокси остановлен"
        if (networkStatus.equals("none", ignoreCase = true)) return "Нет сети"
        if (stats == null) return "Проверяется"

        val activeSessions = stats.connectionsActive > 0
        val hasRouteUsed = !stats.lastRouteUsed.isNullOrBlank() &&
            !stats.lastRouteUsed.equals("none", ignoreCase = true)
        val now = System.currentTimeMillis()
        val freshAcceptedHandshake = stats.lastAcceptedHandshakeTimeMs > 0L &&
            now - stats.lastAcceptedHandshakeTimeMs <= ProxyServerStats.BAD_HANDSHAKE_FRESH_SUCCESS_MS
        val freshSuccessfulRoute = stats.lastSuccessfulRouteTimeMs > 0L &&
            now - stats.lastSuccessfulRouteTimeMs <= ProxyServerStats.BAD_HANDSHAKE_FRESH_SUCCESS_MS
        val hasCurrentSessionErrors = stats.wsConnectErrors + stats.sessionTimeouts + stats.sessionUnexpectedErrors > 0
        val hasRecentBadHandshakeFailures = stats.recentInvalidHandshakeCount > 0L
        val hasFailuresWithoutSuccess = !freshAcceptedHandshake &&
            !freshSuccessfulRoute &&
            (hasRecentBadHandshakeFailures || hasCurrentSessionErrors || stats.directHealthState.equals("unhealthy", ignoreCase = true))

        return when {
            activeSessions && hasRouteUsed -> "Подключён"
            freshAcceptedHandshake || freshSuccessfulRoute -> "Подключён"
            TelegramStatusUiText.showTelegramReconnectWarning(stats) -> TelegramStatusUiText.RECONNECT_STATUS
            checking || stats.routeSettlingUntil > now -> "Проверяется"
            hasRouteUsed && !hasCurrentSessionErrors -> "Подключён"
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
    const val MESSAGE = "Секрет обновлён. Отключите proxy в Telegram, закройте Telegram и подключитесь заново по новой ссылке."

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
