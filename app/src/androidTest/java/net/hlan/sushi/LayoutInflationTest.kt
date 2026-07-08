package net.hlan.sushi

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Inflates activity layouts without launching activities. Catches resource-level
 * regressions — e.g. corrupt vector pathData that newer Android versions reject
 * (Android 17's PathParser crashed TerminalActivity on a malformed
 * ic_content_paste). Runs on the API 37 emulator where the strict parser lives.
 */
@RunWith(AndroidJUnit4::class)
class LayoutInflationTest {

    private fun themedInflater(): LayoutInflater {
        val context = ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.Theme_Sushi
        )
        return LayoutInflater.from(context)
    }

    @Test
    fun activityTerminalInflates() {
        assertNotNull(themedInflater().inflate(R.layout.activity_terminal, null))
    }

    @Test
    fun activityMainInflates() {
        assertNotNull(themedInflater().inflate(R.layout.activity_main, null))
    }

    @Test
    fun activitySettingsInflates() {
        assertNotNull(themedInflater().inflate(R.layout.activity_settings, null))
    }
}
