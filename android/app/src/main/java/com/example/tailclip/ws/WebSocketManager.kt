package com.example.tailclip.ws

import android.util.Log
import com.example.tailclip.data.DeviceInfo
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents the WebSocket connection state.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

/**
 * Represents an incoming clipboard message from another device.
 */
data class ClipboardMessage(
    val content: String,
    val fromDevice: String,
    val fromName: String,
)

/**
 * Represents an incoming file notification from another device.
 */
data class FileMessage(
    val filename: String,
    val fromDevice: String,
    val fromName: String,
)

/**
 * Singleton that manages a Ktor WebSocket connection to the TailClip relay server.
 *
 * Uses JSON protocol for device registration, clipboard sync, and device list updates.
 *
 * - `connect(host, port, deviceId, deviceName)` establishes a WS connection with auto-reconnect.
 * - `disconnect()` cleanly closes everything.
 * - `sendClipboard(text, targetDevices)` sends clipboard content to specific devices.
 * - [incomingClipboard] emits clipboard messages received from the server.
 * - [incomingFiles] emits file notifications received from the server.
 * - [connectedDevices] lists all devices currently connected to the server.
 * - [connectionState] reflects the current connection lifecycle.
 */
object WebSocketManager {

    private const val TAG = "WebSocketManager"
    private const val MAX_RECONNECT_DELAY_MS = 30_000L
    private const val INITIAL_RECONNECT_DELAY_MS = 1_000L

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = 15_000
        }
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingClipboard = MutableSharedFlow<ClipboardMessage>(extraBufferCapacity = 16)
    val incomingClipboard: SharedFlow<ClipboardMessage> = _incomingClipboard.asSharedFlow()

    // Keep backward-compatible: raw text flow for simple consumers
    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val incomingMessages: SharedFlow<String> = _incomingMessages.asSharedFlow()

    private val _incomingFiles = MutableSharedFlow<FileMessage>(extraBufferCapacity = 8)
    val incomingFiles: SharedFlow<FileMessage> = _incomingFiles.asSharedFlow()

    private val _connectedDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val connectedDevices: StateFlow<List<DeviceInfo>> = _connectedDevices.asStateFlow()

    private var session: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Currently targeted host/port (set by [connect]). */
    private var targetHost: String = ""
    private var targetPort: Int = 8765
    private var myDeviceId: String = ""
    private var myDeviceName: String = ""

    /** Whether we should keep reconnecting. */
    @Volatile
    private var shouldReconnect = false

    /**
     * Start connecting to the given [host]:[port].
     * Registers this device with [deviceId] and [deviceName].
     */
    fun connect(host: String, port: Int, deviceId: String = "", deviceName: String = "Android") {
        if (shouldReconnect && host == targetHost && port == targetPort) {
            Log.d(TAG, "Already connected/connecting to $host:$port – ignoring")
            return
        }

        disconnect()

        targetHost = host
        targetPort = port
        myDeviceId = deviceId
        myDeviceName = deviceName
        shouldReconnect = true

        connectionJob = scope.launch {
            var delay = INITIAL_RECONNECT_DELAY_MS
            while (shouldReconnect && isActive) {
                try {
                    _connectionState.value = ConnectionState.CONNECTING
                    Log.i(TAG, "Connecting to ws://$targetHost:$targetPort/ws ...")

                    client.webSocket(host = targetHost, port = targetPort, path = "/ws") {
                        session = this
                        _connectionState.value = ConnectionState.CONNECTED
                        delay = INITIAL_RECONNECT_DELAY_MS // reset on success
                        Log.i(TAG, "Connected!")

                        // Send registration message
                        val registerMsg = JSONObject().apply {
                            put("type", "register")
                            put("device_id", myDeviceId)
                            put("device_name", myDeviceName)
                            put("device_type", "android")
                        }
                        send(Frame.Text(registerMsg.toString()))
                        Log.i(TAG, "Registration sent: $myDeviceName ($myDeviceId)")

                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val raw = frame.readText()
                                    handleMessage(raw)
                                }
                                else -> { /* ignore binary/ping/pong */ }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Connection error: ${e.message}")
                } finally {
                    session = null
                    _connectionState.value = ConnectionState.DISCONNECTED
                }

                if (shouldReconnect) {
                    Log.i(TAG, "Reconnecting in ${delay}ms ...")
                    delay(delay)
                    delay = (delay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                }
            }
        }
    }

    /**
     * Handle an incoming JSON message from the server.
     */
    private suspend fun handleMessage(raw: String) {
        try {
            val msg = JSONObject(raw)
            when (msg.optString("type")) {
                "registered" -> {
                    Log.i(TAG, "Registration confirmed by server")
                }

                "device_list" -> {
                    val devicesArray = msg.optJSONArray("devices") ?: JSONArray()
                    val devices = mutableListOf<DeviceInfo>()
                    for (i in 0 until devicesArray.length()) {
                        val d = devicesArray.getJSONObject(i)
                        val id = d.optString("device_id", "")
                        devices.add(
                            DeviceInfo(
                                deviceId = id,
                                deviceName = d.optString("device_name", "Unknown"),
                                deviceType = d.optString("device_type", "unknown"),
                                isSelf = id == myDeviceId,
                            )
                        )
                    }
                    _connectedDevices.value = devices
                    Log.i(TAG, "Device list updated: ${devices.size} devices")
                }

                "clipboard" -> {
                    val content = msg.optString("content", "")
                    val fromDevice = msg.optString("from_device", "")
                    val fromName = msg.optString("from_name", "Unknown")

                    if (content.isNotEmpty()) {
                        Log.d(TAG, "Received clipboard from $fromName (${content.length} chars)")
                        _incomingClipboard.emit(
                            ClipboardMessage(content, fromDevice, fromName)
                        )
                        // Also emit on legacy flow for backward compatibility
                        _incomingMessages.emit(content)
                    }
                }

                "file" -> {
                    val filename = msg.optString("filename", "")
                    val fromDevice = msg.optString("from_device", "")
                    val fromName = msg.optString("from_name", "Unknown")

                    if (filename.isNotEmpty()) {
                        Log.i(TAG, "File notification from $fromName: $filename")
                        _incomingFiles.emit(
                            FileMessage(filename, fromDevice, fromName)
                        )
                        // Also emit on legacy flow with TAILCLIP_FILE prefix
                        _incomingMessages.emit("[TAILCLIP_FILE]:$filename")
                    }
                }

                "error" -> {
                    Log.e(TAG, "Server error: ${msg.optString("message")}")
                }

                else -> {
                    Log.w(TAG, "Unknown message type: ${msg.optString("type")}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message: ${e.message}")
        }
    }

    /**
     * Send clipboard [text] to [targetDevices].
     * @param targetDevices list of device IDs, or "all" to broadcast
     * Returns `true` on success, `false` if not connected.
     */
    suspend fun sendClipboard(text: String, targetDevices: Any = "all"): Boolean {
        val s = session ?: return false
        return try {
            val msg = JSONObject().apply {
                put("type", "clipboard")
                put("content", text)
                when (targetDevices) {
                    is String -> put("to_devices", targetDevices)
                    is List<*> -> {
                        val arr = JSONArray()
                        targetDevices.forEach { arr.put(it) }
                        put("to_devices", arr)
                    }
                }
            }
            s.send(Frame.Text(msg.toString()))
            Log.d(TAG, "Sent clipboard (${text.length} chars)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Send failed: ${e.message}")
            false
        }
    }

    /**
     * Legacy send method – sends clipboard text to all devices.
     * Kept for backward compatibility with SendActivity and TileService.
     */
    suspend fun send(text: String): Boolean {
        return sendClipboard(text, "all")
    }

    /**
     * Disconnect and stop reconnecting.
     */
    fun disconnect() {
        shouldReconnect = false
        connectionJob?.cancel()
        connectionJob = null
        session = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDevices.value = emptyList()
        Log.i(TAG, "Disconnected")
    }
}
