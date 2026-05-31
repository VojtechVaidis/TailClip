package com.example.tailclip

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.tailclip.data.SettingsRepository
import com.example.tailclip.ws.ConnectionState
import com.example.tailclip.ws.HttpManager
import com.example.tailclip.ws.WebSocketManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * An invisible activity that handles "Share" actions from other apps.
 * Allows sending text to connected devices via Android's native Share menu.
 * Uses the configured target device selection from settings.
 */
class SendActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        val type = intent?.type

        if (action == Intent.ACTION_SEND) {
            if (type == "text/plain") {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    sendToPc(sharedText)
                } else {
                    handleUriFallback()
                }
            } else {
                handleUriFallback()
            }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (uris != null && uris.isNotEmpty()) {
                uploadFiles(uris)
            } else {
                Toast.makeText(this, "TailClip: No files found", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            finish()
        }
    }

    private fun handleUriFallback() {
        val uri = intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri != null) {
            uploadFiles(listOf(uri))
        } else {
            Toast.makeText(this, "TailClip: Unsupported content", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * Read target devices from settings and return as the appropriate type.
     */
    private suspend fun getTargets(): Any {
        val settings = SettingsRepository(applicationContext)
        val prefs = settings.settings.first()
        return if (prefs.targetDevices == "all") {
            "all"
        } else {
            prefs.targetDevices.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }

    private fun sendToPc(text: String) {
        val state = WebSocketManager.connectionState.value
        if (state != ConnectionState.CONNECTED) {
            Toast.makeText(this, "TailClip: Not connected", Toast.LENGTH_SHORT).show()
            Log.w("SendActivity", "Not connected")
            finish()
            return
        }

        lifecycleScope.launch {
            val targets = getTargets()
            val success = WebSocketManager.sendClipboard(text, targets)
            if (success) {
                Toast.makeText(this@SendActivity, "TailClip: Text sent ✓", Toast.LENGTH_SHORT).show()
                Log.i("SendActivity", "Sent ${text.length} chars from Share menu → $targets")
            } else {
                Toast.makeText(this@SendActivity, "TailClip: Send failed", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun uploadFiles(uris: List<Uri>) {
        val state = WebSocketManager.connectionState.value
        if (state != ConnectionState.CONNECTED) {
            Toast.makeText(this, "TailClip: Not connected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val settings = SettingsRepository(applicationContext)

        lifecycleScope.launch {
            Toast.makeText(this@SendActivity, "TailClip: Sending...", Toast.LENGTH_SHORT).show()
            val prefs = settings.settings.first()
            var successCount = 0

            for (uri in uris) {
                val success = HttpManager.uploadFile(applicationContext, uri, prefs.host, prefs.port)
                if (success) successCount++
            }

            if (successCount == uris.size) {
                Toast.makeText(this@SendActivity, "TailClip: Files sent ✓", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SendActivity, "TailClip: Some files failed", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }
}
