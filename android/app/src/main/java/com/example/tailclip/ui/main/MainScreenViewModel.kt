package com.example.tailclip.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val isServiceRunning: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val lastSyncPreview: String = "",
)

/**
 * ViewModel for the TailClip main screen.
 */
class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SettingsRepository(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        // Observe persisted settings
        viewModelScope.launch {
            repo.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        host = settings.host,
                        port = settings.port.toString()
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

        // Observe incoming messages for preview
        viewModelScope.launch {
            WebSocketManager.incomingMessages.collect { text ->
                _uiState.update {
                    it.copy(lastSyncPreview = text.take(100))
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
            }
            ClipboardForegroundService.start(ctx)
        }
    }
}
