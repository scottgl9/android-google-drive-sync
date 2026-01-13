# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned
- Room database persistence for sync state
- Chunked upload for large files
- Parallel upload/download operations
- Compression before upload

## [0.2.0] - 2026-01-13

### Added
- **Encryption Module**
  - `CryptoManager` - AES-256-GCM encryption/decryption
  - `PassphraseBasedCrypto` - PBKDF2 key derivation from passphrases
  - `EncryptionManager` - High-level encryption coordinator
  - Secure random IV generation for each encryption operation

- **Backup/Restore Module**
  - `BackupManager` - Create encrypted ZIP backups with integrity verification
  - `RestoreManager` - Restore and verify encrypted backups
  - Progress callbacks for backup/restore operations
  - Backup metadata with timestamps and checksums

- **Resilience Improvements**
  - `RateLimitHandler` - Google Drive API rate limit handling with exponential backoff
  - `RateLimitException` - Custom exception with retry delay calculation
  - Enhanced `SyncProgressManager` with full resume state persistence
  - `ResumeInfo` - Serializable state for interrupted sync recovery

- **Recursive File Listing**
  - `listAllFilesRecursive()` - Recursively list all files in subdirectories
  - `buildFileCache()` - Build O(1) lookup cache for efficient sync
  - `DriveFileWithPath` - File wrapper with relative path from sync root
  - `DriveFileCache` - Maps paths to files and checksums to paths
  - Fixed subdirectory sync: files now matched by full relative path

### Changed
- `buildRemoteManifest()` now uses recursive listing for proper subdirectory support
- `FileManifestEntry` now includes `driveFileId` for direct file downloads

## [0.1.0] - 2026-01-13

### Added
- **Core Library**
  - `GoogleSyncClient` - Main entry point for all sync operations
  - `GoogleAuthManager` - Google Sign-In and OAuth2 token management
  - `DriveService` - High-level Google Drive API wrapper
  - `DriveFileOperations` - File CRUD operations
  - `DriveFolderManager` - Folder hierarchy management
  - `LocalFileManager` - Local file I/O operations
  - `FileHasher` - MD5 and SHA256 checksum calculation
  - `FileFilter` - Flexible file filtering with composable filters

- **Sync Engine**
  - `SyncManager` - Main sync orchestrator
  - `SyncEngine` - Core sync algorithm with manifest comparison
  - `ConflictResolver` - Policy-based conflict resolution
  - Five sync modes: BIDIRECTIONAL, UPLOAD_ONLY, DOWNLOAD_ONLY, MIRROR_TO_CLOUD, MIRROR_FROM_CLOUD
  - Six conflict policies: LOCAL_WINS, REMOTE_WINS, NEWER_WINS, KEEP_BOTH, SKIP, ASK_USER

- **Background Sync**
  - `SyncWorker` - WorkManager CoroutineWorker for background sync
  - `SyncScheduler` - Periodic and one-time sync scheduling
  - Configurable network constraints and charging requirements

- **Resilience**
  - `RetryPolicy` - Exponential backoff retry logic
  - `NetworkMonitor` - Real-time network state monitoring
  - `SyncProgressManager` - Progress tracking with persistence

- **Cache**
  - `SyncCache` - In-memory manifest caching with disk persistence
  - Configurable cache expiration

- **Sync History**
  - `SyncHistoryManager` - Track and persist sync operations
  - `SyncStatistics` - Aggregated sync statistics

- **Configuration**
  - `SyncClientConfigBuilder` - Fluent configuration API
  - Network policies: ANY, UNMETERED_ONLY, WIFI_ONLY, NOT_ROAMING
  - File extension, size, and hidden file filtering

- **Sample App**
  - Full Jetpack Compose UI
  - Authentication screen with sign-in/sign-out
  - Sync controls for all modes
  - Real-time progress tracking
  - File browser with create/delete operations
  - Sync history with statistics

- **Testing**
  - Unit tests for FileFilter, FileHasher
  - Unit tests for ConflictResolver
  - Unit tests for SyncModels
  - Unit tests for RetryPolicy

- **Documentation**
  - Comprehensive README with API examples
  - INTEGRATION.md setup guide
  - CONFIGURATION.md reference
  - TROUBLESHOOTING.md common issues
  - AGENTS.md development guidelines

### Technical Details
- Kotlin 1.9.22
- Android SDK 26+ (Android 8.0 Oreo)
- Hilt for dependency injection
- Kotlin Coroutines and Flow for async operations
- WorkManager for background processing
- Google Drive API v3
- JUnit 5, Truth, and MockK for testing

---

## Version History Summary

| Version | Date | Description |
|---------|------|-------------|
| 0.2.0 | 2026-01-13 | Encryption, backup/restore, resilience, recursive file listing |
| 0.1.0 | 2026-01-13 | Initial release with full sync functionality |

[Unreleased]: https://github.com/scottgl9/android-google-drive-sync/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/scottgl9/android-google-drive-sync/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/scottgl9/android-google-drive-sync/releases/tag/v0.1.0
