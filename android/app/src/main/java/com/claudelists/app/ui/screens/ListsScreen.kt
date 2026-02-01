package com.claudelists.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.claudelists.app.api.CourtList
import com.claudelists.app.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(
    uiState: UiState,
    onDateChange: (String?) -> Unit,
    onVenueChange: (String?) -> Unit,
    onListSelect: (CourtList) -> Unit,
    onSignOut: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    unreadNotificationCount: Int = 0
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var venueExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Court Lists") },
                actions = {
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
                    IconButton(onClick = onSignOut) {
                        Icon(Icons.Default.Logout, contentDescription = "Sign Out")
                    }
                }
            )
        }
    ) { padding ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
    ) {
        // Filters row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Date filter
            OutlinedCard(
                modifier = Modifier
                    .weight(1f)
                    .clickable { showDatePicker = true }
            ) {
                Row(
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = "Date",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.selectedDate ?: "All dates",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Venue filter
            ExposedDropdownMenuBox(
                expanded = venueExpanded,
                onExpandedChange = { venueExpanded = it },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedCard(
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Place,
                            contentDescription = "Venue",
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.selectedVenue ?: "All venues",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = venueExpanded)
                    }
                }

                ExposedDropdownMenu(
                    expanded = venueExpanded,
                    onDismissRequest = { venueExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("All venues") },
                        onClick = {
                            onVenueChange(null)
                            venueExpanded = false
                        }
                    )
                    uiState.venues.forEach { venue ->
                        DropdownMenuItem(
                            text = { Text(venue) },
                            onClick = {
                                onVenueChange(venue)
                                venueExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Lists count
        Text(
            text = "${uiState.filteredLists.size} lists",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Lists
        if (uiState.isLoading && uiState.lists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.filteredLists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No lists match the selected filters",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.filteredLists) { list ->
                    ListCard(list = list, onClick = { onListSelect(list) })
                }
            }
        }
    }
    }

    // Date picker dialog
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()

        // Auto-close when date is selected
        LaunchedEffect(datePickerState.selectedDateMillis) {
            datePickerState.selectedDateMillis?.let { millis ->
                val date = java.time.Instant.ofEpochMilli(millis)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                    .toString()
                onDateChange(date)
                showDatePicker = false
            }
        }

        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {},  // No confirm button needed
            dismissButton = {
                TextButton(
                    onClick = {
                        onDateChange(null)
                        showDatePicker = false
                    }
                ) {
                    Text("Clear")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
fun ListCard(
    list: CourtList,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = list.name,
                style = MaterialTheme.typography.titleMedium
            )

            list.type?.let { type ->
                Text(
                    text = type,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (list.venue.isNotBlank()) {
                    Text(
                        text = list.venue,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = list.dateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
