package com.vanespark.googledrivesync.di

import android.content.Context
import com.vanespark.googledrivesync.api.GoogleSyncClient
import com.vanespark.googledrivesync.auth.GoogleAuthManager
import com.vanespark.googledrivesync.backup.BackupManager
import com.vanespark.googledrivesync.backup.RestoreManager
import com.vanespark.googledrivesync.cache.SyncCache
import com.vanespark.googledrivesync.crypto.CryptoManager
import com.vanespark.googledrivesync.crypto.EncryptionManager
import com.vanespark.googledrivesync.crypto.PassphraseBasedCrypto
import com.vanespark.googledrivesync.drive.DriveFileOperations
import com.vanespark.googledrivesync.drive.DriveFolderManager
import com.vanespark.googledrivesync.drive.DriveService
import com.vanespark.googledrivesync.local.FileHasher
import com.vanespark.googledrivesync.local.LocalFileManager
import com.vanespark.googledrivesync.resilience.NetworkMonitor
import com.vanespark.googledrivesync.resilience.RateLimitHandler
import com.vanespark.googledrivesync.resilience.SyncProgressManager
import com.vanespark.googledrivesync.sync.ConflictResolver
import com.vanespark.googledrivesync.sync.SyncEngine
import com.vanespark.googledrivesync.sync.SyncHistoryManager
import com.vanespark.googledrivesync.sync.SyncManager
import com.vanespark.googledrivesync.worker.SyncScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing Google Drive Sync library dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object GoogleSyncModule {

    // ========== Auth ==========

    @Provides
    @Singleton
    fun provideGoogleAuthManager(
        @ApplicationContext context: Context
    ): GoogleAuthManager = GoogleAuthManager(context)

    // ========== Drive Operations ==========

    @Provides
    @Singleton
    fun provideDriveFileOperations(): DriveFileOperations = DriveFileOperations()

    @Provides
    @Singleton
    fun provideDriveFolderManager(
        fileOperations: DriveFileOperations
    ): DriveFolderManager = DriveFolderManager(fileOperations)

    @Provides
    @Singleton
    fun provideDriveService(
        authManager: GoogleAuthManager,
        fileOperations: DriveFileOperations,
        folderManager: DriveFolderManager
    ): DriveService = DriveService(authManager, fileOperations, folderManager)

    // ========== Local File Management ==========

    @Provides
    @Singleton
    fun provideFileHasher(): FileHasher = FileHasher()

    @Provides
    @Singleton
    fun provideLocalFileManager(
        @ApplicationContext context: Context,
        fileHasher: FileHasher
    ): LocalFileManager = LocalFileManager(context, fileHasher)

    // ========== Resilience ==========

    @Provides
    @Singleton
    fun provideNetworkMonitor(
        @ApplicationContext context: Context
    ): NetworkMonitor = NetworkMonitor(context)

    @Provides
    @Singleton
    fun provideSyncProgressManager(
        @ApplicationContext context: Context
    ): SyncProgressManager = SyncProgressManager(context)

    @Provides
    @Singleton
    fun provideRateLimitHandler(): RateLimitHandler = RateLimitHandler()

    // ========== Cache ==========

    @Provides
    @Singleton
    fun provideSyncCache(
        @ApplicationContext context: Context
    ): SyncCache = SyncCache(context)

    // ========== Crypto ==========

    @Provides
    @Singleton
    fun providePassphraseBasedCrypto(): PassphraseBasedCrypto = PassphraseBasedCrypto()

    @Provides
    @Singleton
    fun provideCryptoManager(): CryptoManager = CryptoManager()

    @Provides
    @Singleton
    fun provideEncryptionManager(
        passphraseBasedCrypto: PassphraseBasedCrypto,
        cryptoManager: CryptoManager
    ): EncryptionManager = EncryptionManager(passphraseBasedCrypto, cryptoManager)

    // ========== Backup ==========

    @Provides
    @Singleton
    fun provideBackupManager(
        @ApplicationContext context: Context,
        encryptionManager: EncryptionManager,
        fileHasher: FileHasher,
        localFileManager: LocalFileManager
    ): BackupManager = BackupManager(context, encryptionManager, fileHasher, localFileManager)

    @Provides
    @Singleton
    fun provideRestoreManager(
        @ApplicationContext context: Context,
        encryptionManager: EncryptionManager,
        fileHasher: FileHasher
    ): RestoreManager = RestoreManager(context, encryptionManager, fileHasher)

    // ========== Sync Engine ==========

    @Provides
    @Singleton
    fun provideConflictResolver(): ConflictResolver = ConflictResolver()

    @Provides
    @Singleton
    fun provideSyncHistoryManager(
        @ApplicationContext context: Context
    ): SyncHistoryManager = SyncHistoryManager(context)

    @Provides
    @Singleton
    fun provideSyncEngine(
        driveService: DriveService,
        localFileManager: LocalFileManager,
        fileHasher: FileHasher,
        conflictResolver: ConflictResolver,
        progressManager: SyncProgressManager
    ): SyncEngine = SyncEngine(
        driveService,
        localFileManager,
        fileHasher,
        conflictResolver,
        progressManager
    )

    @Provides
    @Singleton
    fun provideSyncManager(
        authManager: GoogleAuthManager,
        syncEngine: SyncEngine,
        networkMonitor: NetworkMonitor,
        progressManager: SyncProgressManager,
        historyManager: SyncHistoryManager
    ): SyncManager = SyncManager(
        authManager,
        syncEngine,
        networkMonitor,
        progressManager,
        historyManager
    )

    // ========== Worker ==========

    @Provides
    @Singleton
    fun provideSyncScheduler(
        @ApplicationContext context: Context
    ): SyncScheduler = SyncScheduler(context)

    // ========== Public API ==========

    @Provides
    @Singleton
    fun provideGoogleSyncClient(
        authManager: GoogleAuthManager,
        syncManager: SyncManager,
        syncScheduler: SyncScheduler,
        conflictResolver: ConflictResolver,
        backupManager: BackupManager,
        restoreManager: RestoreManager,
        encryptionManager: EncryptionManager
    ): GoogleSyncClient = GoogleSyncClient(
        authManager,
        syncManager,
        syncScheduler,
        conflictResolver,
        backupManager,
        restoreManager,
        encryptionManager
    )
}
