package net.hlan.sushi

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DriveAccount(
    val email: String,
    val displayName: String? = null
) {
    val account: Account get() = Account(email, "com.google")
}

class DriveAuthManager(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getSignedInAccount(): DriveAccount? {
        return runCatching {
            val prefs = SecurePrefs.get(appContext)
            val email = prefs.getString(KEY_ACCOUNT_EMAIL, null) ?: return null
            val name = prefs.getString(KEY_ACCOUNT_DISPLAY_NAME, null)
            DriveAccount(email, name)
        }.getOrNull()
    }

    fun requestAuthorization(
        activity: Activity,
        onResolutionRequired: (PendingIntent) -> Unit,
        onSuccess: (DriveAccount) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val requestedScopes = listOf(
            Scope("email"),
            Scope("profile"),
            Scope(DriveScopes.DRIVE_FILE),
            Scope(SCOPE_GENERATIVE_LANGUAGE)
        )
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)
            .build()

        Identity.getAuthorizationClient(activity)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent != null) {
                        mainHandler.post { onResolutionRequired(pendingIntent) }
                    } else {
                        mainHandler.post {
                            onFailure(IllegalStateException("Resolution required but pending intent is null"))
                        }
                    }
                } else {
                    fetchUserInfoAndSave(result.accessToken, onSuccess, onFailure)
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Authorization request failed", error)
                mainHandler.post { onFailure(error) }
            }
    }

    fun handleAuthorizationResult(
        activity: Activity,
        resultData: Intent?,
        onSuccess: (DriveAccount) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        try {
            val result = Identity.getAuthorizationClient(activity)
                .getAuthorizationResultFromIntent(resultData)
            fetchUserInfoAndSave(result.accessToken, onSuccess, onFailure)
        } catch (e: Exception) {
            Log.w(TAG, "Handling authorization result failed", e)
            mainHandler.post { onFailure(e) }
        }
    }

    private fun fetchUserInfoAndSave(
        accessToken: String?,
        onSuccess: (DriveAccount) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (accessToken.isNullOrBlank()) {
            mainHandler.post { onFailure(IllegalStateException("Missing access token")) }
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                val url = URL(USER_INFO_URL)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }
                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    val email = json.optString("email")
                    val name = json.optString("name").ifBlank { null }
                    if (email.isNotBlank()) {
                        val account = DriveAccount(email, name)
                        saveAccount(account)
                        account
                    } else {
                        throw IllegalStateException("No email in userinfo response")
                    }
                } else {
                    throw IllegalStateException("Userinfo request failed: HTTP $responseCode")
                }
            }
            mainHandler.post {
                result.fold(
                    onSuccess = { onSuccess(it) },
                    onFailure = { onFailure(it as? Exception ?: Exception(it)) }
                )
            }
        }
    }

    private fun saveAccount(account: DriveAccount) {
        runCatching {
            SecurePrefs.get(appContext).edit {
                putString(KEY_ACCOUNT_EMAIL, account.email)
                putString(KEY_ACCOUNT_DISPLAY_NAME, account.displayName)
            }
        }
    }

    private fun clearSavedAccount() {
        runCatching {
            SecurePrefs.get(appContext).edit {
                remove(KEY_ACCOUNT_EMAIL)
                remove(KEY_ACCOUNT_DISPLAY_NAME)
            }
        }
    }

    fun signOut(onComplete: () -> Unit) {
        clearSavedAccount()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val credentialManager = CredentialManager.create(appContext)
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            }
            mainHandler.post { onComplete() }
        }
    }

    /**
     * Returns an OAuth2 access token scoped to the Gemini generative-language API.
     * Used by [GeminiClient] for bearer-token auth when the user is signed in with Google.
     * Must be called on a background thread. Returns null if no account is signed in
     * or token retrieval fails.
     */
    fun getGeminiAccessToken(): String? {
        val account = getSignedInAccount() ?: return null
        return runCatching {
            val credential = GoogleAccountCredential.usingOAuth2(
                appContext,
                listOf(SCOPE_GENERATIVE_LANGUAGE)
            )
            credential.selectedAccount = account.account
            credential.token
        }.getOrNull()
    }

    companion object {
        private const val TAG = "DriveAuthManager"
        private const val KEY_ACCOUNT_EMAIL = "drive_account_email"
        private const val KEY_ACCOUNT_DISPLAY_NAME = "drive_account_display_name"
        private const val USER_INFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"

        const val SCOPE_GENERATIVE_LANGUAGE =
            "https://www.googleapis.com/auth/generative-language.retriever"
    }
}
