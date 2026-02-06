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
    data class OpenListComments(val caseKey: String) : PendingAction()
}

// Generate key for list-level comments from list title
fun getListCommentKey(list: CourtList): String {
    return "${list.venue} - ${list.dateText}".trim().ifEmpty { list.name }
}

// Available court types
enum class CourtType(val id: String, val displayName: String) {
    CIRCUIT("circuit-court", "Circuit Court"),
    HIGH("high-court", "High Court"),
    COURT_OF_APPEAL("court-of-appeal", "Court of Appeal")
}

data class UiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val userId: String? = null,
    val userEmail: String? = null,
    val lists: List<CourtList> = emptyList(),
    val filteredLists: List<CourtList> = emptyList(),
    val venues: List<String> = emptyList(),
    val selectedCourt: CourtType = CourtType.CIRCUIT,
    val selectedDate: String? = null,
    val selectedVenue: String? = null,
    val selectedList: CourtList? = null,
    val items: List<CaseItem> = emptyList(),
    val headers: List<String> = emptyList(),
    val lastUpdateByList: Map<String, Long> = emptyMap(), // Per-list update timestamps (sourceUrl -> epoch millis)
    val comments: List<Comment> = emptyList(),
    val selectedItem: CaseItem? = null,
    val showListComments: Boolean = false,
    val listCommentsTitle: String? = null, // Override title when opened from notification
    val listCommentCount: Int = 0,
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

    // Flag to prevent realtime reload from overwriting optimistic updates during toggle
    private var suppressWatchReload = false
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
                Log.i(TAG, "Auth state changed: authenticated=${authState.isAuthenticated}")
                _uiState.value = _uiState.value.copy(
                    isAuthenticated = authState.isAuthenticated,
                    userId = authState.userId,
                    userEmail = authState.userEmail
                )

                if (authState.isAuthenticated) {
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

    fun loadListsForDate(date: String, court: CourtType? = null) {
        val courtToUse = court ?: _uiState.value.selectedCourt
        Log.d(TAG, "loadListsForDate called with date: $date, court: ${courtToUse.id}")
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, selectedDate = date, selectedCourt = courtToUse)

            try {
                val entries = api.getListings(date, courtToUse.id)
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

    fun setCourtFilter(court: CourtType) {
        if (court != _uiState.value.selectedCourt) {
            val date = _uiState.value.selectedDate ?: return
            loadListsForDate(date, court)
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
                val (updatedItems, lastUpdate) = loadCaseStatuses(list.sourceUrl, items)
                items = loadCommentCounts(list.sourceUrl, updatedItems)

                // Load list-level comment count
                val listCommentCount = loadListCommentCount(list)

                // Update timestamp for this specific list
                val updatedTimestamps = if (lastUpdate != null) {
                    _uiState.value.lastUpdateByList + (list.sourceUrl to lastUpdate)
                } else {
                    _uiState.value.lastUpdateByList
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    items = items,
                    headers = casesResponse.headers,
                    lastUpdateByList = updatedTimestamps,
                    listCommentCount = listCommentCount,
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

    private suspend fun loadCaseStatuses(sourceUrl: String, items: List<CaseItem>): Pair<List<CaseItem>, Long?> {
        return try {
            val caseKeys = items.map { it.caseKey }
            val statuses = api.getCaseStatuses(sourceUrl, caseKeys)

            // Find most recent update time as epoch millis
            val lastUpdateMillis = statuses
                .mapNotNull { it.updatedAt }
                .mapNotNull { parseToMillis(it) }
                .maxOrNull()

            val statusMap = statuses.associateBy { it.caseNumber }
            val updatedItems = items.map { item ->
                val status = statusMap[item.caseKey]
                if (status != null) item.copy(done = status.done) else item
            }
            Pair(updatedItems, lastUpdateMillis)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load case statuses", e)
            Pair(items, null)
        }
    }

    private fun parseToMillis(isoTimestamp: String): Long? {
        return try {
            java.time.Instant.parse(isoTimestamp).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadCommentCounts(sourceUrl: String, items: List<CaseItem>): List<CaseItem> {
        return try {
            val caseKeys = items.map { it.caseKey }
            val comments = api.getCommentCounts(sourceUrl, caseKeys)
            Log.d(TAG, "Got ${comments.size} comment count entries")

            // Group by case number and count
            val countMap = comments.groupingBy { it.caseNumber }.eachCount()
            // Check which cases have urgent comments
            val urgentMap = comments.filter { it.urgent }.map { it.caseNumber }.toSet()
            Log.d(TAG, "Comment count map: $countMap, urgent cases: $urgentMap")

            items.map { item ->
                val count = countMap[item.caseKey] ?: 0
                val hasUrgent = item.caseKey in urgentMap
                item.copy(commentCount = count, hasUrgent = hasUrgent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load comment counts", e)
            items
        }
    }

    private suspend fun loadListCommentCount(list: CourtList): Int {
        return try {
            val comments = api.getComments(list.sourceUrl, getListCommentKey(list))
            comments.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load list comment count", e)
            0
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

            // Optimistic update - include per-list timestamp
            val updatedItems = _uiState.value.items.map {
                if (it.id == item.id) it.copy(done = newDone) else it
            }
            val updatedTimestamps = _uiState.value.lastUpdateByList + (item.listSourceUrl to System.currentTimeMillis())
            _uiState.value = _uiState.value.copy(
                items = updatedItems,
                lastUpdateByList = updatedTimestamps
            )

            try {
                api.upsertCaseStatus(item.listSourceUrl, item.caseKey, newDone)
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
        _uiState.value = _uiState.value.copy(
            selectedItem = null,
            showListComments = false,
            listCommentsTitle = null,
            comments = emptyList()
        )
    }

    fun openListComments(overrideCaseKey: String? = null) {
        val list = _uiState.value.selectedList ?: return
        viewModelScope.launch {
            // Use override key if provided (from notification), otherwise generate from list
            val caseKey = overrideCaseKey ?: getListCommentKey(list)
            // Use override key as title if provided (has human-readable date format)
            val title = overrideCaseKey
            _uiState.value = _uiState.value.copy(showListComments = true, listCommentsTitle = title)
            try {
                Log.d(TAG, "Loading list comments with key: $caseKey")
                val comments = api.getComments(list.sourceUrl, caseKey)
                _uiState.value = _uiState.value.copy(comments = comments)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load list comments", e)
            }
        }
    }

    private suspend fun loadComments(item: CaseItem) {
        try {
            val comments = api.getComments(item.listSourceUrl, item.caseKey)
            _uiState.value = _uiState.value.copy(comments = comments)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load comments", e)
        }
    }

    fun sendComment(content: String, urgent: Boolean = false) {
        val userId = _uiState.value.userId ?: return
        val email = _uiState.value.userEmail ?: "User"

        // Check if this is a list comment or item comment
        if (_uiState.value.showListComments) {
            sendListComment(content, userId, email, urgent)
        } else {
            val item = _uiState.value.selectedItem ?: return
            sendItemComment(content, userId, email, item, urgent)
        }
    }

    private fun sendItemComment(content: String, userId: String, email: String, item: CaseItem, urgent: Boolean) {
        viewModelScope.launch {
            try {
                api.addComment(item.listSourceUrl, item.caseKey, content, urgent)

                // Reload comments
                loadComments(item)

                // Update comment count and urgent flag immutably
                val updatedItems = _uiState.value.items.map {
                    if (it.id == item.id) it.copy(
                        commentCount = it.commentCount + 1,
                        hasUrgent = it.hasUrgent || urgent
                    ) else it
                }
                _uiState.value = _uiState.value.copy(
                    items = updatedItems,
                    snackbarMessage = if (urgent) "Urgent comment sent" else "Comment sent"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send comment: ${e.message}", e)
                _uiState.value = _uiState.value.copy(error = "Failed to send: ${e.message}")
            }
        }
    }

    private fun sendListComment(content: String, userId: String, email: String, urgent: Boolean) {
        val list = _uiState.value.selectedList ?: return
        viewModelScope.launch {
            try {
                api.addComment(list.sourceUrl, getListCommentKey(list), content, urgent)

                // Reload list comments
                val comments = api.getComments(list.sourceUrl, getListCommentKey(list))
                _uiState.value = _uiState.value.copy(
                    comments = comments,
                    listCommentCount = comments.size,
                    snackbarMessage = if (urgent) "Urgent comment sent" else "Comment sent"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send list comment: ${e.message}", e)
                _uiState.value = _uiState.value.copy(error = "Failed to send: ${e.message}")
            }
        }
    }

    fun clearSnackbar() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }

    fun deleteComment(comment: Comment) {
        viewModelScope.launch {
            try {
                comment.id?.let { api.deleteComment(it) }

                // Check if this is a list comment or item comment
                if (_uiState.value.showListComments) {
                    val list = _uiState.value.selectedList ?: return@launch
                    val comments = api.getComments(list.sourceUrl, getListCommentKey(list))
                    _uiState.value = _uiState.value.copy(
                        comments = comments,
                        listCommentCount = comments.size
                    )
                } else {
                    val item = _uiState.value.selectedItem ?: return@launch
                    loadComments(item)
                    // Recalculate hasUrgent from remaining comments
                    val remainingComments = _uiState.value.comments
                    val stillHasUrgent = remainingComments.any { it.urgent }
                    val updatedItems = _uiState.value.items.map {
                        if (it.id == item.id) it.copy(
                            commentCount = maxOf(0, it.commentCount - 1),
                            hasUrgent = stillHasUrgent
                        ) else it
                    }
                    _uiState.value = _uiState.value.copy(items = updatedItems)
                }
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

                    // Find most recent update time from server
                    val latestUpdateMillis = statuses
                        .mapNotNull { it.updatedAt }
                        .mapNotNull { parseToMillis(it) }
                        .maxOrNull()

                    val updatedItems = _uiState.value.items.map { item ->
                        val newDone = statusMap[item.caseKey]?.done ?: item.done
                        if (newDone != item.done) {
                            item.copy(done = newDone)
                        } else {
                            item
                        }
                    }

                    // Update per-list timestamp
                    val updatedTimestamps = if (latestUpdateMillis != null) {
                        _uiState.value.lastUpdateByList + (selectedList.sourceUrl to latestUpdateMillis)
                    } else {
                        _uiState.value.lastUpdateByList
                    }

                    _uiState.value = _uiState.value.copy(
                        items = updatedItems,
                        lastUpdateByList = updatedTimestamps
                    )
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
            // Skip reload if we're in the middle of a toggle operation
            if (suppressWatchReload) {
                Log.d(TAG, "Skipping watch reload due to pending toggle")
                return@launch
            }
            try {
                val watched = api.getWatchedCases()

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
            val key = "${item.listSourceUrl}|${item.caseKey}"
            val isCurrentlyWatching = key in _uiState.value.watchedCaseKeys

            // Suppress realtime reloads during toggle
            suppressWatchReload = true

            // Optimistic update
            val newKeys = if (isCurrentlyWatching) {
                _uiState.value.watchedCaseKeys - key
            } else {
                _uiState.value.watchedCaseKeys + key
            }
            _uiState.value = _uiState.value.copy(watchedCaseKeys = newKeys)

            try {
                if (isCurrentlyWatching) {
                    api.unwatchCase(item.listSourceUrl, item.caseKey)
                    Log.d(TAG, "Unwatched case: ${item.caseKey}")
                } else {
                    api.watchCase(item.listSourceUrl, item.caseKey)
                    Log.d(TAG, "Watching case: ${item.caseKey}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle watch", e)
                // Revert on failure
                _uiState.value = _uiState.value.copy(
                    watchedCaseKeys = _uiState.value.watchedCaseKeys,
                    error = "Failed to update watch: ${e.message}"
                )
            } finally {
                // Re-enable reloads after a short delay to let realtime events pass
                kotlinx.coroutines.delay(500)
                suppressWatchReload = false
            }
        }
    }

    fun isWatchingList(): Boolean {
        val list = _uiState.value.selectedList ?: return false
        return "${list.sourceUrl}|${getListCommentKey(list)}" in _uiState.value.watchedCaseKeys
    }

    fun toggleWatchList() {
        viewModelScope.launch {
            val list = _uiState.value.selectedList ?: return@launch
            val listCommentKey = getListCommentKey(list)
            val key = "${list.sourceUrl}|${listCommentKey}"
            val isCurrentlyWatching = key in _uiState.value.watchedCaseKeys

            // Suppress realtime reloads during toggle
            suppressWatchReload = true

            // Optimistic update
            val newKeys = if (isCurrentlyWatching) {
                _uiState.value.watchedCaseKeys - key
            } else {
                _uiState.value.watchedCaseKeys + key
            }
            _uiState.value = _uiState.value.copy(watchedCaseKeys = newKeys)

            try {
                if (isCurrentlyWatching) {
                    api.unwatchCase(list.sourceUrl, listCommentKey)
                    Log.d(TAG, "Unwatched list comments: ${list.sourceUrl}")
                } else {
                    api.watchCase(list.sourceUrl, listCommentKey)
                    Log.d(TAG, "Watching list comments: ${list.sourceUrl}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle list watch", e)
                // Revert on failure
                _uiState.value = _uiState.value.copy(
                    watchedCaseKeys = _uiState.value.watchedCaseKeys,
                    error = "Failed to update watch: ${e.message}"
                )
            } finally {
                // Re-enable reloads after a short delay to let realtime events pass
                kotlinx.coroutines.delay(500)
                suppressWatchReload = false
            }
        }
    }

    // =========================================================================
    // Notifications
    // =========================================================================

    fun loadNotifications() {
        viewModelScope.launch {
            try {
                val notifications = api.getNotifications()

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
                api.markAllNotificationsRead()

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

    fun deleteNotification(notification: AppNotification) {
        viewModelScope.launch {
            // Optimistic update
            val updated = _uiState.value.notifications.filter { it.id != notification.id }
            val unreadCount = updated.count { !it.read }
            _uiState.value = _uiState.value.copy(
                notifications = updated,
                unreadNotificationCount = unreadCount
            )

            try {
                api.deleteNotification(notification.id)
                Log.d(TAG, "Deleted notification: ${notification.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete notification", e)
                // Revert on failure
                loadNotifications()
            }
        }
    }

    fun clearAllNotifications() {
        viewModelScope.launch {
            // Optimistic update
            _uiState.value = _uiState.value.copy(
                notifications = emptyList(),
                unreadNotificationCount = 0
            )

            try {
                api.deleteAllNotifications()
                Log.d(TAG, "Cleared all notifications")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear all notifications", e)
                // Revert on failure
                loadNotifications()
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
        Log.d(TAG, "navigateToCase: url=$listSourceUrl, caseNumber=$caseNumber, type=$notificationType")
        viewModelScope.launch {
            val list = _uiState.value.lists.find { it.sourceUrl == listSourceUrl }
            Log.d(TAG, "navigateToCase: found existing list=${list != null}")

            if (list != null) {
                // Check if this is a list comment
                val listCommentKey = getListCommentKey(list)
                val isListComment = caseNumber == listCommentKey
                Log.d(TAG, "navigateToCase: isListComment=$isListComment, caseNumber='$caseNumber', listCommentKey='$listCommentKey'")

                // Set pending action based on notification type
                val pendingAction = when {
                    isListComment && notificationType == "comment" -> PendingAction.OpenListComments(caseNumber)
                    notificationType == "comment" -> PendingAction.OpenComments(caseNumber)
                    notificationType == "status_done" || notificationType == "status_undone" -> PendingAction.ScrollToCase(caseNumber)
                    else -> PendingAction.ScrollToCase(caseNumber)
                }
                Log.d(TAG, "navigateToCase: pendingAction=$pendingAction")
                _uiState.value = _uiState.value.copy(pendingAction = pendingAction)
                selectList(list)
            } else {
                // List not loaded - delegate to navigateFromNotification which will create temp list
                Log.d(TAG, "navigateToCase: list not found, delegating to navigateFromNotification")
                navigateFromNotification(listSourceUrl, caseNumber, notificationType)
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
            is PendingAction.OpenListComments -> {
                openListComments(action.caseKey)
                clearPendingAction()
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
    fun navigateFromNotification(listSourceUrl: String, caseNumber: String?, notificationType: String? = null) {
        Log.d(TAG, "Navigating from notification: url=$listSourceUrl, case=$caseNumber, type=$notificationType")
        viewModelScope.launch {
            // First check if we already have this list loaded
            val existingList = _uiState.value.lists.find { it.sourceUrl == listSourceUrl }

            if (existingList != null) {
                Log.d(TAG, "Found existing list: ${existingList.name}")
                // Check if this is a list comment
                val isListComment = caseNumber == getListCommentKey(existingList)
                Log.d(TAG, "Is list comment (existing): $isListComment, caseNumber=$caseNumber, listCommentKey=${getListCommentKey(existingList)}")

                // Set pending action based on notification type
                if (caseNumber != null) {
                    val pendingAction = when {
                        isListComment && notificationType == "comment" -> PendingAction.OpenListComments(caseNumber)
                        notificationType == "comment" -> PendingAction.OpenComments(caseNumber)
                        else -> PendingAction.ScrollToCase(caseNumber)
                    }
                    _uiState.value = _uiState.value.copy(pendingAction = pendingAction)
                }
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

            // Check if this is a list comment using the created list
            val isListComment = caseNumber == getListCommentKey(list)
            Log.d(TAG, "Is list comment (created): $isListComment, caseNumber=$caseNumber, listCommentKey=${getListCommentKey(list)}")

            // Set pending action based on notification type
            if (caseNumber != null) {
                val pendingAction = when {
                    isListComment && notificationType == "comment" -> PendingAction.OpenListComments(caseNumber)
                    notificationType == "comment" -> PendingAction.OpenComments(caseNumber)
                    else -> PendingAction.ScrollToCase(caseNumber)
                }
                _uiState.value = _uiState.value.copy(pendingAction = pendingAction)
            }

            selectList(list)
        }
    }
}
