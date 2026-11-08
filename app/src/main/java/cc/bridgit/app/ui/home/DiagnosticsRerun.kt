package cc.bridgit.app.ui.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A request to replay Device Diagnostics on Home, the same as the scene's Rerun button
 * (agent foreground target `device_diagnostics`, report A0052). Home plays one full run per request,
 * whether it is already on screen or opens later. A request left unseen for [TTL_MS] expires,
 * so an old agent request never replays on some later visit.
 */
object DiagnosticsRerun {
    const val TTL_MS = 5 * 60_000L
    private val requested = MutableStateFlow(0L)
    /** The time of the latest request (0 = none); Home collects it. */
    val requests: StateFlow<Long> get() = requested
    private var handled = 0L

    @Synchronized fun request(now: Long = System.currentTimeMillis()) { requested.value = maxOf(now, requested.value + 1) }

    /** True once for each new, unexpired request. */
    @Synchronized fun take(now: Long = System.currentTimeMillis()): Boolean {
        val at = requested.value
        if (at == 0L || at == handled) return false
        handled = at
        return now - at <= TTL_MS
    }
}
