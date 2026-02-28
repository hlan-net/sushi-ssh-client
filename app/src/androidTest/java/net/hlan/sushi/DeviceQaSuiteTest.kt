package net.hlan.sushi

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceQaSuiteTest {

    @Before
    fun clearState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SecurePrefs.get(context).edit().clear().commit()
        ConsoleLogRepository(context).clear()
        context.deleteDatabase("sushi_phrases.db")
        context.deleteDatabase("sushi_plays.db")
    }

    @Test
    fun fullTapThroughNonExternalFlows() {
        val hostAlias = "QA Host"
        val hostValue = "qa-host.local"

        ActivityScenario.launch(MainActivity::class.java).use { mainScenario ->
            mainScenario.onActivity { activity ->
                val status = activity.findViewById<android.widget.TextView>(R.id.sessionStatusText)
                assertTrue(status.text.isNotBlank())
                activity.findViewById<android.view.View>(R.id.geminiSettingsButton).performClick()
            }
        }

        ActivityScenario.launch(SettingsActivity::class.java).use { settingsScenario ->
            settingsScenario.onActivity { activity ->
                val title = activity.findViewById<android.widget.TextView>(R.id.settingsTitle)
                assertTrue(title.text.isNotBlank())
                activity.findViewById<android.view.View>(R.id.manageHostsButton).performClick()
            }
        }

        ActivityScenario.launch(HostsActivity::class.java).use { hostsScenario ->
            hostsScenario.onActivity { activity ->
                val title = activity.findViewById<android.widget.TextView>(R.id.hostsTitle)
                assertTrue(title.text.isNotBlank())
                activity.findViewById<android.view.View>(R.id.addHostFab).performClick()
            }
        }

        ActivityScenario.launch(HostEditActivity::class.java).use { hostEditScenario ->
            hostEditScenario.onActivity { activity ->
                activity.findViewById<android.widget.EditText>(R.id.hostAliasInput).setText(hostAlias)
                activity.findViewById<android.widget.EditText>(R.id.sshHostInput).setText(hostValue)
                activity.findViewById<android.widget.EditText>(R.id.sshPortInput).setText("22")
                activity.findViewById<android.widget.EditText>(R.id.sshUsernameInput).setText("qa-user")
                activity.findViewById<android.widget.EditText>(R.id.sshPasswordInput).setText("qa-password")
                activity.findViewById<android.view.View>(R.id.saveButton).performClick()
            }
        }

        ActivityScenario.launch(HostsActivity::class.java).use { hostsScenario ->
            waitForCondition(hostsScenario) { activity ->
                val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.hostsRecyclerView)
                recycler.adapter?.itemCount ?: 0 > 0
            }

            hostsScenario.onActivity { activity ->
                val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.hostsRecyclerView)
                val firstHolder = recycler.findViewHolderForAdapterPosition(0)
                assertTrue("Expected at least one host row", firstHolder != null)
                val aliasText = firstHolder!!.itemView.findViewById<android.widget.TextView>(R.id.hostAliasText)
                assertTrue(aliasText.text.toString() == hostAlias)
                firstHolder.itemView.performClick()
            }
        }

        ActivityScenario.launch(SettingsActivity::class.java).use { settingsScenario ->
            settingsScenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.manageKeysButton).performClick()
            }
        }

        ActivityScenario.launch(KeysActivity::class.java).use { keysScenario ->
            keysScenario.onActivity { activity ->
                val title = activity.findViewById<android.widget.TextView>(R.id.keysTitle)
                val generateButton = activity.findViewById<android.view.View>(R.id.generateKeyButton)
                val statusText = activity.findViewById<android.widget.TextView>(R.id.keyStatusText)
                assertTrue(title.text.isNotBlank())
                assertTrue(statusText.text.isNotBlank())
                assertTrue(generateButton.id == R.id.generateKeyButton)
            }
        }

        ActivityScenario.launch(SettingsActivity::class.java).use { settingsScenario ->
            settingsScenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.aboutButton).performClick()
            }
        }

        ActivityScenario.launch(AboutActivity::class.java).use { aboutScenario ->
            aboutScenario.onActivity { activity ->
                val title = activity.findViewById<android.widget.TextView>(R.id.aboutTitle)
                val githubButton = activity.findViewById<android.view.View>(R.id.githubButton)
                assertTrue(title.text.isNotBlank())
                assertTrue(githubButton.id == R.id.githubButton)
            }
        }

        ActivityScenario.launch(MainActivity::class.java).use { mainScenario ->
            waitForCondition(mainScenario) { activity ->
                val target = activity.findViewById<android.widget.TextView>(R.id.sessionTargetText)
                target.text?.toString()?.contains(hostValue) == true
            }

            mainScenario.onActivity { activity ->
                val footer = activity.findViewById<android.widget.TextView>(R.id.footerText)
                assertTrue(footer.text?.toString()?.contains("sushi v") == true)
                activity.findViewById<android.view.View>(R.id.phrasesButton).performClick()
            }
        }

        ActivityScenario.launch(PhrasesActivity::class.java).use { phrasesScenario ->
            phrasesScenario.onActivity { activity ->
                val title = activity.findViewById<android.widget.TextView>(R.id.phrasesTitle)
                val addButton = activity.findViewById<android.view.View>(R.id.addPhraseButton)
                assertTrue(title.text.isNotBlank())
                assertTrue(addButton.id == R.id.addPhraseButton)
            }
        }
    }

    @Test
    fun keyGenerationCreatesManagedPhrasesAndPhraseCanBeSelectedInMainUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sshSettings = SshSettings(context)
        val db = PhraseDatabaseHelper.getInstance(context)
        val playDb = PlayDatabaseHelper.getInstance(context)

        ActivityScenario.launch(KeysActivity::class.java).use { keysScenario ->
            keysScenario.onActivity { activity ->
                activity.findViewById<android.view.View>(R.id.generateKeyButton).performClick()
            }

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

        ActivityScenario.launch(PhrasesActivity::class.java).use { phrasesScenario ->
            waitForCondition(phrasesScenario) { activity ->
                val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.phrasesRecyclerView)
                recycler.adapter?.itemCount ?: 0 >= 2
            }

            var clicked = false
            phrasesScenario.onActivity { activity ->
                val recycler = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.phrasesRecyclerView)
                val adapter = recycler.adapter
                val itemCount = adapter?.itemCount ?: 0
                for (position in 0 until itemCount) {
                    recycler.scrollToPosition(position)
                    val holder = recycler.findViewHolderForAdapterPosition(position) ?: continue
                    val nameView = holder.itemView.findViewById<android.widget.TextView>(R.id.phraseNameText)
                    val commandView = holder.itemView.findViewById<android.widget.TextView>(R.id.phraseCommandText)
                    if (nameView.text?.toString() == PHRASE_REMOVE_SUSHI_KEYS &&
                        commandView.text?.toString() == removePhraseCommand
                    ) {
                        holder.itemView.performClick()
                        clicked = true
                        break
                    }
                }
            }

            assertTrue("Remove Sushi SSH Keys phrase row should be selectable", clicked)
        }
    }

    private fun <T : androidx.fragment.app.FragmentActivity> waitForCondition(
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

    companion object {
        private const val PHRASE_INSTALL_KEY = "Install SSH Key"
        private const val PHRASE_REMOVE_SUSHI_KEYS = "Remove Sushi SSH Keys"
    }
}
