package com.vanespark.googledrivesync.util

/**
 * Library-wide constants for Google Drive Sync
 */
object Constants {
    const val TAG = "GoogleDriveSync"

    // Default configuration
    const val DEFAULT_ROOT_FOLDER_NAME = "GoogleDriveSync"
    const val DEFAULT_SYNC_FOLDER_NAME = "sync"
    const val DEFAULT_BACKUPS_FOLDER_NAME = "backups"

    // Drive API
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    // Timeouts (milliseconds)
    const val CONNECT_TIMEOUT_MS = 60_000 // 60 seconds
    const val READ_TIMEOUT_MS = 180_000   // 3 minutes for large uploads/downloads

    // Retry configuration
    const val DEFAULT_MAX_RETRY_ATTEMPTS = 3
    const val DEFAULT_INITIAL_DELAY_MS = 1_000L
    const val DEFAULT_MAX_DELAY_MS = 30_000L
    const val DEFAULT_BACKOFF_MULTIPLIER = 2.0

    // Rate limiting
    const val RATE_LIMIT_DELAY_MS = 60_000L // 1 minute wait on 429
    const val BATCH_SIZE = 20
    const val BATCH_DELAY_MS = 1_000L

    // Cache configuration
    const val DEFAULT_CACHE_MAX_AGE_MS = 3_600_000L // 1 hour
    const val DEFAULT_CACHE_MAX_ENTRIES = 10_000

    // Sync configuration
    const val DEFAULT_SYNC_INTERVAL_HOURS = 12L
    const val SYNC_PROGRESS_TIMEOUT_MS = 30_000L
    const val SYNC_RESUME_TIMEOUT_MS = 3_600_000L // 1 hour

    // MIME types
    const val MIME_TYPE_FOLDER = "application/vnd.google-apps.folder"
    const val MIME_TYPE_OCTET_STREAM = "application/octet-stream"

    // File fields to request from Drive API
    const val FILE_FIELDS = "id, name, size, modifiedTime, md5Checksum, mimeType, parents"
    const val FILE_LIST_FIELDS = "files($FILE_FIELDS), nextPageToken"
}
