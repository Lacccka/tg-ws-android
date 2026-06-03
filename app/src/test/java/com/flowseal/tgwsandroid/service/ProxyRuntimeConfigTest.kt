package com.flowseal.tgwsandroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRuntimeConfigTest {
    @Test
    fun smokeTestAppConfigMapsToProxyServerConfig() {
        val config = ProxyRuntimeConfig.proxyServerConfig()

        assertEquals("127.0.0.1", config.host)
        assertEquals(1443, config.port)
        assertEquals("4014e15dd34e4b05c42413eab68c3da8", config.secretHex)
        assertEquals(mapOf(2 to "149.154.167.220", 4 to "149.154.167.220"), config.dcRedirects)
        assertEquals(256 * 1024, config.bufferSizeBytes)
        assertEquals(4, config.poolSize)
        assertTrue(config.cfproxyEnabled)
    }

    @Test
    fun summariesMatchSmokeTestInstructions() {
        assertEquals("127.0.0.1:1443", ProxyRuntimeConfig.endpointSummary())
        assertEquals("4014...3da8", ProxyRuntimeConfig.partialSecret())
        assertEquals("dd4014e15dd34e4b05c42413eab68c3da8", ProxyRuntimeConfig.TELEGRAM_SECRET_HEX)
    }
}
