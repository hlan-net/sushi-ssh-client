package net.hlan.sushi

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes

class DriveAuthManager(context: Context) {
    private val appContext = context.applicationContext

    private val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(
            Scope(DriveScopes.DRIVE_FILE),
            Scope(SCOPE_GENERATIVE_LANGUAGE)
        )
        .build()

    private val signInClient = GoogleSignIn.getClient(appContext, signInOptions)

    fun getSignInIntent(): Intent = signInClient.signInIntent

    fun getSignedInAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(appContext)

    fun handleSignInResult(data: Intent?): GoogleSignInAccount? {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        return if (task.isSuccessful) task.result else null
    }

    fun signOut(onComplete: () -> Unit) {
        signInClient.signOut().addOnCompleteListener { onComplete() }
    }

    /**
     * Returns an OAuth2 access token for the signed-in account.
     * Must be called on a background thread. Returns null if no account is signed in
     * or token retrieval fails.
     */
    fun getAccessToken(): String? {
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
        const val SCOPE_GENERATIVE_LANGUAGE =
            "https://www.googleapis.com/auth/generative-language.retriever"
    }
}
