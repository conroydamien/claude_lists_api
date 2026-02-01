package com.claudelists.app.api

import android.util.Log
import com.claudelists.app.BuildConfig
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private const val TAG = "RealtimeClient"

/**
 * WebSocket client for realtime database change notifications.
 *
 * Subscribes to postgres_changes events for:
 * - case_status: Status changes
 * - comments: Comment additions/deletions
 * - notifications: New notifications for watched cases
 * - watched_cases: Watch list changes
 *
 * Event types defined in supabase/functions/_shared/types.ts
 */
class RealtimeClient(private val authManager: AuthManager) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }

    private val baseUrl = BuildConfig.API_BASE_URL
        .replace("https://", "wss://")
        .replace("http://", "ws://")
    private val anonKey = BuildConfig.API_ANON_KEY

    private var session: WebSocketSession? = null
    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var messageRef = 0

    // Event flows
    private val _statusChangeEvents = MutableSharedFlow<PostgresChange>()
    val statusChangeEvents: SharedFlow<PostgresChange> = _statusChangeEvents

    private val _commentChangeEvents = MutableSharedFlow<PostgresChange>()
    val commentChangeEvents: SharedFlow<PostgresChange> = _commentChangeEvents

    private val _notificationEvents = MutableSharedFlow<NotificationChange>()
    val notificationEvents: SharedFlow<NotificationChange> = _notificationEvents

    private val _watchedCaseEvents = MutableSharedFlow<PostgresChange>()
    val watchedCaseEvents: SharedFlow<PostgresChange> = _watchedCaseEvents

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    /**
     * Connect to realtime server and subscribe to changes.
     */
    suspend fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            Log.d(TAG, "Already connected")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING

        val accessToken = authManager.getAccessToken()
        if (accessToken == null) {
            Log.e(TAG, "No access token, cannot connect")
            _connectionState.value = ConnectionState.ERROR
            return
        }

        val wsUrl = "$baseUrl/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"

        try {
            connectionJob = CoroutineScope(Dispatchers.IO).launch {
                client.webSocket(wsUrl) {
                    session = this
                    Log.i(TAG, "WebSocket connected")
                    _connectionState.value = ConnectionState.CONNECTED

                    // Send access token
                    sendMessage(
                        topic = "realtime:system",
                        event = "access_token",
                        payload = buildJsonObject {
                            put("access_token", accessToken)
                        }
                    )

                    // Join the postgres_changes channel
                    joinChannel(accessToken)

                    // Start heartbeat
                    heartbeatJob = launch {
                        while (isActive) {
                            delay(30_000)
                            sendHeartbeat()
                        }
                    }

                    // Process incoming messages
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> handleMessage(frame.readText())
                            is Frame.Close -> {
                                Log.i(TAG, "WebSocket closed")
                                _connectionState.value = ConnectionState.DISCONNECTED
                            }
                            else -> {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket connection failed", e)
            _connectionState.value = ConnectionState.ERROR
        }
    }

    private suspend fun WebSocketSession.joinChannel(accessToken: String) {
        // Subscribe to all tables we care about
        val tables = listOf("case_status", "comments", "notifications", "watched_cases")

        sendMessage(
            topic = "realtime:postgres_changes",
            event = "phx_join",
            payload = buildJsonObject {
                put("config", buildJsonObject {
                    put("postgres_changes", buildJsonArray {
                        tables.forEach { table ->
                            add(buildJsonObject {
                                put("event", "*")
                                put("schema", "public")
                                put("table", table)
                            })
                        }
                    })
                })
                put("access_token", accessToken)
            }
        )

        Log.i(TAG, "Joined postgres_changes channel for tables: $tables")
    }

    private suspend fun WebSocketSession.sendMessage(
        topic: String,
        event: String,
        payload: JsonObject
    ) {
        val message = buildJsonObject {
            put("topic", topic)
            put("event", event)
            put("payload", payload)
            put("ref", (++messageRef).toString())
        }
        send(Frame.Text(message.toString()))
    }

    private suspend fun sendHeartbeat() {
        session?.sendMessage(
            topic = "phoenix",
            event = "heartbeat",
            payload = buildJsonObject {}
        )
    }

    private suspend fun handleMessage(text: String) {
        try {
            val message = json.parseToJsonElement(text).jsonObject
            val topic = message["topic"]?.jsonPrimitive?.content ?: return
            val event = message["event"]?.jsonPrimitive?.content ?: return
            val payload = message["payload"]?.jsonObject ?: return

            Log.d(TAG, "Received: topic=$topic, event=$event")

            when (event) {
                "postgres_changes" -> handlePostgresChange(payload)
                "phx_reply" -> handleReply(payload)
                "system" -> Log.d(TAG, "System message: $payload")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text", e)
        }
    }

    private suspend fun handlePostgresChange(payload: JsonObject) {
        val data = payload["data"]?.jsonObject ?: return
        val table = data["table"]?.jsonPrimitive?.content ?: return
        val type = data["type"]?.jsonPrimitive?.content ?: return
        val record = data["record"]?.jsonObject
        val oldRecord = data["old_record"]?.jsonObject

        Log.d(TAG, "Postgres change: $table $type")

        val change = PostgresChange(
            table = table,
            type = type,
            record = record,
            oldRecord = oldRecord
        )

        when (table) {
            "case_status" -> _statusChangeEvents.emit(change)
            "comments" -> _commentChangeEvents.emit(change)
            "notifications" -> {
                if (type == "INSERT" && record != null) {
                    val notification = parseNotification(record)
                    if (notification != null) {
                        _notificationEvents.emit(notification)
                    }
                }
            }
            "watched_cases" -> _watchedCaseEvents.emit(change)
        }
    }

    private fun parseNotification(record: JsonObject): NotificationChange? {
        return try {
            NotificationChange(
                type = record["type"]?.jsonPrimitive?.content ?: "",
                caseNumber = record["case_number"]?.jsonPrimitive?.content ?: "",
                listSourceUrl = record["list_source_url"]?.jsonPrimitive?.content ?: "",
                actorName = record["actor_name"]?.jsonPrimitive?.content ?: "",
                content = record["content"]?.jsonPrimitive?.content
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse notification", e)
            null
        }
    }

    private fun handleReply(payload: JsonObject) {
        val status = payload["status"]?.jsonPrimitive?.content
        Log.d(TAG, "Reply status: $status")
    }

    /**
     * Disconnect from realtime server.
     */
    fun disconnect() {
        heartbeatJob?.cancel()
        connectionJob?.cancel()
        session = null
        _connectionState.value = ConnectionState.DISCONNECTED
        Log.i(TAG, "Disconnected")
    }

    fun close() {
        disconnect()
        client.close()
    }
}

/**
 * Generic postgres change event.
 */
data class PostgresChange(
    val table: String,
    val type: String, // INSERT, UPDATE, DELETE
    val record: JsonObject?,
    val oldRecord: JsonObject?
)

/**
 * Parsed notification event for display.
 */
data class NotificationChange(
    val type: String, // comment, status_done, status_undone
    val caseNumber: String,
    val listSourceUrl: String,
    val actorName: String,
    val content: String?
)
