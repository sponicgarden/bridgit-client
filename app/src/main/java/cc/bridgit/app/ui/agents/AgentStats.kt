package cc.bridgit.app.ui.agents

import cc.bridgit.app.core.Agent
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.log.LogStore
import java.util.Calendar

/** Today's numbers for one agent, read from the log (UI-12, AGT-14). Call off the main thread. */
data class AgentToday(val requests: Int, val errors: Int, val bytesOut: Long)

object AgentStats {
    const val ACTIVE_MS = 10 * 60 * 1000L
    private const val ROTATED_WINDOW_MS = 7L * 24 * 3600 * 1000

    fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun todayRequests(id: String) = LogQuery(channels = listOf("audit"), agentIds = listOf(id),
        kinds = listOf("request"), sinceTs = startOfToday())

    fun requestsToday(log: LogStore, id: String): Int = runCatching { log.count(todayRequests(id)) }.getOrDefault(0)

    fun today(log: LogStore, id: String): AgentToday = runCatching {
        val q = todayRequests(id)
        val requests = log.count(q)
        val errors = log.count(q.copy(result = "error"))
        var bytes = 0L
        log.forEach(q) { bytes += it.bytesOut ?: 0 }
        AgentToday(requests, errors, bytes)
    }.getOrDefault(AgentToday(0, 0, 0))

    fun isActive(a: Agent, now: Long = System.currentTimeMillis()) = a.lastUsedAt > 0 && now - a.lastUsedAt <= ACTIVE_MS

    /** Key rotated in the last 7 days and not used since (ui-spec 5.3). */
    fun waitingForNewPrompt(a: Agent, now: Long = System.currentTimeMillis()) =
        a.keyCreatedAt > a.createdAt && a.lastUsedAt == 0L && now - a.keyCreatedAt <= ROTATED_WINDOW_MS
}
