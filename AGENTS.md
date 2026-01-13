# AGENTS.md - Android Google Drive Sync Library

## Project Overview

**android-google-drive-sync** is a robust, flexible Android library for synchronizing files with Google Drive. Designed to be easily integrated into Android applications requiring cloud backup and sync functionality.

### Key Goals

1. **Robust File Synchronization**: Bidirectional sync between local storage and Google Drive
2. **Checksum-Based Deduplication**: Skip unchanged files using MD5 hashing
3. **Flexible Configuration**: Configurable sync policies, folder structures, and file filters
4. **Background Sync**: WorkManager integration for scheduled periodic sync
5. **Resilient Operations**: Retry logic, conflict resolution, and error recovery
6. **Easy Integration**: Simple API for Android apps to adopt

---

## Technology Stack

### Core Technologies
- **Language**: Kotlin 1.9+
- **Coroutines**: 1.7+ for async operations
- **Min SDK**: 26 (Android 8.0+)
- **Target SDK**: 35 (Android 15)

### Dependencies
- **Google Drive API**: `com.google.api-client:google-api-client-android`
- **Google Auth**: `com.google.android.gms:play-services-auth`
- **Hilt**: Dependency injection
- **WorkManager**: Background sync scheduling
- **Room** (optional): For sync state persistence
- **Kotlinx Serialization**: JSON handling

---

## Architecture

### Clean Architecture Layers

```
┌─────────────────────────────────────────────────────────────┐
│                    PUBLIC API LAYER                         │
│  GoogleSyncClient, SyncConfiguration, SyncResult            │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                    DOMAIN LAYER                             │
│  SyncManager, ConflictResolver, SyncPolicy                  │
└─────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────────────────────────────────────┐
│                    DATA LAYER                               │
│  DriveFileOperations, FolderManager, AuthManager            │
│  FileHasher, SyncStateRepository, CacheManager              │
└─────────────────────────────────────────────────────────────┘
```

### Module Structure

```
android-google-drive-sync/
├── library/                          # Main library module
│   ├── src/main/java/com/vanespark/googlesync/
│   │   ├── api/                      # Public API
│   │   │   ├── GoogleSyncClient.kt   # Main entry point
│   │   │   ├── SyncConfiguration.kt  # Configuration builder
│   │   │   ├── SyncResult.kt         # Result types
│   │   │   └── SyncCallback.kt       # Progress callbacks
│   │   ├── auth/                     # Authentication
│   │   │   ├── GoogleAuthManager.kt  # Google Sign-In
│   │   │   ├── CredentialManager.kt  # Token management
│   │   │   └── AuthState.kt          # Auth state sealed class
│   │   ├── sync/                     # Sync engine
│   │   │   ├── SyncManager.kt        # Main sync orchestrator
│   │   │   ├── SyncEngine.kt         # Core sync logic
│   │   │   ├── SyncPolicy.kt         # Sync strategies
│   │   │   ├── ConflictResolver.kt   # Conflict handling
│   │   │   └── SyncState.kt          # Sync state tracking
│   │   ├── drive/                    # Google Drive operations
│   │   │   ├── DriveService.kt       # Drive API wrapper
│   │   │   ├── DriveFileOperations.kt# File CRUD
│   │   │   ├── DriveFolderManager.kt # Folder hierarchy
│   │   │   ├── DriveQueryBuilder.kt  # Query construction
│   │   │   └── DriveModels.kt        # Data models
│   │   ├── local/                    # Local file operations
│   │   │   ├── LocalFileManager.kt   # Local file I/O
│   │   │   ├── FileHasher.kt         # MD5/SHA256 hashing
│   │   │   └── FileFilter.kt         # File inclusion/exclusion
│   │   ├── cache/                    # Caching layer
│   │   │   ├── SyncCacheManager.kt   # Metadata cache
│   │   │   ├── ChecksumCache.kt      # Hash cache
│   │   │   └── CachePolicy.kt        # Cache invalidation
│   │   ├── worker/                   # Background sync
│   │   │   ├── SyncWorker.kt         # WorkManager worker
│   │   │   ├── SyncScheduler.kt      # Scheduling logic
│   │   │   └── WorkerConstraints.kt  # Network/battery constraints
│   │   ├── resilience/               # Error handling
│   │   │   ├── RetryPolicy.kt        # Exponential backoff
│   │   │   ├── NetworkMonitor.kt     # Connectivity check
│   │   │   └── ErrorRecovery.kt      # Recovery strategies
│   │   ├── db/                       # Persistence (optional)
│   │   │   ├── SyncDatabase.kt       # Room database
│   │   │   ├── SyncStateDao.kt       # DAO
│   │   │   └── SyncStateEntity.kt    # Entity
│   │   ├── di/                       # Dependency injection
│   │   │   └── GoogleSyncModule.kt   # Hilt module
│   │   └── util/                     # Utilities
│   │       ├── Extensions.kt         # Kotlin extensions
│   │       ├── MimeTypeUtils.kt      # MIME type handling
│   │       └── Constants.kt          # Library constants
│   ├── src/test/                     # Unit tests
│   └── build.gradle.kts
├── sample/                           # Sample app
│   ├── src/main/
│   └── build.gradle.kts
├── docs/                             # Documentation
│   ├── INTEGRATION.md                # Integration guide
│   ├── CONFIGURATION.md              # Configuration options
│   └── TROUBLESHOOTING.md            # Common issues
├── AGENTS.md                         # This file
├── TODO.md                           # Outstanding tasks
├── PROGRESS.md                       # Completed items
├── README.md                         # Main documentation
├── CHANGELOG.md                      # Version history
├── build.gradle.kts                  # Root build file
├── settings.gradle.kts               # Module settings
├── gradle.properties                 # Gradle properties
├── detekt.yml                        # Code quality
└── .gitignore                        # Git ignore rules
```

---

## Core Components

### 1. GoogleSyncClient (Public API)

Main entry point for library consumers:

```kotlin
interface GoogleSyncClient {
    // Authentication
    suspend fun signIn(activity: Activity): AuthResult
    suspend fun signOut()
    fun isSignedIn(): Boolean
    fun getSignedInAccount(): GoogleSignInAccount?

    // Sync operations
    suspend fun syncToCloud(options: SyncOptions = SyncOptions.DEFAULT): SyncResult
    suspend fun syncFromCloud(options: SyncOptions = SyncOptions.DEFAULT): SyncResult
    suspend fun fullSync(options: SyncOptions = SyncOptions.DEFAULT): SyncResult

    // File operations
    suspend fun uploadFile(localFile: File, remotePath: String): UploadResult
    suspend fun downloadFile(remoteFileId: String, localPath: File): DownloadResult
    suspend fun deleteRemoteFile(remoteFileId: String): DeleteResult

    // Backup operations
    suspend fun createBackup(backupConfig: BackupConfig): BackupResult
    suspend fun restoreBackup(backupId: String): RestoreResult
    suspend fun listBackups(): List<BackupInfo>

    // Progress observation
    fun observeSyncProgress(): Flow<SyncProgress>

    // Scheduling
    fun schedulePeriodicSync(interval: Duration, constraints: SyncConstraints)
    fun cancelScheduledSync()
}
```

### 2. SyncConfiguration (Builder Pattern)

```kotlin
data class SyncConfiguration(
    val appFolderName: String,              // Root folder on Drive
    val syncDirectories: List<SyncDirectory>,// Directories to sync
    val fileFilters: List<FileFilter>,       // Include/exclude patterns
    val conflictPolicy: ConflictPolicy,      // How to handle conflicts
    val checksumAlgorithm: ChecksumAlgorithm,// MD5 or SHA256
    val networkPolicy: NetworkPolicy,        // WiFi only, any, etc.
    val retryPolicy: RetryPolicy,            // Retry configuration
    val cachePolicy: CachePolicy,            // Metadata caching
    val driveScopes: List<String>,           // Drive API scopes
)

// Builder
class SyncConfigurationBuilder {
    fun appFolderName(name: String): SyncConfigurationBuilder
    fun addSyncDirectory(dir: SyncDirectory): SyncConfigurationBuilder
    fun addFileFilter(filter: FileFilter): SyncConfigurationBuilder
    fun conflictPolicy(policy: ConflictPolicy): SyncConfigurationBuilder
    fun checksumAlgorithm(algorithm: ChecksumAlgorithm): SyncConfigurationBuilder
    fun networkPolicy(policy: NetworkPolicy): SyncConfigurationBuilder
    fun retryPolicy(policy: RetryPolicy): SyncConfigurationBuilder
    fun build(): SyncConfiguration
}
```

### 3. SyncResult (Sealed Class)

```kotlin
sealed class SyncResult {
    data class Success(
        val filesUploaded: Int,
        val filesDownloaded: Int,
        val filesSkipped: Int,
        val conflicts: List<ConflictInfo>,
        val duration: Duration
    ) : SyncResult()

    data class PartialSuccess(
        val completed: Int,
        val failed: Int,
        val errors: List<SyncError>
    ) : SyncResult()

    data class Error(
        val type: SyncErrorType,
        val message: String,
        val cause: Throwable? = null
    ) : SyncResult()

    object NotSignedIn : SyncResult()
    object NetworkUnavailable : SyncResult()
    object QuotaExceeded : SyncResult()
    object Cancelled : SyncResult()
}

enum class SyncErrorType {
    AUTH_FAILED,
    NETWORK_ERROR,
    PERMISSION_DENIED,
    FILE_NOT_FOUND,
    QUOTA_EXCEEDED,
    RATE_LIMITED,
    CONFLICT,
    UNKNOWN
}
```

### 4. Conflict Resolution

```kotlin
enum class ConflictPolicy {
    LOCAL_WINS,           // Local file overwrites remote
    REMOTE_WINS,          // Remote file overwrites local
    NEWER_WINS,           // Compare timestamps, newer wins
    KEEP_BOTH,            // Rename local with suffix
    ASK_USER,             // Callback to ask user
    SKIP                  // Skip conflicting files
}

interface ConflictResolver {
    suspend fun resolve(
        localFile: LocalFileInfo,
        remoteFile: RemoteFileInfo,
        policy: ConflictPolicy
    ): ConflictResolution
}

sealed class ConflictResolution {
    object UseLocal : ConflictResolution()
    object UseRemote : ConflictResolution()
    data class KeepBoth(val localRename: String) : ConflictResolution()
    object Skip : ConflictResolution()
    data class Custom(val action: suspend () -> Unit) : ConflictResolution()
}
```

### 5. Sync State Tracking

```kotlin
data class SyncState(
    val fileId: String,
    val localPath: String,
    val remotePath: String,
    val localChecksum: String,
    val remoteChecksum: String,
    val lastSyncedAt: Instant,
    val syncStatus: SyncStatus
)

enum class SyncStatus {
    SYNCED,
    LOCAL_MODIFIED,
    REMOTE_MODIFIED,
    CONFLICT,
    PENDING_UPLOAD,
    PENDING_DOWNLOAD,
    ERROR
}
```

---

## Sync Algorithm

### Upload Flow

```
1. List local files in sync directories
2. Apply file filters (include/exclude)
3. For each file:
   a. Calculate local checksum (MD5/SHA256)
   b. Check sync state cache for existing remote entry
   c. If no remote entry → Upload new file
   d. If remote exists:
      - Compare checksums
      - If match → Skip (already synced)
      - If different → Check conflict policy
        - NEWER_WINS: Compare timestamps
        - LOCAL_WINS: Upload and overwrite
        - Other: Apply policy
   e. Update sync state with new checksum and timestamp
4. Return SyncResult with statistics
```

### Download Flow

```
1. List remote files in app folder
2. Apply file filters
3. For each remote file:
   a. Get remote checksum from Drive API
   b. Check if local file exists
   c. If no local file → Download
   d. If local exists:
      - Calculate local checksum
      - If match → Skip
      - If different → Apply conflict policy
   e. Update sync state
4. Return SyncResult
```

### Full Sync (Bidirectional)

```
1. Build file manifests:
   - Local: {path → checksum, modifiedAt}
   - Remote: {path → checksum, modifiedAt}
2. Compare manifests:
   - In local only → Upload
   - In remote only → Download
   - In both with matching checksum → Skip
   - In both with different checksum → Conflict resolution
3. Execute operations with progress tracking
4. Update sync state database
5. Return comprehensive SyncResult
```

---

## Google Drive Folder Structure

```
Google Drive/
└── {AppFolderName}/                    # Configurable root
    ├── sync/                           # Synced files mirror
    │   ├── documents/
    │   ├── images/
    │   ├── audio/
    │   └── {user-defined}/
    ├── backups/                        # ZIP backup archives
    │   ├── backup-20240115-1430.zip
    │   └── backup-20240120-0900.zip
    └── .sync-state.json                # Sync metadata (optional)
```

---

## Error Handling & Resilience

### Retry Policy

```kotlin
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelay: Duration = 1.seconds,
    val maxDelay: Duration = 30.seconds,
    val multiplier: Double = 2.0,
    val retryableErrors: Set<SyncErrorType> = setOf(
        SyncErrorType.NETWORK_ERROR,
        SyncErrorType.RATE_LIMITED
    )
)
```

### Network Monitoring

- Check connectivity before operations
- Respect NetworkPolicy (WiFi only, any network, unmetered only)
- Handle network transitions gracefully
- Queue operations when offline (optional)

### Error Recovery

- Transient errors: Retry with exponential backoff
- Auth errors: Trigger re-authentication flow
- Quota errors: Notify user, pause sync
- Conflict errors: Apply conflict policy
- Unknown errors: Log, report, fail gracefully

---

## Background Sync (WorkManager)

### Periodic Sync Configuration

```kotlin
data class SyncScheduleConfig(
    val interval: Duration = 12.hours,
    val flexInterval: Duration = 2.hours,
    val constraints: Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build(),
    val initialDelay: Duration = Duration.ZERO,
    val existingWorkPolicy: ExistingPeriodicWorkPolicy = KEEP
)
```

### SyncWorker Implementation

```kotlin
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncManager: SyncManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return when (val result = syncManager.fullSync()) {
            is SyncResult.Success -> Result.success()
            is SyncResult.PartialSuccess -> Result.success() // Log errors
            is SyncResult.NetworkUnavailable -> Result.retry()
            is SyncResult.Error -> {
                if (result.type in RETRYABLE_ERRORS) Result.retry()
                else Result.failure()
            }
            else -> Result.failure()
        }
    }
}
```

---

## Testing Requirements

### Unit Tests

- **Auth**: Sign-in flow, token refresh, sign-out
- **Sync Engine**: Upload, download, conflict resolution
- **Checksum**: MD5/SHA256 calculation, comparison
- **Folder Manager**: Folder creation, hierarchy
- **Retry Policy**: Exponential backoff, max attempts
- **File Filters**: Include/exclude pattern matching

### Integration Tests

- Drive API operations (mock or real)
- WorkManager scheduling
- End-to-end sync scenarios
- Network transition handling

### Test Coverage Target

- Minimum 80% line coverage
- 100% coverage for public API
- All error paths tested

---

## Code Quality Standards

### Kotlin Style

- Follow official Kotlin coding conventions
- Use meaningful variable/function names
- Prefer immutable data (val over var)
- Use sealed classes for state/result types
- Leverage Kotlin coroutines for async

### Documentation

- KDoc for all public APIs
- README with quick start guide
- Integration guide with examples
- Troubleshooting guide

### Static Analysis

- Detekt for Kotlin linting
- Baseline for existing issues
- CI gate for new violations

---

## String Localization

- NEVER hardcode user-facing strings
- Use `R.string.xxx` for all UI text
- Provide English (en) and Spanish (es) at minimum
- Use format specifiers: `%1$s`, `%1$d`

---

## Build Commands

```bash
# Build library
./gradlew :library:build

# Run tests
./gradlew :library:test

# Run detekt
./gradlew detekt

# Publish to local Maven
./gradlew :library:publishToMavenLocal

# Build sample app
./gradlew :sample:assembleDebug
```

---

## Version History

Track all changes in CHANGELOG.md using Keep a Changelog format.

---

## References

- [Google Drive API v3](https://developers.google.com/drive/api/v3/about-sdk)
- [Google Sign-In for Android](https://developers.google.com/identity/sign-in/android)
- [Android WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
- vane-client-manager (primary reference)
- vane-spark-notes (secondary reference)
