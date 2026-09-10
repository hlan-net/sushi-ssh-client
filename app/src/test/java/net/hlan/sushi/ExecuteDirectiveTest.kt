package net.hlan.sushi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ExecuteDirective] — the `EXECUTE:` directive parsing that drives command
 * execution and, since v0.8.0, multi-step troubleshooting chains.
 */
class ExecuteDirectiveTest {

    @Test
    fun parse_returnsNullWithoutDirective() {
        assertNull(ExecuteDirective.parse("I am running fine, nothing to check."))
    }

    @Test
    fun parse_extractsCommandFromMultilineResponse() {
        val response = """
            Let me check my current temperature.

            EXECUTE: vcgencmd measure_temp
        """.trimIndent()

        assertEquals("vcgencmd measure_temp", ExecuteDirective.parse(response))
    }

    @Test
    fun parse_stopsAtEndOfLine() {
        val response = "EXECUTE: df -h\nThat should show the free space."

        assertEquals("df -h", ExecuteDirective.parse(response))
    }

    @Test
    fun parse_takesFirstDirectiveOnly() {
        val response = "EXECUTE: uptime\nEXECUTE: df -h"

        assertEquals("uptime", ExecuteDirective.parse(response))
    }

    @Test
    fun parse_stripsWrappingBackticks() {
        assertEquals("ss -tlnp", ExecuteDirective.parse("EXECUTE: `ss -tlnp`"))
    }

    @Test
    fun parse_ignoresDirectiveWithNoCommand() {
        assertNull(ExecuteDirective.parse("EXECUTE:   "))
    }

    @Test
    fun strip_removesDirectiveLineAndCollapsesBlanks() {
        val response = """
            Checking the service.

            EXECUTE: systemctl status nginx

            I will report back.
        """.trimIndent()

        assertEquals(
            "Checking the service.\n\nI will report back.",
            ExecuteDirective.strip(response)
        )
    }

    @Test
    fun strip_leavesResponseWithoutDirectiveUntouched() {
        val response = "Disk usage is at 45%."

        assertEquals(response, ExecuteDirective.strip(response))
    }

    @Test
    fun strip_removesEveryDirective() {
        val response = "First.\nEXECUTE: a\nSecond.\nEXECUTE: b\nThird."

        assertEquals("First.\nSecond.\nThird.", ExecuteDirective.strip(response))
    }
}
