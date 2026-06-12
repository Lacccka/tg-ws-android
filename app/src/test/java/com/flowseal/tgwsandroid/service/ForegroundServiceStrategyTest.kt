package com.flowseal.tgwsandroid.service

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServiceStrategyTest {
    @Test
    fun dataSyncUsesRuntimeTypeOnAndroidQAndNewerOnly() {
        assertEquals(
            "dataSync",
            ProxyForegroundService.runtimeForegroundServiceTypeNameForStrategy("dataSync", Build.VERSION_CODES.Q),
        )
        assertEquals(
            "none",
            ProxyForegroundService.runtimeForegroundServiceTypeNameForStrategy("dataSync", Build.VERSION_CODES.P),
        )
    }

    @Test
    fun specialUseUsesRuntimeTypeOnAndroid14AndNewerOnly() {
        assertEquals(
            "specialUse",
            ProxyForegroundService.runtimeForegroundServiceTypeNameForStrategy("specialUse", Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertEquals(
            "none",
            ProxyForegroundService.runtimeForegroundServiceTypeNameForStrategy("specialUse", Build.VERSION_CODES.TIRAMISU),
        )
    }
}
