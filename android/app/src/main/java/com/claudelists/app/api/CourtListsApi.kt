package com.claudelists.app.api

import android.util.Log
import com.claudelists.app.BuildConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

private const val TAG = "CourtListsApi"

/**
 * REST API client for Court Lists backend.
 *
 * Uses Google ID token for authentication.
 * All authenticated endpoints use the token in Authorization header.
 */
class CourtListsApi(
    private val client: HttpClient,
    private val authManager: AuthManager
) {
    private val baseUrl = BuildConfig.API_BASE_URL

    private suspend fun HttpRequestBuilder.addAuth() {
        authManager.getGoogleIdToken()?.let { token ->
            header("Authorization", "Bearer $token")
        }
    }

    // =========================================================================
    // Listings & Cases (JWT required to prevent abuse)
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

    /** Get comments for a case (JWT required) */
    suspend fun getComments(listSourceUrl: String, caseNumber: String): List<Comment> {
        val response = client.get("$baseUrl/functions/v1/comments") {
            addAuth()
            parameter("list_source_url", listSourceUrl)
            parameter("case_number", caseNumber)
        }
        return response.body()
    }

    /** Get comment counts for multiple cases (JWT required) */
    suspend fun getCommentCounts(listSourceUrl: String, caseNumbers: List<String>): List<CommentCount> {
        val response = client.get("$baseUrl/functions/v1/comments") {
            addAuth()
            parameter("list_source_url", listSourceUrl)
            parameter("case_numbers", caseNumbers.joinToString(","))
        }
        return response.body()
    }

    @Serializable
    data class CommentCount(
        @kotlinx.serialization.SerialName("case_number") val caseNumber: String,
        val urgent: Boolean = false
    )

    /** Add a comment (auth required) */
    suspend fun addComment(listSourceUrl: String, caseNumber: String, content: String, urgent: Boolean = false) {
        client.post("$baseUrl/functions/v1/comments") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(AddCommentRequest(listSourceUrl, caseNumber, content, urgent))
        }
    }

    @Serializable
    private data class AddCommentRequest(
        @kotlinx.serialization.SerialName("list_source_url") val listSourceUrl: String,
        @kotlinx.serialization.SerialName("case_number") val caseNumber: String,
        val content: String,
        val urgent: Boolean = false
    )

    /** Delete a comment (auth required) */
    suspend fun deleteComment(id: Long) {
        client.delete("$baseUrl/functions/v1/comments") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(mapOf("id" to id))
        }
    }

    // =========================================================================
    // Case Status
    // =========================================================================

    /** Get statuses for cases (JWT required) */
    suspend fun getCaseStatuses(listSourceUrl: String, caseNumbers: List<String>): List<CaseStatus> {
        val response = client.get("$baseUrl/functions/v1/case-status") {
            addAuth()
            parameter("list_source_url", listSourceUrl)
            parameter("case_numbers", caseNumbers.joinToString(","))
        }
        return response.body()
    }

    /** Upsert case status (auth required) */
    suspend fun upsertCaseStatus(listSourceUrl: String, caseNumber: String, done: Boolean) {
        client.post("$baseUrl/functions/v1/case-status") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(UpsertStatusRequest(listSourceUrl, caseNumber, done))
        }
    }

    @Serializable
    private data class UpsertStatusRequest(
        @kotlinx.serialization.SerialName("list_source_url") val listSourceUrl: String,
        @kotlinx.serialization.SerialName("case_number") val caseNumber: String,
        val done: Boolean
    )

    // =========================================================================
    // Watched Cases
    // =========================================================================

    /** Get watched cases for current user (auth required) */
    suspend fun getWatchedCases(): List<WatchedCase> {
        val response = client.get("$baseUrl/functions/v1/watched-cases") {
            addAuth()
        }
        return response.body()
    }

    /** Watch a case (auth required) */
    suspend fun watchCase(listSourceUrl: String, caseNumber: String) {
        client.post("$baseUrl/functions/v1/watched-cases") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(WatchRequest(listSourceUrl, caseNumber, "manual"))
        }
    }

    @Serializable
    private data class WatchRequest(
        @kotlinx.serialization.SerialName("list_source_url") val listSourceUrl: String,
        @kotlinx.serialization.SerialName("case_number") val caseNumber: String,
        val source: String = "manual"
    )

    /** Unwatch a case (auth required) */
    suspend fun unwatchCase(listSourceUrl: String, caseNumber: String) {
        client.delete("$baseUrl/functions/v1/watched-cases") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(UnwatchRequest(listSourceUrl, caseNumber))
        }
    }

    @Serializable
    private data class UnwatchRequest(
        @kotlinx.serialization.SerialName("list_source_url") val listSourceUrl: String,
        @kotlinx.serialization.SerialName("case_number") val caseNumber: String
    )

    // =========================================================================
    // Notifications
    // =========================================================================

    /** Get notifications for current user (auth required) */
    suspend fun getNotifications(limit: Int = 50): List<AppNotification> {
        val response = client.get("$baseUrl/functions/v1/notifications") {
            addAuth()
            parameter("limit", limit.toString())
        }
        return response.body()
    }

    /** Mark notification as read (auth required) */
    suspend fun markNotificationRead(id: Long) {
        client.patch("$baseUrl/functions/v1/notifications") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(mapOf("id" to id))
        }
    }

    /** Mark all notifications as read (auth required) */
    suspend fun markAllNotificationsRead() {
        client.patch("$baseUrl/functions/v1/notifications") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(mapOf("all" to true))
        }
    }

    /** Delete a notification (auth required) */
    suspend fun deleteNotification(id: Long) {
        client.delete("$baseUrl/functions/v1/notifications") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(mapOf("id" to id))
        }
    }

    /** Delete all notifications (auth required) */
    suspend fun deleteAllNotifications() {
        client.delete("$baseUrl/functions/v1/notifications") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(mapOf("all" to true))
        }
    }
}
