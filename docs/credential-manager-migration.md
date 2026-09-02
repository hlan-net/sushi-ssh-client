# Migration Plan: Google Sign-In to Android Credential Manager

## Context & Background

In `com.google.android.gms:play-services-auth:22.0.0`, Google officially removed the legacy `GoogleSignIn` and `GoogleSignInClient` APIs. Our `DriveAuthManager` currently relies on these deprecated classes to:
1. Authenticate the user (Sign in with Google).
2. Request OAuth 2.0 scopes (`DRIVE_FILE` and `SCOPE_GENERATIVE_LANGUAGE`).
3. Retrieve an `android.accounts.Account` object for `GoogleAccountCredential`.

To unblock the `play-services-auth:22.0.0` dependency update, we must migrate to the modern **Android Credential Manager** for authentication and use the updated **Authorization API** for OAuth scopes.

---

## 1. The New Architecture

Google has separated **Authentication** (who the user is) from **Authorization** (what the user can access).

### Authentication: Credential Manager (`androidx.credentials`)
- Replaces `GoogleSignInClient.getSignInIntent()`.
- Provides a unified UI for Passkeys, saved passwords, and Google Sign-In.
- We will use it with `GetGoogleIdOption` to retrieve a `GoogleIdTokenCredential`.
- This credential provides the user's **email address**, which we can use to construct an `android.accounts.Account(email, "com.google")`.

### Authorization: `AuthorizationClient` / `GoogleAccountCredential`
- Replaces requesting scopes via `GoogleSignInOptions`.
- With the `Account` object from the step above, we pass it to `GoogleAccountCredential.usingOAuth2(...)` (which we already use for Gemini) or `AuthorizationClient`.
- If the user hasn't granted the scopes yet, we must catch the `UserRecoverableAuthIOException` (or use `AuthorizationClient.authorize`) to prompt the user for consent.

---

## 2. Step-by-Step Implementation Plan

### Step 1: Update Dependencies
Modify `app/build.gradle.kts`:
```kotlin
// Remove or bump play-services-auth to 22.0.0+
implementation("com.google.android.gms:play-services-auth:22.0.0")

// Add Android Credential Manager libraries
implementation("androidx.credentials:credentials:1.3.0")
implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
```

### Step 2: Refactor `DriveAuthManager.kt`
Currently, `DriveAuthManager` uses `Intent` and `onActivityResult`. Credential Manager works natively with Kotlin Coroutines. We need to refactor it to expose `suspend` functions.

1. **Replace `getSignInIntent()` & `handleSignInResult()`** with a single `suspend fun signIn(context: Context): Account?`.
2. **Implementation of `signIn`**:
   ```kotlin
   suspend fun signIn(context: Context): Account? {
       val credentialManager = CredentialManager.create(context)
       val googleIdOption = GetGoogleIdOption.Builder()
           .setFilterByAuthorizedAccounts(false)
           .setServerClientId(context.getString(R.string.default_web_client_id)) // Requires Web Client ID from Google Cloud Console
           .setAutoSelectEnabled(true)
           .build()

       val request = GetCredentialRequest.Builder()
           .addCredentialOption(googleIdOption)
           .build()

       return try {
           val result = credentialManager.getCredential(context, request)
           val credential = result.credential
           if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
               val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
               // Construct the Android Account object required for GoogleAccountCredential
               Account(googleIdTokenCredential.id, "com.google")
           } else {
               null
           }
       } catch (e: Exception) {
           e.printStackTrace()
           null
       }
   }
   ```
3. **Persist the Account**: Store the authenticated `Account` instance (or just the email string in SharedPreferences) so `getGeminiAccessToken()` knows which account to use without prompting sign-in again.

### Step 3: Handle OAuth Scope Consent
Previously, `GoogleSignInClient` handled scope consent during sign-in. Now, `GoogleAccountCredential.getToken()` will throw a `UserRecoverableAuthException` (or `UserRecoverableAuthIOException`) if the user hasn't granted the `DRIVE_FILE` or Gemini scopes.
- In `SettingsActivity` (or wherever `DriveAuthManager` is used), wrap the token fetch in a `try-catch`.
- If `UserRecoverableAuthException` is caught, launch its `intent` to show the Google consent screen to the user.

### Step 4: Update UI Callers
Update `SettingsActivity.kt` to launch Coroutines (`lifecycleScope.launch`) instead of `registerForActivityResult` for the authentication flow, keeping the UX seamless.

---

## Implementation Summary (Completed in `feat/credential-manager-migration`)

1. **Dependencies**: Upgraded `play-services-auth` to `22.0.0` and added `androidx.credentials:credentials:1.3.0`, `androidx.credentials:credentials-play-services-auth:1.3.0`, and `com.google.android.libraries.identity.googleid:googleid:1.1.1`.
2. **Authorization & Authentication Flow**:
   - Uses `Identity.getAuthorizationClient(activity).authorize(request)` with `DriveScopes.DRIVE_FILE`, `SCOPE_GENERATIVE_LANGUAGE`, `email`, and `profile` scopes.
   - For client-only open-source applications without a backend web client ID, this utilizes Google Play Services' native Android client credentials.
   - User interaction (if required) is handled via `PendingIntent` and `ActivityResultContracts.StartIntentSenderForResult`.
   - On successful authorization, user profile details (email, display name) are fetched via Google's `userinfo` endpoint with the returned access token and persisted in `SecurePrefs`.
   - Sign-out clears `SecurePrefs` and invokes `CredentialManager.clearCredentialState()`.
3. **Decoupled Architecture**:
   - Introduced `DriveAccount(val email: String, val displayName: String? = null)`.
   - `DriveLogUploader` and `DriveAuthManager` interact with `DriveAccount` and standard `android.accounts.Account`, eliminating any dependency on deprecated `GoogleSignInAccount`.
4. **Verification**:
   - JVM unit tests (`testDebugUnitTest`) pass cleanly.
   - `assembleDebug` and `assembleMinifiedDebug` (R8 shrinking) build without errors.
   - Instrumented device QA suite (`DeviceQaSuiteTest`) passed on device.
