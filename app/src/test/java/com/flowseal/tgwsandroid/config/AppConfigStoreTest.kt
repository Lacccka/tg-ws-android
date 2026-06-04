package com.flowseal.tgwsandroid.config

<<<<<<< ours
import android.content.SharedPreferences
=======
>>>>>>> theirs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigStoreTest {
    @Test
<<<<<<< ours
    fun secretGeneratedWhenMissing() {
        val config = AppConfigStore.getConfig(FakeSharedPreferences())

        assertTrue(config.secret.matches(Regex("^[0-9a-f]{32}$")))
    }

    @Test
    fun secretPersistsBetweenGetConfigCalls() {
        val prefs = FakeSharedPreferences()

        val first = AppConfigStore.getConfig(prefs)
        val second = AppConfigStore.getConfig(prefs)

        assertEquals(first.secret, second.secret)
    }

    @Test
    fun resetSecretChangesSecretButKeepsEndpoint() {
        val prefs = FakeSharedPreferences().also {
            it.edit().putString("host", "0.0.0.0").putInt("port", 2443).apply()
        }
        val first = AppConfigStore.getConfig(prefs)

        val reset = AppConfigStore.resetSecret(prefs)

        assertNotEquals(first.secret, reset.secret)
        assertEquals("0.0.0.0", reset.host)
        assertEquals(2443, reset.port)
    }

    @Test
    fun resetConfigRestoresDefaultsWithNewSecret() {
        val prefs = FakeSharedPreferences().also {
            it.edit()
                .putString("host", "0.0.0.0")
                .putInt("port", 2443)
                .putInt("pool_size", 1)
                .putBoolean("cfproxy", false)
                .apply()
        }
        val first = AppConfigStore.getConfig(prefs)

        val reset = AppConfigStore.resetConfig(prefs)

        assertNotEquals(first.secret, reset.secret)
        assertEquals(AppConfig.DEFAULT_HOST, reset.host)
        assertEquals(AppConfig.DEFAULT_PORT, reset.port)
        assertEquals(AppConfig.DEFAULT_POOL_SIZE, reset.poolSize)
        assertEquals(true, reset.cfproxy)
        assertTrue(reset.secret.matches(Regex("^[0-9a-f]{32}$")))
    }
}

internal class FakeSharedPreferences : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = LinkedHashSet<String>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key.orEmpty()] = value }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { pending[key.orEmpty()] = values?.toSet() }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key.orEmpty()] = value }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key.orEmpty()] = value }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key.orEmpty()] = value }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key.orEmpty()] = value }

        override fun remove(key: String?): SharedPreferences.Editor = apply { removals.add(key.orEmpty()) }

        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) values.clear()
            removals.forEach(values::remove)
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
=======
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
            cfproxyWorkerDomain = listOf("worker.example"),
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
    }

    @Test
    fun `resetConfig resets runtime settings to defaults but preserves valid existing secret`() {
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
>>>>>>> theirs
        }
    }
}
