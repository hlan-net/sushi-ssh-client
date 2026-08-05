package net.hlan.sushi

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI

/**
 * GitHub OAuth Device Flow — no client secret needed, so the client ID is safe
 * to embed in the app. The user approves sign-in on github.com/login/device
 * from any browser instead of pasting a token on a phone keyboard.
 */
class GitHubAuthManager(private val settings: FeedbackSettings) {

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int
    )

    sealed class DeviceFlowResult {
        data class Success(val token: String, val username: String?) : DeviceFlowResult()
        data class Failed(val message: String) : DeviceFlowResult()
        object Denied : DeviceFlowResult()
        object Expired : DeviceFlowResult()
    }

    /** Requests a device code. Must be called on a background thread. */
    fun requestDeviceCode(): DeviceCode? {
        val connection = post(DEVICE_CODE_URL, "client_id=$CLIENT_ID&scope=public_repo")
        return try {
            val response = JSONObject(readBody(connection))
            DeviceCode(
                deviceCode = response.getString("device_code"),
                userCode = response.getString("user_code"),
                verificationUri = response.getString("verification_uri"),
                expiresIn = response.getInt("expires_in"),
                interval = response.getInt("interval")
            )
        } catch (ex: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Polls until the user approves, denies, or the code expires. Must be called
     * on a background thread — suspends between polls with [delay].
     */
    suspend fun pollForToken(deviceCode: DeviceCode): DeviceFlowResult {
        var interval = deviceCode.interval
        val deadline = System.currentTimeMillis() + deviceCode.expiresIn * 1000L

        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)

            val connection = post(
                TOKEN_URL,
                "client_id=$CLIENT_ID&device_code=${deviceCode.deviceCode}" +
                    "&grant_type=urn:ietf:params:oauth:grant-type:device_code"
            )
            val response = try {
                JSONObject(readBody(connection))
            } catch (ex: Exception) {
                return DeviceFlowResult.Failed(ex.message ?: "Network error")
            } finally {
                connection.disconnect()
            }

            when (response.optString("error")) {
                "" -> {
                    val token = response.optString("access_token")
                    if (token.isNotBlank()) {
                        settings.setGitHubToken(token)
                        val username = fetchUsername(token)
                        settings.setGitHubUsername(username.orEmpty())
                        return DeviceFlowResult.Success(token, username)
                    }
                    return DeviceFlowResult.Failed("No access token in response")
                }
                "authorization_pending" -> continue
                "slow_down" -> {
                    interval += SLOW_DOWN_INCREMENT_SECONDS
                    continue
                }
                "access_denied" -> return DeviceFlowResult.Denied
                "expired_token" -> return DeviceFlowResult.Expired
                else -> return DeviceFlowResult.Failed(response.optString("error_description", "Unknown error"))
            }
        }
        return DeviceFlowResult.Expired
    }

    private fun fetchUsername(token: String): String? {
        val connection = URI(USER_URL).toURL().openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        return try {
            JSONObject(readBody(connection)).optString("login").ifBlank { null }
        } catch (ex: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun post(url: String, body: String): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        OutputStreamWriter(connection.outputStream).use { it.write(body) }
        return connection
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return BufferedReader(InputStreamReader(stream)).use { it.readText() }
    }

    companion object {
        private const val CLIENT_ID = "Ov23li1omfB7ZAUAkgji"
        private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        private const val USER_URL = "https://api.github.com/user"
        private const val SLOW_DOWN_INCREMENT_SECONDS = 5
    }
}
