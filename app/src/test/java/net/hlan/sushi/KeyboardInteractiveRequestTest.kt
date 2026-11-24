package net.hlan.sushi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The server's texts reach the "Server sign-in" dialog as plain, single-line text. */
class KeyboardInteractiveRequestTest {

    @Test
    fun terminalEscapesAndLineBreaks_areRemovedFromServerText() {
        val request = KeyboardInteractiveRequest.from(
            hostLabel = "otp.hlan.net:22",
            isJumpHost = false,
            name = "\u001B[31mDuo\u001B[0m",
            instruction = "line one\r\nline two\t",
            prompts = arrayOf("\u0007Verification\ncode: "),
            echo = booleanArrayOf(false)
        )

        assertFalse(request.name.any { it.isISOControl() })
        assertEquals("[31mDuo [0m", request.name)
        assertEquals("line one line two", request.instruction)
        assertEquals("Verification code:", request.prompts.single().text)
    }

    @Test
    fun nullNameAndInstruction_becomeBlank() {
        val request = KeyboardInteractiveRequest.from("h:22", false, null, null, arrayOf("Code:"), booleanArrayOf(true))

        assertEquals("", request.name)
        assertEquals("", request.instruction)
    }

    /** Hiding an answer that could have been shown costs nothing; showing one that should be hidden leaks it. */
    @Test
    fun missingEchoFlag_hidesTheAnswer() {
        val request = KeyboardInteractiveRequest.from("h:22", false, "", "", arrayOf("A:", "B:"), booleanArrayOf(true))

        assertEquals(listOf(true, false), request.prompts.map { it.echo })
    }
}
