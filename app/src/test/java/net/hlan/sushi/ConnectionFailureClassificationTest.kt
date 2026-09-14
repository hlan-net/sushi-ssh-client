package net.hlan.sushi

import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.JSchRevokedHostKeyException
import com.jcraft.jsch.JSchUnknownHostKeyException
import com.jcraft.jsch.UserInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for [ConnectFailure] retryability and [SshClient.classifyException] message mapping.
 */
class ConnectionFailureClassificationTest {

    private object NoOpUserInfo : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?): Boolean = false
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) {}
    }

    private val client = SshClient(
        SshConnectionConfig(host = "unused", port = 22, username = "u", password = "p"),
        NoOpUserInfo,
        File.createTempFile("classification_test_known_hosts", null).apply { deleteOnExit() }
    )

    /**
     * The typed JSch host-key exceptions have package-private constructors (can't `new` them
     * from `net.hlan.sushi`), but they're public classes meant to be caught/instanceof-checked.
     * Reflection + setAccessible is the standard way to construct one for a test.
     */
    private fun <T : Throwable> newHostKeyException(clazz: Class<T>, message: String): T {
        val constructor = clazz.getDeclaredConstructor(String::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(message)
    }

    // --- auth failures are classified from what was attempted, not from JSch's message ---

    /**
     * JSch reports `Auth fail for methods 'publickey,password'` — the list is what the *server*
     * offers, not what failed, so it always contains "publickey" on a normal sshd. Reading the
     * cause out of that string tells a password-only user their key was refused.
     */
    @Test
    fun authFailure_onPasswordOnlyConnection_isAPasswordFailure() {
        val reason = client.classifyException(
            JSchException("Auth fail for methods 'publickey,password'"),
            SshClient.AuthPlan(shouldUseKey = false, shouldUsePassword = true)
        )
        assertEquals(ConnectFailure.AUTH_PASSWORD, reason)
    }

    @Test
    fun authFailure_onKeyOnlyConnection_isAKeyFailure() {
        val reason = client.classifyException(
            JSchException("Auth fail for methods 'publickey,password'"),
            SshClient.AuthPlan(shouldUseKey = true, shouldUsePassword = false)
        )
        assertEquals(ConnectFailure.AUTH_KEY, reason)
    }

    /** With both methods in play there is nothing better than the message to go on. */
    @Test
    fun authFailure_withBothMethods_fallsBackToTheMessage() {
        val reason = client.classifyException(
            JSchException("Auth fail for methods 'publickey,password'"),
            SshClient.AuthPlan(shouldUseKey = true, shouldUsePassword = true)
        )
        assertEquals(ConnectFailure.AUTH_KEY, reason)
    }

    /** Callers that pass no plan keep the previous message-only behaviour. */
    @Test
    fun authFailure_withoutAPlan_keepsTheMessageHeuristic() {
        val reason = client.classifyException(JSchException("Auth fail for methods 'password'"))
        assertEquals(ConnectFailure.AUTH_PASSWORD, reason)
    }

    // --- isRetryable ---

    @Test fun retryable_network() = assertTrue(ConnectFailure.NETWORK.isRetryable)
    @Test fun retryable_timeout() = assertTrue(ConnectFailure.TIMEOUT.isRetryable)
    @Test fun retryable_unknown() = assertTrue(ConnectFailure.UNKNOWN.isRetryable)
    @Test fun retryable_authKeyPassphrase() = assertTrue(ConnectFailure.AUTH_KEY_PASSPHRASE.isRetryable)
    @Test fun notRetryable_authKey() = assertFalse(ConnectFailure.AUTH_KEY.isRetryable)
    @Test fun notRetryable_authPassword() = assertFalse(ConnectFailure.AUTH_PASSWORD.isRetryable)
    /**
     * Declining a host key is a deliberate refusal, not a transient error. Marking it retryable
     * makes TerminalActivity reconnect after CONNECT_RETRY_DELAY_MS and re-show the identical
     * trust dialog the user just cancelled. The 120s prompt timeout returns the same code, so it
     * re-prompts there too.
     */
    @Test fun notRetryable_hostKeyUntrusted() = assertFalse(ConnectFailure.HOST_KEY_UNTRUSTED.isRetryable)
    @Test fun notRetryable_hostKeyMismatch() = assertFalse(ConnectFailure.HOST_KEY_MISMATCH.isRetryable)
    @Test fun notRetryable_jumpFailed() = assertFalse(ConnectFailure.JUMP_FAILED.isRetryable)
    @Test fun notRetryable_channelFailed() = assertFalse(ConnectFailure.CHANNEL_FAILED.isRetryable)

    // --- SshConnectResult defaults ---

    @Test
    fun connectResult_defaultReasonIsUnknown() {
        assertEquals(ConnectFailure.UNKNOWN, SshConnectResult(false, "error").reason)
    }

    @Test
    fun connectResult_reasonPreserved() {
        val r = SshConnectResult(false, "Auth fail", ConnectFailure.AUTH_PASSWORD)
        assertEquals(ConnectFailure.AUTH_PASSWORD, r.reason)
    }

    // --- classifyException message patterns ---



    @Test
    fun classify_typedChangedHostKeyException() {
        val e = newHostKeyException(JSchChangedHostKeyException::class.java, "HostKey has been changed: ergo.local")
        assertEquals(ConnectFailure.HOST_KEY_MISMATCH, client.classifyException(e))
    }

    @Test
    fun classify_typedRevokedHostKeyException() {
        val e = newHostKeyException(JSchRevokedHostKeyException::class.java, "revoked HostKey: ergo.local")
        assertEquals(ConnectFailure.HOST_KEY_MISMATCH, client.classifyException(e))
    }

    @Test
    fun classify_typedUnknownHostKeyException() {
        val e = newHostKeyException(JSchUnknownHostKeyException::class.java, "reject HostKey: ergo.local")
        assertEquals(ConnectFailure.HOST_KEY_UNTRUSTED, client.classifyException(e))
    }

    @Test
    fun classify_hostKeyChangedMessageFallback() {
        // Defensive fallback for a non-typed exception carrying JSch's "changed" wording.
        assertEquals(ConnectFailure.HOST_KEY_MISMATCH,
            client.classifyException(JSchException("HostKey has been changed: ergo.local")))
    }

    @Test
    fun classify_hostKeyReject() {
        // "reject HostKey:" is the message JSch itself uses when the user declines to trust an
        // unknown key (JSchUnknownHostKeyException) — declined trust, not a changed/mismatched key.
        assertEquals(ConnectFailure.HOST_KEY_UNTRUSTED,
            client.classifyException(JSchException("reject HostKey: ssh-rsa")))
    }

    @Test
    fun classify_authFailPublicKey() {
        assertEquals(ConnectFailure.AUTH_KEY,
            client.classifyException(JSchException("Auth fail: publickey")))
    }

    @Test
    fun classify_authFailPassword() {
        assertEquals(ConnectFailure.AUTH_PASSWORD,
            client.classifyException(JSchException("Auth fail")))
    }

    @Test
    fun classify_authCancel() {
        assertEquals(ConnectFailure.AUTH_KEY,
            client.classifyException(JSchException("auth cancel")))
    }

    @Test
    fun classify_timeoutMessage() {
        assertEquals(ConnectFailure.TIMEOUT,
            client.classifyException(JSchException("Connection timed out")))
    }

    @Test
    fun classify_socketTimeoutAsCause() {
        val e = JSchException("connect failed")
        e.initCause(java.net.SocketTimeoutException("timeout"))
        assertEquals(ConnectFailure.TIMEOUT, client.classifyException(e))
    }

    @Test
    fun classify_socketTimeoutDirectly() {
        assertEquals(ConnectFailure.TIMEOUT,
            client.classifyException(java.net.SocketTimeoutException("read timed out")))
    }

    @Test
    fun classify_connectionRefused() {
        assertEquals(ConnectFailure.NETWORK,
            client.classifyException(JSchException("Connection refused (ECONNREFUSED)")))
    }

    @Test
    fun classify_unknownHost() {
        assertEquals(ConnectFailure.NETWORK,
            client.classifyException(JSchException("UnknownHostException: ergo.local")))
    }

    @Test
    fun classify_connectExceptionAsCause() {
        val e = JSchException("connect failed")
        e.initCause(java.net.ConnectException("Connection refused"))
        assertEquals(ConnectFailure.NETWORK, client.classifyException(e))
    }

    @Test
    fun classify_shellChannelFailed() {
        assertEquals(ConnectFailure.CHANNEL_FAILED,
            client.classifyException(IllegalStateException("Unable to open shell channel")))
    }

    @Test
    fun classify_channelNotOpened() {
        assertEquals(ConnectFailure.CHANNEL_FAILED,
            client.classifyException(JSchException("channel is not opened")))
    }

    @Test
    fun classify_unknownFallback() {
        assertEquals(ConnectFailure.UNKNOWN,
            client.classifyException(RuntimeException("something unexpected")))
    }

    // --- the bastion leg is classified and planned on its own terms ---

    /**
     * The reported symptom: a key-only target dropped the bastion's stored password, JSch ran
     * out of methods and cancelled, and the banner blamed "bastion host settings" instead of the
     * credentials.
     */
    @Test
    fun jumpFailure_authCancel_isAJumpAuthFailure() {
        assertEquals(
            ConnectFailure.JUMP_AUTH_FAILED,
            client.classifyJumpFailure(
                JSchException("Auth cancel for methods 'publickey,password'"),
                SshClient.AuthPlan(shouldUseKey = true, shouldUsePassword = false)
            )
        )
    }

    @Test
    fun jumpFailure_authFail_onPasswordOnlyBastion_isAJumpAuthFailure() {
        assertEquals(
            ConnectFailure.JUMP_AUTH_FAILED,
            client.classifyJumpFailure(
                JSchException("Auth fail for methods 'publickey,password'"),
                SshClient.AuthPlan(shouldUseKey = false, shouldUsePassword = true)
            )
        )
    }

    @Test
    fun jumpFailure_unreachableBastion_staysAJumpFailure() {
        assertEquals(
            ConnectFailure.JUMP_FAILED,
            client.classifyJumpFailure(
                JSchException("java.net.ConnectException: Connection refused"),
                SshClient.AuthPlan(shouldUseKey = true, shouldUsePassword = true)
            )
        )
    }

    @Test
    fun jumpFailure_timeout_staysAJumpFailure() {
        assertEquals(
            ConnectFailure.JUMP_FAILED,
            client.classifyJumpFailure(
                JSchException("session is down: timeout"),
                SshClient.AuthPlan(shouldUseKey = true, shouldUsePassword = true)
            )
        )
    }

    @Test fun notRetryable_jumpAuthFailed() = assertFalse(ConnectFailure.JUMP_AUTH_FAILED.isRetryable)

    // --- the jump leg's auth plan comes from the jump host's preference, not the target's ---

    private fun configWithJump(
        targetPreference: SshAuthPreference,
        jumpPreference: SshAuthPreference?,
        privateKey: String? = "-----BEGIN KEY-----"
    ) = SshConnectionConfig(
        host = "ergo", port = 22, username = "larry", password = "pw",
        authPreference = targetPreference.value,
        privateKey = privateKey,
        jumpEnabled = true,
        jumpHost = "bastion", jumpUsername = "larry", jumpPassword = "jumppw",
        jumpAuthPreference = jumpPreference?.value
    )

    /**
     * The bug itself: with the target key-only, the bastion's own password preference has to
     * survive, or its stored password is never handed to JSch.
     */
    @Test
    fun jumpAuthPlan_passwordBastion_behindKeyOnlyTarget_keepsPassword() {
        val plan = client.resolveJumpAuthPlan(
            configWithJump(SshAuthPreference.KEY, SshAuthPreference.PASSWORD)
        )
        assertTrue(plan.shouldUsePassword)
        assertFalse(plan.shouldUseKey)
    }

    @Test
    fun jumpAuthPlan_keyBastion_behindPasswordOnlyTarget_keepsKey() {
        val plan = client.resolveJumpAuthPlan(
            configWithJump(SshAuthPreference.PASSWORD, SshAuthPreference.KEY)
        )
        assertTrue(plan.shouldUseKey)
        assertFalse(plan.shouldUsePassword)
    }

    /** A hand-typed jump server has no saved entry to copy a preference from. */
    @Test
    fun jumpAuthPlan_withoutPreference_fallsBackToAuto() {
        val plan = client.resolveJumpAuthPlan(configWithJump(SshAuthPreference.KEY, null))
        assertTrue(plan.shouldUseKey)
        assertTrue(plan.shouldUsePassword)
    }

    @Test
    fun jumpAuthPlan_autoWithoutKey_usesPasswordOnly() {
        val plan = client.resolveJumpAuthPlan(
            configWithJump(SshAuthPreference.PASSWORD, SshAuthPreference.AUTO, privateKey = null)
        )
        assertFalse(plan.shouldUseKey)
        assertTrue(plan.shouldUsePassword)
    }

    /** Key-only bastion with no key configured is a settings error, not a connection attempt. */
    @Test(expected = IllegalStateException::class)
    fun jumpAuthPlan_keyBastionWithoutKey_fails() {
        client.resolveJumpAuthPlan(
            configWithJump(SshAuthPreference.PASSWORD, SshAuthPreference.KEY, privateKey = null)
        )
    }
}
