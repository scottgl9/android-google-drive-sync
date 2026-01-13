package com.vanespark.googledrivesync.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SyncHistoryTest {

    @Nested
    inner class SyncHistoryStatusTests {

        @Test
        fun `all SyncHistoryStatus values are defined`() {
            val statuses = SyncHistoryStatus.entries

            assertThat(statuses).containsExactly(
                SyncHistoryStatus.SUCCESS,
                SyncHistoryStatus.PARTIAL_SUCCESS,
                SyncHistoryStatus.FAILED,
                SyncHistoryStatus.CANCELLED,
                SyncHistoryStatus.PAUSED,
                SyncHistoryStatus.NOT_SIGNED_IN,
                SyncHistoryStatus.NETWORK_UNAVAILABLE
            )
        }

        @Test
        fun `PAUSED status exists`() {
            assertThat(SyncHistoryStatus.PAUSED).isNotNull()
        }
    }

    @Nested
    inner class SyncHistoryEntryTests {

        @Test
        fun `SyncHistoryEntry contains all fields`() {
            val entry = SyncHistoryEntry(
                id = "entry123",
                timestampMs = System.currentTimeMillis(),
                mode = "UPLOAD_ONLY",
                status = SyncHistoryStatus.SUCCESS,
                filesUploaded = 10,
                filesDownloaded = 5,
                filesSkipped = 2,
                filesFailed = 0,
                bytesTransferred = 50000,
                durationMs = 5000,
                errorMessage = null
            )

            assertThat(entry.id).isEqualTo("entry123")
            assertThat(entry.mode).isEqualTo("UPLOAD_ONLY")
            assertThat(entry.status).isEqualTo(SyncHistoryStatus.SUCCESS)
            assertThat(entry.filesUploaded).isEqualTo(10)
            assertThat(entry.filesDownloaded).isEqualTo(5)
        }

        @Test
        fun `SyncHistoryEntry has sensible defaults`() {
            val entry = SyncHistoryEntry(
                id = "entry123",
                timestampMs = System.currentTimeMillis(),
                mode = "BIDIRECTIONAL",
                status = SyncHistoryStatus.FAILED
            )

            assertThat(entry.filesUploaded).isEqualTo(0)
            assertThat(entry.filesDownloaded).isEqualTo(0)
            assertThat(entry.filesSkipped).isEqualTo(0)
            assertThat(entry.filesFailed).isEqualTo(0)
            assertThat(entry.bytesTransferred).isEqualTo(0)
            assertThat(entry.durationMs).isEqualTo(0)
        }

        @Test
        fun `SyncHistoryEntry can have error message`() {
            val entry = SyncHistoryEntry(
                id = "entry123",
                timestampMs = System.currentTimeMillis(),
                mode = "UPLOAD_ONLY",
                status = SyncHistoryStatus.FAILED,
                errorMessage = "Network timeout"
            )

            assertThat(entry.errorMessage).isEqualTo("Network timeout")
        }
    }

    @Nested
    inner class SyncStatisticsTests {

        @Test
        fun `SyncStatistics contains aggregated data`() {
            val stats = SyncStatistics(
                totalSyncs = 100,
                successfulSyncs = 90,
                failedSyncs = 10,
                totalBytesTransferred = 1000000000,
                totalFilesUploaded = 5000,
                totalFilesDownloaded = 3000,
                lastSyncTime = System.currentTimeMillis()
            )

            assertThat(stats.totalSyncs).isEqualTo(100)
            assertThat(stats.successfulSyncs).isEqualTo(90)
            assertThat(stats.failedSyncs).isEqualTo(10)
            assertThat(stats.totalFilesUploaded).isEqualTo(5000)
            assertThat(stats.totalFilesDownloaded).isEqualTo(3000)
        }

        @Test
        fun `SyncStatistics calculates success rate`() {
            val stats = SyncStatistics(
                totalSyncs = 100,
                successfulSyncs = 80,
                failedSyncs = 20,
                totalBytesTransferred = 0,
                totalFilesUploaded = 0,
                totalFilesDownloaded = 0,
                lastSyncTime = null
            )

            // Success rate = 80 / 100 = 0.8
            assertThat(stats.successRate).isWithin(0.01f).of(0.8f)
        }

        @Test
        fun `SyncStatistics success rate is 0 when no syncs`() {
            val stats = SyncStatistics(
                totalSyncs = 0,
                successfulSyncs = 0,
                failedSyncs = 0,
                totalBytesTransferred = 0,
                totalFilesUploaded = 0,
                totalFilesDownloaded = 0,
                lastSyncTime = null
            )

            assertThat(stats.successRate).isEqualTo(0f)
        }

        @Test
        fun `SyncStatistics allows null lastSyncTime`() {
            val stats = SyncStatistics(
                totalSyncs = 0,
                successfulSyncs = 0,
                failedSyncs = 0,
                totalBytesTransferred = 0,
                totalFilesUploaded = 0,
                totalFilesDownloaded = 0,
                lastSyncTime = null
            )

            assertThat(stats.lastSyncTime).isNull()
        }
    }
}
