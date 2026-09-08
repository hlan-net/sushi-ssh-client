package net.hlan.sushi

/**
 * Pure validation for `SUSHI.md` persona content, used by [PersonaEditorActivity] to warn before
 * saving. Kept dependency-free so the rules can be unit-tested directly.
 *
 * These are warnings, not hard blocks: a persona missing a section still works (the app falls back
 * to a default identity), but flagging it helps users avoid accidentally saving an empty or
 * truncated file.
 */
object PersonaValidator {

    /** Markdown section headings a well-formed persona is expected to contain. */
    val REQUIRED_SECTIONS = listOf("System Identity", "Personality")

    data class Result(val isEmpty: Boolean, val missingSections: List<String>) {
        val hasWarnings: Boolean get() = isEmpty || missingSections.isNotEmpty()
    }

    fun validate(content: String): Result {
        if (content.isBlank()) {
            return Result(isEmpty = true, missingSections = REQUIRED_SECTIONS)
        }
        val headings = content.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("#") }
            .map { it.trimStart('#').trim() }
            .toList()
        val missing = REQUIRED_SECTIONS.filter { required ->
            headings.none { it.equals(required, ignoreCase = true) }
        }
        return Result(isEmpty = false, missingSections = missing)
    }
}
