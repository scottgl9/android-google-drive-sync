package com.vanespark.googledrivesync.compression

import android.util.Log
import com.vanespark.googledrivesync.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Compression level settings for file compression.
 */
enum class CompressionLevel(val value: Int) {
    /** No compression, store only */
    NONE(0),
    /** Fast compression with lower ratio */
    FAST(1),
    /** Balanced speed and compression */
    DEFAULT(6),
    /** Best compression, slower */
    BEST(9)
}

/**
 * Result of a compression operation.
 */
data class CompressionResult(
    /** The compressed file */
    val compressedFile: File,
    /** Original file size in bytes */
    val originalSize: Long,
    /** Compressed file size in bytes */
    val compressedSize: Long,
    /** Compression ratio (0.0 to 1.0, lower is better) */
    val compressionRatio: Double,
    /** Duration of compression in milliseconds */
    val durationMs: Long
) {
    /** Bytes saved by compression */
    val bytesSaved: Long get() = originalSize - compressedSize

    /** Percentage of space saved */
    val percentSaved: Double get() = if (originalSize > 0) {
        (1.0 - compressionRatio) * 100.0
    } else {
        0.0
    }
}

/**
 * Result of a decompression operation.
 */
data class DecompressionResult(
    /** The decompressed file */
    val decompressedFile: File,
    /** Compressed file size in bytes */
    val compressedSize: Long,
    /** Decompressed file size in bytes */
    val decompressedSize: Long,
    /** Duration of decompression in milliseconds */
    val durationMs: Long
)

/**
 * Configuration for compression operations.
 */
data class CompressionConfig(
    /** Compression level to use */
    val level: CompressionLevel = CompressionLevel.DEFAULT,
    /** Minimum file size to compress (smaller files may not benefit) */
    val minSizeToCompress: Long = 1024L,
    /** File extensions that should not be compressed (already compressed formats) */
    val skipExtensions: Set<String> = DEFAULT_SKIP_EXTENSIONS,
    /** Buffer size for I/O operations */
    val bufferSize: Int = 8192
) {
    companion object {
        /** Default extensions to skip (already compressed or incompressible) */
        val DEFAULT_SKIP_EXTENSIONS = setOf(
            "zip", "gz", "gzip", "7z", "rar", "tar.gz", "tgz",
            "jpg", "jpeg", "png", "gif", "webp", "bmp",
            "mp3", "mp4", "avi", "mkv", "mov", "webm",
            "pdf", "docx", "xlsx", "pptx"
        )
    }
}

/**
 * Manages file compression and decompression using GZIP.
 *
 * Features:
 * - GZIP compression for single files
 * - Configurable compression levels
 * - Skip already-compressed file formats
 * - Progress tracking via callbacks
 */
@Singleton
class CompressionManager @Inject constructor() {

    private var config = CompressionConfig()

    /**
     * Configure compression settings.
     */
    fun configure(config: CompressionConfig) {
        this.config = config
    }

    /**
     * Check if a file should be compressed based on configuration.
     *
     * @param file The file to check
     * @return True if the file should be compressed
     */
    fun shouldCompress(file: File): Boolean {
        // Check minimum size
        if (file.length() < config.minSizeToCompress) {
            return false
        }

        // Check extension
        val extension = file.extension.lowercase()
        if (extension in config.skipExtensions) {
            return false
        }

        return true
    }

    /**
     * Compress a file using GZIP.
     *
     * @param inputFile The file to compress
     * @param outputFile The destination for compressed data (defaults to inputFile.gz)
     * @param onProgress Optional callback for progress updates (0.0 to 1.0)
     * @return Compression result with statistics
     */
    suspend fun compress(
        inputFile: File,
        outputFile: File = File(inputFile.parentFile, "${inputFile.name}.gz"),
        onProgress: ((Float) -> Unit)? = null
    ): CompressionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val originalSize = inputFile.length()

        Log.d(Constants.TAG, "Compressing ${inputFile.name} (${originalSize} bytes)")

        FileInputStream(inputFile).use { fis ->
            FileOutputStream(outputFile).use { fos ->
                GZIPOutputStream(fos, config.bufferSize).use { gzos ->
                    val buffer = ByteArray(config.bufferSize)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        gzos.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        onProgress?.invoke((totalRead.toFloat() / originalSize))
                    }
                }
            }
        }

        val compressedSize = outputFile.length()
        val duration = System.currentTimeMillis() - startTime
        val ratio = if (originalSize > 0) {
            compressedSize.toDouble() / originalSize
        } else {
            1.0
        }

        val percentSaved = String.format(java.util.Locale.US, "%.1f", (1 - ratio) * 100)
        Log.d(
            Constants.TAG,
            "Compressed ${inputFile.name}: $originalSize -> $compressedSize bytes " +
                "(${percentSaved}% saved) in ${duration}ms"
        )

        CompressionResult(
            compressedFile = outputFile,
            originalSize = originalSize,
            compressedSize = compressedSize,
            compressionRatio = ratio,
            durationMs = duration
        )
    }

    /**
     * Decompress a GZIP file.
     *
     * @param inputFile The compressed file
     * @param outputFile The destination for decompressed data
     * @param onProgress Optional callback for progress updates (0.0 to 1.0)
     * @return Decompression result with statistics
     */
    suspend fun decompress(
        inputFile: File,
        outputFile: File,
        onProgress: ((Float) -> Unit)? = null
    ): DecompressionResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val compressedSize = inputFile.length()

        Log.d(Constants.TAG, "Decompressing ${inputFile.name} (${compressedSize} bytes)")

        // Ensure output directory exists
        outputFile.parentFile?.mkdirs()

        FileInputStream(inputFile).use { fis ->
            GZIPInputStream(fis, config.bufferSize).use { gzis ->
                FileOutputStream(outputFile).use { fos ->
                    val buffer = ByteArray(config.bufferSize)
                    var bytesRead: Int
                    var totalWritten = 0L

                    while (gzis.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                        totalWritten += bytesRead

                        // Estimate progress based on compressed bytes read
                        val estimatedProgress = (fis.channel.position().toFloat() / compressedSize)
                            .coerceIn(0f, 1f)
                        onProgress?.invoke(estimatedProgress)
                    }
                }
            }
        }

        val decompressedSize = outputFile.length()
        val duration = System.currentTimeMillis() - startTime

        Log.d(
            Constants.TAG,
            "Decompressed ${inputFile.name}: $compressedSize -> $decompressedSize bytes in ${duration}ms"
        )

        DecompressionResult(
            decompressedFile = outputFile,
            compressedSize = compressedSize,
            decompressedSize = decompressedSize,
            durationMs = duration
        )
    }

    /**
     * Compress a file only if compression would be beneficial.
     * Returns the original file if compression doesn't reduce size.
     *
     * @param inputFile The file to potentially compress
     * @param tempDir Directory for temporary compressed file
     * @return Pair of (file to use, wasCompressed)
     */
    suspend fun compressIfBeneficial(
        inputFile: File,
        tempDir: File
    ): Pair<File, Boolean> = withContext(Dispatchers.IO) {
        if (!shouldCompress(inputFile)) {
            return@withContext inputFile to false
        }

        val tempCompressed = File(tempDir, "${inputFile.name}.gz")
        tempDir.mkdirs()

        try {
            val result = compress(inputFile, tempCompressed)

            // Only use compressed version if it's actually smaller
            if (result.compressedSize < result.originalSize) {
                tempCompressed to true
            } else {
                // Compression didn't help, delete temp file
                tempCompressed.delete()
                inputFile to false
            }
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Compression failed for ${inputFile.name}, using original", e)
            tempCompressed.delete()
            inputFile to false
        }
    }

    /**
     * Check if a file appears to be GZIP compressed.
     *
     * @param file The file to check
     * @return True if the file has GZIP magic bytes
     */
    fun isGzipCompressed(file: File): Boolean {
        if (!file.exists() || file.length() < 2) return false

        return try {
            FileInputStream(file).use { fis ->
                val magic = ByteArray(2)
                if (fis.read(magic) == 2) {
                    // GZIP magic number: 1f 8b
                    magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(Constants.TAG, "Error checking GZIP magic bytes for ${file.name}", e)
            false
        }
    }
}
