package com.vanespark.googledrivesync.sync

import android.util.Log
import com.vanespark.googledrivesync.util.Constants
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Callback for user-driven conflict resolution
 */
typealias ConflictCallback = suspend (ConflictInfo) -> ConflictResolution

/**
 * Resolves conflicts between local and remote files.
 */
@Singleton
class ConflictResolver @Inject constructor() {

    private var userCallback: ConflictCallback? = null

    /**
     * Set the callback for ASK_USER conflict policy
     */
    fun setUserCallback(callback: ConflictCallback?) {
        userCallback = callback
    }

    /**
     * Resolve a conflict using the specified policy
     *
     * @param conflict The conflict to resolve
     * @param policy The policy to apply
     * @return The resolution to apply
     */
    suspend fun resolve(
        conflict: ConflictInfo,
        policy: ConflictPolicy
    ): ConflictResolution {
        Log.d(Constants.TAG, "Resolving conflict for ${conflict.relativePath} with policy $policy")

        return when (policy) {
            ConflictPolicy.LOCAL_WINS -> {
                Log.d(Constants.TAG, "Conflict resolved: LOCAL_WINS")
                ConflictResolution.UseLocal
            }

            ConflictPolicy.REMOTE_WINS -> {
                Log.d(Constants.TAG, "Conflict resolved: REMOTE_WINS")
                ConflictResolution.UseRemote
            }

            ConflictPolicy.NEWER_WINS -> {
                resolveByTimestamp(conflict)
            }

            ConflictPolicy.KEEP_BOTH -> {
                val suffix = "_conflict_${System.currentTimeMillis()}"
                Log.d(Constants.TAG, "Conflict resolved: KEEP_BOTH with suffix $suffix")
                ConflictResolution.KeepBoth(suffix)
            }

            ConflictPolicy.SKIP -> {
                Log.d(Constants.TAG, "Conflict resolved: SKIP")
                ConflictResolution.Skip
            }

            ConflictPolicy.ASK_USER -> {
                resolveByUser(conflict)
            }
        }
    }

    /**
     * Resolve conflict by comparing timestamps
     */
    private fun resolveByTimestamp(conflict: ConflictInfo): ConflictResolution {
        val localTime = conflict.localModifiedTime
        val remoteTime = conflict.remoteModifiedTime

        return when {
            localTime == null && remoteTime == null -> {
                // Both times unknown, default to remote
                Log.d(Constants.TAG, "Both timestamps unknown, using remote")
                ConflictResolution.UseRemote
            }
            localTime == null -> {
                Log.d(Constants.TAG, "Local timestamp unknown, using remote")
                ConflictResolution.UseRemote
            }
            remoteTime == null -> {
                Log.d(Constants.TAG, "Remote timestamp unknown, using local")
                ConflictResolution.UseLocal
            }
            localTime.isAfter(remoteTime) -> {
                Log.d(Constants.TAG, "Local is newer ($localTime > $remoteTime), using local")
                ConflictResolution.UseLocal
            }
            remoteTime.isAfter(localTime) -> {
                Log.d(Constants.TAG, "Remote is newer ($remoteTime > $localTime), using remote")
                ConflictResolution.UseRemote
            }
            else -> {
                // Same timestamp, compare by size as tiebreaker
                if (conflict.localSize >= conflict.remoteSize) {
                    Log.d(Constants.TAG, "Same timestamp, local size >= remote, using local")
                    ConflictResolution.UseLocal
                } else {
                    Log.d(Constants.TAG, "Same timestamp, remote size > local, using remote")
                    ConflictResolution.UseRemote
                }
            }
        }
    }

    /**
     * Resolve conflict by asking user
     */
    private suspend fun resolveByUser(conflict: ConflictInfo): ConflictResolution {
        val callback = userCallback
        return if (callback != null) {
            Log.d(Constants.TAG, "Asking user to resolve conflict for ${conflict.relativePath}")
            callback(conflict)
        } else {
            Log.w(Constants.TAG, "No user callback set, falling back to NEWER_WINS")
            resolveByTimestamp(conflict)
        }
    }

    /**
     * Determine if two files are in conflict
     *
     * @param localChecksum Local file checksum
     * @param remoteChecksum Remote file checksum
     * @param localModified Local modification time
     * @param remoteModified Remote modification time
     * @return True if files are in conflict
     */
    fun isConflict(
        localChecksum: String?,
        remoteChecksum: String?,
        @Suppress("UNUSED_PARAMETER") localModified: Instant?,
        @Suppress("UNUSED_PARAMETER") remoteModified: Instant?
    ): Boolean {
        // If checksums match, no conflict
        if (localChecksum != null && remoteChecksum != null &&
            localChecksum.equals(remoteChecksum, ignoreCase = true)
        ) {
            return false
        }

        // Different checksums = conflict
        return true
    }

    /**
     * Create conflict info from local and remote file data
     */
    fun createConflictInfo(
        relativePath: String,
        localChecksum: String?,
        remoteChecksum: String?,
        localModifiedTime: Instant?,
        remoteModifiedTime: Instant?,
        localSize: Long,
        remoteSize: Long
    ): ConflictInfo {
        return ConflictInfo(
            relativePath = relativePath,
            localChecksum = localChecksum,
            remoteChecksum = remoteChecksum,
            localModifiedTime = localModifiedTime,
            remoteModifiedTime = remoteModifiedTime,
            localSize = localSize,
            remoteSize = remoteSize
        )
    }

    /**
     * Generate a conflict suffix for keeping both files
     */
    fun generateConflictSuffix(): String {
        return "_conflict_${System.currentTimeMillis()}"
    }

    /**
     * Apply conflict suffix to a filename
     *
     * @param filename Original filename
     * @param suffix Conflict suffix
     * @return Filename with conflict suffix
     */
    fun applyConflictSuffix(filename: String, suffix: String): String {
        val lastDot = filename.lastIndexOf('.')
        return if (lastDot > 0) {
            val name = filename.substring(0, lastDot)
            val ext = filename.substring(lastDot)
            "$name$suffix$ext"
        } else {
            "$filename$suffix"
        }
    }
}
