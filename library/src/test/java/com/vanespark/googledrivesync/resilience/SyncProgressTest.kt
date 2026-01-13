package com.vanespark.googledrivesync.resilience

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SyncProgressTest {

    @Nested
    inner class SyncProgressTests {

        @Test
        fun `default SyncProgress has idle phase`() {
            val progress = SyncProgress()

            assertThat(progress.phase).isEqualTo(SyncPhase.IDLE)
            assertThat(progress.isActive).isFalse()
        }

        @Test
        fun `percentage is calculated correctly`() {
            val progress = SyncProgress(
                totalFiles = 100,
                processedFiles = 25
            )

            assertThat(progress.percentage).isEqualTo(25)
        }

        @Test
        fun `percentage is 0 when totalFiles is 0`() {
            val progress = SyncProgress(
                totalFiles = 0,
                processedFiles = 0
            )

            assertThat(progress.percentage).isEqualTo(0)
        }

        @Test
        fun `isActive is true for active phases`() {
            val uploadingProgress = SyncProgress(phase = SyncPhase.UPLOADING)
            val downloadingProgress = SyncProgress(phase = SyncPhase.DOWNLOADING)
            val scanningProgress = SyncProgress(phase = SyncPhase.SCANNING_LOCAL)

            assertThat(uploadingProgress.isActive).isTrue()
            assertThat(downloadingProgress.isActive).isTrue()
            assertThat(scanningProgress.isActive).isTrue()
        }

        @Test
        fun `isActive is false for terminal phases`() {
            val idleProgress = SyncProgress(phase = SyncPhase.IDLE)
            val completedProgress = SyncProgress(phase = SyncPhase.COMPLETED)
            val failedProgress = SyncProgress(phase = SyncPhase.FAILED)
            val pausedProgress = SyncProgress(phase = SyncPhase.PAUSED)
            val cancelledProgress = SyncProgress(phase = SyncPhase.CANCELLED)

            assertThat(idleProgress.isActive).isFalse()
            assertThat(completedProgress.isActive).isFalse()
            assertThat(failedProgress.isActive).isFalse()
            assertThat(pausedProgress.isActive).isFalse()
            assertThat(cancelledProgress.isActive).isFalse()
        }

        @Test
        fun `durationMs is calculated from startTime`() {
            val startTime = System.currentTimeMillis() - 5000
            val lastUpdate = System.currentTimeMillis()

            val progress = SyncProgress(
                startTime = startTime,
                lastUpdateTime = lastUpdate
            )

            assertThat(progress.durationMs).isAtLeast(5000)
        }

        @Test
        fun `durationMs is 0 when startTime is 0`() {
            val progress = SyncProgress(
                startTime = 0,
                lastUpdateTime = System.currentTimeMillis()
            )

            assertThat(progress.durationMs).isEqualTo(0)
        }

        @Test
        fun `SyncProgress tracks all statistics`() {
            val progress = SyncProgress(
                phase = SyncPhase.UPLOADING,
                currentFile = "document.pdf",
                totalFiles = 100,
                processedFiles = 50,
                uploadedFiles = 30,
                downloadedFiles = 15,
                skippedFiles = 5,
                failedFiles = 0,
                bytesTransferred = 1000000,
                totalBytes = 5000000,
                error = null
            )

            assertThat(progress.currentFile).isEqualTo("document.pdf")
            assertThat(progress.uploadedFiles).isEqualTo(30)
            assertThat(progress.downloadedFiles).isEqualTo(15)
            assertThat(progress.skippedFiles).isEqualTo(5)
            assertThat(progress.failedFiles).isEqualTo(0)
            assertThat(progress.bytesTransferred).isEqualTo(1000000)
            assertThat(progress.totalBytes).isEqualTo(5000000)
        }
    }

    @Nested
    inner class SyncPhaseTests {

        @Test
        fun `all SyncPhase values are defined`() {
            val phases = SyncPhase.entries

            assertThat(phases).containsExactly(
                SyncPhase.IDLE,
                SyncPhase.AUTHENTICATING,
                SyncPhase.PREPARING,
                SyncPhase.SCANNING_LOCAL,
                SyncPhase.SCANNING_REMOTE,
                SyncPhase.COMPARING,
                SyncPhase.UPLOADING,
                SyncPhase.DOWNLOADING,
                SyncPhase.CLEANING_UP,
                SyncPhase.COMPLETED,
                SyncPhase.FAILED,
                SyncPhase.CANCELLED,
                SyncPhase.PAUSED
            )
        }

        @Test
        fun `PAUSED phase exists`() {
            assertThat(SyncPhase.PAUSED).isNotNull()
        }
    }

    @Nested
    inner class ResumeInfoTests {

        @Test
        fun `ResumeInfo contains all required fields`() {
            val resumeInfo = ResumeInfo(
                timestamp = System.currentTimeMillis(),
                syncMode = "UPLOAD_ONLY",
                rootFolder = "SyncFolder",
                pendingFiles = listOf("file1.txt", "file2.txt"),
                completedFiles = listOf("file3.txt"),
                totalFiles = 3,
                bytesTransferred = 1000,
                totalBytes = 5000
            )

            assertThat(resumeInfo.syncMode).isEqualTo("UPLOAD_ONLY")
            assertThat(resumeInfo.rootFolder).isEqualTo("SyncFolder")
            assertThat(resumeInfo.pendingFiles).hasSize(2)
            assertThat(resumeInfo.completedFiles).hasSize(1)
            assertThat(resumeInfo.totalFiles).isEqualTo(3)
        }

        @Test
        fun `progressPercent is calculated correctly`() {
            val resumeInfo = ResumeInfo(
                timestamp = System.currentTimeMillis(),
                syncMode = "BIDIRECTIONAL",
                rootFolder = "Sync",
                pendingFiles = listOf("file1.txt"),
                completedFiles = listOf("file2.txt", "file3.txt"),
                totalFiles = 3,
                bytesTransferred = 2000,
                totalBytes = 3000
            )

            assertThat(resumeInfo.progressPercent).isEqualTo(66) // 2/3 = 66%
        }

        @Test
        fun `progressPercent is 0 when totalFiles is 0`() {
            val resumeInfo = ResumeInfo(
                timestamp = System.currentTimeMillis(),
                syncMode = "UPLOAD_ONLY",
                rootFolder = "Sync",
                pendingFiles = emptyList(),
                completedFiles = emptyList(),
                totalFiles = 0,
                bytesTransferred = 0,
                totalBytes = 0
            )

            assertThat(resumeInfo.progressPercent).isEqualTo(0)
        }

        @Test
        fun `isValid returns true for recent resume info with pending files`() {
            val resumeInfo = ResumeInfo(
                timestamp = System.currentTimeMillis(),
                syncMode = "UPLOAD_ONLY",
                rootFolder = "Sync",
                pendingFiles = listOf("file1.txt"),
                completedFiles = emptyList(),
                totalFiles = 1,
                bytesTransferred = 0,
                totalBytes = 1000
            )

            assertThat(resumeInfo.isValid()).isTrue()
        }

        @Test
        fun `isValid returns false for old resume info`() {
            val oldTimestamp = System.currentTimeMillis() - (2 * 60 * 60 * 1000) // 2 hours ago

            val resumeInfo = ResumeInfo(
                timestamp = oldTimestamp,
                syncMode = "UPLOAD_ONLY",
                rootFolder = "Sync",
                pendingFiles = listOf("file1.txt"),
                completedFiles = emptyList(),
                totalFiles = 1,
                bytesTransferred = 0,
                totalBytes = 1000
            )

            assertThat(resumeInfo.isValid()).isFalse()
        }

        @Test
        fun `isValid returns false when no pending files`() {
            val resumeInfo = ResumeInfo(
                timestamp = System.currentTimeMillis(),
                syncMode = "UPLOAD_ONLY",
                rootFolder = "Sync",
                pendingFiles = emptyList(),
                completedFiles = listOf("file1.txt"),
                totalFiles = 1,
                bytesTransferred = 1000,
                totalBytes = 1000
            )

            assertThat(resumeInfo.isValid()).isFalse()
        }

        @Test
        fun `isValid respects custom timeout`() {
            val timestamp = System.currentTimeMillis() - (30 * 1000) // 30 seconds ago

            val resumeInfo = ResumeInfo(
                timestamp = timestamp,
                syncMode = "UPLOAD_ONLY",
                rootFolder = "Sync",
                pendingFiles = listOf("file1.txt"),
                completedFiles = emptyList(),
                totalFiles = 1,
                bytesTransferred = 0,
                totalBytes = 1000
            )

            // Should be valid with 1-minute timeout
            assertThat(resumeInfo.isValid(60_000)).isTrue()

            // Should be invalid with 10-second timeout
            assertThat(resumeInfo.isValid(10_000)).isFalse()
        }
    }
}
