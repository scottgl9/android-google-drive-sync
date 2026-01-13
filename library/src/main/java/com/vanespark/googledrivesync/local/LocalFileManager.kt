package com.vanespark.googledrivesync.local

import android.content.Context
import android.util.Log
import com.vanespark.googledrivesync.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Information about a local file
 */
data class LocalFileInfo(
    /**
     * Absolute file path
     */
    val path: String,

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
     * Last modified timestamp
     */
    val modifiedTime: Instant,

    /**
     * File checksum (computed lazily)
     */
    val checksum: String? = null,

    /**
     * Whether this is a directory
     */
    val isDirectory: Boolean
)

/**
 * Manages local file operations for sync.
 *
 * This class provides methods for:
 * - Listing files in directories
 * - Reading and writing files
 * - Managing sync directories
 * - Computing file metadata
 */
@Singleton
class LocalFileManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileHasher: FileHasher
) {
    /**
     * Get the app's files directory
     */
    val filesDir: File get() = context.filesDir

    /**
     * Get the app's cache directory
     */
    val cacheDir: File get() = context.cacheDir

    /**
     * List all files in a directory recursively.
     *
     * @param directory The directory to list
     * @param filter Optional filter for files
     * @param recursive Whether to include subdirectories
     * @return List of file info objects
     */
    suspend fun listFiles(
        directory: File,
        filter: FileFilter = FileFilter.AcceptAll,
        recursive: Boolean = true
    ): List<LocalFileInfo> = withContext(Dispatchers.IO) {
        if (!directory.exists() || !directory.isDirectory) {
            return@withContext emptyList()
        }

        val basePath = directory.absolutePath
        val files = mutableListOf<LocalFileInfo>()

        fun processDirectory(dir: File) {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    if (recursive && filter.accept(file)) {
                        processDirectory(file)
                    }
                } else if (filter.accept(file)) {
                    files.add(
                        LocalFileInfo(
                            path = file.absolutePath,
                            relativePath = file.absolutePath.removePrefix(basePath).removePrefix("/"),
                            name = file.name,
                            size = file.length(),
                            modifiedTime = Instant.ofEpochMilli(file.lastModified()),
                            isDirectory = false
                        )
                    )
                }
            }
        }

        processDirectory(directory)
        files
    }

    /**
     * List files with checksums computed.
     *
     * @param directory The directory to list
     * @param filter Optional filter for files
     * @param algorithm Checksum algorithm to use
     * @return List of file info with checksums
     */
    suspend fun listFilesWithChecksums(
        directory: File,
        filter: FileFilter = FileFilter.AcceptAll,
        algorithm: ChecksumAlgorithm = ChecksumAlgorithm.MD5
    ): List<LocalFileInfo> = withContext(Dispatchers.IO) {
        val files = listFiles(directory, filter)

        files.map { fileInfo ->
            val file = File(fileInfo.path)
            val checksum = try {
                fileHasher.calculateHash(file, algorithm)
            } catch (e: Exception) {
                Log.w(Constants.TAG, "Failed to calculate checksum for ${fileInfo.name}", e)
                null
            }
            fileInfo.copy(checksum = checksum)
        }
    }

    /**
     * Get information about a single file.
     *
     * @param file The file to get info for
     * @param basePath Optional base path for relative path calculation
     * @param includeChecksum Whether to compute checksum
     * @param algorithm Checksum algorithm to use
     * @return File info, or null if file doesn't exist
     */
    suspend fun getFileInfo(
        file: File,
        basePath: File? = null,
        includeChecksum: Boolean = false,
        algorithm: ChecksumAlgorithm = ChecksumAlgorithm.MD5
    ): LocalFileInfo? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null

        val relativePath = basePath?.let {
            file.absolutePath.removePrefix(it.absolutePath).removePrefix("/")
        } ?: file.name

        val checksum = if (includeChecksum && file.isFile) {
            try {
                fileHasher.calculateHash(file, algorithm)
            } catch (e: Exception) {
                Log.w(Constants.TAG, "Failed to calculate checksum for ${file.name}", e)
                null
            }
        } else null

        LocalFileInfo(
            path = file.absolutePath,
            relativePath = relativePath,
            name = file.name,
            size = if (file.isFile) file.length() else 0L,
            modifiedTime = Instant.ofEpochMilli(file.lastModified()),
            checksum = checksum,
            isDirectory = file.isDirectory
        )
    }

    /**
     * Ensure a directory exists.
     *
     * @param directory The directory to create
     * @return True if directory exists or was created
     */
    fun ensureDirectory(directory: File): Boolean {
        return if (directory.exists()) {
            directory.isDirectory
        } else {
            directory.mkdirs()
        }
    }

    /**
     * Ensure a directory path exists relative to a base.
     *
     * @param basePath The base directory
     * @param relativePath The relative path to create
     * @return The created directory, or null if failed
     */
    fun ensureDirectoryPath(basePath: File, relativePath: String): File? {
        val directory = File(basePath, relativePath)
        return if (ensureDirectory(directory)) directory else null
    }

    /**
     * Copy a file to a destination.
     *
     * @param source The source file
     * @param destination The destination file
     * @return True if copy succeeded
     */
    suspend fun copyFile(source: File, destination: File): Boolean = withContext(Dispatchers.IO) {
        try {
            destination.parentFile?.mkdirs()
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to copy ${source.name} to ${destination.path}", e)
            false
        }
    }

    /**
     * Write data to a file.
     *
     * @param file The file to write to
     * @param data The data to write
     * @return True if write succeeded
     */
    suspend fun writeFile(file: File, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { output ->
                output.write(data)
            }
            true
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to write to ${file.path}", e)
            false
        }
    }

    /**
     * Write stream to a file.
     *
     * @param file The file to write to
     * @param inputStream The input stream to write
     * @return True if write succeeded
     */
    suspend fun writeFile(file: File, inputStream: InputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { output ->
                inputStream.copyTo(output)
            }
            true
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to write stream to ${file.path}", e)
            false
        }
    }

    /**
     * Read a file as bytes.
     *
     * @param file The file to read
     * @return File contents, or null if read failed
     */
    suspend fun readFile(file: File): ByteArray? = withContext(Dispatchers.IO) {
        try {
            file.readBytes()
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to read ${file.path}", e)
            null
        }
    }

    /**
     * Delete a file or directory recursively.
     *
     * @param file The file or directory to delete
     * @return True if deletion succeeded
     */
    suspend fun delete(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to delete ${file.path}", e)
            false
        }
    }

    /**
     * Create a temporary file.
     *
     * @param prefix The filename prefix
     * @param suffix The filename suffix
     * @return The created temporary file
     */
    fun createTempFile(prefix: String, suffix: String): File {
        return File.createTempFile(prefix, suffix, cacheDir)
    }

    /**
     * Create a temporary directory.
     *
     * @param prefix The directory name prefix
     * @return The created temporary directory
     */
    fun createTempDirectory(prefix: String): File {
        val tempDir = File(cacheDir, "${prefix}_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        return tempDir
    }

    /**
     * Get the total size of files in a directory.
     *
     * @param directory The directory to measure
     * @param filter Optional filter for files
     * @return Total size in bytes
     */
    suspend fun getDirectorySize(
        directory: File,
        filter: FileFilter = FileFilter.AcceptAll
    ): Long = withContext(Dispatchers.IO) {
        listFiles(directory, filter).sumOf { it.size }
    }

    /**
     * Get the count of files in a directory.
     *
     * @param directory The directory to count
     * @param filter Optional filter for files
     * @return File count
     */
    suspend fun getFileCount(
        directory: File,
        filter: FileFilter = FileFilter.AcceptAll
    ): Int = withContext(Dispatchers.IO) {
        listFiles(directory, filter).size
    }

    /**
     * Clean old files from a directory.
     *
     * @param directory The directory to clean
     * @param maxAgeMs Maximum age in milliseconds
     * @return Number of files deleted
     */
    suspend fun cleanOldFiles(
        directory: File,
        maxAgeMs: Long
    ): Int = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - maxAgeMs
        var deletedCount = 0

        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                if (delete(file)) {
                    deletedCount++
                }
            }
        }

        Log.d(Constants.TAG, "Cleaned $deletedCount old files from ${directory.name}")
        deletedCount
    }
}
