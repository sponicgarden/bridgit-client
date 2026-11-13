package cc.bridgit.app.core.spending

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Budget periods (G5.2): hour, day, week and month, aligned to the boundary in the rule document's fixed
 * time zone. A period id is its kind and its start (`day:2026-11-11`, `week:2026-11-09` for the Monday it
 * begins on, `month:2026-11`, `hour:2026-11-11T05Z` in UTC so a repeated local hour is not one id). Ids of
 * one kind sort in time order as text, which is how a transfer's `from_period` is compared.
 *
 * It also decides **when a period may open** ([roll]). Ending something early is safe; starting a period
 * early is not. A period opens only from the journal's own time ([Journal.Tx.now]): the monotonic clock
 * unbroken since a time the journal accepted. The wall clock alone starts nothing, except the very first
 * period of a first journal, where there is no earlier period to protect.
 */
object PeriodClock {
    val KINDS = listOf("hour", "day", "week", "month")

    fun at(kind: String, zone: String, ms: Long): Journal.Period {
        val t = Instant.ofEpochMilli(ms).atZone(ZoneId.of(zone))
        val (id, start, end) = when (kind) {
            "hour" -> t.withZoneSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS).let { Triple("hour:${it.toLocalDate()}T${"%02d".format(it.hour)}Z", it, it.plusHours(1)) }
            "day" -> day(t).let { Triple("day:${it.toLocalDate()}", it, day(it.plusDays(1).plusHours(12))) }
            "week" -> day(t.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).let { Triple("week:${it.toLocalDate()}", it, day(it.plusWeeks(1).plusHours(12))) }
            "month" -> day(t.withDayOfMonth(1)).let { Triple("month:${it.toLocalDate().toString().take(7)}", it, day(it.plusMonths(1).plusHours(12))) }
            else -> throw IllegalArgumentException("Unknown period kind")
        }
        return Journal.Period(id, start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
    }

    fun kindOf(periodId: String): String = periodId.substringBefore(':')

    /** The period after [period]. */
    fun next(kind: String, zone: String, period: Journal.Period): Journal.Period = at(kind, zone, period.endsMs)

    private fun day(t: ZonedDateTime): ZonedDateTime = t.toLocalDate().atStartOfDay(t.zone)

    /** What [roll] found for one budget. */
    enum class Standing {
        /** The recorded period is open and the journal's time is inside it. */
        CURRENT,

        /** A period is recorded, but the device has rebooted since the journal last had a time: the old count stands (G5.2). */
        UNANCHORED,

        /** No period is open: fully used. After a reset, or on a first journal with no time to go by. */
        NOT_OPEN,
    }

    /**
     * Opens what may open for [budget] now, in the caller's transaction, and says where the budget stands.
     * - No period yet, first journal: the first portion's period, if a rule document named this install for it.
     * - No period yet, after a reset: an anchored time only **names** the current period, which stays fully
     *   used; the first that opens is the one after it (G5.2 rule 3).
     * - A recorded period: the period the journal's time falls in opens when it begins at or after the end
     *   of the recorded one. Periods that passed while the device was off are not opened and give nothing.
     */
    internal fun roll(tx: Journal.Tx, budget: String, kind: String, zone: String): Standing {
        val state = tx.state
        val zero = { unit: String -> Money.zero(unit) }
        var current = state.periods[budget]
        val now = tx.now()
        if (current == null) {
            if (state.origin == Journal.Origin.FIRST) {
                if (budget !in state.budgets) return Standing.NOT_OPEN
                val at = if (state.time == null) tx.clock.wallMs() else now ?: return Standing.NOT_OPEN
                tx.openPeriod(budget, at(kind, zone, at), Journal.Basis.FIRST_PORTION, zero(unitOf(tx, budget)))
                return Standing.CURRENT
            }
            if (now == null || state.time?.anchored != true) return Standing.NOT_OPEN
            tx.openPeriod(budget, at(kind, zone, now), Journal.Basis.NAMED, zero(unitOf(tx, budget)))
            return Standing.NOT_OPEN
        }
        if (now == null) return if (current.full) Standing.NOT_OPEN else Standing.UNANCHORED
        if (now >= current.ends) {
            val ranFull = current.openedBoot == tx.clock.bootCount().toLong() && tx.clock.elapsedMs() - current.openedElapsed >= current.ends - current.starts
            if (state.time?.anchored == true || ranFull) {
                tx.openPeriod(budget, at(kind, zone, now), Journal.Basis.NEXT_PERIOD, zero(current.unit ?: unitOf(tx, budget)))
                current = state.periods.getValue(budget)
            } else {
                // The recorded period is over by the journal's own time, which is not good enough to open the next: the old count stands.
                return if (current.full) Standing.NOT_OPEN else Standing.UNANCHORED
            }
        }
        return if (current.full) Standing.NOT_OPEN else Standing.CURRENT
    }

    private fun unitOf(tx: Journal.Tx, budget: String): String =
        tx.state.rulesDigest?.let { tx.document(it) }?.let { RuleDocument.parse(it).budgets[budget]?.unit } ?: throw JournalRefused("no_rule_document")
}

/**
 * Time anchors (G5.2, SA-G2): a time from outside the device that lets a period open after a reboot.
 *
 * 1. **Where it may come from.** A metered source: the cloud's signed time, the provider's signed time, or
 *    the provider's own response date on the direct path (on the header path the answer is the Worker's, so
 *    its date is the cloud's word). A value-transfer source: a chain's block time or a timestamp inside a
 *    payload the provider signed; never an HTTP date and never the cloud. The cloud's signed time is refused
 *    in this build: there is no production key ([CloudAllowanceKey]).
 * 2. **It must agree with the device's clock**, within [SpendingLimits.ANCHOR_SKEW_MS]. An anchor the wall
 *    clock contradicts starts nothing.
 * 3. **One anchor opens one period, and only a later one**: [PeriodClock.roll] and the journal see to that.
 * 4. **A stored anchor is a floor, not a licence.** After a reboot the journal has no time until a fresh
 *    anchor; one earlier than the last accepted changes nothing.
 */
object TimeAnchor {
    enum class Why { SOURCE_NOT_ALLOWED, NOT_VERIFIED, CLOCK_DISAGREES, OLDER_THAN_STORED }

    sealed class Result {
        object Accepted : Result()
        data class Ignored(val why: Why) : Result()
    }

    /** Rule 1. [custody] and [valueTransfer] are the source's, [timeSource] is a word of `vocab/instruments.json`. */
    fun allowed(valueTransfer: Boolean, custody: String, timeSource: String): Boolean = allowed(valueTransfer, custody, timeSource, CloudAllowanceKey.PUBLIC)

    internal fun allowed(valueTransfer: Boolean, custody: String, timeSource: String, cloudKey: ByteArray): Boolean = when (timeSource) {
        "provider_signed" -> true
        "chain_block" -> valueTransfer
        "provider_date" -> !valueTransfer && custody == "device"
        "cloud_signed" -> !valueTransfer && cloudKey.isNotEmpty()
        else -> false
    }

    /**
     * Offers the journal a time [t] that [timeSource] gave for [instrument]. [verified] is the adapter's
     * word that the time is the provider's own (its signature checked, or read over the authenticated
     * connection to the provider itself).
     */
    internal fun offer(tx: Journal.Tx, instrument: String, valueTransfer: Boolean, custody: String, timeSource: String, t: Long, verified: Boolean): Result {
        if (!allowed(valueTransfer, custody, timeSource)) return Result.Ignored(Why.SOURCE_NOT_ALLOWED)
        if (!verified) return Result.Ignored(Why.NOT_VERIFIED)
        val wall = tx.clock.wallMs()
        if (t < 0 || Math.abs(Math.subtractExact(t, wall)) > SpendingLimits.ANCHOR_SKEW_MS) return Result.Ignored(Why.CLOCK_DISAGREES)
        tx.state.time?.floor?.let { if (t < it) return Result.Ignored(Why.OLDER_THAN_STORED) }
        tx.append(mapOf("kind" to "anchor", "source" to timeSource, "t" to t, "instrument" to Journal.text(instrument)))
        return Result.Accepted
    }
}
