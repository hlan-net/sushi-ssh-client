package net.hlan.sushi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubDeviceFlowStateTest {

    @Test
    fun toDeviceCode_returnsRemainingLifetime() {
        val state = GitHubDeviceFlowState.fromDeviceCode(
            deviceCode = GitHubAuthManager.DeviceCode(
                deviceCode = "device-code",
                userCode = "ABCD-1234",
                verificationUri = "https://github.com/login/device",
                expiresIn = 900,
                interval = 5
            ),
            nowMs = 1_000L
        )

        val restored = state.toDeviceCode(nowMs = 61_000L)

        assertEquals(840, restored?.expiresIn)
        assertEquals("device-code", restored?.deviceCode)
        assertEquals("ABCD-1234", restored?.userCode)
        assertEquals("https://github.com/login/device", restored?.verificationUri)
        assertEquals(5, restored?.interval)
    }

    @Test
    fun toDeviceCode_roundsUpPartialSeconds() {
        val state = GitHubDeviceFlowState(
            deviceCode = "device-code",
            userCode = "ABCD-1234",
            verificationUri = "https://github.com/login/device",
            expiresAtMs = 2_500L,
            intervalSeconds = 5
        )

        val restored = state.toDeviceCode(nowMs = 1_001L)

        assertEquals(2, restored?.expiresIn)
    }

    @Test
    fun toDeviceCode_returnsNullWhenExpired() {
        val state = GitHubDeviceFlowState(
            deviceCode = "device-code",
            userCode = "ABCD-1234",
            verificationUri = "https://github.com/login/device",
            expiresAtMs = 5_000L,
            intervalSeconds = 5
        )

        assertNull(state.toDeviceCode(nowMs = 5_000L))
    }
}
