package com.flowseal.tgwsandroid.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import com.flowseal.tgwsandroid.proxy.NetworkRouteMode
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigStoreTest {

    @Test
    fun `first launch creates and persists generated secret`() {
        val storage = MemoryStorage()
        val firstStore = AppConfigStore(storage) { "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }

        val first = firstStore.loadConfig()
        val second = AppConfigStore(storage) { "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" }.loadConfig()

        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", first.secret)
        assertEquals(first.secret, second.secret)
        assertTrue(AppConfigStore.isValidSecretHex(second.secret))
    }

    @Test
    fun `resetSecret is the only manual operation that changes a valid persisted secret`() {
        val storage = MemoryStorage()
        val store = AppConfigStore(storage) { "11111111111111111111111111111111" }
        val first = store.loadConfig()

        val reloaded = AppConfigStore(storage) { "22222222222222222222222222222222" }.loadConfig()
        val resetConfig = AppConfigStore(storage) { "33333333333333333333333333333333" }.resetConfig()
        val resetSecret = AppConfigStore(storage) { "44444444444444444444444444444444" }.resetSecret()

        assertEquals(first.secret, reloaded.secret)
        assertEquals(first.secret, resetConfig.secret)
        assertEquals("44444444444444444444444444444444", resetSecret.secret)
        assertNotEquals(first.secret, resetSecret.secret)
    }
    @Test
    fun `resetSecret changes only secret and preserves runtime settings`() {
        val store = testStore(
            secrets = listOf("22222222222222222222222222222222"),
        )
        val original = AppConfig(
            host = "0.0.0.0",
            port = 2443,
            secret = "11111111111111111111111111111111",
            poolSize = 9,
            cfproxy = false,
            cfproxyUserDomain = listOf("user.example"),
            cfproxyWorkerDomain = "worker.example",
            dcIp = listOf("1:203.0.113.1"),
            appearance = Appearance.DARK,
            routeMode = NetworkRouteMode.CF_ONLY,
        )
        store.saveConfig(original)

        val updated = store.resetSecret()

        assertEquals("22222222222222222222222222222222", updated.secret)
        assertNotEquals(original.secret, updated.secret)
        assertEquals(original.host, updated.host)
        assertEquals(original.port, updated.port)
        assertEquals(original.poolSize, updated.poolSize)
        assertEquals(original.cfproxy, updated.cfproxy)
        assertEquals(original.cfproxyUserDomain, updated.cfproxyUserDomain)
        assertEquals(original.cfproxyWorkerDomain, updated.cfproxyWorkerDomain)
        assertEquals(original.dcIp, updated.dcIp)
        assertEquals(original.appearance, updated.appearance)
        assertEquals(original.routeMode, updated.routeMode)
    }

    @Test
    fun `resetConfig resets host port poolSize and cfproxy to defaults but preserves valid existing secret`() {
        val store = testStore()
        val validSecret = "abcdefabcdefabcdefabcdefabcdefab"
        store.saveConfig(
            AppConfig(
                host = "0.0.0.0",
                port = 2443,
                secret = validSecret,
                poolSize = 9,
                cfproxy = false,
                dcIp = listOf("1:203.0.113.1"),
                appearance = Appearance.DARK,
            ),
        )

        val updated = store.resetConfig()

        assertEquals(AppConfig.DEFAULT_HOST, updated.host)
        assertEquals(AppConfig.DEFAULT_PORT, updated.port)
        assertEquals(AppConfig.DEFAULT_POOL_SIZE, updated.poolSize)
        assertEquals(true, updated.cfproxy)
        assertEquals(AppConfig.DEFAULT_DC_IP, updated.dcIp)
        assertEquals(Appearance.AUTO, updated.appearance)
        assertEquals(NetworkRouteMode.AUTO, updated.routeMode)
        assertEquals(validSecret, updated.secret)
    }

    @Test
    fun `resetConfig generates secret only if missing or invalid`() {
        val store = testStore(secrets = listOf("33333333333333333333333333333333"))
        store.saveConfig(AppConfig(secret = "not-valid"))

        val invalidReset = store.resetConfig()
        assertEquals("33333333333333333333333333333333", invalidReset.secret)

        store.saveConfig(AppConfig(secret = "44444444444444444444444444444444"))
        val validReset = store.resetConfig()
        assertEquals("44444444444444444444444444444444", validReset.secret)
    }

    @Test
    fun `resetConfig generates secret when missing`() {
        val store = testStore(secrets = listOf("55555555555555555555555555555555"))
        store.saveConfig(AppConfig(secret = ""))

        val updated = store.resetConfig()

        assertEquals("55555555555555555555555555555555", updated.secret)
        assertTrue(AppConfigStore.isValidSecretHex(updated.secret))
    }

    @Test
    fun `routeMode is saved and restored`() {
        val store = testStore()
        store.saveConfig(AppConfig(secret = "11111111111111111111111111111111", routeMode = NetworkRouteMode.CF_FIRST))

        val loaded = store.loadConfig()

        assertEquals(NetworkRouteMode.CF_FIRST, loaded.routeMode)
    }

    @Test
    fun `telemetry is disabled by default and persisted when enabled`() {
        val store = testStore()

        assertEquals(false, store.loadConfig().telemetryEnabled)

        store.saveConfig(store.loadConfig().copy(telemetryEnabled = true))

        assertEquals(true, store.loadConfig().telemetryEnabled)
    }

    @Test
    fun `Telegram secret remains dd plus saved 32 hex secret`() {
        val savedSecret = "0123456789abcdef0123456789abcdef"

        val telegramSecret = AppConfigStore.telegramSecret(savedSecret)

        assertEquals("dd0123456789abcdef0123456789abcdef", telegramSecret)
        assertTrue(telegramSecret.startsWith("dd"))
        assertTrue(AppConfigStore.isValidSecretHex(telegramSecret.removePrefix("dd")))
    }

    private fun testStore(secrets: List<String> = listOf("11111111111111111111111111111111")): AppConfigStore {
        var index = 0
        return AppConfigStore(MemoryStorage()) {
            secrets.getOrElse(index++) { secrets.last() }
        }
    }

    private class MemoryStorage : AppConfigStore.Storage {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }
}
