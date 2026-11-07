package cc.bridgit.app.interact

/** Persisted completion wins over stale intents and stack refreshes. Re-asking needs a new interaction. */
object CardLifecycle {
    fun mayOpenFromNotification(status: IStatus): Boolean = status.open || status == IStatus.EXPIRED

    fun includeInStack(waiting: Boolean, focused: Boolean, historyOnly: Boolean): Boolean =
        if (historyOnly) focused else waiting || focused
}
