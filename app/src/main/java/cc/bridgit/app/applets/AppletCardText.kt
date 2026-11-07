package cc.bridgit.app.applets

import android.content.Context
import android.text.format.DateUtils
import cc.bridgit.app.R
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The words of the Installed applets card and the budget editor (docs/design/installed-applets.md, owner copy of
 * 5 Nov 2026), kept apart from the views so they can be tested: plurals, money, times, readable labels for limits
 * and actions, and never a raw id (a watch's, an action's or a limit's).
 */
class AppletCardText(private val ctx: Context, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val res get() = ctx.resources

    enum class Tone { NORMAL, SUCCESS, WARNING, ERROR, QUIET }

    /** "$0.47" (two decimals, the device's decimal separator). */
    fun money(usd: Double): String = "$" + String.format(Locale.getDefault(), "%.2f", if (usd.isFinite()) usd else 0.0)

    fun dot(a: String, b: String) = res.getString(R.string.applets_dot, a, b)

    // ---- activity -------------------------------------------------------------------------------------------------

    /** "1 check · Last check at 3:00 PM" (a running total: there's no per-day count yet, so no "today"). */
    fun checksLine(checks: Int, lastAt: Long?, now: Long = System.currentTimeMillis()): String = when {
        checks <= 0 && (lastAt == null || lastAt <= 0) -> res.getString(R.string.applets_no_checks_yet)
        checks <= 0 -> dot(res.getString(R.string.applets_no_checks), lastCheck(lastAt, now))
        // A count with no time (the time isn't kept across a reinstall): just the count, never "2 checks · No check yet".
        lastAt == null || lastAt <= 0 -> res.getQuantityString(R.plurals.applets_checks, checks, checks)
        else -> dot(res.getQuantityString(R.plurals.applets_checks, checks, checks), lastCheck(lastAt, now))
    }

    fun lastCheck(lastAt: Long?, now: Long = System.currentTimeMillis()): String {
        if (lastAt == null || lastAt <= 0) return res.getString(R.string.applets_no_check_yet)
        val day = Instant.ofEpochMilli(lastAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = time(lastAt)
        return when (day) {
            today -> res.getString(R.string.applets_last_check_at, time)
            today.minusDays(1) -> res.getString(R.string.applets_last_check_yesterday, time)
            else -> res.getString(R.string.applets_last_check_on, date(lastAt), time)
        }
    }

    /** The watch's own count line ("Pot boiling over once today."), else "2 changes" / "No changes yet". */
    fun tallyLine(today: String?, matches: Int): String = today?.takeIf { it.isNotBlank() }
        ?: if (matches <= 0) res.getString(R.string.applets_no_changes) else res.getQuantityString(R.plurals.applets_changes, matches, matches)

    /** A watch's readable label, or "Check 2" when it has none (never its id). */
    fun watchLabel(label: String?, ids: Collection<String>, index: Int): String =
        label?.trim()?.takeIf { it.isNotEmpty() && it !in ids } ?: res.getString(R.string.applets_check_n, index + 1)

    // ---- budget ---------------------------------------------------------------------------------------------------

    /** Limits in hour, day, week, month order; the same period keeps its package order. */
    fun <T> byPeriod(items: List<T>, per: (T) -> String): List<T> =
        items.withIndex().sortedWith(compareBy({ AppletBudget.PERIODS.indexOf(per(it.value)).let { i -> if (i < 0) 1 else i } }, { it.index })).map { it.value }

    private fun periodWord(per: String) = res.getString(when (per) {
        "hour" -> R.string.applets_period_hour; "week" -> R.string.applets_period_week; "month" -> R.string.applets_period_month; else -> R.string.applets_period_day })

    /** "This hour", "Today", …; a repeated period becomes "Today · limit 1", "Today · limit 2". [pers] in display order. */
    fun periodHeaders(pers: List<String>): List<String> = numbered(pers, ::periodWord) { w, n -> res.getString(R.string.applets_period_numbered, w, n) }

    /** The editor's field labels: "Daily limit" (or "Daily agent maximum"), numbered for a repeated period. */
    fun fieldLabels(pers: List<String>, ceiling: Boolean): List<String> = numbered(pers, { per ->
        res.getString(if (ceiling) when (per) { "hour" -> R.string.applets_ceiling_hour; "week" -> R.string.applets_ceiling_week; "month" -> R.string.applets_ceiling_month; else -> R.string.applets_ceiling_day }
            else when (per) { "hour" -> R.string.applets_limit_hour; "week" -> R.string.applets_limit_week; "month" -> R.string.applets_limit_month; else -> R.string.applets_limit_day })
    }) { w, n -> res.getString(R.string.applets_numbered_label, w, n) }

    private fun numbered(pers: List<String>, word: (String) -> String, both: (String, Int) -> String): List<String> {
        val seen = HashMap<String, Int>()
        return pers.map { per ->
            val n = (seen[per] ?: 0) + 1; seen[per] = n
            if (pers.count { it == per } > 1) both(word(per), n) else word(per)
        }
    }

    /** "$1.00 a day". */
    fun perAmount(usd: Double, per: String): String = res.getString(when (per) {
        "hour" -> R.string.applets_amount_hour; "week" -> R.string.applets_amount_week; "month" -> R.string.applets_amount_month; else -> R.string.applets_amount_day }, money(usd))

    data class LimitBlock(val header: String, val used: String, val topUp: String?, val fraction: Float, val forecast: String, val tone: Tone, val description: String)

    /** One limit of the last sync: used of the limit with its top-ups, the meter and the forecast. */
    fun limitBlock(header: String, l: JSONObject): LimitBlock {
        val top = l.optDouble("top_up_usd", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0
        val effective = l.optDouble("usd", 0.0).let { if (it.isFinite()) it else 0.0 } + top
        val used = l.optDouble("used_usd", 0.0).let { if (it.isFinite()) it else 0.0 }
        val forecast = l.optDouble("forecast_usd", Double.NaN).takeIf { it.isFinite() }
        val fraction = if (effective > 0) (used / effective).coerceIn(0.0, 1.0).toFloat() else 0f
        // Only the limit's own numbers colour its meter: used up, or forecast over it.
        val tone = when {
            effective > 0 && used >= effective -> Tone.ERROR
            effective > 0 && forecast != null && forecast > effective -> Tone.WARNING
            else -> Tone.NORMAL
        }
        val usedText = res.getString(R.string.applets_used_of, money(used), money(effective))
        val forecastText = when {
            forecast == null -> res.getString(R.string.applets_forecast_unavailable)
            l.optBoolean("estimate") -> res.getString(R.string.applets_forecast_estimate, money(forecast))
            else -> res.getString(R.string.applets_forecast, money(forecast))
        }
        return LimitBlock(header, usedText, if (top > 0) res.getString(R.string.applets_includes_top_up, money(top)) else null, fraction, forecastText, tone,
            "$header: $usedText")
    }

    /** The overall line under the limits (the server's state), and for a used-up budget what happens now. */
    fun status(state: String?, resetsAt: Long?): Triple<String, String?, Tone> = when (state) {
        "ok" -> Triple(res.getString(R.string.applets_on_track), null, Tone.SUCCESS)
        "warning" -> Triple(res.getString(R.string.applets_may_run_out), null, Tone.WARNING)
        "out" -> Triple(res.getString(R.string.applets_used_up),
            resetsAt?.takeIf { it > 0 }?.let { res.getString(R.string.applets_used_up_until, dateTime(it)) } ?: res.getString(R.string.applets_used_up_unknown), Tone.ERROR)
        else -> Triple(res.getString(R.string.applets_status_unavailable), null, Tone.QUIET)
    }

    /** "Agents can't change this budget.", or "Agents can change…" and the maximum per limit ($100 when none is set). */
    fun agentLines(mayChange: Boolean, limits: List<Pair<String, String>>, ceiling: JSONObject?): List<String> {
        if (!mayChange) return listOf(res.getString(R.string.applets_agents_cant))
        val max = limits.joinToString(" · ") { (id, per) ->
            perAmount(ceiling?.optDouble(id, Double.NaN)?.takeIf { it.isFinite() } ?: AppletBudget.MAX_USD, per)
        }
        return listOfNotNull(res.getString(R.string.applets_agents_can), max.takeIf { it.isNotEmpty() }?.let { res.getString(R.string.applets_agent_maximum, it) })
    }

    fun configuredLimits(limits: List<AppletBudget.Limit>): String =
        res.getString(R.string.applets_limits_configured, byPeriod(limits) { it.per }.joinToString(" · ") { perAmount(it.usd, it.per) })

    /** "This device: $0.00 today · …", or with [device] (a remote view is showing) "Google Pixel 2 XL: $0.00 today · …". */
    fun thisDevice(today: Double, month: Double, device: String? = null) =
        if (device != null) res.getString(R.string.applets_named_device, device, money(today), money(month)) else res.getString(R.string.applets_this_device, money(today), money(month))

    fun lastUpdated(at: Long) = res.getString(R.string.applets_last_updated, dateTime(at))

    // ---- schedule -------------------------------------------------------------------------------------------------

    /** "Every 10 minutes". */
    fun every(s: Int): String = when {
        s > 0 && s % 86_400 == 0 -> res.getQuantityString(R.plurals.applets_every_days, s / 86_400, s / 86_400)
        s > 0 && s % 3600 == 0 -> res.getQuantityString(R.plurals.applets_every_hours, s / 3600, s / 3600)
        s > 0 && s % 60 == 0 -> res.getQuantityString(R.plurals.applets_every_minutes, s / 60, s / 60)
        else -> res.getQuantityString(R.plurals.applets_every_seconds, s, s)
    }

    /** "10 minutes". */
    fun duration(s: Int): String = when {
        s > 0 && s % 86_400 == 0 -> res.getQuantityString(R.plurals.applets_days, s / 86_400, s / 86_400)
        s > 0 && s % 3600 == 0 -> res.getQuantityString(R.plurals.applets_hours, s / 3600, s / 3600)
        s > 0 && s % 60 == 0 -> res.getQuantityString(R.plurals.applets_minutes, s / 60, s / 60)
        else -> res.getQuantityString(R.plurals.applets_seconds, s, s)
    }

    /**
     * "Allowed: 5 minutes–1 day · About $0.01 a day", or "Fixed interval: 30 seconds · …". The cost is the backend's
     * projection in its own limit's period (a day's when there is one); never converted between periods.
     */
    fun allowedLine(minS: Int, maxS: Int, projected: JSONObject?, limits: List<Pair<String, String>>, free: Boolean): String {
        val range = if (minS == maxS) res.getString(R.string.applets_fixed_interval, duration(minS)) else res.getString(R.string.applets_allowed, duration(minS), duration(maxS))
        // Design pass: no cost estimate says nothing, rather than "Daily cost unavailable".
        return cost(projected, limits, free)?.let { dot(range, it) } ?: range
    }

    fun cost(projected: JSONObject?, limits: List<Pair<String, String>>, free: Boolean): String? {
        if (free) return res.getString(R.string.applets_free)
        val known = limits.mapNotNull { (id, per) -> projected?.optDouble(id, Double.NaN)?.takeIf { it.isFinite() }?.let { per to it } }
        val pick = known.firstOrNull { it.first == "day" } ?: byPeriod(known) { it.first }.firstOrNull()
            ?: return null
        // A flat zero for a paid action is no estimate yet (design pass): say nothing rather than "About $0.00".
        if (pick.second == 0.0) return null
        // Below a cent, "About $0.00 a day" reads as free or broken: say "Under $0.01 a day".
        if (pick.second > 0 && pick.second < 0.005) return res.getString(R.string.applets_under_cost, perAmount(0.01, pick.first))
        return res.getString(R.string.applets_about_cost, perAmount(pick.second, pick.first))
    }

    fun places(n: Int): String = res.getQuantityString(R.plurals.applets_places, n, n)

    /**
     * Readable names for [actionIds] (in their order): the watches' labels joined, else the applet's name when it has
     * a single action, else "Scheduled action 2". [watchLabels] maps an action id to its watches' labels.
     */
    fun actionLabels(actionIds: List<String>, watchLabels: Map<String, List<String>>, appletName: String): Map<String, String> =
        actionIds.withIndex().associate { (i, id) ->
            val labels = watchLabels[id].orEmpty().map { it.trim() }.filter { it.isNotEmpty() && it != id }.distinct()
            id to when {
                labels.isNotEmpty() -> labels.joinToString(", ")
                actionIds.size == 1 && appletName.isNotBlank() -> appletName
                else -> res.getString(R.string.applets_scheduled_action_n, i + 1)
            }
        }

    // ---- recent changes -------------------------------------------------------------------------------------------

    /** Who changed it: You, the agent's name, An agent, the applet (its package default) or Bridgit. */
    fun actor(byKind: String?, byName: String?): String = when (byKind) {
        "owner" -> res.getString(R.string.applets_by_you)
        "agent" -> byName?.trim()?.takeIf { it.isNotEmpty() } ?: res.getString(R.string.applets_by_agent)
        "package" -> res.getString(R.string.applets_by_applet)
        "admin" -> res.getString(R.string.applets_by_bridgit)
        else -> byName?.trim()?.takeIf { it.isNotEmpty() && byKind != null } ?: res.getString(R.string.applets_by_agent)
    }

    /**
     * The backend's plain-words line with this applet's action ids put in words ("Changed main from every 10 min…"
     * → "Changed Kitchen cleanliness from every 10 min…") and limit ids as their field names. Whole words only, and
     * only this applet's ids. "Budget changed" when there's nothing readable.
     */
    fun changeSummary(summary: String?, actionLabels: Map<String, String>, limitLabels: Map<String, String> = emptyMap()): String {
        var s = summary?.trim().orEmpty()
        if (s.isEmpty()) return res.getString(R.string.applets_budget_changed)
        for ((id, label) in (actionLabels + limitLabels).entries.sortedByDescending { it.key.length }) {
            if (id.isBlank()) continue
            s = Regex("(?<![\\w-])" + Regex.escape(id) + "(?![\\w-])").replace(s) { label.replace("$", "\\$") }
        }
        return s
    }

    // ---- times ----------------------------------------------------------------------------------------------------

    fun time(ms: Long): String = DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_TIME)
    fun date(ms: Long): String = DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    fun dateTime(ms: Long): String = DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

    companion object {
        /**
         * An amount the owner typed: the device's decimal separator or a point, an optional "$". Null when it isn't
         * a number from $0.01 to $100.00.
         */
        fun parseAmount(text: String?, locale: Locale = Locale.getDefault()): Double? {
            val t = text?.trim()?.removePrefix("$")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val sep = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
            val norm = t.replace(sep, '.').replace(',', '.')
            if (!Regex("^\\d+(\\.\\d{0,4})?$|^\\.\\d{1,4}$").matches(norm)) return null
            val v = norm.toDoubleOrNull() ?: return null
            return v.takeIf { it.isFinite() && it >= AppletBudget.MIN_USD - 1e-9 && it <= AppletBudget.MAX_USD + 1e-9 }
        }

        /** "2" or "0.5" in the device's decimal separator, for a field. */
        fun amountText(usd: Double?, locale: Locale = Locale.getDefault()): String = usd?.takeIf { it.isFinite() }?.let {
            if (it % 1.0 == 0.0) it.toLong().toString()
            else java.math.BigDecimal(it).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                .replace('.', java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator)
        } ?: ""

        fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)
    }
}
