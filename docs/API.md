# API Reference

> Complete API documentation for integrating the Android Google Drive Sync library

## Table of Contents

- [GoogleSyncClient](#googlessyncclient)
- [Authentication](#authentication)
- [Sync Operations](#sync-operations)
- [Progress Tracking](#progress-tracking)
- [Conflict Resolution](#conflict-resolution)
- [Backup & Restore](#backup--restore)
- [Google Drive Backup](#google-drive-backup)
- [Background Sync](#background-sync)
- [Network Monitoring](#network-monitoring)
- [File Filtering](#file-filtering)
- [Encryption](#encryption)
- [Models & Types](#models--types)

---

## GoogleSyncClient

The main entry point for all library functionality. Injected via Hilt dependency injection.

### Injection

```kotlin
@Inject
lateinit var syncClient: GoogleSyncClient
```

### Configuration

```kotlin
syncClient.configure {
    rootFolderName("MyApp")
    syncDirectory(context.filesDir)
    conflictPolicy(ConflictPolicy.NEWER_WINS)
    networkPolicy(NetworkPolicy.UNMETERED_ONLY)
    excludeExtensions("tmp", "cache")
}
```

| Method | Description |
|--------|-------------|
| `configure(block: SyncClientConfigBuilder.() -> Unit)` | Configure the client using builder DSL |
| `isConfigured(): Boolean` | Check if client has been configured |

---

## Authentication

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `authState` | `StateFlow<AuthState>` | Observable authentication state |

### Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `isSignedIn()` | `Boolean` | Check if user is authenticated |
| `getSignInIntent()` | `Intent` | Get intent for launching Google Sign-In |
| `handleSignInResult(data: Intent?)` | `AuthResult` | Process sign-in result from activity |
| `signOut()` | `Unit` | Sign out current user |
| `revokeAccess()` | `Unit` | Revoke access and disconnect from Google |
| `getSignedInEmail()` | `String?` | Get signed-in user's email address |

### AuthState

```kotlin
sealed class AuthState {
    object NotSignedIn : AuthState()
    object SigningIn : AuthState()
    data class SignedIn(
        val account: GoogleSignInAccount,
        val email: String,
        val displayName: String?
    ) : AuthState()
    data class Error(val message: String, val cause: Throwable?) : AuthState()
    object PermissionRequired : AuthState()
}
```

### AuthResult

```kotlin
sealed class AuthResult {
    data class Success(
        val account: GoogleSignInAccount,
        val email: String,
        val displayName: String?
    ) : AuthResult()
    object Cancelled : AuthResult()
    data class Error(val message: String, val cause: Throwable?) : AuthResult()
    object PermissionRequired : AuthResult()
}
```

### Example

```kotlin
// Launch sign-in
val intent = syncClient.getSignInIntent()
startActivityForResult(intent, RC_SIGN_IN)

// Handle result in onActivityResult
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == RC_SIGN_IN) {
        lifecycleScope.launch {
            when (val result = syncClient.handleSignInResult(data)) {
                is AuthResult.Success -> {
                    Log.d("Auth", "Signed in as ${result.email}")
                }
                is AuthResult.Cancelled -> {
                    Log.d("Auth", "Sign-in cancelled")
                }
                is AuthResult.Error -> {
                    Log.e("Auth", result.message, result.cause)
                }
                is AuthResult.PermissionRequired -> {
                    // Request additional permissions
                }
            }
        }
    }
}

// Observe auth state
lifecycleScope.launch {
    syncClient.authState.collect { state ->
        when (state) {
            is AuthState.SignedIn -> updateUI(state.email)
            is AuthState.NotSignedIn -> showSignInButton()
            else -> {}
        }
    }
}
```

---

## Sync Operations

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `syncProgress` | `StateFlow<SyncProgress>` | Observable sync progress |
| `isSyncing` | `StateFlow<Boolean>` | Observable sync status |
| `syncHistory` | `StateFlow<List<SyncHistoryEntry>>` | Observable sync history |

### Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `sync()` | `SyncResult` | Perform bidirectional sync |
| `sync(options: SyncOptions)` | `SyncResult` | Sync with custom options |
| `uploadOnly()` | `SyncResult` | Upload local changes only |
| `downloadOnly()` | `SyncResult` | Download remote changes only |
| `mirrorToCloud()` | `SyncResult` | Make cloud match local exactly |
| `mirrorFromCloud()` | `SyncResult` | Make local match cloud exactly |
| `cancelSync()` | `Unit` | Cancel current sync operation |
| `getLastSyncTime()` | `Long?` | Get timestamp of last successful sync |
| `getSyncStatistics()` | `SyncStatistics` | Get sync statistics |
| `clearSyncHistory()` | `Unit` | Clear sync history |

### Resume Capability

| Method | Return Type | Description |
|--------|-------------|-------------|
| `hasResumableSync()` | `Boolean` | Check if there's a resumable sync |
| `clearResumableSync()` | `Unit` | Clear resume state |

### SyncResult

```kotlin
sealed class SyncResult {
    data class Success(
        val filesUploaded: Int,
        val filesDownloaded: Int,
        val filesDeleted: Int,
        val filesSkipped: Int,
        val conflicts: Int,
        val duration: Long,
        val bytesTransferred: Long
    ) : SyncResult()

    data class PartialSuccess(
        val filesSucceeded: Int,
        val filesFailed: Int,
        val errors: List<SyncError>,
        val duration: Long
    ) : SyncResult()

    data class Error(
        val message: String,
        val cause: Throwable?
    ) : SyncResult()

    object NotSignedIn : SyncResult()
    object NetworkUnavailable : SyncResult()
    object Cancelled : SyncResult()
    object Paused : SyncResult()
}
```

### SyncOptions

```kotlin
data class SyncOptions(
    val mode: SyncMode = SyncMode.BIDIRECTIONAL,
    val conflictPolicy: ConflictPolicy = ConflictPolicy.NEWER_WINS,
    val allowDeletions: Boolean = true,
    val subdirectory: String? = null,
    val maxFiles: Int? = null,
    val verifyChecksums: Boolean = true,
    val useCache: Boolean = true
)

// Preset options
val defaultOptions = SyncOptions.DEFAULT
val uploadOnly = SyncOptions.UPLOAD_ONLY
val downloadOnly = SyncOptions.DOWNLOAD_ONLY
val mirrorToCloud = SyncOptions.MIRROR_TO_CLOUD
val mirrorFromCloud = SyncOptions.MIRROR_FROM_CLOUD
```

### SyncMode

| Mode | Description |
|------|-------------|
| `BIDIRECTIONAL` | Sync changes in both directions (default) |
| `UPLOAD_ONLY` | Upload local changes to cloud |
| `DOWNLOAD_ONLY` | Download cloud changes to local |
| `MIRROR_TO_CLOUD` | Make remote exactly match local |
| `MIRROR_FROM_CLOUD` | Make local exactly match remote |

### Example

```kotlin
// Simple bidirectional sync
val result = syncClient.sync()

// Sync with options
val result = syncClient.sync(SyncOptions(
    mode = SyncMode.BIDIRECTIONAL,
    conflictPolicy = ConflictPolicy.NEWER_WINS,
    verifyChecksums = true
))

// Handle result
when (result) {
    is SyncResult.Success -> {
        Log.d("Sync", "Uploaded: ${result.filesUploaded}, Downloaded: ${result.filesDownloaded}")
    }
    is SyncResult.PartialSuccess -> {
        Log.w("Sync", "Completed with ${result.filesFailed} failures")
    }
    is SyncResult.Error -> {
        Log.e("Sync", result.message, result.cause)
    }
    SyncResult.NotSignedIn -> {
        // Prompt user to sign in
    }
    SyncResult.NetworkUnavailable -> {
        // Show network error
    }
    SyncResult.Cancelled -> {
        // Sync was cancelled
    }
    SyncResult.Paused -> {
        // Sync was paused
    }
}
```

---

## Progress Tracking

### SyncProgress

```kotlin
data class SyncProgress(
    val phase: SyncPhase,
    val currentFile: String?,
    val totalFiles: Int,
    val processedFiles: Int,
    val uploadedFiles: Int,
    val downloadedFiles: Int,
    val skippedFiles: Int,
    val failedFiles: Int,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val error: String?,
    val startTime: Long,
    val lastUpdateTime: Long
) {
    val percentage: Float  // Computed property: 0.0 to 1.0
    val isActive: Boolean  // True if sync is in progress
    val durationMs: Long   // Elapsed time in milliseconds
}
```

### SyncPhase

| Phase | Description |
|-------|-------------|
| `IDLE` | No sync in progress |
| `AUTHENTICATING` | Verifying authentication |
| `PREPARING` | Preparing sync operation |
| `SCANNING_LOCAL` | Scanning local files |
| `SCANNING_REMOTE` | Scanning remote files |
| `COMPARING` | Comparing local and remote |
| `UPLOADING` | Uploading files |
| `DOWNLOADING` | Downloading files |
| `CLEANING_UP` | Cleaning up after sync |
| `COMPLETED` | Sync completed successfully |
| `FAILED` | Sync failed |
| `CANCELLED` | Sync was cancelled |
| `PAUSED` | Sync is paused |

### Example

```kotlin
lifecycleScope.launch {
    syncClient.syncProgress.collect { progress ->
        progressBar.progress = (progress.percentage * 100).toInt()
        statusText.text = when (progress.phase) {
            SyncPhase.UPLOADING -> "Uploading ${progress.currentFile}"
            SyncPhase.DOWNLOADING -> "Downloading ${progress.currentFile}"
            SyncPhase.COMPLETED -> "Sync complete!"
            else -> progress.phase.name
        }
        fileCountText.text = "${progress.processedFiles}/${progress.totalFiles}"
    }
}
```

---

## Conflict Resolution

### ConflictPolicy

| Policy | Description |
|--------|-------------|
| `LOCAL_WINS` | Local file always overwrites remote |
| `REMOTE_WINS` | Remote file always overwrites local |
| `NEWER_WINS` | File with newer modification timestamp wins (default) |
| `KEEP_BOTH` | Keep both files; rename local with conflict suffix |
| `SKIP` | Skip conflicting files |
| `ASK_USER` | Invoke callback to let user decide |

### ConflictCallback

```kotlin
syncClient.setConflictCallback { conflictInfo ->
    // Return resolution decision
    ConflictResolution.UseLocal      // Use local file
    ConflictResolution.UseRemote     // Use remote file
    ConflictResolution.KeepBoth("_conflict")  // Keep both with suffix
    ConflictResolution.Skip          // Skip this file
    ConflictResolution.DeleteBoth    // Delete both versions
}
```

### ConflictInfo

```kotlin
data class ConflictInfo(
    val relativePath: String,
    val localChecksum: String?,
    val remoteChecksum: String?,
    val localModifiedTime: Long,
    val remoteModifiedTime: Long,
    val localSize: Long,
    val remoteSize: Long
)
```

### ConflictResolution

```kotlin
sealed class ConflictResolution {
    object UseLocal : ConflictResolution()
    object UseRemote : ConflictResolution()
    data class KeepBoth(val suffix: String) : ConflictResolution()
    object Skip : ConflictResolution()
    object DeleteBoth : ConflictResolution()
}
```

### Example

```kotlin
// Set up user-driven conflict resolution
syncClient.setConflictCallback { conflict ->
    // Show dialog and wait for user choice
    val choice = showConflictDialog(conflict)

    when (choice) {
        "local" -> ConflictResolution.UseLocal
        "remote" -> ConflictResolution.UseRemote
        "both" -> ConflictResolution.KeepBoth("_conflict_${System.currentTimeMillis()}")
        else -> ConflictResolution.Skip
    }
}

// Perform sync - callback will be invoked for each conflict
val result = syncClient.sync(SyncOptions(
    conflictPolicy = ConflictPolicy.ASK_USER
))
```

---

## Backup & Restore

### Local Backup Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `createBackup(outputFile, passphrase?, useDeviceKeystore?)` | `BackupResult` | Create encrypted backup |
| `createBackup(config, outputFile)` | `BackupResult` | Create backup with config |
| `restoreBackup(backupFile, passphrase?)` | `RestoreResult` | Restore from backup |
| `restoreBackup(backupFile, passphrase?, config)` | `RestoreResult` | Restore with config |
| `peekBackup(backupFile, passphrase?)` | `PeekResult` | Inspect backup without restoring |
| `listBackups(backupDirectory)` | `List<File>` | List existing backup files |
| `cleanupOldBackups(keepCount, backupDirectory)` | `Int` | Delete old backups, return count deleted |
| `estimateBackupSize()` | `Long?` | Estimate backup size in bytes |
| `hasSufficientSpaceForBackup()` | `Boolean?` | Check if sufficient disk space |

### BackupResult

```kotlin
sealed class BackupResult {
    data class Success(val info: BackupInfo) : BackupResult()
    data class Error(val message: String, val cause: Throwable?) : BackupResult()
}

data class BackupInfo(
    val file: File,
    val manifest: BackupManifest,
    val checksum: String,
    val sizeBytes: Long,
    val encrypted: Boolean
)
```

### RestoreResult

```kotlin
sealed class RestoreResult {
    data class Success(val info: RestoreInfo) : RestoreResult()
    data class Error(
        val message: String,
        val isPassphraseRequired: Boolean,
        val cause: Throwable?
    ) : RestoreResult()
}

data class RestoreInfo(
    val manifest: BackupManifest,
    val filesRestored: Int,
    val bytesRestored: Long,
    val checksumVerified: Boolean,
    val safetyBackup: File?
)
```

### PeekResult

```kotlin
sealed class PeekResult {
    data class Success(val info: BackupPeekInfo) : PeekResult()
    object NeedsPassphrase : PeekResult()
    object WrongPassphrase : PeekResult()
    data class InvalidBackup(val reason: String) : PeekResult()
}
```

### BackupConfig

```kotlin
data class BackupConfig(
    val encryption: EncryptionConfig = EncryptionConfig.None,
    val includeChecksums: Boolean = true,
    val allowEmptyBackup: Boolean = false,
    val fileFilter: FileFilter? = null,
    val appVersion: String? = null
)
```

### RestoreConfig

```kotlin
data class RestoreConfig(
    val createSafetyBackup: Boolean = true,
    val clearBeforeRestore: Boolean = false,
    val verifyChecksums: Boolean = true,
    val rollbackOnFailure: Boolean = true,
    val deleteSafetyBackupOnSuccess: Boolean = true
)
```

### Example

```kotlin
// Create encrypted backup
val backupFile = File(context.cacheDir, "backup_${System.currentTimeMillis()}.zip")
when (val result = syncClient.createBackup(backupFile, passphrase = "secure123")) {
    is BackupResult.Success -> {
        Log.d("Backup", "Created: ${result.info.file.absolutePath}")
        Log.d("Backup", "Size: ${result.info.sizeBytes} bytes")
        Log.d("Backup", "Files: ${result.info.manifest.fileCount}")
    }
    is BackupResult.Error -> {
        Log.e("Backup", result.message, result.cause)
    }
}

// Peek at backup contents before restoring
when (val peek = syncClient.peekBackup(backupFile, passphrase = "secure123")) {
    is PeekResult.Success -> {
        Log.d("Peek", "Files: ${peek.info.manifest.fileCount}")
        Log.d("Peek", "Created: ${peek.info.manifest.createdAt}")
    }
    PeekResult.NeedsPassphrase -> {
        // Prompt for passphrase
    }
    PeekResult.WrongPassphrase -> {
        // Show error
    }
    is PeekResult.InvalidBackup -> {
        Log.e("Peek", peek.reason)
    }
}

// Restore from backup
when (val result = syncClient.restoreBackup(backupFile, passphrase = "secure123")) {
    is RestoreResult.Success -> {
        Log.d("Restore", "Restored ${result.info.filesRestored} files")
    }
    is RestoreResult.Error -> {
        if (result.isPassphraseRequired) {
            // Prompt for passphrase
        } else {
            Log.e("Restore", result.message, result.cause)
        }
    }
}
```

---

## Google Drive Backup

### Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `listDriveBackups()` | `DriveOperationResult<List<DriveBackupFile>>` | List backups on Drive |
| `uploadBackupToDrive(localBackupFile, backupName?)` | `DriveOperationResult<UploadResult>` | Upload backup to Drive |
| `downloadBackupFromDrive(fileId, destinationFile)` | `DriveOperationResult<DownloadResult>` | Download backup from Drive |
| `restoreBackupFromDrive(fileId, passphrase?)` | `RestoreResult` | Download and restore in one step |
| `createAndUploadBackup(passphrase?, useDeviceKeystore?, backupName?)` | `DriveOperationResult<UploadResult>` | Create and upload in one call |
| `deleteBackupFromDrive(fileId)` | `DriveOperationResult<Unit>` | Delete backup from Drive |

### DriveOperationResult

```kotlin
sealed class DriveOperationResult<out T> {
    data class Success<T>(val data: T) : DriveOperationResult<T>()
    data class Error(val message: String, val cause: Throwable?) : DriveOperationResult<Nothing>()
    object NotSignedIn : DriveOperationResult<Nothing>()
    object PermissionRequired : DriveOperationResult<Nothing>()
    object ServiceUnavailable : DriveOperationResult<Nothing>()
    object RateLimited : DriveOperationResult<Nothing>()
    object NotFound : DriveOperationResult<Nothing>()
}
```

### DriveBackupFile

```kotlin
data class DriveBackupFile(
    val id: String,
    val name: String,
    val size: Long,
    val createdTime: Long
)
```

### UploadResult / DownloadResult

```kotlin
data class UploadResult(
    val file: DriveFile,
    val wasUpdated: Boolean,
    val durationMs: Long
)

data class DownloadResult(
    val localPath: String,
    val size: Long,
    val durationMs: Long
)
```

### Example

```kotlin
// List backups on Google Drive
when (val result = syncClient.listDriveBackups()) {
    is DriveOperationResult.Success -> {
        result.data.forEach { backup ->
            Log.d("Drive", "${backup.name}: ${backup.size} bytes")
        }
    }
    is DriveOperationResult.Error -> {
        Log.e("Drive", result.message)
    }
    DriveOperationResult.NotSignedIn -> {
        // Prompt sign-in
    }
    else -> {}
}

// Create and upload backup in one step
when (val result = syncClient.createAndUploadBackup(
    passphrase = "secure123",
    backupName = "MyApp_Backup_${SimpleDateFormat("yyyyMMdd").format(Date())}"
)) {
    is DriveOperationResult.Success -> {
        Log.d("Drive", "Uploaded: ${result.data.file.name}")
    }
    is DriveOperationResult.Error -> {
        Log.e("Drive", result.message)
    }
    else -> {}
}

// Restore directly from Google Drive
val backups = (syncClient.listDriveBackups() as? DriveOperationResult.Success)?.data
val latestBackup = backups?.maxByOrNull { it.createdTime }

latestBackup?.let { backup ->
    when (val result = syncClient.restoreBackupFromDrive(backup.id, passphrase = "secure123")) {
        is RestoreResult.Success -> {
            Log.d("Restore", "Restored ${result.info.filesRestored} files from Drive")
        }
        is RestoreResult.Error -> {
            Log.e("Restore", result.message)
        }
    }
}
```

---

## Background Sync

### Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `schedulePeriodicSync(interval, networkPolicy?, requiresCharging?, syncMode?)` | `Unit` | Schedule periodic background sync |
| `cancelPeriodicSync()` | `Unit` | Cancel scheduled periodic sync |
| `isPeriodicSyncScheduled()` | `Boolean` | Check if periodic sync is scheduled |
| `requestSync(syncMode?, networkPolicy?)` | `Unit` | Request one-time background sync |
| `cancelSyncRequest()` | `Unit` | Cancel pending sync request |
| `observePeriodicSyncStatus()` | `Flow<SyncWorkStatus>` | Observe periodic sync work status |
| `observeSyncRequestStatus()` | `Flow<SyncWorkStatus>` | Observe one-time sync work status |

### SyncWorkStatus

```kotlin
data class SyncWorkStatus(
    val isScheduled: Boolean,
    val state: WorkInfo.State?,
    val progress: Int?,
    val errorMessage: String?
)
```

### Example

```kotlin
import kotlin.time.Duration.Companion.hours

// Schedule periodic sync every 6 hours on WiFi only
syncClient.schedulePeriodicSync(
    interval = 6.hours,
    networkPolicy = NetworkPolicy.WIFI_ONLY,
    requiresCharging = false,
    syncMode = SyncMode.BIDIRECTIONAL
)

// Check if scheduled
if (syncClient.isPeriodicSyncScheduled()) {
    Log.d("Sync", "Periodic sync is active")
}

// Observe sync work status
lifecycleScope.launch {
    syncClient.observePeriodicSyncStatus().collect { status ->
        when (status.state) {
            WorkInfo.State.RUNNING -> {
                Log.d("Sync", "Sync in progress: ${status.progress}%")
            }
            WorkInfo.State.SUCCEEDED -> {
                Log.d("Sync", "Sync completed")
            }
            WorkInfo.State.FAILED -> {
                Log.e("Sync", "Sync failed: ${status.errorMessage}")
            }
            else -> {}
        }
    }
}

// Request immediate one-time sync
syncClient.requestSync(
    syncMode = SyncMode.UPLOAD_ONLY,
    networkPolicy = NetworkPolicy.ANY
)

// Cancel all scheduled syncs
syncClient.cancelPeriodicSync()
syncClient.cancelSyncRequest()
```

---

## Network Monitoring

### NetworkMonitor

Access via dependency injection:

```kotlin
@Inject
lateinit var networkMonitor: NetworkMonitor
```

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `networkState` | `StateFlow<NetworkState>` | Observable network state |

### Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `startMonitoring()` | `Unit` | Start monitoring network changes |
| `stopMonitoring()` | `Unit` | Stop monitoring |
| `meetsPolicy(policy: NetworkPolicy)` | `Boolean` | Check if current network meets policy |
| `waitForNetwork(policy: NetworkPolicy)` | `Flow<NetworkState>` | Wait for network matching policy |

### NetworkState

```kotlin
data class NetworkState(
    val isConnected: Boolean,
    val isUnmetered: Boolean,
    val isWifi: Boolean,
    val isCellular: Boolean,
    val isRoaming: Boolean
)
```

### NetworkPolicy

| Policy | Description |
|--------|-------------|
| `ANY` | Any network connection |
| `UNMETERED_ONLY` | WiFi or unmetered connections only |
| `WIFI_ONLY` | WiFi connections only |
| `NOT_ROAMING` | Any connection except roaming |

### Example

```kotlin
// Observe network state
lifecycleScope.launch {
    networkMonitor.networkState.collect { state ->
        syncButton.isEnabled = state.isConnected
        wifiIcon.isVisible = state.isWifi
    }
}

// Check before sync
if (networkMonitor.meetsPolicy(NetworkPolicy.UNMETERED_ONLY)) {
    syncClient.sync()
} else {
    showMessage("Waiting for WiFi...")
}

// Wait for suitable network
lifecycleScope.launch {
    networkMonitor.waitForNetwork(NetworkPolicy.WIFI_ONLY)
        .first { it.isWifi }

    // WiFi is now available
    syncClient.sync()
}
```

---

## File Filtering

### FileFilter Types

```kotlin
sealed class FileFilter {
    data class ExtensionFilter(val extensions: Set<String>, val include: Boolean)
    data class SizeFilter(val minBytes: Long?, val maxBytes: Long?)
    data class GlobFilter(val pattern: String, val include: Boolean)
    data class RegexFilter(val pattern: Regex, val include: Boolean)
    data class HiddenFilter(val includeHidden: Boolean)
    data class PathPrefixFilter(val prefix: String, val include: Boolean)
    data class CustomFilter(val predicate: (File) -> Boolean)
    data class CompositeFilter(val filters: List<FileFilter>, val mode: CompositeMode)
}

enum class CompositeMode { ALL, ANY }
```

### Factory Methods

```kotlin
// Extension filters
FileFilter.includeExtensions("txt", "json", "xml")
FileFilter.excludeExtensions("tmp", "cache", "log")

// Size filters
FileFilter.maxSize(50 * 1024 * 1024)  // 50 MB
FileFilter.minSize(1024)              // 1 KB
FileFilter.sizeRange(1024, 50 * 1024 * 1024)

// Pattern filters
FileFilter.includePattern("*.doc")
FileFilter.excludePattern("*.swp")
FileFilter.includeRegex(Regex("report_\\d+\\.pdf"))

// Other filters
FileFilter.excludeHidden()
FileFilter.includePath("documents/")
FileFilter.custom { file -> file.lastModified() > lastSyncTime }

// Composite filters
FileFilter.all(filter1, filter2, filter3)  // All must pass
FileFilter.any(filter1, filter2)           // Any can pass

// Default sync filter (excludes hidden, tmp, cache, log)
FileFilter.defaultSyncFilter()
```

### Operators

```kotlin
// Combine filters
val combined = filter1 and filter2  // Both must pass
val either = filter1 or filter2     // Either can pass
val inverted = !filter              // Negate filter
```

### Example

```kotlin
syncClient.configure {
    rootFolderName("MyApp")
    syncDirectory(context.filesDir)

    // Only sync documents under 10MB, excluding temp files
    fileFilter(
        FileFilter.includeExtensions("pdf", "doc", "docx", "txt") and
        FileFilter.maxSize(10 * 1024 * 1024) and
        FileFilter.excludePattern("*.tmp") and
        FileFilter.excludeHidden()
    )
}
```

---

## Encryption

### EncryptionConfig

```kotlin
sealed class EncryptionConfig {
    object None : EncryptionConfig()
    object DeviceKeystore : EncryptionConfig()  // Hardware-backed, device-specific
    data class Passphrase(val passphrase: String) : EncryptionConfig()  // Portable
}
```

### Encryption Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `isPassphraseValid(passphrase: String)` | `Boolean` | Validate passphrase meets requirements |
| `estimatePassphraseStrength(passphrase: String)` | `PassphraseStrength` | Check passphrase strength |
| `detectEncryptionType(file: File)` | `EncryptionType` | Detect encryption type of file |
| `hasDeviceEncryptionKey()` | `Boolean` | Check if device encryption key exists |

### PassphraseStrength

| Strength | Description |
|----------|-------------|
| `WEAK` | Insufficient for secure encryption |
| `MEDIUM` | Acceptable but could be stronger |
| `STRONG` | Good passphrase strength |
| `VERY_STRONG` | Excellent passphrase strength |

### EncryptionType

| Type | Description |
|------|-------------|
| `NONE` | File is not encrypted |
| `DEVICE_KEYSTORE` | Encrypted with device-specific key |
| `PASSPHRASE` | Encrypted with passphrase |

### Exceptions

```kotlin
class WeakPassphraseException(message: String) : Exception(message)
class WrongPassphraseException(message: String) : Exception(message)
class CorruptedFileException(message: String) : Exception(message)
```

### Example

```kotlin
// Check passphrase strength before creating backup
val passphrase = passwordInput.text.toString()
when (syncClient.estimatePassphraseStrength(passphrase)) {
    PassphraseStrength.WEAK -> {
        showError("Please use a stronger passphrase")
        return
    }
    PassphraseStrength.MEDIUM -> {
        showWarning("Consider using a stronger passphrase")
    }
    else -> {
        // Proceed with backup
    }
}

// Create backup with passphrase encryption
val result = syncClient.createBackup(
    outputFile = backupFile,
    passphrase = passphrase
)

// Or use device keystore (not portable between devices)
val result = syncClient.createBackup(
    outputFile = backupFile,
    useDeviceKeystore = true
)
```

---

## Models & Types

### DriveFile

```kotlin
data class DriveFile(
    val id: String,
    val name: String,
    val size: Long,
    val modifiedTime: Long,
    val md5Checksum: String?,
    val mimeType: String,
    val parents: List<String>
)
```

### DriveFolder

```kotlin
data class DriveFolder(
    val id: String,
    val name: String,
    val parents: List<String>
)
```

### SyncItem

```kotlin
data class SyncItem(
    val relativePath: String,
    val name: String,
    val localFile: File?,
    val remoteFile: DriveFile?,
    val action: SyncAction,
    val conflict: ConflictInfo?
)
```

### SyncAction

| Action | Description |
|--------|-------------|
| `UPLOAD` | Upload local file to cloud |
| `DOWNLOAD` | Download cloud file to local |
| `DELETE_LOCAL` | Delete local file |
| `DELETE_REMOTE` | Delete cloud file |
| `SKIP` | Skip this file |
| `CONFLICT` | File has conflict |

### BackupManifest

```kotlin
data class BackupManifest(
    val version: Int,
    val createdAt: Long,
    val appVersion: String?,
    val fileCount: Int,
    val totalSize: Long,
    val encrypted: Boolean,
    val encryptionType: EncryptionType,
    val files: List<BackupFileEntry>
)
```

### ResumeInfo

```kotlin
data class ResumeInfo(
    val timestamp: Long,
    val syncMode: SyncMode,
    val rootFolder: String,
    val pendingFiles: List<String>,
    val completedFiles: List<String>,
    val totalFiles: Int,
    val bytesTransferred: Long,
    val totalBytes: Long
) {
    val progressPercent: Float
    fun isValid(timeoutMs: Long): Boolean
}
```

---

## Complete Integration Example

```kotlin
@HiltViewModel
class SyncViewModel @Inject constructor(
    private val syncClient: GoogleSyncClient,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    val authState = syncClient.authState
    val syncProgress = syncClient.syncProgress
    val networkState = networkMonitor.networkState

    init {
        // Configure client
        syncClient.configure {
            rootFolderName("MyApp")
            conflictPolicy(ConflictPolicy.NEWER_WINS)
            networkPolicy(NetworkPolicy.UNMETERED_ONLY)
            excludeExtensions("tmp", "cache", "log")
        }

        // Set up conflict resolution
        syncClient.setConflictCallback { conflict ->
            // Default to newer wins for automated resolution
            if (conflict.localModifiedTime > conflict.remoteModifiedTime) {
                ConflictResolution.UseLocal
            } else {
                ConflictResolution.UseRemote
            }
        }

        // Schedule background sync
        viewModelScope.launch {
            if (!syncClient.isPeriodicSyncScheduled()) {
                syncClient.schedulePeriodicSync(
                    interval = 6.hours,
                    networkPolicy = NetworkPolicy.WIFI_ONLY
                )
            }
        }
    }

    fun getSignInIntent() = syncClient.getSignInIntent()

    fun handleSignInResult(data: Intent?) = viewModelScope.launch {
        when (val result = syncClient.handleSignInResult(data)) {
            is AuthResult.Success -> {
                // Trigger initial sync
                sync()
            }
            is AuthResult.Error -> {
                _error.value = result.message
            }
            else -> {}
        }
    }

    fun sync() = viewModelScope.launch {
        if (!networkMonitor.meetsPolicy(NetworkPolicy.UNMETERED_ONLY)) {
            _error.value = "Please connect to WiFi to sync"
            return@launch
        }

        when (val result = syncClient.sync()) {
            is SyncResult.Success -> {
                _message.value = "Synced ${result.filesUploaded + result.filesDownloaded} files"
            }
            is SyncResult.Error -> {
                _error.value = result.message
            }
            else -> {}
        }
    }

    fun createBackup(passphrase: String) = viewModelScope.launch {
        val backupFile = File(context.cacheDir, "backup.zip")
        when (val result = syncClient.createAndUploadBackup(passphrase)) {
            is DriveOperationResult.Success -> {
                _message.value = "Backup uploaded to Google Drive"
            }
            is DriveOperationResult.Error -> {
                _error.value = result.message
            }
            else -> {}
        }
    }

    fun signOut() = viewModelScope.launch {
        syncClient.cancelPeriodicSync()
        syncClient.signOut()
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
}
```
