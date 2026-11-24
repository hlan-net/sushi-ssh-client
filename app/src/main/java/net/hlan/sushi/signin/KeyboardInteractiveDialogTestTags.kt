package net.hlan.sushi.signin

/** Compose test tags for [KeyboardInteractiveDialog]. */
object KeyboardInteractiveDialogTestTags {
    const val DIALOG = "keyboard_interactive_dialog"
    const val HOST = "keyboard_interactive_host"
    const val INSTRUCTION = "keyboard_interactive_instruction"
    const val CONTINUE = "keyboard_interactive_continue"
    const val CANCEL = "keyboard_interactive_cancel"

    /** The answer field for the server's prompt at [index], in the server's order. */
    fun field(index: Int): String = "keyboard_interactive_field_$index"
}
