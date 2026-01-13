package com.vanespark.googledrivesync.sync

import com.vanespark.googledrivesync.drive.DriveFile
import com.vanespark.googledrivesync.local.LocalFileInfo
import java.time.Instant
import kotlin.time.Duration

/**
 * Mode of synchronization
 */
enum class SyncMode {
    /**
     * Upload local changes to cloud
     */
    UPLOAD_ONLY,

    /**
     * Download cloud changes to local
     */
    DOWNLOAD_ONLY,

    /**
     * Sync in both directions
     */
    BIDIRECTIONAL,

    /**
     * Make remote exactly match local (deletes remote-only files)
     */
    MIRROR_TO_CLOUD,

    /**
     * Make local exactly match remote (deletes local-only files)
     */
    MIRROR_FROM_CLOUD
}

/**
 * Policy for resolving conflicts when a file exists both locally and remotely with different content
 */
enum class ConflictPolicy {
    /**
     * Local file overwrites remote
     */
    LOCAL_WINS,

    /**
     * Remote file overwrites local
     */
    REMOTE_WINS,

    /**
     * File with newer modification time wins
     */
    NEWER_WINS,

    /**
     * Keep both files (rename local with conflict suffix)
     */
    KEEP_BOTH,

    /**
     * Skip conflicting files
     */
    SKIP,

    /**
     * Ask user via callback
     */
    ASK_USER
}

/**
 * Represents a file that needs to be synced
 */
data class SyncItem(
    /**
     * Relative path from sync root
     */
    val relativePath: String,

    /**
     * File name
     */
    val name: String,

    /**
     * Local file info (null if doesn't exist locally)
     */
    val localFile: LocalFileInfo?,

    /**
     * Remote file info (null if doesn't exist remotely)
     */
    val remoteFile: DriveFile?,

    /**
     * Required action for this file
     */
    val action: SyncAction,

    /**
     * Conflict info if applicable
     */
    val conflict: ConflictInfo? = null
)

/**
 * Action to take for a sync item
 */
enum class SyncAction {
    /**
     * Upload local file to cloud
     */
    UPLOAD,

    /**
     * Download remote file to local
     */
    DOWNLOAD,

    /**
     * Delete local file
     */
    DELETE_LOCAL,

    /**
     * Delete remote file
     */
    DELETE_REMOTE,

    /**
     * File is in sync, no action needed
     */
    SKIP,

    /**
     * Conflict needs resolution
     */
    CONFLICT
}

/**
 * Information about a conflict between local and remote files
 */
data class ConflictInfo(
    /**
     * Relative path of the file
     */
    val relativePath: String,

    /**
     * Local file checksum
     */
    val localChecksum: String?,

    /**
     * Remote file checksum
     */
    val remoteChecksum: String?,

    /**
     * Local modification time
     */
    val localModifiedTime: Instant?,

    /**
     * Remote modification time
     */
    val remoteModifiedTime: Instant?,

    /**
     * Local file size
     */
    val localSize: Long,

    /**
     * Remote file size
     */
    val remoteSize: Long
)

/**
 * Resolution for a conflict
 */
sealed class ConflictResolution {
    /**
     * Use the local version
     */
    object UseLocal : ConflictResolution()

    /**
     * Use the remote version
     */
    object UseRemote : ConflictResolution()

    /**
     * Keep both files with renamed local
     */
    data class KeepBoth(val localSuffix: String = "_conflict") : ConflictResolution()

    /**
     * Skip this file
     */
    object Skip : ConflictResolution()

    /**
     * Delete both versions
     */
    object DeleteBoth : ConflictResolution()
}

/**
 * Result of a sync operation
 */
sealed class SyncResult {
    /**
     * Sync completed successfully
     */
    data class Success(
        val filesUploaded: Int,
        val filesDownloaded: Int,
        val filesDeleted: Int,
        val filesSkipped: Int,
        val conflicts: List<ConflictInfo>,
        val duration: Duration,
        val bytesTransferred: Long
    ) : SyncResult()

    /**
     * Sync completed with some failures
     */
    data class PartialSuccess(
        val filesSucceeded: Int,
        val filesFailed: Int,
        val errors: List<SyncError>,
        val duration: Duration
    ) : SyncResult()

    /**
     * Sync failed completely
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : SyncResult()

    /**
     * User is not signed in
     */
    object NotSignedIn : SyncResult()

    /**
     * Network is not available
     */
    object NetworkUnavailable : SyncResult()

    /**
     * Sync was cancelled
     */
    object Cancelled : SyncResult()

    /**
     * Sync was paused (can be resumed)
     */
    object Paused : SyncResult()
}

/**
 * Error that occurred during sync
 */
data class SyncError(
    /**
     * File that caused the error
     */
    val relativePath: String,

    /**
     * Error message
     */
    val message: String,

    /**
     * Underlying cause
     */
    val cause: Throwable? = null
)

/**
 * Options for a sync operation
 */
data class SyncOptions(
    /**
     * Sync mode to use
     */
    val mode: SyncMode = SyncMode.BIDIRECTIONAL,

    /**
     * Conflict resolution policy
     */
    val conflictPolicy: ConflictPolicy = ConflictPolicy.NEWER_WINS,

    /**
     * Whether to delete files that only exist on one side (for mirror modes)
     */
    val allowDeletions: Boolean = false,

    /**
     * Subdirectory to sync (null for entire sync folder)
     */
    val subdirectory: String? = null,

    /**
     * Maximum number of files to sync (0 for unlimited)
     */
    val maxFiles: Int = 0,

    /**
     * Whether to verify checksums after transfer
     */
    val verifyChecksums: Boolean = true,

    /**
     * Whether to use cached file list
     */
    val useCache: Boolean = true
) {
    companion object {
        val DEFAULT = SyncOptions()

        val UPLOAD_ONLY = SyncOptions(mode = SyncMode.UPLOAD_ONLY)

        val DOWNLOAD_ONLY = SyncOptions(mode = SyncMode.DOWNLOAD_ONLY)

        val MIRROR_TO_CLOUD = SyncOptions(
            mode = SyncMode.MIRROR_TO_CLOUD,
            allowDeletions = true
        )

        val MIRROR_FROM_CLOUD = SyncOptions(
            mode = SyncMode.MIRROR_FROM_CLOUD,
            allowDeletions = true
        )
    }
}

/**
 * Manifest of files for comparison
 */
data class FileManifest(
    /**
     * Map of relative path to file info
     */
    val files: Map<String, FileManifestEntry>,

    /**
     * Time manifest was created
     */
    val createdAt: Instant = Instant.now()
)

/**
 * Entry in a file manifest
 */
data class FileManifestEntry(
    /**
     * Relative path from sync root
     */
    val relativePath: String,

    /**
     * File name
     */
    val name: String,

    /**
     * File size in bytes
     */
    val size: Long,

    /**
     * Last modified time
     */
    val modifiedTime: Instant,

    /**
     * File checksum
     */
    val checksum: String?,

    /**
     * Google Drive file ID (only set for remote files)
     */
    val driveFileId: String? = null
)
