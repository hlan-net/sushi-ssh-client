package net.hlan.sushi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlayRunner.carriesSecretValues] — the check that keeps a Play's rendered
 * command out of the command history when it has a secret substituted into it.
 */
class PlayRunnerSecretsTest {

    private val username = PlayParameter("username", "Username")
    private val password = PlayParameter("password", "Password", secret = true)

    @Test
    fun secretWithValue_carriesSecret() {
        assertTrue(
            PlayRunner.carriesSecretValues(
                listOf(username, password),
                mapOf("username" to "bob", "password" to "hunter2")
            )
        )
    }

    @Test
    fun secretLeftBlank_doesNotCarrySecret() {
        assertFalse(
            PlayRunner.carriesSecretValues(
                listOf(username, password),
                mapOf("username" to "bob", "password" to "")
            )
        )
    }

    @Test
    fun secretMissingFromValues_doesNotCarrySecret() {
        assertFalse(
            PlayRunner.carriesSecretValues(listOf(username, password), mapOf("username" to "bob"))
        )
    }

    @Test
    fun noSecretParameters_doesNotCarrySecret() {
        assertFalse(
            PlayRunner.carriesSecretValues(listOf(username), mapOf("username" to "bob"))
        )
    }

    @Test
    fun secretFromDefaultValue_carriesSecret() {
        // PlayRunner resolves a missing value to the parameter default before this check.
        val token = PlayParameter("token", "Token", secret = true, default = "abc123")
        assertTrue(
            PlayRunner.carriesSecretValues(listOf(token), mapOf("token" to "abc123"))
        )
    }
}
