package com.claudelists.app.api

import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import java.util.concurrent.TimeUnit

private const val TAG = "WebSocketManager"

data class ItemNotification(
    val operation: String,
    val id: Int,
    val list_id: Int
)

data class CommentNotification(
    val operation: String,
    val id: Int,
    val item_id: Int,
    val list_id: Int
)

class WebSocketManager(
    private val onItemChanged: (ItemNotification) -> Unit,
    private val onCommentChanged: (CommentNotification) -> Unit
) {
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect() {
        val wsUrl = ApiClient.wsUrl
        Log.d(TAG, "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WebSocket message: $text")
                try {
                    // Try to parse as item notification first
                    if (text.contains("\"item_id\"")) {
                        val notification = gson.fromJson(text, CommentNotification::class.java)
                        onCommentChanged(notification)
                    } else {
                        val notification = gson.fromJson(text, ItemNotification::class.java)
                        onItemChanged(notification)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse notification: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                // Reconnect after delay
                reconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
            }
        })
    }

    private fun reconnect() {
        // Wait 3 seconds before reconnecting
        Thread {
            try {
                Thread.sleep(3000)
                connect()
            } catch (e: InterruptedException) {
                // Ignore
            }
        }.start()
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
    }
}
