package cc.bridgit.app.alerts

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** How often the owner is emailed about agents calling the phone (Settings › Email alerts). Off by default. */
enum class AlertFrequency(val id: String) {
    EVERY_ACTION("every"), HOURLY("hourly"), DAILY("daily"), WEEKLY("weekly"), NONE("none");
    val isDigest get() = this == HOURLY || this == DAILY || this == WEEKLY
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } ?: NONE }
}

/** One agent API call waiting to be emailed. Copied from the (already redacted) request log entry. */
data class AlertEvent(
    val id: Long = 0,
    val ts: Long,
    val agentId: String,
    val agent: String,
    val method: String,
    val endpoint: String,
    val http: Int,
    val message: String,
)

data class AlertMessage(val subject: String, val text: String, val idempotency: String)

/**
 * When alerts go out. Pure, so the unit tests drive it with a fixed clock and zone.
 *
 * - Every action: sent as soon as a call arrives, but at most one email per [COALESCE_MS];
 *   calls in between are counted into the next email.
 * - Digests: windows end on the hour (hourly), at [DIGEST_HOUR]:00 local (daily), or
 *   Monday [DIGEST_HOUR]:00 local (weekly). A digest holds every call from before the most
 *   recent boundary, so a phone that was off for a while sends one combined digest. Empty
 *   windows send nothing.
 */
object AlertPlanner {
    const val COALESCE_MS = 10 * 60_000L
    const val DIGEST_HOUR = 8

    /** The most recent window boundary at or before [now]. */
    fun boundary(freq: AlertFrequency, now: Long, zone: ZoneId): Long {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        val b = when (freq) {
            AlertFrequency.HOURLY -> t.truncatedTo(ChronoUnit.HOURS)
            AlertFrequency.DAILY -> t.truncatedTo(ChronoUnit.DAYS).withHour(DIGEST_HOUR).let { if (it.isAfter(t)) it.minusDays(1) else it }
            AlertFrequency.WEEKLY -> t.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .withHour(DIGEST_HOUR).let { if (it.isAfter(t)) it.minusWeeks(1) else it }
            else -> throw IllegalArgumentException("$freq has no windows")
        }
        return b.toInstant().toEpochMilli()
    }

    /** The first window boundary strictly after [now]. */
    fun nextBoundary(freq: AlertFrequency, now: Long, zone: ZoneId): Long {
        val b = ZonedDateTime.ofInstant(Instant.ofEpochMilli(boundary(freq, now, zone)), zone)
        return when (freq) {
            AlertFrequency.HOURLY -> b.plusHours(1)
            AlertFrequency.DAILY -> b.plusDays(1)
            else -> b.plusWeeks(1)
        }.toInstant().toEpochMilli()
    }

    /** What to send now: the events and the window they cover, or null if nothing is due. */
    data class Batch(val events: List<AlertEvent>, val from: Long, val until: Long)

    fun due(freq: AlertFrequency, pending: List<AlertEvent>, lastSentAt: Long, now: Long, zone: ZoneId): Batch? {
        if (pending.isEmpty()) return null
        return when {
            freq == AlertFrequency.NONE -> null
            freq == AlertFrequency.EVERY_ACTION ->
                if (now - lastSentAt >= COALESCE_MS) Batch(pending, pending.minOf { it.ts }, now) else null
            else -> {
                val until = boundary(freq, now, zone)
                val ready = pending.filter { it.ts < until }
                if (ready.isEmpty()) null else Batch(ready, ready.minOf { it.ts }, until)
            }
        }
    }

    /** When the dispatcher should look again, or null if it can wait for the next call to arrive. */
    fun nextCheck(freq: AlertFrequency, pending: List<AlertEvent>, lastSentAt: Long, now: Long, zone: ZoneId): Long? {
        if (pending.isEmpty() || freq == AlertFrequency.NONE) return null
        if (freq == AlertFrequency.EVERY_ACTION) return maxOf(now, lastSentAt + COALESCE_MS)
        // Anything from before the current boundary is due now; otherwise the next boundary.
        return if (pending.any { it.ts < boundary(freq, now, zone) }) now else nextBoundary(freq, now, zone)
    }
}

/** Builds the email text. Plain text only; contents are the log's redacted fields, never params or media. */
object AlertComposer {
    const val MAX_LISTED_CALLS = 50
    const val MAX_ENDPOINTS = 10
    const val MAX_TEXT = 15_000

    fun compose(freq: AlertFrequency, batch: AlertPlanner.Batch, zone: ZoneId, phone: String = "your phone"): AlertMessage {
        val events = batch.events.sortedBy { it.ts }
        val byAgent = events.groupBy { it.agentId }
        val agents = byAgent.size
        val calls = events.size
        val oneName = events.last().agent
        val plural = { n: Int, w: String -> if (n == 1) "1 $w" else "$n ${w}s" }
        val subject = when (freq) {
            AlertFrequency.EVERY_ACTION ->
                if (agents == 1) "Bridgit: $oneName used $phone" + (if (calls > 1) " (${plural(calls, "call")})" else "")
                else "Bridgit: ${plural(agents, "agent")} used $phone (${plural(calls, "call")})"
            else -> "Bridgit ${label(freq)} summary: ${plural(calls, "call")} by ${plural(agents, "agent")}"
        }
        val sb = StringBuilder()
        if (freq == AlertFrequency.EVERY_ACTION) {
            sb.append(if (agents == 1) "$oneName called $phone's Bridgit API." else "${plural(agents, "agent")} called $phone's Bridgit API.").append("\n\n")
            for (e in events.takeLast(MAX_LISTED_CALLS)) sb.append(line(e, zone)).append('\n')
            if (calls > MAX_LISTED_CALLS) sb.append("…and ${calls - MAX_LISTED_CALLS} earlier calls.\n")
            sb.append("\nBridgit sends at most one of these emails every ${AlertPlanner.COALESCE_MS / 60_000} minutes; later calls are counted into the next one.\n")
        } else {
            sb.append("Agent activity on $phone, ${range(batch.from, batch.until, zone)}.\n")
            for ((_, list) in byAgent.entries.sortedByDescending { it.value.size }) {
                val failed = list.count { it.http >= 400 }
                sb.append("\n").append(list.last().agent).append(" — ").append(plural(list.size, "call"))
                if (failed > 0) sb.append(", ").append(failed).append(" failed")
                sb.append(", ").append(time(list.first().ts, zone)).append("–").append(time(list.last().ts, zone)).append('\n')
                val top = list.groupingBy { "${it.method} ${it.endpoint}" }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                for ((ep, n) in top.take(MAX_ENDPOINTS)) sb.append("  ").append(n).append(" × ").append(ep).append('\n')
                if (top.size > MAX_ENDPOINTS) sb.append("  …and ${top.size - MAX_ENDPOINTS} other endpoints\n")
            }
        }
        sb.append("\nSee every call in Bridgit › Activity. To stop an agent, remove it in Bridgit › Agents.")
        val text = if (sb.length <= MAX_TEXT) sb.toString() else sb.take(MAX_TEXT - 1).toString() + "…"
        return AlertMessage(subject.take(150), text, "${freq.id}-${events.first().id}-${events.last().id}")
    }

    fun label(freq: AlertFrequency) = when (freq) {
        AlertFrequency.HOURLY -> "hourly"; AlertFrequency.DAILY -> "daily"; AlertFrequency.WEEKLY -> "weekly"; else -> ""
    }

    private fun line(e: AlertEvent, zone: ZoneId): String {
        val status = if (e.http >= 400) " (failed, ${e.http})" else ""
        val msg = e.message.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
        return "${time(e.ts, zone)}  ${e.agent}: ${e.method} ${e.endpoint}$status$msg"
    }

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.US)
    private fun time(ts: Long, zone: ZoneId) = timeFmt.format(Instant.ofEpochMilli(ts).atZone(zone))
    private fun range(from: Long, until: Long, zone: ZoneId) =
        "${dayFmt.format(Instant.ofEpochMilli(from).atZone(zone))} – ${dayFmt.format(Instant.ofEpochMilli(until).atZone(zone))}"
}

/** Where pending events and the last-sent time live. SQLite in the app, memory in tests. */
interface AlertOutbox {
    fun add(e: AlertEvent)
    fun pending(): List<AlertEvent>
    fun remove(ids: Collection<Long>)
    fun clear()
    var lastSentAt: Long
}

/** Sends one email. Implemented by [AlertRelay] (bridgit.cc). */
interface AlertSender {
    enum class Result {
        SENT,
        /** Network or server trouble: keep the batch and try again later. */
        RETRY,
        /** The address was unsubscribed or never confirmed: stop until the owner sets it up again. */
        GONE,
        /** The server refused this message itself: drop it so it can't block later ones. */
        REJECTED,
    }
    fun send(message: AlertMessage): Result
}

/**
 * Records agent calls and sends whatever [AlertPlanner] says is due. Called from the WorkManager
 * worker; [record] runs on the log writer thread for every agent request.
 */
class AlertDispatcher(
    private val outbox: AlertOutbox,
    private val sender: AlertSender,
    private val frequency: () -> AlertFrequency,
    private val confirmed: () -> Boolean,
    private val onGone: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val phone: () -> String = { "your phone" },
) {
    sealed class Outcome {
        /** Nothing left to do until another call arrives (or [at], when set). */
        data class Idle(val at: Long?) : Outcome()
        object Retry : Outcome()
    }

    /** True if the event was kept (alerts are on). */
    fun record(e: AlertEvent): Boolean {
        if (frequency() == AlertFrequency.NONE) return false
        outbox.add(e)
        return true
    }

    /** When the next run should happen, for scheduling right after [record]. */
    fun nextCheck(): Long? = AlertPlanner.nextCheck(frequency(), outbox.pending(), outbox.lastSentAt, clock(), zone())

    @Synchronized
    fun run(): Outcome {
        val freq = frequency()
        if (freq == AlertFrequency.NONE) { outbox.clear(); return Outcome.Idle(null) }
        if (!confirmed()) return Outcome.Idle(null)
        val now = clock()
        val z = zone()
        val pending = outbox.pending()
        val batch = AlertPlanner.due(freq, pending, outbox.lastSentAt, now, z)
            ?: return Outcome.Idle(AlertPlanner.nextCheck(freq, pending, outbox.lastSentAt, now, z))
        return when (sender.send(AlertComposer.compose(freq, batch, z, phone()))) {
            AlertSender.Result.SENT, AlertSender.Result.REJECTED -> {
                outbox.remove(batch.events.map { it.id })
                outbox.lastSentAt = now
                val left = outbox.pending()
                Outcome.Idle(AlertPlanner.nextCheck(freq, left, now, now, z))
            }
            AlertSender.Result.RETRY -> Outcome.Retry
            AlertSender.Result.GONE -> { onGone(); Outcome.Idle(null) }
        }
    }
}
