package com.claudelists.app.api

import com.google.gson.annotations.SerializedName

data class CourtList(
    val id: Int,
    val name: String,
    val description: String?,
    val metadata: ListMetadata?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)

data class ListMetadata(
    val date: String?,
    @SerializedName("date_text") val dateText: String?,
    val venue: String?,
    val type: String?,
    val subtitle: String?,
    val updated: String?,
    @SerializedName("source_url") val sourceUrl: String?,
    val headers: List<Header>?
)

data class Header(
    val text: String,
    @SerializedName("before_case") val beforeCase: Int?,
    @SerializedName("after_cases") val afterCases: Boolean?
)

data class Item(
    val id: Int,
    @SerializedName("list_id") val listId: Int,
    val title: String,
    val description: String?,
    val done: Boolean,
    val metadata: ItemMetadata?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)

data class ItemMetadata(
    @SerializedName("is_case") val isCase: Boolean?,
    @SerializedName("list_number") val listNumber: Int?,
    @SerializedName("case_number") val caseNumber: String?,
    val parties: String?,
    @SerializedName("case_type") val caseType: String?
)

data class Comment(
    val id: Int,
    @SerializedName("item_id") val itemId: Int,
    @SerializedName("author_id") val authorId: String,
    @SerializedName("author_name") val authorName: String,
    val content: String,
    @SerializedName("created_at") val createdAt: String
)

data class ItemUpdate(
    val done: Boolean
)

data class CommentCreate(
    @SerializedName("item_id") val itemId: Int,
    @SerializedName("author_id") val authorId: String,
    @SerializedName("author_name") val authorName: String,
    val content: String
)
