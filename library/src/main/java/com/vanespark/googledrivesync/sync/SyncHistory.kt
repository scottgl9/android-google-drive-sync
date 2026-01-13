package com.vanespark.googledrivesync.sync

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * Represents a single sync history entry
 */
@Serializable
data class SyncHistoryEntry(
    val id: String,
    val timestampMs: Long,
    val mode: String,
    val status: SyncHistoryStatus,
    val filesUploaded: Int = 0,
    val filesDownloaded: Int = 0,
    val filesSkipped: Int = 0,
    val filesFailed: Int = 0,
    val bytesTransferred: Long = 0,
    val durationMs: Long = 0,
    val errorMessage: String? = null
) {
    val timestamp: Instant get() = Instant.ofEpochMilli(timestampMs)
    val duration: Duration get() = kotlin.time.Duration.parse("${durationMs}ms")
}

/**
 * Status of a sync operation
 */
@Serializable
enum class SyncHistoryStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED,
    PAUSED,
    NOT_SIGNED_IN,
    NETWORK_UNAVAILABLE
}

/**
 * Manages sync history persistence and retrieval
 */
@Singleton
class SyncHistoryManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    private val json = Json { ignoreUnknownKeys = true }

    private val _history = MutableStateFlow<List<SyncHistoryEntry>>(emptyList())
    val history: StateFlow<List<SyncHistoryEntry>> = _history.asStateFlow()

    init {
        loadHistory()
    }

    /**
     * Record a sync result in history
     */
    fun recordSync(result: SyncResult, mode: SyncMode) {
        val entry = when (result) {
            is SyncResult.Success -> SyncHistoryEntry(
                id = generateId(),
                timestampMs = System.currentTimeMillis(),
                mode = mode.name,
                status = SyncHistoryStatus.SUCCESS,
                filesUploaded = result.filesUploaded,
                filesDownloaded = result.filesDownloaded,
                filesSkipped = result.filesSkipped,
                bytesTransferred = result.bytesTransferred,
                durationMs = result.duration.inWholeMilliseconds
            )
            is SyncResult.PartialSuccess -> SyncHistoryEntry(
                id = generateId(),
                timestampMs = System.currentTimeMillis(),
                mode = mode.name,
                status = SyncHistoryStatus.PARTIAL_SUCCESS,
                filesUploaded = result.filesSucceeded,
                filesFailed = result.filesFailed,
                durationMs = result.duration.inWholeMilliseconds,
                errorMessage = result.errors.firstOrNull()?.message
            )
            is SyncResult.Error -> SyncHistoryEntry(
                id = generateId(),
                timestampMs = System.currentTimeMillis(),
                mode = mode.name,
                status = SyncHistoryStatus.FAILED,
                errorMessage = result.message
            )
            SyncResult.Cancelled -> SyncHistoryEntry(
                id = generateId(),
                timestampMs = System.currentTimeMillis(),
                mode = mode.name,
                status = SyncHistoryStatus.CANCELLED
            )
            SyncResult.NotSignedIn -> SyncHistoryEntry(
                id = generateId(),
                timestampMs = System.currentTimeMillis(),
                mode = mode.name,
                status = SyncHistoryStatus.NOT_SIGNED_IN,
                errorMessage = "User not signed in"
            )
            SyncResult.NetworkUnavailable -> SyncHistoryEntry(
                id = generateId(),
                timestampMs = System.currentTimeMillis(),
                mode = mode.name,
                status = SyncHistoryStatus.NETWORK_UNAVAILABLE,
                errorMessage = "Network unavailable"
            )
            SyncResult.Paused -> SyncHistoryEntry(
                id = generateId(),
                timestampMs = System.currentTimeMillis(),
                mode = mode.name,
                status = SyncHistoryStatus.PAUSED
            )
        }

        addEntry(entry)
    }

    /**
     * Get the most recent sync entries
     */
    fun getRecentHistory(limit: Int = 20): List<SyncHistoryEntry> {
        return _history.value.take(limit)
    }

    /**
     * Get history entries for a specific date range
     */
    fun getHistoryInRange(startMs: Long, endMs: Long): List<SyncHistoryEntry> {
        return _history.value.filter { it.timestampMs in startMs..endMs }
    }

    /**
     * Get summary statistics
     */
    fun getStatistics(): SyncStatistics {
        val entries = _history.value
        val successCount = entries.count { it.status == SyncHistoryStatus.SUCCESS }
        val failureCount = entries.count {
            it.status == SyncHistoryStatus.FAILED ||
            it.status == SyncHistoryStatus.PARTIAL_SUCCESS
        }
        val totalBytesTransferred = entries.sumOf { it.bytesTransferred }
        val totalFilesUploaded = entries.sumOf { it.filesUploaded }
        val totalFilesDownloaded = entries.sumOf { it.filesDownloaded }
        val lastSync = entries.maxByOrNull { it.timestampMs }

        return SyncStatistics(
            totalSyncs = entries.size,
            successfulSyncs = successCount,
            failedSyncs = failureCount,
            totalBytesTransferred = totalBytesTransferred,
            totalFilesUploaded = totalFilesUploaded,
            totalFilesDownloaded = totalFilesDownloaded,
            lastSyncTime = lastSync?.timestampMs
        )
    }

    /**
     * Clear all history
     */
    fun clearHistory() {
        _history.value = emptyList()
        saveHistory()
    }

    /**
     * Clear history older than specified time
     */
    fun clearHistoryOlderThan(cutoffMs: Long) {
        _history.value = _history.value.filter { it.timestampMs >= cutoffMs }
        saveHistory()
    }

    private fun addEntry(entry: SyncHistoryEntry) {
        val current = _history.value.toMutableList()
        current.add(0, entry) // Add to front

        // Keep only last MAX_ENTRIES
        if (current.size > MAX_ENTRIES) {
            _history.value = current.take(MAX_ENTRIES)
        } else {
            _history.value = current
        }

        saveHistory()
    }

    private fun loadHistory() {
        try {
            val jsonString = prefs.getString(KEY_HISTORY, null) ?: return
            val entries = json.decodeFromString<List<SyncHistoryEntry>>(jsonString)
            _history.value = entries
        } catch (e: Exception) {
            // Clear corrupted data
            _history.value = emptyList()
        }
    }

    private fun saveHistory() {
        try {
            val jsonString = json.encodeToString(_history.value)
            prefs.edit().putString(KEY_HISTORY, jsonString).apply()
        } catch (e: Exception) {
            // Ignore save errors
        }
    }

    private fun generateId(): String {
        return "${System.currentTimeMillis()}_${(0..9999).random()}"
    }

    companion object {
        private const val PREFS_NAME = "sync_history"
        private const val KEY_HISTORY = "history_entries"
        private const val MAX_ENTRIES = 100
    }
}

/**
 * Aggregated sync statistics
 */
data class SyncStatistics(
    val totalSyncs: Int,
    val successfulSyncs: Int,
    val failedSyncs: Int,
    val totalBytesTransferred: Long,
    val totalFilesUploaded: Int,
    val totalFilesDownloaded: Int,
    val lastSyncTime: Long?
) {
    val successRate: Float
        get() = if (totalSyncs > 0) successfulSyncs.toFloat() / totalSyncs else 0f
}
