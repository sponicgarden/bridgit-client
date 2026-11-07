package cc.bridgit.app.remoteview

import kotlinx.coroutines.delay

/** Follow only a live owner-issued setup link, including while the browser covers the app. */
object RemoteViewLink {
    suspend fun awaitClaim(
        expiresAt: Long,
        eligible: () -> Boolean,
        refresh: suspend () -> Boolean,
        now: () -> Long = System::currentTimeMillis,
        pause: suspend (Long) -> Unit = { delay(it) }
    ) {
        while (eligible() && now() < expiresAt) {
            pause(minOf(3_000L, expiresAt - now()).coerceAtLeast(1L))
            if (!eligible() || now() >= expiresAt) return
            if (refresh()) return
        }
    }
}
