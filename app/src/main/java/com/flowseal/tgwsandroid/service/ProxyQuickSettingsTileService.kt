package com.flowseal.tgwsandroid.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ProxyQuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        ProxyRuntimeConfig.initialize(applicationContext)
        ProxyForegroundService.State.initialize(applicationContext, "quick_settings")
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        ProxyRuntimeConfig.initialize(applicationContext)
        ProxyForegroundService.State.initialize(applicationContext, "quick_settings")
        val running = ProxyForegroundService.State.running
        val intent = when (ProxyQuickSettingsTileActionMapper.actionForClick(running)) {
            ProxyForegroundService.ACTION_STOP_FROM_TILE -> ProxyForegroundService.stopFromTileIntent(this)
            else -> ProxyForegroundService.startIntent(this)
        }
        if (intent.action == ProxyForegroundService.ACTION_START && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        qsTile?.let { tile ->
            applyTileState(tile, running = !running)
            tile.updateTile()
        }
    }

    private fun updateTile() {
        qsTile?.let { tile ->
            applyTileState(tile, ProxyForegroundService.State.running)
            tile.updateTile()
        }
    }

    private fun applyTileState(tile: Tile, running: Boolean) {
        tile.label = ProxyTileUiModel.LABEL
        tile.state = if (ProxyTileUiModel.state(running) == ProxyTileUiModel.STATE_ACTIVE) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = ProxyTileUiModel.subtitle(running)
        }
    }

    companion object {
        fun requestTileRefresh(context: Context?) {
            if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            try {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, ProxyQuickSettingsTileService::class.java),
                )
            } catch (_: Throwable) {
                // Best-effort QS tile refresh; unsupported launchers should not affect proxy lifecycle.
            }
        }
    }
}
