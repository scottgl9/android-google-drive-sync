package com.vanespark.googledrivesync.sample

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vanespark.googledrivesync.api.GoogleSyncClient
import com.vanespark.googledrivesync.auth.AuthResult
import com.vanespark.googledrivesync.auth.AuthState
import com.vanespark.googledrivesync.resilience.NetworkPolicy
import com.vanespark.googledrivesync.resilience.SyncProgress
import com.vanespark.googledrivesync.sync.ConflictPolicy
import com.vanespark.googledrivesync.sync.SyncResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours

/**
 * UI state for the main screen
 */
data class MainUiState(
    val isConfigured: Boolean = false,
    val syncDirectory: String = "",
    val lastSyncTime: Long? = null,
    val syncResultMessage: String? = null,
    val isPeriodicSyncEnabled: Boolean = false
)

/**
 * ViewModel for the sample app main screen.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val syncClient: GoogleSyncClient
) : ViewModel() {

    // Auth state from the sync client
    val authState: StateFlow<AuthState> = syncClient.authState

    // Sync progress from the sync client
    val syncProgress: StateFlow<SyncProgress> = syncClient.syncProgress

    // Whether a sync is in progress
    val isSyncing: StateFlow<Boolean> = syncClient.isSyncing

    // UI-specific state
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    /**
     * Configure the sync client with the given directory.
     */
    fun configure(syncDirectory: File) {
        syncClient.configure {
            rootFolderName("GoogleDriveSyncSample")
            syncDirectory(syncDirectory)
            conflictPolicy(ConflictPolicy.NEWER_WINS)
            networkPolicy(NetworkPolicy.ANY)
            excludeExtensions("tmp", "bak", "log")
            excludeHiddenFiles()
        }

        _uiState.value = _uiState.value.copy(
            isConfigured = true,
            syncDirectory = syncDirectory.absolutePath,
            lastSyncTime = syncClient.getLastSyncTime()
        )
    }

    /**
     * Get the sign-in intent.
     */
    fun getSignInIntent(): Intent = syncClient.getSignInIntent()

    /**
     * Handle the sign-in result.
     */
    fun handleSignInResult(data: Intent?) {
        viewModelScope.launch {
            val result = syncClient.handleSignInResult(data)
            when (result) {
                is AuthResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        syncResultMessage = "Signed in as ${result.email}"
                    )
                }
                is AuthResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        syncResultMessage = "Sign-in failed: ${result.message}"
                    )
                }
                AuthResult.Cancelled -> {
                    _uiState.value = _uiState.value.copy(
                        syncResultMessage = "Sign-in cancelled"
                    )
                }
                AuthResult.NeedsPermission -> {
                    _uiState.value = _uiState.value.copy(
                        syncResultMessage = "Additional permissions required"
                    )
                }
            }
        }
    }

    /**
     * Sign out.
     */
    fun signOut() {
        viewModelScope.launch {
            syncClient.signOut()
            _uiState.value = _uiState.value.copy(
                syncResultMessage = "Signed out",
                isPeriodicSyncEnabled = false
            )
        }
    }

    /**
     * Perform a bidirectional sync.
     */
    fun sync() {
        viewModelScope.launch {
            val result = syncClient.sync()
            handleSyncResult(result)
        }
    }

    /**
     * Upload local files to cloud.
     */
    fun uploadOnly() {
        viewModelScope.launch {
            val result = syncClient.uploadOnly()
            handleSyncResult(result)
        }
    }

    /**
     * Download cloud files to local.
     */
    fun downloadOnly() {
        viewModelScope.launch {
            val result = syncClient.downloadOnly()
            handleSyncResult(result)
        }
    }

    /**
     * Cancel the current sync operation.
     */
    fun cancelSync() {
        syncClient.cancelSync()
        _uiState.value = _uiState.value.copy(
            syncResultMessage = "Sync cancelled"
        )
    }

    /**
     * Toggle periodic sync.
     */
    fun togglePeriodicSync() {
        if (syncClient.isPeriodicSyncScheduled()) {
            syncClient.cancelPeriodicSync()
            _uiState.value = _uiState.value.copy(
                isPeriodicSyncEnabled = false,
                syncResultMessage = "Periodic sync disabled"
            )
        } else {
            syncClient.schedulePeriodicSync(
                interval = 12.hours,
                networkPolicy = NetworkPolicy.UNMETERED_ONLY,
                requiresCharging = false
            )
            _uiState.value = _uiState.value.copy(
                isPeriodicSyncEnabled = true,
                syncResultMessage = "Periodic sync enabled (every 12 hours)"
            )
        }
    }

    /**
     * Clear the result message.
     */
    fun clearMessage() {
        _uiState.value = _uiState.value.copy(syncResultMessage = null)
    }

    private fun handleSyncResult(result: SyncResult) {
        val message = when (result) {
            is SyncResult.Success -> {
                _uiState.value = _uiState.value.copy(
                    lastSyncTime = System.currentTimeMillis()
                )
                "Sync completed: ${result.filesUploaded} uploaded, " +
                    "${result.filesDownloaded} downloaded"
            }
            is SyncResult.PartialSuccess -> {
                "Sync partially completed: ${result.filesSucceeded} succeeded, " +
                    "${result.filesFailed} failed"
            }
            is SyncResult.Error -> "Sync failed: ${result.message}"
            SyncResult.NotSignedIn -> "Not signed in"
            SyncResult.NetworkUnavailable -> "Network unavailable"
            SyncResult.Cancelled -> "Sync cancelled"
        }

        _uiState.value = _uiState.value.copy(syncResultMessage = message)
    }
}
