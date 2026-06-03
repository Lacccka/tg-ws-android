package com.flowseal.tgwsandroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRuntimeConfigTest {
    @Test
    fun smokeTestAppConfigMapsToProxyServerConfig() {
        val config = ProxyRuntimeConfig.proxyServerConfig()

        assertEquals("127.0.0.1", config.host)
        assertEquals(1443, config.port)
        assertEquals("4014e15dd34e4b05c42413eab68c3da8", config.secretHex)
        assertEquals(
            mapOf(
                2 to "149.154.167.220",
                3 to "149.154.167.220",
                4 to "149.154.167.220",
            ),
            config.dcRedirects,
        )
        assertEquals(256 * 1024, config.bufferSizeBytes)
        assertEquals(4, config.poolSize)
        assertTrue(config.cfproxyEnabled)
    }

    @Test
    fun dcRedirectsKeepDc2Dc3Dc4OnKnownWorkingTarget() {
        val redirects = ProxyRuntimeConfig.proxyServerConfig().dcRedirects

        assertEquals("149.154.167.220", redirects[2])
        assertEquals("149.154.167.220", redirects[3])
        assertEquals("149.154.167.220", redirects[4])
        assertNotEquals("149.154.167.51", redirects[2])
        assertNotEquals("149.154.167.91", redirects[2])
        assertNotEquals("149.154.167.51", redirects[4])
        assertNotEquals("149.154.167.91", redirects[4])
    }

    @Test
    fun summariesMatchSmokeTestInstructions() {
        assertEquals("127.0.0.1:1443", ProxyRuntimeConfig.endpointSummary())
        assertEquals("4014...3da8", ProxyRuntimeConfig.partialSecret())
        assertEquals("dd4014e15dd34e4b05c42413eab68c3da8", ProxyRuntimeConfig.TELEGRAM_SECRET_HEX)
        assertEquals("dd40...3da8", ProxyRuntimeConfig.partialTelegramSecret())
    }

    @Test
    fun telegramProxyUriContainsEndpointAndSecret() {
        val uri = ProxyRuntimeConfig.telegramProxyUri()

        assertTrue(uri.startsWith("tg://proxy?"))
        assertTrue(uri.contains("server=127.0.0.1"))
        assertTrue(uri.contains("port=1443"))
        assertTrue(uri.contains("secret=dd4014e15dd34e4b05c42413eab68c3da8"))
    }

    @Test
    fun telegramProxyUrlContainsEndpointAndSecret() {
        val url = ProxyRuntimeConfig.telegramProxyUrl()

        assertTrue(url.startsWith("https://t.me/proxy?"))
        assertTrue(url.contains("server=127.0.0.1"))
        assertTrue(url.contains("port=1443"))
        assertTrue(url.contains("secret=dd4014e15dd34e4b05c42413eab68c3da8"))
    }
}
