package com.vanespark.googledrivesync.cache

import android.content.Context
import android.util.Log
import com.vanespark.googledrivesync.sync.FileManifest
import com.vanespark.googledrivesync.sync.FileManifestEntry
import com.vanespark.googledrivesync.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configuration for the sync cache
 */
data class CacheConfig(
    /**
     * Maximum age of cache entries
     */
    val maxAgeMs: Long = Constants.DEFAULT_CACHE_MAX_AGE_MS,

    /**
     * Maximum number of entries
     */
    val maxEntries: Int = Constants.DEFAULT_CACHE_MAX_ENTRIES,

    /**
     * Whether caching is enabled
     */
    val enabled: Boolean = true
)

/**
 * Serializable cache entry
 */
@Serializable
private data class CachedManifest(
    val entries: Map<String, CachedEntry>,
    val createdAtMs: Long
)

@Serializable
private data class CachedEntry(
    val relativePath: String,
    val name: String,
    val size: Long,
    val modifiedTimeMs: Long,
    val checksum: String?
)

/**
 * Cache for sync metadata to reduce API calls and improve performance.
 *
 * Caches:
 * - File manifests (local and remote)
 * - Checksums
 * - Folder IDs
 */
@Singleton
class SyncCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cacheDir = File(context.cacheDir, "sync_cache")
    private val json = Json { ignoreUnknownKeys = true }

    private var config = CacheConfig()
    private var localManifestCache: FileManifest? = null
    private var remoteManifestCache: FileManifest? = null
    private var localManifestTimestamp: Long = 0
    private var remoteManifestTimestamp: Long = 0

    init {
        cacheDir.mkdirs()
    }

    /**
     * Configure the cache
     */
    fun configure(config: CacheConfig) {
        this.config = config
    }

    /**
     * Get cached local manifest if still valid
     */
    fun getLocalManifest(): FileManifest? {
        if (!config.enabled) return null
        if (isExpired(localManifestTimestamp)) return null
        return localManifestCache
    }

    /**
     * Cache local manifest
     */
    fun setLocalManifest(manifest: FileManifest) {
        if (!config.enabled) return
        localManifestCache = manifest
        localManifestTimestamp = System.currentTimeMillis()
    }

    /**
     * Get cached remote manifest if still valid
     */
    fun getRemoteManifest(): FileManifest? {
        if (!config.enabled) return null
        if (isExpired(remoteManifestTimestamp)) return null
        return remoteManifestCache
    }

    /**
     * Cache remote manifest
     */
    fun setRemoteManifest(manifest: FileManifest) {
        if (!config.enabled) return
        remoteManifestCache = manifest
        remoteManifestTimestamp = System.currentTimeMillis()
    }

    /**
     * Invalidate local manifest cache
     */
    fun invalidateLocalManifest() {
        localManifestCache = null
        localManifestTimestamp = 0
    }

    /**
     * Invalidate remote manifest cache
     */
    fun invalidateRemoteManifest() {
        remoteManifestCache = null
        remoteManifestTimestamp = 0
    }

    /**
     * Invalidate all caches
     */
    fun invalidateAll() {
        invalidateLocalManifest()
        invalidateRemoteManifest()
        clearDiskCache()
    }

    /**
     * Save manifest to disk for persistence across app restarts
     */
    suspend fun saveManifestToDisk(manifest: FileManifest, isRemote: Boolean) = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext

        try {
            val cached = manifest.toCached()
            val jsonString = json.encodeToString(cached)
            val fileName = if (isRemote) "remote_manifest.json" else "local_manifest.json"
            File(cacheDir, fileName).writeText(jsonString)
            Log.d(Constants.TAG, "Saved manifest to disk: $fileName")
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to save manifest to disk", e)
        }
    }

    /**
     * Load manifest from disk
     */
    suspend fun loadManifestFromDisk(isRemote: Boolean): FileManifest? = withContext(Dispatchers.IO) {
        if (!config.enabled) return@withContext null

        try {
            val fileName = if (isRemote) "remote_manifest.json" else "local_manifest.json"
            val file = File(cacheDir, fileName)
            if (!file.exists()) return@withContext null

            val jsonString = file.readText()
            val cached = json.decodeFromString<CachedManifest>(jsonString)

            // Check if cache is expired
            if (isExpired(cached.createdAtMs)) {
                file.delete()
                return@withContext null
            }

            cached.toManifest()
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to load manifest from disk", e)
            null
        }
    }

    /**
     * Clear disk cache
     */
    fun clearDiskCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(Constants.TAG, "Disk cache cleared")
    }

    /**
     * Check if a cached timestamp is expired
     */
    private fun isExpired(timestamp: Long): Boolean {
        if (timestamp == 0L) return true
        val age = System.currentTimeMillis() - timestamp
        return age > config.maxAgeMs
    }

    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats {
        val diskFiles = cacheDir.listFiles()?.size ?: 0
        val diskSize = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

        return CacheStats(
            localManifestCached = localManifestCache != null,
            remoteManifestCached = remoteManifestCache != null,
            diskFileCount = diskFiles,
            diskSizeBytes = diskSize
        )
    }

    // ========== Conversion helpers ==========

    private fun FileManifest.toCached(): CachedManifest {
        return CachedManifest(
            entries = files.mapValues { (_, entry) ->
                CachedEntry(
                    relativePath = entry.relativePath,
                    name = entry.name,
                    size = entry.size,
                    modifiedTimeMs = entry.modifiedTime.toEpochMilli(),
                    checksum = entry.checksum
                )
            },
            createdAtMs = createdAt.toEpochMilli()
        )
    }

    private fun CachedManifest.toManifest(): FileManifest {
        return FileManifest(
            files = entries.mapValues { (_, cached) ->
                FileManifestEntry(
                    relativePath = cached.relativePath,
                    name = cached.name,
                    size = cached.size,
                    modifiedTime = Instant.ofEpochMilli(cached.modifiedTimeMs),
                    checksum = cached.checksum
                )
            },
            createdAt = Instant.ofEpochMilli(createdAtMs)
        )
    }
}

/**
 * Cache statistics
 */
data class CacheStats(
    val localManifestCached: Boolean,
    val remoteManifestCached: Boolean,
    val diskFileCount: Int,
    val diskSizeBytes: Long
)
