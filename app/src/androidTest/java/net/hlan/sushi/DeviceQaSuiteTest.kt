package net.hlan.sushi

import android.content.Intent
import android.util.Base64
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.isEmptyOrNullString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class DeviceQaSuiteTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun clearState() {
        wakeAndUnlock()
        // Disable autofill to prevent Google Password Manager from stealing focus.
        instrumentation.uiAutomation.executeShellCommand(
            "settings put secure autofill_service null"
        ).close()
        Thread.sleep(500)

        val context = instrumentation.targetContext
        SecurePrefs.get(context).edit().clear().commit()
        context.getSharedPreferences("sushi_console_logs", android.content.Context.MODE_PRIVATE)
            .edit()
            .remove("latest_log")
            .commit()
        PhraseDatabaseHelper.resetInstance()
        PlayDatabaseHelper.resetInstance()
        context.deleteDatabase("sushi_phrases.db")
        context.deleteDatabase("sushi_plays.db")
        SshKnownHosts.file(context).delete()
    }

    @Test
    fun fullTapThroughNonExternalFlows() {
        val hostAlias = "QA Host"
        val hostValue = "qa-host.local"

        // MainActivity — verify status text and settings button
        launchActivity(MainActivity::class.java).use {
            onView(withId(R.id.sessionStatusText))
                .check(matches(withText(not(isEmptyOrNullString()))))
            onView(withId(R.id.mainSettingsButton)).perform(scrollTo()).check(matches(isDisplayed()))
        }

        // SettingsActivity — verify title and SSH page generate-key button
        launchActivity(SettingsActivity::class.java).use { scenario ->
            onView(withId(R.id.settingsTitle))
                .check(matches(withText(not(isEmptyOrNullString()))))
            onView(withText("SSH")).perform(click())
            scrollIntoView(scenario, R.id.quickGenerateKeyButton)
            onView(withId(R.id.quickGenerateKeyButton)).check(matches(isDisplayed()))
        }

        // HostsActivity — verify title and FAB
        launchActivity(HostsActivity::class.java).use {
            onView(withId(R.id.hostsTitle))
                .check(matches(withText(not(isEmptyOrNullString()))))
            onView(withId(R.id.addHostFab)).check(matches(isDisplayed()))
        }

        // HostEditActivity — fill in and save a host
        launchActivity(HostEditActivity::class.java).use {
            onView(withId(R.id.hostAliasInput)).perform(replaceText(hostAlias))
            onView(withId(R.id.sshHostInput)).perform(replaceText(hostValue))
            onView(withId(R.id.sshPortInput)).perform(replaceText("22"))
            onView(withId(R.id.sshUsernameInput)).perform(replaceText("qa-user"))
            onView(withId(R.id.sshPasswordInput)).perform(replaceText("qa-password"))
            onView(withId(R.id.saveButton)).perform(scrollTo(), click())
        }

        // HostsActivity — verify host appears and can be tapped
        launchActivity(HostsActivity::class.java).use { hostsScenario ->
            waitForCondition(hostsScenario) { activity ->
                val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.hostsRecyclerView)
                recycler.adapter?.itemCount ?: 0 > 0
            }
            onView(withId(R.id.hostsRecyclerView)).perform(
                RecyclerViewActions.actionOnItemAtPosition<androidx.recyclerview.widget.RecyclerView.ViewHolder>(0, click())
            )
        }

        // SettingsActivity — verify quick-generate-key button on SSH tab
        launchActivity(SettingsActivity::class.java).use { scenario ->
            onView(withText("SSH")).perform(click())
            scrollIntoView(scenario, R.id.quickGenerateKeyButton)
            onView(withId(R.id.quickGenerateKeyButton)).check(matches(isDisplayed()))
        }

        // KeysActivity — verify title, status, and generate button
        launchActivity(KeysActivity::class.java).use {
            onView(withId(R.id.keysTitle))
                .check(matches(withText(not(isEmptyOrNullString()))))
            onView(withId(R.id.keyStatusText))
                .check(matches(withText(not(isEmptyOrNullString()))))
            onView(withId(R.id.generateKeyButton)).check(matches(isDisplayed()))
        }

        // SettingsActivity — verify about button
        launchActivity(SettingsActivity::class.java).use {
            onView(withId(R.id.aboutButton)).check(matches(isDisplayed()))
        }

        // AboutActivity — verify title and github button
        launchActivity(AboutActivity::class.java).use {
            onView(withId(R.id.aboutTitle))
                .check(matches(withText(not(isEmptyOrNullString()))))
            onView(withId(R.id.githubButton)).check(matches(isDisplayed()))
        }

        // Verify host was saved via SshSettings
        val context = instrumentation.targetContext
        val sshSettings = SshSettings(context)
        val activeHost = sshSettings.getActiveHostId()?.let { id ->
            sshSettings.getHosts().find { it.id == id }
        }
        assertTrue("Active host should be set", activeHost != null)
        assertTrue("Active host should match saved value",
            activeHost?.host == hostValue)

        // PhrasesActivity — verify title and add button
        launchActivity(PhrasesActivity::class.java).use {
            onView(withId(R.id.phrasesTitle))
                .check(matches(withText(not(isEmptyOrNullString()))))
            onView(withId(R.id.addPhraseButton)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun keyGenerationCreatesManagedPhrasesAndPhraseCanBeSelectedInMainUi() {
        val context = instrumentation.targetContext
        val sshSettings = SshSettings(context)
        val db = PhraseDatabaseHelper.getInstance(context)
        val playDb = PlayDatabaseHelper.getInstance(context)

        launchActivity(KeysActivity::class.java).use {
            onView(withId(R.id.generateKeyButton)).perform(click())
            // Key generation now prompts for an optional passphrase first; confirm with it
            // left blank (an explicit, supported "no passphrase" choice) to proceed.
            onView(withText(R.string.key_passphrase_confirm)).perform(click())

            waitUntil(
                timeoutMs = 20_000,
                timeoutMessage = "Key generation did not create managed phrases"
            ) {
                sshSettings.getPrivateKey().orEmpty().isNotBlank() &&
                    sshSettings.getPublicKey().orEmpty().isNotBlank() &&
                    db.getPhraseByName(PHRASE_INSTALL_KEY) != null &&
                    db.getPhraseByName(PHRASE_REMOVE_SUSHI_KEYS) != null &&
                    playDb.getPlayByName("Install SSH Key") != null &&
                    playDb.getPlayByName("Reboot Host") != null
            }
        }

        val removePhrase = db.getPhraseByName(PHRASE_REMOVE_SUSHI_KEYS)
        assertTrue("Remove Sushi SSH Keys phrase should exist", removePhrase != null)
        val removePhraseCommand = removePhrase?.command.orEmpty()
        assertTrue("Remove Sushi SSH Keys command should not be blank", removePhraseCommand.isNotBlank())

        val installPlay = playDb.getPlayByName("Install SSH Key")
        assertTrue("Install SSH Key play should exist", installPlay != null)
        assertTrue(
            "Install SSH Key play should avoid duplicates",
            installPlay?.scriptTemplate.orEmpty().contains("grep -Fqx")
        )

        val rebootPlay = playDb.getPlayByName("Reboot Host")
        assertTrue("Reboot Host play should exist", rebootPlay != null)
        assertTrue("Reboot Host play should use logout placeholder", rebootPlay?.scriptTemplate == "logout")

        launchActivity(PhrasesActivity::class.java).use { phrasesScenario ->
            waitForCondition(phrasesScenario) { activity ->
                val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.phrasesRecyclerView)
                recycler.adapter?.itemCount ?: 0 >= 2
            }

            onView(withId(R.id.phrasesRecyclerView)).perform(
                RecyclerViewActions.actionOnItem<androidx.recyclerview.widget.RecyclerView.ViewHolder>(
                    hasDescendant(withText(PHRASE_REMOVE_SUSHI_KEYS)), click()
                )
            )
        }
    }

    @Test
    fun localShellHostIsSeededAndAppearsInHostList() {
        val context = instrumentation.targetContext
        // clearState() wiped prefs; re-seed as Application.onCreate() would on first launch.
        SshSettings(context).seedLocalHostIfMissing()

        val sshSettings = SshSettings(context)
        val localHosts = sshSettings.getHosts().filter { it.kind == HostKind.LOCAL }
        assertTrue("At least one LOCAL host should exist after seeding", localHosts.isNotEmpty())
        assertTrue(
            "Seeded LOCAL host alias should not be blank",
            localHosts.first().alias.isNotBlank()
        )

        // Verify the seeded host appears in HostsActivity list
        launchActivity(HostsActivity::class.java).use { scenario ->
            waitForCondition(scenario) { activity ->
                val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
                    R.id.hostsRecyclerView
                )
                recycler.adapter?.itemCount ?: 0 > 0
            }
            onView(withId(R.id.hostsRecyclerView))
                .check(matches(isDisplayed()))
        }
    }

    @Test
    fun localHostEditShowsOnlyAliasField() {
        val context = instrumentation.targetContext
        SshSettings(context).seedLocalHostIfMissing()

        val localHost = SshSettings(context).getHosts().first { it.kind == HostKind.LOCAL }
        val intent = Intent(context, HostEditActivity::class.java)
            .putExtra(HostEditActivity.EXTRA_HOST_ID, localHost.id)

        wakeAndUnlock()
        Thread.sleep(400)
        ActivityScenario.launch<HostEditActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }

            // Alias field must be visible and editable
            onView(withId(R.id.hostAliasInput)).check(matches(isDisplayed()))

            // SSH-specific fields must be hidden for LOCAL hosts
            onView(withId(R.id.sshHostLayout)).check(matches(not(isDisplayed())))
            onView(withId(R.id.sshPortLayout)).check(matches(not(isDisplayed())))
            onView(withId(R.id.sshUsernameLayout)).check(matches(not(isDisplayed())))
            onView(withId(R.id.sshPasswordLayout)).check(matches(not(isDisplayed())))
            onView(withId(R.id.authPreferenceLayout)).check(matches(not(isDisplayed())))
            onView(withId(R.id.jumpEnabledSwitch)).check(matches(not(isDisplayed())))

            // Delete button must be hidden so the synthetic host cannot be removed
            onView(withId(R.id.deleteButton)).check(matches(not(isDisplayed())))
        }
    }

    /**
     * The changed-key path: JSch calls `remove(host, type, null)` from
     * `Session.doCheckHostKey` when the user accepts a replacement key. If the override
     * declares `key` non-null, Kotlin's intrinsic null check throws out of `session.connect()`,
     * the old key is never removed, the new one is never stored, and the host becomes
     * permanently unreachable.
     */
    @Test
    fun changedHostKeyCanBeReplacedTheWayJschDoesIt() {
        val context = instrumentation.targetContext
        val knownHosts = File(context.cacheDir, "qa_known_hosts_replace").apply { delete() }
        val jsch = JSch()
        val repo: HostKeyRepository = SshKnownHosts.attach(jsch, knownHosts)

        val hostKey = HostKey(HOST_KEY_ALIAS, generatePublicKeyBlob(jsch))
        repo.add(hostKey, null)
        assertEquals(
            "seeded key should be present before the replace",
            1,
            repo.getHostKey(HOST_KEY_ALIAS, hostKey.type).size
        )

        // Exactly the call JSch makes when the user confirms "replace changed key".
        repo.remove(HOST_KEY_ALIAS, hostKey.type, null)

        assertEquals(
            "the superseded key must be removable so the replacement can be stored",
            0,
            repo.getHostKey(HOST_KEY_ALIAS, hostKey.type).size
        )
        knownHosts.delete()
    }

    /**
     * Host keys are stored under the `host:port` alias set by `configureSession`, but the
     * HOST_KEY_MISMATCH banner's "View host key" action passes the bare host. If the screen
     * filters on an exact match the user always lands on the empty state.
     */
    @Test
    fun hostKeysScreenShowsEntryStoredUnderHostPortAlias() {
        val context = instrumentation.targetContext
        val jsch = JSch()
        val blob = generatePublicKeyBlob(jsch)
        val entry = "$HOST_KEY_ALIAS ssh-rsa ${Base64.encodeToString(blob, Base64.NO_WRAP)}\n"
        // java.io rather than File.writeText(): kotlin.io.FilesKt is stripped from the minified
        // APK this suite runs against, since no app code pulls it in.
        FileOutputStream(SshKnownHosts.file(context)).use { out ->
            out.write(entry.toByteArray(Charsets.UTF_8))
        }

        // Control: prove the seeded entry really parses back out under the host:port alias, so a
        // failure below can only mean the screen's filter rejected it.
        val storedKeys = SshKnownHosts.attach(JSch(), SshKnownHosts.file(context)).hostKey.orEmpty()
        assertEquals("seeded entry should parse back out of known_hosts", 1, storedKeys.size)
        assertEquals("entry is stored under the host:port alias", HOST_KEY_ALIAS, storedKeys[0].host)

        val intent = Intent(context, HostKeysActivity::class.java)
            // TerminalActivity passes config.host, without the port.
            .putExtra(HostKeysActivity.EXTRA_HOST_FILTER, HOST_KEY_HOST)

        wakeAndUnlock()
        Thread.sleep(400)
        ActivityScenario.launch<HostKeysActivity>(intent).use { scenario ->
            waitForCondition(scenario) { activity ->
                activity.findViewById<androidx.recyclerview.widget.RecyclerView>(
                    R.id.hostKeysRecyclerView
                ).adapter?.itemCount ?: 0 > 0
            }
            onView(withId(R.id.hostKeysRecyclerView)).check(matches(isDisplayed()))
            onView(withId(R.id.emptyHostKeysText)).check(matches(not(isDisplayed())))
        }
    }

    /**
     * `attach()` must leave a known_hosts file on disk. Otherwise JSch's `syncKnownHostsFile`
     * raises a second, separate "…does not exist. Are you sure you want to create it?" yes/no
     * prompt on the very first trust — the user sees the trust dialog twice, and cancelling the
     * second one silently discards the key they just approved.
     */
    @Test
    fun attachCreatesKnownHostsSoJschNeverAsksToCreateIt() {
        val context = instrumentation.targetContext
        val knownHosts = SshKnownHosts.file(context)
        knownHosts.delete()

        SshKnownHosts.attach(JSch(), knownHosts)

        assertTrue(
            "known_hosts must exist before the first trust prompt",
            knownHosts.exists()
        )
    }

    /** Declining a host key is a deliberate refusal, not a transient error to auto-retry. */
    @Test
    fun decliningAHostKeyDoesNotTriggerAnAutomaticReconnect() {
        assertFalse(
            "HOST_KEY_UNTRUSTED must not be retryable — TerminalActivity would re-show the " +
                "same trust dialog immediately after the user cancelled it",
            ConnectFailure.HOST_KEY_UNTRUSTED.isRetryable
        )
    }

    /**
     * `promptPassword` returning true while `getPassword()` returns null makes JSch raise
     * `JSchAuthCancelException` ("Auth cancel") instead of a plain "Auth fail" on a rejected
     * password — which `classifyException` reports as a public-key failure. That misleads every
     * password-only host, including ones that never touch host keys or passphrases.
     */
    @Test
    fun promptPasswordDoesNotClaimAPasswordItCannotSupply() {
        launchActivity(MainActivity::class.java).use { scenario ->
            var claimedAPassword = true
            var suppliedPassword: String? = "unset"
            scenario.onActivity { activity ->
                val userInfo = DialogUserInfo(
                    activity = activity,
                    targetLabel = HOST_KEY_HOST,
                    passphraseCache = KeyPassphraseCache(activity)
                )
                claimedAPassword = userInfo.promptPassword("Password:")
                suppliedPassword = userInfo.password
            }
            assertFalse(
                "promptPassword must return false when getPassword() has nothing to return " +
                    "(supplied: $suppliedPassword)",
                claimedAPassword
            )
        }
    }

    /** RSA-1024 keeps generation fast on low-end test devices; only the blob shape matters here. */
    private fun generatePublicKeyBlob(jsch: JSch): ByteArray =
        KeyPair.genKeyPair(jsch, KeyPair.RSA, 1024).publicKeyBlob

    private fun <T : AppCompatActivity> launchActivity(
        activityClass: Class<T>
    ): ActivityScenario<T> {
        wakeAndUnlock()
        Thread.sleep(400)
        val scenario = ActivityScenario.launch(activityClass)
        scenario.onActivity { activity ->
            activity.window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // Wait for the activity to gain window focus, re-dismissing keyguard if needed.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            var hasFocus = false
            scenario.onActivity { activity -> hasFocus = activity.hasWindowFocus() }
            if (hasFocus) return scenario
            wakeAndUnlock()
            Thread.sleep(250)
        }
        return scenario
    }

    /**
     * Scrolls a view into the visible area using the activity's ScrollView.
     * Espresso's scrollTo() doesn't work for views inside ViewPager2 pages,
     * so we scroll programmatically via onActivity.
     */
    private fun <T : AppCompatActivity> scrollIntoView(
        scenario: ActivityScenario<T>,
        viewId: Int
    ) {
        scenario.onActivity { activity ->
            val view = activity.findViewById<View>(viewId) ?: return@onActivity
            view.parent?.requestChildFocus(view, view)
        }
    }

    private fun <T : AppCompatActivity> waitForCondition(
        scenario: ActivityScenario<T>,
        timeoutMs: Long = 10_000,
        condition: (T) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var ok = false
            scenario.onActivity { activity ->
                ok = condition(activity)
            }
            if (ok) {
                return
            }
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for condition")
    }

    private fun waitUntil(
        timeoutMs: Long,
        timeoutMessage: String,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(250)
        }
        throw AssertionError(timeoutMessage)
    }

    private fun wakeAndUnlock() {
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
    }

    companion object {
        private const val PHRASE_INSTALL_KEY = "Install SSH Key"
        private const val PHRASE_REMOVE_SUSHI_KEYS = "Remove Sushi SSH Keys"

        /** Host keys are stored under the `host:port` alias set by `configureSession`. */
        private const val HOST_KEY_HOST = "qa-host.local"
        private const val HOST_KEY_ALIAS = "$HOST_KEY_HOST:2222"
    }
}
