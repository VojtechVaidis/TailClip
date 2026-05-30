package com.example.tailclip.ws

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Represents the WebSocket connection state.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

/**
 * Singleton that manages a Ktor WebSocket connection to the TailClip backend.
 *
 * - `connect(host, port)` establishes a WS connection with auto-reconnect.
 * - `disconnect()` cleanly closes everything.
 * - `send(text)` sends clipboard content to the server.
 * - [incomingMessages] emits texts received from the server.
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

    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val incomingMessages: SharedFlow<String> = _incomingMessages.asSharedFlow()

    private var session: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Currently targeted host/port (set by [connect]). */
    private var targetHost: String = ""
    private var targetPort: Int = 8765

    /** Whether we should keep reconnecting. */
    @Volatile
    private var shouldReconnect = false

    /**
     * Start connecting to the given [host]:[port].
     * If already connected to the same endpoint, this is a no-op.
     * If connected to a different endpoint, it disconnects first.
     */
    fun connect(host: String, port: Int) {
        if (shouldReconnect && host == targetHost && port == targetPort) {
            Log.d(TAG, "Already connected/connecting to $host:$port – ignoring")
            return
        }

        disconnect()

        targetHost = host
        targetPort = port
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

                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    Log.d(TAG, "Received ${text.length} chars")
                                    _incomingMessages.emit(text)
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
     * Send [text] to the server. Returns `true` on success, `false` if not connected.
     */
    suspend fun send(text: String): Boolean {
        val s = session ?: return false
        return try {
            s.send(Frame.Text(text))
            Log.d(TAG, "Sent ${text.length} chars")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Send failed: ${e.message}")
            false
        }
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
        Log.i(TAG, "Disconnected")
    }
}
