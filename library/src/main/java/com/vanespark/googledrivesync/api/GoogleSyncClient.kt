package com.vanespark.googledrivesync.api

import android.content.Intent
import com.vanespark.googledrivesync.auth.AuthResult
import com.vanespark.googledrivesync.auth.AuthState
import com.vanespark.googledrivesync.auth.GoogleAuthManager
import com.vanespark.googledrivesync.backup.BackupConfig
import com.vanespark.googledrivesync.backup.BackupManager
import com.vanespark.googledrivesync.backup.BackupResult
import com.vanespark.googledrivesync.backup.PeekResult
import com.vanespark.googledrivesync.backup.RestoreConfig
import com.vanespark.googledrivesync.backup.RestoreManager
import com.vanespark.googledrivesync.backup.RestoreResult
import com.vanespark.googledrivesync.crypto.EncryptionConfig
import com.vanespark.googledrivesync.crypto.EncryptionManager
import com.vanespark.googledrivesync.crypto.EncryptionType
import com.vanespark.googledrivesync.crypto.PassphraseStrength
import com.vanespark.googledrivesync.local.FileFilter
import com.vanespark.googledrivesync.resilience.NetworkPolicy
import com.vanespark.googledrivesync.resilience.SyncProgress
import com.vanespark.googledrivesync.sync.ConflictCallback
import com.vanespark.googledrivesync.sync.ConflictPolicy
import com.vanespark.googledrivesync.sync.ConflictResolver
import com.vanespark.googledrivesync.sync.SyncConfiguration
import com.vanespark.googledrivesync.sync.SyncManager
import com.vanespark.googledrivesync.sync.SyncMode
import com.vanespark.googledrivesync.sync.SyncOptions
import com.vanespark.googledrivesync.sync.SyncResult
import com.vanespark.googledrivesync.worker.SyncScheduleConfig
import com.vanespark.googledrivesync.worker.SyncScheduler
import com.vanespark.googledrivesync.worker.SyncWorkStatus
import com.vanespark.googledrivesync.worker.SyncWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * Main entry point for the Google Drive Sync library.
 *
 * Provides a high-level API for:
 * - Authentication (sign-in/sign-out)
 * - Manual sync operations
 * - Background sync scheduling
 * - Progress monitoring
 *
 * Example usage:
 * ```kotlin
 * @Inject lateinit var syncClient: GoogleSyncClient
 *
 * // Configure
 * syncClient.configure {
 *     rootFolderName("MyApp")
 *     syncDirectory(context.filesDir)
 *     conflictPolicy(ConflictPolicy.NEWER_WINS)
 *     networkPolicy(NetworkPolicy.UNMETERED_ONLY)
 * }
 *
 * // Sign in
 * val signInIntent = syncClient.getSignInIntent()
 * startActivityForResult(signInIntent, RC_SIGN_IN)
 *
 * // In onActivityResult
 * val result = syncClient.handleSignInResult(data)
 *
 * // Sync
 * val syncResult = syncClient.sync()
 *
 * // Schedule periodic sync
 * syncClient.schedulePeriodicSync(interval = 12.hours)
 * ```
 */
@Singleton
class GoogleSyncClient @Inject constructor(
    private val authManager: GoogleAuthManager,
    private val syncManager: SyncManager,
    private val syncScheduler: SyncScheduler,
    private val conflictResolver: ConflictResolver,
    private val backupManager: BackupManager,
    private val restoreManager: RestoreManager,
    private val encryptionManager: EncryptionManager
) {
    private var isConfigured = false

    // ========== Configuration ==========

    /**
     * Configure the sync client.
     *
     * Must be called before any sync operations.
     *
     * @param block Configuration builder
     */
    fun configure(block: SyncClientConfigBuilder.() -> Unit) {
        val builder = SyncClientConfigBuilder()
        builder.block()
        val config = builder.build()

        syncManager.configure(config)
        isConfigured = true
    }

    /**
     * Check if the client is configured.
     */
    fun isConfigured(): Boolean = isConfigured

    // ========== Authentication ==========

    /**
     * Observable authentication state.
     */
    val authState: StateFlow<AuthState> = authManager.authState

    /**
     * Check if user is signed in.
     */
    suspend fun isSignedIn(): Boolean = authManager.isSignedIn()

    /**
     * Get the sign-in intent to launch Google Sign-In.
     *
     * @return Intent to start with startActivityForResult
     */
    fun getSignInIntent(): Intent = authManager.getSignInIntent()

    /**
     * Handle the result from the sign-in activity.
     *
     * Call this from onActivityResult.
     *
     * @param data The intent data from onActivityResult
     * @return Authentication result
     */
    suspend fun handleSignInResult(data: Intent?): AuthResult =
        authManager.handleSignInResult(data)

    /**
     * Sign out the current user.
     */
    suspend fun signOut() {
        authManager.signOut()
        syncScheduler.cancelAllSync()
    }

    /**
     * Revoke access and disconnect.
     */
    suspend fun revokeAccess() {
        authManager.revokeAccess()
        syncScheduler.cancelAllSync()
    }

    /**
     * Get the signed-in user's email.
     */
    suspend fun getSignedInEmail(): String? = authManager.getSignedInAccount()?.email

    // ========== Sync Operations ==========

    /**
     * Observable sync progress.
     */
    val syncProgress: StateFlow<SyncProgress> = syncManager.progress

    /**
     * Whether a sync is currently in progress.
     */
    val isSyncing: StateFlow<Boolean> = syncManager.isSyncing

    /**
     * Perform a bidirectional sync.
     *
     * @return Sync result
     */
    suspend fun sync(): SyncResult {
        checkConfigured()
        return syncManager.sync()
    }

    /**
     * Perform a sync with custom options.
     *
     * @param options Sync options
     * @return Sync result
     */
    suspend fun sync(options: SyncOptions): SyncResult {
        checkConfigured()
        return syncManager.sync(options)
    }

    /**
     * Upload local changes to cloud.
     */
    suspend fun uploadOnly(): SyncResult {
        checkConfigured()
        return syncManager.uploadOnly()
    }

    /**
     * Download cloud changes to local.
     */
    suspend fun downloadOnly(): SyncResult {
        checkConfigured()
        return syncManager.downloadOnly()
    }

    /**
     * Mirror local to cloud (make cloud match local exactly).
     *
     * Warning: This will delete files on cloud that don't exist locally.
     */
    suspend fun mirrorToCloud(): SyncResult {
        checkConfigured()
        return syncManager.mirrorToCloud()
    }

    /**
     * Mirror cloud to local (make local match cloud exactly).
     *
     * Warning: This will delete local files that don't exist on cloud.
     */
    suspend fun mirrorFromCloud(): SyncResult {
        checkConfigured()
        return syncManager.mirrorFromCloud()
    }

    /**
     * Cancel the current sync operation.
     */
    fun cancelSync() = syncManager.cancel()

    /**
     * Set callback for user-driven conflict resolution.
     *
     * Used when conflict policy is ASK_USER.
     */
    fun setConflictCallback(callback: ConflictCallback?) {
        conflictResolver.setUserCallback(callback)
    }

    /**
     * Get the time of the last successful sync.
     */
    fun getLastSyncTime(): Long? = syncManager.getLastSyncTime()

    // ========== Resume Capability ==========

    /**
     * Check if there is a resumable sync in progress.
     */
    fun hasResumableSync(): Boolean = syncManager.hasResumableSync()

    /**
     * Clear any resumable sync state.
     */
    fun clearResumableSync() = syncManager.clearResumableSync()

    // ========== Sync History ==========

    /**
     * Observable sync history.
     */
    val syncHistory = syncManager.history

    /**
     * Get sync statistics.
     */
    fun getSyncStatistics() = syncManager.getStatistics()

    /**
     * Clear sync history.
     */
    fun clearSyncHistory() = syncManager.clearHistory()

    // ========== Background Sync ==========

    /**
     * Schedule periodic background sync.
     *
     * @param interval Time between syncs
     * @param networkPolicy Network requirements
     * @param requiresCharging Only sync while charging
     * @param syncMode Sync mode to use
     */
    fun schedulePeriodicSync(
        interval: Duration = DEFAULT_SYNC_INTERVAL,
        networkPolicy: NetworkPolicy = NetworkPolicy.UNMETERED_ONLY,
        requiresCharging: Boolean = false,
        syncMode: SyncMode = SyncMode.BIDIRECTIONAL
    ) {
        checkConfigured()
        syncScheduler.schedulePeriodicSync(
            SyncScheduleConfig(
                interval = interval,
                networkPolicy = networkPolicy,
                requiresCharging = requiresCharging,
                syncMode = syncMode.toWorkerMode()
            )
        )
    }

    /**
     * Cancel scheduled periodic sync.
     */
    fun cancelPeriodicSync() = syncScheduler.cancelPeriodicSync()

    /**
     * Check if periodic sync is scheduled.
     */
    fun isPeriodicSyncScheduled(): Boolean = syncScheduler.isPeriodicSyncScheduled()

    /**
     * Request an immediate one-time sync.
     *
     * Sync will run when constraints are met.
     *
     * @param syncMode Sync mode to use
     * @param networkPolicy Network requirements
     */
    fun requestSync(
        syncMode: SyncMode = SyncMode.BIDIRECTIONAL,
        networkPolicy: NetworkPolicy = NetworkPolicy.ANY
    ) {
        checkConfigured()
        syncScheduler.requestOneTimeSync(
            syncMode = syncMode.toWorkerMode(),
            networkPolicy = networkPolicy
        )
    }

    /**
     * Cancel pending one-time sync request.
     */
    fun cancelSyncRequest() = syncScheduler.cancelOneTimeSync()

    /**
     * Observe status of periodic sync work.
     */
    fun observePeriodicSyncStatus(): Flow<SyncWorkStatus> =
        syncScheduler.observePeriodicSyncStatus()

    /**
     * Observe status of one-time sync work.
     */
    fun observeSyncRequestStatus(): Flow<SyncWorkStatus> =
        syncScheduler.observeOneTimeSyncStatus()

    // ========== Backup & Restore ==========

    /**
     * Create a backup of the sync directory.
     *
     * @param outputFile Output file for the backup (or null for default location)
     * @param passphrase Passphrase for encryption (null for no encryption, or use device keystore)
     * @param useDeviceKeystore Use device keystore encryption (device-specific, not portable)
     * @return Backup result with info or error
     */
    suspend fun createBackup(
        outputFile: File? = null,
        passphrase: String? = null,
        useDeviceKeystore: Boolean = false
    ): BackupResult {
        checkConfigured()
        val syncDirectory = syncManager.getSyncDirectory()
            ?: return BackupResult.Error("Sync directory not configured")

        val encryption = when {
            passphrase != null -> EncryptionConfig.passphrase(passphrase)
            useDeviceKeystore -> EncryptionConfig.deviceKeystore()
            else -> EncryptionConfig.none()
        }

        val config = BackupConfig(
            encryption = encryption,
            includeChecksums = true,
            allowEmptyBackup = false
        )

        return backupManager.createBackup(syncDirectory, outputFile, config)
    }

    /**
     * Create a backup with custom configuration.
     *
     * @param config Backup configuration
     * @param outputFile Output file for the backup (or null for default location)
     * @return Backup result with info or error
     */
    suspend fun createBackup(
        config: BackupConfig,
        outputFile: File? = null
    ): BackupResult {
        checkConfigured()
        val syncDirectory = syncManager.getSyncDirectory()
            ?: return BackupResult.Error("Sync directory not configured")

        return backupManager.createBackup(syncDirectory, outputFile, config)
    }

    /**
     * Restore a backup to the sync directory.
     *
     * @param backupFile Backup file to restore
     * @param passphrase Passphrase for encrypted backups (optional)
     * @return Restore result with info or error
     */
    suspend fun restoreBackup(
        backupFile: File,
        passphrase: String? = null
    ): RestoreResult {
        checkConfigured()
        val syncDirectory = syncManager.getSyncDirectory()
            ?: return RestoreResult.Error("Sync directory not configured")

        return restoreManager.restoreBackup(backupFile, syncDirectory, passphrase)
    }

    /**
     * Restore a backup with custom configuration.
     *
     * @param backupFile Backup file to restore
     * @param passphrase Passphrase for encrypted backups (optional)
     * @param config Restore configuration
     * @return Restore result with info or error
     */
    suspend fun restoreBackup(
        backupFile: File,
        passphrase: String? = null,
        config: RestoreConfig
    ): RestoreResult {
        checkConfigured()
        val syncDirectory = syncManager.getSyncDirectory()
            ?: return RestoreResult.Error("Sync directory not configured")

        return restoreManager.restoreBackup(backupFile, syncDirectory, passphrase, config)
    }

    /**
     * Peek at a backup to get info without restoring.
     *
     * @param backupFile Backup file to inspect
     * @param passphrase Passphrase for encrypted backups (optional)
     * @return Peek result with backup info or error
     */
    suspend fun peekBackup(
        backupFile: File,
        passphrase: String? = null
    ): PeekResult = restoreManager.peekBackup(backupFile, passphrase)

    /**
     * List existing backup files.
     *
     * @param backupDirectory Directory to search (or null for default)
     * @return List of backup files sorted by date (newest first)
     */
    fun listBackups(backupDirectory: File? = null): List<File> =
        backupManager.listBackups(backupDirectory)

    /**
     * Delete old backups, keeping only the most recent ones.
     *
     * @param keepCount Number of backups to keep
     * @param backupDirectory Directory to clean (or null for default)
     * @return Number of backups deleted
     */
    fun cleanupOldBackups(keepCount: Int = 5, backupDirectory: File? = null): Int =
        backupManager.cleanupOldBackups(keepCount, backupDirectory)

    /**
     * Estimate the size of a backup.
     *
     * @return Estimated backup size in bytes, or null if not configured
     */
    suspend fun estimateBackupSize(): Long? {
        val syncDirectory = syncManager.getSyncDirectory() ?: return null
        return backupManager.estimateBackupSize(syncDirectory)
    }

    /**
     * Check if there's enough disk space for a backup.
     *
     * @return true if sufficient space available, false if not, null if not configured
     */
    suspend fun hasSufficientSpaceForBackup(): Boolean? {
        val syncDirectory = syncManager.getSyncDirectory() ?: return null
        return backupManager.hasSufficientDiskSpace(syncDirectory)
    }

    // ========== Encryption ==========

    /**
     * Validate a passphrase for encryption.
     *
     * @param passphrase Passphrase to validate
     * @return true if valid, false if weak
     */
    fun isPassphraseValid(passphrase: String): Boolean {
        return try {
            encryptionManager.validatePassphrase(passphrase)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Estimate passphrase strength.
     *
     * @param passphrase Passphrase to evaluate
     * @return Strength level
     */
    fun estimatePassphraseStrength(passphrase: String): PassphraseStrength =
        encryptionManager.estimatePassphraseStrength(passphrase)

    /**
     * Detect the encryption type of a file.
     *
     * @param file File to check
     * @return Encryption type
     */
    fun detectEncryptionType(file: File): EncryptionType =
        encryptionManager.detectEncryptionType(file)

    /**
     * Check if device keystore encryption key exists.
     */
    fun hasDeviceEncryptionKey(): Boolean = encryptionManager.hasDeviceKey()

    // ========== Helpers ==========

    private fun checkConfigured() {
        check(isConfigured) { "GoogleSyncClient not configured. Call configure() first." }
    }

    private fun SyncMode.toWorkerMode(): String = when (this) {
        SyncMode.UPLOAD_ONLY -> SyncWorker.SYNC_MODE_UPLOAD_ONLY
        SyncMode.DOWNLOAD_ONLY -> SyncWorker.SYNC_MODE_DOWNLOAD_ONLY
        SyncMode.BIDIRECTIONAL -> SyncWorker.SYNC_MODE_BIDIRECTIONAL
        SyncMode.MIRROR_TO_CLOUD -> SyncWorker.SYNC_MODE_MIRROR_TO_CLOUD
        SyncMode.MIRROR_FROM_CLOUD -> SyncWorker.SYNC_MODE_MIRROR_FROM_CLOUD
    }

    companion object {
        private val DEFAULT_SYNC_INTERVAL = kotlin.time.Duration.parse("12h")
    }
}

/**
 * Builder for sync client configuration.
 */
class SyncClientConfigBuilder {
    private var rootFolderName: String = "GoogleDriveSync"
    private var syncDirectory: File? = null
    private var fileFilter: FileFilter = FileFilter.defaultSyncFilter()
    private var networkPolicy: NetworkPolicy = NetworkPolicy.UNMETERED_ONLY
    private var conflictPolicy: ConflictPolicy = ConflictPolicy.NEWER_WINS

    /**
     * Set the root folder name on Google Drive.
     */
    fun rootFolderName(name: String) = apply { rootFolderName = name }

    /**
     * Set the local directory to sync.
     */
    fun syncDirectory(directory: File) = apply { syncDirectory = directory }

    /**
     * Set the file filter for sync.
     */
    fun fileFilter(filter: FileFilter) = apply { fileFilter = filter }

    /**
     * Set the network policy for sync operations.
     */
    fun networkPolicy(policy: NetworkPolicy) = apply { networkPolicy = policy }

    /**
     * Set the default conflict resolution policy.
     */
    fun conflictPolicy(policy: ConflictPolicy) = apply { conflictPolicy = policy }

    /**
     * Add file extension exclusions.
     */
    fun excludeExtensions(vararg extensions: String) = apply {
        fileFilter = fileFilter and FileFilter.excludeExtensions(*extensions)
    }

    /**
     * Add file extension inclusions.
     */
    fun includeExtensions(vararg extensions: String) = apply {
        fileFilter = fileFilter and FileFilter.includeExtensions(*extensions)
    }

    /**
     * Set maximum file size.
     */
    fun maxFileSize(bytes: Long) = apply {
        fileFilter = fileFilter and FileFilter.maxSize(bytes)
    }

    /**
     * Exclude hidden files.
     */
    fun excludeHiddenFiles() = apply {
        fileFilter = fileFilter and FileFilter.excludeHidden()
    }

    internal fun build(): SyncConfiguration {
        requireNotNull(syncDirectory) { "syncDirectory must be set" }

        return SyncConfiguration(
            rootFolderName = rootFolderName,
            syncDirectory = syncDirectory!!,
            fileFilter = fileFilter,
            networkPolicy = networkPolicy,
            defaultOptions = SyncOptions(
                conflictPolicy = conflictPolicy
            )
        )
    }
}
