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
    fun stoppedProxyConnectionStateIsInactive() {
        assertEquals("Неактивно", ConnectionStatusMapper.status(running = false, networkStatus = "Wi-Fi", stats = stats()))
    }

    @Test
    fun startingOrUnknownConnectionStateIsChecking() {
        assertEquals("Проверяется", ConnectionStatusMapper.status(running = false, networkStatus = "Wi-Fi", stats = null, checking = true))
        assertEquals("Проверяется", ConnectionStatusMapper.status(running = true, networkStatus = "Wi-Fi", stats = null))
    }

    @Test
    fun networkNoneConnectionStateHasNoNetwork() {
        assertEquals("Нет сети", ConnectionStatusMapper.status(running = true, networkStatus = "none", stats = stats()))
    }

    @Test
    fun wifiDirectHealthyConnectionStateIsFast() {
        assertEquals(
            "Работает быстро",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "Wi-Fi",
                stats = stats(connectionsTotal = 1, effectiveRouteMode = NetworkRouteMode.DIRECT_FIRST.configValue),
            ),
        )
    }

    @Test
    fun wifiDirectIgnoresOldCfErrors() {
        assertEquals(
            "Работает быстро",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "Wi-Fi",
                stats = stats(
                    connectionsTotal = 1,
                    effectiveRouteMode = NetworkRouteMode.DIRECT_FIRST.configValue,
                    cfProxyErrors = 10,
                    cf429Count = 10,
                    cfCooldownSkips = 10,
                ),
            ),
        )
    }

    @Test
    fun mobileCompatibleWithSuccessAndModerateCfErrorsWorks() {
        assertEquals(
            "Работает",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "mobile",
                stats = stats(
                    connectionsTotal = 3,
                    effectiveRouteMode = NetworkRouteMode.CF_FIRST.configValue,
                    cfProxyConnections = 2,
                    cfProxyErrors = 2,
                ),
            ),
        )
    }

    @Test
    fun mobileCompatibleWithHighCurrentCfErrorsIsSlow() {
        assertEquals(
            "Работает медленно",
            ConnectionStatusMapper.status(
                running = true,
                networkStatus = "mobile",
                stats = stats(
                    connectionsTotal = 3,
                    effectiveRouteMode = NetworkRouteMode.CF_FIRST.configValue,
                    cfProxyConnections = 2,
                    cf429Count = 3,
                    cfCooldownSkips = 2,
                ),
            ),
        )
    }

    @Test
    fun noRouteOrCurrentFailureIsConnectionProblem() {
        assertEquals(
            "Проблема подключения",
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
    fun overloadedIsNotUsedInNormalConnectionLabels() {
        val labels = listOf(
            "Неактивно",
            "Проверяется",
            "Работает быстро",
            "Работает",
            "Работает медленно",
            "Нет сети",
            "Проблема подключения",
            "Неизвестно",
        )
        assertFalse(labels.contains("Перегружено"))
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
        cf429Count = cf429Count,
        cfCooldownSkips = cfCooldownSkips,
    )
}
