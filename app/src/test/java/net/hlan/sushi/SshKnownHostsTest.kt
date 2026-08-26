package net.hlan.sushi

import com.jcraft.jsch.JSch
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [SshKnownHosts.attach] wires JSch to the app's known-hosts store. The file it points at must
 * already exist by the time the first trust prompt is answered: `KnownHosts.syncKnownHostsFile`
 * raises its own separate `promptYesNo` ("…does not exist. Are you sure you want to create it?")
 * when it is missing, which [DialogUserInfo.promptYesNo] cannot tell apart from a host-key
 * prompt — so the user is asked to verify the same key twice, and cancelling the second one
 * silently discards the key they just approved.
 */
class SshKnownHostsTest {

    private fun freshKnownHostsPath(): File =
        File.createTempFile("sushi_known_hosts_test", null).apply {
            delete()
            deleteOnExit()
        }

    @Test
    fun attach_createsTheKnownHostsFileWhenItIsMissing() {
        val knownHosts = freshKnownHostsPath()

        SshKnownHosts.attach(JSch(), knownHosts)

        assertTrue(
            "known_hosts must exist before the first trust prompt, or JSch raises a second " +
                "yes/no prompt of its own",
            knownHosts.exists()
        )
    }

    @Test
    fun attach_returnsATrackingRepositoryBoundToThatFile() {
        val knownHosts = freshKnownHostsPath()
        val jsch = JSch()

        val repo = SshKnownHosts.attach(jsch, knownHosts)

        assertNotNull("attach() should return the tracking decorator", repo)
        assertTrue(
            "the JSch instance should be using the tracking repository",
            jsch.hostKeyRepository === repo
        )
    }

    @Test
    fun attach_keepsExistingEntries() {
        val knownHosts = freshKnownHostsPath()
        knownHosts.writeText(
            "example.com:22 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGV4YW1wbGVrZXlmb3J0ZXN0aW5nMTIzNDU2\n"
        )

        val repo = SshKnownHosts.attach(JSch(), knownHosts)

        assertTrue(
            "attach() must not clobber a known_hosts file that already has entries",
            repo.hostKey.orEmpty().any { it.host == "example.com:22" }
        )
    }
}
