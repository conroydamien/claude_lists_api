package com.claudelists.app.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claudelists.app.CourtListsApplication
import com.claudelists.app.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "MainViewModel"

// Pending action after navigation completes
sealed class PendingAction {
    data class OpenComments(val caseKey: String) : PendingAction()
    data class ScrollToCase(val caseKey: String) : PendingAction()
}

data class UiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val isApproved: Boolean = false,
    val userId: String? = null,
    val userEmail: String? = null,
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
    val error: String? = null,
    val snackbarMessage: String? = null,
    val pendingAction: PendingAction? = null,
    // Notification state
    val notifications: List<AppNotification> = emptyList(),
    val unreadNotificationCount: Int = 0,
    val watchedCaseKeys: Set<String> = emptySet(), // "listSourceUrl|caseNumber"
    val showNotifications: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = CourtListsApplication.instance
    private val api = app.api
    private val authManager = app.authManager

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        Log.i(TAG, "MainViewModel init started")
        observeAuthState()
    }

    override fun onCleared() {
        super.onCleared()
        Log.i(TAG, "ViewModel cleared")
    }

    // =========================================================================
    // Authentication
    // =========================================================================

    private fun observeAuthState() {
        viewModelScope.launch {
            authManager.authState.collect { authState ->
                Log.i(TAG, "Auth state changed: authenticated=${authState.isAuthenticated}, approved=${authState.isApproved}")
                _uiState.value = _uiState.value.copy(
                    isAuthenticated = authState.isAuthenticated,
                    isApproved = authState.isApproved,
                    userId = authState.userId,
                    userEmail = authState.userEmail
                )

                if (authState.isAuthenticated && authState.isApproved) {
                    loadWatchedCases()
                    loadNotifications()
                    setupRealtimeSubscription()
                }
            }
        }
    }

    fun getSignInIntent(): android.content.Intent {
        return authManager.getSignInIntent()
    }

    fun signOut() {
        authManager.signOut()
        _uiState.value = UiState()
    }

    // =========================================================================
    // Data Loading
    // =========================================================================

    fun loadListsForDate(date: String) {
        Log.d(TAG, "loadListsForDate called with date: $date")
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, selectedDate = date)

            try {
                val entries = api.getListings(date)
                Log.d(TAG, "Loaded ${entries.size} entries")

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
                val casesResponse = api.getCases(list.sourceUrl)

                var items = casesResponse.cases
                    .filter { it.isCase }
                    .mapIndexed { index, case ->
                        CaseItem.fromParsedCase(case, index + 1, list.sourceUrl)
                    }

                // Load done status and comment counts (returns updated items)
                items = loadCaseStatuses(list.sourceUrl, items)
                items = loadCommentCounts(list.sourceUrl, items)

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

    private suspend fun loadCaseStatuses(sourceUrl: String, items: List<CaseItem>): List<CaseItem> {
        return try {
            val caseKeys = items.map { it.caseKey }
            val statuses = api.getCaseStatuses(sourceUrl, caseKeys)

            val statusMap = statuses.associateBy { it.caseNumber }
            items.map { item ->
                val status = statusMap[item.caseKey]
                if (status != null) item.copy(done = status.done) else item
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load case statuses", e)
            items
        }
    }

    private suspend fun loadCommentCounts(sourceUrl: String, items: List<CaseItem>): List<CaseItem> {
        return try {
            val caseKeys = items.map { it.caseKey }
            val commentCounts = api.getCommentCounts(sourceUrl, caseKeys)
            Log.d(TAG, "Got ${commentCounts.size} comment count entries")

            val countMap = commentCounts.groupingBy { it.caseNumber }.eachCount()
            Log.d(TAG, "Comment count map: $countMap")

            items.map { item ->
                val count = countMap[item.caseKey] ?: 0
                item.copy(commentCount = count)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load comment counts", e)
            items
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

    // =========================================================================
    // Case Status
    // =========================================================================

    fun toggleDone(item: CaseItem) {
        Log.d(TAG, "toggleDone called for item: ${item.caseKey}")
        viewModelScope.launch {
            val newDone = !item.done

            // Optimistic update
            val updatedItems = _uiState.value.items.map {
                if (it.id == item.id) it.copy(done = newDone) else it
            }
            _uiState.value = _uiState.value.copy(items = updatedItems)

            try {
                val userId = authManager.getUserId()
                val status = CaseStatus(
                    listSourceUrl = item.listSourceUrl,
                    caseNumber = item.caseKey,
                    done = newDone,
                    updatedBy = userId
                )

                api.upsertCaseStatus(status)
                Log.d(TAG, "Upsert successful")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle done: ${e.message}", e)
                // Revert on failure
                val revertedItems = _uiState.value.items.map {
                    if (it.id == item.id) it.copy(done = !newDone) else it
                }
                _uiState.value = _uiState.value.copy(
                    items = revertedItems,
                    error = "Failed to update: ${e.message}"
                )
            }
        }
    }

    // =========================================================================
    // Comments
    // =========================================================================

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
            val comments = api.getComments(item.listSourceUrl, item.caseKey)
            _uiState.value = _uiState.value.copy(comments = comments)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load comments", e)
        }
    }

    fun sendComment(content: String) {
        val item = _uiState.value.selectedItem ?: return
        val userId = _uiState.value.userId ?: return
        val email = _uiState.value.userEmail ?: "User"

        viewModelScope.launch {
            try {
                val comment = Comment(
                    listSourceUrl = item.listSourceUrl,
                    caseNumber = item.caseKey,
                    userId = userId,
                    authorName = email,
                    content = content
                )

                api.addComment(comment)

                // Reload comments
                loadComments(item)

                // Update comment count immutably
                val updatedItems = _uiState.value.items.map {
                    if (it.id == item.id) it.copy(commentCount = it.commentCount + 1) else it
                }
                _uiState.value = _uiState.value.copy(
                    items = updatedItems,
                    snackbarMessage = "Comment sent"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send comment: ${e.message}", e)
                _uiState.value = _uiState.value.copy(error = "Failed to send: ${e.message}")
            }
        }
    }

    fun clearSnackbar() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }

    fun deleteComment(comment: Comment) {
        val item = _uiState.value.selectedItem ?: return

        viewModelScope.launch {
            try {
                comment.id?.let { api.deleteComment(it) }

                // Reload comments
                loadComments(item)

                // Update comment count immutably
                val updatedItems = _uiState.value.items.map {
                    if (it.id == item.id) it.copy(commentCount = maxOf(0, it.commentCount - 1)) else it
                }
                _uiState.value = _uiState.value.copy(items = updatedItems)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete comment", e)
            }
        }
    }

    // =========================================================================
    // Realtime - uses Application-level subscription
    // =========================================================================

    private fun setupRealtimeSubscription() {
        // Set up Application-level realtime (only happens once per process)
        app.setupRealtimeIfNeeded()

        // Collect from Application's SharedFlows to update ViewModel state
        viewModelScope.launch {
            app.statusChangeEvents.collect {
                Log.d(TAG, "ViewModel received status change event")
                handleStatusChange()
            }
        }

        viewModelScope.launch {
            app.commentChangeEvents.collect {
                Log.d(TAG, "ViewModel received comment change event")
                handleCommentChange()
            }
        }

        viewModelScope.launch {
            app.notificationEvents.collect { event ->
                Log.d(TAG, "ViewModel received notification event: ${event.caseNumber}")
                loadNotifications()
            }
        }

        viewModelScope.launch {
            app.watchedCaseEvents.collect {
                Log.d(TAG, "ViewModel received watched case event")
                loadWatchedCases()
            }
        }

        Log.i(TAG, "ViewModel collectors set up for Application events")
    }

    private fun handleCommentChange() {
        val selectedList = _uiState.value.selectedList
        if (selectedList != null) {
            viewModelScope.launch {
                try {
                    val caseKeys = _uiState.value.items.map { it.caseKey }
                    val commentCounts = api.getCommentCounts(selectedList.sourceUrl, caseKeys)

                    val countMap = commentCounts.groupingBy { it.caseNumber }.eachCount()

                    val updatedItems = _uiState.value.items.map { item ->
                        val newCount = countMap[item.caseKey] ?: 0
                        if (newCount != item.commentCount) {
                            item.copy(commentCount = newCount)
                        } else {
                            item
                        }
                    }

                    _uiState.value = _uiState.value.copy(items = updatedItems)
                    Log.d(TAG, "Comments reloaded via realtime")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reload comments", e)
                }
            }
        }
        // Also reload comments if sheet is open
        _uiState.value.selectedItem?.let { item ->
            viewModelScope.launch {
                loadComments(item)
            }
        }
    }

    private fun handleStatusChange() {
        val selectedList = _uiState.value.selectedList
        if (selectedList != null) {
            viewModelScope.launch {
                try {
                    val caseKeys = _uiState.value.items.map { it.caseKey }
                    val statuses = api.getCaseStatuses(selectedList.sourceUrl, caseKeys)

                    val statusMap = statuses.associateBy { it.caseNumber }

                    val updatedItems = _uiState.value.items.map { item ->
                        val newDone = statusMap[item.caseKey]?.done ?: item.done
                        if (newDone != item.done) {
                            item.copy(done = newDone)
                        } else {
                            item
                        }
                    }

                    _uiState.value = _uiState.value.copy(items = updatedItems)
                    Log.d(TAG, "Status reloaded via realtime, ${statuses.size} statuses")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reload statuses", e)
                }
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // =========================================================================
    // Watched Cases
    // =========================================================================

    private fun loadWatchedCases() {
        viewModelScope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch
                val watched = api.getWatchedCases(userId)

                val keys = watched.map { "${it.listSourceUrl}|${it.caseNumber}" }.toSet()
                _uiState.value = _uiState.value.copy(watchedCaseKeys = keys)
                Log.d(TAG, "Loaded ${keys.size} watched cases")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load watched cases", e)
            }
        }
    }

    fun isWatching(listSourceUrl: String, caseKey: String): Boolean {
        return "${listSourceUrl}|${caseKey}" in _uiState.value.watchedCaseKeys
    }

    fun toggleWatch(item: CaseItem) {
        viewModelScope.launch {
            val userId = authManager.getUserId() ?: return@launch
            val key = "${item.listSourceUrl}|${item.caseKey}"
            val isCurrentlyWatching = key in _uiState.value.watchedCaseKeys

            // Optimistic update
            val newKeys = if (isCurrentlyWatching) {
                _uiState.value.watchedCaseKeys - key
            } else {
                _uiState.value.watchedCaseKeys + key
            }
            _uiState.value = _uiState.value.copy(watchedCaseKeys = newKeys)

            try {
                if (isCurrentlyWatching) {
                    api.unwatchCase(userId, item.listSourceUrl, item.caseKey)
                    Log.d(TAG, "Unwatched case: ${item.caseKey}")
                } else {
                    val watchedCase = WatchedCase(
                        userId = userId,
                        listSourceUrl = item.listSourceUrl,
                        caseNumber = item.caseKey,
                        source = "manual"
                    )
                    api.watchCase(watchedCase)
                    Log.d(TAG, "Watching case: ${item.caseKey}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle watch", e)
                // Revert on failure
                _uiState.value = _uiState.value.copy(
                    watchedCaseKeys = _uiState.value.watchedCaseKeys,
                    error = "Failed to update watch: ${e.message}"
                )
            }
        }
    }

    // =========================================================================
    // Notifications
    // =========================================================================

    fun loadNotifications() {
        viewModelScope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch
                val notifications = api.getNotifications(userId)

                val unreadCount = notifications.count { !it.read }
                _uiState.value = _uiState.value.copy(
                    notifications = notifications,
                    unreadNotificationCount = unreadCount
                )
                Log.d(TAG, "Loaded ${notifications.size} notifications ($unreadCount unread)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load notifications", e)
            }
        }
    }

    fun markNotificationRead(notification: AppNotification) {
        if (notification.read) return

        viewModelScope.launch {
            try {
                api.markNotificationRead(notification.id)

                // Update local state
                val updated = _uiState.value.notifications.map {
                    if (it.id == notification.id) it.copy(read = true) else it
                }
                val unreadCount = updated.count { !it.read }
                _uiState.value = _uiState.value.copy(
                    notifications = updated,
                    unreadNotificationCount = unreadCount
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark notification read", e)
            }
        }
    }

    fun markAllNotificationsRead() {
        viewModelScope.launch {
            try {
                val userId = authManager.getUserId() ?: return@launch
                api.markAllNotificationsRead(userId)

                // Update local state
                val updated = _uiState.value.notifications.map { it.copy(read = true) }
                _uiState.value = _uiState.value.copy(
                    notifications = updated,
                    unreadNotificationCount = 0
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mark all notifications read", e)
            }
        }
    }

    fun showNotifications() {
        _uiState.value = _uiState.value.copy(showNotifications = true)
    }

    fun hideNotifications() {
        _uiState.value = _uiState.value.copy(showNotifications = false)
    }

    // Navigate to a specific case from notification
    fun navigateToCase(listSourceUrl: String, caseNumber: String, notificationType: String? = null) {
        viewModelScope.launch {
            // Set pending action based on notification type
            val pendingAction = when (notificationType) {
                "comment" -> PendingAction.OpenComments(caseNumber)
                "status_done", "status_undone" -> PendingAction.ScrollToCase(caseNumber)
                else -> PendingAction.ScrollToCase(caseNumber)
            }
            _uiState.value = _uiState.value.copy(pendingAction = pendingAction)

            val list = _uiState.value.lists.find { it.sourceUrl == listSourceUrl }
            if (list != null) {
                selectList(list)
            } else {
                // Create list from URL and navigate
                navigateFromNotification(listSourceUrl, caseNumber)
            }
        }
    }

    fun clearPendingAction() {
        _uiState.value = _uiState.value.copy(pendingAction = null)
    }

    // Execute pending action after items are loaded
    fun executePendingAction() {
        val action = _uiState.value.pendingAction ?: return
        val items = _uiState.value.items

        when (action) {
            is PendingAction.OpenComments -> {
                val item = items.find { it.caseKey == action.caseKey }
                if (item != null) {
                    openComments(item)
                }
                clearPendingAction()
            }
            is PendingAction.ScrollToCase -> {
                // ScrollToCase is handled by ItemsScreen via scrollToCaseIndex
                // Don't clear here - let ItemsScreen clear it after scrolling
            }
        }
    }

    // Get index for scrolling (used by ItemsScreen)
    fun getScrollTargetIndex(): Int? {
        val action = _uiState.value.pendingAction
        if (action is PendingAction.ScrollToCase) {
            val index = _uiState.value.items.indexOfFirst { it.caseKey == action.caseKey }
            return if (index >= 0) index else null
        }
        return null
    }

    // Navigate from a system notification click
    fun navigateFromNotification(listSourceUrl: String, caseNumber: String?) {
        Log.d(TAG, "Navigating from notification: url=$listSourceUrl, case=$caseNumber")
        viewModelScope.launch {
            // First check if we already have this list loaded
            val existingList = _uiState.value.lists.find { it.sourceUrl == listSourceUrl }
            if (existingList != null) {
                Log.d(TAG, "Found existing list: ${existingList.name}")
                selectList(existingList)
                return@launch
            }

            // Extract date from URL if possible
            val dateRegex = Regex("""/(\d{4}-\d{2}-\d{2})/""")
            val dateMatch = dateRegex.find(listSourceUrl)
            val dateFromUrl = dateMatch?.groupValues?.get(1)

            // Try to extract venue from URL
            val venueRegex = Regex("""/([^/]+)/\d{4}-\d{2}-\d{2}/""")
            val venueMatch = venueRegex.find(listSourceUrl)
            val venueFromUrl = venueMatch?.groupValues?.get(1)?.replace("-", " ")?.replaceFirstChar { it.uppercase() }

            Log.d(TAG, "Extracted from URL: date=$dateFromUrl, venue=$venueFromUrl")

            // Create a CourtList with extracted info
            val list = CourtList(
                id = 0,
                name = venueFromUrl ?: "Court List",
                dateIso = dateFromUrl,
                dateText = dateFromUrl ?: "",
                venue = venueFromUrl ?: "",
                type = null,
                sourceUrl = listSourceUrl
            )

            selectList(list)
        }
    }
}
