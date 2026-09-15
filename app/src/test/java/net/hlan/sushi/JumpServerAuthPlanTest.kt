package net.hlan.sushi

import com.jcraft.jsch.UserInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests for [SshClient.resolveJumpAuthPlan] — the jump host authenticates on its own preference.
 *
 * Regression cover for a bastion connection that failed with `Auth cancel for methods
 * 'publickey,password'`: the jump session took its methods from the *target's* auth plan, so a
 * key-auth target left `setPassword` unreached on the bastion. JSch then asked its UserInfo,
 * which declines to prompt, and cancelled the only method it had left.
 */
class JumpServerAuthPlanTest {

    private object NoOpUserInfo : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?): Boolean = false
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) {}
    }

    private fun clientFor(config: SshConnectionConfig) = SshClient(
        config,
        NoOpUserInfo,
        File.createTempFile("jump_auth_plan_test_known_hosts", null).apply { deleteOnExit() }
    )

    private fun config(
        authPreference: SshAuthPreference,
        jumpAuthPreference: String?,
        privateKey: String? = PRIVATE_KEY
    ) = SshConnectionConfig(
        host = "ergo",
        port = 22,
        username = "larry",
        password = "target-password",
        authPreference = authPreference.value,
        privateKey = privateKey,
        jumpEnabled = true,
        jumpHostId = "bastion-id",
        jumpHost = "bastion",
        jumpPort = 22,
        jumpUsername = "larry",
        jumpPassword = "bastion-password",
        jumpAuthPreference = jumpAuthPreference
    )

    /**
     * The reported failure: the target is key-only, the bastion wants a password. The jump plan
     * has to keep the password whatever the target prefers, or the bastion is offered nothing.
     */
    @Test
    fun keyAuthTarget_stillOffersThePasswordToAPasswordAuthJumpHost() {
        val config = config(SshAuthPreference.KEY, SshAuthPreference.PASSWORD.value)
        val client = clientFor(config)

        assertFalse(client.resolveAuthPlan(config.resolvedAuthPreference(), true).shouldUsePassword)

        val jumpPlan = client.resolveJumpAuthPlan(config)
        assertTrue(jumpPlan.shouldUsePassword)
        assertFalse(jumpPlan.shouldUseKey)
    }

    /** The mirror case: a password-only target in front of a key-auth bastion. */
    @Test
    fun passwordAuthTarget_stillOffersTheKeyToAKeyAuthJumpHost() {
        val config = config(SshAuthPreference.PASSWORD, SshAuthPreference.KEY.value)

        val jumpPlan = clientFor(config).resolveJumpAuthPlan(config)
        assertTrue(jumpPlan.shouldUseKey)
        assertFalse(jumpPlan.shouldUsePassword)
    }

    /** AUTO offers both, so a bastion that takes either still connects. */
    @Test
    fun autoJumpPreference_offersBothMethods() {
        val config = config(SshAuthPreference.KEY, SshAuthPreference.AUTO.value)

        val jumpPlan = clientFor(config).resolveJumpAuthPlan(config)
        assertTrue(jumpPlan.shouldUsePassword)
        assertTrue(jumpPlan.shouldUseKey)
    }

    /**
     * Hosts saved before `jumpAuthPreference` existed deserialize it as null. Those fall back to
     * AUTO, which offers both methods — so an upgrade cannot make a working bastion stop working.
     */
    @Test
    fun jumpPreferenceMissingFromStoredHost_fallsBackToAuto() {
        val config = config(SshAuthPreference.KEY, jumpAuthPreference = null)

        val jumpPlan = clientFor(config).resolveJumpAuthPlan(config)
        assertEquals(SshAuthPreference.AUTO, config.resolvedJumpAuthPreference())
        assertTrue(jumpPlan.shouldUsePassword)
        assertTrue(jumpPlan.shouldUseKey)
    }

    /** AUTO without a key is password-only — the key half must not be claimed. */
    @Test
    fun autoJumpPreferenceWithoutAKey_isPasswordOnly() {
        val config = config(SshAuthPreference.PASSWORD, SshAuthPreference.AUTO.value, privateKey = null)

        val jumpPlan = clientFor(config).resolveJumpAuthPlan(config)
        assertTrue(jumpPlan.shouldUsePassword)
        assertFalse(jumpPlan.shouldUseKey)
    }

    /** A jump host set to KEY with no key configured is a misconfiguration, not a silent retry. */
    @Test(expected = IllegalStateException::class)
    fun keyJumpPreferenceWithoutAKey_isRejected() {
        val config = config(SshAuthPreference.PASSWORD, SshAuthPreference.KEY.value, privateKey = null)

        clientFor(config).resolveJumpAuthPlan(config)
    }

    /** The target's own plan is unchanged by any of this. */
    @Test
    fun targetPlan_stillFollowsTheTargetPreference() {
        val config = config(SshAuthPreference.KEY, SshAuthPreference.PASSWORD.value)
        val client = clientFor(config)

        val targetPlan = client.resolveAuthPlan(config.resolvedAuthPreference(), true)
        assertTrue(targetPlan.shouldUseKey)
        assertFalse(targetPlan.shouldUsePassword)
    }

    private companion object {
        /** Never parsed — only its blank/non-blank state is read when resolving a plan. */
        const val PRIVATE_KEY = "-----BEGIN OPENSSH PRIVATE KEY-----"
    }
}
