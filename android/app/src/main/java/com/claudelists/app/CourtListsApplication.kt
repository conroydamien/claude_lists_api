package com.claudelists.app

import android.app.Application
import android.util.Log
import com.claudelists.app.api.AuthManager
import com.claudelists.app.api.CourtListsApi
import com.claudelists.app.api.NotificationChange
import com.claudelists.app.api.RealtimeClient
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private const val TAG = "CourtListsApp"

class CourtListsApplication : Application() {

    // Application-scoped coroutine scope that survives activity recreation
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Shared HTTP client for all API calls
    private lateinit var httpClient: HttpClient

    // API clients
    lateinit var authManager: AuthManager
        private set
    lateinit var api: CourtListsApi
        private set
    lateinit var realtimeClient: RealtimeClient
        private set

    // SharedFlows to emit notifications to any active ViewModel
    private val _statusChangeEvents = MutableSharedFlow<Unit>()
    val statusChangeEvents: SharedFlow<Unit> = _statusChangeEvents

    private val _commentChangeEvents = MutableSharedFlow<Unit>()
    val commentChangeEvents: SharedFlow<Unit> = _commentChangeEvents

    private val _notificationEvents = MutableSharedFlow<NotificationEvent>()
    val notificationEvents: SharedFlow<NotificationEvent> = _notificationEvents

    private val _watchedCaseEvents = MutableSharedFlow<Unit>()
    val watchedCaseEvents: SharedFlow<Unit> = _watchedCaseEvents

    private var realtimeSetup = false

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Shared HTTP client with JSON and WebSocket support
        httpClient = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                    explicitNulls = false
                })
            }
            install(WebSockets)
        }

        // Initialize API clients with shared HTTP client
        authManager = AuthManager(this)
        api = CourtListsApi(httpClient, authManager)
        realtimeClient = RealtimeClient(httpClient, authManager)

        Log.i(TAG, "Application created")
    }

    fun setupRealtimeIfNeeded() {
        if (realtimeSetup) {
            Log.i(TAG, "Realtime already set up")
            return
        }
        realtimeSetup = true

        applicationScope.launch {
            try {
                Log.i(TAG, "Setting up application-level realtime...")

                realtimeClient.connect()
                Log.i(TAG, "Realtime connected")

                // Collect status changes
                launch {
                    realtimeClient.statusChangeEvents.collect {
                        Log.d(TAG, "case_status change")
                        _statusChangeEvents.emit(Unit)
                    }
                }

                // Collect comment changes
                launch {
                    realtimeClient.commentChangeEvents.collect {
                        Log.d(TAG, "comments change")
                        _commentChangeEvents.emit(Unit)
                    }
                }

                // Collect notifications and show system notification
                launch {
                    realtimeClient.notificationEvents.collect { change ->
                        Log.d(TAG, "notification received: ${change.caseNumber}")
                        handleNotification(change)
                    }
                }

                // Collect watched case changes
                launch {
                    realtimeClient.watchedCaseEvents.collect {
                        Log.d(TAG, "watched_cases change")
                        _watchedCaseEvents.emit(Unit)
                    }
                }

                Log.i(TAG, "All collectors set up")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup realtime", e)
                realtimeSetup = false // Allow retry
            }
        }
    }

    private suspend fun handleNotification(change: NotificationChange) {
        try {
            val title = when (change.type) {
                "comment" -> "New Comment"
                "status_done" -> "Case Marked Done"
                "status_undone" -> "Case Marked Not Done"
                else -> "Case Update"
            }
            val message = "Case ${change.caseNumber}"

            Log.i(TAG, "Showing notification for ${change.caseNumber}")
            NotificationHelper.showNotification(
                context = this@CourtListsApplication,
                title = title,
                message = message,
                listSourceUrl = change.listSourceUrl,
                caseNumber = change.caseNumber,
                notificationType = change.type
            )

            // Emit event for ViewModel to update UI
            _notificationEvents.emit(
                NotificationEvent(
                    type = change.type,
                    caseNumber = change.caseNumber,
                    listSourceUrl = change.listSourceUrl
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process notification", e)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        httpClient.close()
        authManager.close()
    }

    companion object {
        lateinit var instance: CourtListsApplication
            private set
    }
}

data class NotificationEvent(
    val type: String,
    val caseNumber: String,
    val listSourceUrl: String
)
