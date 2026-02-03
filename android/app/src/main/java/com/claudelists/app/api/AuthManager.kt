package com.claudelists.app.api

import android.content.Context
import android.content.Intent
import android.util.Log
import com.claudelists.app.BuildConfig
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.*

private const val TAG = "AuthManager"

/**
 * Manages authentication using native Google Sign-In.
 *
 * Uses Google ID token for Edge Function API authentication.
 * Exchanges Google token with Supabase for realtime subscriptions.
 */
class AuthManager(
    private val context: Context,
    private val httpClient: HttpClient
) {

    private val googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
    private val apiBaseUrl = BuildConfig.API_BASE_URL
    private val apiAnonKey = BuildConfig.API_ANON_KEY

    private val googleSignInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(googleWebClientId)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    // Auth state
    private val _authState = MutableStateFlow(loadAuthState())
    val authState: StateFlow<AuthState> = _authState

    // Loading state for UI
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Error state for UI
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Supabase access token for realtime
    private var supabaseAccessToken: String? = null

    data class AuthState(
        val isAuthenticated: Boolean = false,
        val userId: String? = null,
        val userEmail: String? = null,
        val userName: String? = null
    )

    private fun loadAuthState(): AuthState {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return if (account != null && account.idToken != null) {
            AuthState(
                isAuthenticated = true,
                userId = account.id,
                userEmail = account.email,
                userName = account.displayName
            )
        } else {
            AuthState()
        }
    }

    /**
     * Get the Google ID token for API authentication.
     * Refreshes silently if needed.
     */
    suspend fun getGoogleIdToken(): String? {
        return try {
            // Try silent sign-in to refresh the token
            val account = googleSignInClient.silentSignIn().await()
            account.idToken
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get ID token", e)
            // Fall back to last signed in account
            GoogleSignIn.getLastSignedInAccount(context)?.idToken
        }
    }

    /**
     * Get the Supabase access token for realtime subscriptions.
     * Exchanges Google token if needed.
     */
    suspend fun getValidAccessToken(): String? {
        // Return cached token if available
        supabaseAccessToken?.let { return it }

        // Exchange Google token for Supabase session
        val googleToken = getGoogleIdToken() ?: return null
        return exchangeForSupabaseSession(googleToken)
    }

    /**
     * Exchange Google ID token for Supabase session.
     */
    private suspend fun exchangeForSupabaseSession(googleIdToken: String): String? {
        return try {
            val response = httpClient.post("$apiBaseUrl/auth/v1/token") {
                parameter("grant_type", "id_token")
                contentType(ContentType.Application.Json)
                header("apikey", apiAnonKey)
                setBody(buildJsonObject {
                    put("provider", "google")
                    put("id_token", googleIdToken)
                }.toString())
            }

            if (response.status.isSuccess()) {
                val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val accessToken = json["access_token"]?.jsonPrimitive?.content
                if (accessToken != null) {
                    supabaseAccessToken = accessToken
                    Log.i(TAG, "Supabase session obtained")
                }
                accessToken
            } else {
                Log.e(TAG, "Supabase token exchange failed: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Supabase token exchange error", e)
            null
        }
    }

    fun getUserId(): String? = _authState.value.userId

    /**
     * Get the sign-in intent to launch.
     * Call this and launch the intent with an ActivityResultLauncher.
     */
    fun getSignInIntent(): Intent {
        _isLoading.value = true
        _error.value = null
        return googleSignInClient.signInIntent
    }

    /**
     * Handle the result from the sign-in intent.
     * Call this from the ActivityResultCallback.
     */
    fun handleSignInResult(data: Intent?): Boolean {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)

            if (account.idToken != null) {
                _authState.value = AuthState(
                    isAuthenticated = true,
                    userId = account.id,
                    userEmail = account.email,
                    userName = account.displayName
                )
                Log.i(TAG, "Signed in: ${account.email}")
                _isLoading.value = false
                true
            } else {
                Log.e(TAG, "No ID token received")
                _error.value = "No ID token received"
                _isLoading.value = false
                false
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Google Sign-In failed: ${e.statusCode}", e)
            _error.value = "Sign-in failed: ${e.statusCode}"
            _isLoading.value = false
            false
        }
    }

    /**
     * Sign out and clear state.
     */
    fun signOut() {
        googleSignInClient.signOut()
        supabaseAccessToken = null
        _authState.value = AuthState()
    }

    fun clearError() {
        _error.value = null
    }
}
