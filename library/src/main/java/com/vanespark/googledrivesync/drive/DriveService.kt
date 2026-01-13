package com.vanespark.googledrivesync.drive

import android.util.Log
import com.google.api.services.drive.Drive
import com.vanespark.googledrivesync.auth.GoogleAuthManager
import com.vanespark.googledrivesync.util.Constants
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level wrapper for Google Drive operations.
 *
 * This class provides:
 * - Automatic authentication handling
 * - Lazy Drive service creation
 * - Coordinated access to Drive operations
 */
@Singleton
class DriveService @Inject constructor(
    private val authManager: GoogleAuthManager,
    private val fileOperations: DriveFileOperations,
    private val folderManager: DriveFolderManager
) {
    private var driveInstance: Drive? = null
    private val driveMutex = Mutex()

    private var folderIds: DriveFolderManager.SyncFolderIds? = null
    private val folderMutex = Mutex()

    /**
     * Get the Drive service instance, creating it if needed.
     *
     * @return Drive service or null if not signed in
     */
    suspend fun getDrive(): Drive? = driveMutex.withLock {
        // Return cached instance if valid
        driveInstance?.let { return it }

        // Create new instance
        val service = authManager.getDriveService()
        if (service != null) {
            driveInstance = service
            Log.d(Constants.TAG, "Created Drive service instance")
        }
        service
    }

    /**
     * Get Drive service or return NotSignedIn result.
     */
    suspend fun <T> withDrive(
        block: suspend (Drive) -> DriveOperationResult<T>
    ): DriveOperationResult<T> {
        val drive = getDrive() ?: return DriveOperationResult.NotSignedIn
        return block(drive)
    }

    /**
     * Clear cached Drive instance (call on sign-out).
     */
    suspend fun clearCache() = driveMutex.withLock {
        driveInstance = null
        folderIds = null
        Log.d(Constants.TAG, "Cleared Drive service cache")
    }

    /**
     * Get or create the standard sync folder structure.
     *
     * @param rootFolderName The root folder name to use
     * @return Folder IDs for the structure
     */
    suspend fun ensureFolderStructure(
        rootFolderName: String
    ): DriveOperationResult<DriveFolderManager.SyncFolderIds> = folderMutex.withLock {
        // Return cached folder IDs if available
        folderIds?.let { return DriveOperationResult.Success(it) }

        // Create folder structure
        return withDrive { drive ->
            val result = folderManager.ensureSyncFolderStructure(drive, rootFolderName)
            if (result is DriveOperationResult.Success) {
                folderIds = result.data
            }
            result
        }
    }

    // ========== File Operations ==========

    /**
     * Upload a file to the sync folder.
     *
     * @param localFile The local file to upload
     * @param remotePath The path within the sync folder
     * @param rootFolderName The root folder name
     * @return Upload result
     */
    suspend fun uploadFile(
        localFile: java.io.File,
        remotePath: String,
        rootFolderName: String
    ): DriveOperationResult<UploadResult> {
        // Ensure folder structure exists
        val folders = when (val result = ensureFolderStructure(rootFolderName)) {
            is DriveOperationResult.Success -> result.data
            else -> return result as DriveOperationResult<UploadResult>
        }

        return withDrive { drive ->
            // Parse the path
            val pathParts = remotePath.split("/")
            val fileName = pathParts.last()
            val folderPath = pathParts.dropLast(1).joinToString("/")

            // Ensure nested folders exist
            val parentFolderId = if (folderPath.isNotBlank()) {
                when (val folderResult = folderManager.ensureFolderPath(
                    drive,
                    folderPath,
                    folders.syncFolderId
                )) {
                    is DriveOperationResult.Success -> folderResult.data
                    else -> return@withDrive folderResult as DriveOperationResult<UploadResult>
                }
            } else {
                folders.syncFolderId
            }

            // Check if file already exists
            val existingFileId = fileOperations.findFileIdByName(drive, fileName, parentFolderId)

            // Upload file
            fileOperations.uploadFile(
                driveService = drive,
                localFile = localFile,
                fileName = fileName,
                parentFolderId = parentFolderId,
                existingFileId = existingFileId
            )
        }
    }

    /**
     * Download a file from the sync folder.
     *
     * @param fileId The Drive file ID
     * @param destinationFile The local destination file
     * @return Download result
     */
    suspend fun downloadFile(
        fileId: String,
        destinationFile: java.io.File
    ): DriveOperationResult<DownloadResult> {
        return withDrive { drive ->
            fileOperations.downloadFile(drive, fileId, destinationFile)
        }
    }

    /**
     * Delete a file from Google Drive.
     *
     * @param fileId The Drive file ID
     * @return Operation result
     */
    suspend fun deleteFile(fileId: String): DriveOperationResult<Unit> {
        return withDrive { drive ->
            fileOperations.deleteFile(drive, fileId)
        }
    }

    /**
     * Get file metadata.
     *
     * @param fileId The Drive file ID
     * @return File metadata
     */
    suspend fun getFile(fileId: String): DriveOperationResult<DriveFile> {
        return withDrive { drive ->
            fileOperations.getFile(drive, fileId)
        }
    }

    /**
     * List all files in the sync folder.
     *
     * @param rootFolderName The root folder name
     * @return List of files
     */
    suspend fun listSyncFiles(
        rootFolderName: String
    ): DriveOperationResult<List<DriveFile>> {
        val folders = when (val result = ensureFolderStructure(rootFolderName)) {
            is DriveOperationResult.Success -> result.data
            else -> return result as DriveOperationResult<List<DriveFile>>
        }

        return withDrive { drive ->
            fileOperations.listAllFiles(drive, folders.syncFolderId)
        }
    }

    /**
     * Find a file by name in a specific folder path.
     *
     * @param name The file name
     * @param folderPath The path within the sync folder
     * @param rootFolderName The root folder name
     * @return The file if found
     */
    suspend fun findFile(
        name: String,
        folderPath: String,
        rootFolderName: String
    ): DriveOperationResult<DriveFile> {
        val folders = when (val result = ensureFolderStructure(rootFolderName)) {
            is DriveOperationResult.Success -> result.data
            else -> return result as DriveOperationResult<DriveFile>
        }

        return withDrive { drive ->
            // Find the parent folder
            val parentFolderId = if (folderPath.isNotBlank()) {
                when (val folderResult = folderManager.ensureFolderPath(
                    drive,
                    folderPath,
                    folders.syncFolderId
                )) {
                    is DriveOperationResult.Success -> folderResult.data
                    else -> return@withDrive folderResult as DriveOperationResult<DriveFile>
                }
            } else {
                folders.syncFolderId
            }

            fileOperations.findFileByName(drive, name, parentFolderId)
        }
    }

    // ========== Backup Operations ==========

    /**
     * Upload a backup file.
     *
     * @param localFile The local backup file
     * @param backupName The backup file name
     * @param rootFolderName The root folder name
     * @return Upload result
     */
    suspend fun uploadBackup(
        localFile: java.io.File,
        backupName: String,
        rootFolderName: String
    ): DriveOperationResult<UploadResult> {
        val folders = when (val result = ensureFolderStructure(rootFolderName)) {
            is DriveOperationResult.Success -> result.data
            else -> return result as DriveOperationResult<UploadResult>
        }

        return withDrive { drive ->
            fileOperations.uploadFile(
                driveService = drive,
                localFile = localFile,
                fileName = backupName,
                parentFolderId = folders.backupsFolderId
            )
        }
    }

    /**
     * List all backup files.
     *
     * @param rootFolderName The root folder name
     * @return List of backup files
     */
    suspend fun listBackups(
        rootFolderName: String
    ): DriveOperationResult<List<DriveFile>> {
        val folders = when (val result = ensureFolderStructure(rootFolderName)) {
            is DriveOperationResult.Success -> result.data
            else -> return result as DriveOperationResult<List<DriveFile>>
        }

        return withDrive { drive ->
            fileOperations.listAllFiles(drive, folders.backupsFolderId)
        }
    }
}
