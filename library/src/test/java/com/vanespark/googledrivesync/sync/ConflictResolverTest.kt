package com.vanespark.googledrivesync.sync

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class ConflictResolverTest {

    private lateinit var conflictResolver: ConflictResolver

    @BeforeEach
    fun setup() {
        // Mock Android Log
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0

        conflictResolver = ConflictResolver()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun createConflict(
        localTime: Instant? = Instant.now(),
        remoteTime: Instant? = Instant.now().minusSeconds(3600),
        localSize: Long = 1000,
        remoteSize: Long = 1000
    ) = ConflictInfo(
        relativePath = "test/file.txt",
        localChecksum = "abc123",
        remoteChecksum = "def456",
        localModifiedTime = localTime,
        remoteModifiedTime = remoteTime,
        localSize = localSize,
        remoteSize = remoteSize
    )

    @Nested
    inner class ResolvePolicyTests {

        @Test
        fun `LOCAL_WINS always returns UseLocal`() = runTest {
            val conflict = createConflict()

            val result = conflictResolver.resolve(conflict, ConflictPolicy.LOCAL_WINS)

            assertThat(result).isEqualTo(ConflictResolution.UseLocal)
        }

        @Test
        fun `REMOTE_WINS always returns UseRemote`() = runTest {
            val conflict = createConflict()

            val result = conflictResolver.resolve(conflict, ConflictPolicy.REMOTE_WINS)

            assertThat(result).isEqualTo(ConflictResolution.UseRemote)
        }

        @Test
        fun `SKIP always returns Skip`() = runTest {
            val conflict = createConflict()

            val result = conflictResolver.resolve(conflict, ConflictPolicy.SKIP)

            assertThat(result).isEqualTo(ConflictResolution.Skip)
        }

        @Test
        fun `KEEP_BOTH returns KeepBoth with suffix`() = runTest {
            val conflict = createConflict()

            val result = conflictResolver.resolve(conflict, ConflictPolicy.KEEP_BOTH)

            assertThat(result).isInstanceOf(ConflictResolution.KeepBoth::class.java)
            val keepBoth = result as ConflictResolution.KeepBoth
            assertThat(keepBoth.suffix).startsWith("_conflict_")
        }
    }

    @Nested
    inner class NewerWinsPolicyTests {

        @Test
        fun `NEWER_WINS returns UseLocal when local is newer`() = runTest {
            val now = Instant.now()
            val conflict = createConflict(
                localTime = now,
                remoteTime = now.minusSeconds(3600)
            )

            val result = conflictResolver.resolve(conflict, ConflictPolicy.NEWER_WINS)

            assertThat(result).isEqualTo(ConflictResolution.UseLocal)
        }

        @Test
        fun `NEWER_WINS returns UseRemote when remote is newer`() = runTest {
            val now = Instant.now()
            val conflict = createConflict(
                localTime = now.minusSeconds(3600),
                remoteTime = now
            )

            val result = conflictResolver.resolve(conflict, ConflictPolicy.NEWER_WINS)

            assertThat(result).isEqualTo(ConflictResolution.UseRemote)
        }

        @Test
        fun `NEWER_WINS uses local when timestamps are equal and local size is larger`() = runTest {
            val now = Instant.now()
            val conflict = createConflict(
                localTime = now,
                remoteTime = now,
                localSize = 2000,
                remoteSize = 1000
            )

            val result = conflictResolver.resolve(conflict, ConflictPolicy.NEWER_WINS)

            assertThat(result).isEqualTo(ConflictResolution.UseLocal)
        }

        @Test
        fun `NEWER_WINS uses remote when timestamps are equal and remote size is larger`() = runTest {
            val now = Instant.now()
            val conflict = createConflict(
                localTime = now,
                remoteTime = now,
                localSize = 1000,
                remoteSize = 2000
            )

            val result = conflictResolver.resolve(conflict, ConflictPolicy.NEWER_WINS)

            assertThat(result).isEqualTo(ConflictResolution.UseRemote)
        }

        @Test
        fun `NEWER_WINS returns UseRemote when local timestamp is null`() = runTest {
            val conflict = createConflict(
                localTime = null,
                remoteTime = Instant.now()
            )

            val result = conflictResolver.resolve(conflict, ConflictPolicy.NEWER_WINS)

            assertThat(result).isEqualTo(ConflictResolution.UseRemote)
        }

        @Test
        fun `NEWER_WINS returns UseLocal when remote timestamp is null`() = runTest {
            val conflict = createConflict(
                localTime = Instant.now(),
                remoteTime = null
            )

            val result = conflictResolver.resolve(conflict, ConflictPolicy.NEWER_WINS)

            assertThat(result).isEqualTo(ConflictResolution.UseLocal)
        }

        @Test
        fun `NEWER_WINS returns UseRemote when both timestamps are null`() = runTest {
            val conflict = createConflict(
                localTime = null,
                remoteTime = null
            )

            val result = conflictResolver.resolve(conflict, ConflictPolicy.NEWER_WINS)

            assertThat(result).isEqualTo(ConflictResolution.UseRemote)
        }
    }

    @Nested
    inner class AskUserPolicyTests {

        @Test
        fun `ASK_USER falls back to NEWER_WINS when no callback set`() = runTest {
            val now = Instant.now()
            val conflict = createConflict(
                localTime = now,
                remoteTime = now.minusSeconds(3600)
            )

            val result = conflictResolver.resolve(conflict, ConflictPolicy.ASK_USER)

            assertThat(result).isEqualTo(ConflictResolution.UseLocal)
        }

        @Test
        fun `ASK_USER uses callback when set`() = runTest {
            conflictResolver.setUserCallback { ConflictResolution.Skip }
            val conflict = createConflict()

            val result = conflictResolver.resolve(conflict, ConflictPolicy.ASK_USER)

            assertThat(result).isEqualTo(ConflictResolution.Skip)
        }

        @Test
        fun `callback receives correct conflict info`() = runTest {
            var receivedConflict: ConflictInfo? = null
            conflictResolver.setUserCallback { info ->
                receivedConflict = info
                ConflictResolution.Skip
            }

            val conflict = createConflict()
            conflictResolver.resolve(conflict, ConflictPolicy.ASK_USER)

            assertThat(receivedConflict).isEqualTo(conflict)
        }
    }

    @Nested
    inner class IsConflictTests {

        @Test
        fun `isConflict returns false when checksums match`() {
            val result = conflictResolver.isConflict(
                localChecksum = "abc123",
                remoteChecksum = "abc123",
                localModified = Instant.now(),
                remoteModified = Instant.now()
            )

            assertThat(result).isFalse()
        }

        @Test
        fun `isConflict returns true when checksums differ`() {
            val result = conflictResolver.isConflict(
                localChecksum = "abc123",
                remoteChecksum = "def456",
                localModified = Instant.now(),
                remoteModified = Instant.now()
            )

            assertThat(result).isTrue()
        }

        @Test
        fun `isConflict is case insensitive for checksums`() {
            val result = conflictResolver.isConflict(
                localChecksum = "ABC123",
                remoteChecksum = "abc123",
                localModified = null,
                remoteModified = null
            )

            assertThat(result).isFalse()
        }

        @Test
        fun `isConflict returns true when local checksum is null`() {
            val result = conflictResolver.isConflict(
                localChecksum = null,
                remoteChecksum = "abc123",
                localModified = Instant.now(),
                remoteModified = Instant.now()
            )

            assertThat(result).isTrue()
        }

        @Test
        fun `isConflict returns true when remote checksum is null`() {
            val result = conflictResolver.isConflict(
                localChecksum = "abc123",
                remoteChecksum = null,
                localModified = Instant.now(),
                remoteModified = Instant.now()
            )

            assertThat(result).isTrue()
        }
    }

    @Nested
    inner class ConflictSuffixTests {

        @Test
        fun `generateConflictSuffix creates timestamped suffix`() {
            val suffix = conflictResolver.generateConflictSuffix()

            assertThat(suffix).startsWith("_conflict_")
            assertThat(suffix.length).isGreaterThan(10)
        }

        @Test
        fun `applyConflictSuffix inserts suffix before extension`() {
            val result = conflictResolver.applyConflictSuffix("document.pdf", "_conflict_123")

            assertThat(result).isEqualTo("document_conflict_123.pdf")
        }

        @Test
        fun `applyConflictSuffix appends suffix to filename without extension`() {
            val result = conflictResolver.applyConflictSuffix("README", "_conflict_123")

            assertThat(result).isEqualTo("README_conflict_123")
        }

        @Test
        fun `applyConflictSuffix handles multiple dots in filename`() {
            val result = conflictResolver.applyConflictSuffix("file.backup.tar.gz", "_conflict_123")

            assertThat(result).isEqualTo("file.backup.tar_conflict_123.gz")
        }
    }

    @Nested
    inner class CreateConflictInfoTests {

        @Test
        fun `createConflictInfo creates correct info object`() {
            val now = Instant.now()
            val later = now.plusSeconds(3600)

            val info = conflictResolver.createConflictInfo(
                relativePath = "docs/file.txt",
                localChecksum = "abc123",
                remoteChecksum = "def456",
                localModifiedTime = now,
                remoteModifiedTime = later,
                localSize = 1000,
                remoteSize = 2000
            )

            assertThat(info.relativePath).isEqualTo("docs/file.txt")
            assertThat(info.localChecksum).isEqualTo("abc123")
            assertThat(info.remoteChecksum).isEqualTo("def456")
            assertThat(info.localModifiedTime).isEqualTo(now)
            assertThat(info.remoteModifiedTime).isEqualTo(later)
            assertThat(info.localSize).isEqualTo(1000)
            assertThat(info.remoteSize).isEqualTo(2000)
        }
    }
}
