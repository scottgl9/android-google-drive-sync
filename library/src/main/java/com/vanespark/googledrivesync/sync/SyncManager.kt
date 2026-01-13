package com.vanespark.googledrivesync.sync

import android.util.Log
import com.vanespark.googledrivesync.auth.AuthState
import com.vanespark.googledrivesync.auth.GoogleAuthManager
import com.vanespark.googledrivesync.local.FileFilter
import com.vanespark.googledrivesync.resilience.NetworkMonitor
import com.vanespark.googledrivesync.resilience.NetworkPolicy
import com.vanespark.googledrivesync.resilience.RetryPolicy
import com.vanespark.googledrivesync.resilience.SyncPhase
import com.vanespark.googledrivesync.resilience.SyncProgress
import com.vanespark.googledrivesync.resilience.SyncProgressManager
import com.vanespark.googledrivesync.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configuration for the sync manager
 */
data class SyncConfiguration(
    /**
     * Root folder name on Google Drive
     */
    val rootFolderName: String = Constants.DEFAULT_ROOT_FOLDER_NAME,

    /**
     * Local directory to sync
     */
    val syncDirectory: File,

    /**
     * File filter for sync
     */
    val fileFilter: FileFilter = FileFilter.defaultSyncFilter(),

    /**
     * Network policy for sync operations
     */
    val networkPolicy: NetworkPolicy = NetworkPolicy.UNMETERED_ONLY,

    /**
     * Retry policy for operations
     */
    val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,

    /**
     * Default sync options
     */
    val defaultOptions: SyncOptions = SyncOptions.DEFAULT
)

/**
 * Main orchestrator for sync operations.
 *
 * Coordinates:
 * - Authentication state
 * - Network availability
 * - Sync engine operations
 * - Progress tracking
 * - Cancellation
 */
@Singleton
class SyncManager @Inject constructor(
    private val authManager: GoogleAuthManager,
    private val syncEngine: SyncEngine,
    private val networkMonitor: NetworkMonitor,
    private val progressManager: SyncProgressManager
) {
    private var configuration: SyncConfiguration? = null
    private var currentSyncJob: Job? = null

    private val _isSyncing = MutableStateFlow(false)
    /**
     * Whether a sync is currently in progress
     */
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    /**
     * Current sync progress
     */
    val progress: StateFlow<SyncProgress> = progressManager.progress

    /**
     * Configure the sync manager
     *
     * @param configuration Sync configuration
     */
    fun configure(configuration: SyncConfiguration) {
        this.configuration = configuration
        Log.d(Constants.TAG, "SyncManager configured: folder=${configuration.rootFolderName}, " +
            "dir=${configuration.syncDirectory.absolutePath}")
    }

    /**
     * Perform a full sync operation.
     *
     * @param options Sync options (uses default if not specified)
     * @return Sync result
     */
    suspend fun sync(options: SyncOptions? = null): SyncResult {
        val config = configuration ?: run {
            Log.e(Constants.TAG, "SyncManager not configured")
            return SyncResult.Error("SyncManager not configured. Call configure() first.")
        }

        // Check if already syncing
        if (_isSyncing.value) {
            Log.w(Constants.TAG, "Sync already in progress")
            return SyncResult.Error("Sync already in progress")
        }

        // Check authentication
        if (!authManager.isSignedIn()) {
            Log.w(Constants.TAG, "Not signed in")
            return SyncResult.NotSignedIn
        }

        // Check network
        if (!networkMonitor.meetsPolicy(config.networkPolicy)) {
            Log.w(Constants.TAG, "Network does not meet policy: ${config.networkPolicy}")
            return SyncResult.NetworkUnavailable
        }

        val syncOptions = options ?: config.defaultOptions

        _isSyncing.value = true
        progressManager.updatePhase(SyncPhase.PREPARING)

        try {
            return executeSyncInternal(config, syncOptions)
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Perform upload-only sync
     */
    suspend fun uploadOnly(): SyncResult = sync(SyncOptions.UPLOAD_ONLY)

    /**
     * Perform download-only sync
     */
    suspend fun downloadOnly(): SyncResult = sync(SyncOptions.DOWNLOAD_ONLY)

    /**
     * Mirror local to cloud (make cloud match local exactly)
     */
    suspend fun mirrorToCloud(): SyncResult = sync(SyncOptions.MIRROR_TO_CLOUD)

    /**
     * Mirror cloud to local (make local match cloud exactly)
     */
    suspend fun mirrorFromCloud(): SyncResult = sync(SyncOptions.MIRROR_FROM_CLOUD)

    /**
     * Cancel the current sync operation
     */
    fun cancel() {
        currentSyncJob?.cancel()
        progressManager.cancelSync()
        _isSyncing.value = false
        Log.d(Constants.TAG, "Sync cancelled")
    }

    /**
     * Internal sync execution
     */
    private suspend fun executeSyncInternal(
        config: SyncConfiguration,
        options: SyncOptions
    ): SyncResult = coroutineScope {
        try {
            currentSyncJob = launch {
                // Phase 1: Scan local files
                progressManager.updatePhase(SyncPhase.SCANNING_LOCAL)
                Log.d(Constants.TAG, "Scanning local files...")
                val localManifest = syncEngine.buildLocalManifest(
                    syncDirectory = config.syncDirectory,
                    filter = config.fileFilter
                )

                // Phase 2: Scan remote files
                progressManager.updatePhase(SyncPhase.SCANNING_REMOTE)
                Log.d(Constants.TAG, "Scanning remote files...")
                val remoteManifest = syncEngine.buildRemoteManifest(config.rootFolderName)

                // Phase 3: Compare and determine actions
                progressManager.updatePhase(SyncPhase.COMPARING)
                Log.d(Constants.TAG, "Comparing files...")
                val syncItems = syncEngine.compareManifests(localManifest, remoteManifest, options)

                // Filter by subdirectory if specified
                val filteredItems = if (options.subdirectory != null) {
                    syncItems.filter { it.relativePath.startsWith(options.subdirectory) }
                } else {
                    syncItems
                }

                // Limit files if specified
                val limitedItems = if (options.maxFiles > 0) {
                    filteredItems.take(options.maxFiles)
                } else {
                    filteredItems
                }

                // Phase 4: Execute sync
                val uploadPhase = when (options.mode) {
                    SyncMode.UPLOAD_ONLY, SyncMode.MIRROR_TO_CLOUD -> SyncPhase.UPLOADING
                    SyncMode.DOWNLOAD_ONLY, SyncMode.MIRROR_FROM_CLOUD -> SyncPhase.DOWNLOADING
                    SyncMode.BIDIRECTIONAL -> SyncPhase.UPLOADING // Will switch as needed
                }
                progressManager.updatePhase(uploadPhase)
                Log.d(Constants.TAG, "Executing sync plan...")

                syncEngine.executeSyncPlan(
                    syncItems = limitedItems,
                    syncDirectory = config.syncDirectory,
                    rootFolderName = config.rootFolderName,
                    options = options,
                    retryPolicy = config.retryPolicy
                )
            }

            // Wait for sync to complete
            currentSyncJob?.join()

            // Return the final result based on progress
            val finalProgress = progressManager.progress.value
            when (finalProgress.phase) {
                SyncPhase.COMPLETED -> {
                    SyncResult.Success(
                        filesUploaded = finalProgress.uploadedFiles,
                        filesDownloaded = finalProgress.downloadedFiles,
                        filesDeleted = 0,
                        filesSkipped = finalProgress.skippedFiles,
                        conflicts = emptyList(),
                        duration = finalProgress.durationMs.kotlin.time.Duration.Companion.milliseconds,
                        bytesTransferred = finalProgress.bytesTransferred
                    )
                }
                SyncPhase.CANCELLED -> SyncResult.Cancelled
                SyncPhase.FAILED -> SyncResult.Error(finalProgress.error ?: "Sync failed")
                else -> SyncResult.Error("Unexpected sync state: ${finalProgress.phase}")
            }
        } catch (e: CancellationException) {
            SyncResult.Cancelled
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Sync failed", e)
            SyncResult.Error(e.message ?: "Sync failed", e)
        }
    }

    /**
     * Get the time of the last successful sync
     */
    fun getLastSyncTime(): Long? = progressManager.getLastSyncTimestamp()

    /**
     * Check if there is a resumable sync in progress
     */
    fun hasResumableSync(): Boolean = progressManager.hasResumableProgress()

    /**
     * Clear any resumable sync state
     */
    fun clearResumableSync() = progressManager.clearPersistedProgress()
}
