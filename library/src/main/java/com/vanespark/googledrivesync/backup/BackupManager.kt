package com.vanespark.googledrivesync.backup

import android.content.Context
import com.vanespark.googledrivesync.crypto.EncryptionConfig
import com.vanespark.googledrivesync.crypto.EncryptionManager
import com.vanespark.googledrivesync.local.FileHasher
import com.vanespark.googledrivesync.local.LocalFileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages creation of backup archives.
 *
 * Features:
 * - ZIP archive format with manifest
 * - Optional encryption (passphrase or device keystore)
 * - Checksum verification
 * - Pre-flight checks (disk space, permissions)
 *
 * Backup archive structure:
 * ```
 * backup.zip
 * ├── manifest.json          # Backup metadata
 * └── files/                  # Synced files
 *     ├── document.txt
 *     ├── image.png
 *     └── subfolder/
 *         └── data.json
 * ```
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionManager: EncryptionManager,
    private val fileHasher: FileHasher,
    private val localFileManager: LocalFileManager
) {

    companion object {
        private const val MANIFEST_FILENAME = "manifest.json"
        private const val FILES_DIRECTORY = "files/"
        private const val BACKUP_PREFIX = "sync-backup"
        private const val BACKUP_EXTENSION = ".zip"
        private const val ENCRYPTED_EXTENSION = ".zip.enc"
        private const val MIN_DISK_SPACE_MB = 100
        private const val BUFFER_SIZE = 8192

        // Manifest version
        private const val MANIFEST_VERSION = 1
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Create a backup of the sync directory.
     *
     * @param syncDirectory Directory to back up
     * @param outputFile Output file for the backup (or null for default location)
     * @param config Backup configuration
     * @return Result with backup info or error
     */
    suspend fun createBackup(
        syncDirectory: File,
        outputFile: File? = null,
        config: BackupConfig = BackupConfig()
    ): BackupResult {
        // Pre-flight checks
        val preflightResult = performPreflightChecks(syncDirectory)
        if (preflightResult != null) {
            return preflightResult
        }

        // Determine output file
        val backupFile = outputFile ?: generateBackupFile(config.encryption)
        val tempFile = File(context.cacheDir, "backup_temp_${System.currentTimeMillis()}.zip")

        try {
            // Collect files to backup
            val filesToBackup = collectFiles(syncDirectory, config.fileFilter)
            if (filesToBackup.isEmpty() && !config.allowEmptyBackup) {
                return BackupResult.Error("No files to backup")
            }

            // Create manifest
            val manifest = createManifest(syncDirectory, filesToBackup, config)

            // Create ZIP archive
            createZipArchive(tempFile, syncDirectory, filesToBackup, manifest)

            // Apply encryption if configured
            when (val encryption = config.encryption) {
                is EncryptionConfig.None -> {
                    tempFile.copyTo(backupFile, overwrite = true)
                }
                is EncryptionConfig.DeviceKeystore,
                is EncryptionConfig.Passphrase -> {
                    encryptionManager.encryptFile(tempFile, backupFile, encryption)
                }
            }

            // Calculate final checksum
            val checksum = fileHasher.calculateHash(backupFile)

            return BackupResult.Success(
                BackupInfo(
                    file = backupFile,
                    manifest = manifest,
                    checksum = checksum,
                    sizeBytes = backupFile.length(),
                    encrypted = config.encryption !is EncryptionConfig.None
                )
            )
        } catch (e: Exception) {
            return BackupResult.Error("Backup failed: ${e.message}", e)
        } finally {
            // Cleanup temp file
            tempFile.delete()
        }
    }

    /**
     * Generate a default backup filename.
     *
     * @param encryption Encryption config (affects extension)
     * @return Backup file in app's backup directory
     */
    fun generateBackupFile(encryption: EncryptionConfig = EncryptionConfig.None): File {
        val backupDir = File(context.filesDir, "backups")
        backupDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val extension = if (encryption is EncryptionConfig.None) {
            BACKUP_EXTENSION
        } else {
            ENCRYPTED_EXTENSION
        }

        return File(backupDir, "$BACKUP_PREFIX-$timestamp$extension")
    }

    /**
     * List existing backup files.
     *
     * @param backupDirectory Directory to search (or null for default)
     * @return List of backup files sorted by date (newest first)
     */
    fun listBackups(backupDirectory: File? = null): List<File> {
        val dir = backupDirectory ?: File(context.filesDir, "backups")
        if (!dir.exists()) return emptyList()

        return dir.listFiles { file ->
            file.isFile && (
                file.name.endsWith(BACKUP_EXTENSION) ||
                file.name.endsWith(ENCRYPTED_EXTENSION)
            )
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * Delete old backups, keeping only the most recent ones.
     *
     * @param keepCount Number of backups to keep
     * @param backupDirectory Directory to clean (or null for default)
     * @return Number of backups deleted
     */
    fun cleanupOldBackups(keepCount: Int = 5, backupDirectory: File? = null): Int {
        val backups = listBackups(backupDirectory)
        if (backups.size <= keepCount) return 0

        var deleted = 0
        backups.drop(keepCount).forEach { file ->
            if (file.delete()) deleted++
        }
        return deleted
    }

    /**
     * Get disk space required for backup (estimated).
     *
     * @param syncDirectory Directory to backup
     * @return Estimated backup size in bytes
     */
    suspend fun estimateBackupSize(syncDirectory: File): Long {
        return localFileManager.getDirectorySize(syncDirectory)
    }

    /**
     * Check if there's enough disk space for backup.
     *
     * @param syncDirectory Directory to backup
     * @return true if sufficient space available
     */
    suspend fun hasSufficientDiskSpace(syncDirectory: File): Boolean {
        val requiredSpace = estimateBackupSize(syncDirectory) + (MIN_DISK_SPACE_MB * 1024 * 1024)
        val availableSpace = context.filesDir.freeSpace
        return availableSpace >= requiredSpace
    }

    // ========== Private Methods ==========

    private suspend fun performPreflightChecks(syncDirectory: File): BackupResult.Error? {
        if (!syncDirectory.exists()) {
            return BackupResult.Error("Sync directory does not exist")
        }
        if (!syncDirectory.isDirectory) {
            return BackupResult.Error("Sync path is not a directory")
        }
        if (!hasSufficientDiskSpace(syncDirectory)) {
            return BackupResult.Error("Insufficient disk space for backup")
        }
        return null
    }

    private fun collectFiles(
        syncDirectory: File,
        filter: ((File) -> Boolean)?
    ): List<File> {
        val files = mutableListOf<File>()

        syncDirectory.walkTopDown()
            .filter { it.isFile }
            .filter { filter?.invoke(it) ?: true }
            .forEach { files.add(it) }

        return files
    }

    private suspend fun createManifest(
        syncDirectory: File,
        files: List<File>,
        config: BackupConfig
    ): BackupManifest {
        val fileEntries = files.map { file ->
            val relativePath = file.relativeTo(syncDirectory).path
            BackupFileEntry(
                path = relativePath,
                size = file.length(),
                checksum = if (config.includeChecksums) fileHasher.calculateHash(file) else null,
                modifiedTime = file.lastModified()
            )
        }

        return BackupManifest(
            version = MANIFEST_VERSION,
            createdAt = System.currentTimeMillis(),
            appVersion = config.appVersion,
            fileCount = files.size,
            totalSize = files.sumOf { it.length() },
            encrypted = config.encryption !is EncryptionConfig.None,
            encryptionType = when (config.encryption) {
                is EncryptionConfig.None -> null
                is EncryptionConfig.DeviceKeystore -> "device"
                is EncryptionConfig.Passphrase -> "passphrase"
            },
            files = fileEntries
        )
    }

    private fun createZipArchive(
        zipFile: File,
        syncDirectory: File,
        files: List<File>,
        manifest: BackupManifest
    ) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
            // Add manifest
            val manifestJson = json.encodeToString(manifest)
            zipOut.putNextEntry(ZipEntry(MANIFEST_FILENAME))
            zipOut.write(manifestJson.toByteArray())
            zipOut.closeEntry()

            // Add files
            val buffer = ByteArray(BUFFER_SIZE)
            files.forEach { file ->
                val relativePath = file.relativeTo(syncDirectory).path
                val entryName = FILES_DIRECTORY + relativePath

                zipOut.putNextEntry(ZipEntry(entryName))
                FileInputStream(file).use { input ->
                    var len: Int
                    while (input.read(buffer).also { len = it } > 0) {
                        zipOut.write(buffer, 0, len)
                    }
                }
                zipOut.closeEntry()
            }
        }
    }
}

/**
 * Backup configuration
 */
data class BackupConfig(
    /**
     * Encryption configuration
     */
    val encryption: EncryptionConfig = EncryptionConfig.None,

    /**
     * Include file checksums in manifest
     */
    val includeChecksums: Boolean = true,

    /**
     * Allow creating empty backups
     */
    val allowEmptyBackup: Boolean = false,

    /**
     * Custom file filter (return true to include)
     */
    val fileFilter: ((File) -> Boolean)? = null,

    /**
     * App version to include in manifest
     */
    val appVersion: String? = null
)

/**
 * Backup result sealed class
 */
sealed class BackupResult {
    data class Success(val info: BackupInfo) : BackupResult()
    data class Error(val message: String, val cause: Throwable? = null) : BackupResult()
}

/**
 * Information about a created backup
 */
data class BackupInfo(
    val file: File,
    val manifest: BackupManifest,
    val checksum: String,
    val sizeBytes: Long,
    val encrypted: Boolean
)

/**
 * Backup manifest (stored in ZIP as manifest.json)
 */
@Serializable
data class BackupManifest(
    val version: Int,
    val createdAt: Long,
    val appVersion: String?,
    val fileCount: Int,
    val totalSize: Long,
    val encrypted: Boolean,
    val encryptionType: String?,
    val files: List<BackupFileEntry>
)

/**
 * File entry in backup manifest
 */
@Serializable
data class BackupFileEntry(
    val path: String,
    val size: Long,
    val checksum: String?,
    val modifiedTime: Long
)
