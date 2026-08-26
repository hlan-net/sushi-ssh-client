package net.hlan.sushi

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.hlan.sushi.databinding.DialogHostKeyChangedBinding
import net.hlan.sushi.databinding.DialogHostKeyTrustBinding

/** First-trust (TOFU) confirmation — shown when a host's key has never been seen before. */
fun showHostKeyTrustDialog(
    activity: AppCompatActivity,
    targetLabel: String,
    keyType: String,
    fingerprint: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val binding = DialogHostKeyTrustBinding.inflate(activity.layoutInflater)
    binding.hostKeyTrustMessage.text = activity.getString(R.string.host_key_trust_message, targetLabel)
    binding.hostKeyTrustFingerprint.text = "$keyType\n$fingerprint"

    MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.host_key_trust_title)
        .setView(binding.root)
        .setPositiveButton(R.string.host_key_trust_confirm) { _, _ -> onConfirm() }
        .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
        .setCancelable(false)
        .show()
}

/**
 * Changed-key warning — shown when a host presents a *different* key than the one Sushi
 * previously trusted for it. Deliberately harder to dismiss than the first-trust dialog: the
 * positive button stays disabled until the user explicitly checks the confirmation box.
 */
fun showHostKeyChangedDialog(
    activity: AppCompatActivity,
    targetLabel: String,
    oldFingerprint: String?,
    newFingerprint: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val binding = DialogHostKeyChangedBinding.inflate(activity.layoutInflater)
    binding.hostKeyChangedWarning.text = activity.getString(R.string.host_key_changed_warning, targetLabel)
    binding.hostKeyChangedOldFingerprint.text =
        oldFingerprint ?: activity.getString(R.string.host_key_changed_old_label)
    binding.hostKeyChangedNewFingerprint.text = newFingerprint

    val dialog = MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.host_key_changed_title)
        .setView(binding.root)
        .setPositiveButton(R.string.host_key_changed_confirm_button, null)
        .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
        .setCancelable(false)
        .create()

    dialog.setOnShowListener {
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton.isEnabled = false
        binding.hostKeyChangedConfirmCheckbox.setOnCheckedChangeListener { _, isChecked ->
            positiveButton.isEnabled = isChecked
        }
        positiveButton.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
    }
    dialog.show()
}
