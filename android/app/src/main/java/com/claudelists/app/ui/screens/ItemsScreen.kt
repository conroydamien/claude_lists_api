package com.claudelists.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import android.util.Log
import com.claudelists.app.api.CaseItem
import com.claudelists.app.viewmodel.UiState

private const val TAG = "ItemsScreen"

sealed class DisplayItem {
    data class HeaderItem(val text: String) : DisplayItem()
    data class CaseDisplayItem(val item: CaseItem) : DisplayItem()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    uiState: UiState,
    onBack: () -> Unit,
    onToggleDone: (CaseItem) -> Unit,
    onOpenComments: (CaseItem) -> Unit,
    onRefresh: () -> Unit = {}
) {
    val list = uiState.selectedList ?: return

    // Build display items with headers
    val displayItems = buildDisplayItems(uiState.items, uiState.headers)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = list.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        val subtitle = listOfNotNull(list.venue, list.dateText).joinToString(" · ")
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
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
                itemsIndexed(
                    items = displayItems,
                    key = { index, displayItem ->
                        when (displayItem) {
                            is DisplayItem.HeaderItem -> "header-$index"
                            is DisplayItem.CaseDisplayItem -> "item-${displayItem.item.id}"
                        }
                    }
                ) { index, displayItem ->
                    when (displayItem) {
                        is DisplayItem.HeaderItem -> {
                            HeaderRow(text = displayItem.text)
                        }
                        is DisplayItem.CaseDisplayItem -> {
                            val item = displayItem.item
                            CaseRow(
                                item = item,
                                onToggleDone = {
                                    Log.d(TAG, "onToggleDone clicked for item ${item.id}")
                                    onToggleDone(item)
                                },
                                onOpenComments = { onOpenComments(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun buildDisplayItems(
    items: List<CaseItem>,
    headers: List<String>
): List<DisplayItem> {
    val result = mutableListOf<DisplayItem>()

    // Add headers at the top if any
    if (headers.isNotEmpty()) {
        result.add(DisplayItem.HeaderItem(headers.joinToString(" · ")))
    }

    // Add all items
    for (item in items) {
        result.add(DisplayItem.CaseDisplayItem(item))
    }

    return result
}

@Composable
fun HeaderRow(text: String) {
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
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF1565C0)
            )
        }
    }
}

@Composable
fun CaseRow(
    item: CaseItem,
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
            item.listNumber?.let { num ->
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
                        if (item.commentCount > 0) {
                            Badge { Text("${item.commentCount}") }
                        }
                    }
                ) {
                    Icon(
                        Icons.Default.ChatBubbleOutline,
                        contentDescription = "Comments",
                        modifier = Modifier.size(20.dp),
                        tint = if (item.commentCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    HorizontalDivider()
}
