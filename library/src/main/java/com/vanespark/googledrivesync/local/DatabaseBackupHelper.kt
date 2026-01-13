package com.vanespark.googledrivesync.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.vanespark.googledrivesync.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a database backup operation
 */
sealed class DatabaseBackupResult {
    /**
     * Backup succeeded
     */
    data class Success(
        val backupFile: File,
        val originalSize: Long,
        val backupSize: Long
    ) : DatabaseBackupResult()

    /**
     * Backup failed
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : DatabaseBackupResult()
}

/**
 * Result of a database integrity check
 */
data class IntegrityCheckResult(
    val isValid: Boolean,
    val message: String
)

/**
 * Helper for creating safe database backups.
 *
 * Provides utilities for:
 * - WAL checkpoint to merge write-ahead log
 * - VACUUM INTO to create isolated snapshot
 * - Database integrity verification
 * - Atomic database replacement
 */
@Singleton
class DatabaseBackupHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Create a self-contained database snapshot using VACUUM INTO.
     *
     * This creates a fully independent copy of the database that doesn't
     * depend on WAL files. Ideal for backup/sync operations.
     *
     * @param databasePath Path to the source database
     * @param destinationPath Path for the snapshot file
     * @return Backup result with file info
     */
    suspend fun createSnapshot(
        databasePath: String,
        destinationPath: String
    ): DatabaseBackupResult = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(databasePath)
            if (!sourceFile.exists()) {
                return@withContext DatabaseBackupResult.Error("Source database not found: $databasePath")
            }

            val destFile = File(destinationPath)
            destFile.parentFile?.mkdirs()

            // Delete existing destination if present
            if (destFile.exists()) {
                destFile.delete()
            }

            SQLiteDatabase.openDatabase(
                databasePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { db ->
                // First checkpoint WAL to ensure all data is in main db
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val blocked = cursor.getInt(0)
                        val walPages = cursor.getInt(1)
                        val checkpointed = cursor.getInt(2)
                        Log.d(
                            Constants.TAG,
                            "WAL checkpoint: blocked=$blocked, wal=$walPages, checkpointed=$checkpointed"
                        )
                    }
                }

                // Create isolated snapshot with VACUUM INTO
                db.execSQL("VACUUM INTO '$destinationPath'")
            }

            if (!destFile.exists()) {
                return@withContext DatabaseBackupResult.Error("Snapshot file was not created")
            }

            Log.d(
                Constants.TAG,
                "Database snapshot created: ${sourceFile.length()} -> ${destFile.length()} bytes"
            )

            DatabaseBackupResult.Success(
                backupFile = destFile,
                originalSize = sourceFile.length(),
                backupSize = destFile.length()
            )
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to create database snapshot", e)
            DatabaseBackupResult.Error("Snapshot creation failed: ${e.message}", e)
        }
    }

    /**
     * Checkpoint WAL (Write-Ahead Log) to merge pending changes.
     *
     * Call this before copying a database file to ensure all
     * changes are written to the main database file.
     *
     * @param databasePath Path to the database
     * @return True if checkpoint succeeded
     */
    suspend fun checkpointWal(databasePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            SQLiteDatabase.openDatabase(
                databasePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val blocked = cursor.getInt(0)
                        Log.d(Constants.TAG, "WAL checkpoint completed, blocked=$blocked")
                        return@withContext blocked == 0
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(Constants.TAG, "WAL checkpoint failed", e)
            false
        }
    }

    /**
     * Delete WAL and shared memory files for a database.
     *
     * Call this after restoring a database to prevent stale WAL reads.
     *
     * @param databasePath Path to the main database file
     */
    fun deleteWalFiles(databasePath: String) {
        val walFile = File("$databasePath-wal")
        val shmFile = File("$databasePath-shm")

        if (walFile.exists()) {
            walFile.delete()
            Log.d(Constants.TAG, "Deleted WAL file: ${walFile.name}")
        }
        if (shmFile.exists()) {
            shmFile.delete()
            Log.d(Constants.TAG, "Deleted SHM file: ${shmFile.name}")
        }
    }

    /**
     * Check database integrity using PRAGMA integrity_check.
     *
     * @param databasePath Path to the database
     * @return Integrity check result
     */
    suspend fun checkIntegrity(databasePath: String): IntegrityCheckResult =
        withContext(Dispatchers.IO) {
            try {
                val dbFile = File(databasePath)
                if (!dbFile.exists()) {
                    return@withContext IntegrityCheckResult(false, "Database file not found")
                }

                SQLiteDatabase.openDatabase(
                    databasePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY
                ).use { db ->
                    db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val result = cursor.getString(0)
                            val isOk = result.equals("ok", ignoreCase = true)
                            Log.d(Constants.TAG, "Integrity check result: $result")
                            return@withContext IntegrityCheckResult(isOk, result)
                        }
                    }
                }

                IntegrityCheckResult(false, "No result from integrity check")
            } catch (e: Exception) {
                Log.e(Constants.TAG, "Integrity check failed", e)
                IntegrityCheckResult(false, "Check failed: ${e.message}")
            }
        }

    /**
     * Atomically replace a database file with a new version.
     *
     * Creates a backup of the existing database, then replaces it.
     * Rolls back if the new database fails integrity check.
     *
     * @param existingPath Path to the existing database
     * @param newPath Path to the new database file
     * @param keepBackup Whether to keep backup after successful replace
     * @return True if replacement succeeded
     */
    suspend fun atomicReplace(
        existingPath: String,
        newPath: String,
        keepBackup: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val existingFile = File(existingPath)
        val newFile = File(newPath)
        val backupFile = File("$existingPath.backup")

        try {
            // Verify new database
            val integrityResult = checkIntegrity(newPath)
            if (!integrityResult.isValid) {
                Log.e(Constants.TAG, "New database failed integrity check: ${integrityResult.message}")
                return@withContext false
            }

            // Backup existing if it exists
            if (existingFile.exists()) {
                deleteWalFiles(existingPath)
                existingFile.copyTo(backupFile, overwrite = true)
                existingFile.delete()
            }

            // Copy new database
            newFile.copyTo(existingFile, overwrite = true)
            deleteWalFiles(existingPath)

            // Verify the replacement
            val verifyResult = checkIntegrity(existingPath)
            if (!verifyResult.isValid) {
                // Rollback
                Log.e(Constants.TAG, "Replacement failed verification, rolling back")
                if (backupFile.exists()) {
                    backupFile.copyTo(existingFile, overwrite = true)
                }
                return@withContext false
            }

            // Cleanup
            if (!keepBackup && backupFile.exists()) {
                backupFile.delete()
            }

            Log.d(Constants.TAG, "Database replaced successfully: $existingPath")
            true
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Atomic replace failed", e)

            // Attempt rollback
            try {
                if (backupFile.exists() && !existingFile.exists()) {
                    backupFile.copyTo(existingFile, overwrite = true)
                }
            } catch (rollbackError: Exception) {
                Log.e(Constants.TAG, "Rollback also failed", rollbackError)
            }

            false
        }
    }

    /**
     * Get the application's default database directory.
     */
    fun getDatabaseDirectory(): File {
        return context.getDatabasePath("temp").parentFile ?: context.filesDir
    }

    /**
     * Create a temporary file for database operations.
     *
     * @param prefix Filename prefix
     * @param suffix Filename suffix (e.g., ".db")
     * @return Temporary file in cache directory
     */
    fun createTempFile(prefix: String, suffix: String): File {
        val cacheDir = File(context.cacheDir, "db_temp")
        cacheDir.mkdirs()
        return File.createTempFile(prefix, suffix, cacheDir)
    }

    /**
     * Clean up temporary database files.
     */
    fun cleanupTempFiles() {
        val cacheDir = File(context.cacheDir, "db_temp")
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { file ->
                file.delete()
            }
        }
    }
}
