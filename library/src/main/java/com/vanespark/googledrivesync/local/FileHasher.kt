package com.vanespark.googledrivesync.local

import android.util.Log
import com.vanespark.googledrivesync.util.Constants
import com.vanespark.googledrivesync.util.toHexString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Algorithm for calculating file checksums
 */
enum class ChecksumAlgorithm(val algorithmName: String) {
    MD5("MD5"),
    SHA256("SHA-256")
}

/**
 * Utility class for calculating file checksums.
 *
 * Supports MD5 and SHA-256 algorithms for detecting file changes.
 */
@Singleton
class FileHasher @Inject constructor() {

    /**
     * Calculate checksum of a file.
     *
     * @param file The file to hash
     * @param algorithm The hash algorithm to use
     * @return Hex string of the hash
     */
    suspend fun calculateHash(
        file: File,
        algorithm: ChecksumAlgorithm = ChecksumAlgorithm.MD5
    ): String = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance(algorithm.algorithmName)
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().toHexString()
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to calculate hash for ${file.name}", e)
            throw HashCalculationException("Failed to calculate hash: ${e.message}", e)
        }
    }

    /**
     * Calculate checksum of an input stream.
     *
     * Note: This consumes the stream.
     *
     * @param inputStream The stream to hash
     * @param algorithm The hash algorithm to use
     * @return Hex string of the hash
     */
    suspend fun calculateHash(
        inputStream: InputStream,
        algorithm: ChecksumAlgorithm = ChecksumAlgorithm.MD5
    ): String = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance(algorithm.algorithmName)
            inputStream.use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().toHexString()
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to calculate hash from stream", e)
            throw HashCalculationException("Failed to calculate hash: ${e.message}", e)
        }
    }

    /**
     * Calculate checksum of byte array.
     *
     * @param data The data to hash
     * @param algorithm The hash algorithm to use
     * @return Hex string of the hash
     */
    fun calculateHash(
        data: ByteArray,
        algorithm: ChecksumAlgorithm = ChecksumAlgorithm.MD5
    ): String {
        val digest = MessageDigest.getInstance(algorithm.algorithmName)
        return digest.digest(data).toHexString()
    }

    /**
     * Verify a file matches an expected checksum.
     *
     * @param file The file to verify
     * @param expectedChecksum The expected hash value
     * @param algorithm The hash algorithm to use
     * @return True if checksums match
     */
    suspend fun verifyChecksum(
        file: File,
        expectedChecksum: String,
        algorithm: ChecksumAlgorithm = ChecksumAlgorithm.MD5
    ): Boolean {
        val actualChecksum = calculateHash(file, algorithm)
        val matches = actualChecksum.equals(expectedChecksum, ignoreCase = true)

        if (!matches) {
            Log.w(
                Constants.TAG,
                "Checksum mismatch for ${file.name}: expected=$expectedChecksum, actual=$actualChecksum"
            )
        }

        return matches
    }

    /**
     * Create a hashed filename with checksum embedded.
     *
     * Format: {basename}_{hash}.{extension}
     * Example: document_abc123.pdf
     *
     * @param originalName The original filename
     * @param checksum The file checksum
     * @return Hashed filename
     */
    fun createHashedFilename(originalName: String, checksum: String): String {
        val lastDot = originalName.lastIndexOf('.')
        return if (lastDot > 0) {
            val basename = originalName.substring(0, lastDot)
            val extension = originalName.substring(lastDot)
            "${basename}_${checksum.take(HASH_PREFIX_LENGTH)}$extension"
        } else {
            "${originalName}_${checksum.take(HASH_PREFIX_LENGTH)}"
        }
    }

    /**
     * Extract the original filename from a hashed filename.
     *
     * @param hashedName The hashed filename
     * @return Original filename, or the input if not a valid hashed name
     */
    fun getOriginalFilename(hashedName: String): String {
        val lastDot = hashedName.lastIndexOf('.')
        val baseName = if (lastDot > 0) hashedName.substring(0, lastDot) else hashedName
        val extension = if (lastDot > 0) hashedName.substring(lastDot) else ""

        val lastUnderscore = baseName.lastIndexOf('_')
        if (lastUnderscore > 0) {
            val possibleHash = baseName.substring(lastUnderscore + 1)
            // Check if the suffix looks like a hash (hex characters, expected length)
            if (possibleHash.length == HASH_PREFIX_LENGTH && possibleHash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return baseName.substring(0, lastUnderscore) + extension
            }
        }

        return hashedName
    }

    /**
     * Extract the hash from a hashed filename.
     *
     * @param hashedName The hashed filename
     * @return The hash prefix, or null if not a valid hashed name
     */
    fun extractHash(hashedName: String): String? {
        val lastDot = hashedName.lastIndexOf('.')
        val baseName = if (lastDot > 0) hashedName.substring(0, lastDot) else hashedName

        val lastUnderscore = baseName.lastIndexOf('_')
        if (lastUnderscore > 0) {
            val possibleHash = baseName.substring(lastUnderscore + 1)
            if (possibleHash.length == HASH_PREFIX_LENGTH && possibleHash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return possibleHash
            }
        }

        return null
    }

    companion object {
        private const val BUFFER_SIZE = 8192
        private const val HASH_PREFIX_LENGTH = 8
    }
}

/**
 * Exception thrown when hash calculation fails
 */
class HashCalculationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
