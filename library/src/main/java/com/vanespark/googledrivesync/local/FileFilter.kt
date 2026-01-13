package com.vanespark.googledrivesync.local

import java.io.File
import java.util.regex.Pattern

/**
 * Filters for including or excluding files from sync operations.
 *
 * Multiple filters can be combined. A file must pass ALL filters to be included.
 */
sealed class FileFilter {

    /**
     * Test if a file passes this filter.
     *
     * @param file The file to test
     * @return True if the file should be included
     */
    abstract fun accept(file: File): Boolean

    /**
     * Combine this filter with another (both must pass).
     */
    infix fun and(other: FileFilter): FileFilter = CompositeFilter(listOf(this, other), MatchMode.ALL)

    /**
     * Combine this filter with another (either can pass).
     */
    infix fun or(other: FileFilter): FileFilter = CompositeFilter(listOf(this, other), MatchMode.ANY)

    /**
     * Negate this filter.
     */
    operator fun not(): FileFilter = NegatedFilter(this)

    // ========== Filter Implementations ==========

    /**
     * Filter by file extension.
     */
    data class ExtensionFilter(
        val extensions: Set<String>,
        val include: Boolean
    ) : FileFilter() {
        override fun accept(file: File): Boolean {
            val ext = file.extension.lowercase()
            val matches = extensions.any { it.equals(ext, ignoreCase = true) }
            return if (include) matches else !matches
        }
    }

    /**
     * Filter by file size.
     */
    data class SizeFilter(
        val minBytes: Long?,
        val maxBytes: Long?
    ) : FileFilter() {
        override fun accept(file: File): Boolean {
            val size = file.length()
            if (minBytes != null && size < minBytes) return false
            if (maxBytes != null && size > maxBytes) return false
            return true
        }
    }

    /**
     * Filter by glob pattern.
     */
    data class GlobFilter(
        val pattern: String,
        val include: Boolean
    ) : FileFilter() {
        private val regex: Regex by lazy {
            val regexPattern = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
            Regex(regexPattern, RegexOption.IGNORE_CASE)
        }

        override fun accept(file: File): Boolean {
            val matches = regex.matches(file.name)
            return if (include) matches else !matches
        }
    }

    /**
     * Filter by regex pattern.
     */
    data class RegexFilter(
        val pattern: Pattern,
        val include: Boolean
    ) : FileFilter() {
        override fun accept(file: File): Boolean {
            val matches = pattern.matcher(file.name).matches()
            return if (include) matches else !matches
        }
    }

    /**
     * Filter hidden files (starting with .).
     */
    data class HiddenFilter(
        val includeHidden: Boolean
    ) : FileFilter() {
        override fun accept(file: File): Boolean {
            val isHidden = file.name.startsWith(".")
            return if (includeHidden) true else !isHidden
        }
    }

    /**
     * Filter by file path prefix.
     */
    data class PathPrefixFilter(
        val prefix: String,
        val include: Boolean
    ) : FileFilter() {
        override fun accept(file: File): Boolean {
            val matches = file.path.startsWith(prefix)
            return if (include) matches else !matches
        }
    }

    /**
     * Custom filter with predicate.
     */
    data class CustomFilter(
        val predicate: (File) -> Boolean
    ) : FileFilter() {
        override fun accept(file: File): Boolean = predicate(file)
    }

    /**
     * Composite filter combining multiple filters.
     */
    data class CompositeFilter(
        val filters: List<FileFilter>,
        val mode: MatchMode
    ) : FileFilter() {
        override fun accept(file: File): Boolean {
            return when (mode) {
                MatchMode.ALL -> filters.all { it.accept(file) }
                MatchMode.ANY -> filters.any { it.accept(file) }
            }
        }
    }

    /**
     * Negated filter.
     */
    data class NegatedFilter(
        val filter: FileFilter
    ) : FileFilter() {
        override fun accept(file: File): Boolean = !filter.accept(file)
    }

    /**
     * Filter that accepts all files.
     */
    object AcceptAll : FileFilter() {
        override fun accept(file: File): Boolean = true
    }

    /**
     * Filter that rejects all files.
     */
    object RejectAll : FileFilter() {
        override fun accept(file: File): Boolean = false
    }

    enum class MatchMode {
        ALL, ANY
    }

    companion object {
        /**
         * Include only files with specified extensions.
         */
        fun includeExtensions(vararg extensions: String): FileFilter =
            ExtensionFilter(extensions.toSet(), include = true)

        /**
         * Exclude files with specified extensions.
         */
        fun excludeExtensions(vararg extensions: String): FileFilter =
            ExtensionFilter(extensions.toSet(), include = false)

        /**
         * Filter by maximum file size.
         */
        fun maxSize(bytes: Long): FileFilter =
            SizeFilter(minBytes = null, maxBytes = bytes)

        /**
         * Filter by minimum file size.
         */
        fun minSize(bytes: Long): FileFilter =
            SizeFilter(minBytes = bytes, maxBytes = null)

        /**
         * Filter by size range.
         */
        fun sizeRange(minBytes: Long, maxBytes: Long): FileFilter =
            SizeFilter(minBytes = minBytes, maxBytes = maxBytes)

        /**
         * Include files matching glob pattern.
         */
        fun includePattern(pattern: String): FileFilter =
            GlobFilter(pattern, include = true)

        /**
         * Exclude files matching glob pattern.
         */
        fun excludePattern(pattern: String): FileFilter =
            GlobFilter(pattern, include = false)

        /**
         * Include files matching regex pattern.
         */
        fun includeRegex(pattern: String): FileFilter =
            RegexFilter(Pattern.compile(pattern), include = true)

        /**
         * Exclude files matching regex pattern.
         */
        fun excludeRegex(pattern: String): FileFilter =
            RegexFilter(Pattern.compile(pattern), include = false)

        /**
         * Exclude hidden files (starting with .).
         */
        fun excludeHidden(): FileFilter =
            HiddenFilter(includeHidden = false)

        /**
         * Include hidden files (starting with .).
         */
        fun includeHidden(): FileFilter =
            HiddenFilter(includeHidden = true)

        /**
         * Include files in specified path.
         */
        fun includePath(prefix: String): FileFilter =
            PathPrefixFilter(prefix, include = true)

        /**
         * Exclude files in specified path.
         */
        fun excludePath(prefix: String): FileFilter =
            PathPrefixFilter(prefix, include = false)

        /**
         * Custom filter with predicate.
         */
        fun custom(predicate: (File) -> Boolean): FileFilter =
            CustomFilter(predicate)

        /**
         * Combine multiple filters (all must pass).
         */
        fun all(vararg filters: FileFilter): FileFilter =
            CompositeFilter(filters.toList(), MatchMode.ALL)

        /**
         * Combine multiple filters (any can pass).
         */
        fun any(vararg filters: FileFilter): FileFilter =
            CompositeFilter(filters.toList(), MatchMode.ANY)

        /**
         * Default filter for sync operations.
         * Excludes hidden files and common temporary files.
         */
        fun defaultSyncFilter(): FileFilter = all(
            excludeHidden(),
            excludeExtensions("tmp", "temp", "cache", "log", "bak"),
            excludePattern("*.swp"),
            excludePattern("*~")
        )
    }
}

/**
 * Apply a list of filters to a file.
 * All filters must pass for the file to be included.
 */
fun List<FileFilter>.accept(file: File): Boolean = all { it.accept(file) }

/**
 * Filter a list of files using multiple filters.
 */
fun List<File>.filterWith(filters: List<FileFilter>): List<File> =
    filter { file -> filters.accept(file) }

/**
 * Filter a list of files using a single filter.
 */
fun List<File>.filterWith(filter: FileFilter): List<File> =
    filter { file -> filter.accept(file) }
