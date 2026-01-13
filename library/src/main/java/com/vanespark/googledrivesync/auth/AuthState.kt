package com.vanespark.googledrivesync.auth

import com.google.android.gms.auth.api.signin.GoogleSignInAccount

/**
 * Represents the current authentication state for Google Drive
 */
sealed class AuthState {
    /**
     * User is not signed in
     */
    object NotSignedIn : AuthState()

    /**
     * Sign-in is in progress
     */
    object SigningIn : AuthState()

    /**
     * User is signed in with valid credentials
     */
    data class SignedIn(
        val account: GoogleSignInAccount,
        val email: String,
        val displayName: String?
    ) : AuthState()

    /**
     * Sign-in failed with an error
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : AuthState()

    /**
     * User needs to grant additional permissions
     */
    object PermissionRequired : AuthState()
}

/**
 * Result of an authentication operation
 */
sealed class AuthResult {
    /**
     * Authentication succeeded
     */
    data class Success(
        val account: GoogleSignInAccount,
        val email: String,
        val displayName: String?
    ) : AuthResult()

    /**
     * User cancelled the sign-in flow
     */
    object Cancelled : AuthResult()

    /**
     * Authentication failed with an error
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : AuthResult()

    /**
     * Additional permissions are required
     */
    object PermissionRequired : AuthResult()
}

/**
 * Configuration for Google Sign-In
 */
data class AuthConfig(
    /**
     * Google Drive API scopes to request
     */
    val scopes: List<String> = listOf(
        "https://www.googleapis.com/auth/drive.file"
    ),

    /**
     * Request email address
     */
    val requestEmail: Boolean = true,

    /**
     * Request user profile information
     */
    val requestProfile: Boolean = false,

    /**
     * Request ID token for server-side verification
     */
    val requestIdToken: String? = null,

    /**
     * Force account selection even if only one account
     */
    val forceAccountSelection: Boolean = false
)
