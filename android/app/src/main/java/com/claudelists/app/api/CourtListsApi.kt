package com.claudelists.app.api

import android.util.Log
import com.claudelists.app.BuildConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

private const val TAG = "CourtListsApi"

/**
 * REST API client for Court Lists backend.
 *
 * Implements the API defined in supabase/api.yaml
 * Types defined in supabase/functions/_shared/types.ts
 */
class CourtListsApi(private val authManager: AuthManager) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    Log.d(TAG, message)
                }
            }
            level = LogLevel.HEADERS
        }
    }

    private val baseUrl = BuildConfig.API_BASE_URL
    private val anonKey = BuildConfig.API_ANON_KEY

    private suspend fun HttpRequestBuilder.addAuth() {
        header("apikey", anonKey)
        authManager.getAccessToken()?.let { token ->
            header("Authorization", "Bearer $token")
        }
    }

    // =========================================================================
    // Edge Functions
    // =========================================================================

    /** Get court listings for a date (POST /functions/v1/listings) */
    suspend fun getListings(date: String): List<DiaryEntry> {
        val response = client.post("$baseUrl/functions/v1/listings") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(ListingsRequest(date))
        }
        return response.body()
    }

    /** Get cases for a listing (POST /functions/v1/cases) */
    suspend fun getCases(url: String): CasesResponse {
        val response = client.post("$baseUrl/functions/v1/cases") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(CasesRequest(url))
        }
        return response.body()
    }

    // =========================================================================
    // Comments
    // =========================================================================

    /** Get comments for a case */
    suspend fun getComments(listSourceUrl: String, caseNumber: String): List<Comment> {
        val response = client.get("$baseUrl/rest/v1/comments") {
            addAuth()
            header("Accept", "application/json")
            parameter("list_source_url", "eq.$listSourceUrl")
            parameter("case_number", "eq.$caseNumber")
            parameter("order", "created_at.asc")
        }
        return response.body()
    }

    /** Get comment counts for multiple cases */
    suspend fun getCommentCounts(listSourceUrl: String, caseNumbers: List<String>): List<CommentCount> {
        val inClause = caseNumbers.joinToString(",") { "\"$it\"" }
        val response = client.get("$baseUrl/rest/v1/comments") {
            addAuth()
            header("Accept", "application/json")
            parameter("list_source_url", "eq.$listSourceUrl")
            parameter("case_number", "in.($inClause)")
            parameter("select", "case_number")
        }
        return response.body()
    }

    @kotlinx.serialization.Serializable
    data class CommentCount(
        @kotlinx.serialization.SerialName("case_number") val caseNumber: String
    )

    /** Add a comment */
    suspend fun addComment(comment: Comment) {
        client.post("$baseUrl/rest/v1/comments") {
            addAuth()
            contentType(ContentType.Application.Json)
            header("Prefer", "return=minimal")
            setBody(comment)
        }
    }

    /** Delete a comment */
    suspend fun deleteComment(id: Long) {
        client.delete("$baseUrl/rest/v1/comments") {
            addAuth()
            parameter("id", "eq.$id")
        }
    }

    // =========================================================================
    // Case Status
    // =========================================================================

    /** Get statuses for cases */
    suspend fun getCaseStatuses(listSourceUrl: String, caseNumbers: List<String>): List<CaseStatus> {
        val inClause = caseNumbers.joinToString(",") { "\"$it\"" }
        val response = client.get("$baseUrl/rest/v1/case_status") {
            addAuth()
            header("Accept", "application/json")
            parameter("list_source_url", "eq.$listSourceUrl")
            parameter("case_number", "in.($inClause)")
        }
        return response.body()
    }

    /** Upsert case status */
    suspend fun upsertCaseStatus(status: CaseStatus) {
        client.post("$baseUrl/rest/v1/case_status") {
            addAuth()
            contentType(ContentType.Application.Json)
            header("Prefer", "resolution=merge-duplicates")
            setBody(status)
        }
    }

    // =========================================================================
    // Watched Cases
    // =========================================================================

    /** Get watched cases for current user */
    suspend fun getWatchedCases(userId: String): List<WatchedCase> {
        val response = client.get("$baseUrl/rest/v1/watched_cases") {
            addAuth()
            header("Accept", "application/json")
            parameter("user_id", "eq.$userId")
        }
        return response.body()
    }

    /** Watch a case */
    suspend fun watchCase(watchedCase: WatchedCase) {
        client.post("$baseUrl/rest/v1/watched_cases") {
            addAuth()
            contentType(ContentType.Application.Json)
            header("Prefer", "return=minimal")
            setBody(watchedCase)
        }
    }

    /** Unwatch a case */
    suspend fun unwatchCase(userId: String, listSourceUrl: String, caseNumber: String) {
        client.delete("$baseUrl/rest/v1/watched_cases") {
            addAuth()
            parameter("user_id", "eq.$userId")
            parameter("list_source_url", "eq.$listSourceUrl")
            parameter("case_number", "eq.$caseNumber")
        }
    }

    // =========================================================================
    // Notifications
    // =========================================================================

    /** Get notifications for current user */
    suspend fun getNotifications(userId: String, limit: Int = 50): List<AppNotification> {
        val response = client.get("$baseUrl/rest/v1/notifications") {
            addAuth()
            header("Accept", "application/json")
            parameter("user_id", "eq.$userId")
            parameter("order", "created_at.desc")
            parameter("limit", limit.toString())
        }
        return response.body()
    }

    /** Mark notification as read */
    suspend fun markNotificationRead(id: Long) {
        client.patch("$baseUrl/rest/v1/notifications") {
            addAuth()
            contentType(ContentType.Application.Json)
            parameter("id", "eq.$id")
            setBody(mapOf("read" to true))
        }
    }

    /** Mark all notifications as read */
    suspend fun markAllNotificationsRead(userId: String) {
        client.patch("$baseUrl/rest/v1/notifications") {
            addAuth()
            contentType(ContentType.Application.Json)
            parameter("user_id", "eq.$userId")
            parameter("read", "eq.false")
            setBody(mapOf("read" to true))
        }
    }

    fun close() {
        client.close()
    }
}
