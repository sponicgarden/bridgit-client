package cc.bridgit.app.feedback

import org.json.JSONArray
import org.json.JSONObject

/** Bug report or feature request (section 11). Copied in concept from the Spotka reporter (D14). */
enum class ReportKind(val id: String) {
    BUG("bug"), FEATURE("feature");
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } }
}

/** Report statuses (FBK-09, FBK-11a). */
enum class ReportStatus(val id: String) {
    NEW("new"), SEEN("seen"), IN_PROGRESS("in_progress"), FIXED("fixed"), WONT_FIX("wont_fix"),
    DUPLICATE("duplicate"), CONFIRMED("confirmed"), REOPENED("reopened");
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } }
}

/**
 * Who moves a report: Bridgit itself (first developer read), a developer agent, the owner on the phone,
 * the Bridgit team through the feedback backend (FBK-14 status sync), or the agent that filed the
 * report (FBK-12a follow-ups; it has the owner's Confirm / Still broken on its own report).
 */
enum class Actor(val id: String) {
    AUTO("auto"), DEVELOPER("developer"), OWNER("owner"), TEAM("team"), REPORTER("reporter");
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } }
}

/**
 * FBK-11a status machine. Pure, unit-tested row by row.
 * `reopened` behaves as `new`.
 */
object StatusMachine {
    private fun asNew(s: ReportStatus) = if (s == ReportStatus.REOPENED) ReportStatus.NEW else s

    fun allowed(from: ReportStatus, to: ReportStatus, actor: Actor): Boolean {
        val f = asNew(from)
        return when (actor) {
            Actor.AUTO -> f == ReportStatus.NEW && to == ReportStatus.SEEN
            Actor.DEVELOPER -> when (f) {
                ReportStatus.NEW, ReportStatus.SEEN -> to in setOf(ReportStatus.IN_PROGRESS, ReportStatus.DUPLICATE, ReportStatus.WONT_FIX)
                ReportStatus.IN_PROGRESS -> to in setOf(ReportStatus.FIXED, ReportStatus.WONT_FIX)
                else -> false
            }
            // The filing agent's "works now" / "still broken" follow the owner's Confirm / Still broken (FBK-12a).
            Actor.OWNER, Actor.REPORTER -> f in setOf(ReportStatus.FIXED, ReportStatus.WONT_FIX, ReportStatus.DUPLICATE) &&
                to in setOf(ReportStatus.CONFIRMED, ReportStatus.REOPENED)
            // The team may move any report the owner hasn't closed; it never closes or reopens one itself.
            Actor.TEAM -> f != ReportStatus.CONFIRMED && f != to && when (to) {
                ReportStatus.SEEN -> f == ReportStatus.NEW
                ReportStatus.IN_PROGRESS, ReportStatus.FIXED, ReportStatus.WONT_FIX, ReportStatus.DUPLICATE -> true
                else -> false
            }
        }
    }

    /**
     * FBK-14 status sync: the phone status a team status from the backend should become, or null
     * when it changes nothing ("new", unknown, already there, or a move the team may not make).
     */
    fun fromTeam(phone: ReportStatus, team: String?): ReportStatus? {
        val to = when (team) {
            "seen" -> ReportStatus.SEEN; "in_progress" -> ReportStatus.IN_PROGRESS; "fixed" -> ReportStatus.FIXED
            "wont_fix" -> ReportStatus.WONT_FIX; "duplicate" -> ReportStatus.DUPLICATE
            else -> return null
        }
        return to.takeIf { allowed(phone, it, Actor.TEAM) }
    }

    /** Statuses a developer agent may set from [from], for error messages and the guide. */
    fun nextForDeveloper(from: ReportStatus) = ReportStatus.values().filter { allowed(from, it, Actor.DEVELOPER) }

    /** FBK-12a follow-up outcomes: "works" closes the report, "still_broken" reopens it. */
    fun outcome(id: String?): ReportStatus? = when (id) {
        "works" -> ReportStatus.CONFIRMED; "still_broken" -> ReportStatus.REOPENED; else -> null
    }

    /** The outcomes the filing agent may send now (empty until someone says it's fixed, won't fix or duplicate). */
    fun outcomesForReporter(from: ReportStatus): List<String> = listOf("works", "still_broken")
        .filter { allowed(from, outcome(it)!!, Actor.REPORTER) }

    /** The owner is told (FBK-11) when a report becomes one of these. */
    fun notifiesOwner(to: ReportStatus) = to == ReportStatus.FIXED || to == ReportStatus.WONT_FIX || to == ReportStatus.DUPLICATE

    /** Labels for My reports (ui-spec 5.12). */
    fun label(s: ReportStatus) = when (s) {
        ReportStatus.NEW -> "New"; ReportStatus.SEEN -> "Seen by agent"; ReportStatus.IN_PROGRESS -> "In progress"
        ReportStatus.FIXED -> "Fixed — check it"; ReportStatus.WONT_FIX -> "Won't fix"; ReportStatus.DUPLICATE -> "Duplicate"
        ReportStatus.CONFIRMED -> "Closed"; ReportStatus.REOPENED -> "Reopened"
    }
}

/**
 * One line of a report's history. [kind] is "status" (a move, or the report being filed) or "comment"
 * (a follow-up that doesn't change the status). [actor] is null on entries from before v3.
 */
data class HistoryItem(val at: Long, val status: ReportStatus, val by: String, val note: String?,
                       val id: Long = 0, val kind: String = HistoryKind.STATUS, val actor: Actor? = null) {
    fun toJson(iso: (Long) -> String) = JSONObject().put("at", iso(at)).put("kind", kind).put("status", status.id).put("by", by)
        .put("note", note ?: JSONObject.NULL)
}

object HistoryKind {
    const val STATUS = "status"
    const val COMMENT = "comment"
}

/** One report. [images] are file names under filesDir/feedback. [log] holds already-redacted LOG-01 entries. */
data class Report(
    val id: Long,
    val kind: ReportKind,
    val description: String,
    val noAutofix: Boolean,
    val status: ReportStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val captureSource: String,        // "form" | "agent"
    val agentId: String?,             // filed by (agent reports)
    val agent: String?,
    val context: JSONObject,          // FBK-06
    val log: JSONArray,
    val images: List<String>,
    val lastBy: String?,
    val history: List<HistoryItem> = emptyList(),
    /** FBK-14 upload to the Bridgit team: pending, sent, failed, off (switch was off) or skipped (filed before uploads existed). */
    val upload: String = UploadState.PENDING,
    val remoteId: Long? = null,
    val uploadedAt: Long = 0,
    val uploadError: String? = null,
) {
    /**
     * The team-wide number, e.g. M0023 (the owner's form) or A0023 (an agent), once the Bridgit team
     * has the report; null until it is sent. The local [id] is never shown as a number.
     */
    val ref: String? get() = remoteId?.takeIf { it > 0 }?.let { ReportRef.of(captureSource, it) }
    /** How a sentence names this report: its team number, or "(not sent yet)". */
    val number: String get() = ref ?: if (upload == UploadState.OFF || upload == UploadState.SKIPPED) "(not sent)" else "(not sent yet)"
    /** Whether agent [agentId] filed this report through the API. */
    fun filedBy(agentId: String) = captureSource == "agent" && this.agentId == agentId

    /** The Bridgit team's latest note (FBK-14 status sync), if any. */
    val teamNote: String? get() = history.lastOrNull { it.by == TEAM_BY && it.note != null }?.note

    val firstLine get() = description.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120) ?: ""

    fun summaryJson(iso: (Long) -> String): JSONObject = JSONObject()
        .put("id", id).put("ref", ref ?: JSONObject.NULL).put("number", ref ?: JSONObject.NULL)
        .put("team_id", remoteId?.takeIf { it > 0 } ?: JSONObject.NULL).put("kind", kind.id).put("status", status.id)
        .put("first_line", firstLine).put("created_at", iso(createdAt)).put("updated_at", iso(updatedAt))
        .put("no_autofix", noAutofix).put("capture_source", captureSource)
        .put("filed_by", agent ?: "owner").put("image_count", images.size).put("last_updated_by", lastBy ?: JSONObject.NULL)
        .put("sent_to_team", upload)
}

/**
 * One team-wide number per report: a letter for the source and the Bridgit team's report id,
 * zero-padded to 4. M = the owner, from the app's form; C = caught automatically (crashes);
 * A = an agent. Same rule as bridgit-cloud/feedback/src/ref.mjs.
 */
object ReportRef {
    fun letter(captureSource: String) = when (captureSource) {
        "form" -> "M"
        "agent", "agent_api" -> "A"
        else -> "C"
    }
    fun of(captureSource: String, teamId: Long) = letter(captureSource) + teamId.toString().padStart(4, '0')
}

/** The name the Bridgit team's moves carry on the phone. */
const val TEAM_BY = "Bridgit team"

/** FBK-14 upload states, stored per report and per history entry. */
object UploadState {
    const val PENDING = "pending"
    const val SENT = "sent"
    const val FAILED = "failed"
    const val OFF = "off"
    const val SKIPPED = "skipped"
}

/** Limits from FBK-13 and FBK-04/07. */
object FeedbackLimits {
    const val FORM_PER_HOUR_PER_KIND = 10
    const val API_PER_HOUR_PER_AGENT = 50
    const val FOLLOWUPS_PER_HOUR_PER_REPORT = 20
    const val NOTE = 500
    const val MAX_IMAGES = 5
    const val MAX_DESCRIPTION = 10_000
    const val IMAGE_EDGE = 1600
    const val IMAGE_MAX_BYTES = 1_000_000
    const val LOG_ENTRIES = 50
    const val CONTEXT_MAX = 500
}
