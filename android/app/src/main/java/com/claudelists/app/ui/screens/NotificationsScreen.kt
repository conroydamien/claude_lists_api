package com.claudelists.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.claudelists.app.api.AppNotification
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    notifications: List<AppNotification>,
    onBack: () -> Unit,
    onNotificationClick: (AppNotification) -> Unit,
    onMarkAllRead: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (notifications.any { !it.read }) {
                        IconButton(onClick = onMarkAllRead) {
                            Icon(Icons.Default.DoneAll, contentDescription = "Mark all as read")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (notifications.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No notifications",
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
                items(notifications, key = { it.id }) { notification ->
                    NotificationItem(
                        notification = notification,
                        onClick = { onNotificationClick(notification) }
                    )
                }
            }
        }
    }
}

@Composable
fun NotificationItem(
    notification: AppNotification,
    onClick: () -> Unit
) {
    val backgroundColor = if (notification.read) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Icon based on notification type
            val icon = when (notification.type) {
                "comment" -> Icons.Default.ChatBubble
                "status_done" -> Icons.Default.Check
                "status_undone" -> Icons.Default.Close
                else -> Icons.Default.ChatBubble
            }
            val iconTint = when (notification.type) {
                "comment" -> MaterialTheme.colorScheme.primary
                "status_done" -> MaterialTheme.colorScheme.tertiary
                "status_undone" -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .size(24.dp)
                    .padding(end = 4.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Title based on notification type
                val title = when (notification.type) {
                    "comment" -> "${notification.actorName} commented"
                    "status_done" -> "${notification.actorName} marked as done"
                    "status_undone" -> "${notification.actorName} marked as not done"
                    else -> notification.actorName
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (notification.read) FontWeight.Normal else FontWeight.SemiBold
                )

                // Case identifier
                Text(
                    text = "Case: ${notification.caseNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Comment content preview (for comment notifications)
                notification.content?.let { content ->
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // Timestamp
                Text(
                    text = formatTimestamp(notification.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Unread indicator
            if (!notification.read) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .padding(start = 8.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(8.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary
                    ) {}
                }
            }
        }
    }
    Divider()
}

private fun formatTimestamp(isoTimestamp: String): String {
    return try {
        val instant = Instant.parse(isoTimestamp)
        val now = Instant.now()
        val minutes = ChronoUnit.MINUTES.between(instant, now)
        val hours = ChronoUnit.HOURS.between(instant, now)
        val days = ChronoUnit.DAYS.between(instant, now)

        when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> {
                val formatter = DateTimeFormatter.ofPattern("MMM d")
                instant.atZone(ZoneId.systemDefault()).format(formatter)
            }
        }
    } catch (e: Exception) {
        isoTimestamp
    }
}
