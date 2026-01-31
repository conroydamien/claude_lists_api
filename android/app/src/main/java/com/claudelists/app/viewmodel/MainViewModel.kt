package com.claudelists.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.claudelists.app.BuildConfig
import com.claudelists.app.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "MainViewModel"
private val USE_COURTS_IE = BuildConfig.USE_COURTS_IE_DIRECT

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
    val error: String? = null,
    val dataSource: DataSource = if (USE_COURTS_IE) DataSource.COURTS_IE else DataSource.API
)

enum class DataSource {
    COURTS_IE,  // Direct from courts.ie (no comments/done state persistence)
    API         // From backend API (full features)
}

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val api = ApiClient.apiService

    // Store diary entries for URL lookup (courts.ie mode)
    private var diaryEntries: List<DiaryEntry> = emptyList()

    // WebSocket manager for real-time updates
    private val webSocketManager = WebSocketManager(
        onItemChanged = { notification ->
            Log.d(TAG, "Item changed: ${notification.operation} id=${notification.id} list=${notification.list_id}")
            if (!USE_COURTS_IE && _uiState.value.selectedList?.id == notification.list_id) {
                viewModelScope.launch {
                    refreshItemsSilentlyFromApi(notification.list_id)
                }
            }
        },
        onCommentChanged = { notification ->
            Log.d(TAG, "Comment changed: ${notification.operation} id=${notification.id} item=${notification.item_id}")
            viewModelScope.launch {
                if (USE_COURTS_IE) {
                    // In courts.ie mode, find the local item ID from the database item ID
                    val localItemId = itemIdMap.entries.find { it.value == notification.item_id }?.key
                    if (localItemId != null) {
                        // Update comment count for this item
                        val currentCounts = _uiState.value.commentCounts.toMutableMap()
                        when (notification.operation) {
                            "INSERT" -> currentCounts[localItemId] = (currentCounts[localItemId] ?: 0) + 1
                            "DELETE" -> currentCounts[localItemId] = maxOf(0, (currentCounts[localItemId] ?: 1) - 1)
                        }
                        _uiState.value = _uiState.value.copy(commentCounts = currentCounts)
                        Log.d(TAG, "Updated comment count for local item $localItemId")

                        // If viewing comments for this item, refresh them
                        if (_uiState.value.selectedItemForComments?.id == localItemId) {
                            loadComments(notification.item_id)
                        }
                    }
                } else {
                    if (_uiState.value.selectedList?.id == notification.list_id) {
                        refreshCommentCounts()
                        if (_uiState.value.selectedItemForComments?.id == notification.item_id) {
                            loadComments(notification.item_id)
                        }
                    }
                }
            }
        }
    )

    init {
        Log.d(TAG, "Initializing with data source: ${if (USE_COURTS_IE) "courts.ie" else "API"}")
        loadLists()
        webSocketManager.connect()
    }

    override fun onCleared() {
        super.onCleared()
        webSocketManager.disconnect()
    }

    fun loadLists() {
        if (USE_COURTS_IE) {
            loadListsFromCourtsIe()
        } else {
            loadListsFromApi()
        }
    }

    private fun loadListsFromCourtsIe() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                Log.d(TAG, "Loading lists from courts.ie")
                diaryEntries = CourtsIeService.fetchListing()

                if (diaryEntries.isEmpty()) {
                    throw Exception("No diary entries found")
                }

                val lists = diaryEntries.mapIndexed { index, entry ->
                    CourtsIeService.diaryEntryToCourtList(entry, index)
                }

                val venues = lists
                    .mapNotNull { it.metadata?.venue }
                    .distinct()
                    .sorted()

                val today = java.time.LocalDate.now().toString()
                val dates = lists.mapNotNull { it.metadata?.date }.distinct().sorted()
                val nearestDate = dates.firstOrNull { it >= today } ?: dates.lastOrNull()

                _uiState.value = _uiState.value.copy(
                    lists = lists,
                    venues = venues,
                    selectedDate = nearestDate,
                    isLoading = false,
                    dataSource = DataSource.COURTS_IE
                )

                Log.d(TAG, "Loaded ${lists.size} lists from courts.ie")
                filterLists()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load lists from courts.ie: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load from courts.ie: ${e.message}"
                )
            }
        }
    }

    private fun loadListsFromApi() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                Log.d(TAG, "Loading lists from API")
                val lists = api.getLists()

                val venues = lists
                    .mapNotNull { it.metadata?.venue }
                    .distinct()
                    .sorted()

                val today = java.time.LocalDate.now().toString()
                val dates = lists.mapNotNull { it.metadata?.date }.sorted()
                val nearestDate = dates.firstOrNull { it >= today } ?: dates.lastOrNull()

                _uiState.value = _uiState.value.copy(
                    lists = lists,
                    venues = venues,
                    selectedDate = nearestDate,
                    isLoading = false,
                    dataSource = DataSource.API
                )

                Log.d(TAG, "Loaded ${lists.size} lists from API")
                filterLists()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load lists from API: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load lists"
                )
            }
        }
    }

    fun setDateFilter(date: String?) {
        val oldDate = _uiState.value.selectedDate
        _uiState.value = _uiState.value.copy(selectedDate = date)

        if (USE_COURTS_IE && date != null && date != oldDate) {
            // Lazy load: fetch listings for the new date from courts.ie
            loadListsForDate(date)
        } else {
            filterLists()
        }
    }

    private fun loadListsForDate(dateStr: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val date = java.time.LocalDate.parse(dateStr)
                Log.d(TAG, "Loading lists for date: $dateStr")

                val entries = CourtsIeService.fetchListing(date)

                val lists = entries.mapIndexed { index, entry ->
                    CourtsIeService.diaryEntryToCourtList(entry, index)
                }

                // Extract venues from this date's data only
                val venues = lists
                    .mapNotNull { it.metadata?.venue }
                    .distinct()
                    .sorted()

                // Replace all data with just this date's listings
                _uiState.value = _uiState.value.copy(
                    lists = lists,
                    filteredLists = lists,
                    venues = venues,
                    isLoading = false
                )

                Log.d(TAG, "Loaded ${lists.size} lists for $dateStr")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load lists for date: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load: ${e.message}"
                )
            }
        }
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
        Log.d(TAG, "Filtered to ${filtered.size} lists")
    }

    fun selectList(list: CourtList) {
        _uiState.value = _uiState.value.copy(selectedList = list)
        if (USE_COURTS_IE) {
            loadItemsFromCourtsIe(list)
        } else {
            loadItemsFromApi(list.id)
        }
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
        val list = _uiState.value.selectedList ?: return
        if (USE_COURTS_IE) {
            loadItemsFromCourtsIe(list)
        } else {
            loadItemsFromApi(list.id)
        }
    }

    private fun loadItemsFromCourtsIe(list: CourtList) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val sourceUrl = list.metadata?.sourceUrl
                    ?: throw Exception("No source URL for list")

                Log.d(TAG, "Loading items from courts.ie: $sourceUrl")

                val (cases, headerTexts) = CourtsIeService.fetchDetail(sourceUrl)

                val items = cases.filter { it.isCase }.mapIndexed { index, case ->
                    CourtsIeService.parsedCaseToItem(case, index, list.id)
                }

                val headers = headerTexts.mapIndexed { index, text ->
                    Header(text = text, beforeCase = if (index == 0) 1 else null, afterCases = index == headerTexts.lastIndex)
                }

                _uiState.value = _uiState.value.copy(
                    items = items,
                    headers = headers,
                    commentCounts = emptyMap(),
                    isLoading = false
                )

                Log.d(TAG, "Loaded ${items.size} items from courts.ie")

                // Try to load comment counts from database (in background)
                loadCommentCountsForCourtsIeItems(list, items)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load items from courts.ie: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load items: ${e.message}"
                )
            }
        }
    }

    private fun loadItemsFromApi(listId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                Log.d(TAG, "Loading items from API for list $listId")

                val listDetails = api.getList("eq.$listId")
                val items = api.getItems("eq.$listId")

                val headers = listDetails.firstOrNull()?.metadata?.headers ?: emptyList()
                val sortedItems = items.sortedBy { it.metadata?.listNumber ?: 999 }

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

                Log.d(TAG, "Loaded ${items.size} items from API")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load items from API: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load items"
                )
            }
        }
    }

    private suspend fun refreshItemsSilentlyFromApi(listId: Int) {
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

    fun toggleItemDone(item: Item) {
        Log.d(TAG, "toggleItemDone called: id=${item.id}, currentDone=${item.done}")
        val newDoneState = !item.done

        // Optimistic UI update
        val updatedItems = _uiState.value.items.map {
            if (it.id == item.id) it.copy(done = newDoneState) else it
        }
        _uiState.value = _uiState.value.copy(items = updatedItems)

        if (USE_COURTS_IE) {
            // In courts.ie mode, done state is local only
            Log.d(TAG, "Done state updated locally (courts.ie mode)")
        } else {
            // In API mode, sync with server
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

        if (USE_COURTS_IE) {
            // In courts.ie mode, we need to find/create the item in the database first
            ensureItemInDatabase(item)
        } else {
            loadComments(item.id)
        }
    }

    // Map from local item ID to database item ID (for courts.ie mode)
    private val itemIdMap = mutableMapOf<Int, Int>()

    private fun loadCommentCountsForCourtsIeItems(list: CourtList, items: List<Item>) {
        viewModelScope.launch {
            try {
                val sourceUrl = list.metadata?.sourceUrl ?: return@launch

                // Find the list in the database
                val dbLists = api.findListBySourceUrl("eq.$sourceUrl")
                if (dbLists.isEmpty()) {
                    Log.d(TAG, "List not in database yet, no comment counts")
                    return@launch
                }
                val dbListId = dbLists[0].id

                // Get all items in that list from database
                val dbItems = api.getItems("eq.$dbListId")
                if (dbItems.isEmpty()) {
                    Log.d(TAG, "No items in database for this list")
                    return@launch
                }

                // Build case_number -> db item mapping
                val caseToDbItem = dbItems.associateBy { it.metadata?.caseNumber }

                // Map local items to database items and cache the mapping
                val localToDbId = mutableMapOf<Int, Int>()
                for (localItem in items) {
                    val caseNum = localItem.metadata?.caseNumber ?: continue
                    val dbItem = caseToDbItem[caseNum] ?: continue
                    localToDbId[localItem.id] = dbItem.id
                    itemIdMap[localItem.id] = dbItem.id
                }

                if (localToDbId.isEmpty()) {
                    Log.d(TAG, "No matching items found in database")
                    return@launch
                }

                // Fetch comment counts for database items
                val dbItemIds = localToDbId.values.joinToString(",")
                val comments = api.getComments("in.($dbItemIds)")
                val dbCommentCounts = comments.groupBy { it.itemId }.mapValues { it.value.size }

                // Convert to local item IDs
                val localCommentCounts = mutableMapOf<Int, Int>()
                for ((localId, dbId) in localToDbId) {
                    val count = dbCommentCounts[dbId] ?: 0
                    if (count > 0) {
                        localCommentCounts[localId] = count
                    }
                }

                if (localCommentCounts.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(commentCounts = localCommentCounts)
                    Log.d(TAG, "Loaded comment counts for ${localCommentCounts.size} items")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load comment counts: ${e.message}")
                // Don't show error to user - this is a background enhancement
            }
        }
    }

    private fun ensureItemInDatabase(item: Item) {
        viewModelScope.launch {
            try {
                val list = _uiState.value.selectedList
                    ?: throw Exception("No list selected")
                val sourceUrl = list.metadata?.sourceUrl
                    ?: throw Exception("No source URL for list")
                val caseNumber = item.metadata?.caseNumber
                    ?: throw Exception("No case number for item")

                Log.d(TAG, "Ensuring item in database: caseNumber=$caseNumber, sourceUrl=$sourceUrl")

                // Check if we already have a mapping
                val existingDbId = itemIdMap[item.id]
                if (existingDbId != null) {
                    Log.d(TAG, "Using cached database ID: $existingDbId")
                    loadComments(existingDbId)
                    return@launch
                }

                // Find or create the list in the database
                val dbListId = findOrCreateList(list, sourceUrl)

                // Find or create the item in the database
                val dbItemId = findOrCreateItem(item, dbListId, caseNumber)

                // Cache the mapping
                itemIdMap[item.id] = dbItemId
                Log.d(TAG, "Mapped local item ${item.id} to database item $dbItemId")

                // Now load comments using the database ID
                loadComments(dbItemId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to ensure item in database: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isCommentsLoading = false,
                    error = "Failed to load comments: ${e.message}"
                )
            }
        }
    }

    private suspend fun findOrCreateList(list: CourtList, sourceUrl: String): Int {
        // Try to find existing list by source URL
        val existing = api.findListBySourceUrl("eq.$sourceUrl")
        if (existing.isNotEmpty()) {
            Log.d(TAG, "Found existing list: ${existing[0].id}")
            return existing[0].id
        }

        // Create new list
        Log.d(TAG, "Creating new list: ${list.name}")
        val created = api.createList(
            ListCreate(
                name = list.name,
                description = list.description,
                metadata = list.metadata
            )
        )
        return created[0].id
    }

    private suspend fun findOrCreateItem(item: Item, listId: Int, caseNumber: String): Int {
        // Try to find existing item by case number within the list
        val existing = api.findItemByCaseNumber("eq.$listId", "eq.$caseNumber")
        if (existing.isNotEmpty()) {
            Log.d(TAG, "Found existing item: ${existing[0].id}")
            return existing[0].id
        }

        // Create new item
        Log.d(TAG, "Creating new item: $caseNumber")
        val created = api.createItem(
            ItemCreate(
                listId = listId,
                title = item.title,
                done = item.done,
                metadata = item.metadata
            )
        )
        return created[0].id
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
                // In courts.ie mode, use the database ID from our mapping
                val dbItemId = if (USE_COURTS_IE) {
                    itemIdMap[item.id] ?: throw Exception("Item not in database")
                } else {
                    item.id
                }

                val comment = CommentCreate(
                    itemId = dbItemId,
                    authorId = ApiClient.currentUserId,
                    authorName = ApiClient.currentUserName,
                    content = content
                )
                val response = api.createComment(comment)
                if (response.isSuccessful) {
                    loadComments(dbItemId)
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
