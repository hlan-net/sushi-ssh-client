package net.hlan.sushi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SushiConfigTest {

    // --- parseLogDir -----------------------------------------------------

    @Test fun parseLogDir_readsValueUnderLoggingSection() {
        val config = """
            [logging]
            log_dir = /mnt/logs

            [safety]
            # custom_blocked =
        """.trimIndent()
        assertEquals("/mnt/logs", SushiConfig.parseLogDir(config))
    }

    @Test fun parseLogDir_defaultTildePath() {
        assertEquals("~/.sushi_logs", SushiConfig.parseLogDir("log_dir = ~/.sushi_logs"))
    }

    @Test fun parseLogDir_toleratesNoSpacesAroundEquals() {
        assertEquals("/var/log/sushi", SushiConfig.parseLogDir("log_dir=/var/log/sushi"))
    }

    @Test fun parseLogDir_stripsSurroundingQuotes() {
        assertEquals("/mnt/my logs", SushiConfig.parseLogDir("log_dir = \"/mnt/my logs\""))
    }

    @Test fun parseLogDir_ignoresCommentedKey() {
        assertNull(SushiConfig.parseLogDir("# log_dir = /commented/out"))
        assertNull(SushiConfig.parseLogDir("; log_dir = /commented/out"))
    }

    @Test fun parseLogDir_missingKeyReturnsNull() {
        assertNull(SushiConfig.parseLogDir("[persona]\nname = Sushi"))
    }

    @Test fun parseLogDir_emptyValueReturnsNull() {
        assertNull(SushiConfig.parseLogDir("log_dir ="))
    }

    @Test fun parseLogDir_ignoresKeyOutsideLoggingSection() {
        val config = """
            [persona]
            log_dir = /wrong/section

            [logging]
            log_dir = /correct
        """.trimIndent()
        assertEquals("/correct", SushiConfig.parseLogDir(config))
    }

    @Test fun parseLogDir_honoursKeyBeforeAnySection() {
        assertEquals("/early", SushiConfig.parseLogDir("log_dir = /early\n\n[persona]\nname = x"))
    }

    @Test fun parseLogDir_ignoresKeyInOtherSectionWithNoLoggingSection() {
        assertNull(SushiConfig.parseLogDir("[safety]\nlog_dir = /nope"))
    }

    // --- shellQuotePath --------------------------------------------------

    @Test fun shellQuotePath_keepsSlashAfterTildeUnquoted() {
        // The slash right after ~ must stay unquoted or the shell won't expand the tilde.
        assertEquals("~/'.sushi_logs'", SushiConfig.shellQuotePath("~/.sushi_logs"))
    }

    @Test fun shellQuotePath_bareTildeStaysBare() {
        assertEquals("~", SushiConfig.shellQuotePath("~"))
    }

    @Test fun shellQuotePath_absolutePathFullyQuoted() {
        assertEquals("'/mnt/logs'", SushiConfig.shellQuotePath("/mnt/logs"))
    }

    @Test fun shellQuotePath_rootFullyQuoted() {
        assertEquals("'/'", SushiConfig.shellQuotePath("/"))
    }

    @Test fun shellQuotePath_quotesSpaces() {
        assertEquals("'/mnt/my logs'", SushiConfig.shellQuotePath("/mnt/my logs"))
    }

    @Test fun shellQuotePath_escapesSingleQuotes() {
        assertEquals("'/mnt/o'\\''brien'", SushiConfig.shellQuotePath("/mnt/o'brien"))
    }
}
