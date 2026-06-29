package com.flowseal.tgwsandroid.service

import com.flowseal.tgwsandroid.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRuntimeConfigTest {
    private val appConfig = AppConfig(secret = "4014e15dd34e4b05c42413eab68c3da8", verbose = true)

    @Test
    fun smokeTestAppConfigMapsToProxyServerConfig() {
        val config = ProxyRuntimeConfig.proxyServerConfig(appConfig)

        assertEquals("127.0.0.1", config.host)
        assertEquals(1443, config.port)
        assertEquals("4014e15dd34e4b05c42413eab68c3da8", config.secretHex)
        assertEquals(
            mapOf(
                2 to "149.154.167.220",
                4 to "149.154.167.220",
            ),
            config.dcRedirects,
        )
        assertEquals(256 * 1024, config.bufferSizeBytes)
        assertEquals(4, config.poolSize)
        assertTrue(config.cfproxyEnabled)
    }

    @Test
    fun dcRedirectsKeepOnlyDc2Dc4OnKnownWorkingTarget() {
        val redirects = ProxyRuntimeConfig.proxyServerConfig(appConfig).dcRedirects

        assertEquals("149.154.167.220", redirects[2])
        assertEquals("149.154.167.220", redirects[4])
        assertFalse(redirects.containsKey(3))
        assertNotEquals("149.154.167.51", redirects[2])
        assertNotEquals("149.154.167.91", redirects[2])
        assertNotEquals("149.154.167.51", redirects[4])
        assertNotEquals("149.154.167.91", redirects[4])
    }

    @Test
    fun summariesMatchSmokeTestInstructions() {
        assertEquals("127.0.0.1:1443", ProxyRuntimeConfig.endpointSummary(appConfig))
        assertEquals("4014••••3da8", ProxyRuntimeConfig.partialSecret(appConfig))
        assertEquals("dd4014e15dd34e4b05c42413eab68c3da8", ProxyRuntimeConfig.telegramSecretHex(appConfig))
        assertEquals("dd40••••3da8", ProxyRuntimeConfig.partialTelegramSecret(appConfig))
        assertEquals("2,4 via 149.154.167.220", ProxyRuntimeConfig.dcSummary(appConfig))
        assertEquals("enabled", ProxyRuntimeConfig.cfFallbackSummary(appConfig))
    }

    @Test
    fun telegramProxyUrlUsesPersistedSecret() {
        val config = AppConfig(secret = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

        val url = ProxyRuntimeConfig.telegramProxyUrl(config)

        assertTrue(url.contains("secret=ddaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
    }

    @Test
    fun telegramProxyUriContainsEndpointAndSecretFromAppConfigSecret() {
        val config = appConfig.copy(secret = "0123456789abcdeffedcba9876543210")

        val uri = ProxyRuntimeConfig.telegramProxyUri(config)

        assertTrue(uri.startsWith("tg://proxy?"))
        assertTrue(uri.contains("server=127.0.0.1"))
        assertTrue(uri.contains("port=1443"))
        assertTrue(uri.contains("secret=dd0123456789abcdeffedcba9876543210"))
    }

    @Test
    fun proxyServerRestartConfigKeepsSameSecret() {
        val firstStart = ProxyRuntimeConfig.proxyServerConfig(appConfig)
        val restarted = ProxyRuntimeConfig.proxyServerConfig(appConfig)

        assertEquals(firstStart.secretHex, restarted.secretHex)
        assertEquals("4014e15dd34e4b05c42413eab68c3da8", restarted.secretHex)
    }

    @Test
    fun telegramProxyUrlContainsEndpointAndSecretFromAppConfigSecret() {
        val config = appConfig.copy(secret = "0123456789abcdeffedcba9876543210")

        val url = ProxyRuntimeConfig.telegramProxyUrl(config)

        assertTrue(url.startsWith("https://t.me/proxy?"))
        assertTrue(url.contains("server=127.0.0.1"))
        assertTrue(url.contains("port=1443"))
        assertTrue(url.contains("secret=dd0123456789abcdeffedcba9876543210"))
    }
}
