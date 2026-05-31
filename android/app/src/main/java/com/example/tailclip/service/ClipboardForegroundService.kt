package com.example.tailclip.service

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings.Secure
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.tailclip.MainActivity
import com.example.tailclip.R
import com.example.tailclip.data.Settings
import com.example.tailclip.data.SettingsRepository
import com.example.tailclip.ws.ConnectionState
import com.example.tailclip.ws.HttpManager
import com.example.tailclip.ws.WebSocketManager
import java.net.URLDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Foreground service that keeps a persistent WebSocket connection alive.
 *
 * When the server sends clipboard text (from any device),
 * this service writes it to the system ClipboardManager.
 *
 * Clipboard changes detected via polling are sent to the server
 * with the configured target device selection.
 */
class ClipboardForegroundService : Service() {

    companion object {
        private const val TAG = "ClipboardFGS"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "tailclip_fgs_channel"

        /** Extras key for forcing a disconnect/stop. */
        const val ACTION_STOP = "com.example.tailclip.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, ClipboardForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ClipboardForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var settingsRepository: SettingsRepository
    private var lastReceivedText: String? = null
    private var lastPolledText: String? = null

    private fun startClipboardPolling() {
        serviceScope.launch {
            while (isActive) {
                delay(1000) // Poll every 1 second

                if (WebSocketManager.connectionState.value != ConnectionState.CONNECTED) {
                    continue
                }

                withContext(Dispatchers.Main) {
                    try {
                        val clip = clipboardManager.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val text = clip.getItemAt(0).coerceToText(this@ClipboardForegroundService).toString()

                            // Only send if it's new text, not empty, and not what we just received
                            if (text.isNotBlank() && text != lastPolledText && text != lastReceivedText) {
                                lastPolledText = text

                                // Read target devices from settings
                                serviceScope.launch {
                                    val prefs = settingsRepository.settings.first()
                                    val targets: Any = if (prefs.targetDevices == "all") {
                                        "all"
                                    } else {
                                        prefs.targetDevices.split(",")
                                            .map { it.trim() }
                                            .filter { it.isNotEmpty() }
                                    }

                                    val success = WebSocketManager.sendClipboard(text, targets)
                                    if (success) {
                                        Log.i(TAG, "Auto-polled clipboard sent (${text.length} chars) → $targets")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // In case of any permission issues or crashes while polling
                        Log.w(TAG, "Polling clipboard failed: ${e.message}")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        settingsRepository = SettingsRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stop requested")
            WebSocketManager.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Promote to foreground immediately
        val notification = buildNotification("Connecting...")
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )

        // Start connection and message handling
        serviceScope.launch {
            val settings = settingsRepository.settings.first()
            if (settings.host.isBlank()) {
                Log.w(TAG, "No host configured – stopping")
                stopSelf()
                return@launch
            }

            // Get stable device ID from Android Settings
            val deviceId = Secure.getString(contentResolver, Secure.ANDROID_ID) ?: "unknown"
            val deviceName = settings.deviceName.ifBlank {
                SettingsRepository.getDefaultDeviceName()
            }

            WebSocketManager.connect(settings.host, settings.port, deviceId, deviceName)

            // Update notification on connection state changes
            launch {
                WebSocketManager.connectionState.collect { state ->
                    val text = when (state) {
                        ConnectionState.DISCONNECTED -> "Disconnected – reconnecting..."
                        ConnectionState.CONNECTING -> "Connecting to ${settings.host}..."
                        ConnectionState.CONNECTED -> "Connected to ${settings.host}:${settings.port}"
                    }
                    updateNotification(text)
                }
            }

            // Handle incoming clipboard content
            launch {
                WebSocketManager.incomingClipboard.collect { msg ->
                    Log.i(TAG, "Received clipboard from ${msg.fromName} (${msg.content.length} chars)")
                    withContext(Dispatchers.Main) {
                        lastReceivedText = msg.content
                        lastPolledText = msg.content // Prevent poller from echoing it back
                        val clip = ClipData.newPlainText("TailClip", msg.content)
                        clipboardManager.setPrimaryClip(clip)
                    }
                    updateNotification("📋 From ${msg.fromName}: ${msg.content.take(30)}${if (msg.content.length > 30) "…" else ""}")
                }
            }

            // Handle incoming file notifications
            launch {
                WebSocketManager.incomingFiles.collect { fileMsg ->
                    Log.i(TAG, "File from ${fileMsg.fromName}: ${fileMsg.filename}")
                    val prefs = settingsRepository.settings.first()
                    HttpManager.downloadFile(applicationContext, fileMsg.filename, prefs.host, prefs.port)
                    updateNotification("📁 File from ${fileMsg.fromName}: ${fileMsg.filename}")
                }
            }

            // Start the background poller
            startClipboardPolling()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        WebSocketManager.disconnect()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TailClip Sync",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification for clipboard sync"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPending = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ClipboardForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("TailClip")
            .setContentText(contentText)
            .setContentIntent(tapPending)
            .addAction(R.drawable.ic_notification, "Stop", stopPending)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
