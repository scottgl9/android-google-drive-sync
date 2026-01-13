package com.vanespark.googledrivesync.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class SyncModelsTest {

    @Nested
    inner class SyncItemTests {

        @Test
        fun `SyncItem creates correctly with all parameters`() {
            val localFile = File("/local/path/file.txt")
            val item = SyncItem(
                relativePath = "docs/file.txt",
                localFile = localFile,
                remoteId = "remote123",
                action = SyncAction.UPLOAD
            )

            assertThat(item.relativePath).isEqualTo("docs/file.txt")
            assertThat(item.localFile).isEqualTo(localFile)
            assertThat(item.remoteId).isEqualTo("remote123")
            assertThat(item.action).isEqualTo(SyncAction.UPLOAD)
        }

        @Test
        fun `SyncItem allows null optional fields`() {
            val item = SyncItem(
                relativePath = "file.txt",
                localFile = null,
                remoteId = null,
                action = SyncAction.NONE
            )

            assertThat(item.localFile).isNull()
            assertThat(item.remoteId).isNull()
        }
    }

    @Nested
    inner class SyncActionTests {

        @Test
        fun `all SyncAction values are defined`() {
            val actions = SyncAction.entries

            assertThat(actions).containsExactly(
                SyncAction.UPLOAD,
                SyncAction.DOWNLOAD,
                SyncAction.DELETE_LOCAL,
                SyncAction.DELETE_REMOTE,
                SyncAction.CONFLICT,
                SyncAction.SKIP,
                SyncAction.NONE
            )
        }
    }

    @Nested
    inner class SyncModeTests {

        @Test
        fun `all SyncMode values are defined`() {
            val modes = SyncMode.entries

            assertThat(modes).containsExactly(
                SyncMode.UPLOAD_ONLY,
                SyncMode.DOWNLOAD_ONLY,
                SyncMode.BIDIRECTIONAL,
                SyncMode.MIRROR_TO_CLOUD,
                SyncMode.MIRROR_FROM_CLOUD
            )
        }
    }

    @Nested
    inner class ConflictPolicyTests {

        @Test
        fun `all ConflictPolicy values are defined`() {
            val policies = ConflictPolicy.entries

            assertThat(policies).containsExactly(
                ConflictPolicy.LOCAL_WINS,
                ConflictPolicy.REMOTE_WINS,
                ConflictPolicy.NEWER_WINS,
                ConflictPolicy.KEEP_BOTH,
                ConflictPolicy.SKIP,
                ConflictPolicy.ASK_USER
            )
        }
    }

    @Nested
    inner class SyncResultTests {

        @Test
        fun `Success contains all sync statistics`() {
            val result = SyncResult.Success(
                filesUploaded = 5,
                filesDownloaded = 3,
                filesSkipped = 2,
                bytesTransferred = 10000,
                duration = 5.seconds
            )

            assertThat(result.filesUploaded).isEqualTo(5)
            assertThat(result.filesDownloaded).isEqualTo(3)
            assertThat(result.filesSkipped).isEqualTo(2)
            assertThat(result.bytesTransferred).isEqualTo(10000)
            assertThat(result.duration).isEqualTo(5.seconds)
        }

        @Test
        fun `PartialSuccess contains failure information`() {
            val errors = listOf(
                SyncError("file1.txt", "Upload failed"),
                SyncError("file2.txt", "Download failed")
            )

            val result = SyncResult.PartialSuccess(
                filesSucceeded = 8,
                filesFailed = 2,
                errors = errors,
                duration = 10.seconds
            )

            assertThat(result.filesSucceeded).isEqualTo(8)
            assertThat(result.filesFailed).isEqualTo(2)
            assertThat(result.errors).hasSize(2)
            assertThat(result.errors[0].path).isEqualTo("file1.txt")
        }

        @Test
        fun `Error contains error message and optional cause`() {
            val cause = IOException("Network error")
            val result = SyncResult.Error(
                message = "Sync failed",
                cause = cause
            )

            assertThat(result.message).isEqualTo("Sync failed")
            assertThat(result.cause).isEqualTo(cause)
        }

        @Test
        fun `Singleton results are equal to themselves`() {
            assertThat(SyncResult.NotSignedIn).isEqualTo(SyncResult.NotSignedIn)
            assertThat(SyncResult.NetworkUnavailable).isEqualTo(SyncResult.NetworkUnavailable)
            assertThat(SyncResult.Cancelled).isEqualTo(SyncResult.Cancelled)
        }
    }

    @Nested
    inner class SyncOptionsTests {

        @Test
        fun `DEFAULT options have expected values`() {
            val options = SyncOptions.DEFAULT

            assertThat(options.mode).isEqualTo(SyncMode.BIDIRECTIONAL)
            assertThat(options.conflictPolicy).isEqualTo(ConflictPolicy.NEWER_WINS)
            assertThat(options.dryRun).isFalse()
            assertThat(options.forceUpload).isFalse()
            assertThat(options.forceDownload).isFalse()
        }

        @Test
        fun `UPLOAD_ONLY preset has correct mode`() {
            val options = SyncOptions.UPLOAD_ONLY

            assertThat(options.mode).isEqualTo(SyncMode.UPLOAD_ONLY)
        }

        @Test
        fun `DOWNLOAD_ONLY preset has correct mode`() {
            val options = SyncOptions.DOWNLOAD_ONLY

            assertThat(options.mode).isEqualTo(SyncMode.DOWNLOAD_ONLY)
        }

        @Test
        fun `MIRROR_TO_CLOUD preset has correct mode and policy`() {
            val options = SyncOptions.MIRROR_TO_CLOUD

            assertThat(options.mode).isEqualTo(SyncMode.MIRROR_TO_CLOUD)
            assertThat(options.conflictPolicy).isEqualTo(ConflictPolicy.LOCAL_WINS)
        }

        @Test
        fun `MIRROR_FROM_CLOUD preset has correct mode and policy`() {
            val options = SyncOptions.MIRROR_FROM_CLOUD

            assertThat(options.mode).isEqualTo(SyncMode.MIRROR_FROM_CLOUD)
            assertThat(options.conflictPolicy).isEqualTo(ConflictPolicy.REMOTE_WINS)
        }

        @Test
        fun `custom options can be created`() {
            val options = SyncOptions(
                mode = SyncMode.BIDIRECTIONAL,
                conflictPolicy = ConflictPolicy.ASK_USER,
                dryRun = true,
                forceUpload = true,
                forceDownload = false
            )

            assertThat(options.mode).isEqualTo(SyncMode.BIDIRECTIONAL)
            assertThat(options.conflictPolicy).isEqualTo(ConflictPolicy.ASK_USER)
            assertThat(options.dryRun).isTrue()
            assertThat(options.forceUpload).isTrue()
            assertThat(options.forceDownload).isFalse()
        }
    }

    @Nested
    inner class FileManifestTests {

        @Test
        fun `FileManifest contains entries correctly`() {
            val now = Instant.now()
            val entry1 = FileManifestEntry(
                relativePath = "file1.txt",
                name = "file1.txt",
                size = 100,
                modifiedTime = now,
                checksum = "abc123"
            )
            val entry2 = FileManifestEntry(
                relativePath = "file2.txt",
                name = "file2.txt",
                size = 200,
                modifiedTime = now,
                checksum = "def456"
            )

            val manifest = FileManifest(
                files = mapOf(
                    "file1.txt" to entry1,
                    "file2.txt" to entry2
                ),
                createdAt = now
            )

            assertThat(manifest.files).hasSize(2)
            assertThat(manifest.files["file1.txt"]).isEqualTo(entry1)
            assertThat(manifest.files["file2.txt"]).isEqualTo(entry2)
            assertThat(manifest.createdAt).isEqualTo(now)
        }

        @Test
        fun `FileManifestEntry stores all file metadata`() {
            val modTime = Instant.now()
            val entry = FileManifestEntry(
                relativePath = "docs/report.pdf",
                name = "report.pdf",
                size = 50000,
                modifiedTime = modTime,
                checksum = "abc123def456"
            )

            assertThat(entry.relativePath).isEqualTo("docs/report.pdf")
            assertThat(entry.name).isEqualTo("report.pdf")
            assertThat(entry.size).isEqualTo(50000)
            assertThat(entry.modifiedTime).isEqualTo(modTime)
            assertThat(entry.checksum).isEqualTo("abc123def456")
        }

        @Test
        fun `FileManifestEntry allows null checksum`() {
            val entry = FileManifestEntry(
                relativePath = "file.txt",
                name = "file.txt",
                size = 100,
                modifiedTime = Instant.now(),
                checksum = null
            )

            assertThat(entry.checksum).isNull()
        }
    }

    @Nested
    inner class ConflictInfoTests {

        @Test
        fun `ConflictInfo contains all conflict details`() {
            val localTime = Instant.now()
            val remoteTime = Instant.now().minusSeconds(3600)

            val info = ConflictInfo(
                relativePath = "docs/file.txt",
                localChecksum = "abc123",
                remoteChecksum = "def456",
                localModifiedTime = localTime,
                remoteModifiedTime = remoteTime,
                localSize = 1000,
                remoteSize = 1500
            )

            assertThat(info.relativePath).isEqualTo("docs/file.txt")
            assertThat(info.localChecksum).isEqualTo("abc123")
            assertThat(info.remoteChecksum).isEqualTo("def456")
            assertThat(info.localModifiedTime).isEqualTo(localTime)
            assertThat(info.remoteModifiedTime).isEqualTo(remoteTime)
            assertThat(info.localSize).isEqualTo(1000)
            assertThat(info.remoteSize).isEqualTo(1500)
        }
    }

    @Nested
    inner class ConflictResolutionTests {

        @Test
        fun `all ConflictResolution types are available`() {
            val useLocal: ConflictResolution = ConflictResolution.UseLocal
            val useRemote: ConflictResolution = ConflictResolution.UseRemote
            val skip: ConflictResolution = ConflictResolution.Skip
            val keepBoth: ConflictResolution = ConflictResolution.KeepBoth("_copy")

            assertThat(useLocal).isEqualTo(ConflictResolution.UseLocal)
            assertThat(useRemote).isEqualTo(ConflictResolution.UseRemote)
            assertThat(skip).isEqualTo(ConflictResolution.Skip)
            assertThat(keepBoth).isInstanceOf(ConflictResolution.KeepBoth::class.java)
        }

        @Test
        fun `KeepBoth contains suffix`() {
            val keepBoth = ConflictResolution.KeepBoth("_conflict_123")

            assertThat(keepBoth.suffix).isEqualTo("_conflict_123")
        }
    }

    @Nested
    inner class SyncErrorTests {

        @Test
        fun `SyncError contains path and message`() {
            val error = SyncError(
                path = "documents/important.pdf",
                message = "Upload failed: connection reset"
            )

            assertThat(error.path).isEqualTo("documents/important.pdf")
            assertThat(error.message).isEqualTo("Upload failed: connection reset")
        }
    }
}

// Helper class for testing - IOException is used in tests
class IOException(message: String) : java.io.IOException(message)
