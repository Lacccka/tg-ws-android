package com.flowseal.tgwsandroid

import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import com.flowseal.tgwsandroid.proxy.ProxyServerStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiModelTest {
    @Test
    fun normalRouteModeLabelsMapToInternalModes() {
        val options = UserRouteModes.normalOptions.associateBy { it.title }

        assertEquals(NetworkRouteMode.AUTO, options.getValue("Авто").routeMode)
        assertEquals(NetworkRouteMode.DIRECT_FIRST, options.getValue("Быстрый Wi-Fi").routeMode)
        assertEquals(NetworkRouteMode.CF_FIRST, options.getValue("Совместимый").routeMode)
    }

    @Test
    fun autoRouteModeUsesCompactRecommendedSubtitle() {
        val auto = UserRouteModes.normalOptions.single { it.routeMode == NetworkRouteMode.AUTO }

        assertEquals("Авто", auto.title)
        assertEquals("Рекомендуется", auto.subtitle)
    }

    @Test
    fun normalRouteModeOptionsDoNotUseLongLabelsOrCardBodies() {
        val labels = UserRouteModes.normalOptions.map { it.title }
        val subtitles = UserRouteModes.normalOptions.mapNotNull { it.subtitle }

        assertFalse(labels.contains("Автоматически — рекомендуется"))
        assertFalse(labels.contains("Совместимый Wi-Fi + мобильная сеть"))
        assertFalse(subtitles.any { it.contains("Приложение само выбирает лучший маршрут") })
        assertFalse(subtitles.any { it.contains("Использует быстрый прямой маршрут") })
        assertFalse(subtitles.any { it.contains("Использует совместимый маршрут через резервные домены") })
    }

    @Test
    fun routeModeButtonTextMarksSelectedModeWithoutLongBody() {
        val auto = UserRouteModes.normalOptions.single { it.routeMode == NetworkRouteMode.AUTO }
        val direct = UserRouteModes.normalOptions.single { it.routeMode == NetworkRouteMode.DIRECT_FIRST }

        assertEquals("✓ Авто\nРекомендуется", UserRouteModes.buttonText(auto, selected = true))
        assertEquals("Быстрый Wi-Fi", UserRouteModes.buttonText(direct, selected = false))
    }

    @Test
    fun selectedRouteHelperTextChangesBySelectedMode() {
        assertEquals("Авто выбирает быстрый маршрут на Wi-Fi и совместимый на мобильной сети.", UserRouteModes.helperFor(NetworkRouteMode.AUTO))
        assertEquals("Подходит для Wi-Fi. На мобильной сети может не работать.", UserRouteModes.helperFor(NetworkRouteMode.DIRECT_FIRST))
        assertEquals("Подходит для Wi-Fi и мобильной сети, но ping может быть выше.", UserRouteModes.helperFor(NetworkRouteMode.CF_FIRST))
    }

    @Test
    fun cfOnlyIsNotShownInNormalSettings() {
        assertFalse(UserRouteModes.normalOptions.any { it.routeMode == NetworkRouteMode.CF_ONLY })
    }

    @Test
    fun developerDiagnosticsMayExposeRawRouteMode() {
        assertEquals("CF_ONLY", DeveloperUiModel.routeModeValue(NetworkRouteMode.CF_ONLY, developerModeEnabled = true))
        assertEquals("cf_only", DeveloperUiModel.routeModeValue(NetworkRouteMode.CF_ONLY, developerModeEnabled = false))
    }

    @Test
    fun developerModeHiddenByDefault() {
        assertFalse(DeveloperUiModel.DEFAULT_DEVELOPER_MODE_ENABLED)
    }

    @Test
    fun developerModeActionsAreAvailableOnlyForEnabledSection() {
        assertTrue(DeveloperUiModel.developerActions.contains("Логи"))
        assertTrue(DeveloperUiModel.developerActions.contains("Копировать диагностику"))
        assertTrue(DeveloperUiModel.developerActions.contains("Очистить логи"))
        assertTrue(DeveloperUiModel.developerActions.contains("Копировать ссылку прокси"))
        assertTrue(DeveloperUiModel.developerActions.contains("Подробности маршрута"))
        assertTrue(DeveloperUiModel.developerActions.contains("Состояние резервных доменов"))
        assertTrue(DeveloperUiModel.developerActions.contains("Состояние прямого маршрута"))
    }

    @Test
    fun developerModeDoesNotExposeUpstreamManualCheckText() {
        assertFalse(DeveloperUiModel.developerActions.any { it.contains("upstream", ignoreCase = true) })
        assertFalse(DeveloperUiModel.developerActions.any { it.contains("check_upstream", ignoreCase = true) })
        assertFalse(DeveloperUiModel.developerActions.contains("Статус upstream-файлов"))
    }

    @Test
    fun batteryAndQuickSettingsInstructionsAreRussianAndCompact() {
        assertEquals("Работа в фоне", SettingsUiText.BATTERY_BACKGROUND_TITLE)
        assertTrue(SettingsUiText.BATTERY_XIAOMI_AUTOSTART_TEXT.contains("автозапуск"))
        assertTrue(SettingsUiText.BATTERY_XIAOMI_AUTOSTART_TEXT.contains("Без ограничений"))
        assertTrue(SettingsUiText.BATTERY_BUTTON_HELP_TEXT.contains("Без ограничений"))
        assertEquals("Статус: Без ограничений", SettingsUiText.batteryStatusLine("Без ограничений"))
        assertFalse(SettingsUiText.BATTERY_XIAOMI_AUTOSTART_TEXT.contains("Чтобы прокси не останавливался"))
        assertFalse(SettingsUiText.BATTERY_BUTTON_HELP_TEXT.contains("Чтобы прокси не останавливался"))
        assertEquals("Кнопка в шторке", SettingsUiText.QS_TILE_TITLE)
        assertTrue(SettingsUiText.QS_TILE_TEXT.contains("быстрые настройки Android"))
    }

    @Test
    fun stoppedProxyTelegramStatusSaysProxyStopped() {
        assertEquals("Прокси остановлен", ConnectionStatusMapper.status(running = false, networkStatus = "Wi-Fi", stats = stats()))
    }

    @Test
    fun startingOrUnknownTelegramStatusIsChecking() {
        assertEquals("Проверяется", ConnectionStatusMapper.status(running = false, networkStatus = "Wi-Fi", stats = null, checking = true))
        assertEquals("Проверяется", ConnectionStatusMapper.status(running = true, networkStatus = "Wi-Fi", stats = null))
    }

    @Test
    fun runningWithoutSessionsWaitsForTelegramConnection() {
        assertEquals("Ожидает подключения", ConnectionStatusMapper.status(running = true, networkStatus = "Wi-Fi", stats = stats()))
    }

    @Test
    fun activeSessionsShowTelegramConnected() {
        assertEquals(
            "Подключён",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "Wi-Fi",
                stats = stats(connectionsActive = 1),
            ),
        )
    }

    @Test
    fun lastRouteUsedWithoutCurrentFatalErrorsShowsConnected() {
        assertEquals(
            "Подключён",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "Wi-Fi",
                stats = stats(lastRouteUsed = "direct-cold"),
            ),
        )
    }

    @Test
    fun networkNoneTelegramStatusHasNoNetwork() {
        assertEquals("Нет сети", ConnectionStatusMapper.status(running = true, networkStatus = "none", stats = stats()))
    }

    @Test
    fun wifiDirectWithOldCfErrorsDoesNotShowProblem() {
        val label = ConnectionStatusMapper.status(
            running = true,
            networkStatus = "Wi-Fi",
            stats = stats(
                effectiveRouteMode = NetworkRouteMode.DIRECT_FIRST.configValue,
                lastRouteUsed = "direct-cold",
                cfProxyErrors = 10,
                cf429Count = 10,
                cfCooldownSkips = 10,
            ),
        )

        assertEquals("Подключён", label)
        assertFalse(label.contains("Проблема"))
    }

    @Test
    fun mobileCompatibleWithOld429AndSuccessfulRouteShowsConnected() {
        assertEquals(
            "Подключён",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "mobile",
                stats = stats(
                    effectiveRouteMode = NetworkRouteMode.CF_FIRST.configValue,
                    cfProxyConnections = 2,
                    cf429Count = 3,
                    cfCooldownSkips = 2,
                    lastRouteUsed = "cf",
                ),
            ),
        )
    }

    @Test
    fun routeFailuresWithoutSuccessfulRouteAreUnstable() {
        assertEquals(
            "Нестабильное соединение",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "Wi-Fi",
                stats = stats(
                    connectionsTotal = 1,
                    connectionsBad = 1,
                    effectiveRouteMode = NetworkRouteMode.DIRECT_FIRST.configValue,
                    directHealthState = "unhealthy",
                ),
            ),
        )
    }

    @Test
    fun normalTelegramStatusDoesNotUseOldEvaluativeLabels() {
        val labels = listOf(
            ConnectionStatusMapper.status(running = false, networkStatus = "Wi-Fi", stats = stats()),
            ConnectionStatusMapper.status(running = true, networkStatus = "Wi-Fi", stats = stats()),
            ConnectionStatusMapper.status(running = true, networkStatus = "Wi-Fi", stats = stats(connectionsActive = 1)),
            ConnectionStatusMapper.status(running = true, networkStatus = "Wi-Fi", stats = stats(connectionsTotal = 1, connectionsBad = 1)),
            ConnectionStatusMapper.status(running = true, networkStatus = "none", stats = stats()),
        )

        assertFalse(labels.contains("Проблема подключения"))
        assertFalse(labels.contains("Работает быстро"))
        assertFalse(labels.contains("Работает"))
        assertFalse(labels.contains("Работает медленно"))
        assertFalse(labels.contains("Перегружено"))
    }


    @Test
    fun lowBadHandshakeRatioDoesNotChangeTelegramStatus() {
        assertEquals(
            "Подключён",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "mobile",
                stats = stats(connectionsTotal = 200, connectionsBad = 20, lastRouteUsed = "cf"),
            ),
        )
    }

    @Test
    fun highBadHandshakeRatioAsksToReconnectTelegram() {
        assertEquals(
            "Нужно переподключить Telegram",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "mobile",
                stats = stats(connectionsTotal = 100, connectionsBad = 50),
            ),
        )
    }

    @Test
    fun activeSessionsDoNotHideBadHandshakeStorm() {
        assertEquals(
            "Нужно переподключить Telegram",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "mobile",
                stats = stats(connectionsTotal = 9749, connectionsBad = 9611, connectionsActive = 1, lastRouteUsed = "cf"),
            ),
        )
    }

    @Test
    fun telegramStormHelperMentionsReconnectWithoutRawProtocolText() {
        val helper = TelegramStatusUiText.helper(stats(connectionsTotal = 100, connectionsBad = 50), routeHelper = null).orEmpty()

        assertTrue(helper.contains("Подключить Telegram"))
        assertFalse(helper.contains("Invalid MTProto handshake"))
        assertFalse(helper.contains("bad handshake", ignoreCase = true))
        assertFalse(helper.contains("MTProto"))
    }

    @Test
    fun oldCfCountersDoNotCreateBadHandshakeStatus() {
        assertEquals(
            "Ожидает подключения",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "mobile",
                stats = stats(cfProxyErrors = 46, cf429Count = 12, cfCooldownSkips = 9),
            ),
        )
    }

    @Test
    fun developerDiagnosticsTextMayExposeRawBadHandshakeStormInfo() {
        val diagnostic = "Invalid MTProto handshake storm=true badHandshakeRatio=0.986 bad=9611"

        assertTrue(diagnostic.contains("Invalid MTProto handshake storm"))
        assertTrue(diagnostic.contains("badHandshakeRatio"))
        assertTrue(TelegramStatusUiText.DEVELOPER_RECOMMENDATION.contains("Отключите прокси"))
    }

    @Test
    fun routeLabelMapsWifiDirectToFastWifi() {
        assertEquals(
            "Быстрый Wi-Fi",
            HomeRouteLabelMapper.label(
                networkStatus = "Wi-Fi",
                configuredRouteMode = NetworkRouteMode.DIRECT_FIRST,
                stats = stats(effectiveRouteMode = NetworkRouteMode.DIRECT_FIRST.configValue),
            ),
        )
    }

    @Test
    fun routeLabelMapsMobileCfToCompatible() {
        assertEquals(
            "Совместимый",
            HomeRouteLabelMapper.label(
                networkStatus = "mobile",
                configuredRouteMode = NetworkRouteMode.CF_FIRST,
                stats = stats(effectiveRouteMode = NetworkRouteMode.CF_FIRST.configValue),
            ),
        )
    }

    @Test
    fun routeLabelKeepsAutoBeforeEffectiveRouteKnown() {
        assertEquals(
            "Автоматический выбор",
            HomeRouteLabelMapper.label(
                networkStatus = "Wi-Fi",
                configuredRouteMode = NetworkRouteMode.AUTO,
                stats = null,
            ),
        )
    }

    @Test
    fun routeLabelUsesEffectiveRouteForAutoWhenKnown() {
        assertEquals(
            "Быстрый Wi-Fi",
            HomeRouteLabelMapper.label(
                networkStatus = "Wi-Fi",
                configuredRouteMode = NetworkRouteMode.AUTO,
                stats = stats(effectiveRouteMode = NetworkRouteMode.DIRECT_FIRST.configValue),
            ),
        )
    }


    @Test
    fun routeLabelMapsNoNetworkToNoNetwork() {
        assertEquals(
            "Нет сети",
            HomeRouteLabelMapper.label(
                networkStatus = "none",
                configuredRouteMode = NetworkRouteMode.AUTO,
                stats = stats(effectiveRouteMode = NetworkRouteMode.CF_FIRST.configValue),
            ),
        )
    }

    @Test
    fun mobileCompatibleRouteHelperIsSoftNonErrorText() {
        assertEquals(
            "На мобильной сети используется совместимый маршрут. Ping может быть выше.",
            HomeRouteLabelMapper.mobileCompatibleHelper("mobile", "Совместимый"),
        )
    }

    @Test
    fun secretUpdatedMessageIsNotShownByDefault() {
        assertFalse(SecretUpdatedMessageModel.visibleByDefault())
    }

    @Test
    fun routeModeChangeDoesNotShowSecretUpdatedMessage() {
        assertFalse(SecretUpdatedMessageModel.visibleAfterRouteModeChange())
    }

    @Test
    fun secretUpdatedMessageAppearsOnlyAfterConfirmedUpdateSecretAction() {
        assertTrue(SecretUpdatedMessageModel.visibleAfterConfirmedUpdateSecret(secretUpdated = true))
        assertFalse(SecretUpdatedMessageModel.visibleAfterConfirmedUpdateSecret(secretUpdated = false))
    }

    @Test
    fun secretUpdatedMessageIsNotShownAgainAfterConsumed() {
        assertFalse(SecretUpdatedMessageModel.visibleAfterConsumed())
    }

    @Test
    fun routeModeChangeWhileStoppedDoesNotCreateRestartWarning() {
        assertFalse(PendingRestartModel.pendingAfterRouteModeChange(proxyRunning = false))
    }

    @Test
    fun routeModeChangeWhileRunningCreatesRestartWarningOnlyIfRequired() {
        assertTrue(PendingRestartModel.pendingAfterRouteModeChange(proxyRunning = true, restartRequiredForRunningProxy = true))
        assertFalse(PendingRestartModel.pendingAfterRouteModeChange(proxyRunning = true, restartRequiredForRunningProxy = false))
    }

    @Test
    fun restartProxyActionDisabledWhileStopped() {
        assertFalse(PendingRestartModel.restartActionEnabled(proxyRunning = false))
        assertTrue(PendingRestartModel.restartActionEnabled(proxyRunning = true))
    }

    @Test
    fun savedRouteModeIsAppliedOnNextStart() {
        assertEquals(NetworkRouteMode.CF_FIRST, NetworkRouteMode.fromConfigValue(NetworkRouteMode.CF_FIRST.configValue))
        assertFalse(PendingRestartModel.pendingAfterRouteModeChange(proxyRunning = false))
    }

    @Test
    fun diagnosticsStillContainRawCounters() {
        val details = DeveloperUiModel.developerActions + listOf("CF", "429", "pool", "route state")
        assertTrue(details.joinToString(" ").contains("429"))
        assertTrue(details.joinToString(" ").contains("pool"))
    }

    private fun stats(
        connectionsTotal: Long = 0,
        connectionsActive: Int = 0,
        connectionsBad: Long = 0,
        wsConnectErrors: Long = 0,
        cfProxyConnections: Long = 0,
        cfProxyErrors: Long = 0,
        bytesUp: Long = 0,
        bytesDown: Long = 0,
        effectiveRouteMode: String = NetworkRouteMode.CF_FIRST.configValue,
        sessionTimeouts: Long = 0,
        sessionUnexpectedErrors: Long = 0,
        networkNoneEvents: Long = 0,
        cf429Count: Long = 0,
        cfCooldownSkips: Long = 0,
        directHealthState: String = "healthy",
        directHealthSuccesses: Long = 0,
        lastRouteUsed: String? = null,
    ): ProxyServerStats = ProxyServerStats(
        connectionsTotal = connectionsTotal,
        connectionsActive = connectionsActive,
        connectionsBad = connectionsBad,
        wsConnectErrors = wsConnectErrors,
        cfProxyConnections = cfProxyConnections,
        cfProxyErrors = cfProxyErrors,
        bytesUp = bytesUp,
        bytesDown = bytesDown,
        poolHits = 0,
        poolMisses = 0,
        poolRefillErrors = 0,
        effectiveRouteMode = effectiveRouteMode,
        sessionTimeouts = sessionTimeouts,
        sessionUnexpectedErrors = sessionUnexpectedErrors,
        networkNoneEvents = networkNoneEvents,
        directHealthState = directHealthState,
        directHealthSuccesses = directHealthSuccesses,
        lastRouteUsed = lastRouteUsed,
        cf429Count = cf429Count,
        cfCooldownSkips = cfCooldownSkips,
    )
}
