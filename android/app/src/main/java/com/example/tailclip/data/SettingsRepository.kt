package com.example.tailclip.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** DataStore instance scoped to the application context. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tailclip_settings")

/**
 * Holds the user's connection settings.
 */
data class Settings(
    val host: String = "",
    val port: Int = 8765,
    val autoConnect: Boolean = false
)

/**
 * Persists TailClip settings (host, port, autoConnect) using Jetpack DataStore.
 */
class SettingsRepository(private val context: Context) {

    private companion object {
        val HOST_KEY = stringPreferencesKey("host")
        val PORT_KEY = intPreferencesKey("port")
        val AUTO_CONNECT_KEY = booleanPreferencesKey("auto_connect")
    }

    /** Observable stream of current settings. */
    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            host = prefs[HOST_KEY] ?: "",
            port = prefs[PORT_KEY] ?: 8765,
            autoConnect = prefs[AUTO_CONNECT_KEY] ?: false
        )
    }

    /** Update server host. */
    suspend fun setHost(host: String) {
        context.dataStore.edit { it[HOST_KEY] = host }
    }

    /** Update server port. */
    suspend fun setPort(port: Int) {
        context.dataStore.edit { it[PORT_KEY] = port }
    }

    /** Update auto-connect preference. */
    suspend fun setAutoConnect(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_CONNECT_KEY] = enabled }
    }
}
