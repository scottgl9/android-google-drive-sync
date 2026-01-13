# Troubleshooting Guide

> Common issues and solutions for Android Google Drive Sync library

## Authentication Issues

### Error: "Sign-in failed" or AUTH_FAILED

**Possible Causes:**
1. OAuth credentials not configured correctly
2. SHA-1 fingerprint mismatch
3. Package name mismatch

**Solutions:**
1. Verify your Google Cloud Console configuration:
   - Correct package name
   - Correct SHA-1 fingerprint for your build type (debug/release)
2. Check that Drive API is enabled in your project
3. Regenerate OAuth credentials if needed

```bash
# Get SHA-1 for debug builds
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1

# Get SHA-1 for release builds
keytool -list -v -keystore your-release.keystore -alias your-alias | grep SHA1
```

### Error: "User cancelled sign-in"

**Solution:**
This is expected behavior when user dismisses the sign-in dialog. Handle gracefully:

```kotlin
when (val result = syncClient.signIn(activity)) {
    is AuthResult.Cancelled -> {
        // User cancelled, don't show error
    }
    is AuthResult.Error -> {
        showError(result.message)
    }
    is AuthResult.Success -> {
        // Proceed with sync
    }
}
```

---

## Sync Issues

### Error: "Permission denied" or PERMISSION_DENIED

**Possible Causes:**
1. User didn't grant Drive permissions
2. Scopes not configured correctly

**Solutions:**
1. Request sign-in again with proper scopes
2. Verify OAuth consent screen includes required scopes:
   - `https://www.googleapis.com/auth/drive.file`

### Error: "Quota exceeded" or QUOTA_EXCEEDED

**Possible Causes:**
1. Google Drive storage quota exceeded
2. API quota limits reached

**Solutions:**
1. Check user's Drive storage usage
2. Implement rate limiting in your app
3. Use exponential backoff for retries

```kotlin
when (val result = syncClient.syncToCloud()) {
    is SyncResult.QuotaExceeded -> {
        showMessage("Storage quota exceeded. Please free up space in Google Drive.")
    }
    // Handle other cases
}
```

### Error: "Network unavailable"

**Solutions:**
1. Check device connectivity
2. Verify NetworkPolicy matches available networks
3. Handle offline gracefully:

```kotlin
when (val result = syncClient.fullSync()) {
    SyncResult.NetworkUnavailable -> {
        // Queue for later or inform user
        showMessage("No network. Sync will resume when connected.")
    }
    // Handle other cases
}
```

### Sync appears stuck or slow

**Possible Causes:**
1. Large number of files
2. Large file sizes
3. Slow network connection

**Solutions:**
1. Monitor progress:
```kotlin
lifecycleScope.launch {
    syncClient.observeSyncProgress().collect { progress ->
        Log.d("Sync", "Progress: ${progress.currentFile}/${progress.totalFiles}")
    }
}
```

2. Limit file sizes:
```kotlin
SyncConfiguration.builder()
    .addFileFilter(FileFilter.maxSize(50.megabytes))
    .build()
```

3. Use background sync for large operations

---

## Conflict Resolution Issues

### Files keep conflicting

**Possible Causes:**
1. Clock skew between devices
2. Rapid changes on multiple devices

**Solutions:**
1. Use checksum-based comparison instead of timestamps:
```kotlin
SyncConfiguration.builder()
    .checksumAlgorithm(ChecksumAlgorithm.SHA256)
    .build()
```

2. Choose a deterministic conflict policy:
```kotlin
SyncConfiguration.builder()
    .conflictPolicy(ConflictPolicy.LOCAL_WINS)  // or REMOTE_WINS
    .build()
```

### KEEP_BOTH creating too many duplicates

**Solution:**
Periodically clean up conflict files:
```kotlin
// Files are named with suffix like "_conflict_20240115"
// Implement cleanup logic in your app
```

---

## Background Sync Issues

### Background sync not running

**Possible Causes:**
1. Battery optimization killing WorkManager
2. Constraints not being met
3. App not whitelisted

**Solutions:**
1. Check WorkManager status:
```kotlin
val workInfos = WorkManager.getInstance(context)
    .getWorkInfosForUniqueWork("google_drive_sync")
    .get()
workInfos.forEach { info ->
    Log.d("Sync", "State: ${info.state}")
}
```

2. Request battery optimization exemption:
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    val pm = getSystemService(PowerManager::class.java)
    if (!pm.isIgnoringBatteryOptimizations(packageName)) {
        // Prompt user to disable battery optimization
    }
}
```

3. Relax constraints:
```kotlin
syncClient.schedulePeriodicSync(
    SyncScheduleConfig(
        requiresCharging = false,
        requiresBatteryNotLow = false
    )
)
```

---

## Build Issues

### Duplicate class errors

**Solution:**
Exclude conflicting dependencies:
```kotlin
configurations.all {
    exclude(group = "com.google.guava", module = "listenablefuture")
}
```

### Missing classes at runtime

**Solution:**
Check ProGuard rules. The library includes consumer rules, but you may need additional rules:
```proguard
-keep class com.google.api.** { *; }
-keep class com.google.apis.** { *; }
```

---

## Debugging

### Enable verbose logging

```kotlin
GoogleSyncClient.setLogLevel(LogLevel.VERBOSE)
```

### Capture sync logs

```kotlin
syncClient.observeSyncLogs().collect { log ->
    when (log.level) {
        LogLevel.ERROR -> Log.e("Sync", log.message, log.throwable)
        LogLevel.WARNING -> Log.w("Sync", log.message)
        LogLevel.INFO -> Log.i("Sync", log.message)
        LogLevel.DEBUG -> Log.d("Sync", log.message)
        LogLevel.VERBOSE -> Log.v("Sync", log.message)
    }
}
```

---

## Getting Help

If you're still experiencing issues:

1. Check the [GitHub Issues](https://github.com/vanespark/android-google-drive-sync/issues)
2. Search for similar issues or create a new one
3. Include:
   - Library version
   - Android version
   - Error message and stack trace
   - Steps to reproduce
   - Relevant configuration
