package com.example.tailclip.ui.main

import android.app.Application
import android.provider.Settings.Secure
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tailclip.data.DeviceInfo
import com.example.tailclip.data.Settings
import com.example.tailclip.data.SettingsRepository
import com.example.tailclip.service.ClipboardForegroundService
import com.example.tailclip.ws.ConnectionState
import com.example.tailclip.ws.WebSocketManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * UI state for the main screen.
 */
data class MainUiState(
    val host: String = "",
    val port: String = "8765",
    val deviceName: String = "",
    val isServiceRunning: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val lastSyncPreview: String = "",
    val connectedDevices: List<DeviceInfo> = emptyList(),
    /** "all" or comma-separated device IDs */
    val targetDevices: String = "all",
    val sendToAll: Boolean = true,
    /** Set of checked device IDs (used when sendToAll is false) */
    val selectedDeviceIds: Set<String> = emptySet(),
)

/**
 * ViewModel for the TailClip main screen.
 */
class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SettingsRepository(application)
    private val deviceId: String = getAndroidId(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        // Observe persisted settings
        viewModelScope.launch {
            repo.settings.collect { settings ->
                val sendToAll = settings.targetDevices == "all"
                val selectedIds = if (sendToAll) {
                    emptySet()
                } else {
                    settings.targetDevices.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                }
                _uiState.update {
                    it.copy(
                        host = settings.host,
                        port = settings.port.toString(),
                        deviceName = settings.deviceName,
                        targetDevices = settings.targetDevices,
                        sendToAll = sendToAll,
                        selectedDeviceIds = selectedIds,
                    )
                }
            }
        }

        // Observe WebSocket connection state
        viewModelScope.launch {
            WebSocketManager.connectionState.collect { state ->
                _uiState.update {
                    it.copy(
                        connectionState = state,
                        isServiceRunning = state != ConnectionState.DISCONNECTED
                    )
                }
            }
        }

        // Observe connected device list
        viewModelScope.launch {
            WebSocketManager.connectedDevices.collect { devices ->
                _uiState.update {
                    it.copy(connectedDevices = devices)
                }
            }
        }

        // Observe incoming clipboard messages for preview
        viewModelScope.launch {
            WebSocketManager.incomingClipboard.collect { msg ->
                _uiState.update {
                    it.copy(lastSyncPreview = "${msg.fromName}: ${msg.content.take(80)}")
                }
            }
        }
    }

    fun onHostChange(host: String) {
        _uiState.update { it.copy(host = host) }
        viewModelScope.launch { repo.setHost(host) }
    }

    fun onPortChange(port: String) {
        _uiState.update { it.copy(port = port) }
        val portInt = port.toIntOrNull() ?: return
        viewModelScope.launch { repo.setPort(portInt) }
    }

    fun onDeviceNameChange(name: String) {
        _uiState.update { it.copy(deviceName = name) }
        viewModelScope.launch { repo.setDeviceName(name) }
    }

    fun onSendToAllToggle(enabled: Boolean) {
        _uiState.update { it.copy(sendToAll = enabled) }
        viewModelScope.launch {
            if (enabled) {
                repo.setTargetDevices("all")
            } else {
                val ids = _uiState.value.selectedDeviceIds.joinToString(",")
                repo.setTargetDevices(ids.ifEmpty { "all" })
            }
        }
    }

    fun onToggleDevice(deviceId: String) {
        val current = _uiState.value.selectedDeviceIds.toMutableSet()
        if (current.contains(deviceId)) {
            current.remove(deviceId)
        } else {
            current.add(deviceId)
        }
        _uiState.update { it.copy(selectedDeviceIds = current) }
        viewModelScope.launch {
            val targets = if (current.isEmpty()) "all" else current.joinToString(",")
            repo.setTargetDevices(targets)
        }
    }

    fun toggleService() {
        val ctx = getApplication<Application>()
        val state = _uiState.value

        if (state.isServiceRunning) {
            ClipboardForegroundService.stop(ctx)
        } else {
            if (state.host.isBlank()) return
            viewModelScope.launch {
                repo.setHost(state.host)
                repo.setPort(state.port.toIntOrNull() ?: 8765)
                if (state.deviceName.isNotBlank()) {
                    repo.setDeviceName(state.deviceName)
                }
            }
            ClipboardForegroundService.start(ctx)
        }
    }

    companion object {
        /** Get a stable device ID from Android Settings. */
        fun getAndroidId(context: android.content.Context): String {
            return Secure.getString(context.contentResolver, Secure.ANDROID_ID) ?: "unknown"
        }
    }
}
