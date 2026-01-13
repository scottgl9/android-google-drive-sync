package com.vanespark.googledrivesync.backup

import android.content.Context
import com.vanespark.googledrivesync.crypto.CorruptedFileException
import com.vanespark.googledrivesync.crypto.EncryptionManager
import com.vanespark.googledrivesync.crypto.EncryptionType
import com.vanespark.googledrivesync.crypto.WrongPassphraseException
import com.vanespark.googledrivesync.local.FileHasher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages restoration of backup archives.
 *
 * Features:
 * - Auto-detects encryption type
 * - Passphrase and device keystore decryption
 * - Manifest validation
 * - Checksum verification
 * - Safety backup before restore
 * - Automatic rollback on failure
 *
 * Restore process:
 * 1. Copy backup to temp location (if from external source)
 * 2. Detect and decrypt if encrypted
 * 3. Extract and validate manifest
 * 4. Create safety backup of current data
 * 5. Restore files
 * 6. Verify checksums
 * 7. Rollback on failure
 */
@Singleton
class RestoreManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionManager: EncryptionManager,
    private val fileHasher: FileHasher
) {

    companion object {
        private const val MANIFEST_FILENAME = "manifest.json"
        private const val FILES_DIRECTORY = "files/"
        private const val BUFFER_SIZE = 8192
        private const val SAFETY_BACKUP_PREFIX = "safety-backup-"
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /**
     * Restore a backup to the sync directory.
     *
     * @param backupFile Backup file to restore
     * @param syncDirectory Target sync directory
     * @param passphrase Passphrase for encrypted backups (optional)
     * @param config Restore configuration
     * @return Restore result
     */
    suspend fun restoreBackup(
        backupFile: File,
        syncDirectory: File,
        passphrase: String? = null,
        config: RestoreConfig = RestoreConfig()
    ): RestoreResult = withContext(Dispatchers.IO) {
        var safetyBackup: File? = null
        var tempDecryptedFile: File? = null

        try {
            // Validate backup file
            if (!backupFile.exists()) {
                return@withContext RestoreResult.Error("Backup file does not exist")
            }

            // Detect encryption type
            val encryptionType = encryptionManager.detectEncryptionType(backupFile)

            // Decrypt if needed
            val zipFile = when (encryptionType) {
                EncryptionType.NONE -> backupFile
                EncryptionType.PASSPHRASE -> {
                    if (passphrase == null) {
                        return@withContext RestoreResult.Error(
                            "Passphrase required for encrypted backup",
                            isPassphraseRequired = true
                        )
                    }
                    tempDecryptedFile = File(context.cacheDir, "restore_temp_${System.currentTimeMillis()}.zip")
                    try {
                        encryptionManager.decryptFile(backupFile, tempDecryptedFile!!, passphrase)
                    } catch (e: WrongPassphraseException) {
                        return@withContext RestoreResult.Error(
                            "Incorrect passphrase",
                            isPassphraseRequired = true,
                            cause = e
                        )
                    }
                    tempDecryptedFile
                }
                EncryptionType.DEVICE_KEYSTORE -> {
                    tempDecryptedFile = File(context.cacheDir, "restore_temp_${System.currentTimeMillis()}.zip")
                    encryptionManager.decryptFile(backupFile, tempDecryptedFile!!, null)
                    tempDecryptedFile
                }
            }

            // Read and validate manifest
            val manifest = readManifest(zipFile!!)
                ?: return@withContext RestoreResult.Error("Invalid backup: manifest not found")

            // Create safety backup if enabled
            if (config.createSafetyBackup && syncDirectory.exists() && syncDirectory.listFiles()?.isNotEmpty() == true) {
                safetyBackup = createSafetyBackup(syncDirectory)
            }

            // Clear target directory if configured
            if (config.clearBeforeRestore && syncDirectory.exists()) {
                syncDirectory.deleteRecursively()
            }
            syncDirectory.mkdirs()

            // Extract files
            val extractResult = extractFiles(zipFile, syncDirectory, manifest, config)

            // Verify checksums if enabled
            val verificationResult = if (config.verifyChecksums) {
                verifyChecksums(syncDirectory, manifest)
            } else {
                null
            }

            if (verificationResult != null && verificationResult.failedFiles.isNotEmpty()) {
                if (config.rollbackOnFailure && safetyBackup != null) {
                    // Rollback
                    syncDirectory.deleteRecursively()
                    safetyBackup.copyRecursively(syncDirectory, overwrite = true)
                    return@withContext RestoreResult.Error(
                        "Checksum verification failed for ${verificationResult.failedFiles.size} files. Rolled back to previous state."
                    )
                }
            }

            // Cleanup safety backup on success if configured
            if (config.deleteSafetyBackupOnSuccess) {
                safetyBackup?.deleteRecursively()
            }

            return@withContext RestoreResult.Success(
                RestoreInfo(
                    manifest = manifest,
                    filesRestored = extractResult.filesRestored,
                    bytesRestored = extractResult.bytesRestored,
                    checksumVerified = config.verifyChecksums,
                    safetyBackup = if (config.deleteSafetyBackupOnSuccess) null else safetyBackup
                )
            )
        } catch (e: CorruptedFileException) {
            return@withContext RestoreResult.Error("Backup file is corrupted: ${e.message}", cause = e)
        } catch (e: Exception) {
            // Attempt rollback on unexpected error
            if (config.rollbackOnFailure && safetyBackup != null && syncDirectory.exists()) {
                try {
                    syncDirectory.deleteRecursively()
                    safetyBackup.copyRecursively(syncDirectory, overwrite = true)
                } catch (rollbackError: Exception) {
                    // Rollback failed
                }
            }
            return@withContext RestoreResult.Error("Restore failed: ${e.message}", cause = e)
        } finally {
            // Cleanup temp files
            tempDecryptedFile?.delete()
        }
    }

    /**
     * Read manifest from a backup file without full restore.
     *
     * @param backupFile Backup file (must be decrypted ZIP)
     * @return Manifest or null if invalid
     */
    fun readManifest(backupFile: File): BackupManifest? {
        return try {
            ZipFile(backupFile).use { zip ->
                val manifestEntry = zip.getEntry(MANIFEST_FILENAME)
                    ?: return null

                val manifestJson = zip.getInputStream(manifestEntry).bufferedReader().readText()
                json.decodeFromString<BackupManifest>(manifestJson)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Peek at a backup to get basic info without restoring.
     *
     * @param backupFile Backup file
     * @param passphrase Passphrase for encrypted backups (optional)
     * @return Backup info or error
     */
    suspend fun peekBackup(
        backupFile: File,
        passphrase: String? = null
    ): PeekResult = withContext(Dispatchers.IO) {
        var tempFile: File? = null

        try {
            val encryptionType = encryptionManager.detectEncryptionType(backupFile)

            val zipFile = when (encryptionType) {
                EncryptionType.NONE -> backupFile
                EncryptionType.PASSPHRASE -> {
                    if (passphrase == null) {
                        return@withContext PeekResult.NeedsPassphrase
                    }
                    tempFile = File(context.cacheDir, "peek_temp_${System.currentTimeMillis()}.zip")
                    try {
                        encryptionManager.decryptFile(backupFile, tempFile!!, passphrase)
                    } catch (e: WrongPassphraseException) {
                        return@withContext PeekResult.WrongPassphrase
                    }
                    tempFile
                }
                EncryptionType.DEVICE_KEYSTORE -> {
                    tempFile = File(context.cacheDir, "peek_temp_${System.currentTimeMillis()}.zip")
                    encryptionManager.decryptFile(backupFile, tempFile!!, null)
                    tempFile
                }
            }

            val manifest = readManifest(zipFile!!)
                ?: return@withContext PeekResult.InvalidBackup("Manifest not found")

            return@withContext PeekResult.Success(
                BackupPeekInfo(
                    manifest = manifest,
                    encryptionType = encryptionType,
                    fileSize = backupFile.length()
                )
            )
        } catch (e: Exception) {
            return@withContext PeekResult.InvalidBackup(e.message ?: "Unknown error")
        } finally {
            tempFile?.delete()
        }
    }

    // ========== Private Methods ==========

    private fun createSafetyBackup(syncDirectory: File): File {
        val safetyDir = File(context.cacheDir, "$SAFETY_BACKUP_PREFIX${System.currentTimeMillis()}")
        syncDirectory.copyRecursively(safetyDir, overwrite = true)
        return safetyDir
    }

    private fun extractFiles(
        zipFile: File,
        targetDir: File,
        manifest: BackupManifest,
        @Suppress("UNUSED_PARAMETER") config: RestoreConfig
    ): ExtractResult {
        var filesRestored = 0
        var bytesRestored = 0L

        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(FILES_DIRECTORY) }
                .forEach { entry ->
                    val relativePath = entry.name.removePrefix(FILES_DIRECTORY)
                    val targetFile = File(targetDir, relativePath)

                    // Ensure parent directory exists
                    targetFile.parentFile?.mkdirs()

                    // Extract file
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var len: Int
                            while (input.read(buffer).also { len = it } > 0) {
                                output.write(buffer, 0, len)
                                bytesRestored += len
                            }
                        }
                    }

                    // Restore modification time if available
                    manifest.files.find { it.path == relativePath }?.let { fileEntry ->
                        targetFile.setLastModified(fileEntry.modifiedTime)
                    }

                    filesRestored++
                }
        }

        return ExtractResult(filesRestored, bytesRestored)
    }

    private suspend fun verifyChecksums(
        targetDir: File,
        manifest: BackupManifest
    ): VerificationResult {
        val failedFiles = mutableListOf<String>()
        var verifiedCount = 0

        manifest.files.forEach { fileEntry ->
            if (fileEntry.checksum == null) return@forEach

            val file = File(targetDir, fileEntry.path)
            if (!file.exists()) {
                failedFiles.add(fileEntry.path)
                return@forEach
            }

            val actualChecksum = fileHasher.calculateHash(file)
            if (actualChecksum != fileEntry.checksum) {
                failedFiles.add(fileEntry.path)
            } else {
                verifiedCount++
            }
        }

        return VerificationResult(verifiedCount, failedFiles)
    }

    private data class ExtractResult(
        val filesRestored: Int,
        val bytesRestored: Long
    )

    private data class VerificationResult(
        val verifiedCount: Int,
        val failedFiles: List<String>
    )
}

/**
 * Restore configuration
 */
data class RestoreConfig(
    /**
     * Create a safety backup before restore
     */
    val createSafetyBackup: Boolean = true,

    /**
     * Clear target directory before restore
     */
    val clearBeforeRestore: Boolean = true,

    /**
     * Verify file checksums after restore
     */
    val verifyChecksums: Boolean = true,

    /**
     * Rollback to safety backup on failure
     */
    val rollbackOnFailure: Boolean = true,

    /**
     * Delete safety backup after successful restore
     */
    val deleteSafetyBackupOnSuccess: Boolean = true
)

/**
 * Restore result sealed class
 */
sealed class RestoreResult {
    data class Success(val info: RestoreInfo) : RestoreResult()
    data class Error(
        val message: String,
        val isPassphraseRequired: Boolean = false,
        val cause: Throwable? = null
    ) : RestoreResult()
}

/**
 * Information about a completed restore
 */
data class RestoreInfo(
    val manifest: BackupManifest,
    val filesRestored: Int,
    val bytesRestored: Long,
    val checksumVerified: Boolean,
    val safetyBackup: File?
)

/**
 * Peek result sealed class
 */
sealed class PeekResult {
    data class Success(val info: BackupPeekInfo) : PeekResult()
    object NeedsPassphrase : PeekResult()
    object WrongPassphrase : PeekResult()
    data class InvalidBackup(val reason: String) : PeekResult()
}

/**
 * Basic backup info from peeking
 */
data class BackupPeekInfo(
    val manifest: BackupManifest,
    val encryptionType: EncryptionType,
    val fileSize: Long
)
