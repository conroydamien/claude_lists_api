package com.claudelists.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.claudelists.app.api.Comment
import com.claudelists.app.ui.screens.CommentsSheet
import com.claudelists.app.ui.screens.ItemsScreen
import com.claudelists.app.ui.screens.ListsScreen
import com.claudelists.app.ui.screens.NotificationsSheet
import com.claudelists.app.ui.screens.SignInScreen
import com.claudelists.app.ui.theme.CourtListsTheme
import com.claudelists.app.viewmodel.getListCommentKey
import com.claudelists.app.viewmodel.MainViewModel
import java.time.LocalDate

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    // Observable state for pending navigation from notification clicks
    private val _pendingNavigation = mutableStateOf<PendingNavigation?>(null)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        Log.d(TAG, "Notification permission granted: $isGranted")
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "Google Sign-In result: ${result.resultCode}")
        lifecycleScope.launch {
            val app = CourtListsApplication.instance
            app.authManager.handleSignInResult(result.data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create notification channel
        NotificationHelper.createNotificationChannel(this)

        // Request notification permission on Android 13+
        requestNotificationPermission()

        // Handle notification deep link
        handleIntent(intent)

        setContent {
            CourtListsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CourtListsApp(
                        pendingNavigation = _pendingNavigation.value,
                        onNavigationHandled = { _pendingNavigation.value = null },
                        onSignInWithGoogle = {
                            val intent = CourtListsApplication.instance.authManager.getSignInIntent()
                            googleSignInLauncher.launch(intent)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Handle notification click - set observable state
        intent?.getStringExtra("list_source_url")?.let { listUrl ->
            val caseNumber = intent.getStringExtra("case_number")
            val notificationType = intent.getStringExtra("notification_type")
            Log.d(TAG, "Notification click: list=$listUrl, case=$caseNumber, type=$notificationType")
            _pendingNavigation.value = PendingNavigation(listUrl, caseNumber, notificationType)
        }
    }

    data class PendingNavigation(val listSourceUrl: String, val caseNumber: String?, val notificationType: String? = null)

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission already granted
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}

@Composable
fun CourtListsApp(
    viewModel: MainViewModel = viewModel(),
    pendingNavigation: MainActivity.PendingNavigation? = null,
    onNavigationHandled: () -> Unit = {},
    onSignInWithGoogle: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    val context = LocalContext.current

    // Share a comment via Android share sheet
    fun shareComment(comment: Comment, title: String) {
        val shareText = buildString {
            append("Re: $title\n\n")
            append("\"${comment.content}\"\n\n")
            append("— ${comment.authorName}")
        }
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share comment")
        context.startActivity(shareIntent)
    }

    // Show snackbar messages
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message, duration = androidx.compose.material3.SnackbarDuration.Short)
            viewModel.clearSnackbar()
        }
    }

    // Show sign-in screen if not authenticated
    if (!uiState.isAuthenticated) {
        SignInScreen(
            onSignInWithGoogle = onSignInWithGoogle,
            isLoading = uiState.isLoading,
            error = uiState.error
        )
        return
    }

    // Load initial data when authenticated
    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated && uiState.lists.isEmpty() && !uiState.isLoading) {
            viewModel.loadListsForDate(LocalDate.now().toString())
        }
    }

    // Handle notification click navigation - triggered when pendingNavigation changes
    LaunchedEffect(pendingNavigation) {
        if (uiState.isAuthenticated && pendingNavigation != null) {
            viewModel.navigateFromNotification(
                pendingNavigation.listSourceUrl,
                pendingNavigation.caseNumber,
                pendingNavigation.notificationType
            )
            onNavigationHandled()
        }
    }

    // Show error as snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            viewModel.clearError()
        }
    }

    // Wrap in Box with SnackbarHost for feedback messages
    androidx.compose.foundation.layout.Box(modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
    // Navigation based on state
    when {
        uiState.selectedList != null -> {
            ItemsScreen(
                uiState = uiState,
                onBack = { viewModel.clearSelectedList() },
                onToggleDone = { item -> viewModel.toggleDone(item) },
                onOpenComments = { item -> viewModel.openComments(item) },
                onOpenListComments = { caseKey -> viewModel.openListComments(caseKey) },
                onToggleWatch = { item -> viewModel.toggleWatch(item) },
                isWatching = { item -> viewModel.isWatching(item.listSourceUrl, item.caseKey) },
                onToggleWatchList = { viewModel.toggleWatchList() },
                isWatchingList = uiState.selectedList?.let { list ->
                    "${list.sourceUrl}|${getListCommentKey(list)}" in uiState.watchedCaseKeys
                } ?: false,
                onRefresh = { viewModel.refreshItems() },
                onClearPendingAction = { viewModel.clearPendingAction() },
                onNotificationsClick = { viewModel.showNotifications() },
                unreadNotificationCount = uiState.unreadNotificationCount,
                listCommentCount = uiState.listCommentCount
            )
        }
        else -> {
            ListsScreen(
                uiState = uiState,
                onDateChange = { viewModel.setDateFilter(it) },
                onVenueChange = { viewModel.setVenueFilter(it) },
                onCourtChange = { viewModel.setCourtFilter(it) },
                onListSelect = { viewModel.selectList(it) },
                onSignOut = { viewModel.signOut() },
                onNotificationsClick = { viewModel.showNotifications() },
                unreadNotificationCount = uiState.unreadNotificationCount
            )
        }
    }

    // Case comments bottom sheet
    uiState.selectedItem?.let { item ->
        val commentTitle = item.caseNumber ?: item.title
        val shareTitle = buildString {
            uiState.selectedList?.venue?.let { append("$it - ") }
            item.listNumber?.let { append("#$it - ") }
            append(item.caseNumber ?: item.title)
            if (item.caseNumber != null && item.title.isNotBlank()) {
                append(" - ${item.title}")
            }
        }
        CommentsSheet(
            title = commentTitle,
            comments = uiState.comments,
            currentUserId = uiState.userId ?: "",
            onDismiss = { viewModel.closeComments() },
            onSendComment = { content, urgent -> viewModel.sendComment(content, urgent) },
            onDeleteComment = { comment -> viewModel.deleteComment(comment) },
            onShareComment = { comment -> shareComment(comment, shareTitle) }
        )
    }

    // List comments bottom sheet
    if (uiState.showListComments) {
        // Use override title from notification, or generate from list
        val listTitle = uiState.listCommentsTitle ?: run {
            val list = uiState.selectedList
            if (list != null) {
                "${list.venue} - ${list.dateText}".trim().trimStart('-').trimEnd('-').trim()
            } else ""
        }
        CommentsSheet(
            title = listTitle,
            comments = uiState.comments,
            currentUserId = uiState.userId ?: "",
            onDismiss = { viewModel.closeComments() },
            onSendComment = { content, urgent -> viewModel.sendComment(content, urgent) },
            onDeleteComment = { comment -> viewModel.deleteComment(comment) },
            onShareComment = { comment -> shareComment(comment, listTitle) }
        )
    }

    // Notifications bottom sheet
    if (uiState.showNotifications) {
        NotificationsSheet(
            notifications = uiState.notifications,
            onDismiss = { viewModel.hideNotifications() },
            onNotificationClick = { notification ->
                viewModel.markNotificationRead(notification)
                viewModel.navigateToCase(
                    notification.listSourceUrl,
                    notification.caseNumber,
                    notification.type
                )
                viewModel.hideNotifications()
            },
            onMarkAllRead = { viewModel.markAllNotificationsRead() },
            onDeleteNotification = { notification -> viewModel.deleteNotification(notification) },
            onClearAll = { viewModel.clearAllNotifications() }
        )
    }

    // Snackbar for feedback messages
    androidx.compose.material3.SnackbarHost(
        hostState = snackbarHostState,
        modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
    )
    } // End Box
}
