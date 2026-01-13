# Android Google Drive Sync Library

A robust, flexible Android library for synchronizing files with Google Drive.

## Features

- **Bidirectional Sync**: Upload and download files between local storage and Google Drive
- **Checksum-Based Deduplication**: Skip unchanged files using MD5/SHA256 hashing
- **Conflict Resolution**: Multiple strategies (local wins, remote wins, newer wins, keep both)
- **Background Sync**: WorkManager integration for scheduled periodic synchronization
- **Resilient Operations**: Retry logic, exponential backoff, and error recovery
- **Flexible Configuration**: Configurable sync policies, folder structures, and file filters
- **Progress Tracking**: Real-time sync progress via Kotlin Flow

## Requirements

- Android SDK 26+ (Android 8.0 Oreo)
- Kotlin 1.9+
- Google Play Services

## Installation

```kotlin
// build.gradle.kts (app module)
dependencies {
    implementation("com.vanespark:google-drive-sync:1.0.0")
}
```

## Quick Start

### 1. Initialize the Library

```kotlin
// In your Application class or Hilt module
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides
    @Singleton
    fun provideSyncClient(
        @ApplicationContext context: Context
    ): GoogleSyncClient {
        return GoogleSyncClient.create(context) {
            appFolderName("MyApp")
            conflictPolicy(ConflictPolicy.NEWER_WINS)
            networkPolicy(NetworkPolicy.UNMETERED_ONLY)
        }
    }
}
```

### 2. Sign In with Google

```kotlin
class MainActivity : ComponentActivity() {
    @Inject lateinit var syncClient: GoogleSyncClient

    private fun signIn() {
        lifecycleScope.launch {
            when (val result = syncClient.signIn(this@MainActivity)) {
                is AuthResult.Success -> showMessage("Signed in as ${result.email}")
                is AuthResult.Cancelled -> showMessage("Sign-in cancelled")
                is AuthResult.Error -> showMessage("Error: ${result.message}")
            }
        }
    }
}
```

### 3. Sync Files

```kotlin
// Upload to Google Drive
lifecycleScope.launch {
    val result = syncClient.syncToCloud()
    when (result) {
        is SyncResult.Success -> {
            showMessage("Uploaded ${result.filesUploaded} files")
        }
        is SyncResult.Error -> {
            showMessage("Sync failed: ${result.message}")
        }
        // Handle other cases...
    }
}

// Download from Google Drive
lifecycleScope.launch {
    val result = syncClient.syncFromCloud()
    // Handle result...
}

// Full bidirectional sync
lifecycleScope.launch {
    val result = syncClient.fullSync()
    // Handle result...
}
```

### 4. Schedule Background Sync

```kotlin
// Schedule periodic sync every 12 hours
syncClient.schedulePeriodicSync(
    interval = 12.hours,
    constraints = SyncConstraints(
        requiresUnmeteredNetwork = true,
        requiresBatteryNotLow = true
    )
)
```

### 5. Observe Sync Progress

```kotlin
lifecycleScope.launch {
    syncClient.observeSyncProgress().collect { progress ->
        updateProgressUI(
            current = progress.currentFile,
            total = progress.totalFiles,
            percentage = progress.percentage
        )
    }
}
```

## Configuration Options

```kotlin
GoogleSyncClient.create(context) {
    // Required: Root folder name on Google Drive
    appFolderName("MyApp")

    // Sync directories (relative to app files dir)
    addSyncDirectory(SyncDirectory("documents", SyncMode.BIDIRECTIONAL))
    addSyncDirectory(SyncDirectory("images", SyncMode.UPLOAD_ONLY))
    addSyncDirectory(SyncDirectory("backups", SyncMode.DOWNLOAD_ONLY))

    // File filters
    addFileFilter(FileFilter.excludeExtensions("tmp", "cache"))
    addFileFilter(FileFilter.maxSize(50.megabytes))

    // Conflict handling
    conflictPolicy(ConflictPolicy.NEWER_WINS)

    // Checksum algorithm
    checksumAlgorithm(ChecksumAlgorithm.MD5) // or SHA256

    // Network policy
    networkPolicy(NetworkPolicy.UNMETERED_ONLY)

    // Retry configuration
    retryPolicy(RetryPolicy(
        maxAttempts = 3,
        initialDelay = 1.seconds,
        maxDelay = 30.seconds
    ))
}
```

## Conflict Resolution Policies

| Policy | Description |
|--------|-------------|
| `LOCAL_WINS` | Local file always overwrites remote |
| `REMOTE_WINS` | Remote file always overwrites local |
| `NEWER_WINS` | File with newer timestamp wins |
| `KEEP_BOTH` | Keep both files (rename local with suffix) |
| `ASK_USER` | Callback to let user decide |
| `SKIP` | Skip conflicting files |

## Backup & Restore

```kotlin
// Create a backup
val backupResult = syncClient.createBackup(BackupConfig(
    includeDatabase = true,
    compress = true
))

// List available backups
val backups = syncClient.listBackups()

// Restore from backup
val restoreResult = syncClient.restoreBackup(backupId)
```

## Error Handling

```kotlin
when (val result = syncClient.fullSync()) {
    is SyncResult.Success -> { /* Success */ }
    is SyncResult.PartialSuccess -> {
        // Some files synced, some failed
        result.errors.forEach { error ->
            log("Failed: ${error.file} - ${error.message}")
        }
    }
    is SyncResult.Error -> {
        when (result.type) {
            SyncErrorType.AUTH_FAILED -> promptReAuth()
            SyncErrorType.NETWORK_ERROR -> showOfflineMessage()
            SyncErrorType.QUOTA_EXCEEDED -> showQuotaWarning()
            else -> showGenericError(result.message)
        }
    }
    SyncResult.NotSignedIn -> promptSignIn()
    SyncResult.NetworkUnavailable -> showOfflineMessage()
    SyncResult.Cancelled -> { /* User cancelled */ }
}
```

## Project Structure

```
android-google-drive-sync/
├── library/          # Main library module
├── sample/           # Sample application
├── docs/             # Documentation
├── AGENTS.md         # Development guidelines
├── TODO.md           # Outstanding tasks
├── PROGRESS.md       # Completed items
└── README.md         # This file
```

## Building

```bash
# Build library
./gradlew :library:build

# Run tests
./gradlew :library:test

# Build sample app
./gradlew :sample:assembleDebug
```

## Documentation

- [AGENTS.md](AGENTS.md) - Development guidelines and architecture
- [TODO.md](TODO.md) - Outstanding tasks and roadmap
- [PROGRESS.md](PROGRESS.md) - Completed work and milestones
- [docs/INTEGRATION.md](docs/INTEGRATION.md) - Integration guide
- [docs/CONFIGURATION.md](docs/CONFIGURATION.md) - Configuration reference
- [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) - Common issues

## License

TBD

## References

- [Google Drive API v3](https://developers.google.com/drive/api/v3/about-sdk)
- [Google Sign-In for Android](https://developers.google.com/identity/sign-in/android)
- [Android WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
