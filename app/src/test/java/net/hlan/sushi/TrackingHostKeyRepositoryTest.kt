package net.hlan.sushi

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Tests for [TrackingHostKeyRepository]'s decorator behavior — it must forward every call to
 * the delegate unchanged, while additionally recording the last [HostKeyRepository.check]
 * result so [DialogUserInfo.promptYesNo] can tell a first-trust prompt apart from a
 * changed-key warning without string-sniffing JSch's message text.
 */
class TrackingHostKeyRepositoryTest {

    private class FakeHostKeyRepository : HostKeyRepository {
        var checkResultToReturn: Int = HostKeyRepository.OK
        var lastAddedHostKey: HostKey? = null
        var lastRemovedHost: String? = null

        override fun check(host: String?, key: ByteArray?): Int = checkResultToReturn
        override fun add(hostkey: HostKey?, userinfo: UserInfo?) { lastAddedHostKey = hostkey }
        override fun remove(host: String?, type: String?) { lastRemovedHost = host }
        override fun remove(host: String?, type: String?, key: ByteArray?) { lastRemovedHost = host }
        override fun getKnownHostsRepositoryID(): String = "fake-repo-id"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    @Test
    fun check_recordsResultHostAndKey() {
        val delegate = FakeHostKeyRepository().apply { checkResultToReturn = HostKeyRepository.NOT_INCLUDED }
        val tracking = TrackingHostKeyRepository(delegate)
        val keyBytes = byteArrayOf(1, 2, 3)

        val result = tracking.check("example.com:22", keyBytes)

        assertEquals(HostKeyRepository.NOT_INCLUDED, result)
        assertEquals(HostKeyRepository.NOT_INCLUDED, tracking.lastCheckResult)
        assertEquals("example.com:22", tracking.lastCheckedHost)
        assertArrayEquals(keyBytes, tracking.lastCheckedKey)
    }

    @Test
    fun check_defaultsToOkBeforeAnyCall() {
        val tracking = TrackingHostKeyRepository(FakeHostKeyRepository())
        assertEquals(HostKeyRepository.OK, tracking.lastCheckResult)
        assertNull(tracking.lastCheckedHost)
    }

    @Test
    fun check_overwritesPreviousResultOnEachCall() {
        val delegate = FakeHostKeyRepository()
        val tracking = TrackingHostKeyRepository(delegate)

        delegate.checkResultToReturn = HostKeyRepository.NOT_INCLUDED
        tracking.check("host-a", byteArrayOf(1))
        assertEquals(HostKeyRepository.NOT_INCLUDED, tracking.lastCheckResult)
        assertEquals("host-a", tracking.lastCheckedHost)

        delegate.checkResultToReturn = HostKeyRepository.CHANGED
        tracking.check("host-b", byteArrayOf(2))
        assertEquals(HostKeyRepository.CHANGED, tracking.lastCheckResult)
        assertEquals("host-b", tracking.lastCheckedHost)
    }

    @Test
    fun add_forwardsToDelegate() {
        val delegate = FakeHostKeyRepository()
        val tracking = TrackingHostKeyRepository(delegate)
        // Minimal SSH wire-format blob (length-prefixed algorithm name + a fake 32-byte
        // Ed25519 public key) so HostKey's constructor can determine a key type from it.
        val algName = "ssh-ed25519".toByteArray()
        val keyBytes = byteArrayOf(0, 0, 0, algName.size.toByte()) + algName +
            byteArrayOf(0, 0, 0, 32) + ByteArray(32)
        val hostKey = HostKey("example.com:22", keyBytes)

        tracking.add(hostKey, null)

        assertSame(hostKey, delegate.lastAddedHostKey)
    }

    @Test
    fun remove_forwardsToDelegate() {
        val delegate = FakeHostKeyRepository()
        val tracking = TrackingHostKeyRepository(delegate)

        tracking.remove("example.com:22", "ssh-ed25519")

        assertEquals("example.com:22", delegate.lastRemovedHost)
    }

    @Test
    fun getKnownHostsRepositoryID_forwardsToDelegate() {
        val tracking = TrackingHostKeyRepository(FakeHostKeyRepository())
        assertEquals("fake-repo-id", tracking.knownHostsRepositoryID)
    }
}
