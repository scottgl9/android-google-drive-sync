@file:Suppress("DEPRECATION") // GoogleSignIn APIs are deprecated but replacement not yet available

package com.vanespark.googledrivesync.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.vanespark.googledrivesync.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Google Sign-In authentication and Drive API service creation.
 *
 * This class handles the complete authentication lifecycle including:
 * - Sign-in flow initiation
 * - Sign-in result handling
 * - Token management
 * - Sign-out
 * - Drive service creation
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.NotSignedIn)

    /**
     * Observable authentication state
     */
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var authConfig: AuthConfig = AuthConfig()
    private var signInClient: GoogleSignInClient? = null

    /**
     * Configure the authentication options
     */
    fun configure(config: AuthConfig) {
        authConfig = config
        signInClient = null // Force recreation with new config
    }

    /**
     * Get the Google Sign-In client configured for Drive access
     */
    fun getSignInClient(): GoogleSignInClient {
        return signInClient ?: createSignInClient().also { signInClient = it }
    }

    private fun createSignInClient(): GoogleSignInClient {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)

        // Add requested scopes
        authConfig.scopes.forEach { scope ->
            builder.requestScopes(Scope(scope))
        }

        // Configure additional options
        if (authConfig.requestEmail) {
            builder.requestEmail()
        }

        if (authConfig.requestProfile) {
            builder.requestProfile()
        }

        authConfig.requestIdToken?.let { serverClientId ->
            builder.requestIdToken(serverClientId)
        }

        return GoogleSignIn.getClient(context, builder.build())
    }

    /**
     * Get the sign-in intent to launch the Google Sign-In flow
     */
    fun getSignInIntent(): Intent {
        _authState.value = AuthState.SigningIn
        return getSignInClient().signInIntent
    }

    /**
     * Handle the result from the sign-in activity
     *
     * @param data The intent data from onActivityResult
     * @return The authentication result
     */
    suspend fun handleSignInResult(data: Intent?): AuthResult = withContext(Dispatchers.IO) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)

            if (account != null) {
                val result = AuthResult.Success(
                    account = account,
                    email = account.email ?: "",
                    displayName = account.displayName
                )
                _authState.value = AuthState.SignedIn(
                    account = account,
                    email = account.email ?: "",
                    displayName = account.displayName
                )
                Log.d(Constants.TAG, "Sign-in successful: ${account.email}")
                result
            } else {
                val error = AuthResult.Error("Sign-in returned null account")
                _authState.value = AuthState.Error(error.message)
                error
            }
        } catch (e: ApiException) {
            val result = when (e.statusCode) {
                12501 -> { // Sign in cancelled
                    _authState.value = AuthState.NotSignedIn
                    AuthResult.Cancelled
                }
                12502 -> { // Sign in currently in progress
                    AuthResult.Cancelled
                }
                else -> {
                    val error = AuthResult.Error(
                        message = "Sign-in failed: ${e.statusCode} - ${e.message}",
                        cause = e
                    )
                    _authState.value = AuthState.Error(error.message, e)
                    error
                }
            }
            Log.e(Constants.TAG, "Sign-in failed: ${e.statusCode}", e)
            result
        } catch (e: Exception) {
            val error = AuthResult.Error(
                message = "Sign-in failed: ${e.message}",
                cause = e
            )
            _authState.value = AuthState.Error(error.message, e)
            Log.e(Constants.TAG, "Sign-in failed", e)
            error
        }
    }

    /**
     * Check if the user is currently signed in with the required permissions
     */
    suspend fun isSignedIn(): Boolean = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        val hasPermissions = account != null && authConfig.scopes.all { scope ->
            GoogleSignIn.hasPermissions(account, Scope(scope))
        }

        // Update state if needed
        if (hasPermissions && account != null && _authState.value !is AuthState.SignedIn) {
            _authState.value = AuthState.SignedIn(
                account = account,
                email = account.email ?: "",
                displayName = account.displayName
            )
        } else if (!hasPermissions && _authState.value is AuthState.SignedIn) {
            _authState.value = AuthState.NotSignedIn
        }

        hasPermissions
    }

    /**
     * Get the currently signed-in account, if any
     */
    suspend fun getSignedInAccount(): GoogleSignInAccount? = withContext(Dispatchers.IO) {
        GoogleSignIn.getLastSignedInAccount(context)
    }

    /**
     * Sign out the current user
     */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        try {
            getSignInClient().signOut().addOnCompleteListener {
                _authState.value = AuthState.NotSignedIn
            }
            Log.d(Constants.TAG, "Sign-out successful")
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Sign-out failed", e)
            // Still update state to not signed in
            _authState.value = AuthState.NotSignedIn
        }
    }

    /**
     * Revoke access and disconnect the account
     */
    suspend fun revokeAccess() = withContext(Dispatchers.IO) {
        try {
            getSignInClient().revokeAccess().addOnCompleteListener {
                _authState.value = AuthState.NotSignedIn
            }
            Log.d(Constants.TAG, "Access revoked successfully")
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Revoke access failed", e)
            _authState.value = AuthState.NotSignedIn
        }
    }

    /**
     * Create a Google Drive service instance for the signed-in account
     *
     * @param account The signed-in Google account
     * @return Drive service instance, or null if creation failed
     */
    fun createDriveService(account: GoogleSignInAccount): Drive? {
        return try {
            val credential = GoogleAccountCredential.usingOAuth2(
                context,
                authConfig.scopes
            ).apply {
                selectedAccount = account.account
            }

            val httpTransport = NetHttpTransport()

            Drive.Builder(
                httpTransport,
                GsonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName("GoogleDriveSync")
                .setHttpRequestInitializer { request ->
                    credential.initialize(request)
                    request.connectTimeout = Constants.CONNECT_TIMEOUT_MS
                    request.readTimeout = Constants.READ_TIMEOUT_MS
                }
                .build()
        } catch (e: Exception) {
            Log.e(Constants.TAG, "Failed to create Drive service", e)
            null
        }
    }

    /**
     * Get the Drive service for the currently signed-in account
     *
     * @return Drive service instance, or null if not signed in or creation failed
     */
    suspend fun getDriveService(): Drive? = withContext(Dispatchers.IO) {
        val account = getSignedInAccount() ?: return@withContext null
        createDriveService(account)
    }
}
