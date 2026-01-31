package com.claudelists.app.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val sourceUrl: String
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
                sourceUrl = entry.sourceUrl
            )
        }
    }
}

data class CaseItem(
    val id: Int,
    val listSourceUrl: String,
    val listNumber: Int?,
    val caseNumber: String?,
    val title: String,
    val parties: String?,
    var done: Boolean = false,
    var commentCount: Int = 0
) {
    // Key for database lookups - use caseNumber if available, otherwise listNumber
    val caseKey: String
        get() = caseNumber ?: "item-$listNumber"

    companion object {
        fun fromParsedCase(case: ParsedCase, index: Int, listSourceUrl: String): CaseItem {
            return CaseItem(
                id = index,
                listSourceUrl = listSourceUrl,
                listNumber = case.listNumber,
                caseNumber = case.caseNumber,
                title = case.title,
                parties = case.parties
            )
        }
    }
}
