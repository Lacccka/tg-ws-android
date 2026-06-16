package com.flowseal.tgwsandroid.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class InstallIdStoreTest {
    @Test
    fun `creates random persistent install id without hardware identifiers`() {
        val storage = MemoryStorage()
        val first = InstallIdStore(storage) { "random-install-id" }.getOrCreateInstallId()
        val second = InstallIdStore(storage) { "different-id" }.getOrCreateInstallId()

        assertEquals("random-install-id", first)
        assertEquals(first, second)
        assertNotEquals("different-id", second)
    }

    private class MemoryStorage : InstallIdStore.Storage {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) { values[key] = value }
    }
}
