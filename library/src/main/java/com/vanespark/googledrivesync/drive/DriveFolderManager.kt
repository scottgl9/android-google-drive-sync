package com.vanespark.googledrivesync.drive

import android.util.Log
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFileModel
import com.vanespark.googledrivesync.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Google Drive folder hierarchy.
 *
 * This class provides methods for:
 * - Creating folders
 * - Finding folders by name
 * - Ensuring folder hierarchies exist
 * - Caching folder IDs for optimization
 */
@Singleton
class DriveFolderManager @Inject constructor() {
    /**
     * Folder IDs for the standard sync structure
     */
    data class SyncFolderIds(
        val rootFolderId: String,
        val syncFolderId: String,
        val backupsFolderId: String
    )

    /**
     * Create a folder on Google Drive.
     *
     * @param driveService The Drive service instance
     * @param folderName The name for the new folder
     * @param parentFolderId Optional parent folder ID (root if null)
     * @return The created folder
     */
    suspend fun createFolder(
        driveService: Drive,
        folderName: String,
        parentFolderId: String? = null
    ): DriveOperationResult<DriveFolder> = withContext(Dispatchers.IO) {
        try {
            val folderMetadata = DriveFileModel().apply {
                name = folderName
                mimeType = Constants.MIME_TYPE_FOLDER
                parentFolderId?.let { parents = listOf(it) }
            }

            val folder = driveService.files()
                .create(folderMetadata)
                .setFields("id, name, parents")
                .execute()

            Log.d(Constants.TAG, "Created folder: $folderName (${folder.id})")

            DriveOperationResult.Success(
                DriveFolder(
                    id = folder.id,
                    name = folder.name,
                    parents = folder.parents
                )
            )
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to create folder: $folderName", e)
            handleException(e)
        }
    }

    /**
     * Find a folder by name in a parent folder.
     *
     * @param driveService The Drive service instance
     * @param folderName The folder name to search for
     * @param parentFolderId Optional parent folder ID (searches root if null)
     * @return The folder if found
     */
    suspend fun findFolder(
        driveService: Drive,
        folderName: String,
        parentFolderId: String? = null
    ): DriveOperationResult<DriveFolder> = withContext(Dispatchers.IO) {
        try {
            val queryParts = mutableListOf(
                "name = '$folderName'",
                "mimeType = '${Constants.MIME_TYPE_FOLDER}'",
                "trashed = false"
            )

            parentFolderId?.let {
                queryParts.add("'$it' in parents")
            }

            val query = queryParts.joinToString(" and ")

            val result = driveService.files()
                .list()
                .setQ(query)
                .setFields("files(id, name, parents)")
                .setPageSize(1)
                .execute()

            val folder = result.files?.firstOrNull()
            if (folder != null) {
                DriveOperationResult.Success(
                    DriveFolder(
                        id = folder.id,
                        name = folder.name,
                        parents = folder.parents
                    )
                )
            } else {
                DriveOperationResult.NotFound
            }
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to find folder: $folderName", e)
            handleException(e)
        }
    }

    /**
     * Find or create a folder.
     *
     * @param driveService The Drive service instance
     * @param folderName The folder name
     * @param parentFolderId Optional parent folder ID
     * @return The existing or newly created folder
     */
    suspend fun findOrCreateFolder(
        driveService: Drive,
        folderName: String,
        parentFolderId: String? = null
    ): DriveOperationResult<DriveFolder> {
        // First try to find existing folder
        val findResult = findFolder(driveService, folderName, parentFolderId)

        return when (findResult) {
            is DriveOperationResult.Success -> findResult
            is DriveOperationResult.NotFound -> {
                // Create the folder
                createFolder(driveService, folderName, parentFolderId)
            }
            else -> findResult
        }
    }

    /**
     * Ensure the standard sync folder structure exists.
     *
     * Creates the following structure:
     * ```
     * {rootFolderName}/
     * ├── sync/
     * └── backups/
     * ```
     *
     * @param driveService The Drive service instance
     * @param rootFolderName The name of the root folder
     * @return The folder IDs for the structure
     */
    suspend fun ensureSyncFolderStructure(
        driveService: Drive,
        rootFolderName: String
    ): DriveOperationResult<SyncFolderIds> {
        // Create or find root folder
        val rootResult = findOrCreateFolder(driveService, rootFolderName)
        val rootFolder = when (rootResult) {
            is DriveOperationResult.Success -> rootResult.data
            else -> return rootResult as DriveOperationResult<SyncFolderIds>
        }

        // Create or find sync folder
        val syncResult = findOrCreateFolder(
            driveService,
            Constants.DEFAULT_SYNC_FOLDER_NAME,
            rootFolder.id
        )
        val syncFolder = when (syncResult) {
            is DriveOperationResult.Success -> syncResult.data
            else -> return syncResult as DriveOperationResult<SyncFolderIds>
        }

        // Create or find backups folder
        val backupsResult = findOrCreateFolder(
            driveService,
            Constants.DEFAULT_BACKUPS_FOLDER_NAME,
            rootFolder.id
        )
        val backupsFolder = when (backupsResult) {
            is DriveOperationResult.Success -> backupsResult.data
            else -> return backupsResult as DriveOperationResult<SyncFolderIds>
        }

        Log.d(Constants.TAG, "Folder structure ensured: root=${rootFolder.id}, sync=${syncFolder.id}, backups=${backupsFolder.id}")

        return DriveOperationResult.Success(
            SyncFolderIds(
                rootFolderId = rootFolder.id,
                syncFolderId = syncFolder.id,
                backupsFolderId = backupsFolder.id
            )
        )
    }

    /**
     * Ensure a nested folder path exists.
     *
     * @param driveService The Drive service instance
     * @param path The folder path (e.g., "documents/2024/january")
     * @param rootFolderId The root folder ID to start from
     * @return The ID of the deepest folder
     */
    suspend fun ensureFolderPath(
        driveService: Drive,
        path: String,
        rootFolderId: String
    ): DriveOperationResult<String> {
        val segments = path.split("/").filter { it.isNotBlank() }
        if (segments.isEmpty()) {
            return DriveOperationResult.Success(rootFolderId)
        }

        var currentParentId = rootFolderId

        for (segment in segments) {
            val result = findOrCreateFolder(driveService, segment, currentParentId)
            when (result) {
                is DriveOperationResult.Success -> {
                    currentParentId = result.data.id
                }
                else -> return result as DriveOperationResult<String>
            }
        }

        return DriveOperationResult.Success(currentParentId)
    }

    /**
     * List all subfolders in a folder.
     *
     * @param driveService The Drive service instance
     * @param parentFolderId The parent folder ID
     * @return List of subfolders
     */
    suspend fun listSubfolders(
        driveService: Drive,
        parentFolderId: String
    ): DriveOperationResult<List<DriveFolder>> = withContext(Dispatchers.IO) {
        try {
            val query = "'$parentFolderId' in parents and mimeType = '${Constants.MIME_TYPE_FOLDER}' and trashed = false"

            val allFolders = mutableListOf<DriveFolder>()
            var pageToken: String? = null

            do {
                val request = driveService.files()
                    .list()
                    .setQ(query)
                    .setFields("files(id, name, parents), nextPageToken")
                    .setPageSize(100)

                pageToken?.let { request.pageToken = it }

                val result = request.execute()

                result.files?.forEach { file ->
                    allFolders.add(
                        DriveFolder(
                            id = file.id,
                            name = file.name,
                            parents = file.parents
                        )
                    )
                }

                pageToken = result.nextPageToken
            } while (pageToken != null)

            DriveOperationResult.Success(allFolders)
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to list subfolders", e)
            handleException(e)
        }
    }

    /**
     * Delete a folder and all its contents.
     *
     * @param driveService The Drive service instance
     * @param folderId The folder ID to delete
     * @return Operation result
     */
    suspend fun deleteFolder(
        driveService: Drive,
        folderId: String
    ): DriveOperationResult<Unit> = withContext(Dispatchers.IO) {
        try {
            driveService.files()
                .delete(folderId)
                .execute()

            Log.d(Constants.TAG, "Deleted folder: $folderId")
            DriveOperationResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to delete folder: $folderId", e)
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
}
