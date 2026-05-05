package net.hlan.sushi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupChecklistTest {

    private fun state(
        hasSshHost: Boolean = false,
        hasSshKey: Boolean = false,
        hasGeminiKey: Boolean = false,
        hasDriveAuth: Boolean = false
    ) = SetupChecklistState(hasSshHost, hasSshKey, hasGeminiKey, hasDriveAuth)

    @Test fun required_nothingConfigured() = assertFalse(state().requiredComplete)
    @Test fun required_onlyHost() = assertFalse(state(hasSshHost = true).requiredComplete)
    @Test fun required_onlyKey() = assertFalse(state(hasSshKey = true).requiredComplete)
    @Test fun required_hostAndKey() = assertTrue(state(hasSshHost = true, hasSshKey = true).requiredComplete)
    @Test fun required_optionalsDoNotUnblock() = assertFalse(
        state(hasGeminiKey = true, hasDriveAuth = true).requiredComplete
    )
    @Test fun required_allConfigured() = assertTrue(
        state(hasSshHost = true, hasSshKey = true, hasGeminiKey = true, hasDriveAuth = true).requiredComplete
    )
}
