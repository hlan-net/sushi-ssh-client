package net.hlan.sushi

import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.isEmptyOrNullString
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
    }

    @Test
    fun fullTapThroughNonExternalFlows() {
        val hostAlias = "QA Host"
        val hostValue = "qa-host.local"

        // MainActivity — verify status text and settings button
        launchActivity(MainActivity::class.java).use {
            onView(withId(R.id.sessionStatusText))
                .check(matches(withText(not(isEmptyOrNullString()))))
            onView(withId(R.id.mainSettingsButton)).check(matches(isDisplayed()))
        }

        // SettingsActivity — verify title and SSH page buttons
        launchActivity(SettingsActivity::class.java).use { scenario ->
            onView(withId(R.id.settingsTitle))
                .check(matches(withText(not(isEmptyOrNullString()))))
            onView(withText("SSH")).perform(click())
            scrollIntoView(scenario, R.id.manageHostsButton)
            onView(withId(R.id.manageHostsButton)).check(matches(isDisplayed()))
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
            onView(withId(R.id.saveButton)).perform(click())
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

            var targetPosition = -1
            phrasesScenario.onActivity { activity ->
                val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.phrasesRecyclerView)
                val adapter = recycler.adapter as? PhraseAdapter ?: return@onActivity
                targetPosition = adapter.currentList.indexOfFirst { phrase ->
                    phrase.name == PHRASE_REMOVE_SUSHI_KEYS && phrase.command == removePhraseCommand
                }
            }

            assertTrue("Remove Sushi SSH Keys phrase row should exist", targetPosition >= 0)

            onView(withId(R.id.phrasesRecyclerView)).perform(
                RecyclerViewActions.actionOnItemAtPosition<androidx.recyclerview.widget.RecyclerView.ViewHolder>(
                    targetPosition, click()
                )
            )
        }
    }

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
    }
}
