package com.flowseal.tgwsandroid.telemetry

import android.content.Context
import java.util.UUID

class InstallIdStore(
    private val storage: Storage,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    fun getOrCreateInstallId(): String {
        val existing = storage.getString(KEY_INSTALL_ID)?.trim().orEmpty()
        if (existing.isNotEmpty()) return existing
        val generated = idGenerator().trim().ifEmpty { UUID.randomUUID().toString() }
        storage.putString(KEY_INSTALL_ID, generated)
        return generated
    }

    interface Storage {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
    }

    private class SharedPreferencesStorage(
        private val preferences: android.content.SharedPreferences,
    ) : Storage {
        override fun getString(key: String): String? = preferences.getString(key, null)
        override fun putString(key: String, value: String) {
            preferences.edit().putString(key, value).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "telemetry"
        private const val KEY_INSTALL_ID = "install_id"

        fun from(context: Context): InstallIdStore = InstallIdStore(
            SharedPreferencesStorage(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
        )
    }
}
