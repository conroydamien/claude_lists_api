package com.claudelists.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import android.util.Log
import com.claudelists.app.api.CaseItem
import com.claudelists.app.viewmodel.PendingAction
import com.claudelists.app.viewmodel.UiState
import com.claudelists.app.viewmodel.getListCommentKey

private const val TAG = "ItemsScreen"

sealed class DisplayItem {
    data class HeaderItem(val text: String) : DisplayItem()
    data class ListNotesItem(val commentCount: Int) : DisplayItem()
    data class CaseDisplayItem(val item: CaseItem) : DisplayItem()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    uiState: UiState,
    onBack: () -> Unit,
    onToggleDone: (CaseItem) -> Unit,
    onOpenComments: (CaseItem) -> Unit,
    onOpenListComments: (String?) -> Unit = {},
    onToggleWatch: (CaseItem) -> Unit = {},
    isWatching: (CaseItem) -> Boolean = { false },
    onToggleWatchList: () -> Unit = {},
    isWatchingList: Boolean = false,
    onRefresh: () -> Unit = {},
    onClearPendingAction: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    unreadNotificationCount: Int = 0,
    listCommentCount: Int = 0
) {
    val list = uiState.selectedList ?: return
    val listState = rememberLazyListState()

    // Build display items with headers and list notes
    val displayItems = buildDisplayItems(uiState.items, uiState.headers, listCommentCount)

    // Handle pending actions after items are loaded
    LaunchedEffect(uiState.pendingAction, uiState.items) {
        val action = uiState.pendingAction
        if (action != null && uiState.items.isNotEmpty()) {
            when (action) {
                is PendingAction.OpenComments -> {
                    val item = uiState.items.find { it.caseKey == action.caseKey }
                    if (item != null) {
                        // Find index in display items (accounting for header)
                        val displayIndex = displayItems.indexOfFirst {
                            it is DisplayItem.CaseDisplayItem && it.item.caseKey == action.caseKey
                        }
                        if (displayIndex >= 0) {
                            listState.animateScrollToItem(displayIndex)
                        }
                        onOpenComments(item)
                    } else {
                        // No matching case item found - this is likely a list comment
                        // (List comment keys don't match case numbers like "2024/12345")
                        Log.d(TAG, "No case found for '${action.caseKey}', opening list comments")
                        onOpenListComments(action.caseKey)
                    }
                    onClearPendingAction()
                }
                is PendingAction.ScrollToCase -> {
                    val displayIndex = displayItems.indexOfFirst {
                        it is DisplayItem.CaseDisplayItem && it.item.caseKey == action.caseKey
                    }
                    if (displayIndex >= 0) {
                        listState.animateScrollToItem(displayIndex)
                    }
                    onClearPendingAction()
                }
                is PendingAction.OpenListComments -> {
                    onOpenListComments(action.caseKey)
                    onClearPendingAction()
                }
            }
        }
    }

    // Live updating elapsed time for top bar - tick every second
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            tick = System.currentTimeMillis() / 1000
        }
    }

    // Compute elapsed time from server timestamp for this specific list
    val lastUpdateText = run {
        val serverTimestamp = uiState.lastUpdateByList[list.sourceUrl]
        if (serverTimestamp != null) {
            // Use tick to force recomputation every second
            @Suppress("UNUSED_EXPRESSION")
            tick
            val elapsedSeconds = maxOf(0L, (System.currentTimeMillis() - serverTimestamp) / 1000)
            val days = elapsedSeconds / 86400
            val hours = (elapsedSeconds % 86400) / 3600
            val minutes = (elapsedSeconds % 3600) / 60
            val seconds = elapsedSeconds % 60

            val timeStr = when {
                days > 0 -> "${days}d"
                hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
                minutes > 0 -> "${minutes}m ${seconds}s"
                else -> "${seconds}s"
            }
            "Updated: $timeStr ago"
        } else {
            "No updates"
        }
    }

    Scaffold(
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    // Top row: back button and action icons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                        Row {
                            // Notification bell with badge
                            IconButton(onClick = onNotificationsClick) {
                                BadgedBox(
                                    badge = {
                                        if (unreadNotificationCount > 0) {
                                            Badge {
                                                Text(
                                                    if (unreadNotificationCount > 99) "99+"
                                                    else unreadNotificationCount.toString()
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Notifications,
                                        contentDescription = "Notifications"
                                    )
                                }
                            }
                            IconButton(onClick = onRefresh) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                        }
                    }
                    // Bottom row: title text
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = list.venue.ifBlank { list.name },
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (list.dateText.isNotBlank()) {
                            Text(
                                text = list.dateText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = lastUpdateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
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
                state = listState,
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
                                is DisplayItem.ListNotesItem -> "list-notes"
                                is DisplayItem.CaseDisplayItem -> "item-${displayItem.item.id}"
                            }
                        }
                    ) { index, displayItem ->
                        when (displayItem) {
                            is DisplayItem.HeaderItem -> {
                                HeaderRow(text = displayItem.text)
                            }
                            is DisplayItem.ListNotesItem -> {
                                ListNotesRow(
                                    commentCount = displayItem.commentCount,
                                    isWatching = isWatchingList,
                                    onClick = { onOpenListComments(null) },
                                    onToggleWatch = onToggleWatchList
                                )
                            }
                            is DisplayItem.CaseDisplayItem -> {
                                val item = displayItem.item
                                CaseRow(
                                    item = item,
                                    isWatching = isWatching(item),
                                    onToggleDone = {
                                        Log.d(TAG, "onToggleDone clicked for item ${item.id}")
                                        onToggleDone(item)
                                    },
                                    onOpenComments = { onOpenComments(item) },
                                    onToggleWatch = { onToggleWatch(item) }
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
    headers: List<String>,
    listCommentCount: Int
): List<DisplayItem> {
    val result = mutableListOf<DisplayItem>()

    // Add headers at the top if any
    if (headers.isNotEmpty()) {
        result.add(DisplayItem.HeaderItem(headers.joinToString("\n")))
    }

    // Add list notes row
    result.add(DisplayItem.ListNotesItem(listCommentCount))

    // Add all items
    for (item in items) {
        result.add(DisplayItem.CaseDisplayItem(item))
    }

    return result
}

@Composable
fun HeaderRow(text: String) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
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
                color = Color(0xFF1565C0),
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = Color(0xFF1976D2),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun ListNotesRow(
    commentCount: Int,
    isWatching: Boolean,
    onClick: () -> Unit,
    onToggleWatch: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.ChatBubbleOutline,
                contentDescription = "List Notes",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
            if (commentCount > 0) {
                Text(
                    text = "$commentCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "List Notes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
            // Watch/notify button
            IconButton(
                onClick = onToggleWatch,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (isWatching) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                    contentDescription = if (isWatching) "Stop notifications" else "Get notified",
                    modifier = Modifier.size(20.dp),
                    tint = if (isWatching) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    Divider()
}

@Composable
fun CaseRow(
    item: CaseItem,
    isWatching: Boolean = false,
    onToggleDone: () -> Unit,
    onOpenComments: () -> Unit,
    onToggleWatch: () -> Unit = {}
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !item.done) {
                Log.d(TAG, "Surface clicked: item=${item.id}, done=${item.done}")
                onToggleDone()
            },
        color = if (item.done) Color(0xFFE0E0E0) else MaterialTheme.colorScheme.surface
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

            // List number with optional suffix (e.g., "4a")
            item.listPosition?.let { pos ->
                Text(
                    text = pos,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(32.dp)
                )
            }

            // Title with case number
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Case number (bold)
                item.caseNumber?.let { caseNum ->
                    Text(
                        text = caseNum,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                }
                // Title
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }

            // Watch/notify button (bell icon)
            IconButton(
                onClick = onToggleWatch,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (isWatching) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                    contentDescription = if (isWatching) "Stop notifications" else "Get notified",
                    modifier = Modifier.size(20.dp),
                    tint = if (isWatching) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Comments button - show red raised hand if urgent
            Row(
                modifier = Modifier
                    .clickable(onClick = onOpenComments)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.hasUrgent) {
                    Icon(
                        Icons.Default.PanTool,
                        contentDescription = "Needs help",
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFFD32F2F)
                    )
                } else {
                    Icon(
                        Icons.Default.ChatBubbleOutline,
                        contentDescription = "Comments",
                        modifier = Modifier.size(20.dp),
                        tint = if (item.commentCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.commentCount > 0) {
                    Text(
                        text = "${item.commentCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.hasUrgent) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
        }
    }
    Divider()
}

