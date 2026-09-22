package net.hlan.sushi.conversation

/**
 * One row of the conversation transcript as the screen shows it.
 *
 * Carries no resource strings: a [HostSwitch] holds the two host labels and the renderer
 * decides how to word it, so the same state serves the legacy dialog and the Compose screen.
 */
sealed interface TranscriptItem {

    /**
     * A user prompt and the system's reply. [response] grows while output streams in and is
     * replaced by the final answer when the turn completes; [id] is what lets the streaming
     * chunks find their row. A raw-mode turn ([isRaw]) shows [prompt] as a shell command.
     */
    data class Turn(
        val id: Long,
        val prompt: String,
        val response: String,
        val isRaw: Boolean = false
    ) : TranscriptItem

    /**
     * Marks that the conversation moved from [previousHost] to [newHost] mid-transcript, so
     * the bubbles above it are not mistaken for the new system's answers. A null label is a
     * host whose name is not known.
     */
    data class HostSwitch(
        val previousHost: String?,
        val newHost: String?
    ) : TranscriptItem
}
