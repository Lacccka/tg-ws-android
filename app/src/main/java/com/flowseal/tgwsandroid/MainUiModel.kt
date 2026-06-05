package com.flowseal.tgwsandroid

import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import com.flowseal.tgwsandroid.proxy.ProxyServerStats

object RussianUiText {
    const val ROUTE_AUTO_RECOMMENDED = "Автоматически — рекомендуется"
    const val ROUTE_FAST_WIFI = "Быстрый Wi-Fi"
    const val ROUTE_COMPATIBLE = "Совместимый Wi-Fi + мобильная сеть"
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

object ConnectionStatusMapper {
    fun status(
        running: Boolean,
        networkStatus: String,
        stats: ProxyServerStats?,
        checking: Boolean = false,
    ): String {
        if (!running && !checking) return "Неактивно"
        if (checking) return "Проверяется"
        if (networkStatus.equals("none", ignoreCase = true)) return "Нет сети"
        if (stats == null) return "Проверяется"

        val effective = stats.effectiveRouteMode
        val isWifi = networkStatus.equals("Wi-Fi", ignoreCase = true) || networkStatus.equals("wifi", ignoreCase = true)
        val isMobile = networkStatus.equals("mobile", ignoreCase = true) || networkStatus.equals("cellular", ignoreCase = true)
        val direct = effective.equals(NetworkRouteMode.DIRECT_FIRST.configValue, ignoreCase = true) ||
            effective.equals(NetworkRouteMode.DIRECT_FIRST.name, ignoreCase = true)
        val compatible = effective.equals(NetworkRouteMode.CF_FIRST.configValue, ignoreCase = true) ||
            effective.equals(NetworkRouteMode.CF_ONLY.configValue, ignoreCase = true) ||
            effective.equals(NetworkRouteMode.CF_FIRST.name, ignoreCase = true) ||
            effective.equals(NetworkRouteMode.CF_ONLY.name, ignoreCase = true)

        val directUnavailable = stats.directHealthState.equals("unhealthy", ignoreCase = true) ||
            stats.directHealthState.equals("cooldown", ignoreCase = true)
        val hasCurrentSessionErrors = stats.wsConnectErrors + stats.sessionTimeouts + stats.sessionUnexpectedErrors > 0
        val allConnectionsFailed = stats.connectionsBad > 0 && stats.connectionsBad >= stats.connectionsTotal.coerceAtLeast(1)
        val successfulCfConnections = stats.cfProxyConnections > 0
        val successfulConnections = stats.connectionsTotal > stats.connectionsBad || successfulCfConnections || stats.directHealthSuccesses > 0
        val cfProblemSignals = stats.cfProxyErrors + stats.cf429Count + stats.cf429BackoffCount + stats.cfCooldownSkips +
            stats.cfConnectQueueTimeouts + stats.cfQueueControlledFailures + stats.cfAllDomainsInCooldownFallbacks

        if (isWifi && direct) {
            return if (!directUnavailable && !hasCurrentSessionErrors && !allConnectionsFailed) {
                "Работает быстро"
            } else {
                "Проблема подключения"
            }
        }

        if (isWifi && directUnavailable) return "Проблема подключения"

        if (isMobile && compatible) {
            return when {
                successfulCfConnections && cfProblemSignals < HIGH_CF_PROBLEM_SIGNALS -> "Работает"
                successfulConnections && cfProblemSignals < HIGH_CF_PROBLEM_SIGNALS -> "Работает"
                successfulConnections -> "Работает медленно"
                else -> "Проблема подключения"
            }
        }

        if (allConnectionsFailed || (hasCurrentSessionErrors && !successfulConnections)) return "Проблема подключения"
        if (successfulConnections) return if (hasCurrentSessionErrors) "Работает медленно" else "Работает"
        return "Неизвестно"
    }

    private const val HIGH_CF_PROBLEM_SIGNALS = 5L
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
