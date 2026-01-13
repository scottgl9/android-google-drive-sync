package com.vanespark.googledrivesync.resilience

import android.content.Context
import android.util.Log
import com.vanespark.googledrivesync.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Progress state for a sync operation
 */
data class SyncProgress(
    /**
     * Current phase of the sync
     */
    val phase: SyncPhase = SyncPhase.IDLE,

    /**
     * Current file being processed
     */
    val currentFile: String? = null,

    /**
     * Total number of files to process
     */
    val totalFiles: Int = 0,

    /**
     * Number of files processed so far
     */
    val processedFiles: Int = 0,

    /**
     * Number of files uploaded
     */
    val uploadedFiles: Int = 0,

    /**
     * Number of files downloaded
     */
    val downloadedFiles: Int = 0,

    /**
     * Number of files skipped (unchanged)
     */
    val skippedFiles: Int = 0,

    /**
     * Number of files that failed
     */
    val failedFiles: Int = 0,

    /**
     * Total bytes transferred
     */
    val bytesTransferred: Long = 0,

    /**
     * Total bytes to transfer
     */
    val totalBytes: Long = 0,

    /**
     * Error message if any
     */
    val error: String? = null,

    /**
     * Sync start time
     */
    val startTime: Long = 0,

    /**
     * Last progress update time
     */
    val lastUpdateTime: Long = 0
) {
    /**
     * Progress percentage (0-100)
     */
    val percentage: Int
        get() = if (totalFiles > 0) (processedFiles * 100 / totalFiles) else 0

    /**
     * Whether sync is currently active
     */
    val isActive: Boolean
        get() = phase != SyncPhase.IDLE && phase != SyncPhase.COMPLETED && phase != SyncPhase.FAILED

    /**
     * Duration in milliseconds
     */
    val durationMs: Long
        get() = if (startTime > 0) lastUpdateTime - startTime else 0
}

/**
 * Phases of a sync operation
 */
enum class SyncPhase {
    IDLE,
    AUTHENTICATING,
    PREPARING,
    SCANNING_LOCAL,
    SCANNING_REMOTE,
    COMPARING,
    UPLOADING,
    DOWNLOADING,
    CLEANING_UP,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Manages sync progress tracking and persistence for resumable operations.
 */
@Singleton
class SyncProgressManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy {
        context.getSharedPreferences("google_drive_sync_progress", Context.MODE_PRIVATE)
    }

    private val _progress = MutableStateFlow(SyncProgress())

    /**
     * Current sync progress as observable flow
     */
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    /**
     * Start a new sync operation
     */
    fun startSync(totalFiles: Int = 0, totalBytes: Long = 0) {
        val now = System.currentTimeMillis()
        _progress.value = SyncProgress(
            phase = SyncPhase.PREPARING,
            totalFiles = totalFiles,
            totalBytes = totalBytes,
            startTime = now,
            lastUpdateTime = now
        )
        saveProgress()
        Log.d(Constants.TAG, "Sync started: $totalFiles files, $totalBytes bytes")
    }

    /**
     * Update the current phase
     */
    fun updatePhase(phase: SyncPhase) {
        _progress.value = _progress.value.copy(
            phase = phase,
            lastUpdateTime = System.currentTimeMillis()
        )
        saveProgress()
    }

    /**
     * Update progress for a file being processed
     */
    fun updateFileProgress(
        currentFile: String,
        processedFiles: Int? = null,
        uploadedFiles: Int? = null,
        downloadedFiles: Int? = null,
        skippedFiles: Int? = null,
        failedFiles: Int? = null,
        bytesTransferred: Long? = null
    ) {
        val current = _progress.value
        _progress.value = current.copy(
            currentFile = currentFile,
            processedFiles = processedFiles ?: current.processedFiles,
            uploadedFiles = uploadedFiles ?: current.uploadedFiles,
            downloadedFiles = downloadedFiles ?: current.downloadedFiles,
            skippedFiles = skippedFiles ?: current.skippedFiles,
            failedFiles = failedFiles ?: current.failedFiles,
            bytesTransferred = bytesTransferred ?: current.bytesTransferred,
            lastUpdateTime = System.currentTimeMillis()
        )
    }

    /**
     * Mark a file as uploaded
     */
    fun fileUploaded(fileName: String, bytes: Long) {
        val current = _progress.value
        _progress.value = current.copy(
            currentFile = fileName,
            processedFiles = current.processedFiles + 1,
            uploadedFiles = current.uploadedFiles + 1,
            bytesTransferred = current.bytesTransferred + bytes,
            lastUpdateTime = System.currentTimeMillis()
        )
        addSyncedFile(fileName)
    }

    /**
     * Mark a file as downloaded
     */
    fun fileDownloaded(fileName: String, bytes: Long) {
        val current = _progress.value
        _progress.value = current.copy(
            currentFile = fileName,
            processedFiles = current.processedFiles + 1,
            downloadedFiles = current.downloadedFiles + 1,
            bytesTransferred = current.bytesTransferred + bytes,
            lastUpdateTime = System.currentTimeMillis()
        )
        addSyncedFile(fileName)
    }

    /**
     * Mark a file as skipped
     */
    fun fileSkipped(fileName: String) {
        val current = _progress.value
        _progress.value = current.copy(
            currentFile = fileName,
            processedFiles = current.processedFiles + 1,
            skippedFiles = current.skippedFiles + 1,
            lastUpdateTime = System.currentTimeMillis()
        )
        addSyncedFile(fileName)
    }

    /**
     * Mark a file as failed
     */
    fun fileFailed(fileName: String, error: String? = null) {
        val current = _progress.value
        _progress.value = current.copy(
            currentFile = fileName,
            processedFiles = current.processedFiles + 1,
            failedFiles = current.failedFiles + 1,
            error = error,
            lastUpdateTime = System.currentTimeMillis()
        )
    }

    /**
     * Complete the sync successfully
     */
    fun completeSync() {
        _progress.value = _progress.value.copy(
            phase = SyncPhase.COMPLETED,
            currentFile = null,
            lastUpdateTime = System.currentTimeMillis()
        )
        clearPersistedProgress()
        Log.d(Constants.TAG, "Sync completed: ${_progress.value}")
    }

    /**
     * Mark sync as failed
     */
    fun failSync(error: String) {
        _progress.value = _progress.value.copy(
            phase = SyncPhase.FAILED,
            error = error,
            lastUpdateTime = System.currentTimeMillis()
        )
        clearPersistedProgress()
        Log.e(Constants.TAG, "Sync failed: $error")
    }

    /**
     * Cancel the sync
     */
    fun cancelSync() {
        _progress.value = _progress.value.copy(
            phase = SyncPhase.CANCELLED,
            lastUpdateTime = System.currentTimeMillis()
        )
        // Keep persisted progress for potential resume
        Log.d(Constants.TAG, "Sync cancelled")
    }

    /**
     * Reset progress to idle state
     */
    fun reset() {
        _progress.value = SyncProgress()
    }

    // ========== Progress Persistence ==========

    private fun saveProgress() {
        prefs.edit().apply {
            putBoolean(KEY_IN_PROGRESS, true)
            putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            apply()
        }
    }

    private fun addSyncedFile(fileName: String) {
        val syncedFiles = getSyncedFiles().toMutableSet()
        syncedFiles.add(fileName)
        prefs.edit().putStringSet(KEY_SYNCED_FILES, syncedFiles).apply()
    }

    /**
     * Get files that have already been synced (for resume)
     */
    fun getSyncedFiles(): Set<String> {
        return prefs.getStringSet(KEY_SYNCED_FILES, emptySet()) ?: emptySet()
    }

    /**
     * Check if there's a sync in progress that can be resumed
     */
    fun hasResumableProgress(): Boolean {
        if (!prefs.getBoolean(KEY_IN_PROGRESS, false)) return false

        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0)
        val age = System.currentTimeMillis() - timestamp

        // Consider progress stale after timeout
        if (age > Constants.SYNC_RESUME_TIMEOUT_MS) {
            clearPersistedProgress()
            return false
        }

        val syncedFiles = getSyncedFiles()
        return syncedFiles.isNotEmpty()
    }

    /**
     * Clear persisted progress
     */
    fun clearPersistedProgress() {
        prefs.edit().clear().apply()
    }

    /**
     * Save the last successful database checksum
     */
    fun saveLastDatabaseChecksum(checksum: String) {
        prefs.edit().putString(KEY_LAST_DB_CHECKSUM, checksum).apply()
    }

    /**
     * Get the last successful database checksum
     */
    fun getLastDatabaseChecksum(): String? {
        return prefs.getString(KEY_LAST_DB_CHECKSUM, null)
    }

    /**
     * Save the last sync timestamp
     */
    fun saveLastSyncTimestamp(timestamp: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, timestamp).apply()
    }

    /**
     * Get the last sync timestamp
     */
    fun getLastSyncTimestamp(): Long? {
        val timestamp = prefs.getLong(KEY_LAST_SYNC_TIME, -1)
        return if (timestamp == -1L) null else timestamp
    }

    companion object {
        private const val KEY_IN_PROGRESS = "sync_in_progress"
        private const val KEY_TIMESTAMP = "sync_timestamp"
        private const val KEY_SYNCED_FILES = "synced_files"
        private const val KEY_LAST_DB_CHECKSUM = "last_db_checksum"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    }
}
