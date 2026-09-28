package net.hlan.sushi

import com.jcraft.jsch.UserInfo
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.util.buffer.Buffer
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.AbstractUserAuth
import org.apache.sshd.server.auth.UserAuth
import org.apache.sshd.server.auth.UserAuthFactory
import org.apache.sshd.server.auth.keyboard.InteractiveChallenge
import org.apache.sshd.server.auth.keyboard.KeyboardInteractiveAuthenticator
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SshClient] against a real SSH server that offers `keyboard-interactive`, so the pieces the
 * unit tests of [KeyboardInteractiveUserInfo.respond] cannot reach are covered: JSch's own
 * answer to a `Password:` prompt, the wrapper actually being installed by `configureSession`,
 * and the order the methods are tried in.
 */
class SshClientKeyboardInteractiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: SshServer

    /** Every response set the server received, in order, across all exchanges. */
    private val responsesReceived = CopyOnWriteArrayList<List<String>>()
    private val challengesIssued = AtomicInteger()
    private val passwordAttempts = AtomicInteger()

    @Before
    fun setUp() {
        server = SshServer.setUpDefaultServer().apply {
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(tmp.newFile("host.ser").toPath()).apply {
                algorithm = "RSA"
            }
            shellFactory = { _ -> IdleShell() }
        }
    }

    @After
    fun tearDown() {
        server.stop(true)
    }

    private fun offerOnlyKeyboardInteractive(prompt: String) {
        server.userAuthFactories = listOf(UserAuthKeyboardInteractiveFactory.INSTANCE)
        server.keyboardInteractiveAuthenticator = object : KeyboardInteractiveAuthenticator {
            override fun generateChallenge(
                session: ServerSession?,
                username: String?,
                lang: String?,
                subMethods: String?
            ): InteractiveChallenge {
                challengesIssued.incrementAndGet()
                return InteractiveChallenge().apply {
                    interactionName = ""
                    interactionInstruction = ""
                    addPrompt(prompt, false)
                }
            }

            override fun authenticate(
                session: ServerSession?,
                username: String?,
                responses: MutableList<String>?
            ): Boolean {
                val answers = responses.orEmpty().toList()
                responsesReceived += answers
                return username == USER && answers == listOf(PASSWORD)
            }
        }
    }

    private fun start() {
        server.start()
    }

    private fun connect(password: String): SshConnectResult {
        val client = SshClient(
            SshConnectionConfig(
                host = "127.0.0.1",
                port = server.port,
                username = USER,
                password = password,
                authPreference = SshAuthPreference.PASSWORD.value
            ),
            TrustingUserInfo(),
            tmp.newFile("known_hosts")
        )
        return try {
            client.connect(onLine = {}, streamMode = true, onConnectionClosed = null)
        } finally {
            client.disconnect()
        }
    }

    /** OpenSSH/PAM's usual prompt. JSch answers it itself once the UserInfo is interactive. */
    @Test
    fun keyboardInteractiveOnlyServer_withPamPasswordPrompt_connects() {
        offerOnlyKeyboardInteractive("Password: ")
        start()

        val result = connect(PASSWORD)

        assertTrue(result.message, result.success)
        assertEquals(listOf(listOf(PASSWORD)), responsesReceived)
    }

    /** A prompt JSch does not recognise; the wrapper answers it. */
    @Test
    fun keyboardInteractiveOnlyServer_withOtherPasswordPrompt_connects() {
        offerOnlyKeyboardInteractive("Password for $USER: ")
        start()

        val result = connect(PASSWORD)

        assertTrue(result.message, result.success)
        assertEquals(listOf(listOf(PASSWORD)), responsesReceived)
    }

    /**
     * A rejected password is not sent again: the server sees it once, and the next challenge is
     * declined instead of spending more of its `MaxAuthTries`.
     */
    @Test
    fun wrongPassword_isSentOnlyOnce() {
        offerOnlyKeyboardInteractive("Password for $USER: ")
        start()

        val result = connect("wrong")

        assertFalse(result.success)
        assertEquals(ConnectFailure.AUTH_PASSWORD, result.reason)
        assertEquals(1, responsesReceived.count { it == listOf("wrong") })
    }

    /** The same through JSch's own answer to `Password:`, which the wrapper never sees. */
    @Test
    fun wrongPassword_withPamPasswordPrompt_isSentOnlyOnce() {
        offerOnlyKeyboardInteractive("Password: ")
        start()

        val result = connect("wrong")

        assertFalse(result.success)
        assertEquals(ConnectFailure.AUTH_PASSWORD, result.reason)
        assertEquals(1, responsesReceived.count { it == listOf("wrong") })
    }

    /** A one-time code is never answered with the password. */
    @Test
    fun oneTimeCodePrompt_isNotAnsweredWithThePassword() {
        offerOnlyKeyboardInteractive("Verification code: ")
        start()

        val result = connect(PASSWORD)

        assertFalse(result.success)
        assertTrue(
            "the password reached a one-time-code prompt: $responsesReceived",
            responsesReceived.none { PASSWORD in it }
        )
    }

    /** A server that accepts `password` is authenticated by it, exactly as before this change. */
    @Test
    fun serverOfferingBoth_authenticatesWithPasswordFirst() {
        offerOnlyKeyboardInteractive("Password: ")
        server.userAuthFactories = listOf(
            UserAuthPasswordFactory.INSTANCE,
            UserAuthKeyboardInteractiveFactory.INSTANCE
        )
        server.passwordAuthenticator = { username, password, _ ->
            passwordAttempts.incrementAndGet()
            username == USER && password == PASSWORD
        }
        start()

        val result = connect(PASSWORD)

        assertTrue(result.message, result.success)
        assertEquals(1, passwordAttempts.get())
        assertEquals(0, challengesIssued.get())
    }

    /**
     * A hostile server claiming a hundred million prompts in a packet that holds none. JSch's
     * own class allocates `String[num]` and `boolean[num]` from that before reading anything —
     * about half a gigabyte on the JVM, enough to take an Android app down. The bounded copy
     * rejects the count first, so the connection fails with that reason instead.
     */
    @Test
    fun hostilePromptCount_isRejectedBeforeAllocating() {
        server.userAuthFactories = listOf(HostilePromptCountFactory(100_000_000))
        start()

        val result = connect(PASSWORD)

        assertFalse(result.success)
        assertTrue(result.message, "invalid prompt count 100000000" in result.message)
    }

    /** A negative count, which JSch's class would turn into NegativeArraySizeException. */
    @Test
    fun negativePromptCount_isRejected() {
        server.userAuthFactories = listOf(HostilePromptCountFactory(-1))
        start()

        val result = connect(PASSWORD)

        assertFalse(result.success)
        assertTrue(result.message, "invalid prompt count -1" in result.message)
    }

    /** Sends one `SSH_MSG_USERAUTH_INFO_REQUEST` whose `num-prompts` is [count], and no prompts. */
    private class HostilePromptCountFactory(private val count: Int) : UserAuthFactory {
        override fun getName(): String = "keyboard-interactive"

        override fun createUserAuth(session: ServerSession?): UserAuth =
            object : AbstractUserAuth(name) {
                override fun doAuth(buffer: Buffer?, init: Boolean): Boolean? {
                    if (!init) return false
                    val request = serverSession.createBuffer(SshConstants.SSH_MSG_USERAUTH_INFO_REQUEST)
                    request.putString("")
                    request.putString("")
                    request.putString("")
                    request.putInt(count.toLong())
                    serverSession.writePacket(request)
                    return null
                }
            }
    }

    /** Accepts the host key; nothing else here prompts. */
    private class TrustingUserInfo : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = null
        override fun promptPassword(message: String?): Boolean = false
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = true
        override fun showMessage(message: String?) = Unit
    }

    /** A shell that stays open and does nothing, so `connect()` can open its channel. */
    private class IdleShell : Command {
        override fun setInputStream(`in`: InputStream?) = Unit
        override fun setOutputStream(out: OutputStream?) = Unit
        override fun setErrorStream(err: OutputStream?) = Unit
        override fun setExitCallback(callback: ExitCallback?) = Unit
        override fun start(channel: ChannelSession?, env: Environment?) = Unit
        override fun destroy(channel: ChannelSession?) = Unit
    }

    private companion object {
        const val USER = "larry"
        const val PASSWORD = "correct horse"
    }
}
