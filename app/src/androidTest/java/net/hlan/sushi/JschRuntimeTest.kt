package net.hlan.sushi

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JschRuntimeTest {
    @Test
    fun jschJceClassesPresent() {
        Class.forName("com.jcraft.jsch.jce.Random")
        Class.forName("com.jcraft.jsch.jce.AES128CTR")
        Class.forName("com.jcraft.jsch.jce.SHA256")

        val jsch = JSch()
        assertNotNull(jsch)
    }

    @Test
    fun ed25519BcClassesPresent() {
        // Verify Bouncy Castle Ed25519 classes are available at runtime (not stripped by
        // ProGuard) so that ssh-ed25519 host key negotiation succeeds on all API levels.
        Class.forName("com.jcraft.jsch.bc.SignatureEd25519")
        Class.forName("com.jcraft.jsch.bc.KeyPairGenEdDSA")
    }

    @Test
    fun ed25519InServerHostKeyProposal() {
        // Verify that a session configured with the BC Ed25519 implementation includes
        // ssh-ed25519 in the server_host_key negotiation proposal.
        val jsch = JSch()
        val session = jsch.getSession("user", "127.0.0.1", 22)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        session.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")

        val proposal = session.getConfig("server_host_key")
        assertNotNull("server_host_key config should not be null", proposal)
        assertTrue(
            "server_host_key proposal should contain ssh-ed25519 but was: $proposal",
            proposal.split(",").contains("ssh-ed25519")
        )
    }
}
