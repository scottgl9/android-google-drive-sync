package com.vanespark.googledrivesync.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Extension functions for the Google Drive Sync library
 */

// ========== Duration Extensions ==========

val Int.seconds: Duration get() = this.toLong().seconds
val Int.minutes: Duration get() = this.toLong().minutes
val Int.hours: Duration get() = this.toLong().hours

val Long.milliseconds: Duration get() = this.milliseconds

// ========== File Size Extensions ==========

val Int.bytes: Long get() = this.toLong()
val Int.kilobytes: Long get() = this * 1024L
val Int.megabytes: Long get() = this * 1024L * 1024L
val Int.gigabytes: Long get() = this * 1024L * 1024L * 1024L

val Long.bytes: Long get() = this
val Long.kilobytes: Long get() = this * 1024L
val Long.megabytes: Long get() = this * 1024L * 1024L
val Long.gigabytes: Long get() = this * 1024L * 1024L * 1024L

// ========== String Extensions ==========

/**
 * Truncate string to specified length with ellipsis
 */
fun String.truncate(maxLength: Int, ellipsis: String = "..."): String {
    return if (length <= maxLength) this
    else take(maxLength - ellipsis.length) + ellipsis
}

/**
 * Get file extension from filename
 */
fun String.fileExtension(): String? {
    val lastDot = lastIndexOf('.')
    return if (lastDot > 0 && lastDot < length - 1) substring(lastDot + 1) else null
}

/**
 * Get filename without extension
 */
fun String.fileNameWithoutExtension(): String {
    val lastDot = lastIndexOf('.')
    return if (lastDot > 0) substring(0, lastDot) else this
}

// ========== File Extensions ==========

/**
 * Calculate MD5 hash of file contents
 */
fun File.md5Hash(): String {
    val digest = MessageDigest.getInstance("MD5")
    inputStream().use { input ->
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().toHexString()
}

/**
 * Calculate SHA-256 hash of file contents
 */
fun File.sha256Hash(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().toHexString()
}

/**
 * Get human-readable file size
 */
fun File.humanReadableSize(): String = length().humanReadableSize()

/**
 * Check if file is hidden (starts with dot)
 */
fun File.isHiddenFile(): Boolean = name.startsWith(".")

// ========== Long Extensions ==========

/**
 * Convert bytes to human-readable size string
 */
fun Long.humanReadableSize(): String {
    if (this < 1024) return "$this B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = this.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}

// ========== ByteArray Extensions ==========

/**
 * Convert byte array to hex string
 */
fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

// ========== InputStream Extensions ==========

/**
 * Calculate MD5 hash of input stream contents
 */
fun InputStream.md5Hash(): String {
    val digest = MessageDigest.getInstance("MD5")
    val buffer = ByteArray(8192)
    var bytesRead: Int
    while (read(buffer).also { bytesRead = it } != -1) {
        digest.update(buffer, 0, bytesRead)
    }
    return digest.digest().toHexString()
}

// ========== Result Extensions ==========

/**
 * Map success value of Result
 */
inline fun <T, R> Result<T>.mapSuccess(transform: (T) -> R): Result<R> {
    return when {
        isSuccess -> Result.success(transform(getOrThrow()))
        else -> Result.failure(exceptionOrNull()!!)
    }
}

/**
 * Recover from failure with a default value
 */
inline fun <T> Result<T>.recoverWith(recovery: (Throwable) -> T): T {
    return getOrElse { recovery(it) }
}
