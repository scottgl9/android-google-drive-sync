# Configuration Reference

> Detailed configuration options for Android Google Drive Sync library

## SyncConfiguration

The main configuration object for the sync library.

### Builder Methods

| Method | Type | Default | Description |
|--------|------|---------|-------------|
| `appFolderName(name)` | String | Required | Root folder name on Google Drive |
| `addSyncDirectory(dir)` | SyncDirectory | None | Add a directory to sync |
| `addFileFilter(filter)` | FileFilter | None | Add file inclusion/exclusion filter |
| `conflictPolicy(policy)` | ConflictPolicy | NEWER_WINS | How to resolve conflicts |
| `checksumAlgorithm(algo)` | ChecksumAlgorithm | MD5 | Hash algorithm for change detection |
| `networkPolicy(policy)` | NetworkPolicy | ANY | Network requirements |
| `retryPolicy(policy)` | RetryPolicy | Default | Retry configuration |
| `cachePolicy(policy)` | CachePolicy | Default | Metadata caching |

### Example

```kotlin
val config = SyncConfiguration.builder()
    .appFolderName("MyApp")
    .addSyncDirectory(SyncDirectory("documents", SyncMode.BIDIRECTIONAL))
    .addSyncDirectory(SyncDirectory("images", SyncMode.UPLOAD_ONLY))
    .addFileFilter(FileFilter.excludeExtensions("tmp", "cache"))
    .addFileFilter(FileFilter.maxSize(100.megabytes))
    .conflictPolicy(ConflictPolicy.NEWER_WINS)
    .checksumAlgorithm(ChecksumAlgorithm.MD5)
    .networkPolicy(NetworkPolicy.UNMETERED_ONLY)
    .retryPolicy(RetryPolicy(maxAttempts = 3))
    .build()
```

---

## SyncDirectory

Defines a directory to synchronize.

### Properties

| Property | Type | Description |
|----------|------|-------------|
| `path` | String | Relative path from app files directory |
| `mode` | SyncMode | Sync direction mode |
| `recursive` | Boolean | Include subdirectories (default: true) |

### SyncMode

| Value | Description |
|-------|-------------|
| `BIDIRECTIONAL` | Upload and download changes |
| `UPLOAD_ONLY` | Only upload local changes |
| `DOWNLOAD_ONLY` | Only download remote changes |
| `MIRROR` | Exact replica of source |

---

## FileFilter

Filter files for inclusion or exclusion.

### Factory Methods

```kotlin
// Exclude by extension
FileFilter.excludeExtensions("tmp", "cache", "log")

// Include only specific extensions
FileFilter.includeExtensions("pdf", "doc", "docx")

// Maximum file size
FileFilter.maxSize(50.megabytes)

// Minimum file size
FileFilter.minSize(1.kilobytes)

// Exclude hidden files (starting with .)
FileFilter.excludeHidden()

// Custom glob pattern
FileFilter.excludePattern("*.tmp")
FileFilter.includePattern("*.pdf")

// Custom predicate
FileFilter.custom { file -> file.name.startsWith("sync_") }
```

---

## ConflictPolicy

How to handle files modified on both local and remote.

| Policy | Description |
|--------|-------------|
| `LOCAL_WINS` | Local file always overwrites remote |
| `REMOTE_WINS` | Remote file always overwrites local |
| `NEWER_WINS` | File with newer modification timestamp wins |
| `KEEP_BOTH` | Keep both files; rename local with conflict suffix |
| `ASK_USER` | Invoke callback to let user decide |
| `SKIP` | Skip conflicting files; log warning |

### ASK_USER Example

```kotlin
syncClient.fullSync(
    conflictCallback = { local, remote ->
        // Show dialog to user
        when (userChoice) {
            Choice.KEEP_LOCAL -> ConflictResolution.UseLocal
            Choice.KEEP_REMOTE -> ConflictResolution.UseRemote
            Choice.KEEP_BOTH -> ConflictResolution.KeepBoth("_conflict")
            else -> ConflictResolution.Skip
        }
    }
)
```

---

## ChecksumAlgorithm

Algorithm for calculating file checksums.

| Algorithm | Speed | Collision Resistance |
|-----------|-------|---------------------|
| `MD5` | Fast | Good for most use cases |
| `SHA256` | Slower | Better collision resistance |

---

## NetworkPolicy

Network requirements for sync operations.

| Policy | Description |
|--------|-------------|
| `ANY` | Any network connection |
| `UNMETERED_ONLY` | WiFi or unmetered connections only |
| `WIFI_ONLY` | WiFi connections only |
| `NOT_ROAMING` | Any connection except roaming |

---

## RetryPolicy

Configuration for automatic retry on transient failures.

### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `maxAttempts` | Int | 3 | Maximum retry attempts |
| `initialDelay` | Duration | 1 second | Delay before first retry |
| `maxDelay` | Duration | 30 seconds | Maximum delay between retries |
| `multiplier` | Double | 2.0 | Exponential backoff multiplier |
| `retryableErrors` | Set | Network, RateLimited | Errors that trigger retry |

### Example

```kotlin
RetryPolicy(
    maxAttempts = 5,
    initialDelay = 2.seconds,
    maxDelay = 60.seconds,
    multiplier = 1.5,
    retryableErrors = setOf(
        SyncErrorType.NETWORK_ERROR,
        SyncErrorType.RATE_LIMITED,
        SyncErrorType.SERVICE_UNAVAILABLE
    )
)
```

---

## CachePolicy

Configuration for metadata caching.

### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | Boolean | true | Enable caching |
| `maxAge` | Duration | 1 hour | Maximum cache age |
| `maxEntries` | Int | 10000 | Maximum cached entries |

---

## SyncScheduleConfig

Configuration for background periodic sync.

### Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `interval` | Duration | 12 hours | Time between syncs |
| `flexInterval` | Duration | 2 hours | Flexibility window |
| `initialDelay` | Duration | 0 | Delay before first sync |
| `requiresCharging` | Boolean | false | Only sync while charging |
| `requiresBatteryNotLow` | Boolean | true | Skip if battery low |
| `requiresDeviceIdle` | Boolean | false | Only sync when idle |

### Example

```kotlin
syncClient.schedulePeriodicSync(
    SyncScheduleConfig(
        interval = 6.hours,
        flexInterval = 1.hours,
        requiresCharging = true,
        requiresBatteryNotLow = true
    )
)
```
