# TODO.md - Android Google Drive Sync Library

> Track outstanding tasks, features, and improvements

---

## Phase 1: Project Setup

- [ ] Create Gradle project structure with library and sample modules
- [ ] Configure build.gradle.kts with required dependencies
- [ ] Set up Hilt dependency injection
- [ ] Configure detekt for code quality
- [ ] Create .gitignore with appropriate rules
- [ ] Set up GitHub Actions CI pipeline
- [ ] Configure library publishing (Maven)

---

## Phase 2: Core Infrastructure

### Authentication Module (`auth/`)

- [ ] Implement `GoogleAuthManager` with Google Sign-In
- [ ] Handle OAuth2 token management and refresh
- [ ] Create `AuthState` sealed class for auth states
- [ ] Implement `CredentialManager` for secure token storage
- [ ] Add sign-out and account switching support
- [ ] Write unit tests for auth flows

### Drive Operations Module (`drive/`)

- [ ] Implement `DriveService` wrapper for Drive API v3
- [ ] Create `DriveFileOperations` for CRUD operations
  - [ ] Upload file (create/update)
  - [ ] Download file
  - [ ] Delete file
  - [ ] List files with pagination
  - [ ] Get file metadata
- [ ] Implement `DriveFolderManager` for folder hierarchy
  - [ ] Create folder
  - [ ] Find folder by name/path
  - [ ] Ensure folder hierarchy exists
- [ ] Create `DriveQueryBuilder` for constructing queries
- [ ] Define `DriveModels` data classes
- [ ] Write comprehensive tests

### Local File Module (`local/`)

- [ ] Implement `LocalFileManager` for local file I/O
- [ ] Create `FileHasher` with MD5 and SHA256 support
- [ ] Implement `FileFilter` for include/exclude patterns
  - [ ] Glob pattern matching
  - [ ] Extension filtering
  - [ ] Size filtering
  - [ ] Hidden file handling
- [ ] Write tests for all local operations

---

## Phase 3: Sync Engine

### Sync Core (`sync/`)

- [ ] Implement `SyncManager` as main orchestrator
- [ ] Create `SyncEngine` with core sync logic
  - [ ] Upload flow with checksum comparison
  - [ ] Download flow with checksum comparison
  - [ ] Full bidirectional sync
  - [ ] Delta sync (changes only)
- [ ] Implement `SyncPolicy` strategies
  - [ ] Upload-only mode
  - [ ] Download-only mode
  - [ ] Bidirectional mode
  - [ ] Mirror mode (exact replica)
- [ ] Create `ConflictResolver` with policies
  - [ ] LOCAL_WINS
  - [ ] REMOTE_WINS
  - [ ] NEWER_WINS
  - [ ] KEEP_BOTH
  - [ ] ASK_USER (callback)
  - [ ] SKIP
- [ ] Implement `SyncState` tracking
- [ ] Add progress reporting via Flow
- [ ] Write extensive tests for sync scenarios

### Cache Layer (`cache/`)

- [ ] Implement `SyncCacheManager` for metadata caching
- [ ] Create `ChecksumCache` for hash caching
- [ ] Define `CachePolicy` for invalidation rules
- [ ] Add cache persistence (Room or preferences)
- [ ] Write cache tests

---

## Phase 4: Resilience & Background Sync

### Resilience Module (`resilience/`)

- [ ] Implement `RetryPolicy` with exponential backoff
- [ ] Create `NetworkMonitor` for connectivity checking
- [ ] Implement `ErrorRecovery` strategies
- [ ] Add operation queueing for offline support
- [ ] Handle rate limiting gracefully
- [ ] Write resilience tests

### Worker Module (`worker/`)

- [ ] Implement `SyncWorker` for WorkManager
- [ ] Create `SyncScheduler` for periodic scheduling
- [ ] Define `WorkerConstraints` configuration
- [ ] Add one-time sync work support
- [ ] Implement sync notifications (optional)
- [ ] Write worker tests

---

## Phase 5: Public API

### API Module (`api/`)

- [ ] Design and implement `GoogleSyncClient` interface
- [ ] Create `SyncConfiguration` builder
- [ ] Define `SyncResult` sealed class hierarchy
- [ ] Implement `SyncCallback` for progress observation
- [ ] Create `SyncOptions` for operation customization
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

- [ ] Create sample app module
- [ ] Implement sign-in screen
- [ ] Create sync status dashboard
- [ ] Add file browser with sync indicators
- [ ] Implement manual sync trigger
- [ ] Add settings for sync configuration
- [ ] Show sync history/logs
- [ ] Test on multiple devices

---

## Phase 8: Documentation & Polish

### Documentation

- [ ] Write comprehensive README.md
- [ ] Create INTEGRATION.md guide
- [ ] Document CONFIGURATION.md options
- [ ] Write TROUBLESHOOTING.md
- [ ] Add inline code examples
- [ ] Create API reference

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
- [ ] Create CHANGELOG.md
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
