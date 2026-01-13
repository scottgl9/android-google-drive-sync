package com.vanespark.googledrivesync.sync

import android.util.Log
import com.vanespark.googledrivesync.drive.DriveFile
import com.vanespark.googledrivesync.drive.DriveOperationResult
import com.vanespark.googledrivesync.drive.DriveService
import com.vanespark.googledrivesync.local.ChecksumAlgorithm
import com.vanespark.googledrivesync.local.FileFilter
import com.vanespark.googledrivesync.local.FileHasher
import com.vanespark.googledrivesync.local.LocalFileInfo
import com.vanespark.googledrivesync.local.LocalFileManager
import com.vanespark.googledrivesync.resilience.RetryPolicy
import com.vanespark.googledrivesync.resilience.SyncProgressManager
import com.vanespark.googledrivesync.resilience.withRetry
import com.vanespark.googledrivesync.util.Constants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Core sync engine that performs file synchronization operations.
 *
 * Handles:
 * - Building file manifests (local and remote)
 * - Comparing files to determine sync actions
 * - Executing uploads, downloads, and deletions
 * - Progress tracking and error handling
 */
@Singleton
class SyncEngine @Inject constructor(
    private val driveService: DriveService,
    private val localFileManager: LocalFileManager,
    private val fileHasher: FileHasher,
    private val conflictResolver: ConflictResolver,
    private val progressManager: SyncProgressManager
) {
    /**
     * Build a manifest of local files
     *
     * @param syncDirectory The local directory to scan
     * @param filter Optional file filter
     * @param includeChecksums Whether to compute checksums
     * @return Local file manifest
     */
    suspend fun buildLocalManifest(
        syncDirectory: File,
        filter: FileFilter = FileFilter.defaultSyncFilter(),
        includeChecksums: Boolean = true
    ): FileManifest {
        Log.d(Constants.TAG, "Building local manifest from ${syncDirectory.absolutePath}")

        val files = if (includeChecksums) {
            localFileManager.listFilesWithChecksums(syncDirectory, filter)
        } else {
            localFileManager.listFiles(syncDirectory, filter)
        }

        val entries = files.associate { file ->
            file.relativePath to FileManifestEntry(
                relativePath = file.relativePath,
                name = file.name,
                size = file.size,
                modifiedTime = file.modifiedTime,
                checksum = file.checksum
            )
        }

        Log.d(Constants.TAG, "Local manifest built: ${entries.size} files")
        return FileManifest(files = entries)
    }

    /**
     * Build a manifest of remote files (includes subdirectories recursively)
     *
     * @param rootFolderName The root folder name on Drive
     * @return Remote file manifest with full relative paths
     */
    suspend fun buildRemoteManifest(rootFolderName: String): FileManifest {
        Log.d(Constants.TAG, "Building remote manifest from $rootFolderName (recursive)")

        val result = driveService.listSyncFilesRecursive(rootFolderName)

        return when (result) {
            is DriveOperationResult.Success -> {
                val entries = result.data.associate { fileWithPath ->
                    fileWithPath.relativePath to FileManifestEntry(
                        relativePath = fileWithPath.relativePath,
                        name = fileWithPath.file.name,
                        size = fileWithPath.file.size,
                        modifiedTime = fileWithPath.file.modifiedTime,
                        checksum = fileWithPath.file.md5Checksum,
                        driveFileId = fileWithPath.file.id
                    )
                }
                Log.d(Constants.TAG, "Remote manifest built: ${entries.size} files (recursive)")
                FileManifest(files = entries)
            }
            else -> {
                Log.w(Constants.TAG, "Failed to build remote manifest: $result")
                FileManifest(files = emptyMap())
            }
        }
    }

    /**
     * Compare local and remote manifests to determine sync actions
     *
     * @param localManifest Local file manifest
     * @param remoteManifest Remote file manifest
     * @param options Sync options
     * @return List of items to sync
     */
    suspend fun compareManifests(
        localManifest: FileManifest,
        remoteManifest: FileManifest,
        options: SyncOptions
    ): List<SyncItem> {
        Log.d(Constants.TAG, "Comparing manifests: ${localManifest.files.size} local, ${remoteManifest.files.size} remote")

        val syncItems = mutableListOf<SyncItem>()
        val allPaths = (localManifest.files.keys + remoteManifest.files.keys).toSet()

        for (path in allPaths) {
            coroutineContext.ensureActive()

            val localEntry = localManifest.files[path]
            val remoteEntry = remoteManifest.files[path]

            val item = determineSyncAction(localEntry, remoteEntry, options)
            syncItems.add(item)
        }

        Log.d(Constants.TAG, "Comparison complete: ${syncItems.size} items")
        logSyncPlan(syncItems)

        return syncItems
    }

    /**
     * Determine the sync action for a single file
     */
    private suspend fun determineSyncAction(
        localEntry: FileManifestEntry?,
        remoteEntry: FileManifestEntry?,
        options: SyncOptions
    ): SyncItem {
        val path = localEntry?.relativePath ?: remoteEntry?.relativePath ?: ""
        val name = localEntry?.name ?: remoteEntry?.name ?: ""

        // Convert entries to file info for SyncItem
        val localInfo = localEntry?.toLocalFileInfo()
        val remoteInfo = remoteEntry?.toDriveFile()

        return when {
            // Local only
            localEntry != null && remoteEntry == null -> {
                when (options.mode) {
                    SyncMode.UPLOAD_ONLY,
                    SyncMode.BIDIRECTIONAL,
                    SyncMode.MIRROR_TO_CLOUD -> {
                        SyncItem(path, name, localInfo, null, SyncAction.UPLOAD)
                    }
                    SyncMode.MIRROR_FROM_CLOUD -> {
                        if (options.allowDeletions) {
                            SyncItem(path, name, localInfo, null, SyncAction.DELETE_LOCAL)
                        } else {
                            SyncItem(path, name, localInfo, null, SyncAction.SKIP)
                        }
                    }
                    SyncMode.DOWNLOAD_ONLY -> {
                        SyncItem(path, name, localInfo, null, SyncAction.SKIP)
                    }
                }
            }

            // Remote only
            localEntry == null && remoteEntry != null -> {
                when (options.mode) {
                    SyncMode.DOWNLOAD_ONLY,
                    SyncMode.BIDIRECTIONAL,
                    SyncMode.MIRROR_FROM_CLOUD -> {
                        SyncItem(path, name, null, remoteInfo, SyncAction.DOWNLOAD)
                    }
                    SyncMode.MIRROR_TO_CLOUD -> {
                        if (options.allowDeletions) {
                            SyncItem(path, name, null, remoteInfo, SyncAction.DELETE_REMOTE)
                        } else {
                            SyncItem(path, name, null, remoteInfo, SyncAction.SKIP)
                        }
                    }
                    SyncMode.UPLOAD_ONLY -> {
                        SyncItem(path, name, null, remoteInfo, SyncAction.SKIP)
                    }
                }
            }

            // Both exist
            localEntry != null && remoteEntry != null -> {
                // Check if files are identical
                val checksumMatch = localEntry.checksum != null &&
                    remoteEntry.checksum != null &&
                    localEntry.checksum.equals(remoteEntry.checksum, ignoreCase = true)

                if (checksumMatch) {
                    // Files are identical
                    SyncItem(path, name, localInfo, remoteInfo, SyncAction.SKIP)
                } else {
                    // Files differ - conflict
                    val conflict = conflictResolver.createConflictInfo(
                        relativePath = path,
                        localChecksum = localEntry.checksum,
                        remoteChecksum = remoteEntry.checksum,
                        localModifiedTime = localEntry.modifiedTime,
                        remoteModifiedTime = remoteEntry.modifiedTime,
                        localSize = localEntry.size,
                        remoteSize = remoteEntry.size
                    )

                    // Resolve conflict based on policy
                    val resolution = conflictResolver.resolve(conflict, options.conflictPolicy)
                    val action = when (resolution) {
                        is ConflictResolution.UseLocal -> SyncAction.UPLOAD
                        is ConflictResolution.UseRemote -> SyncAction.DOWNLOAD
                        is ConflictResolution.KeepBoth -> SyncAction.CONFLICT
                        is ConflictResolution.Skip -> SyncAction.SKIP
                        is ConflictResolution.DeleteBoth -> SyncAction.SKIP // Handle specially
                    }

                    SyncItem(path, name, localInfo, remoteInfo, action, conflict)
                }
            }

            // Neither exists (shouldn't happen)
            else -> {
                SyncItem(path, name, null, null, SyncAction.SKIP)
            }
        }
    }

    /**
     * Execute sync actions
     *
     * @param syncItems Items to sync
     * @param syncDirectory Local sync directory
     * @param rootFolderName Remote root folder name
     * @param options Sync options
     * @param retryPolicy Retry policy for operations
     * @return Sync result
     */
    suspend fun executeSyncPlan(
        syncItems: List<SyncItem>,
        syncDirectory: File,
        rootFolderName: String,
        options: SyncOptions,
        retryPolicy: RetryPolicy = RetryPolicy.DEFAULT
    ): SyncResult {
        val startTime = System.currentTimeMillis()
        var filesUploaded = 0
        var filesDownloaded = 0
        var filesDeleted = 0
        var filesSkipped = 0
        var bytesTransferred = 0L
        val errors = mutableListOf<SyncError>()
        val conflicts = mutableListOf<ConflictInfo>()

        // Get already synced files for resume support
        val alreadySynced = progressManager.getSyncedFiles()

        progressManager.startSync(syncItems.size)

        try {
            for (item in syncItems) {
                coroutineContext.ensureActive()

                // Skip if already synced (resume support)
                if (alreadySynced.contains(item.relativePath)) {
                    filesSkipped++
                    continue
                }

                try {
                    when (item.action) {
                        SyncAction.UPLOAD -> {
                            val localFile = File(syncDirectory, item.relativePath)
                            if (localFile.exists()) {
                                val result = withRetry(retryPolicy, "Upload ${item.name}") {
                                    driveService.uploadFile(localFile, item.relativePath, rootFolderName)
                                }
                                when (result) {
                                    is DriveOperationResult.Success -> {
                                        filesUploaded++
                                        bytesTransferred += localFile.length()
                                        progressManager.fileUploaded(item.relativePath, localFile.length())
                                    }
                                    else -> {
                                        errors.add(SyncError(item.relativePath, "Upload failed: $result"))
                                        progressManager.fileFailed(item.relativePath, "Upload failed")
                                    }
                                }
                            }
                        }

                        SyncAction.DOWNLOAD -> {
                            val remoteFile = item.remoteFile
                            if (remoteFile != null) {
                                val localFile = File(syncDirectory, item.relativePath)
                                val result = withRetry(retryPolicy, "Download ${item.name}") {
                                    driveService.downloadFile(remoteFile.id, localFile)
                                }
                                when (result) {
                                    is DriveOperationResult.Success -> {
                                        filesDownloaded++
                                        bytesTransferred += result.data.size
                                        progressManager.fileDownloaded(item.relativePath, result.data.size)

                                        // Verify checksum if enabled
                                        if (options.verifyChecksums && remoteFile.md5Checksum != null) {
                                            val localChecksum = fileHasher.calculateHash(localFile, ChecksumAlgorithm.MD5)
                                            if (!localChecksum.equals(remoteFile.md5Checksum, ignoreCase = true)) {
                                                Log.w(Constants.TAG, "Checksum mismatch after download: ${item.name}")
                                                errors.add(SyncError(item.relativePath, "Checksum verification failed"))
                                            }
                                        }
                                    }
                                    else -> {
                                        errors.add(SyncError(item.relativePath, "Download failed: $result"))
                                        progressManager.fileFailed(item.relativePath, "Download failed")
                                    }
                                }
                            }
                        }

                        SyncAction.DELETE_LOCAL -> {
                            val localFile = File(syncDirectory, item.relativePath)
                            if (localFile.delete()) {
                                filesDeleted++
                                progressManager.fileSkipped(item.relativePath)
                            }
                        }

                        SyncAction.DELETE_REMOTE -> {
                            val remoteFile = item.remoteFile
                            if (remoteFile != null) {
                                val result = driveService.deleteFile(remoteFile.id)
                                when (result) {
                                    is DriveOperationResult.Success -> {
                                        filesDeleted++
                                        progressManager.fileSkipped(item.relativePath)
                                    }
                                    else -> {
                                        errors.add(SyncError(item.relativePath, "Delete failed: $result"))
                                    }
                                }
                            }
                        }

                        SyncAction.CONFLICT -> {
                            item.conflict?.let { conflicts.add(it) }
                            // Handle KEEP_BOTH by uploading with conflict suffix
                            handleKeepBothConflict(item, syncDirectory, rootFolderName)
                            filesUploaded++
                        }

                        SyncAction.SKIP -> {
                            filesSkipped++
                            progressManager.fileSkipped(item.relativePath)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(Constants.TAG, "Error processing ${item.relativePath}", e)
                    errors.add(SyncError(item.relativePath, e.message ?: "Unknown error", e))
                    progressManager.fileFailed(item.relativePath, e.message)
                }
            }

            val duration = (System.currentTimeMillis() - startTime).milliseconds

            return if (errors.isEmpty()) {
                progressManager.completeSync()
                SyncResult.Success(
                    filesUploaded = filesUploaded,
                    filesDownloaded = filesDownloaded,
                    filesDeleted = filesDeleted,
                    filesSkipped = filesSkipped,
                    conflicts = conflicts,
                    duration = duration,
                    bytesTransferred = bytesTransferred
                )
            } else {
                progressManager.completeSync()
                SyncResult.PartialSuccess(
                    filesSucceeded = filesUploaded + filesDownloaded + filesDeleted,
                    filesFailed = errors.size,
                    errors = errors,
                    duration = duration
                )
            }
        } catch (e: CancellationException) {
            progressManager.cancelSync()
            throw e
        } catch (e: Exception) {
            progressManager.failSync(e.message ?: "Unknown error")
            return SyncResult.Error(e.message ?: "Sync failed", e)
        }
    }

    /**
     * Handle KEEP_BOTH conflict resolution
     */
    private suspend fun handleKeepBothConflict(
        item: SyncItem,
        syncDirectory: File,
        rootFolderName: String
    ) {
        val localFile = File(syncDirectory, item.relativePath)
        if (!localFile.exists()) return

        // Generate conflict filename
        val suffix = conflictResolver.generateConflictSuffix()
        val conflictName = conflictResolver.applyConflictSuffix(item.name, suffix)
        val conflictPath = item.relativePath.replace(item.name, conflictName)

        // Upload local file with conflict name
        driveService.uploadFile(localFile, conflictPath, rootFolderName)

        // Download remote file to original location
        item.remoteFile?.let { remote ->
            driveService.downloadFile(remote.id, localFile)
        }
    }

    /**
     * Log the sync plan for debugging
     */
    private fun logSyncPlan(items: List<SyncItem>) {
        val uploads = items.count { it.action == SyncAction.UPLOAD }
        val downloads = items.count { it.action == SyncAction.DOWNLOAD }
        val deleteLocal = items.count { it.action == SyncAction.DELETE_LOCAL }
        val deleteRemote = items.count { it.action == SyncAction.DELETE_REMOTE }
        val conflicts = items.count { it.action == SyncAction.CONFLICT }
        val skips = items.count { it.action == SyncAction.SKIP }

        Log.d(Constants.TAG, "Sync plan: uploads=$uploads, downloads=$downloads, " +
            "deleteLocal=$deleteLocal, deleteRemote=$deleteRemote, conflicts=$conflicts, skips=$skips")
    }

    // ========== Extension functions for conversion ==========

    private fun FileManifestEntry.toLocalFileInfo(): LocalFileInfo {
        return LocalFileInfo(
            path = relativePath,
            relativePath = relativePath,
            name = name,
            size = size,
            modifiedTime = modifiedTime,
            checksum = checksum,
            isDirectory = false
        )
    }

    private fun FileManifestEntry.toDriveFile(): DriveFile {
        return DriveFile(
            id = driveFileId ?: "",
            name = name,
            size = size,
            modifiedTime = modifiedTime,
            md5Checksum = checksum,
            mimeType = Constants.MIME_TYPE_OCTET_STREAM,
            parents = null
        )
    }
}
