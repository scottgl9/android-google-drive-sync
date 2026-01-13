# Android Google Drive Sync Library

A robust, flexible Android library for synchronizing files with Google Drive.

## Features

### Core Sync
- **Bidirectional Sync**: Upload and download files between local storage and Google Drive
- **Checksum-Based Deduplication**: Skip unchanged files using MD5/SHA256 hashing
- **Recursive Subdirectory Support**: Full subdirectory sync with efficient O(1) file cache lookups
- **Conflict Resolution**: Multiple strategies (local wins, remote wins, newer wins, keep both, skip, ask user)
- **Pause/Resume**: Pause and resume sync operations mid-progress
- **Progress Tracking**: Real-time sync progress via Kotlin StateFlow

### Background & Scheduling
- **Background Sync**: WorkManager integration for scheduled periodic synchronization
- **Network Policies**: Configure sync to run only on WiFi, unmetered networks, or when not roaming

### Security & Backup
- **Encryption**: AES-256-GCM encryption with passphrase-based key derivation (PBKDF2)
- **Backup & Restore**: Create and restore encrypted ZIP backups with integrity verification
- **Database Backup Helper**: Safe SQLite database backup with WAL checkpoint, VACUUM INTO, and integrity checks

### Resilience
- **Retry Logic**: Exponential backoff with configurable retry policies
- **Rate Limiting**: Intelligent handling of Google API rate limits with batch processing
- **Multi-Device Safety**: Instance ID tracking to prevent data corruption from concurrent syncs
- **Upload Verification**: Post-upload checksum verification with automatic corruption handling

### Optimization
- **Compression**: GZIP compression for text files with automatic skip for already-compressed formats
- **File Filtering**: Flexible filters by extension, size, glob patterns, or custom predicates
- **Duplicate Removal**: Identify and remove duplicate files to free storage

### Developer Experience
- **Hilt Integration**: Full dependency injection support
- **Kotlin Coroutines**: Modern async/await patterns with Flow observables
- **Sync History**: Track and analyze sync operations with aggregated statistics

## Requirements

- Android SDK 26+ (Android 8.0 Oreo)
- Kotlin 1.9+
- Google Play Services

## Installation

### 1. Add the dependency

```kotlin
// build.gradle.kts (app module)
dependencies {
    implementation("com.vanespark:google-drive-sync:1.0.0")
}
```

### 2. Configure Google Cloud Console

1. Create a project in [Google Cloud Console](https://console.cloud.google.com/)
2. Enable the Google Drive API
3. Create OAuth 2.0 credentials (Android app)
4. Add your SHA-1 fingerprint and package name

### 3. Add required permissions

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Quick Start

### 1. Set up Hilt in your Application

The library uses Hilt for dependency injection. Ensure your app is set up with Hilt:

```kotlin
@HiltAndroidApp
class MyApplication : Application()
```

### 2. Inject and Configure the Client

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var syncClient: GoogleSyncClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure with your sync directory
        val syncDir = File(filesDir, "sync_data")
        syncDir.mkdirs()

        syncClient.configure {
            rootFolderName("MyApp")
            syncDirectory(syncDir)
            conflictPolicy(ConflictPolicy.NEWER_WINS)
            networkPolicy(NetworkPolicy.UNMETERED_ONLY)
            excludeExtensions("tmp", "bak", "log")
            excludeHiddenFiles()
        }
    }
}
```

### 3. Sign In with Google

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        lifecycleScope.launch {
            when (val authResult = syncClient.handleSignInResult(result.data)) {
                is AuthResult.Success -> showMessage("Signed in as ${authResult.email}")
                is AuthResult.Error -> showMessage("Error: ${authResult.message}")
                AuthResult.Cancelled -> showMessage("Sign-in cancelled")
                AuthResult.NeedsPermission -> showMessage("Permission required")
            }
        }
    }

    private fun signIn() {
        val intent = syncClient.getSignInIntent()
        signInLauncher.launch(intent)
    }

    private fun signOut() {
        lifecycleScope.launch {
            syncClient.signOut()
        }
    }
}
```

### 4. Perform Sync Operations

```kotlin
// Bidirectional sync (upload and download changes)
lifecycleScope.launch {
    when (val result = syncClient.sync()) {
        is SyncResult.Success -> {
            showMessage("Uploaded ${result.filesUploaded}, downloaded ${result.filesDownloaded}")
        }
        is SyncResult.PartialSuccess -> {
            showMessage("${result.filesSucceeded} succeeded, ${result.filesFailed} failed")
        }
        is SyncResult.Error -> showMessage("Error: ${result.message}")
        SyncResult.NotSignedIn -> promptSignIn()
        SyncResult.NetworkUnavailable -> showMessage("No network")
        SyncResult.Cancelled -> { /* User cancelled */ }
    }
}

// Upload only (local to cloud)
val uploadResult = syncClient.uploadOnly()

// Download only (cloud to local)
val downloadResult = syncClient.downloadOnly()

// Mirror modes (make one side match the other exactly)
val mirrorUpResult = syncClient.mirrorToCloud()     // Delete cloud files not in local
val mirrorDownResult = syncClient.mirrorFromCloud() // Delete local files not in cloud
```

### 5. Pause and Resume Sync

```kotlin
// Pause a running sync operation
syncClient.pauseSync()

// Resume a paused sync operation
lifecycleScope.launch {
    when (val result = syncClient.resumeSync()) {
        is SyncResult.Success -> showMessage("Sync completed")
        is SyncResult.Paused -> showMessage("Sync paused again")
        else -> handleResult(result)
    }
}

// Observe pause state
lifecycleScope.launch {
    syncClient.isPaused.collect { paused ->
        updatePauseButton(paused)
    }
}

// Cancel a running sync operation entirely
syncClient.cancelSync()
```

### 6. Schedule Background Sync

```kotlin
import kotlin.time.Duration.Companion.hours

// Schedule periodic sync every 12 hours
syncClient.schedulePeriodicSync(
    interval = 12.hours,
    networkPolicy = NetworkPolicy.UNMETERED_ONLY,
    requiresCharging = false,
    syncMode = SyncMode.BIDIRECTIONAL
)

// Check if periodic sync is scheduled
if (syncClient.isPeriodicSyncScheduled()) {
    showMessage("Periodic sync is active")
}

// Cancel periodic sync
syncClient.cancelPeriodicSync()

// Request immediate one-time sync
syncClient.requestSync(
    syncMode = SyncMode.BIDIRECTIONAL,
    networkPolicy = NetworkPolicy.ANY
)
```

### 7. Observe Sync Progress

```kotlin
// Collect progress updates
lifecycleScope.launch {
    syncClient.syncProgress.collect { progress ->
        updateUI(
            phase = progress.phase,
            currentFile = progress.currentFile,
            filesCompleted = progress.filesCompleted,
            totalFiles = progress.totalFiles,
            bytesTransferred = progress.bytesTransferred
        )
    }
}

// Check if sync is in progress
lifecycleScope.launch {
    syncClient.isSyncing.collect { syncing ->
        showSyncIndicator(syncing)
    }
}
```

### 8. Observe Auth State

```kotlin
lifecycleScope.launch {
    syncClient.authState.collect { state ->
        when (state) {
            is AuthState.NotSignedIn -> showSignInButton()
            is AuthState.SigningIn -> showProgress()
            is AuthState.SignedIn -> showUserInfo(state.email)
            is AuthState.Error -> showError(state.message)
            is AuthState.PermissionRequired -> requestPermissions()
        }
    }
}
```

### 9. View Sync History

```kotlin
// Observe sync history
lifecycleScope.launch {
    syncClient.syncHistory.collect { history ->
        updateHistoryList(history)
    }
}

// Get statistics
val stats = syncClient.getSyncStatistics()
showStats(
    totalSyncs = stats.totalSyncs,
    successful = stats.successfulSyncs,
    failed = stats.failedSyncs,
    uploaded = stats.totalFilesUploaded,
    downloaded = stats.totalFilesDownloaded,
    transferred = stats.totalBytesTransferred
)

// Clear history
syncClient.clearSyncHistory()
```

### 10. Compression

```kotlin
@Inject
lateinit var compressionManager: CompressionManager

// Configure compression
compressionManager.configure(CompressionConfig(
    level = CompressionLevel.DEFAULT,
    minSizeToCompress = 1024L, // Only compress files > 1KB
    skipExtensions = setOf("jpg", "png", "mp4", "zip", "gz")
))

// Check if file should be compressed
if (compressionManager.shouldCompress(file)) {
    val result = compressionManager.compress(file) { progress ->
        updateProgress(progress)
    }
    println("Compressed: ${result.percentSaved}% saved")
}

// Compress only if beneficial (smaller output)
val (resultFile, wasCompressed) = compressionManager.compressIfBeneficial(
    inputFile,
    outputDir
)
```

### 11. Encryption and Backup

```kotlin
@Inject
lateinit var backupManager: BackupManager

@Inject
lateinit var restoreManager: RestoreManager

// Create encrypted backup
lifecycleScope.launch {
    val result = backupManager.createBackup(
        sourceDir = syncDir,
        outputFile = File(backupDir, "backup.zip"),
        passphrase = "user-passphrase"
    ) { progress ->
        updateProgress(progress)
    }

    if (result is BackupResult.Success) {
        showMessage("Backup created: ${result.file.name}")
    }
}

// Restore from encrypted backup
lifecycleScope.launch {
    val result = restoreManager.restoreBackup(
        backupFile = File(backupDir, "backup.zip"),
        targetDir = restoreDir,
        passphrase = "user-passphrase"
    ) { progress ->
        updateProgress(progress)
    }

    if (result is RestoreResult.Success) {
        showMessage("Restored ${result.filesRestored} files")
    }
}
```

### 12. Database Backup (for Room/SQLite)

```kotlin
@Inject
lateinit var databaseBackupHelper: DatabaseBackupHelper

// Create a safe database snapshot (uses VACUUM INTO)
lifecycleScope.launch {
    val result = databaseBackupHelper.createSnapshot(
        sourcePath = database.path,
        targetPath = backupFile.path
    )

    when (result) {
        is DatabaseBackupResult.Success -> {
            // Include backupFile in sync
        }
        is DatabaseBackupResult.Error -> {
            showError(result.message)
        }
    }
}

// Restore database with integrity check
lifecycleScope.launch {
    // First verify the backup
    val integrityResult = databaseBackupHelper.checkIntegrity(backupFile.path)
    if (integrityResult.isValid) {
        // Safely replace the current database
        databaseBackupHelper.atomicReplace(
            backupPath = backupFile.path,
            targetPath = database.path
        )
        // Clean up WAL files after restore
        databaseBackupHelper.deleteWalFiles(database.path)
    }
}
```

## Configuration Options

```kotlin
syncClient.configure {
    // Required: Root folder name on Google Drive
    rootFolderName("MyApp")

    // Required: Local directory to sync
    syncDirectory(File(filesDir, "sync_data"))

    // Conflict resolution policy
    conflictPolicy(ConflictPolicy.NEWER_WINS)

    // Network requirements
    networkPolicy(NetworkPolicy.UNMETERED_ONLY)

    // File exclusions by extension
    excludeExtensions("tmp", "cache", "log")

    // Include only specific extensions
    includeExtensions("txt", "json", "pdf")

    // Maximum file size (in bytes)
    maxFileSize(50 * 1024 * 1024) // 50 MB

    // Exclude hidden files
    excludeHiddenFiles()

    // Custom file filter
    fileFilter(
        FileFilter.excludeExtensions("tmp") and
        FileFilter.maxSize(100 * 1024 * 1024) and
        FileFilter.excludeHidden()
    )
}
```

## Conflict Resolution Policies

| Policy | Description |
|--------|-------------|
| `LOCAL_WINS` | Local file always overwrites remote |
| `REMOTE_WINS` | Remote file always overwrites local |
| `NEWER_WINS` | File with newer timestamp wins |
| `KEEP_BOTH` | Keep both files (remote renamed with conflict suffix) |
| `SKIP` | Skip conflicting files entirely |
| `ASK_USER` | Callback to let user decide |

### Custom Conflict Handling

```kotlin
// Set callback for ASK_USER policy
syncClient.setConflictCallback { conflict ->
    // Show dialog to user and return their choice
    val userChoice = showConflictDialog(conflict)
    ConflictResolution(
        action = userChoice.action, // KEEP_LOCAL, KEEP_REMOTE, KEEP_BOTH, SKIP
        newName = userChoice.customName // Optional rename
    )
}
```

## Network Policies

| Policy | Description |
|--------|-------------|
| `ANY` | Sync on any network connection |
| `UNMETERED_ONLY` | Only sync on unmetered networks (WiFi) |
| `WIFI_ONLY` | Only sync on WiFi |
| `NOT_ROAMING` | Sync when not roaming |

## Sync Modes

| Mode | Description |
|------|-------------|
| `BIDIRECTIONAL` | Upload local changes and download remote changes |
| `UPLOAD_ONLY` | Only upload local files to cloud |
| `DOWNLOAD_ONLY` | Only download cloud files to local |
| `MIRROR_TO_CLOUD` | Make cloud match local exactly (deletes remote-only files) |
| `MIRROR_FROM_CLOUD` | Make local match cloud exactly (deletes local-only files) |

## File Filters

```kotlin
// Exclude by extension
FileFilter.excludeExtensions("tmp", "bak", "cache")

// Include only specific extensions
FileFilter.includeExtensions("txt", "json", "md")

// Size limits
FileFilter.maxSize(50 * 1024 * 1024) // 50 MB
FileFilter.minSize(1024) // At least 1 KB

// Hidden files
FileFilter.excludeHidden()
FileFilter.onlyHidden()

// Glob patterns
FileFilter.glob("**/*.txt")
FileFilter.glob("documents/**")

// Regex patterns
FileFilter.regex(".*\\.log$")

// Path prefix
FileFilter.pathPrefix("important/")

// Custom predicate
FileFilter.custom { file -> file.name.startsWith("sync_") }

// Combine filters
val filter = FileFilter.excludeExtensions("tmp") and
             FileFilter.maxSize(10 * 1024 * 1024) and
             FileFilter.excludeHidden()

// Or combine (any filter passes)
val filter = FileFilter.includeExtensions("txt") or
             FileFilter.includeExtensions("md")

// Negate
val filter = FileFilter.excludeExtensions("txt").not()
```

## Error Handling

```kotlin
when (val result = syncClient.sync()) {
    is SyncResult.Success -> {
        log("Synced: ${result.filesUploaded} up, ${result.filesDownloaded} down")
    }
    is SyncResult.PartialSuccess -> {
        log("Partial: ${result.filesSucceeded} ok, ${result.filesFailed} failed")
        result.errors.forEach { error ->
            log("Failed: ${error.file} - ${error.message}")
        }
    }
    is SyncResult.Error -> {
        log("Error: ${result.message}")
        result.cause?.let { handleException(it) }
    }
    SyncResult.NotSignedIn -> promptSignIn()
    SyncResult.NetworkUnavailable -> showOfflineMessage()
    SyncResult.Cancelled -> { /* User cancelled */ }
}
```

## Project Structure

```
android-google-drive-sync/
├── library/                    # Main library module
│   └── src/main/java/com/vanespark/googledrivesync/
│       ├── api/                # Public API (GoogleSyncClient)
│       ├── auth/               # Authentication (GoogleAuthManager)
│       ├── backup/             # Backup & restore (BackupManager, RestoreManager)
│       ├── cache/              # Manifest caching (SyncCache)
│       ├── compression/        # GZIP compression (CompressionManager)
│       ├── database/           # Database backup (DatabaseBackupHelper)
│       ├── di/                 # Hilt modules (GoogleSyncModule)
│       ├── drive/              # Drive operations (DriveService)
│       ├── encryption/         # AES-256-GCM encryption (CryptoManager)
│       ├── local/              # Local file operations (LocalFileManager)
│       ├── resilience/         # Retry & network (RetryPolicy, RateLimitHandler)
│       ├── sync/               # Sync engine (SyncManager, SyncEngine)
│       └── worker/             # Background sync (SyncWorker, SyncScheduler)
│   └── src/test/               # Unit tests
│       └── java/com/vanespark/googledrivesync/
│           ├── compression/    # CompressionManagerTest
│           ├── drive/          # DriveModelsTest
│           ├── local/          # FileHasherTest, FileFilterTest
│           ├── resilience/     # NetworkPolicyTest, RetryPolicyTest, SyncProgressTest
│           └── sync/           # SyncModelsTest, ConflictResolverTest, SyncHistoryTest
├── sample/                     # Sample application
│   └── src/main/java/com/vanespark/googledrivesync/sample/
│       ├── MainActivity.kt     # Main UI
│       ├── MainViewModel.kt    # ViewModel
│       ├── FileBrowserScreen.kt # File browser
│       └── SyncHistoryScreen.kt # Sync history
├── .github/                    # GitHub configuration
│   ├── workflows/ci.yml        # CI/CD pipeline
│   └── dependabot.yml          # Dependency updates
├── docs/                       # Documentation
│   ├── INTEGRATION.md          # Integration guide
│   ├── CONFIGURATION.md        # Configuration reference
│   └── TROUBLESHOOTING.md      # Common issues
├── AGENTS.md                   # Development guidelines
├── CHANGELOG.md                # Version history
├── TODO.md                     # Outstanding tasks
├── PROGRESS.md                 # Completed work
└── README.md                   # This file
```

## Building

```bash
# Build library
./gradlew :library:build

# Run tests
./gradlew :library:test

# Build sample app
./gradlew :sample:assembleDebug

# Run code quality checks
./gradlew detekt
```

## Testing

The library includes comprehensive unit tests:

```bash
# Run all tests
./gradlew :library:testDebugUnitTest

# Run specific test class
./gradlew :library:test --tests "*.FileFilterTest"

# Run tests with verbose output
./gradlew :library:testDebugUnitTest --info
```

Test coverage includes:
- `FileFilterTest` - All filter types and combinations
- `FileHasherTest` - MD5/SHA256 hashing
- `ConflictResolverTest` - All conflict policies
- `SyncModelsTest` - Data classes and enums
- `RetryPolicyTest` - Retry logic and backoff
- `CompressionManagerTest` - Compression/decompression and configuration
- `DriveModelsTest` - Drive file models and operation results
- `SyncProgressTest` - Progress tracking and sync phases
- `SyncHistoryTest` - Sync history entries and statistics
- `NetworkPolicyTest` - Network policies and rate limiting

## Sample App

The sample app demonstrates all library features:

- **Authentication**: Sign in/out with Google
- **Sync Operations**: Bidirectional, upload, download
- **Progress Tracking**: Real-time sync progress
- **File Browser**: View and manage synced files
- **Sync History**: View past sync operations
- **Settings**: Configure periodic sync

Run the sample:

```bash
./gradlew :sample:installDebug
```

## Documentation

- [AGENTS.md](AGENTS.md) - Development guidelines and architecture
- [TODO.md](TODO.md) - Outstanding tasks and roadmap
- [PROGRESS.md](PROGRESS.md) - Completed work and milestones
- [docs/INTEGRATION.md](docs/INTEGRATION.md) - Integration guide
- [docs/CONFIGURATION.md](docs/CONFIGURATION.md) - Configuration reference
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) - Common issues

## Roadmap

### Completed Features

- [x] Bidirectional sync with conflict resolution
- [x] Backup/Restore API (create/restore ZIP backups)
- [x] Encryption at rest (AES-256-GCM with PBKDF2)
- [x] Rate limiting and resilience improvements
- [x] Recursive subdirectory sync with file cache
- [x] GZIP compression for compressible files
- [x] Sync pause/resume functionality
- [x] Database backup helper (WAL checkpoint, VACUUM INTO)
- [x] Multi-device safety with instance ID tracking
- [x] Duplicate file removal
- [x] Upload integrity verification
- [x] GitHub Actions CI/CD pipeline

### Planned Features

- [ ] Chunked upload for large files (>100MB)
- [ ] Parallel upload/download operations
- [ ] Google Drive Shared Drives support
- [ ] Real-time sync with Drive push notifications
- [ ] Room database persistence for sync state

See [TODO.md](TODO.md) for the complete roadmap.

## License

TBD

## References

- [Google Drive API v3](https://developers.google.com/drive/api/v3/about-sdk)
- [Google Sign-In for Android](https://developers.google.com/identity/sign-in/android)
- [Android WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)
- [Hilt Dependency Injection](https://dagger.dev/hilt/)
