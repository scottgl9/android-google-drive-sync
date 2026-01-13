package com.vanespark.googledrivesync.local

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

class FileHasherTest {

    private lateinit var fileHasher: FileHasher

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        // Mock Android Log to avoid RuntimeException
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0

        fileHasher = FileHasher()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    inner class CalculateHashTests {

        @Test
        fun `calculateHash returns consistent MD5 hash for same content`() = runTest {
            val file1 = File(tempDir, "file1.txt").apply { writeText("Hello World") }
            val file2 = File(tempDir, "file2.txt").apply { writeText("Hello World") }

            val hash1 = fileHasher.calculateHash(file1, ChecksumAlgorithm.MD5)
            val hash2 = fileHasher.calculateHash(file2, ChecksumAlgorithm.MD5)

            assertThat(hash1).isEqualTo(hash2)
            assertThat(hash1).isNotEmpty()
        }

        @Test
        fun `calculateHash returns different hash for different content`() = runTest {
            val file1 = File(tempDir, "file1.txt").apply { writeText("Hello") }
            val file2 = File(tempDir, "file2.txt").apply { writeText("World") }

            val hash1 = fileHasher.calculateHash(file1, ChecksumAlgorithm.MD5)
            val hash2 = fileHasher.calculateHash(file2, ChecksumAlgorithm.MD5)

            assertThat(hash1).isNotEqualTo(hash2)
        }

        @Test
        fun `calculateHash with SHA256 returns longer hash`() = runTest {
            val file = File(tempDir, "test.txt").apply { writeText("Test content") }

            val md5Hash = fileHasher.calculateHash(file, ChecksumAlgorithm.MD5)
            val sha256Hash = fileHasher.calculateHash(file, ChecksumAlgorithm.SHA256)

            assertThat(md5Hash).hasLength(32) // MD5 = 128 bits = 32 hex chars
            assertThat(sha256Hash).hasLength(64) // SHA-256 = 256 bits = 64 hex chars
        }

        @Test
        fun `calculateHash returns lowercase hex string`() = runTest {
            val file = File(tempDir, "test.txt").apply { writeText("Test") }

            val hash = fileHasher.calculateHash(file, ChecksumAlgorithm.MD5)

            assertThat(hash).matches("[a-f0-9]+")
        }
    }

    @Nested
    inner class CalculateHashFromStreamTests {

        @Test
        fun `calculateHash from stream returns correct hash`() = runTest {
            val content = "Hello World"
            val stream = ByteArrayInputStream(content.toByteArray())

            val streamHash = fileHasher.calculateHash(stream, ChecksumAlgorithm.MD5)

            // Create file with same content and compare
            val file = File(tempDir, "test.txt").apply { writeText(content) }
            val fileHash = fileHasher.calculateHash(file, ChecksumAlgorithm.MD5)

            assertThat(streamHash).isEqualTo(fileHash)
        }
    }

    @Nested
    inner class CalculateHashFromBytesTests {

        @Test
        fun `calculateHash from bytes returns correct hash`() {
            val content = "Hello World"
            val bytes = content.toByteArray()

            val hash = fileHasher.calculateHash(bytes, ChecksumAlgorithm.MD5)

            assertThat(hash).isNotEmpty()
            assertThat(hash).hasLength(32)
        }

        @Test
        fun `calculateHash from bytes matches file hash`() = runTest {
            val content = "Test content"
            val bytes = content.toByteArray()
            val file = File(tempDir, "test.txt").apply { writeText(content) }

            val bytesHash = fileHasher.calculateHash(bytes, ChecksumAlgorithm.MD5)
            val fileHash = fileHasher.calculateHash(file, ChecksumAlgorithm.MD5)

            assertThat(bytesHash).isEqualTo(fileHash)
        }
    }

    @Nested
    inner class VerifyChecksumTests {

        @Test
        fun `verifyChecksum returns true for matching checksum`() = runTest {
            val file = File(tempDir, "test.txt").apply { writeText("Test content") }
            val expectedHash = fileHasher.calculateHash(file, ChecksumAlgorithm.MD5)

            val result = fileHasher.verifyChecksum(file, expectedHash, ChecksumAlgorithm.MD5)

            assertThat(result).isTrue()
        }

        @Test
        fun `verifyChecksum returns false for non-matching checksum`() = runTest {
            val file = File(tempDir, "test.txt").apply { writeText("Test content") }

            val result = fileHasher.verifyChecksum(file, "wronghash123", ChecksumAlgorithm.MD5)

            assertThat(result).isFalse()
        }

        @Test
        fun `verifyChecksum is case insensitive`() = runTest {
            val file = File(tempDir, "test.txt").apply { writeText("Test") }
            val hash = fileHasher.calculateHash(file, ChecksumAlgorithm.MD5)

            val resultLower = fileHasher.verifyChecksum(file, hash.lowercase(), ChecksumAlgorithm.MD5)
            val resultUpper = fileHasher.verifyChecksum(file, hash.uppercase(), ChecksumAlgorithm.MD5)

            assertThat(resultLower).isTrue()
            assertThat(resultUpper).isTrue()
        }
    }

    @Nested
    inner class HashedFilenameTests {

        @Test
        fun `createHashedFilename adds hash to filename with extension`() {
            val original = "document.pdf"
            val hash = "abc123def456"

            val result = fileHasher.createHashedFilename(original, hash)

            assertThat(result).isEqualTo("document_abc123de.pdf")
        }

        @Test
        fun `createHashedFilename adds hash to filename without extension`() {
            val original = "README"
            val hash = "abc123def456"

            val result = fileHasher.createHashedFilename(original, hash)

            assertThat(result).isEqualTo("README_abc123de")
        }

        @Test
        fun `createHashedFilename truncates hash to 8 characters`() {
            val original = "file.txt"
            val longHash = "abcdefghijklmnopqrstuvwxyz123456"

            val result = fileHasher.createHashedFilename(original, longHash)

            assertThat(result).isEqualTo("file_abcdefgh.txt")
        }

        @Test
        fun `getOriginalFilename extracts original name`() {
            val hashedName = "document_abc123de.pdf"

            val result = fileHasher.getOriginalFilename(hashedName)

            assertThat(result).isEqualTo("document.pdf")
        }

        @Test
        fun `getOriginalFilename returns input if not hashed`() {
            val normalName = "document.pdf"

            val result = fileHasher.getOriginalFilename(normalName)

            assertThat(result).isEqualTo("document.pdf")
        }

        @Test
        fun `getOriginalFilename handles files without extension`() {
            val hashedName = "README_abc123de"

            val result = fileHasher.getOriginalFilename(hashedName)

            assertThat(result).isEqualTo("README")
        }

        @Test
        fun `extractHash returns hash from hashed filename`() {
            val hashedName = "document_abc123de.pdf"

            val result = fileHasher.extractHash(hashedName)

            assertThat(result).isEqualTo("abc123de")
        }

        @Test
        fun `extractHash returns null for non-hashed filename`() {
            val normalName = "document.pdf"

            val result = fileHasher.extractHash(normalName)

            assertThat(result).isNull()
        }

        @Test
        fun `extractHash returns null for filename with non-hex suffix`() {
            val fileName = "document_notahash.pdf"

            val result = fileHasher.extractHash(fileName)

            assertThat(result).isNull()
        }
    }
}
