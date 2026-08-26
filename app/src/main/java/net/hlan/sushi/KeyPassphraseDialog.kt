package net.hlan.sushi

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.hlan.sushi.databinding.DialogKeyPassphraseBinding

/**
 * Shared passphrase-entry dialog used both when JSch needs a passphrase to decrypt a private
 * key during connect (see [DialogUserInfo]) and when generating a new key pair in [KeysActivity].
 *
 * @param allowEmpty When true, submitting a blank passphrase resolves [onResult] with a null
 * passphrase — used for "generate this key with no passphrase" as an explicit opt-out. When
 * false (the connect-time case), blank input is rejected inline since an encrypted key cannot
 * be unlocked with nothing.
 */
fun showKeyPassphraseDialog(
    activity: AppCompatActivity,
    targetLabel: String?,
    allowEmpty: Boolean = false,
    onResult: (passphrase: String?, remember: Boolean) -> Unit,
    onCancel: () -> Unit
) {
    val binding = DialogKeyPassphraseBinding.inflate(activity.layoutInflater)
    if (targetLabel != null) {
        binding.keyPassphraseSubtitle.text = targetLabel
    } else {
        binding.keyPassphraseSubtitle.visibility = android.view.View.GONE
    }

    val dialog = MaterialAlertDialogBuilder(activity)
        .setTitle(R.string.key_passphrase_title)
        .setView(binding.root)
        .setPositiveButton(R.string.key_passphrase_confirm, null)
        .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
        .setCancelable(false)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val passphrase = binding.keyPassphraseInput.text?.toString().orEmpty()
            if (passphrase.isEmpty() && !allowEmpty) {
                binding.keyPassphraseLayout.error = activity.getString(R.string.key_passphrase_required_error)
                return@setOnClickListener
            }
            binding.keyPassphraseLayout.error = null
            val remember = binding.keyPassphraseRememberSwitch.isChecked
            dialog.dismiss()
            onResult(passphrase.ifEmpty { null }, remember)
        }
    }
    dialog.show()
}
