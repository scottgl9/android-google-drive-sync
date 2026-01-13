package com.vanespark.googledrivesync.drive

import java.time.Instant

/**
 * Represents a file stored on Google Drive
 */
data class DriveFile(
    /**
     * Google Drive file ID
     */
    val id: String,

    /**
     * File name
     */
    val name: String,

    /**
     * File size in bytes
     */
    val size: Long,

    /**
     * Last modified timestamp
     */
    val modifiedTime: Instant,

    /**
     * MD5 checksum of file contents (null for folders)
     */
    val md5Checksum: String?,

    /**
     * MIME type of the file
     */
    val mimeType: String,

    /**
     * Parent folder IDs
     */
    val parents: List<String>?
) {
    /**
     * Check if this is a folder
     */
    val isFolder: Boolean
        get() = mimeType == "application/vnd.google-apps.folder"
}

/**
 * Represents a folder on Google Drive
 */
data class DriveFolder(
    /**
     * Google Drive folder ID
     */
    val id: String,

    /**
     * Folder name
     */
    val name: String,

    /**
     * Parent folder IDs
     */
    val parents: List<String>?
)

/**
 * Represents a backup file stored on Google Drive
 */
data class DriveBackupFile(
    /**
     * Google Drive file ID
     */
    val id: String,

    /**
     * Backup file name
     */
    val name: String,

    /**
     * File size in bytes
     */
    val size: Long,

    /**
     * Creation timestamp
     */
    val createdTime: Instant
)

/**
 * Result of a Drive operation
 */
sealed class DriveOperationResult<out T> {
    /**
     * Operation succeeded with result
     */
    data class Success<T>(val data: T) : DriveOperationResult<T>()

    /**
     * Operation failed with error
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : DriveOperationResult<Nothing>()

    /**
     * User is not signed in
     */
    object NotSignedIn : DriveOperationResult<Nothing>()

    /**
     * Additional permissions are required
     */
    object PermissionRequired : DriveOperationResult<Nothing>()

    /**
     * Service is temporarily unavailable
     */
    object ServiceUnavailable : DriveOperationResult<Nothing>()

    /**
     * Rate limit exceeded
     */
    object RateLimited : DriveOperationResult<Nothing>()

    /**
     * File or folder not found
     */
    object NotFound : DriveOperationResult<Nothing>()

    /**
     * Check if the result is successful
     */
    val isSuccess: Boolean
        get() = this is Success

    /**
     * Get the data if successful, or null otherwise
     */
    fun getOrNull(): T? = (this as? Success)?.data

    /**
     * Get the data if successful, or throw an exception
     */
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw DriveException(message, cause)
        NotSignedIn -> throw DriveException("Not signed in")
        PermissionRequired -> throw DriveException("Permission required")
        ServiceUnavailable -> throw DriveException("Service unavailable")
        RateLimited -> throw DriveException("Rate limited")
        NotFound -> throw DriveException("Not found")
    }
}

/**
 * Exception for Drive operations
 */
class DriveException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Result of an upload operation
 */
data class UploadResult(
    /**
     * The uploaded file
     */
    val file: DriveFile,

    /**
     * Whether this was a new file or update
     */
    val wasUpdated: Boolean,

    /**
     * Upload duration in milliseconds
     */
    val durationMs: Long
)

/**
 * Result of a download operation
 */
data class DownloadResult(
    /**
     * Local file path where the file was downloaded
     */
    val localPath: String,

    /**
     * Size of downloaded file in bytes
     */
    val size: Long,

    /**
     * Download duration in milliseconds
     */
    val durationMs: Long
)

/**
 * Metadata about a file listing page
 */
data class FileListPage(
    /**
     * Files in this page
     */
    val files: List<DriveFile>,

    /**
     * Token for fetching next page, null if last page
     */
    val nextPageToken: String?
)

/**
 * Query parameters for listing files
 */
data class FileListQuery(
    /**
     * Parent folder ID to list files from
     */
    val parentId: String? = null,

    /**
     * File name to filter by (exact match)
     */
    val name: String? = null,

    /**
     * MIME type to filter by
     */
    val mimeType: String? = null,

    /**
     * Maximum number of files to return per page
     */
    val pageSize: Int = 100,

    /**
     * Token for fetching next page
     */
    val pageToken: String? = null,

    /**
     * Order by clause (e.g., "modifiedTime desc")
     */
    val orderBy: String? = null,

    /**
     * Include trashed files
     */
    val includeTrashed: Boolean = false
)

/**
 * Cache of folder IDs for sync optimization
 */
data class FolderCache(
    /**
     * Root folder ID
     */
    val rootFolderId: String?,

    /**
     * Map of folder path to folder ID
     */
    val folderIds: Map<String, String> = emptyMap()
) {
    /**
     * Get folder ID by path
     */
    fun getFolderId(path: String): String? = folderIds[path]

    /**
     * Check if folder exists in cache
     */
    fun hasFolder(path: String): Boolean = folderIds.containsKey(path)

    /**
     * Create a new cache with an additional folder
     */
    fun withFolder(path: String, folderId: String): FolderCache =
        copy(folderIds = folderIds + (path to folderId))
}

/**
 * Wrapper for DriveFile with its relative path from sync root.
 * Used for recursive file listing.
 */
data class DriveFileWithPath(
    /**
     * The Drive file
     */
    val file: DriveFile,

    /**
     * Relative path from sync root (e.g., "subdir/file.txt")
     */
    val relativePath: String
)

/**
 * Cache of remote files for efficient lookups during sync.
 * Enables O(1) file existence checks instead of O(n) API calls.
 */
data class DriveFileCache(
    /**
     * Map of relative path to file metadata
     */
    val filesByPath: Map<String, DriveFile>,

    /**
     * Map of MD5 checksum to relative path for duplicate detection
     */
    val checksumToPath: Map<String, String>
) {
    /**
     * Check if a file exists at the given path
     */
    fun hasFile(path: String): Boolean = filesByPath.containsKey(path)

    /**
     * Get file by path
     */
    fun getFile(path: String): DriveFile? = filesByPath[path]

    /**
     * Check if a file with the given checksum already exists
     */
    fun hasChecksum(checksum: String): Boolean = checksumToPath.containsKey(checksum)

    /**
     * Get the path of file with the given checksum
     */
    fun getPathByChecksum(checksum: String): String? = checksumToPath[checksum]

    /**
     * Number of files in cache
     */
    val size: Int get() = filesByPath.size
}
