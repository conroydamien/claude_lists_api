package com.claudelists.app.api

import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @GET("lists")
    suspend fun getLists(
        @Query("order") order: String = "name"
    ): List<CourtList>

    @GET("lists")
    suspend fun getList(
        @Query("id") id: String  // e.g., "eq.123"
    ): List<CourtList>

    @GET("items")
    suspend fun getItems(
        @Query("list_id") listId: String,  // e.g., "eq.123"
        @Query("order") order: String = "id"
    ): List<Item>

    @PATCH("items")
    suspend fun updateItem(
        @Query("id") id: String,  // e.g., "eq.123"
        @Body update: ItemUpdate
    ): Response<Unit>

    @GET("comments")
    suspend fun getComments(
        @Query("item_id") itemId: String,  // e.g., "eq.123" or "in.(1,2,3)"
        @Query("order") order: String = "created_at"
    ): List<Comment>

    @POST("comments")
    suspend fun createComment(
        @Body comment: CommentCreate
    ): Response<Unit>

    @DELETE("comments")
    suspend fun deleteComment(
        @Query("id") id: String  // e.g., "eq.123"
    ): Response<Unit>
}
