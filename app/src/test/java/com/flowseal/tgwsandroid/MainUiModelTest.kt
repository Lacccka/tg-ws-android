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
        assertEquals(NetworkRouteMode.CF_FIRST, options.getValue("Совместимый Wi-Fi + Mobile").routeMode)
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
    fun qualityStoppedProxyIsInactive() {
        assertEquals("Неактивно", ConnectionQualityMapper.quality(running = false, networkStatus = "Wi-Fi", stats = stats()))
    }

    @Test
    fun qualityWifiDirectHealthyIsGood() {
        assertEquals(
            "Хорошее",
            ConnectionQualityMapper.quality(
                running = true,
                networkStatus = "Wi-Fi",
                stats = stats(connectionsTotal = 1, effectiveRouteMode = NetworkRouteMode.DIRECT_FIRST.configValue),
            ),
        )
    }

    @Test
    fun qualityMobileCompatibleWithModerateCfErrorsIsMedium() {
        assertEquals(
            "Среднее",
            ConnectionQualityMapper.quality(
                running = true,
                networkStatus = "mobile",
                stats = stats(
                    connectionsTotal = 3,
                    effectiveRouteMode = NetworkRouteMode.CF_FIRST.configValue,
                    cfProxyErrors = 2,
                ),
            ),
        )
    }

    @Test
    fun qualityHighCfCongestionIsOverloaded() {
        assertEquals(
            "Перегружено",
            ConnectionQualityMapper.quality(
                running = true,
                networkStatus = "mobile",
                stats = stats(
                    connectionsTotal = 3,
                    effectiveRouteMode = NetworkRouteMode.CF_FIRST.configValue,
                    cf429Count = 3,
                    cfCooldownSkips = 2,
                ),
            ),
        )
    }

    @Test
    fun qualityNetworkNoneHasNoRoute() {
        assertEquals("Нет маршрута", ConnectionQualityMapper.quality(running = true, networkStatus = "none", stats = stats()))
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
        cf429Count = cf429Count,
        cfCooldownSkips = cfCooldownSkips,
    )
}
