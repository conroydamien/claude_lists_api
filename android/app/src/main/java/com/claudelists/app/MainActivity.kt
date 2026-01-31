package com.claudelists.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.claudelists.app.api.CaseItem
import com.claudelists.app.ui.screens.CommentsSheet
import com.claudelists.app.ui.screens.ItemsScreen
import com.claudelists.app.ui.screens.ListsScreen
import com.claudelists.app.ui.theme.CourtListsTheme
import com.claudelists.app.viewmodel.MainViewModel
import java.time.LocalDate

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

    // Load initial data
    LaunchedEffect(Unit) {
        if (uiState.lists.isEmpty() && !uiState.isLoading) {
            viewModel.loadListsForDate(LocalDate.now().toString())
        }
    }

    // Show error as snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            viewModel.clearError()
        }
    }

    // Navigation based on selected list
    if (uiState.selectedList != null) {
        ItemsScreen(
            uiState = uiState,
            onBack = { viewModel.clearSelectedList() },
            onToggleDone = { item -> viewModel.toggleDone(item) },
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
    uiState.selectedItem?.let { item ->
        CommentsSheet(
            item = item,
            comments = uiState.comments,
            currentUserId = uiState.user?.id ?: "",
            onDismiss = { viewModel.closeComments() },
            onSendComment = { content -> viewModel.sendComment(content) },
            onDeleteComment = { comment -> viewModel.deleteComment(comment) }
        )
    }
}
