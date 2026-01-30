package com.claudelists.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.compose.material.icons.filled.Refresh
import com.claudelists.app.api.CourtList
import com.claudelists.app.api.Header
import com.claudelists.app.api.Item
import com.claudelists.app.viewmodel.UiState

private const val TAG = "ItemsScreen"

sealed class ListItem {
    data class HeaderItem(val texts: List<String>) : ListItem()
    data class CaseItem(val item: Item, val commentCount: Int) : ListItem()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    uiState: UiState,
    onBack: () -> Unit,
    onToggleDone: (Item) -> Unit,
    onOpenComments: (Item) -> Unit,
    onRefresh: () -> Unit = {}
) {
    val list = uiState.selectedList ?: return

    // Build display items with headers interspersed
    val displayItems = buildDisplayItems(uiState.items, uiState.headers, uiState.commentCounts)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = list.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        list.metadata?.venue?.let { venue ->
                            Text(
                                text = venue,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (displayItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No items in this list",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(
                    items = displayItems,
                    key = { displayItem ->
                        when (displayItem) {
                            is ListItem.HeaderItem -> "header-${displayItem.texts.hashCode()}"
                            is ListItem.CaseItem -> "item-${displayItem.item.id}"
                        }
                    }
                ) { displayItem ->
                    when (displayItem) {
                        is ListItem.HeaderItem -> {
                            HeaderRow(texts = displayItem.texts)
                        }
                        is ListItem.CaseItem -> {
                            // Get the current item from uiState to avoid stale captures
                            val currentItem = uiState.items.find { it.id == displayItem.item.id } ?: displayItem.item
                            val currentCommentCount = uiState.commentCounts[displayItem.item.id] ?: 0
                            CaseRow(
                                item = currentItem,
                                commentCount = currentCommentCount,
                                onToggleDone = {
                                    Log.d(TAG, "onToggleDone clicked for item ${currentItem.id}, done=${currentItem.done}")
                                    onToggleDone(currentItem)
                                },
                                onOpenComments = { onOpenComments(currentItem) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildDisplayItems(
    items: List<Item>,
    headers: List<Header>,
    commentCounts: Map<Int, Int>
): List<ListItem> {
    val result = mutableListOf<ListItem>()

    // Group headers by position
    val headersByPosition = headers.groupBy { it.beforeCase ?: -1 }
    val renderedPositions = mutableSetOf<Int>()

    for (item in items) {
        val listNum = item.metadata?.listNumber

        // Add headers before this case
        if (listNum != null && headersByPosition.containsKey(listNum) && listNum !in renderedPositions) {
            val texts = headersByPosition[listNum]?.map { it.text } ?: emptyList()
            if (texts.isNotEmpty()) {
                result.add(ListItem.HeaderItem(texts))
            }
            renderedPositions.add(listNum)
        }

        result.add(ListItem.CaseItem(item, commentCounts[item.id] ?: 0))
    }

    // Add any trailing headers
    val endHeaders = headers.filter { it.afterCases == true || it.beforeCase == null }
    if (endHeaders.isNotEmpty()) {
        result.add(ListItem.HeaderItem(endHeaders.map { it.text }))
    }

    return result
}

@Composable
fun HeaderRow(texts: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFFF0F7FF)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(24.dp)
                    .background(Color(0xFF1976D2))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = texts.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF1565C0)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseRow(
    item: Item,
    commentCount: Int,
    onToggleDone: () -> Unit,
    onOpenComments: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !item.done) {
                Log.d(TAG, "Surface clicked: item=${item.id}, done=${item.done}")
                onToggleDone()
            },
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Undo button or spacer
            if (item.done) {
                IconButton(
                    onClick = {
                        Log.d(TAG, "Undo button clicked: item=${item.id}")
                        onToggleDone()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Replay,
                        contentDescription = "Undo",
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(32.dp))
            }

            // List number
            item.metadata?.listNumber?.let { num ->
                Text(
                    text = "$num",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(32.dp)
                )
            }

            // Title
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }

            // Comments button
            IconButton(
                onClick = onOpenComments,
                modifier = Modifier.size(32.dp)
            ) {
                BadgedBox(
                    badge = {
                        if (commentCount > 0) {
                            Badge { Text("$commentCount") }
                        }
                    }
                ) {
                    Icon(
                        Icons.Default.ChatBubbleOutline,
                        contentDescription = "Comments",
                        modifier = Modifier.size(20.dp),
                        tint = if (commentCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    Divider()
}
