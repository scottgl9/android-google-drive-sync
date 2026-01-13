# TODO.md - Android Google Drive Sync Library

> Track outstanding tasks, features, and improvements

---

## Phase 1: Project Setup

- [x] Create Gradle project structure with library and sample modules
- [x] Configure build.gradle.kts with required dependencies
- [x] Set up Hilt dependency injection
- [x] Configure detekt for code quality
- [x] Create .gitignore with appropriate rules
- [ ] Set up GitHub Actions CI pipeline
- [x] Configure library publishing (Maven)

---

## Phase 2: Core Infrastructure

### Authentication Module (`auth/`)

- [x] Implement `GoogleAuthManager` with Google Sign-In
- [x] Handle OAuth2 token management and refresh
- [x] Create `AuthState` sealed class for auth states
- [x] Create `AuthResult` sealed class for auth operations
- [x] Create `AuthConfig` data class for configuration
- [x] Add sign-out and account switching support
- [ ] Write unit tests for auth flows (requires Android framework mocking)

### Drive Operations Module (`drive/`)

- [x] Implement `DriveService` wrapper for Drive API v3
- [x] Create `DriveFileOperations` for CRUD operations
  - [x] Upload file (create/update)
  - [x] Download file
  - [x] Delete file
  - [x] List files with pagination
  - [x] Get file metadata
- [x] Implement `DriveFolderManager` for folder hierarchy
  - [x] Create folder
  - [x] Find folder by name/path
  - [x] Ensure folder hierarchy exists
- [x] Define `DriveModels` data classes (DriveFile, DriveFolder, DriveOperationResult, etc.)
- [ ] Write comprehensive tests

### Local File Module (`local/`)

- [x] Implement `LocalFileManager` for local file I/O
- [x] Create `FileHasher` with MD5 and SHA256 support
- [x] Implement `FileFilter` for include/exclude patterns
  - [x] Glob pattern matching
  - [x] Extension filtering
  - [x] Size filtering
  - [x] Hidden file handling
- [x] Write tests for FileFilter
- [x] Write tests for FileHasher

### Resilience Module (`resilience/`)

- [x] Implement `RetryPolicy` with exponential backoff
- [x] Create `NetworkMonitor` for connectivity checking
- [x] Implement `SyncProgressManager` for progress tracking
- [x] Write tests for RetryPolicy

### Dependency Injection (`di/`)

- [x] Create `GoogleSyncModule` Hilt module
- [x] Configure all singleton dependencies

---

## Phase 3: Sync Engine

### Sync Core (`sync/`)

- [x] Implement `SyncManager` as main orchestrator
- [x] Create `SyncEngine` with core sync logic
  - [x] Upload flow with checksum comparison
  - [x] Download flow with checksum comparison
  - [x] Full bidirectional sync
  - [x] Delta sync (changes only)
- [x] Implement `SyncPolicy` strategies
  - [x] Upload-only mode
  - [x] Download-only mode
  - [x] Bidirectional mode
  - [x] Mirror mode (exact replica)
- [x] Create `ConflictResolver` with policies
  - [x] LOCAL_WINS
  - [x] REMOTE_WINS
  - [x] NEWER_WINS
  - [x] KEEP_BOTH
  - [x] ASK_USER (callback)
  - [x] SKIP
- [x] Implement `SyncState` tracking
- [x] Add progress reporting via Flow
- [x] Write tests for ConflictResolver
- [x] Write tests for SyncModels
- [ ] Write tests for SyncEngine (integration tests)

### Cache Layer (`cache/`)

- [x] Implement `SyncCache` for metadata caching
- [x] Create manifest caching with disk persistence
- [x] Define `CacheConfig` for invalidation rules
- [x] Add cache persistence (JSON file-based)
- [ ] Write cache tests

---

## Phase 4: Resilience & Background Sync

### Resilience Module (`resilience/`)

- [x] Implement `RetryPolicy` with exponential backoff
- [x] Create `NetworkMonitor` for connectivity checking
- [x] Implement error recovery via retry policies
- [ ] Add operation queueing for offline support
- [x] Handle rate limiting gracefully
- [ ] Write resilience tests

### Worker Module (`worker/`)

- [x] Implement `SyncWorker` for WorkManager
- [x] Create `SyncScheduler` for periodic scheduling
- [x] Define `SyncScheduleConfig` configuration
- [x] Add one-time sync work support
- [ ] Implement sync notifications (optional)
- [ ] Write worker tests

---

## Phase 5: Public API

### API Module (`api/`)

- [x] Design and implement `GoogleSyncClient` class
- [x] Create `SyncClientConfigBuilder` builder
- [x] Define `SyncResult` sealed class hierarchy
- [x] Implement progress observation via StateFlow
- [x] Create `SyncOptions` for operation customization
- [ ] Add backup/restore API
  - [ ] Create ZIP backup
  - [ ] Restore from backup
  - [ ] List available backups
- [ ] Write API documentation (KDoc)
- [ ] Create integration tests

---

## Phase 6: Persistence (Optional)

### Database Module (`db/`)

- [ ] Create `SyncDatabase` with Room
- [ ] Define `SyncStateEntity` for tracking
- [ ] Implement `SyncStateDao` with queries
- [ ] Add migration support
- [ ] Write database tests

---

## Phase 7: Sample App

- [x] Create sample app module
- [x] Implement sign-in screen
- [x] Create sync status dashboard
- [x] Add file browser with file management
- [x] Implement manual sync trigger
- [x] Add settings for sync configuration
- [x] Show sync history/logs
- [ ] Test on multiple devices

---

## Phase 8: Documentation & Polish

### Documentation

- [x] Write comprehensive README.md
- [x] Create INTEGRATION.md guide
- [x] Document CONFIGURATION.md options
- [x] Write TROUBLESHOOTING.md
- [x] Add inline code examples
- [ ] Create API reference (KDoc)

### Quality Assurance

- [ ] Achieve 80%+ test coverage
- [ ] Pass all detekt checks
- [ ] Review and optimize performance
- [ ] Memory leak testing
- [ ] Large file handling tests
- [ ] Stress testing with many files

### Release Preparation

- [ ] Version 1.0.0 feature freeze
- [ ] Final documentation review
- [x] Create CHANGELOG.md
- [ ] Prepare Maven Central publishing
- [ ] Create GitHub release

---

## Future Enhancements (Post 1.0)

### Features

- [ ] Support for Google Drive Shared Drives
- [ ] Selective folder sync (choose specific folders)
- [ ] Real-time sync with Drive push notifications
- [ ] Encryption at rest (encrypt files before upload)
- [ ] Compression before upload
- [ ] Bandwidth throttling
- [ ] Sync pause/resume
- [ ] Sync queue with priority

### Platform Support

- [ ] Kotlin Multiplatform (KMP) support
- [ ] Desktop (JVM) support
- [ ] iOS via KMP

### Integrations

- [ ] Support for other cloud providers (OneDrive, Dropbox)
- [ ] SAF (Storage Access Framework) integration
- [ ] Content provider support

### Performance

- [ ] Chunked upload for large files
- [ ] Parallel upload/download
- [ ] Resume interrupted transfers
- [ ] Smart batching of operations

---

## Known Issues

*Track bugs and issues here*

---

## Notes

- Reference `vane-client-manager` for proven patterns
- Reference `vane-spark-notes` for sync implementation details
- Prioritize reliability over features
- Test on real devices with real Drive accounts
- Consider quota limits and rate limiting

---

*Last Updated: 2026-01-13*
