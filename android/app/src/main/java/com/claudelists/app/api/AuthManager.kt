package com.claudelists.app.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.claudelists.app.BuildConfig
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "AuthManager"
private const val PREFS_NAME = "court_lists_auth"
private const val KEY_ACCESS_TOKEN = "access_token"
private const val KEY_REFRESH_TOKEN = "refresh_token"
private const val KEY_EXPIRES_AT = "expires_at"
private const val KEY_USER_ID = "user_id"
private const val KEY_USER_EMAIL = "user_email"
private const val KEY_USER_APPROVED = "user_approved"

/**
 * Manages authentication using native Google Sign-In.
 *
 * Uses Google Sign-In SDK for native account picker UI,
 * then exchanges the Google ID token with Supabase for session tokens.
 */
class AuthManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val baseUrl = BuildConfig.API_BASE_URL
    private val anonKey = BuildConfig.API_ANON_KEY
    private val googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID

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

    data class AuthState(
        val isAuthenticated: Boolean = false,
        val isApproved: Boolean = false,
        val userId: String? = null,
        val userEmail: String? = null
    )

    private fun loadAuthState(): AuthState {
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null)
        return if (accessToken != null) {
            AuthState(
                isAuthenticated = true,
                isApproved = prefs.getBoolean(KEY_USER_APPROVED, false),
                userId = prefs.getString(KEY_USER_ID, null),
                userEmail = prefs.getString(KEY_USER_EMAIL, null)
            )
        } else {
            AuthState()
        }
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    /**
     * Check if the current access token is expired or about to expire.
     * Returns true if token expires in less than 60 seconds.
     */
    fun isTokenExpired(): Boolean {
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0)
        if (expiresAt == 0L) return true
        // Consider expired if less than 60 seconds remaining
        return System.currentTimeMillis() > (expiresAt - 60_000)
    }

    /**
     * Get a valid access token, refreshing if necessary.
     * This is a suspending function that may perform network calls.
     */
    suspend fun getValidAccessToken(): String? {
        val currentToken = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null

        if (isTokenExpired()) {
            Log.d(TAG, "Token expired, refreshing...")
            val refreshed = refreshToken()
            if (!refreshed) {
                Log.e(TAG, "Token refresh failed")
                return null
            }
        }

        return prefs.getString(KEY_ACCESS_TOKEN, null)
    }

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
    suspend fun handleSignInResult(data: Intent?): Boolean {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            val idToken = account.idToken

            if (idToken != null) {
                Log.d(TAG, "Got Google ID token, exchanging with Supabase...")
                exchangeGoogleToken(idToken)
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
     * Exchange Google ID token for Supabase session tokens.
     */
    private suspend fun exchangeGoogleToken(idToken: String): Boolean {
        return try {
            val response = httpClient.post("$baseUrl/auth/v1/token?grant_type=id_token") {
                header("apikey", anonKey)
                contentType(ContentType.Application.Json)
                setBody(IdTokenRequest(
                    provider = "google",
                    idToken = idToken
                ))
            }

            if (response.status.isSuccess()) {
                val tokenResponse: TokenResponse = response.body()
                saveTokens(tokenResponse)
                _isLoading.value = false
                true
            } else {
                val errorBody = response.body<String>()
                Log.e(TAG, "Token exchange failed: ${response.status} - $errorBody")
                _error.value = "Authentication failed"
                _isLoading.value = false
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange error", e)
            _error.value = "Authentication failed: ${e.message}"
            _isLoading.value = false
            false
        }
    }

    /**
     * Refresh the access token using refresh token.
     */
    suspend fun refreshToken(): Boolean {
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return false

        return try {
            val response = httpClient.post("$baseUrl/auth/v1/token?grant_type=refresh_token") {
                header("apikey", anonKey)
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(refreshToken = refreshToken))
            }

            val tokenResponse: TokenResponse = response.body()
            saveTokens(tokenResponse)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed", e)
            signOut()
            false
        }
    }

    /**
     * Sign out and clear stored tokens.
     */
    fun signOut() {
        googleSignInClient.signOut()

        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_APPROVED)
            .apply()

        _authState.value = AuthState()
    }

    private fun saveTokens(response: TokenResponse) {
        val userId = response.user?.id
        val email = response.user?.email
        val approved = response.user?.appMetadata?.get("approved")
            ?.jsonPrimitive?.boolean ?: false

        // Calculate expiration time (expiresIn is in seconds)
        val expiresAt = System.currentTimeMillis() + ((response.expiresIn ?: 3600) * 1000L)

        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, response.accessToken)
            .putString(KEY_REFRESH_TOKEN, response.refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_USER_EMAIL, email)
            .putBoolean(KEY_USER_APPROVED, approved)
            .apply()

        _authState.value = AuthState(
            isAuthenticated = true,
            isApproved = approved,
            userId = userId,
            userEmail = email
        )

        Log.i(TAG, "Tokens saved. User: $email, approved: $approved, expires at: $expiresAt")
    }

    fun clearError() {
        _error.value = null
    }

    fun close() {
        httpClient.close()
    }
}

@Serializable
private data class IdTokenRequest(
    val provider: String,
    @SerialName("id_token") val idToken: String
)

@Serializable
private data class RefreshRequest(
    @SerialName("grant_type") val grantType: String = "refresh_token",
    @SerialName("refresh_token") val refreshToken: String
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
    val user: UserInfo? = null
)

@Serializable
private data class UserInfo(
    val id: String,
    val email: String? = null,
    @SerialName("app_metadata") val appMetadata: JsonObject? = null
)
