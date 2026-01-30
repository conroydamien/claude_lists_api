package com.claudelists.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.claudelists.app.api.ApiClient
import com.claudelists.app.api.Item
import com.claudelists.app.ui.screens.CommentsSheet
import com.claudelists.app.ui.screens.ItemsScreen
import com.claudelists.app.ui.screens.ListsScreen
import com.claudelists.app.ui.theme.CourtListsTheme
import com.claudelists.app.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CourtListsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CourtListsApp()
                }
            }
        }
    }
}

@Composable
fun CourtListsApp(
    viewModel: MainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Show error as snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            // In a real app, use SnackbarHost
            viewModel.clearError()
        }
    }

    // Navigation based on selected list
    if (uiState.selectedList != null) {
        ItemsScreen(
            uiState = uiState,
            onBack = { viewModel.clearSelectedList() },
            onToggleDone = { item -> viewModel.toggleItemDone(item) },
            onOpenComments = { item -> viewModel.openComments(item) },
            onRefresh = { viewModel.refreshItems() }
        )
    } else {
        ListsScreen(
            uiState = uiState,
            onDateChange = { viewModel.setDateFilter(it) },
            onVenueChange = { viewModel.setVenueFilter(it) },
            onListSelect = { viewModel.selectList(it) }
        )
    }

    // Comments bottom sheet
    uiState.selectedItemForComments?.let { item ->
        CommentsSheet(
            item = item,
            comments = uiState.comments,
            currentUserId = ApiClient.currentUserId,
            isLoading = uiState.isCommentsLoading,
            onDismiss = { viewModel.closeComments() },
            onSendComment = { content -> viewModel.sendComment(content) },
            onDeleteComment = { comment -> viewModel.deleteComment(comment) }
        )
    }
}
