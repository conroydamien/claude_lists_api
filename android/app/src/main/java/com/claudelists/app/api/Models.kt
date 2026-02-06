package com.claudelists.app.api

/**
 * Data models for Court Lists API
 *
 * These types mirror the definitions in: supabase/functions/_shared/types.ts
 * Keep in sync when modifying API contracts.
 *
 * See also: supabase/api.yaml (OpenAPI spec)
 */

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Request models for Edge Functions
@Serializable
data class ListingsRequest(
    val date: String,
    val court: String = "circuit-court"
)

@Serializable
data class CasesRequest(val url: String)

// Response from /functions/v1/listings
@Serializable
data class DiaryEntry(
    val dateText: String,
    val dateIso: String?,
    val venue: String,
    val type: String,
    val subtitle: String,
    val updated: String,
    val sourceUrl: String
)

// Response from /functions/v1/cases
@Serializable
data class CasesResponse(
    val cases: List<ParsedCase>,
    val headers: List<String>
)

@Serializable
data class ParsedCase(
    val listNumber: Int?,
    val listSuffix: String? = null,
    val caseNumber: String?,
    val title: String,
    val parties: String?,
    val isCase: Boolean
)

// Database models
@Serializable
data class Comment(
    val id: Long? = null,
    @SerialName("list_source_url") val listSourceUrl: String,
    @SerialName("case_number") val caseNumber: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("author_name") val authorName: String,
    val content: String,
    val urgent: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class CaseStatus(
    @SerialName("list_source_url") val listSourceUrl: String,
    @SerialName("case_number") val caseNumber: String,
    val done: Boolean = false,
    @SerialName("updated_by") val updatedBy: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

// UI models (for display in the app)
data class CourtList(
    val id: Int,
    val name: String,
    val dateIso: String?,
    val dateText: String,
    val venue: String,
    val type: String?,
    val sourceUrl: String,
    val updated: String? = null
) {
    companion object {
        fun fromDiaryEntry(entry: DiaryEntry, index: Int): CourtList {
            return CourtList(
                id = index,
                name = entry.subtitle.ifBlank { "${entry.venue} - ${entry.dateText}" },
                dateIso = entry.dateIso,
                dateText = entry.dateText,
                venue = entry.venue,
                type = entry.type.takeIf { it.isNotBlank() },
                sourceUrl = entry.sourceUrl,
                updated = entry.updated.takeIf { it.isNotBlank() }
            )
        }
    }
}

data class CaseItem(
    val id: Int,
    val listSourceUrl: String,
    val listNumber: Int?,
    val listSuffix: String? = null,
    val caseNumber: String?,
    val title: String,
    val parties: String?,
    var done: Boolean = false,
    var commentCount: Int = 0,
    var hasUrgent: Boolean = false
) {
    // Key for database lookups - use caseNumber if available, otherwise listNumber
    val caseKey: String
        get() = caseNumber ?: "item-$listNumber"

    // Display string for list position (e.g., "4", "4a", "10A")
    val listPosition: String?
        get() = listNumber?.let { "$it${listSuffix ?: ""}" }

    companion object {
        fun fromParsedCase(case: ParsedCase, index: Int, listSourceUrl: String): CaseItem {
            return CaseItem(
                id = index,
                listSourceUrl = listSourceUrl,
                listNumber = case.listNumber,
                listSuffix = case.listSuffix,
                caseNumber = case.caseNumber,
                title = case.title,
                parties = case.parties
            )
        }
    }
}

// Watched case record
@Serializable
data class WatchedCase(
    val id: Long? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("list_source_url") val listSourceUrl: String,
    @SerialName("case_number") val caseNumber: String,
    val source: String = "manual",
    @SerialName("created_at") val createdAt: String? = null
)

// In-app notification (delivered via WebSocket realtime)
@Serializable
data class AppNotification(
    val id: Long,
    @SerialName("user_id") val userId: String,
    val type: String, // 'comment', 'status_done', 'status_undone'
    @SerialName("list_source_url") val listSourceUrl: String,
    @SerialName("case_number") val caseNumber: String,
    @SerialName("case_title") val caseTitle: String? = null,
    @SerialName("actor_name") val actorName: String,
    @SerialName("actor_id") val actorId: String? = null,
    val content: String? = null,
    val read: Boolean = false,
    @SerialName("created_at") val createdAt: String
)
