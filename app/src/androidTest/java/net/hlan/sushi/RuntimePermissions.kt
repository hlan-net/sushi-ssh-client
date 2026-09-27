package net.hlan.sushi

import android.Manifest
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Grants the app's runtime permissions before the first activity launch.
 *
 * `TerminalActivity` asks for `POST_NOTIFICATIONS` as soon as it starts on Android 13+
 * (`TerminalActivity.requestNotificationPermissionIfNeeded`). The system dialog takes focus, the
 * activity under test drops straight from RESUMED to PAUSED, and every wait for the session to
 * reach "connected" then times out against an activity that is not in the foreground. The failure
 * reads as a broken SSH connection and is nothing of the sort.
 *
 * Granting up front keeps the dialog from appearing at all. The grant does not survive the
 * reinstall that precedes each run, so it has to happen inside the test, not from a shell script.
 */
object RuntimePermissions {

    private const val DRAIN_BUFFER_BYTES = 1024

    private val PERMISSIONS = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    fun grantAll() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        for (permission in PERMISSIONS) {
            if (permission == Manifest.permission.POST_NOTIFICATIONS &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            ) {
                continue
            }
            // executeShellCommand is asynchronous and closing the descriptor does not wait for it,
            // so the output is read to EOF: the grant must be in place before the activity starts.
            // Read with a plain loop rather than readBytes()/use(): the test APK resolves Kotlin
            // stdlib against the minified app APK, where R8 strips whatever the app itself never
            // calls, and kotlin.io.ByteStreamsKt is one of those.
            val descriptor = instrumentation.uiAutomation
                .executeShellCommand("pm grant $packageName $permission")
            val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            try {
                val buffer = ByteArray(DRAIN_BUFFER_BYTES)
                var read = stream.read(buffer)
                while (read >= 0) {
                    read = stream.read(buffer)
                }
            } finally {
                stream.close()
            }
        }
    }
}
