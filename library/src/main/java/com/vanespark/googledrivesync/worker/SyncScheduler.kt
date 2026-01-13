package com.vanespark.googledrivesync.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vanespark.googledrivesync.resilience.NetworkPolicy
import com.vanespark.googledrivesync.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Configuration for scheduled sync
 */
data class SyncScheduleConfig(
    /**
     * Interval between periodic syncs
     */
    val interval: Duration = 12.hours,

    /**
     * Flex interval for WorkManager (sync can run within this window)
     */
    val flexInterval: Duration = 2.hours,

    /**
     * Initial delay before first sync
     */
    val initialDelay: Duration = Duration.ZERO,

    /**
     * Network type requirement
     */
    val networkPolicy: NetworkPolicy = NetworkPolicy.UNMETERED_ONLY,

    /**
     * Require device to be charging
     */
    val requiresCharging: Boolean = false,

    /**
     * Require battery not low
     */
    val requiresBatteryNotLow: Boolean = true,

    /**
     * Require device to be idle
     */
    val requiresDeviceIdle: Boolean = false,

    /**
     * Sync mode to use
     */
    val syncMode: String = SyncWorker.SYNC_MODE_BIDIRECTIONAL
)

/**
 * Status of scheduled sync work
 */
data class SyncWorkStatus(
    /**
     * Whether periodic sync is scheduled
     */
    val isScheduled: Boolean,

    /**
     * Current state of the work
     */
    val state: WorkInfo.State?,

    /**
     * Progress percentage (0-100)
     */
    val progress: Int,

    /**
     * Error message if failed
     */
    val errorMessage: String?
)

/**
 * Schedules and manages background sync operations using WorkManager.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Schedule periodic background sync.
     *
     * @param config Schedule configuration
     */
    fun schedulePeriodicSync(config: SyncScheduleConfig = SyncScheduleConfig()) {
        Log.d(Constants.TAG, "Scheduling periodic sync: interval=${config.interval}")

        val constraints = buildConstraints(config)

        val inputData = Data.Builder()
            .putString(SyncWorker.KEY_SYNC_MODE, config.syncMode)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            config.interval.inWholeMinutes,
            TimeUnit.MINUTES,
            config.flexInterval.inWholeMinutes,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(inputData)
            .apply {
                if (config.initialDelay > Duration.ZERO) {
                    setInitialDelay(config.initialDelay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                }
            }
            .build()

        workManager.enqueueUniquePeriodicWork(
            SyncWorker.WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )

        Log.d(Constants.TAG, "Periodic sync scheduled successfully")
    }

    /**
     * Cancel scheduled periodic sync.
     */
    fun cancelPeriodicSync() {
        workManager.cancelUniqueWork(SyncWorker.WORK_NAME_PERIODIC)
        Log.d(Constants.TAG, "Periodic sync cancelled")
    }

    /**
     * Request a one-time sync.
     *
     * @param syncMode Sync mode to use
     * @param networkPolicy Network requirements
     */
    fun requestOneTimeSync(
        syncMode: String = SyncWorker.SYNC_MODE_BIDIRECTIONAL,
        networkPolicy: NetworkPolicy = NetworkPolicy.ANY
    ) {
        Log.d(Constants.TAG, "Requesting one-time sync: mode=$syncMode")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkPolicy.toNetworkType())
            .build()

        val inputData = Data.Builder()
            .putString(SyncWorker.KEY_SYNC_MODE, syncMode)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .build()

        workManager.enqueueUniqueWork(
            SyncWorker.WORK_NAME_ONE_TIME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        Log.d(Constants.TAG, "One-time sync requested")
    }

    /**
     * Cancel one-time sync request.
     */
    fun cancelOneTimeSync() {
        workManager.cancelUniqueWork(SyncWorker.WORK_NAME_ONE_TIME)
        Log.d(Constants.TAG, "One-time sync cancelled")
    }

    /**
     * Cancel all sync work.
     */
    fun cancelAllSync() {
        workManager.cancelUniqueWork(SyncWorker.WORK_NAME_PERIODIC)
        workManager.cancelUniqueWork(SyncWorker.WORK_NAME_ONE_TIME)
        Log.d(Constants.TAG, "All sync work cancelled")
    }

    /**
     * Check if periodic sync is currently scheduled.
     */
    fun isPeriodicSyncScheduled(): Boolean {
        val workInfos = workManager
            .getWorkInfosForUniqueWork(SyncWorker.WORK_NAME_PERIODIC)
            .get()

        return workInfos.any { !it.state.isFinished }
    }

    /**
     * Observe the status of periodic sync work.
     */
    fun observePeriodicSyncStatus(): Flow<SyncWorkStatus> {
        return workManager
            .getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME_PERIODIC)
            .map { workInfos ->
                val workInfo = workInfos.firstOrNull()
                SyncWorkStatus(
                    isScheduled = workInfo != null && !workInfo.state.isFinished,
                    state = workInfo?.state,
                    progress = workInfo?.progress?.getInt("progress", 0) ?: 0,
                    errorMessage = workInfo?.outputData?.getString(SyncWorker.KEY_ERROR_MESSAGE)
                )
            }
    }

    /**
     * Observe the status of one-time sync work.
     */
    fun observeOneTimeSyncStatus(): Flow<SyncWorkStatus> {
        return workManager
            .getWorkInfosForUniqueWorkFlow(SyncWorker.WORK_NAME_ONE_TIME)
            .map { workInfos ->
                val workInfo = workInfos.firstOrNull()
                SyncWorkStatus(
                    isScheduled = workInfo != null && !workInfo.state.isFinished,
                    state = workInfo?.state,
                    progress = workInfo?.progress?.getInt("progress", 0) ?: 0,
                    errorMessage = workInfo?.outputData?.getString(SyncWorker.KEY_ERROR_MESSAGE)
                )
            }
    }

    /**
     * Build WorkManager constraints from config.
     */
    private fun buildConstraints(config: SyncScheduleConfig): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(config.networkPolicy.toNetworkType())
            .setRequiresCharging(config.requiresCharging)
            .setRequiresBatteryNotLow(config.requiresBatteryNotLow)
            .setRequiresDeviceIdle(config.requiresDeviceIdle)
            .build()
    }

    /**
     * Convert NetworkPolicy to WorkManager NetworkType.
     */
    private fun NetworkPolicy.toNetworkType(): NetworkType {
        return when (this) {
            NetworkPolicy.ANY -> NetworkType.CONNECTED
            NetworkPolicy.UNMETERED_ONLY -> NetworkType.UNMETERED
            NetworkPolicy.WIFI_ONLY -> NetworkType.UNMETERED
            NetworkPolicy.NOT_ROAMING -> NetworkType.NOT_ROAMING
        }
    }
}
