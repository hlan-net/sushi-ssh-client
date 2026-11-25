package net.hlan.sushi

import android.app.Activity
import androidx.appcompat.app.AlertDialog

/**
 * "Reboot ekho?" — the one confirmation between tapping *Reboot Host* and `sudo -n reboot`
 * (Figma `node-id=145-75`). Plays do not pass `CommandSafety`, which guards the AI conversation
 * and raw mode, so this is the only check. Cancel and Back both go to [onCancel], which returns
 * to the Play list like Back does for other Plays.
 */
fun showRebootConfirmDialog(
    activity: Activity,
    hostLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.play_reboot_confirm_title, hostLabel))
        .setMessage(R.string.play_reboot_confirm_message)
        .setPositiveButton(R.string.action_reboot) { _, _ -> onConfirm() }
        .setNegativeButton(R.string.phrase_cancel) { _, _ -> onCancel() }
        .setOnCancelListener { onCancel() }
        .show()
}
