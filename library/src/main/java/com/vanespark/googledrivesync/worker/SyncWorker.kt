package com.vanespark.googledrivesync.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.vanespark.googledrivesync.sync.SyncManager
import com.vanespark.googledrivesync.sync.SyncOptions
import com.vanespark.googledrivesync.sync.SyncResult
import com.vanespark.googledrivesync.util.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * WorkManager worker for background sync operations.
 *
 * Supports:
 * - Periodic background sync
 * - One-time sync requests
 * - Progress reporting
 * - Retry on failure
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncManager: SyncManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(Constants.TAG, "SyncWorker starting")

        // Get sync mode from input data
        val syncMode = inputData.getString(KEY_SYNC_MODE) ?: SYNC_MODE_BIDIRECTIONAL

        val options = when (syncMode) {
            SYNC_MODE_UPLOAD_ONLY -> SyncOptions.UPLOAD_ONLY
            SYNC_MODE_DOWNLOAD_ONLY -> SyncOptions.DOWNLOAD_ONLY
            SYNC_MODE_MIRROR_TO_CLOUD -> SyncOptions.MIRROR_TO_CLOUD
            SYNC_MODE_MIRROR_FROM_CLOUD -> SyncOptions.MIRROR_FROM_CLOUD
            else -> SyncOptions.DEFAULT
        }

        return try {
            val result = syncManager.sync(options)

            when (result) {
                is SyncResult.Success -> {
                    Log.d(Constants.TAG, "SyncWorker completed successfully: " +
                        "uploaded=${result.filesUploaded}, downloaded=${result.filesDownloaded}")

                    val outputData = Data.Builder()
                        .putInt(KEY_FILES_UPLOADED, result.filesUploaded)
                        .putInt(KEY_FILES_DOWNLOADED, result.filesDownloaded)
                        .putInt(KEY_FILES_SKIPPED, result.filesSkipped)
                        .putLong(KEY_BYTES_TRANSFERRED, result.bytesTransferred)
                        .putLong(KEY_DURATION_MS, result.duration.inWholeMilliseconds)
                        .build()

                    Result.success(outputData)
                }

                is SyncResult.PartialSuccess -> {
                    Log.w(Constants.TAG, "SyncWorker completed with errors: " +
                        "succeeded=${result.filesSucceeded}, failed=${result.filesFailed}")

                    val outputData = Data.Builder()
                        .putInt(KEY_FILES_SUCCEEDED, result.filesSucceeded)
                        .putInt(KEY_FILES_FAILED, result.filesFailed)
                        .putLong(KEY_DURATION_MS, result.duration.inWholeMilliseconds)
                        .build()

                    // Consider partial success as success
                    Result.success(outputData)
                }

                is SyncResult.Error -> {
                    Log.e(Constants.TAG, "SyncWorker failed: ${result.message}")

                    // Retry if we have attempts remaining
                    if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Result.retry()
                    } else {
                        Result.failure(
                            Data.Builder()
                                .putString(KEY_ERROR_MESSAGE, result.message)
                                .build()
                        )
                    }
                }

                SyncResult.NotSignedIn -> {
                    Log.w(Constants.TAG, "SyncWorker: not signed in")
                    Result.failure(
                        Data.Builder()
                            .putString(KEY_ERROR_MESSAGE, "Not signed in")
                            .build()
                    )
                }

                SyncResult.NetworkUnavailable -> {
                    Log.w(Constants.TAG, "SyncWorker: network unavailable")
                    // Retry when network becomes available
                    Result.retry()
                }

                SyncResult.Cancelled -> {
                    Log.d(Constants.TAG, "SyncWorker: cancelled")
                    Result.failure(
                        Data.Builder()
                            .putString(KEY_ERROR_MESSAGE, "Cancelled")
                            .build()
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(Constants.TAG, "SyncWorker exception", e)

            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(
                    Data.Builder()
                        .putString(KEY_ERROR_MESSAGE, e.message ?: "Unknown error")
                        .build()
                )
            }
        }
    }

    companion object {
        const val WORK_NAME = "google_drive_sync"
        const val WORK_NAME_PERIODIC = "google_drive_sync_periodic"
        const val WORK_NAME_ONE_TIME = "google_drive_sync_one_time"

        // Input keys
        const val KEY_SYNC_MODE = "sync_mode"

        // Sync modes
        const val SYNC_MODE_BIDIRECTIONAL = "bidirectional"
        const val SYNC_MODE_UPLOAD_ONLY = "upload_only"
        const val SYNC_MODE_DOWNLOAD_ONLY = "download_only"
        const val SYNC_MODE_MIRROR_TO_CLOUD = "mirror_to_cloud"
        const val SYNC_MODE_MIRROR_FROM_CLOUD = "mirror_from_cloud"

        // Output keys
        const val KEY_FILES_UPLOADED = "files_uploaded"
        const val KEY_FILES_DOWNLOADED = "files_downloaded"
        const val KEY_FILES_SKIPPED = "files_skipped"
        const val KEY_FILES_SUCCEEDED = "files_succeeded"
        const val KEY_FILES_FAILED = "files_failed"
        const val KEY_BYTES_TRANSFERRED = "bytes_transferred"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_ERROR_MESSAGE = "error_message"

        private const val MAX_RETRY_ATTEMPTS = 3
    }
}
