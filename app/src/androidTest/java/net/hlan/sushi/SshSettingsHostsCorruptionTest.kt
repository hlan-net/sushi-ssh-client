package net.hlan.sushi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `SshSettings.getHosts()` used to return `emptyList()` on a corrupt hosts blob with no trace of
 * what happened, and the very next `saveHost()` call then overwrote that blob for good
 * (`ROADMAP.md` v0.9.x). These cover the backup-before-write and restore-from-backup path that
 * replaced it.
 *
 * Needs a real, Keystore-backed `EncryptedSharedPreferences` ([SecurePrefs]), so this is
 * instrumented rather than a JVM test.
 */
@RunWith(AndroidJUnit4::class)
class SshSettingsHostsCorruptionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sshSettings = SshSettings(context)
    private val prefs = SecurePrefs.get(context)

    @Before
    fun clearHostsState() = clearKeys()

    @After
    fun tearDown() = clearKeys()

    private fun clearKeys() {
        prefs.edit()
            .remove(KEY_HOSTS_JSON)
            .remove(KEY_HOSTS_JSON_BACKUP)
            .remove(KEY_HOSTS_CORRUPT)
            .commit()
    }

    private fun host(alias: String) =
        SshConnectionConfig(alias = alias, host = "$alias.local", port = 22, username = "pi", password = "")

    private fun corrupt() = prefs.edit().putString(KEY_HOSTS_JSON, "not json").commit()

    private fun aliases() = sshSettings.getHosts().map { it.alias }

    @Test
    fun corruptBlob_getHostsReturnsEmptyAndFlagsCorruption() {
        corrupt()

        assertTrue(sshSettings.getHosts().isEmpty())
        assertTrue(sshSettings.hasCorruptHosts())
    }

    @Test
    fun validBlob_doesNotFlagCorruption() {
        sshSettings.saveHost(host("ekho"))

        assertFalse(sshSettings.hasCorruptHosts())
    }

    /** The backup is the list just written, so a single saved host is already recoverable. */
    @Test
    fun theFirstSave_isAlreadyBackedUp() {
        sshSettings.saveHost(host("ekho"))
        corrupt()
        sshSettings.getHosts()

        assertTrue(sshSettings.restoreHostsFromBackup())
        assertEquals(listOf("ekho"), aliases())
    }

    /** Restore brings back the latest write, not the one before it. */
    @Test
    fun restore_bringsBackTheLatestWrite() {
        sshSettings.saveHost(host("ekho"))
        sshSettings.saveHost(host("larry"))
        corrupt()
        sshSettings.getHosts()

        assertTrue(sshSettings.restoreHostsFromBackup())

        assertEquals(listOf("ekho", "larry"), aliases())
        assertFalse(sshSettings.hasCorruptHosts())
    }

    /** An install from before the backup existed gets one from its first good read. */
    @Test
    fun aGoodReadSeedsAMissingBackup() {
        sshSettings.saveHost(host("ekho"))
        prefs.edit().remove(KEY_HOSTS_JSON_BACKUP).commit()

        sshSettings.getHosts()

        assertTrue(sshSettings.hasHostsBackup())
    }

    /**
     * Writes while the corruption is unanswered do not move the backup, and Restore keeps what
     * was added in the meantime.
     */
    @Test
    fun hostsAddedWhileCorrupt_surviveRestore_andDoNotReplaceTheBackup() {
        sshSettings.saveHost(host("ekho"))
        sshSettings.saveHost(host("larry"))
        corrupt()
        sshSettings.getHosts()

        sshSettings.saveHost(host("mira"))
        sshSettings.saveHost(host("ville"))

        assertTrue(sshSettings.restoreHostsFromBackup())
        assertEquals(listOf("ekho", "larry", "mira", "ville"), aliases())
    }

    /** The app-start seed does not write over a blob that fails to parse. */
    @Test
    fun seedLocalHost_leavesACorruptBlobAlone() {
        sshSettings.saveHost(host("ekho"))
        corrupt()

        sshSettings.seedLocalHostIfMissing()

        assertEquals("not json", prefs.getString(KEY_HOSTS_JSON, null))
        assertTrue(sshSettings.hasCorruptHosts())
        assertTrue(sshSettings.restoreHostsFromBackup())
        assertEquals(listOf("ekho"), aliases())
    }

    /** A local shell the seed added after the corruption is not duplicated by Restore. */
    @Test
    fun restore_doesNotDuplicateTheLocalShell() {
        sshSettings.seedLocalHostIfMissing()
        sshSettings.saveHost(host("ekho"))
        corrupt()
        sshSettings.getHosts()
        // The user dismisses nothing yet but adds a host, which writes a valid list; a later
        // seed on that list adds its own local shell.
        sshSettings.saveHost(host("mira"))
        sshSettings.seedLocalHostIfMissing()

        assertTrue(sshSettings.restoreHostsFromBackup())

        assertEquals(1, sshSettings.getHosts().count { it.kind == HostKind.LOCAL })
        assertTrue(aliases().containsAll(listOf("ekho", "mira")))
    }

    @Test
    fun restoreHostsFromBackup_falseWithNoBackup() {
        assertFalse(sshSettings.restoreHostsFromBackup())
    }

    /** Dismiss answers the notice for good: the next read does not flag the same blob again. */
    @Test
    fun dismiss_answersTheNoticeForGood() {
        corrupt()
        sshSettings.getHosts()
        assertTrue(sshSettings.hasCorruptHosts())

        sshSettings.dismissHostsCorruptionNotice()

        assertTrue(sshSettings.getHosts().isEmpty())
        assertFalse(sshSettings.hasCorruptHosts())
    }

    companion object {
        private const val KEY_HOSTS_JSON = "ssh_hosts_json"
        private const val KEY_HOSTS_JSON_BACKUP = "ssh_hosts_json_backup"
        private const val KEY_HOSTS_CORRUPT = "ssh_hosts_corrupt"
    }
}
