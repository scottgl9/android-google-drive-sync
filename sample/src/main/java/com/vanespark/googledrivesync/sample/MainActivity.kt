package com.vanespark.googledrivesync.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vanespark.googledrivesync.auth.AuthState
import com.vanespark.googledrivesync.resilience.SyncPhase
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleSignInResult(result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure with app's files directory
        val syncDir = File(filesDir, "sync_data")
        syncDir.mkdirs()
        viewModel.configure(syncDir)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        onSignIn = { signInLauncher.launch(viewModel.getSignInIntent()) }
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSignIn: () -> Unit
) {
    val authState by viewModel.authState.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.syncResultMessage) {
        uiState.syncResultMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Google Drive Sync",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Auth Card
            AuthCard(
                authState = authState,
                onSignIn = onSignIn,
                onSignOut = { viewModel.signOut() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Sync Progress Card
            if (isSyncing || syncProgress.phase != SyncPhase.IDLE) {
                SyncProgressCard(
                    phase = syncProgress.phase,
                    currentFile = syncProgress.currentFile,
                    filesCompleted = syncProgress.filesCompleted,
                    totalFiles = syncProgress.totalFiles,
                    bytesTransferred = syncProgress.bytesTransferred,
                    onCancel = { viewModel.cancelSync() }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Sync Controls Card
            if (authState is AuthState.SignedIn && uiState.isConfigured) {
                SyncControlsCard(
                    isSyncing = isSyncing,
                    onSync = { viewModel.sync() },
                    onUpload = { viewModel.uploadOnly() },
                    onDownload = { viewModel.downloadOnly() }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Settings Card
                SettingsCard(
                    syncDirectory = uiState.syncDirectory,
                    lastSyncTime = uiState.lastSyncTime,
                    isPeriodicSyncEnabled = uiState.isPeriodicSyncEnabled,
                    onTogglePeriodicSync = { viewModel.togglePeriodicSync() }
                )
            }
        }
    }
}

@Composable
fun AuthCard(
    authState: AuthState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Authentication",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            when (authState) {
                is AuthState.NotSignedIn -> {
                    Text(text = "Not signed in")
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onSignIn,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sign in with Google")
                    }
                }
                is AuthState.SigningIn -> {
                    Text(text = "Signing in...")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is AuthState.SignedIn -> {
                    Text(text = "Signed in as: ${authState.email}")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onSignOut,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sign out")
                    }
                }
                is AuthState.Error -> {
                    Text(
                        text = "Error: ${authState.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onSignIn,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Try again")
                    }
                }
                is AuthState.PermissionRequired -> {
                    Text(
                        text = "Additional permissions required",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onSignIn,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Grant permissions")
                    }
                }
            }
        }
    }
}

@Composable
fun SyncProgressCard(
    phase: SyncPhase,
    currentFile: String?,
    filesCompleted: Int,
    totalFiles: Int,
    bytesTransferred: Long,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Sync Progress",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(text = "Phase: ${phase.name.replace("_", " ")}")

            if (totalFiles > 0) {
                val progress = filesCompleted.toFloat() / totalFiles.toFloat()
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
                Text(text = "Files: $filesCompleted / $totalFiles")
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }

            currentFile?.let {
                Text(
                    text = "Current: $it",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }

            if (bytesTransferred > 0) {
                Text(
                    text = "Transferred: ${formatBytes(bytesTransferred)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun SyncControlsCard(
    isSyncing: Boolean,
    onSync: () -> Unit,
    onUpload: () -> Unit,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Sync Controls",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onSync,
                enabled = !isSyncing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sync (Bidirectional)")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onUpload,
                    enabled = !isSyncing,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Upload")
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onDownload,
                    enabled = !isSyncing,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Download")
                }
            }
        }
    }
}

@Composable
fun SettingsCard(
    syncDirectory: String,
    lastSyncTime: Long?,
    isPeriodicSyncEnabled: Boolean,
    onTogglePeriodicSync: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Sync Directory:",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = syncDirectory,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Last Sync:",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = lastSyncTime?.let { formatTime(it) } ?: "Never",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Periodic Sync (12h)")
                Switch(
                    checked = isPeriodicSyncEnabled,
                    onCheckedChange = { onTogglePeriodicSync() }
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
