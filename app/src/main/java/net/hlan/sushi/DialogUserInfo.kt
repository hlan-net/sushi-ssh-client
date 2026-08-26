package net.hlan.sushi

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UserInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridges JSch's synchronous [UserInfo] callbacks — invoked on the connecting thread, from
 * inside [com.jcraft.jsch.Session.connect] — to a modal dialog shown on the UI thread. Each
 * prompt blocks its caller on a latch that the dialog's button click, a bounded timeout, or the
 * hosting activity being destroyed releases exactly once, so a backgrounded/killed activity
 * can't leave the connecting thread parked forever.
 */
class DialogUserInfo(
    private val activity: AppCompatActivity,
    private val targetLabel: String,
    private val passphraseCache: KeyPassphraseCache
) : UserInfo, DefaultLifecycleObserver {

    @Volatile
    private var pendingLatch: CountDownLatch? = null
    private var lastPassphrase: String? = null

    /**
     * Bound by [SshClient.createConnectedSession] right after [SshKnownHosts.attach], once per
     * `connect()` call — [promptYesNo] reads these to know which host/key triggered the prompt
     * and to fetch the previously-trusted key when [TrackingHostKeyRepository.lastCheckResult]
     * is [HostKeyRepository.CHANGED].
     */
    var jsch: JSch? = null
    var trackingRepo: TrackingHostKeyRepository? = null

    init {
        // Constructed from Dispatchers.IO at every call site (SshClient is built inside
        // lifecycleScope.launch(Dispatchers.IO)) — Lifecycle.addObserver() enforces main-thread
        // only, so it must be dispatched there rather than called directly from init.
        activity.runOnUiThread { activity.lifecycle.addObserver(this) }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        pendingLatch?.countDown()
    }

    override fun promptPassphrase(message: String): Boolean {
        passphraseCache.get()?.let {
            lastPassphrase = it
            return true
        }
        val (passphrase, remember) = awaitOnUiThread<Pair<String?, Boolean>>(default = null to false) { deliver ->
            showKeyPassphraseDialog(
                activity = activity,
                targetLabel = targetLabel,
                onResult = { enteredPassphrase, rememberChoice -> deliver(enteredPassphrase to rememberChoice) },
                onCancel = { deliver(null to false) }
            )
        }
        lastPassphrase = passphrase
        if (passphrase != null && remember) {
            passphraseCache.set(passphrase)
        }
        return passphrase != null
    }

    override fun getPassphrase(): String? = lastPassphrase

    /**
     * Drops a remembered passphrase that turned out not to unlock the key. [promptPassphrase]
     * has to store it before anything can verify it, so without this a single typo with
     * "remember on this device" enabled would be answered from the cache on every later
     * connect, and the dialog would never appear again.
     */
    fun forgetPassphrase() {
        lastPassphrase = null
        passphraseCache.set(null)
    }

    override fun promptYesNo(message: String): Boolean {
        val repo = trackingRepo ?: return false
        val jschInstance = jsch ?: return false
        val host = repo.lastCheckedHost ?: return false
        val keyBytes = repo.lastCheckedKey ?: return false
        val newHostKey = HostKey(host, keyBytes)

        return awaitOnUiThread(default = false) { deliver ->
            if (repo.lastCheckResult == HostKeyRepository.CHANGED) {
                val oldFingerprint = repo.getHostKey(host, newHostKey.type).firstOrNull()
                    ?.getFingerPrint(jschInstance)
                showHostKeyChangedDialog(
                    activity = activity,
                    targetLabel = targetLabel,
                    oldFingerprint = oldFingerprint,
                    newFingerprint = newHostKey.getFingerPrint(jschInstance),
                    onConfirm = { deliver(true) },
                    onCancel = { deliver(false) }
                )
            } else {
                showHostKeyTrustDialog(
                    activity = activity,
                    targetLabel = targetLabel,
                    keyType = newHostKey.type,
                    fingerprint = newHostKey.getFingerPrint(jschInstance),
                    onConfirm = { deliver(true) },
                    onCancel = { deliver(false) }
                )
            }
        }
    }

    /**
     * False, not true: the password comes from [SshConnectionConfig] via
     * `session.setPassword()`, so there is nothing to prompt for and [getPassword] has nothing
     * to return. Claiming otherwise makes `UserAuthPassword` retry with a null password, which
     * JSch turns into `JSchAuthCancelException` ("Auth cancel") — classified as a *key* failure,
     * so a rejected password on a password-only host reports the wrong cause.
     */
    override fun promptPassword(message: String): Boolean = false

    override fun getPassword(): String? = null

    override fun showMessage(message: String) {
        // JSch informational messages (e.g. banner text) — not surfaced to the user today.
    }

    private fun <T> awaitOnUiThread(default: T, show: (deliver: (T) -> Unit) -> Unit): T {
        if (activity.isFinishing || activity.isDestroyed) return default
        val latch = CountDownLatch(1)
        val result = AtomicReference(default)
        pendingLatch = latch
        val deliver: (T) -> Unit = { value ->
            result.set(value)
            latch.countDown()
        }
        activity.runOnUiThread { show(deliver) }
        val completed = latch.await(PROMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        pendingLatch = null
        return if (completed) result.get() else default
    }

    companion object {
        private const val PROMPT_TIMEOUT_MS = 120_000L
    }
}
