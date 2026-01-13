package com.vanespark.googledrivesync.sync

import android.content.Context
import android.util.Log
import com.vanespark.googledrivesync.drive.DriveOperationResult
import com.vanespark.googledrivesync.drive.DriveService
import com.vanespark.googledrivesync.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sync metadata stored on Google Drive for validation and multi-device safety.
 */
@Serializable
data class SyncMetadataInfo(
    /**
     * Unique instance ID for this sync source.
     * Used to prevent multiple devices from overwriting each other's data.
     */
    val instanceId: String,

    /**
     * Checksum of the last successfully synced database/content.
     * Used to detect if remote data has changed.
     */
    val lastChecksum: String? = null,

    /**
     * Timestamp of the last successful sync.
     */
    val lastSyncTimestamp: Long = 0,

    /**
     * App version that created this sync.
     */
    val appVersion: String? = null,

    /**
     * Human-readable device name (optional).
     */
    val deviceName: String? = null,

    /**
     * Schema version for future compatibility.
     */
    val schemaVersion: Int = 1
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * Result of sync metadata validation.
 */
sealed class SyncMetadataValidation {
    /**
     * Metadata is valid and matches current instance.
     */
    object Valid : SyncMetadataValidation()

    /**
     * No metadata exists (first sync or legacy data).
     */
    object NoMetadata : SyncMetadataValidation()

    /**
     * Instance ID mismatch - different device synced last.
     */
    data class InstanceMismatch(
        val localInstanceId: String,
        val remoteInstanceId: String,
        val remoteDeviceName: String?
    ) : SyncMetadataValidation()

    /**
     * Failed to read or parse metadata.
     */
    data class Error(val message: String) : SyncMetadataValidation()
}

/**
 * Manages sync metadata for multi-device safety and change detection.
 *
 * Features:
 * - Instance ID generation and validation
 * - Checksum tracking for change detection
 * - Device identification for multi-device scenarios
 */
@Singleton
class SyncMetadataManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy {
        context.getSharedPreferences("google_drive_sync_metadata", Context.MODE_PRIVATE)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * Get or create a unique instance ID for this sync source.
     * The instance ID persists across app restarts.
     */
    fun getOrCreateInstanceId(): String {
        var instanceId = prefs.getString(KEY_INSTANCE_ID, null)
        if (instanceId == null) {
            instanceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTANCE_ID, instanceId).apply()
            Log.d(Constants.TAG, "Created new sync instance ID: $instanceId")
        }
        return instanceId
    }

    /**
     * Get the device name for identification purposes.
     */
    fun getDeviceName(): String {
        return android.os.Build.MODEL ?: "Unknown Device"
    }

    /**
     * Create sync metadata for upload.
     */
    fun createMetadata(
        checksum: String? = null,
        appVersion: String? = null
    ): SyncMetadataInfo {
        return SyncMetadataInfo(
            instanceId = getOrCreateInstanceId(),
            lastChecksum = checksum,
            lastSyncTimestamp = System.currentTimeMillis(),
            appVersion = appVersion,
            deviceName = getDeviceName(),
            schemaVersion = SyncMetadataInfo.CURRENT_SCHEMA_VERSION
        )
    }

    /**
     * Serialize metadata to JSON string.
     */
    fun serializeMetadata(metadata: SyncMetadataInfo): String {
        return json.encodeToString(metadata)
    }

    /**
     * Parse metadata from JSON string.
     */
    fun parseMetadata(jsonString: String): SyncMetadataInfo? {
        return try {
            json.decodeFromString<SyncMetadataInfo>(jsonString)
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to parse sync metadata", e)
            null
        }
    }

    /**
     * Validate remote metadata against local instance.
     *
     * @param remoteMetadata The metadata from Google Drive
     * @param allowMismatch If true, instance mismatch is treated as valid (for initial sync)
     * @return Validation result
     */
    fun validateMetadata(
        remoteMetadata: SyncMetadataInfo?,
        allowMismatch: Boolean = false
    ): SyncMetadataValidation {
        if (remoteMetadata == null) {
            return SyncMetadataValidation.NoMetadata
        }

        val localInstanceId = getOrCreateInstanceId()

        if (remoteMetadata.instanceId != localInstanceId) {
            if (allowMismatch) {
                Log.w(Constants.TAG, "Instance ID mismatch allowed for initial sync")
                return SyncMetadataValidation.Valid
            }

            Log.w(
                Constants.TAG,
                "Instance ID mismatch: local=$localInstanceId, remote=${remoteMetadata.instanceId}"
            )
            return SyncMetadataValidation.InstanceMismatch(
                localInstanceId = localInstanceId,
                remoteInstanceId = remoteMetadata.instanceId,
                remoteDeviceName = remoteMetadata.deviceName
            )
        }

        return SyncMetadataValidation.Valid
    }

    /**
     * Save the last successful sync checksum locally.
     */
    fun saveLastChecksum(checksum: String) {
        prefs.edit().putString(KEY_LAST_CHECKSUM, checksum).apply()
    }

    /**
     * Get the last successful sync checksum.
     */
    fun getLastChecksum(): String? {
        return prefs.getString(KEY_LAST_CHECKSUM, null)
    }

    /**
     * Check if content has changed since last sync.
     */
    fun hasContentChanged(currentChecksum: String): Boolean {
        val lastChecksum = getLastChecksum()
        return lastChecksum == null || !lastChecksum.equals(currentChecksum, ignoreCase = true)
    }

    /**
     * Clear all stored metadata (for sign-out or reset).
     */
    fun clearMetadata() {
        prefs.edit().clear().apply()
        Log.d(Constants.TAG, "Cleared sync metadata")
    }

    /**
     * Force a new instance ID (for taking ownership of remote data).
     */
    fun resetInstanceId(): String {
        val newInstanceId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTANCE_ID, newInstanceId).apply()
        Log.d(Constants.TAG, "Reset sync instance ID to: $newInstanceId")
        return newInstanceId
    }

    companion object {
        private const val KEY_INSTANCE_ID = "sync_instance_id"
        private const val KEY_LAST_CHECKSUM = "last_sync_checksum"
        const val METADATA_FILENAME = "sync_metadata.json"
    }
}

/**
 * Extension functions for DriveService to handle sync metadata.
 */
suspend fun DriveService.uploadSyncMetadata(
    metadata: SyncMetadataInfo,
    metadataManager: SyncMetadataManager,
    rootFolderName: String
): DriveOperationResult<Unit> = withContext(Dispatchers.IO) {
    try {
        val jsonContent = metadataManager.serializeMetadata(metadata)
        val tempFile = File.createTempFile("sync_metadata", ".json")

        try {
            tempFile.writeText(jsonContent)

            val result = uploadFile(
                localFile = tempFile,
                remotePath = SyncMetadataManager.METADATA_FILENAME,
                rootFolderName = rootFolderName
            )

            when (result) {
                is DriveOperationResult.Success -> {
                    Log.d(Constants.TAG, "Uploaded sync metadata successfully")
                    DriveOperationResult.Success(Unit)
                }
                else -> result as DriveOperationResult<Unit>
            }
        } finally {
            tempFile.delete()
        }
    } catch (e: Exception) {
        Log.e(Constants.TAG, "Failed to upload sync metadata", e)
        DriveOperationResult.Error(e.message ?: "Failed to upload metadata", e)
    }
}

suspend fun DriveService.downloadSyncMetadata(
    metadataManager: SyncMetadataManager,
    rootFolderName: String
): DriveOperationResult<SyncMetadataInfo> = withContext(Dispatchers.IO) {
    try {
        val result = findFile(
            name = SyncMetadataManager.METADATA_FILENAME,
            folderPath = "",
            rootFolderName = rootFolderName
        )

        when (result) {
            is DriveOperationResult.Success -> {
                val tempFile = File.createTempFile("sync_metadata_download", ".json")
                try {
                    val downloadResult = downloadFile(result.data.id, tempFile)
                    when (downloadResult) {
                        is DriveOperationResult.Success -> {
                            val jsonContent = tempFile.readText()
                            val metadata = metadataManager.parseMetadata(jsonContent)
                            if (metadata != null) {
                                DriveOperationResult.Success(metadata)
                            } else {
                                DriveOperationResult.Error("Failed to parse sync metadata")
                            }
                        }
                        else -> downloadResult as DriveOperationResult<SyncMetadataInfo>
                    }
                } finally {
                    tempFile.delete()
                }
            }
            is DriveOperationResult.NotFound -> {
                Log.d(Constants.TAG, "No sync metadata found (first sync or legacy)")
                DriveOperationResult.NotFound
            }
            else -> result as DriveOperationResult<SyncMetadataInfo>
        }
    } catch (e: Exception) {
        Log.e(Constants.TAG, "Failed to download sync metadata", e)
        DriveOperationResult.Error(e.message ?: "Failed to download metadata", e)
    }
}
