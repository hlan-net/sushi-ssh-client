package net.hlan.sushi

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Client for interacting with the AI persona on the target system.
 * 
 * The persona is defined by ~/.config/sushi/SUSHI.md on the target system.
 * This client reads that file on connection and uses it as context for LLM interactions.
 */
class PersonaClient(private val sshClient: SshClient) {

    private var sushiMdContent: String? = null
    private var systemIdentity: String? = null

    /**
     * Initialize the persona by reading SUSHI.md from the target system.
     * Should be called once after SSH connection is established.
     * 
     * @return PersonaInitResult with success status and the SUSHI.md content
     */
    suspend fun initialize(): PersonaInitResult = withContext(Dispatchers.IO) {
        return@withContext try {
            // Try to read SUSHI.md
            val readResult = sshClient.sendCommand("cat ~/.config/sushi/SUSHI.md 2>/dev/null || echo 'SUSHI_NOT_FOUND'")
            
            if (!readResult.success) {
                Log.w(TAG, "Failed to read SUSHI.md: ${readResult.message}")
                return@withContext PersonaInitResult(
                    success = false,
                    sushiMdContent = null,
                    systemIdentity = null,
                    message = "Could not access target system"
                )
            }

            val output = readResult.message
            if (output.contains("SUSHI_NOT_FOUND") || output.trim().isEmpty()) {
                Log.i(TAG, "SUSHI.md not found on target system")
                // Return minimal default persona
                val defaultContent = buildDefaultPersona()
                sushiMdContent = defaultContent
                systemIdentity = extractSystemIdentity(defaultContent)
                return@withContext PersonaInitResult(
                    success = true,
                    sushiMdContent = defaultContent,
                    systemIdentity = systemIdentity,
                    message = "Persona not initialized. Run 'Initialize AI Persona' Play.",
                    isDefault = true
                )
            }

            // Successfully read SUSHI.md
            sushiMdContent = output
            systemIdentity = extractSystemIdentity(output)
            
            Log.d(TAG, "Persona initialized successfully. Identity: $systemIdentity")
            PersonaInitResult(
                success = true,
                sushiMdContent = output,
                systemIdentity = systemIdentity,
                message = "Connected to $systemIdentity"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing persona", e)
            PersonaInitResult(
                success = false,
                sushiMdContent = null,
                systemIdentity = null,
                message = "Error: ${e.message}"
            )
        }
    }

    /**
     * Get the cached SUSHI.md content. Returns null if not initialized.
     */
    fun getSushiMdContent(): String? = sushiMdContent

    /**
     * Get the system identity name (extracted from SUSHI.md or hostname).
     */
    fun getSystemIdentity(): String? = systemIdentity

    /**
     * Profile the target system to gather basic information.
     * Can be used to populate initial SUSHI.md or update existing one.
     */
    suspend fun profileSystem(): SystemProfile? = withContext(Dispatchers.IO) {
        return@withContext try {
            val scriptPath = "~/.config/sushi/scripts/profile_system.sh"
            val checkResult = sshClient.sendCommand("test -x $scriptPath && echo 'EXISTS' || echo 'NOT_FOUND'")
            
            if (checkResult.message.contains("NOT_FOUND")) {
                Log.w(TAG, "Profile script not found")
                return@withContext null
            }

            val result = sshClient.sendCommand("$scriptPath 2>/dev/null")
            if (!result.success) {
                Log.w(TAG, "Profile script execution failed: ${result.message}")
                return@withContext null
            }

            // Parse JSON output
            parseSystemProfile(result.message)
        } catch (e: Exception) {
            Log.e(TAG, "Error profiling system", e)
            null
        }
    }

    /**
     * Build a default persona template when SUSHI.md doesn't exist.
     */
    private fun buildDefaultPersona(): String {
        return """
# Sushi AI Persona Configuration

You are connected to a remote Linux system via SSH. There is no detailed persona configuration yet.

## System Identity

Unknown system. The AI persona has not been initialized on this target.

## Personality

Professional and helpful. Explain that the persona is not initialized and suggest running the 'Initialize AI Persona' Play.

## Available Commands

You can execute standard Linux commands, but you don't have specific system context yet.

### Safe Commands
- ls, pwd, cat, grep (file operations)
- df -h, free -h, uptime (system status)
- systemctl status (service status)

## Safety Guidelines

### App-Enforced (always blocked)
- shutdown, reboot, poweroff, halt
- rm -rf / and recursive system deletes
- mkfs, dd if=/dev/, disk operations

### Requires Confirmation
- Service restarts
- Package operations
- sudo commands

## Notes

This is a minimal default persona. To enable full AI assistance:
1. Run the 'Initialize AI Persona' Play from the Sushi app
2. This will create ~/.config/sushi/SUSHI.md on the target system
3. Edit SUSHI.md to add system-specific information

---
*Default persona template v0.5.0*
        """.trimIndent()
    }

    /**
     * Extract system identity name from SUSHI.md content.
     * Looks for "Hostname:" or custom "Name:" in the file.
     */
    private fun extractSystemIdentity(content: String): String {
        // Look for "Name: ..." in persona section
        val nameRegex = Regex("""(?:^|\n)\s*-\s*\*\*Name\*\*:\s*(.+)""", RegexOption.MULTILINE)
        val nameMatch = nameRegex.find(content)
        if (nameMatch != null) {
            return nameMatch.groupValues[1].trim()
        }

        // Fall back to Hostname
        val hostnameRegex = Regex("""(?:^|\n)\s*-\s*\*\*Hostname\*\*:\s*(.+)""", RegexOption.MULTILINE)
        val hostnameMatch = hostnameRegex.find(content)
        if (hostnameMatch != null) {
            return hostnameMatch.groupValues[1].trim()
        }

        return "Unknown System"
    }

    /**
     * Parse system profile JSON output from profile_system.sh script.
     */
    private fun parseSystemProfile(json: String): SystemProfile? {
        return try {
            // Simple JSON parsing (could use org.json.JSONObject for more robust parsing)
            SystemProfile(
                osName = extractJsonField(json, "os_name"),
                hardware = extractJsonField(json, "hardware"),
                cpuCores = extractJsonField(json, "cpu_cores")?.toIntOrNull() ?: 0,
                memoryTotal = extractJsonField(json, "memory_total"),
                diskTotal = extractJsonField(json, "disk_total"),
                hostname = extractJsonField(json, "hostname"),
                kernel = extractJsonField(json, "kernel"),
                uptime = extractJsonField(json, "uptime"),
                temperature = extractJsonField(json, "temperature"),
                isRaspberryPi = extractJsonField(json, "is_raspberry_pi") == "true"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse system profile", e)
            null
        }
    }

    private fun extractJsonField(json: String, field: String): String? {
        val regex = Regex(""""$field":\s*"([^"]*)"?""")
        val match = regex.find(json)
        return match?.groupValues?.get(1)
    }

    companion object {
        private const val TAG = "PersonaClient"
    }
}

/**
 * Result of persona initialization.
 */
data class PersonaInitResult(
    val success: Boolean,
    val sushiMdContent: String?,
    val systemIdentity: String?,
    val message: String,
    val isDefault: Boolean = false
)

/**
 * System profile information gathered from the target system.
 */
data class SystemProfile(
    val osName: String?,
    val hardware: String?,
    val cpuCores: Int,
    val memoryTotal: String?,
    val diskTotal: String?,
    val hostname: String?,
    val kernel: String?,
    val uptime: String?,
    val temperature: String?,
    val isRaspberryPi: Boolean
)
