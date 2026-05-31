package com.example.tailclip.service

import android.content.ClipboardManager
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.example.tailclip.data.SettingsRepository
import com.example.tailclip.ws.ConnectionState
import com.example.tailclip.ws.WebSocketManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Quick Settings tile that sends the current clipboard content to selected devices.
 *
 * Mobile → other devices: user copies text, pulls down Quick Settings,
 * taps the TailClip tile, and the clipboard content is sent via WebSocket
 * to the configured target devices.
 */
class ClipboardTileService : TileService() {

    companion object {
        private const val TAG = "ClipboardTile"
    }

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        val state = WebSocketManager.connectionState.value
        if (state != ConnectionState.CONNECTED) {
            showToast("TailClip: Not connected")
            Log.w(TAG, "Not connected – cannot send clipboard")
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip

        if (clip == null || clip.itemCount == 0) {
            showToast("TailClip: Clipboard is empty")
            Log.w(TAG, "Clipboard is empty")
            return
        }

        val text = clip.getItemAt(0).coerceToText(this).toString()
        if (text.isBlank()) {
            showToast("TailClip: Clipboard is empty")
            return
        }

        tileScope.launch {
            // Read target devices from settings
            val settings = SettingsRepository(applicationContext)
            val prefs = settings.settings.first()
            val targets: Any = if (prefs.targetDevices == "all") {
                "all"
            } else {
                prefs.targetDevices.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }

            val success = WebSocketManager.sendClipboard(text, targets)
            withContext(Dispatchers.Main) {
                if (success) {
                    showToast("TailClip: Sent ✓")
                    Log.i(TAG, "Sent clipboard (${text.length} chars) → $targets")
                } else {
                    showToast("TailClip: Send failed")
                    Log.w(TAG, "Failed to send clipboard")
                }
            }
        }
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val state = WebSocketManager.connectionState.value

        when (state) {
            ConnectionState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Connected"
            }
            ConnectionState.CONNECTING -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.subtitle = "Connecting..."
            }
            ConnectionState.DISCONNECTED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Disconnected"
            }
        }
        tile.updateTile()
    }

    private fun showToast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }
}
