package com.flowseal.tgwsandroid.service

object ProxyTileUiModel {
    const val LABEL = "TG Proxy"
    const val SUBTITLE_RUNNING = "Включён"
    const val SUBTITLE_STOPPED = "Остановлен"
    const val STATE_ACTIVE = 2
    const val STATE_INACTIVE = 1

    fun subtitle(running: Boolean): String = if (running) SUBTITLE_RUNNING else SUBTITLE_STOPPED

    fun state(running: Boolean): Int = if (running) STATE_ACTIVE else STATE_INACTIVE
}

object ProxyQuickSettingsTileActionMapper {
    fun actionForClick(running: Boolean): String = if (running) {
        ProxyForegroundService.ACTION_STOP_FROM_TILE
    } else {
        ProxyForegroundService.ACTION_START
    }
}
