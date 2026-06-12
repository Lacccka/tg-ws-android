package com.flowseal.tgwsandroid.service

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServiceStrategyTest {
    @Test
    fun dataSyncOnApiBeforeQUsesNoRuntimeType() {
        assertEquals(
            "none",
            ProxyForegroundService.runtimeForegroundServiceTypeNameForStrategy("dataSync", Build.VERSION_CODES.P),
        )
    }

    @Test
    fun dataSyncOnApiQAndNewerUsesDataSyncRuntimeType() {
        assertEquals(
            "dataSync",
            ProxyForegroundService.runtimeForegroundServiceTypeNameForStrategy("dataSync", Build.VERSION_CODES.Q),
        )
    }

    @Test
    fun specialUseOnApiBeforeAndroid14UsesNoRuntimeType() {
        assertEquals(
            "none",
            ProxyForegroundService.runtimeForegroundServiceTypeNameForStrategy("specialUse", Build.VERSION_CODES.TIRAMISU),
        )
    }

    @Test
    fun specialUseOnAndroid14AndNewerUsesSpecialUseRuntimeType() {
        assertEquals(
            "specialUse",
            ProxyForegroundService.runtimeForegroundServiceTypeNameForStrategy("specialUse", Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
    }
}
