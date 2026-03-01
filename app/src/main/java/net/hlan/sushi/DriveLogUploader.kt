package net.hlan.sushi

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.http.ByteArrayContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DriveLogUploader(private val context: Context) {
    fun uploadLog(
        account: GoogleSignInAccount,
        logContent: String,
        onResult: (DriveUploadResult) -> Unit
    ) {
        Thread {
            val result = runCatching {
                val driveService = buildDriveService(account)
                val fileMetadata = File().apply {
                    name = buildFileName()
                    mimeType = "text/plain"
                }
                val mediaContent = ByteArrayContent.fromString("text/plain", logContent)
                val file = driveService.files().create(fileMetadata, mediaContent)
                    .setFields("id")
                    .execute()

                DriveUploadResult(true, file.id ?: "")
            }.getOrElse { error ->
                DriveUploadResult(false, error.message ?: "")
            }
            onResult(result)
        }.start()
    }

    private fun buildDriveService(account: GoogleSignInAccount): Drive {
        val selectedAccount = account.account
            ?: Account(account.email ?: throw IllegalStateException("Missing account email"), "com.google")
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE)
        ).apply {
            this.selectedAccount = selectedAccount
        }

        return Drive.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(context.getString(R.string.app_name_short))
            .build()
    }

    private fun buildFileName(): String {
        val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val timestamp = formatter.format(Date())
        return "sushi-console-$timestamp.log"
    }
}

data class DriveUploadResult(
    val success: Boolean,
    val message: String
)
