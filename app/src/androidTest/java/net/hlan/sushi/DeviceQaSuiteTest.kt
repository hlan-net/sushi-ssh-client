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
}
