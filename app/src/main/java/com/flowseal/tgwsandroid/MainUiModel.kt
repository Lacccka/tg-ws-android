package com.flowseal.tgwsandroid

import com.flowseal.tgwsandroid.config.Appearance
import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import com.flowseal.tgwsandroid.proxy.ProxyServerStats


data class AppColorScheme(
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val surfaceContainer: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val error: Int,
    val onError: Int,
    val onBackground: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val divider: Int,
    val success: Int,
    val warning: Int,
    val successContainer: Int,
    val onSuccessContainer: Int,
    val warningContainer: Int,
    val onWarningContainer: Int,
)

fun lightAppColorScheme(): AppColorScheme = AppColorScheme(
    background = 0xFFF6F7FB.toInt(),
    surface = 0xFFFFFFFF.toInt(),
    surfaceVariant = 0xFFEFF6FF.toInt(),
    surfaceContainer = 0xFFFFFFFF.toInt(),
    primary = 0xFF2563EB.toInt(),
    onPrimary = 0xFFFFFFFF.toInt(),
    primaryContainer = 0xFFDCEBFF.toInt(),
    onPrimaryContainer = 0xFF0B3A75.toInt(),
    secondary = 0xFF475569.toInt(),
    onSecondary = 0xFFFFFFFF.toInt(),
    error = 0xFFB42318.toInt(),
    onError = 0xFFFFFFFF.toInt(),
    onBackground = 0xFF111827.toInt(),
    onSurface = 0xFF111827.toInt(),
    onSurfaceVariant = 0xFF4B5563.toInt(),
    outline = 0xFFE5E7EB.toInt(),
    divider = 0xFFE5E7EB.toInt(),
    success = 0xFF047857.toInt(),
    warning = 0xFFB45309.toInt(),
    successContainer = 0xFFD1FAE5.toInt(),
    onSuccessContainer = 0xFF064E3B.toInt(),
    warningContainer = 0xFFFEF3C7.toInt(),
    onWarningContainer = 0xFF78350F.toInt(),
)

fun darkAppColorScheme(): AppColorScheme = AppColorScheme(
    background = 0xFF111827.toInt(),
    surface = 0xFF1F2937.toInt(),
    surfaceVariant = 0xFF374151.toInt(),
    surfaceContainer = 0xFF1F2937.toInt(),
    primary = 0xFF60A5FA.toInt(),
    onPrimary = 0xFF0B1220.toInt(),
    primaryContainer = 0xFF1D4ED8.toInt(),
    onPrimaryContainer = 0xFFEFF6FF.toInt(),
    secondary = 0xFFCBD5E1.toInt(),
    onSecondary = 0xFF0F172A.toInt(),
    error = 0xFFF87171.toInt(),
    onError = 0xFF1F0A0A.toInt(),
    onBackground = 0xFFF9FAFB.toInt(),
    onSurface = 0xFFF9FAFB.toInt(),
    onSurfaceVariant = 0xFFD1D5DB.toInt(),
    outline = 0xFF4B5563.toInt(),
    divider = 0xFF374151.toInt(),
    success = 0xFF34D399.toInt(),
    warning = 0xFFF59E0B.toInt(),
    successContainer = 0xFF064E3B.toInt(),
    onSuccessContainer = 0xFFD1FAE5.toInt(),
    warningContainer = 0xFF78350F.toInt(),
    onWarningContainer = 0xFFFFF7ED.toInt(),
)

data class BottomNavColorModel(
    val background: Int,
    val selected: Int,
    val unselected: Int,
    val activeIndicator: Int,
)

fun bottomNavColorModel(colorScheme: AppColorScheme): BottomNavColorModel = BottomNavColorModel(
    background = colorScheme.surfaceContainer,
    selected = colorScheme.primary,
    unselected = colorScheme.onSurfaceVariant,
    activeIndicator = colorScheme.primaryContainer,
)

object RussianUiText {
    const val ROUTE_AUTO = "Авто"
    const val ROUTE_AUTO_SUBTITLE = "Рекомендуется. Приложение само выбирает подходящий режим."
    const val ROUTE_FAST_WIFI = "Быстрый Wi-Fi"
    const val ROUTE_COMPATIBLE_SHORT = "Совместимый"
    const val ROUTE_AUTO_SELECTION = "Автоматический выбор"
    const val ROUTE_AUTO_HELPER = ROUTE_AUTO_SUBTITLE
    const val ROUTE_FAST_WIFI_HELPER = "Для стабильного Wi-Fi."
    const val ROUTE_COMPATIBLE_HELPER = "Для мобильной сети."
    const val MOBILE_COMPATIBLE_ROUTE_HELPER = "На мобильной сети используется совместимый маршрут. Ping может быть выше."
}

data class UserRouteModeOption(
    val title: String,
    val subtitle: String?,
    val routeMode: NetworkRouteMode,
)


object SettingsUiText {
    const val RESET_SECRET_TITLE = "Обновить secret"
    const val RESET_SECRET_DIALOG_TITLE = "Обновить secret?"
    const val RESET_SECRET_DIALOG_MESSAGE = "После обновления нужно заново подключить Telegram."
    const val CONNECTION_MODE_TITLE = "Режим подключения"
    const val THEME_TITLE = "Тема приложения"
    val themeOptions: List<String> = AppearanceUiModels.options.map { it.label }
    val connectionModeOptions: List<String> = UserRouteModes.normalOptions.map { it.title }
    val connectionModeDialogDescriptions: List<String> = listOf(
        RussianUiText.ROUTE_AUTO_SUBTITLE,
        RussianUiText.ROUTE_FAST_WIFI_HELPER,
        RussianUiText.ROUTE_COMPATIBLE_HELPER,
    )
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

    fun uiModel(mode: NetworkRouteMode): RouteModeUiModel = RouteModeUiModel(
        label = labelFor(mode),
        description = helperFor(mode),
    )


    fun labelFor(mode: NetworkRouteMode): String = normalOptions.firstOrNull { it.routeMode == mode }?.title
        ?: RussianUiText.ROUTE_COMPATIBLE_SHORT

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

data class RouteModeUiModel(
    val label: String,
    val description: String,
)

data class AppearanceUiModel(
    val appearance: Appearance,
    val label: String,
    val description: String,
)

enum class ResolvedNightMode {
    FOLLOW_SYSTEM,
    LIGHT,
    DARK,
}

object AppearanceUiModels {
    val options: List<AppearanceUiModel> = listOf(
        AppearanceUiModel(Appearance.AUTO, "Авто", "Следует системной теме."),
        AppearanceUiModel(Appearance.LIGHT, "Светлая", "Всегда использовать светлую тему."),
        AppearanceUiModel(Appearance.DARK, "Тёмная", "Всегда использовать тёмную тему."),
    )

    fun uiModel(appearance: Appearance): AppearanceUiModel = options.first { it.appearance == appearance }

    fun label(appearance: Appearance): String = uiModel(appearance).label

    fun description(appearance: Appearance): String = uiModel(appearance).description

    fun compactHelper(appearance: Appearance): String = when (appearance) {
        Appearance.AUTO -> "системная тема"
        Appearance.LIGHT -> "светлая тема"
        Appearance.DARK -> "тёмная тема"
    }

    fun nightMode(appearance: Appearance): ResolvedNightMode = when (appearance) {
        Appearance.AUTO -> ResolvedNightMode.FOLLOW_SYSTEM
        Appearance.LIGHT -> ResolvedNightMode.LIGHT
        Appearance.DARK -> ResolvedNightMode.DARK
    }
}


enum class SettingsBadge(val label: String) {
    IMPORTANT("Важно"),
    RECOMMENDED("Рекомендуется"),
}

enum class SettingsRowKind {
    SWITCH,
    STATUS,
    VALUE,
    ACTION,
    DANGER,
}

data class SettingsRowModel(
    val title: String,
    val kind: SettingsRowKind,
    val description: String? = null,
    val status: String? = null,
    val selectedValue: String? = null,
    val badge: SettingsBadge? = null,
)

data class SettingsChoiceModel(
    val title: String,
    val selectedValue: String,
    val options: List<String>,
)

object SettingsScreenModel {
    val normalRows: List<SettingsRowModel> = listOf(
        SettingsRowModel("Уведомления", SettingsRowKind.STATUS, description = "Показывают состояние подключения.", status = "Включено", badge = SettingsBadge.RECOMMENDED),
        SettingsRowModel(SettingsUiText.BATTERY_BACKGROUND_TITLE, SettingsRowKind.STATUS, description = "Помогает сохранять подключение после блокировки экрана.", status = "Может ограничиваться", badge = SettingsBadge.IMPORTANT),
        SettingsRowModel("Автозапуск", SettingsRowKind.STATUS, description = "Позволяет запускать прокси после перезагрузки устройства.", status = "Не проверено", badge = SettingsBadge.IMPORTANT),
        SettingsRowModel("Анонимная диагностика", SettingsRowKind.SWITCH, description = "Помогает улучшать стабильность без личных данных.", status = "Выключено", badge = SettingsBadge.RECOMMENDED),
        SettingsRowModel(SettingsUiText.CONNECTION_MODE_TITLE, SettingsRowKind.VALUE, description = RussianUiText.ROUTE_AUTO_HELPER, selectedValue = SettingsUiText.connectionModeOptions.first()),
        SettingsRowModel(SettingsUiText.THEME_TITLE, SettingsRowKind.VALUE, description = AppearanceUiModels.uiModel(Appearance.AUTO).description, selectedValue = AppearanceUiModels.uiModel(Appearance.AUTO).label),
        SettingsRowModel("IP-адрес", SettingsRowKind.VALUE),
        SettingsRowModel("Порт", SettingsRowKind.VALUE),
        SettingsRowModel("Secret", SettingsRowKind.VALUE),
        SettingsRowModel(SettingsUiText.RESET_SECRET_TITLE, SettingsRowKind.ACTION),
    )

    val connectionModeChoice = SettingsChoiceModel(
        title = SettingsUiText.CONNECTION_MODE_TITLE,
        selectedValue = SettingsUiText.connectionModeOptions.first(),
        options = SettingsUiText.connectionModeOptions,
    )

    val themeChoice = SettingsChoiceModel(
        title = SettingsUiText.THEME_TITLE,
        selectedValue = SettingsUiText.themeOptions.first(),
        options = SettingsUiText.themeOptions,
    )

    fun connectionModeChoice(selectedMode: NetworkRouteMode): SettingsChoiceModel = connectionModeChoice.copy(
        selectedValue = UserRouteModes.labelFor(selectedMode),
    )

    fun themeChoice(selectedAppearance: Appearance): SettingsChoiceModel = themeChoice.copy(
        selectedValue = AppearanceUiModels.uiModel(selectedAppearance).label,
    )
}


data class MainHeroState(
    val title: String,
    val subtitle: String,
    val primaryAction: String?,
    val secondaryAction: String?,
)

data class MainActionModel(
    val primaryAction: String?,
    val restartAction: String?,
    val telegramAction: String?,
    val restartNote: String?,
) {
    val visibleActions: List<String> = listOfNotNull(primaryAction, restartAction, telegramAction)
}

object MainActionModelMapper {
    const val START_ACTION = "Включить"
    const val STOP_ACTION = "Отключить"
    const val CONNECT_TELEGRAM_ACTION = "Подключить Telegram"
    const val RESTART_ACTION = "Перезапустить"
    const val PENDING_RESTART_NOTE = "Перезапустите прокси, чтобы применить изменения"

    fun actions(proxyEnabled: Boolean, settingsChangedPendingRestart: Boolean): MainActionModel = if (proxyEnabled) {
        MainActionModel(
            primaryAction = STOP_ACTION,
            restartAction = RESTART_ACTION,
            telegramAction = CONNECT_TELEGRAM_ACTION,
            restartNote = if (settingsChangedPendingRestart) PENDING_RESTART_NOTE else null,
        )
    } else {
        MainActionModel(
            primaryAction = START_ACTION,
            restartAction = null,
            telegramAction = null,
            restartNote = null,
        )
    }
}

object MainHeroStateMapper {
    const val STARTING_TITLE = "Подключаемся…"
    const val STOPPED_TITLE = "Прокси выключен"
    const val UNSTABLE_TITLE = "Подключение нестабильно"
    const val TELEGRAM_NOT_CONNECTED_TITLE = "Почти готово"
    const val READY_TITLE = "Всё готово"

    const val START_ACTION = "Включить"
    const val STOP_ACTION = "Отключить"
    const val CONNECT_TELEGRAM_ACTION = "Подключить Telegram"
    const val RESTART_ACTION = "Перезапустить"

    fun state(
        running: Boolean,
        starting: Boolean,
        failed: Boolean,
        healthLabel: String,
        telegramReconnectWarning: Boolean,
        telegramConnected: Boolean,
    ): MainHeroState = when {
        starting -> MainHeroState(STARTING_TITLE, "Это займёт несколько секунд", null, null)
        !running -> MainHeroState(STOPPED_TITLE, "Включите подключение", null, null)
        failed || healthLabel == "Нестабильно" || telegramReconnectWarning -> {
            MainHeroState(UNSTABLE_TITLE, "Можно перезапустить прокси или подключить Telegram заново", null, null)
        }
        !telegramConnected -> MainHeroState(TELEGRAM_NOT_CONNECTED_TITLE, "Осталось подключить Telegram", null, null)
        else -> MainHeroState(READY_TITLE, "Telegram подключён", null, null)
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

object RecommendationUiText {
    const val DISMISS_ACTION = "Позже"
    const val OPEN_ACTION = "Открыть"
    const val ENABLE_ACTION = "Включить"
    const val ADD_ACTION = "Добавить"

    val cards: Map<String, Pair<String, String>> = mapOf(
        "notifications" to ("Включить уведомления" to "Так будет проще видеть состояние подключения"),
        "battery" to ("Разрешить работу в фоне" to "Помогает сохранять подключение после блокировки экрана"),
        "autostart" to ("Включить автозапуск" to "Прокси сможет запускаться после перезагрузки"),
        "quick_settings" to ("Добавить кнопку в шторку" to "Быстрый запуск и остановка прокси"),
        "telemetry" to ("Включить анонимную диагностику" to "Помогает улучшать стабильность без личных данных"),
    )
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
