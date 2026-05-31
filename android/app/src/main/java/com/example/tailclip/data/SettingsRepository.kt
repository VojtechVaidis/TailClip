package com.example.tailclip.data

import android.content.Context
import android.os.Build
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
    val autoConnect: Boolean = false,
    val deviceName: String = "",
    /** Comma-separated device IDs, or "all" */
    val targetDevices: String = "all",
)

/**
 * Persists TailClip settings (host, port, autoConnect, deviceName, targetDevices)
 * using Jetpack DataStore.
 */
class SettingsRepository(private val context: Context) {

    companion object {
        private val HOST_KEY = stringPreferencesKey("host")
        private val PORT_KEY = intPreferencesKey("port")
        private val AUTO_CONNECT_KEY = booleanPreferencesKey("auto_connect")
        private val DEVICE_NAME_KEY = stringPreferencesKey("device_name")
        private val TARGET_DEVICES_KEY = stringPreferencesKey("target_devices")

        /** Auto-detect a friendly device name from the build model. */
        fun getDefaultDeviceName(): String {
            val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            val model = Build.MODEL
            return if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model"
            }
        }
    }

    /** Observable stream of current settings. */
    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            host = prefs[HOST_KEY] ?: "",
            port = prefs[PORT_KEY] ?: 8765,
            autoConnect = prefs[AUTO_CONNECT_KEY] ?: false,
            deviceName = prefs[DEVICE_NAME_KEY] ?: getDefaultDeviceName(),
            targetDevices = prefs[TARGET_DEVICES_KEY] ?: "all",
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

    /** Update display name for this device. */
    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { it[DEVICE_NAME_KEY] = name }
    }

    /**
     * Update target devices.
     * @param targets "all" or comma-separated device IDs
     */
    suspend fun setTargetDevices(targets: String) {
        context.dataStore.edit { it[TARGET_DEVICES_KEY] = targets }
    }
}
