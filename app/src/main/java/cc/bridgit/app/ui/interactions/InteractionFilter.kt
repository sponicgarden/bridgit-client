package cc.bridgit.app.ui.interactions

/**
 * Inbox filter (A0405: one type per pill group). The kind group (All · Questions · Notices) and the
 * status group (Open) are separate; the inbox shows rows that match both.
 */
data class InteractionFilter(val kind: Kind, val openOnly: Boolean) {
    enum class Kind { ALL, QUESTIONS, NOTICES }

    fun <T> apply(rows: List<T>, isQuestion: (T) -> Boolean, waiting: (T) -> Boolean): List<T> = rows.filter {
        when (kind) { Kind.ALL -> true; Kind.QUESTIONS -> isQuestion(it); Kind.NOTICES -> !isQuestion(it) } &&
            (!openOnly || waiting(it))
    }

    /** True when either group narrows the inbox. */
    val narrowed get() = kind != Kind.ALL || openOnly
}
