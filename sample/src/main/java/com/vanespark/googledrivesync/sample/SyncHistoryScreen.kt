package com.vanespark.googledrivesync.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vanespark.googledrivesync.sync.SyncHistoryEntry
import com.vanespark.googledrivesync.sync.SyncHistoryStatus
import com.vanespark.googledrivesync.sync.SyncStatistics
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHistoryScreen(
    historyFlow: StateFlow<List<SyncHistoryEntry>>,
    statistics: SyncStatistics,
    onBack: () -> Unit,
    onClearHistory: () -> Unit
) {
    val history by historyFlow.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (history.isNotEmpty()) {
                        TextButton(onClick = onClearHistory) {
                            Text("Clear")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Statistics Card
            StatisticsCard(
                statistics = statistics,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // History List
            if (history.isEmpty()) {
                EmptyHistoryView(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    items(history, key = { it.id }) { entry ->
                        HistoryEntryCard(
                            entry = entry,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticsCard(
    statistics: SyncStatistics,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Statistics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "Total Syncs",
                    value = statistics.totalSyncs.toString()
                )
                StatItem(
                    label = "Successful",
                    value = statistics.successfulSyncs.toString(),
                    color = Color(0xFF4CAF50)
                )
                StatItem(
                    label = "Failed",
                    value = statistics.failedSyncs.toString(),
                    color = Color(0xFFF44336)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "Uploaded",
                    value = statistics.totalFilesUploaded.toString()
                )
                StatItem(
                    label = "Downloaded",
                    value = statistics.totalFilesDownloaded.toString()
                )
                StatItem(
                    label = "Transferred",
                    value = formatBytes(statistics.totalBytesTransferred)
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryEntryCard(
    entry: SyncHistoryEntry,
    modifier: Modifier = Modifier
) {
    val (icon, iconColor) = getStatusIconAndColor(entry.status)

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = iconColor
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = entry.mode.replace("_", " "),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = formatTimestamp(entry.timestampMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                when (entry.status) {
                    SyncHistoryStatus.SUCCESS, SyncHistoryStatus.PARTIAL_SUCCESS -> {
                        Text(
                            text = buildString {
                                if (entry.filesUploaded > 0) append("${entry.filesUploaded} uploaded")
                                if (entry.filesDownloaded > 0) {
                                    if (isNotEmpty()) append(", ")
                                    append("${entry.filesDownloaded} downloaded")
                                }
                                if (entry.filesFailed > 0) {
                                    if (isNotEmpty()) append(", ")
                                    append("${entry.filesFailed} failed")
                                }
                                if (isEmpty()) append("No changes")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (entry.bytesTransferred > 0) {
                            Text(
                                text = "${formatBytes(entry.bytesTransferred)} transferred",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        entry.errorMessage?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = iconColor,
                                maxLines = 2
                            )
                        }
                    }
                }

                if (entry.durationMs > 0) {
                    Text(
                        text = "Duration: ${formatDuration(entry.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Sync History",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Sync operations will appear here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun getStatusIconAndColor(status: SyncHistoryStatus): Pair<ImageVector, Color> {
    return when (status) {
        SyncHistoryStatus.SUCCESS -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
        SyncHistoryStatus.PARTIAL_SUCCESS -> Icons.Default.Warning to Color(0xFFFF9800)
        SyncHistoryStatus.FAILED -> Icons.Default.Error to Color(0xFFF44336)
        SyncHistoryStatus.CANCELLED -> Icons.Default.Clear to Color(0xFF9E9E9E)
        SyncHistoryStatus.NOT_SIGNED_IN -> Icons.Default.Error to Color(0xFFF44336)
        SyncHistoryStatus.NETWORK_UNAVAILABLE -> Icons.Default.CloudOff to Color(0xFF9E9E9E)
    }
}

private fun formatTimestamp(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestampMs

    return when {
        diff < 60_000 -> "Just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> {
            val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            sdf.format(Date(timestampMs))
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    return when {
        durationMs < 1000 -> "${durationMs}ms"
        durationMs < 60_000 -> "${durationMs / 1000}s"
        else -> "${durationMs / 60_000}m ${(durationMs % 60_000) / 1000}s"
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
