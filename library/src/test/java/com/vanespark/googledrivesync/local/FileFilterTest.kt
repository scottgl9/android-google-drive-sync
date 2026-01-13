package com.vanespark.googledrivesync.local

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileFilterTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var txtFile: File
    private lateinit var pdfFile: File
    private lateinit var hiddenFile: File
    private lateinit var tempFile: File
    private lateinit var largeFile: File
    private lateinit var smallFile: File

    @BeforeEach
    fun setup() {
        txtFile = File(tempDir, "document.txt").apply { writeText("Hello") }
        pdfFile = File(tempDir, "report.pdf").apply { writeText("PDF content") }
        hiddenFile = File(tempDir, ".hidden").apply { writeText("Secret") }
        tempFile = File(tempDir, "cache.tmp").apply { writeText("Temp data") }
        smallFile = File(tempDir, "small.dat").apply { writeText("A") }
        largeFile = File(tempDir, "large.dat").apply { writeText("A".repeat(10000)) }
    }

    @Nested
    inner class ExtensionFilterTests {

        @Test
        fun `includeExtensions accepts files with matching extensions`() {
            val filter = FileFilter.includeExtensions("txt", "pdf")

            assertThat(filter.accept(txtFile)).isTrue()
            assertThat(filter.accept(pdfFile)).isTrue()
            assertThat(filter.accept(hiddenFile)).isFalse()
        }

        @Test
        fun `excludeExtensions rejects files with matching extensions`() {
            val filter = FileFilter.excludeExtensions("tmp", "log")

            assertThat(filter.accept(txtFile)).isTrue()
            assertThat(filter.accept(pdfFile)).isTrue()
            assertThat(filter.accept(tempFile)).isFalse()
        }

        @Test
        fun `extension matching is case insensitive`() {
            val upperFile = File(tempDir, "file.TXT").apply { writeText("Test") }
            val filter = FileFilter.includeExtensions("txt")

            assertThat(filter.accept(upperFile)).isTrue()
        }
    }

    @Nested
    inner class SizeFilterTests {

        @Test
        fun `maxSize accepts files smaller than limit`() {
            val filter = FileFilter.maxSize(1000)

            assertThat(filter.accept(smallFile)).isTrue()
            assertThat(filter.accept(largeFile)).isFalse()
        }

        @Test
        fun `minSize accepts files larger than limit`() {
            val filter = FileFilter.minSize(100)

            assertThat(filter.accept(smallFile)).isFalse()
            assertThat(filter.accept(largeFile)).isTrue()
        }

        @Test
        fun `sizeRange accepts files within range`() {
            val filter = FileFilter.sizeRange(5, 100)

            assertThat(filter.accept(smallFile)).isFalse()
            assertThat(filter.accept(txtFile)).isTrue()
            assertThat(filter.accept(largeFile)).isFalse()
        }
    }

    @Nested
    inner class HiddenFilterTests {

        @Test
        fun `excludeHidden rejects hidden files`() {
            val filter = FileFilter.excludeHidden()

            assertThat(filter.accept(txtFile)).isTrue()
            assertThat(filter.accept(hiddenFile)).isFalse()
        }

        @Test
        fun `includeHidden accepts all files`() {
            val filter = FileFilter.includeHidden()

            assertThat(filter.accept(txtFile)).isTrue()
            assertThat(filter.accept(hiddenFile)).isTrue()
        }
    }

    @Nested
    inner class GlobFilterTests {

        @Test
        fun `includePattern matches glob pattern`() {
            val filter = FileFilter.includePattern("*.txt")

            assertThat(filter.accept(txtFile)).isTrue()
            assertThat(filter.accept(pdfFile)).isFalse()
        }

        @Test
        fun `excludePattern rejects matching files`() {
            val filter = FileFilter.excludePattern("*.tmp")

            assertThat(filter.accept(txtFile)).isTrue()
            assertThat(filter.accept(tempFile)).isFalse()
        }

        @Test
        fun `wildcard matches any characters`() {
            val filter = FileFilter.includePattern("doc*")

            assertThat(filter.accept(txtFile)).isTrue()
            assertThat(filter.accept(pdfFile)).isFalse()
        }
    }

    @Nested
    inner class CompositeFilterTests {

        @Test
        fun `and combines filters requiring all to pass`() {
            val filter = FileFilter.excludeHidden() and FileFilter.excludeExtensions("tmp")

            assertThat(filter.accept(txtFile)).isTrue()
            assertThat(filter.accept(hiddenFile)).isFalse()
            assertThat(filter.accept(tempFile)).isFalse()
        }

        @Test
        fun `or combines filters requiring any to pass`() {
            val filter = FileFilter.includeExtensions("txt") or FileFilter.includeExtensions("pdf")

            assertThat(filter.accept(txtFile)).isTrue()
            assertThat(filter.accept(pdfFile)).isTrue()
            assertThat(filter.accept(tempFile)).isFalse()
        }

        @Test
        fun `not negates filter`() {
            val filter = !FileFilter.includeExtensions("txt")

            assertThat(filter.accept(txtFile)).isFalse()
            assertThat(filter.accept(pdfFile)).isTrue()
        }

        @Test
        fun `all requires all filters to pass`() {
            val filter = FileFilter.all(
                FileFilter.excludeHidden(),
                FileFilter.excludeExtensions("tmp"),
                FileFilter.maxSize(5000)
            )

            assertThat(filter.accept(txtFile)).isTrue()
            assertThat(filter.accept(hiddenFile)).isFalse()
            assertThat(filter.accept(tempFile)).isFalse()
            assertThat(filter.accept(largeFile)).isFalse()
        }

        @Test
        fun `any requires at least one filter to pass`() {
            val filter = FileFilter.any(
                FileFilter.includeExtensions("txt"),
                FileFilter.includeExtensions("pdf")
            )

            assertThat(filter.accept(txtFile)).isTrue()
            assertThat(filter.accept(pdfFile)).isTrue()
            assertThat(filter.accept(tempFile)).isFalse()
        }
    }

    @Nested
    inner class SpecialFiltersTests {

        @Test
        fun `AcceptAll accepts all files`() {
            val filter = FileFilter.AcceptAll

            assertThat(filter.accept(txtFile)).isTrue()
            assertThat(filter.accept(hiddenFile)).isTrue()
            assertThat(filter.accept(tempFile)).isTrue()
        }

        @Test
        fun `RejectAll rejects all files`() {
            val filter = FileFilter.RejectAll

            assertThat(filter.accept(txtFile)).isFalse()
            assertThat(filter.accept(hiddenFile)).isFalse()
        }

        @Test
        fun `custom filter uses predicate`() {
            val filter = FileFilter.custom { it.name.length > 10 }

            assertThat(filter.accept(txtFile)).isTrue() // "document.txt" = 12 chars > 10
            assertThat(filter.accept(pdfFile)).isFalse() // "report.pdf" = 10 chars, not > 10
        }
    }

    @Nested
    inner class DefaultSyncFilterTests {

        @Test
        fun `defaultSyncFilter excludes hidden files`() {
            val filter = FileFilter.defaultSyncFilter()

            assertThat(filter.accept(hiddenFile)).isFalse()
        }

        @Test
        fun `defaultSyncFilter excludes temp files`() {
            val filter = FileFilter.defaultSyncFilter()

            assertThat(filter.accept(tempFile)).isFalse()
        }

        @Test
        fun `defaultSyncFilter accepts normal files`() {
            val filter = FileFilter.defaultSyncFilter()

            assertThat(filter.accept(txtFile)).isTrue()
            assertThat(filter.accept(pdfFile)).isTrue()
        }
    }

    @Nested
    inner class ExtensionFunctionsTests {

        @Test
        fun `filterWith filters list of files`() {
            val files = listOf(txtFile, pdfFile, hiddenFile, tempFile)
            val filter = FileFilter.excludeHidden() and FileFilter.excludeExtensions("tmp")

            val result = files.filterWith(filter)

            assertThat(result).containsExactly(txtFile, pdfFile)
        }

        @Test
        fun `list of filters accept requires all to pass`() {
            val filters = listOf(
                FileFilter.excludeHidden(),
                FileFilter.excludeExtensions("tmp")
            )

            assertThat(filters.accept(txtFile)).isTrue()
            assertThat(filters.accept(hiddenFile)).isFalse()
            assertThat(filters.accept(tempFile)).isFalse()
        }
    }
}
