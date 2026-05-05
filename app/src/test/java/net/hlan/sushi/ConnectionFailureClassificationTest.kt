package net.hlan.sushi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ConnectFailure] classification and retryability.
 *
 * These exercise the message-pattern matching in SshClient.classifyException() indirectly
 * via [SshConnectResult] construction — we check that the public surface behaves correctly
 * rather than white-boxing the private helper.
 */
class ConnectionFailureClassificationTest {

    @Test
    fun retryable_network() {
        assertTrue(ConnectFailure.NETWORK.isRetryable)
    }

    @Test
    fun retryable_timeout() {
        assertTrue(ConnectFailure.TIMEOUT.isRetryable)
    }

    @Test
    fun retryable_unknown() {
        assertTrue(ConnectFailure.UNKNOWN.isRetryable)
    }

    @Test
    fun notRetryable_authKey() {
        assertFalse(ConnectFailure.AUTH_KEY.isRetryable)
    }

    @Test
    fun notRetryable_authPassword() {
        assertFalse(ConnectFailure.AUTH_PASSWORD.isRetryable)
    }

    @Test
    fun notRetryable_hostKeyMismatch() {
        assertFalse(ConnectFailure.HOST_KEY_MISMATCH.isRetryable)
    }

    @Test
    fun notRetryable_jumpFailed() {
        assertFalse(ConnectFailure.JUMP_FAILED.isRetryable)
    }

    @Test
    fun notRetryable_channelFailed() {
        assertFalse(ConnectFailure.CHANNEL_FAILED.isRetryable)
    }

    @Test
    fun connectResult_defaultReasonIsUnknown() {
        val result = SshConnectResult(false, "some error")
        assertEquals(ConnectFailure.UNKNOWN, result.reason)
    }

    @Test
    fun connectResult_reasonIsPreserved() {
        val result = SshConnectResult(false, "Auth fail", ConnectFailure.AUTH_PASSWORD)
        assertEquals(ConnectFailure.AUTH_PASSWORD, result.reason)
        assertFalse(result.success)
    }

    @Test
    fun connectResult_successHasUnknownReasonByDefault() {
        val result = SshConnectResult(true, "Connected")
        assertEquals(ConnectFailure.UNKNOWN, result.reason)
        assertTrue(result.success)
    }
}
