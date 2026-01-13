package com.vanespark.googledrivesync.compression

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
import java.io.File

class CompressionManagerTest {

    private lateinit var compressionManager: CompressionManager

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.w(any(), any<String>(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0

        compressionManager = CompressionManager()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Nested
    inner class ShouldCompressTests {

        @Test
        fun `shouldCompress returns false for small files`() {
            val smallFile = File(tempDir, "small.txt").apply {
                writeText("Hi")
            }

            val result = compressionManager.shouldCompress(smallFile)

            assertThat(result).isFalse()
        }

        @Test
        fun `shouldCompress returns false for already compressed formats`() {
            val zipFile = File(tempDir, "archive.zip").apply {
                writeBytes(ByteArray(2000) { it.toByte() })
            }

            val result = compressionManager.shouldCompress(zipFile)

            assertThat(result).isFalse()
        }

        @Test
        fun `shouldCompress returns false for image formats`() {
            val jpgFile = File(tempDir, "photo.jpg").apply {
                writeBytes(ByteArray(2000) { it.toByte() })
            }

            val result = compressionManager.shouldCompress(jpgFile)

            assertThat(result).isFalse()
        }

        @Test
        fun `shouldCompress returns true for text files above minimum size`() {
            val textFile = File(tempDir, "document.txt").apply {
                writeText("A".repeat(2000))
            }

            val result = compressionManager.shouldCompress(textFile)

            assertThat(result).isTrue()
        }

        @Test
        fun `shouldCompress returns true for json files`() {
            val jsonFile = File(tempDir, "data.json").apply {
                writeText("{" + "\"key\":\"value\",".repeat(100) + "\"end\":true}")
            }

            val result = compressionManager.shouldCompress(jsonFile)

            assertThat(result).isTrue()
        }
    }

    @Nested
    inner class CompressTests {

        @Test
        fun `compress creates gzip file`() = runTest {
            val inputFile = File(tempDir, "input.txt").apply {
                writeText("Hello World! ".repeat(100))
            }
            val outputFile = File(tempDir, "output.gz")

            val result = compressionManager.compress(inputFile, outputFile)

            assertThat(outputFile.exists()).isTrue()
            assertThat(result.compressedFile).isEqualTo(outputFile)
            assertThat(result.originalSize).isEqualTo(inputFile.length())
            assertThat(result.compressedSize).isLessThan(result.originalSize)
        }

        @Test
        fun `compress reports correct compression ratio`() = runTest {
            val inputFile = File(tempDir, "input.txt").apply {
                writeText("AAAAAAAAAA".repeat(1000)) // Highly compressible
            }
            val outputFile = File(tempDir, "output.gz")

            val result = compressionManager.compress(inputFile, outputFile)

            assertThat(result.compressionRatio).isLessThan(0.5) // Should compress well
            assertThat(result.percentSaved).isGreaterThan(50.0)
        }

        @Test
        fun `compress calls progress callback`() = runTest {
            val inputFile = File(tempDir, "input.txt").apply {
                writeText("Test content ".repeat(1000))
            }
            val outputFile = File(tempDir, "output.gz")

            var progressCalled = false
            compressionManager.compress(inputFile, outputFile) { progress ->
                progressCalled = true
                assertThat(progress).isAtLeast(0f)
                assertThat(progress).isAtMost(1f)
            }

            assertThat(progressCalled).isTrue()
        }

        @Test
        fun `compress uses default output filename with gz extension`() = runTest {
            val inputFile = File(tempDir, "document.txt").apply {
                writeText("Content ".repeat(200))
            }

            val result = compressionManager.compress(inputFile)

            assertThat(result.compressedFile.name).isEqualTo("document.txt.gz")
            assertThat(result.compressedFile.exists()).isTrue()
        }
    }

    @Nested
    inner class DecompressTests {

        @Test
        fun `decompress restores original content`() = runTest {
            val originalContent = "Hello World! This is a test. ".repeat(100)
            val inputFile = File(tempDir, "input.txt").apply {
                writeText(originalContent)
            }
            val compressedFile = File(tempDir, "compressed.gz")
            val decompressedFile = File(tempDir, "decompressed.txt")

            compressionManager.compress(inputFile, compressedFile)
            val result = compressionManager.decompress(compressedFile, decompressedFile)

            assertThat(decompressedFile.exists()).isTrue()
            assertThat(decompressedFile.readText()).isEqualTo(originalContent)
            assertThat(result.decompressedSize).isEqualTo(inputFile.length())
        }

        @Test
        fun `decompress reports correct sizes`() = runTest {
            val inputFile = File(tempDir, "input.txt").apply {
                writeText("Test ".repeat(500))
            }
            val compressedFile = File(tempDir, "compressed.gz")
            val decompressedFile = File(tempDir, "decompressed.txt")

            compressionManager.compress(inputFile, compressedFile)
            val result = compressionManager.decompress(compressedFile, decompressedFile)

            assertThat(result.compressedSize).isEqualTo(compressedFile.length())
            assertThat(result.decompressedSize).isEqualTo(decompressedFile.length())
        }
    }

    @Nested
    inner class CompressIfBeneficialTests {

        @Test
        fun `compressIfBeneficial returns original for small files`() = runTest {
            val smallFile = File(tempDir, "small.txt").apply {
                writeText("Hi")
            }

            val (resultFile, wasCompressed) = compressionManager.compressIfBeneficial(
                smallFile,
                tempDir
            )

            assertThat(wasCompressed).isFalse()
            assertThat(resultFile).isEqualTo(smallFile)
        }

        @Test
        fun `compressIfBeneficial compresses beneficial files`() = runTest {
            val largeFile = File(tempDir, "large.txt").apply {
                writeText("A".repeat(10000)) // Highly compressible
            }

            val (resultFile, wasCompressed) = compressionManager.compressIfBeneficial(
                largeFile,
                File(tempDir, "compressed")
            )

            assertThat(wasCompressed).isTrue()
            assertThat(resultFile.name).endsWith(".gz")
            assertThat(resultFile.length()).isLessThan(largeFile.length())
        }

        @Test
        fun `compressIfBeneficial skips already compressed formats`() = runTest {
            val zipFile = File(tempDir, "archive.zip").apply {
                writeBytes(ByteArray(5000) { it.toByte() })
            }

            val (resultFile, wasCompressed) = compressionManager.compressIfBeneficial(
                zipFile,
                tempDir
            )

            assertThat(wasCompressed).isFalse()
            assertThat(resultFile).isEqualTo(zipFile)
        }
    }

    @Nested
    inner class IsGzipCompressedTests {

        @Test
        fun `isGzipCompressed returns true for gzip files`() = runTest {
            val inputFile = File(tempDir, "input.txt").apply {
                writeText("Test content ".repeat(100))
            }
            val compressedFile = File(tempDir, "compressed.gz")

            compressionManager.compress(inputFile, compressedFile)

            assertThat(compressionManager.isGzipCompressed(compressedFile)).isTrue()
        }

        @Test
        fun `isGzipCompressed returns false for regular files`() {
            val regularFile = File(tempDir, "regular.txt").apply {
                writeText("Not compressed")
            }

            assertThat(compressionManager.isGzipCompressed(regularFile)).isFalse()
        }

        @Test
        fun `isGzipCompressed returns false for non-existent files`() {
            val nonExistent = File(tempDir, "does_not_exist.gz")

            assertThat(compressionManager.isGzipCompressed(nonExistent)).isFalse()
        }

        @Test
        fun `isGzipCompressed returns false for empty files`() {
            val emptyFile = File(tempDir, "empty.gz").apply {
                createNewFile()
            }

            assertThat(compressionManager.isGzipCompressed(emptyFile)).isFalse()
        }
    }

    @Nested
    inner class CompressionConfigTests {

        @Test
        fun `default config has expected values`() {
            val config = CompressionConfig()

            assertThat(config.level).isEqualTo(CompressionLevel.DEFAULT)
            assertThat(config.minSizeToCompress).isEqualTo(1024L)
            assertThat(config.bufferSize).isEqualTo(8192)
        }

        @Test
        fun `default skip extensions include common compressed formats`() {
            val skipExtensions = CompressionConfig.DEFAULT_SKIP_EXTENSIONS

            assertThat(skipExtensions).contains("zip")
            assertThat(skipExtensions).contains("gz")
            assertThat(skipExtensions).contains("jpg")
            assertThat(skipExtensions).contains("png")
            assertThat(skipExtensions).contains("mp4")
            assertThat(skipExtensions).contains("pdf")
        }

        @Test
        fun `configure applies custom settings`() {
            val customConfig = CompressionConfig(
                level = CompressionLevel.BEST,
                minSizeToCompress = 500L,
                skipExtensions = setOf("custom")
            )

            compressionManager.configure(customConfig)

            // After configuring with lower minSizeToCompress
            val smallFile = File(tempDir, "test.txt").apply {
                writeText("A".repeat(600))
            }

            assertThat(compressionManager.shouldCompress(smallFile)).isTrue()
        }
    }

    @Nested
    inner class CompressionResultTests {

        @Test
        fun `CompressionResult calculates bytesSaved correctly`() {
            val result = CompressionResult(
                compressedFile = File(tempDir, "test.gz"),
                originalSize = 10000,
                compressedSize = 3000,
                compressionRatio = 0.3,
                durationMs = 100
            )

            assertThat(result.bytesSaved).isEqualTo(7000)
        }

        @Test
        fun `CompressionResult calculates percentSaved correctly`() {
            val result = CompressionResult(
                compressedFile = File(tempDir, "test.gz"),
                originalSize = 10000,
                compressedSize = 3000,
                compressionRatio = 0.3,
                durationMs = 100
            )

            assertThat(result.percentSaved).isWithin(0.1).of(70.0)
        }
    }

    @Nested
    inner class CompressionLevelTests {

        @Test
        fun `CompressionLevel has expected values`() {
            assertThat(CompressionLevel.NONE.value).isEqualTo(0)
            assertThat(CompressionLevel.FAST.value).isEqualTo(1)
            assertThat(CompressionLevel.DEFAULT.value).isEqualTo(6)
            assertThat(CompressionLevel.BEST.value).isEqualTo(9)
        }
    }
}
