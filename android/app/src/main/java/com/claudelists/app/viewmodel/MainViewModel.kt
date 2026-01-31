package com.claudelists.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudelists.app.api.*
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.Google
import io.github.jan.supabase.gotrue.user.UserInfo
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.ktor.client.call.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private const val TAG = "MainViewModel"

data class UiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val isApproved: Boolean = false,
    val user: UserInfo? = null,
    val lists: List<CourtList> = emptyList(),
    val filteredLists: List<CourtList> = emptyList(),
    val venues: List<String> = emptyList(),
    val selectedDate: String? = null,
    val selectedVenue: String? = null,
    val selectedList: CourtList? = null,
    val items: List<CaseItem> = emptyList(),
    val headers: List<String> = emptyList(),
    val comments: List<Comment> = emptyList(),
    val selectedItem: CaseItem? = null,
    val error: String? = null
)

class MainViewModel : ViewModel() {
    private val supabase = SupabaseClient.client
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        checkAuthState()
        setupRealtimeSubscription()
    }

    // Authentication

    private fun checkAuthState() {
        viewModelScope.launch {
            try {
                val session = supabase.auth.currentSessionOrNull()
                if (session != null) {
                    val user = supabase.auth.currentUserOrNull()
                    val appMetadata = user?.appMetadata
                    val isApproved = appMetadata?.get("approved")?.toString() == "true"
                    _uiState.value = _uiState.value.copy(
                        isAuthenticated = true,
                        isApproved = isApproved,
                        user = user
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking auth state", e)
            }
        }
    }

    fun signInWithGoogle() {
        viewModelScope.launch {
            try {
                supabase.auth.signInWith(Google)
                checkAuthState()
            } catch (e: Exception) {
                Log.e(TAG, "Sign in failed", e)
                _uiState.value = _uiState.value.copy(error = "Sign in failed: ${e.message}")
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                supabase.auth.signOut()
                _uiState.value = UiState()
            } catch (e: Exception) {
                Log.e(TAG, "Sign out failed", e)
            }
        }
    }

    // Data loading

    fun loadListsForDate(date: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, selectedDate = date)

            try {
                val response = supabase.functions.invoke("listings") {
                    body = mapOf("date" to date)
                }

                val body = response.body<String>()
                val entries = json.decodeFromString<List<DiaryEntry>>(body)

                val lists = entries.mapIndexed { index, entry ->
                    CourtList.fromDiaryEntry(entry, index + 1)
                }

                val venues = lists.mapNotNull { it.venue.takeIf { v -> v.isNotBlank() } }
                    .distinct()
                    .sorted()

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    lists = lists,
                    filteredLists = applyFilters(lists, _uiState.value.selectedVenue),
                    venues = venues,
                    error = null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load listings", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load listings: ${e.message}"
                )
            }
        }
    }

    fun setDateFilter(date: String?) {
        if (date != null && date != _uiState.value.selectedDate) {
            loadListsForDate(date)
        }
    }

    fun setVenueFilter(venue: String?) {
        _uiState.value = _uiState.value.copy(
            selectedVenue = venue,
            filteredLists = applyFilters(_uiState.value.lists, venue)
        )
    }

    private fun applyFilters(lists: List<CourtList>, venue: String?): List<CourtList> {
        return lists.filter { list ->
            venue == null || list.venue == venue
        }
    }

    fun selectList(list: CourtList) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                selectedList = list,
                items = emptyList(),
                headers = emptyList()
            )

            try {
                val response = supabase.functions.invoke("cases") {
                    body = mapOf("url" to list.sourceUrl)
                }

                val body = response.body<String>()
                val casesResponse = json.decodeFromString<CasesResponse>(body)

                val items = casesResponse.cases
                    .filter { it.isCase }
                    .mapIndexed { index, case ->
                        CaseItem.fromParsedCase(case, index + 1, list.sourceUrl)
                    }

                // Load done status and comment counts
                loadCaseStatuses(list.sourceUrl, items)
                loadCommentCounts(list.sourceUrl, items)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    items = items,
                    headers = casesResponse.headers,
                    error = null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load cases", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load cases: ${e.message}"
                )
            }
        }
    }

    private suspend fun loadCaseStatuses(sourceUrl: String, items: List<CaseItem>) {
        try {
            val caseKeys = items.map { it.caseKey }
            val statuses = supabase.postgrest["case_status"]
                .select {
                    filter {
                        eq("list_source_url", sourceUrl)
                        isIn("case_number", caseKeys)
                    }
                }
                .decodeList<CaseStatus>()

            val statusMap = statuses.associateBy { it.caseNumber }
            items.forEach { item ->
                statusMap[item.caseKey]?.let { status ->
                    item.done = status.done
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load case statuses", e)
        }
    }

    private suspend fun loadCommentCounts(sourceUrl: String, items: List<CaseItem>) {
        try {
            val caseKeys = items.map { it.caseKey }
            val comments = supabase.postgrest["comments"]
                .select {
                    filter {
                        eq("list_source_url", sourceUrl)
                        isIn("case_number", caseKeys)
                    }
                }
                .decodeList<Comment>()

            val countMap = comments.groupingBy { it.caseNumber }.eachCount()
            items.forEach { item ->
                item.commentCount = countMap[item.caseKey] ?: 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load comment counts", e)
        }
    }

    fun clearSelectedList() {
        _uiState.value = _uiState.value.copy(
            selectedList = null,
            items = emptyList(),
            headers = emptyList()
        )
    }

    fun refreshItems() {
        _uiState.value.selectedList?.let { selectList(it) }
    }

    // Case status

    fun toggleDone(item: CaseItem) {
        viewModelScope.launch {
            val newDone = !item.done

            // Optimistic update
            item.done = newDone
            _uiState.value = _uiState.value.copy(items = _uiState.value.items.toList())

            try {
                val status = CaseStatus(
                    listSourceUrl = item.listSourceUrl,
                    caseNumber = item.caseKey,
                    done = newDone,
                    updatedBy = supabase.auth.currentUserOrNull()?.id
                )

                supabase.postgrest["case_status"].upsert(status)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle done", e)
                // Revert on failure
                item.done = !newDone
                _uiState.value = _uiState.value.copy(
                    items = _uiState.value.items.toList(),
                    error = "Failed to update: ${e.message}"
                )
            }
        }
    }

    // Comments

    fun openComments(item: CaseItem) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(selectedItem = item)
            loadComments(item)
        }
    }

    fun closeComments() {
        _uiState.value = _uiState.value.copy(selectedItem = null, comments = emptyList())
    }

    private suspend fun loadComments(item: CaseItem) {
        try {
            val comments = supabase.postgrest["comments"]
                .select {
                    filter {
                        eq("list_source_url", item.listSourceUrl)
                        eq("case_number", item.caseKey)
                    }
                    order("created_at", ascending = true)
                }
                .decodeList<Comment>()

            _uiState.value = _uiState.value.copy(comments = comments)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load comments", e)
        }
    }

    fun sendComment(content: String) {
        val item = _uiState.value.selectedItem ?: return
        val user = _uiState.value.user ?: return

        viewModelScope.launch {
            try {
                val comment = Comment(
                    listSourceUrl = item.listSourceUrl,
                    caseNumber = item.caseKey,
                    userId = user.id,
                    authorName = user.email ?: "User",
                    content = content
                )

                supabase.postgrest["comments"].insert(comment)

                // Reload comments
                loadComments(item)

                // Update comment count
                item.commentCount++
                _uiState.value = _uiState.value.copy(items = _uiState.value.items.toList())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send comment", e)
                _uiState.value = _uiState.value.copy(error = "Failed to send: ${e.message}")
            }
        }
    }

    fun deleteComment(comment: Comment) {
        viewModelScope.launch {
            try {
                supabase.postgrest["comments"]
                    .delete {
                        filter {
                            eq("id", comment.id!!)
                        }
                    }

                // Reload comments
                _uiState.value.selectedItem?.let { loadComments(it) }

                // Update comment count
                _uiState.value.selectedItem?.let { item ->
                    item.commentCount--
                    _uiState.value = _uiState.value.copy(items = _uiState.value.items.toList())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete comment", e)
            }
        }
    }

    // Realtime

    private fun setupRealtimeSubscription() {
        viewModelScope.launch {
            try {
                val channel = supabase.realtime.channel("db-changes")

                // Listen for changes - simplified for now
                channel.subscribe()

                Log.d(TAG, "Realtime subscription setup complete")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup realtime", e)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
