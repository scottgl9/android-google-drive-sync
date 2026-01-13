package com.vanespark.googledrivesync.drive

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class DriveModelsTest {

    @Nested
    inner class DriveFileTests {

        @Test
        fun `DriveFile contains all required properties`() {
            val now = Instant.now()
            val file = DriveFile(
                id = "abc123",
                name = "document.pdf",
                size = 50000,
                modifiedTime = now,
                md5Checksum = "def456",
                mimeType = "application/pdf",
                parents = listOf("folder123")
            )

            assertThat(file.id).isEqualTo("abc123")
            assertThat(file.name).isEqualTo("document.pdf")
            assertThat(file.size).isEqualTo(50000)
            assertThat(file.md5Checksum).isEqualTo("def456")
            assertThat(file.mimeType).isEqualTo("application/pdf")
            assertThat(file.parents).containsExactly("folder123")
        }

        @Test
        fun `DriveFile allows null optional fields`() {
            val file = DriveFile(
                id = "abc123",
                name = "file.txt",
                size = 100,
                modifiedTime = Instant.now(),
                md5Checksum = null,
                mimeType = "text/plain",
                parents = null
            )

            assertThat(file.md5Checksum).isNull()
            assertThat(file.parents).isNull()
        }

        @Test
        fun `DriveFile isFolder returns true for folder mime type`() {
            val folder = DriveFile(
                id = "folder123",
                name = "Documents",
                size = 0,
                modifiedTime = Instant.now(),
                md5Checksum = null,
                mimeType = "application/vnd.google-apps.folder",
                parents = null
            )

            assertThat(folder.isFolder).isTrue()
        }

        @Test
        fun `DriveFile isFolder returns false for regular files`() {
            val file = DriveFile(
                id = "file123",
                name = "document.pdf",
                size = 1000,
                modifiedTime = Instant.now(),
                md5Checksum = "hash123",
                mimeType = "application/pdf",
                parents = null
            )

            assertThat(file.isFolder).isFalse()
        }
    }

    @Nested
    inner class DriveFolderTests {

        @Test
        fun `DriveFolder contains folder information`() {
            val folder = DriveFolder(
                id = "folder123",
                name = "Documents",
                parents = listOf("root")
            )

            assertThat(folder.id).isEqualTo("folder123")
            assertThat(folder.name).isEqualTo("Documents")
            assertThat(folder.parents).containsExactly("root")
        }

        @Test
        fun `DriveFolder allows null parents`() {
            val folder = DriveFolder(
                id = "folder123",
                name = "Root",
                parents = null
            )

            assertThat(folder.parents).isNull()
        }
    }

    @Nested
    inner class DriveOperationResultTests {

        @Test
        fun `Success result contains data`() {
            val result = DriveOperationResult.Success("test data")

            assertThat(result.data).isEqualTo("test data")
            assertThat(result.isSuccess).isTrue()
            assertThat(result.getOrNull()).isEqualTo("test data")
        }

        @Test
        fun `Error result contains message and cause`() {
            val cause = RuntimeException("Network error")
            val result = DriveOperationResult.Error("Upload failed", cause)

            assertThat(result.message).isEqualTo("Upload failed")
            assertThat(result.cause).isEqualTo(cause)
            assertThat(result.isSuccess).isFalse()
        }

        @Test
        fun `NotFound result is singleton`() {
            val result1 = DriveOperationResult.NotFound
            val result2 = DriveOperationResult.NotFound

            assertThat(result1).isEqualTo(result2)
            assertThat(result1.isSuccess).isFalse()
        }

        @Test
        fun `getOrNull returns null for error results`() {
            val result: DriveOperationResult<String> = DriveOperationResult.Error("failed")

            assertThat(result.getOrNull() as Any?).isNull()
        }

        @Test
        fun `NotSignedIn result is singleton`() {
            assertThat(DriveOperationResult.NotSignedIn)
                .isEqualTo(DriveOperationResult.NotSignedIn)
        }

        @Test
        fun `RateLimited result is singleton`() {
            assertThat(DriveOperationResult.RateLimited)
                .isEqualTo(DriveOperationResult.RateLimited)
        }
    }

    @Nested
    inner class DriveFileWithPathTests {

        @Test
        fun `DriveFileWithPath includes relative path`() {
            val file = DriveFile(
                id = "abc123",
                name = "report.pdf",
                size = 5000,
                modifiedTime = Instant.now(),
                md5Checksum = "hash123",
                mimeType = "application/pdf",
                parents = listOf("folder123")
            )

            val fileWithPath = DriveFileWithPath(
                file = file,
                relativePath = "documents/reports/report.pdf"
            )

            assertThat(fileWithPath.file).isEqualTo(file)
            assertThat(fileWithPath.relativePath).isEqualTo("documents/reports/report.pdf")
        }
    }

    @Nested
    inner class DriveFileCacheTests {

        @Test
        fun `DriveFileCache provides file lookup by path`() {
            val file1 = createTestFile("file1.txt", "id1", "hash1")
            val file2 = createTestFile("file2.txt", "id2", "hash2")

            val cache = DriveFileCache(
                filesByPath = mapOf(
                    "docs/file1.txt" to file1,
                    "docs/file2.txt" to file2
                ),
                checksumToPath = mapOf(
                    "hash1" to "docs/file1.txt",
                    "hash2" to "docs/file2.txt"
                )
            )

            assertThat(cache.getFile("docs/file1.txt")).isEqualTo(file1)
            assertThat(cache.getFile("docs/file2.txt")).isEqualTo(file2)
            assertThat(cache.getFile("nonexistent")).isNull()
        }

        @Test
        fun `DriveFileCache provides checksum lookup`() {
            val file1 = createTestFile("file1.txt", "id1", "hash1")

            val cache = DriveFileCache(
                filesByPath = mapOf("file1.txt" to file1),
                checksumToPath = mapOf("hash1" to "file1.txt")
            )

            assertThat(cache.getPathByChecksum("hash1")).isEqualTo("file1.txt")
            assertThat(cache.getPathByChecksum("nonexistent")).isNull()
        }

        @Test
        fun `DriveFileCache hasFile works correctly`() {
            val file = createTestFile("file.txt", "id1", "hash1")

            val cache = DriveFileCache(
                filesByPath = mapOf("file.txt" to file),
                checksumToPath = mapOf("hash1" to "file.txt")
            )

            assertThat(cache.hasFile("file.txt")).isTrue()
            assertThat(cache.hasFile("other.txt")).isFalse()
        }

        @Test
        fun `DriveFileCache hasChecksum works correctly`() {
            val file = createTestFile("file.txt", "id1", "hash1")

            val cache = DriveFileCache(
                filesByPath = mapOf("file.txt" to file),
                checksumToPath = mapOf("hash1" to "file.txt")
            )

            assertThat(cache.hasChecksum("hash1")).isTrue()
            assertThat(cache.hasChecksum("otherhash")).isFalse()
        }

        @Test
        fun `DriveFileCache size returns correct count`() {
            val file1 = createTestFile("file1.txt", "id1", "hash1")
            val file2 = createTestFile("file2.txt", "id2", "hash2")

            val cache = DriveFileCache(
                filesByPath = mapOf(
                    "file1.txt" to file1,
                    "file2.txt" to file2
                ),
                checksumToPath = emptyMap()
            )

            assertThat(cache.size).isEqualTo(2)
        }

        @Test
        fun `empty DriveFileCache has size zero`() {
            val cache = DriveFileCache(
                filesByPath = emptyMap(),
                checksumToPath = emptyMap()
            )

            assertThat(cache.size).isEqualTo(0)
        }

        private fun createTestFile(name: String, id: String, checksum: String): DriveFile {
            return DriveFile(
                id = id,
                name = name,
                size = 100,
                modifiedTime = Instant.now(),
                md5Checksum = checksum,
                mimeType = "text/plain",
                parents = null
            )
        }
    }

    @Nested
    inner class DuplicateRemovalResultTests {

        @Test
        fun `DuplicateRemovalResult contains removal statistics`() {
            val result = DuplicateRemovalResult(
                duplicatesRemoved = 5,
                bytesFreed = 50000
            )

            assertThat(result.duplicatesRemoved).isEqualTo(5)
            assertThat(result.bytesFreed).isEqualTo(50000)
        }
    }

    @Nested
    inner class IntegrityVerificationResultTests {

        @Test
        fun `IntegrityVerificationResult for successful verification`() {
            val result = IntegrityVerificationResult(
                verified = true,
                localChecksum = "abc123",
                remoteChecksum = "abc123"
            )

            assertThat(result.verified).isTrue()
            assertThat(result.reason).isNull()
            assertThat(result.localChecksum).isEqualTo("abc123")
            assertThat(result.remoteChecksum).isEqualTo("abc123")
        }

        @Test
        fun `IntegrityVerificationResult for failed verification`() {
            val result = IntegrityVerificationResult(
                verified = false,
                reason = "Checksum mismatch",
                localChecksum = "abc123",
                remoteChecksum = "def456"
            )

            assertThat(result.verified).isFalse()
            assertThat(result.reason).isEqualTo("Checksum mismatch")
            assertThat(result.localChecksum).isNotEqualTo(result.remoteChecksum)
        }
    }

    @Nested
    inner class UploadResultTests {

        @Test
        fun `UploadResult contains upload information`() {
            val file = createTestFile("test.txt", "id1", "hash1")
            val result = UploadResult(
                file = file,
                wasUpdated = false,
                durationMs = 1500
            )

            assertThat(result.file).isEqualTo(file)
            assertThat(result.wasUpdated).isFalse()
            assertThat(result.durationMs).isEqualTo(1500)
        }

        private fun createTestFile(name: String, id: String, checksum: String): DriveFile {
            return DriveFile(
                id = id,
                name = name,
                size = 100,
                modifiedTime = Instant.now(),
                md5Checksum = checksum,
                mimeType = "text/plain",
                parents = null
            )
        }
    }

    @Nested
    inner class DownloadResultTests {

        @Test
        fun `DownloadResult contains download information`() {
            val result = DownloadResult(
                localPath = "/path/to/file.txt",
                size = 5000,
                durationMs = 2000
            )

            assertThat(result.localPath).isEqualTo("/path/to/file.txt")
            assertThat(result.size).isEqualTo(5000)
            assertThat(result.durationMs).isEqualTo(2000)
        }
    }

    @Nested
    inner class FolderCacheTests {

        @Test
        fun `FolderCache provides folder lookup`() {
            val cache = FolderCache(
                rootFolderId = "root123",
                folderIds = mapOf(
                    "docs" to "folder1",
                    "docs/reports" to "folder2"
                )
            )

            assertThat(cache.rootFolderId).isEqualTo("root123")
            assertThat(cache.getFolderId("docs")).isEqualTo("folder1")
            assertThat(cache.getFolderId("docs/reports")).isEqualTo("folder2")
            assertThat(cache.getFolderId("nonexistent")).isNull()
        }

        @Test
        fun `FolderCache hasFolder works correctly`() {
            val cache = FolderCache(
                rootFolderId = "root123",
                folderIds = mapOf("docs" to "folder1")
            )

            assertThat(cache.hasFolder("docs")).isTrue()
            assertThat(cache.hasFolder("other")).isFalse()
        }

        @Test
        fun `FolderCache withFolder adds new folder`() {
            val cache = FolderCache(rootFolderId = "root")
            val newCache = cache.withFolder("docs", "folder1")

            assertThat(cache.hasFolder("docs")).isFalse()
            assertThat(newCache.hasFolder("docs")).isTrue()
            assertThat(newCache.getFolderId("docs")).isEqualTo("folder1")
        }
    }
}
