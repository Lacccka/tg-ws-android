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

        assertEquals(NetworkRouteMode.AUTO, options.getValue("Автоматически — рекомендуется").routeMode)
        assertEquals(NetworkRouteMode.DIRECT_FIRST, options.getValue("Быстрый Wi-Fi").routeMode)
        assertEquals(NetworkRouteMode.CF_FIRST, options.getValue("Совместимый Wi-Fi + мобильная сеть").routeMode)
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
    }

    @Test
    fun batteryAndQuickSettingsInstructionsAreRussian() {
        assertEquals("Работа в фоне", SettingsUiText.BATTERY_BACKGROUND_TITLE)
        assertEquals("Чтобы прокси не останавливался, разрешите приложению работу без ограничений батареи.", SettingsUiText.BATTERY_BACKGROUND_TEXT)
        assertEquals("На Xiaomi также включите автозапуск для приложения.", SettingsUiText.BATTERY_XIAOMI_AUTOSTART_TEXT)
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
