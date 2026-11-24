package net.hlan.sushi.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which Gemini set-ups may start voice input (#231): Nano alone is enough. */
class GeminiAvailabilityTest {

    private fun resolve(enabled: Boolean = true, cloud: Boolean = false, nano: Boolean = false) =
        GeminiAvailability.resolve(enabled, hasCloudCredentials = cloud, nanoPreferredAndAvailable = nano)

    @Test
    fun nanoAlone_isReady() {
        assertEquals(GeminiAvailability.READY, resolve(nano = true))
    }

    @Test
    fun cloudCredentialsAlone_areReady() {
        assertEquals(GeminiAvailability.READY, resolve(cloud = true))
    }

    @Test
    fun neitherNanoNorCloud_isMissingCredentials() {
        assertEquals(GeminiAvailability.MISSING_CREDENTIALS, resolve())
    }

    @Test
    fun disabled_winsOverAnyBackend() {
        assertEquals(GeminiAvailability.DISABLED, resolve(enabled = false, cloud = true, nano = true))
    }
}
