package net.hlan.sushi

import com.jcraft.jsch.JSchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ConnectFailure] retryability and [SshClient.classifyException] message mapping.
 */
class ConnectionFailureClassificationTest {

    private val client = SshClient(
        SshConnectionConfig(host = "unused", port = 22, username = "u", password = "p")
    )

    // --- isRetryable ---

    @Test fun retryable_network() = assertTrue(ConnectFailure.NETWORK.isRetryable)
    @Test fun retryable_timeout() = assertTrue(ConnectFailure.TIMEOUT.isRetryable)
    @Test fun retryable_unknown() = assertTrue(ConnectFailure.UNKNOWN.isRetryable)
    @Test fun notRetryable_authKey() = assertFalse(ConnectFailure.AUTH_KEY.isRetryable)
    @Test fun notRetryable_authPassword() = assertFalse(ConnectFailure.AUTH_PASSWORD.isRetryable)
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
    fun classify_hostKeyReject() {
        assertEquals(ConnectFailure.HOST_KEY_MISMATCH,
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
}
