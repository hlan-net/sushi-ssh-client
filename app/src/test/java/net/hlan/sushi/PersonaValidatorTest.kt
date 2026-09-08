package net.hlan.sushi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaValidatorTest {

    @Test fun validate_completePersonaHasNoWarnings() {
        val content = """
            # Sushi AI Persona

            ## System Identity
            web-server

            ## Personality
            Helpful and concise.
        """.trimIndent()
        val result = PersonaValidator.validate(content)
        assertFalse(result.hasWarnings)
        assertFalse(result.isEmpty)
        assertTrue(result.missingSections.isEmpty())
    }

    @Test fun validate_emptyContentIsFlaggedEmpty() {
        val result = PersonaValidator.validate("   \n  ")
        assertTrue(result.isEmpty)
        assertTrue(result.hasWarnings)
        assertEquals(PersonaValidator.REQUIRED_SECTIONS, result.missingSections)
    }

    @Test fun validate_missingPersonalitySection() {
        val content = """
            ## System Identity
            some-host
        """.trimIndent()
        val result = PersonaValidator.validate(content)
        assertFalse(result.isEmpty)
        assertTrue(result.hasWarnings)
        assertEquals(listOf("Personality"), result.missingSections)
    }

    @Test fun validate_headingMatchIsCaseInsensitive() {
        val content = """
            ## system identity
            host

            ## PERSONALITY
            Calm.
        """.trimIndent()
        assertFalse(PersonaValidator.validate(content).hasWarnings)
    }

    @Test fun validate_mentionInBodyDoesNotCountAsSection() {
        val content = """
            # Notes
            This file describes the System Identity and Personality of the host.
        """.trimIndent()
        val result = PersonaValidator.validate(content)
        assertEquals(PersonaValidator.REQUIRED_SECTIONS, result.missingSections)
    }
}
