package com.flowseal.tgwsandroid.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyTileUiModelTest {
    @Test
    fun tileLabelIsTgProxy() {
        assertEquals("TG Proxy", ProxyTileUiModel.LABEL)
    }

    @Test
    fun runningProxyMapsToActiveRussianState() {
        assertEquals(ProxyTileUiModel.STATE_ACTIVE, ProxyTileUiModel.state(running = true))
        assertEquals("Работает", ProxyTileUiModel.subtitle(running = true))
    }

    @Test
    fun stoppedProxyMapsToInactiveRussianState() {
        assertEquals(ProxyTileUiModel.STATE_INACTIVE, ProxyTileUiModel.state(running = false))
        assertEquals("Остановлен", ProxyTileUiModel.subtitle(running = false))
    }

    @Test
    fun clickUsesExistingForegroundServiceActions() {
        assertEquals(ProxyForegroundService.ACTION_START, ProxyQuickSettingsTileActionMapper.actionForClick(running = false))
        assertEquals(ProxyForegroundService.ACTION_STOP_FROM_TILE, ProxyQuickSettingsTileActionMapper.actionForClick(running = true))
    }
}
