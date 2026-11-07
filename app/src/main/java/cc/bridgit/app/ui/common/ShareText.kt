package cc.bridgit.app.ui.common

/**
 * Plain-text layout for anything sent through the share sheet or a mail app (owner, 4 Nov 2026: make it as
 * clean as plain text allows: white space, indents, clear sections). Mail apps drop styling, so the
 * structure comes from blank lines, an underlined title, indented lines and dividers.
 */
object ShareText {
    const val SIGN_OFF = "—\nSent from Bridgit · bridgit.cc"
    // Line characters are about twice as wide as letters in mail fonts, so lines stay short enough for a phone.
    const val RULE = "══════════════════"

    /** A title with an underline about as wide as the title in a proportional mail font. */
    fun title(text: String) = text + "\n" + "─".repeat((text.length * 11 / 20).coerceIn(6, 18))

    /**
     * A prompt meant for an agent, sent to a person: a short note for them on top, then the prompt between two
     * clear dividers, unchanged. Pasting the whole message into an agent still works.
     */
    fun prompt(heading: String, note: String, prompt: String, pasteInto: String = "your control agent"): String = buildString {
        append(title(heading)).append("\n\n")
        append(note.trim()).append("\n\n")
        append("Paste everything between the two lines into ").append(pasteInto).append(":\n\n")
        append(RULE).append("\n\n")
        append(prompt.trim()).append("\n\n")
        append(RULE).append("\n\n")
        append(SIGN_OFF)
    }
}
