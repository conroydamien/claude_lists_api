package com.claudelists.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudelists.app.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "MainViewModel"

data class UiState(
    val lists: List<CourtList> = emptyList(),
    val filteredLists: List<CourtList> = emptyList(),
    val venues: List<String> = emptyList(),
    val selectedDate: String? = null,
    val selectedVenue: String? = null,
    val selectedList: CourtList? = null,
    val items: List<Item> = emptyList(),
    val headers: List<Header> = emptyList(),
    val commentCounts: Map<Int, Int> = emptyMap(),
    val selectedItemForComments: Item? = null,
    val comments: List<Comment> = emptyList(),
    val isCommentsLoading: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val api = ApiClient.apiService

    private val webSocketManager = WebSocketManager(
        onItemChanged = { notification ->
            Log.d(TAG, "Item changed: ${notification.operation} id=${notification.id} list=${notification.list_id}")
            // If viewing the affected list, refresh items
            if (_uiState.value.selectedList?.id == notification.list_id) {
                viewModelScope.launch {
                    refreshItemsSilently(notification.list_id)
                }
            }
        },
        onCommentChanged = { notification ->
            Log.d(TAG, "Comment changed: ${notification.operation} id=${notification.id} item=${notification.item_id}")
            // If viewing the affected list, refresh comment counts
            if (_uiState.value.selectedList?.id == notification.list_id) {
                viewModelScope.launch {
                    refreshCommentCounts()
                    // If viewing comments for this item, refresh them
                    if (_uiState.value.selectedItemForComments?.id == notification.item_id) {
                        loadComments(notification.item_id)
                    }
                }
            }
        }
    )

    init {
        loadLists()
        webSocketManager.connect()
    }

    override fun onCleared() {
        super.onCleared()
        webSocketManager.disconnect()
    }

    fun loadLists() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val lists = api.getLists()

                // Extract unique venues
                val venues = lists
                    .mapNotNull { it.metadata?.venue }
                    .distinct()
                    .sorted()

                // Find nearest date to today
                val today = java.time.LocalDate.now().toString()
                val dates = lists.mapNotNull { it.metadata?.date }.sorted()
                val nearestDate = dates.firstOrNull { it >= today } ?: dates.lastOrNull()

                _uiState.value = _uiState.value.copy(
                    lists = lists,
                    venues = venues,
                    selectedDate = nearestDate,
                    isLoading = false
                )

                filterLists()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load lists"
                )
            }
        }
    }

    fun setDateFilter(date: String?) {
        _uiState.value = _uiState.value.copy(selectedDate = date)
        filterLists()
    }

    fun setVenueFilter(venue: String?) {
        _uiState.value = _uiState.value.copy(selectedVenue = venue)
        filterLists()
    }

    private fun filterLists() {
        val state = _uiState.value
        val filtered = state.lists.filter { list ->
            val dateMatch = state.selectedDate == null ||
                    list.metadata?.date == state.selectedDate
            val venueMatch = state.selectedVenue == null ||
                    list.metadata?.venue == state.selectedVenue
            dateMatch && venueMatch
        }
        _uiState.value = state.copy(filteredLists = filtered)
    }

    fun selectList(list: CourtList) {
        _uiState.value = _uiState.value.copy(selectedList = list)
        loadItems(list.id)
    }

    fun clearSelectedList() {
        _uiState.value = _uiState.value.copy(
            selectedList = null,
            items = emptyList(),
            headers = emptyList(),
            commentCounts = emptyMap()
        )
    }

    fun refreshItems() {
        _uiState.value.selectedList?.let { loadItems(it.id) }
    }

    private suspend fun refreshItemsSilently(listId: Int) {
        try {
            val items = api.getItems("eq.$listId")
            val sortedItems = items.sortedBy { it.metadata?.listNumber ?: 999 }
            _uiState.value = _uiState.value.copy(items = sortedItems)
            Log.d(TAG, "Items refreshed silently: ${items.size} items")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh items: ${e.message}")
        }
    }

    private suspend fun refreshCommentCounts() {
        try {
            val items = _uiState.value.items
            if (items.isNotEmpty()) {
                val itemIds = items.map { it.id }.joinToString(",")
                val comments = api.getComments("in.($itemIds)")
                val commentCounts = comments.groupBy { it.itemId }.mapValues { it.value.size }
                _uiState.value = _uiState.value.copy(commentCounts = commentCounts)
                Log.d(TAG, "Comment counts refreshed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh comment counts: ${e.message}")
        }
    }

    private fun loadItems(listId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                // Fetch list details (for headers) and items
                val listDetails = api.getList("eq.$listId")
                val items = api.getItems("eq.$listId")

                val headers = listDetails.firstOrNull()?.metadata?.headers ?: emptyList()

                // Sort items by list_number
                val sortedItems = items.sortedBy { it.metadata?.listNumber ?: 999 }

                // Fetch comment counts
                val commentCounts = if (items.isNotEmpty()) {
                    val itemIds = items.map { it.id }.joinToString(",")
                    val comments = api.getComments("in.($itemIds)")
                    comments.groupBy { it.itemId }.mapValues { it.value.size }
                } else {
                    emptyMap()
                }

                _uiState.value = _uiState.value.copy(
                    items = sortedItems,
                    headers = headers,
                    commentCounts = commentCounts,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load items"
                )
            }
        }
    }

    fun toggleItemDone(item: Item) {
        Log.d(TAG, "toggleItemDone called: id=${item.id}, currentDone=${item.done}")
        val newDoneState = !item.done

        // Optimistic UI update - update locally first
        val updatedItems = _uiState.value.items.map {
            if (it.id == item.id) it.copy(done = newDoneState) else it
        }
        _uiState.value = _uiState.value.copy(items = updatedItems)
        Log.d(TAG, "Optimistic update applied: newDoneState=$newDoneState")

        // Then sync with server
        viewModelScope.launch {
            try {
                Log.d(TAG, "Calling API to update item ${item.id} to done=$newDoneState")
                val response = api.updateItem("eq.${item.id}", ItemUpdate(done = newDoneState))
                if (response.isSuccessful) {
                    Log.d(TAG, "API call successful")
                } else {
                    throw Exception("API returned ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "API call failed: ${e.message}", e)
                // Revert on failure
                val revertedItems = _uiState.value.items.map {
                    if (it.id == item.id) it.copy(done = !newDoneState) else it
                }
                _uiState.value = _uiState.value.copy(
                    items = revertedItems,
                    error = e.message ?: "Failed to update item"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun openComments(item: Item) {
        _uiState.value = _uiState.value.copy(
            selectedItemForComments = item,
            comments = emptyList(),
            isCommentsLoading = true
        )
        loadComments(item.id)
    }

    fun closeComments() {
        _uiState.value = _uiState.value.copy(
            selectedItemForComments = null,
            comments = emptyList()
        )
    }

    private fun loadComments(itemId: Int) {
        viewModelScope.launch {
            try {
                val comments = api.getComments("eq.$itemId")
                _uiState.value = _uiState.value.copy(
                    comments = comments,
                    isCommentsLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCommentsLoading = false,
                    error = e.message ?: "Failed to load comments"
                )
            }
        }
    }

    fun sendComment(content: String) {
        val item = _uiState.value.selectedItemForComments ?: return
        viewModelScope.launch {
            try {
                val comment = CommentCreate(
                    itemId = item.id,
                    authorId = ApiClient.currentUserId,
                    authorName = ApiClient.currentUserName,
                    content = content
                )
                val response = api.createComment(comment)
                if (response.isSuccessful) {
                    // Reload comments and update count
                    loadComments(item.id)
                    updateCommentCount(item.id, 1)
                } else {
                    throw Exception("API returned ${response.code()}")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to send comment"
                )
            }
        }
    }

    fun deleteComment(comment: Comment) {
        viewModelScope.launch {
            try {
                val response = api.deleteComment("eq.${comment.id}")
                if (response.isSuccessful) {
                    // Remove from local list
                    _uiState.value = _uiState.value.copy(
                        comments = _uiState.value.comments.filter { it.id != comment.id }
                    )
                    updateCommentCount(comment.itemId, -1)
                } else {
                    throw Exception("API returned ${response.code()}")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to delete comment"
                )
            }
        }
    }

    private fun updateCommentCount(itemId: Int, delta: Int) {
        val currentCounts = _uiState.value.commentCounts.toMutableMap()
        val currentCount = currentCounts[itemId] ?: 0
        currentCounts[itemId] = maxOf(0, currentCount + delta)
        _uiState.value = _uiState.value.copy(commentCounts = currentCounts)
    }
}
