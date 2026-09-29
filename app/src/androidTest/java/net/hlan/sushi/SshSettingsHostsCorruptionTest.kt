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

    @Test
    fun corruptBlob_getHostsReturnsEmptyAndFlagsCorruption() {
        prefs.edit().putString(KEY_HOSTS_JSON, "not json").commit()

        assertTrue(sshSettings.getHosts().isEmpty())
        assertTrue(sshSettings.hasCorruptHosts())
    }

    @Test
    fun validBlob_doesNotFlagCorruption() {
        sshSettings.saveHost(host("ekho"))

        assertFalse(sshSettings.hasCorruptHosts())
    }

    @Test
    fun savingAHost_backsUpThePreviousValidBlob() {
        sshSettings.saveHost(host("ekho"))
        assertFalse("nothing to back up yet on the very first save", sshSettings.hasHostsBackup())

        sshSettings.saveHost(host("larry"))

        assertTrue(sshSettings.hasHostsBackup())
    }

    @Test
    fun corruptingTheBlobAfterAGoodSave_leavesTheBackupRestorable() {
        sshSettings.saveHost(host("ekho"))
        sshSettings.saveHost(host("larry")) // backs up the single-host ("ekho") blob

        prefs.edit().putString(KEY_HOSTS_JSON, "not json").commit()
        assertTrue(sshSettings.getHosts().isEmpty())
        assertTrue(sshSettings.hasCorruptHosts())

        assertTrue(sshSettings.restoreHostsFromBackup())

        assertEquals(listOf("ekho"), sshSettings.getHosts().map { it.alias })
        assertFalse(sshSettings.hasCorruptHosts())
    }

    @Test
    fun aCorruptBlobIsNeverBackedUpOverAGoodOne() {
        sshSettings.saveHost(host("ekho"))
        sshSettings.saveHost(host("larry")) // backup now holds the "ekho" blob

        prefs.edit().putString(KEY_HOSTS_JSON, "not json").commit()
        sshSettings.getHosts() // observe the corruption, as the corruption-notice path would

        // A further write while the live blob is corrupt must not clobber the good backup.
        sshSettings.saveHost(host("mira"))

        assertTrue(sshSettings.restoreHostsFromBackup())
        assertEquals(listOf("ekho"), sshSettings.getHosts().map { it.alias })
    }

    @Test
    fun restoreHostsFromBackup_falseWithNoBackup() {
        assertFalse(sshSettings.restoreHostsFromBackup())
    }

    @Test
    fun dismissHostsCorruptionNotice_clearsTheFlagWithoutTouchingTheBlob() {
        prefs.edit().putString(KEY_HOSTS_JSON, "not json").commit()
        sshSettings.getHosts()
        assertTrue(sshSettings.hasCorruptHosts())

        sshSettings.dismissHostsCorruptionNotice()

        assertFalse(sshSettings.hasCorruptHosts())
        assertTrue("dismissing must not fix the underlying blob", sshSettings.getHosts().isEmpty())
    }

    companion object {
        private const val KEY_HOSTS_JSON = "ssh_hosts_json"
        private const val KEY_HOSTS_JSON_BACKUP = "ssh_hosts_json_backup"
        private const val KEY_HOSTS_CORRUPT = "ssh_hosts_corrupt"
    }
}
