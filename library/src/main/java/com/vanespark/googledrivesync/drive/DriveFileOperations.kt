package com.vanespark.googledrivesync.drive

import android.util.Log
import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFileModel
import com.vanespark.googledrivesync.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles low-level Google Drive file operations.
 *
 * This class provides methods for:
 * - Uploading files (create and update)
 * - Downloading files
 * - Deleting files
 * - Listing files
 * - Getting file metadata
 */
@Singleton
class DriveFileOperations @Inject constructor() {

    /**
     * Upload a file to Google Drive, creating or updating as needed.
     *
     * @param driveService The Drive service instance
     * @param localFile The local file to upload
     * @param fileName The name to use on Drive
     * @param parentFolderId The parent folder ID
     * @param mimeType Optional MIME type (defaults to octet-stream)
     * @param existingFileId Optional existing file ID to update
     * @return Upload result with file metadata
     */
    suspend fun uploadFile(
        driveService: Drive,
        localFile: File,
        fileName: String,
        parentFolderId: String,
        mimeType: String = Constants.MIME_TYPE_OCTET_STREAM,
        existingFileId: String? = null
    ): DriveOperationResult<UploadResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val fileMetadata = DriveFileModel().apply {
                name = fileName
                if (existingFileId == null) {
                    parents = listOf(parentFolderId)
                }
            }

            val mediaContent = FileContent(mimeType, localFile)

            val uploadedFile = if (existingFileId != null) {
                // Update existing file
                driveService.files()
                    .update(existingFileId, fileMetadata, mediaContent)
                    .setFields(Constants.FILE_FIELDS)
                    .execute()
            } else {
                // Create new file
                driveService.files()
                    .create(fileMetadata, mediaContent)
                    .setFields(Constants.FILE_FIELDS)
                    .execute()
            }

            val duration = System.currentTimeMillis() - startTime
            Log.d(Constants.TAG, "Uploaded $fileName (${localFile.length()} bytes) in ${duration}ms")

            DriveOperationResult.Success(
                UploadResult(
                    file = uploadedFile.toDriveFile(),
                    wasUpdated = existingFileId != null,
                    durationMs = duration
                )
            )
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Upload failed for $fileName", e)
            handleException(e)
        }
    }

    /**
     * Download a file from Google Drive.
     *
     * @param driveService The Drive service instance
     * @param fileId The Drive file ID to download
     * @param destinationFile The local file to write to
     * @return Download result with file info
     */
    suspend fun downloadFile(
        driveService: Drive,
        fileId: String,
        destinationFile: File
    ): DriveOperationResult<DownloadResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            // Ensure parent directory exists
            destinationFile.parentFile?.mkdirs()

            FileOutputStream(destinationFile).use { outputStream ->
                driveService.files()
                    .get(fileId)
                    .executeMediaAndDownloadTo(outputStream)
            }

            val duration = System.currentTimeMillis() - startTime
            val size = destinationFile.length()
            Log.d(Constants.TAG, "Downloaded file ($size bytes) in ${duration}ms")

            DriveOperationResult.Success(
                DownloadResult(
                    localPath = destinationFile.absolutePath,
                    size = size,
                    durationMs = duration
                )
            )
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Download failed for file $fileId", e)
            // Clean up partial download
            destinationFile.delete()
            handleException(e)
        }
    }

    /**
     * Delete a file from Google Drive.
     *
     * @param driveService The Drive service instance
     * @param fileId The Drive file ID to delete
     * @return Operation result
     */
    suspend fun deleteFile(
        driveService: Drive,
        fileId: String
    ): DriveOperationResult<Unit> = withContext(Dispatchers.IO) {
        try {
            driveService.files()
                .delete(fileId)
                .execute()

            Log.d(Constants.TAG, "Deleted file $fileId")
            DriveOperationResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Delete failed for file $fileId", e)
            handleException(e)
        }
    }

    /**
     * Get file metadata from Google Drive.
     *
     * @param driveService The Drive service instance
     * @param fileId The Drive file ID
     * @return File metadata
     */
    suspend fun getFile(
        driveService: Drive,
        fileId: String
    ): DriveOperationResult<DriveFile> = withContext(Dispatchers.IO) {
        try {
            val file = driveService.files()
                .get(fileId)
                .setFields(Constants.FILE_FIELDS)
                .execute()

            DriveOperationResult.Success(file.toDriveFile())
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Get file failed for $fileId", e)
            handleException(e)
        }
    }

    /**
     * Find a file by name in a parent folder.
     *
     * @param driveService The Drive service instance
     * @param name The file name to search for
     * @param parentFolderId The parent folder ID
     * @return The file if found, or NotFound
     */
    suspend fun findFileByName(
        driveService: Drive,
        name: String,
        parentFolderId: String
    ): DriveOperationResult<DriveFile> = withContext(Dispatchers.IO) {
        try {
            val query = "'$parentFolderId' in parents and name = '$name' and trashed = false"

            val result = driveService.files()
                .list()
                .setQ(query)
                .setFields(Constants.FILE_LIST_FIELDS)
                .setPageSize(1)
                .execute()

            val file = result.files?.firstOrNull()
            if (file != null) {
                DriveOperationResult.Success(file.toDriveFile())
            } else {
                DriveOperationResult.NotFound
            }
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Find file failed for $name", e)
            handleException(e)
        }
    }

    /**
     * Find a file ID by name in a parent folder.
     *
     * @param driveService The Drive service instance
     * @param name The file name to search for
     * @param parentFolderId The parent folder ID
     * @return The file ID if found, or null
     */
    suspend fun findFileIdByName(
        driveService: Drive,
        name: String,
        parentFolderId: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val query = "'$parentFolderId' in parents and name = '$name' and trashed = false"

            val result = driveService.files()
                .list()
                .setQ(query)
                .setFields("files(id)")
                .setPageSize(1)
                .execute()

            result.files?.firstOrNull()?.id
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Find file ID failed for $name", e)
            null
        }
    }

    /**
     * List files in a folder.
     *
     * @param driveService The Drive service instance
     * @param query The query parameters
     * @return Page of files
     */
    suspend fun listFiles(
        driveService: Drive,
        query: FileListQuery
    ): DriveOperationResult<FileListPage> = withContext(Dispatchers.IO) {
        try {
            val queryParts = mutableListOf<String>()

            query.parentId?.let { queryParts.add("'$it' in parents") }
            query.name?.let { queryParts.add("name = '$it'") }
            query.mimeType?.let { queryParts.add("mimeType = '$it'") }

            if (!query.includeTrashed) {
                queryParts.add("trashed = false")
            }

            val queryString = queryParts.joinToString(" and ")

            val request = driveService.files()
                .list()
                .setFields(Constants.FILE_LIST_FIELDS)
                .setPageSize(query.pageSize)

            if (queryString.isNotEmpty()) {
                request.setQ(queryString)
            }

            query.pageToken?.let { request.pageToken = it }
            query.orderBy?.let { request.orderBy = it }

            val result = request.execute()

            DriveOperationResult.Success(
                FileListPage(
                    files = result.files?.map { it.toDriveFile() } ?: emptyList(),
                    nextPageToken = result.nextPageToken
                )
            )
        } catch (e: Exception) {
            Log.e(Constants.TAG, "List files failed", e)
            handleException(e)
        }
    }

    /**
     * List all files in a folder (handles pagination automatically).
     *
     * @param driveService The Drive service instance
     * @param parentFolderId The parent folder ID
     * @return All files in the folder
     */
    suspend fun listAllFiles(
        driveService: Drive,
        parentFolderId: String
    ): DriveOperationResult<List<DriveFile>> = withContext(Dispatchers.IO) {
        try {
            val allFiles = mutableListOf<DriveFile>()
            var pageToken: String? = null

            do {
                val result = listFiles(
                    driveService,
                    FileListQuery(
                        parentId = parentFolderId,
                        pageToken = pageToken
                    )
                )

                when (result) {
                    is DriveOperationResult.Success -> {
                        allFiles.addAll(result.data.files)
                        pageToken = result.data.nextPageToken
                    }
                    else -> return@withContext result as DriveOperationResult<List<DriveFile>>
                }
            } while (pageToken != null)

            DriveOperationResult.Success(allFiles)
        } catch (e: Exception) {
            Log.e(Constants.TAG, "List all files failed", e)
            handleException(e)
        }
    }

    /**
     * List all files recursively in a folder, including subdirectories.
     * Returns files with their relative paths.
     *
     * @param driveService The Drive service instance
     * @param parentFolderId The parent folder ID
     * @param basePath The base path prefix for relative paths
     * @return All files with their relative paths
     */
    suspend fun listAllFilesRecursive(
        driveService: Drive,
        parentFolderId: String,
        basePath: String = ""
    ): DriveOperationResult<List<DriveFileWithPath>> = withContext(Dispatchers.IO) {
        try {
            val allFiles = mutableListOf<DriveFileWithPath>()

            // List all items in this folder
            val result = listAllFiles(driveService, parentFolderId)

            when (result) {
                is DriveOperationResult.Success -> {
                    for (file in result.data) {
                        val relativePath = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"

                        if (file.isFolder) {
                            // Recursively list subfolder contents
                            when (val subResult = listAllFilesRecursive(driveService, file.id, relativePath)) {
                                is DriveOperationResult.Success -> {
                                    allFiles.addAll(subResult.data)
                                }
                                else -> {
                                    Log.w(Constants.TAG, "Failed to list subfolder $relativePath")
                                }
                            }
                        } else {
                            // Add file with its relative path
                            allFiles.add(DriveFileWithPath(
                                file = file,
                                relativePath = relativePath
                            ))
                        }
                    }
                    DriveOperationResult.Success(allFiles)
                }
                else -> result as DriveOperationResult<List<DriveFileWithPath>>
            }
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Recursive file list failed", e)
            handleException(e)
        }
    }

    /**
     * Build a cache of all files for efficient lookups.
     * Maps relative paths to file info including checksums.
     *
     * @param driveService The Drive service instance
     * @param parentFolderId The parent folder ID
     * @return File cache mapping paths to files
     */
    suspend fun buildFileCache(
        driveService: Drive,
        parentFolderId: String
    ): DriveOperationResult<DriveFileCache> = withContext(Dispatchers.IO) {
        try {
            when (val result = listAllFilesRecursive(driveService, parentFolderId)) {
                is DriveOperationResult.Success -> {
                    val filesByPath = result.data.associate { it.relativePath to it.file }
                    val checksumToPath = mutableMapOf<String, String>()

                    // Build checksum index for efficient duplicate detection
                    result.data.forEach { fileWithPath ->
                        fileWithPath.file.md5Checksum?.let { checksum ->
                            checksumToPath[checksum] = fileWithPath.relativePath
                        }
                    }

                    Log.d(Constants.TAG, "Built file cache: ${filesByPath.size} files, ${checksumToPath.size} with checksums")
                    DriveOperationResult.Success(DriveFileCache(filesByPath, checksumToPath))
                }
                else -> result as DriveOperationResult<DriveFileCache>
            }
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Build file cache failed", e)
            handleException(e)
        }
    }

    /**
     * Convert Drive API exception to operation result
     */
    private fun <T> handleException(e: Exception): DriveOperationResult<T> {
        val message = e.message ?: "Unknown error"
        return when {
            message.contains("401") -> DriveOperationResult.NotSignedIn
            message.contains("403") -> DriveOperationResult.PermissionRequired
            message.contains("404") -> DriveOperationResult.NotFound
            message.contains("429") -> DriveOperationResult.RateLimited
            message.contains("503") || message.contains("502") || message.contains("504") ->
                DriveOperationResult.ServiceUnavailable
            else -> DriveOperationResult.Error(message, e)
        }
    }

    /**
     * Extension function to convert Drive API File to our DriveFile model
     */
    private fun DriveFileModel.toDriveFile(): DriveFile {
        return DriveFile(
            id = id,
            name = name,
            size = getSize()?.toLong() ?: 0L,
            modifiedTime = modifiedTime?.let {
                Instant.ofEpochMilli(it.value)
            } ?: Instant.now(),
            md5Checksum = md5Checksum,
            mimeType = mimeType ?: Constants.MIME_TYPE_OCTET_STREAM,
            parents = parents
        )
    }
}
