package cc.bridgit.app.feedback

import org.json.JSONArray
import org.json.JSONObject

/** Issue kind: a bug or a feature request (section 11). Copied in concept from the Spotka reporter (D14). */
enum class IssueKind(val id: String) {
    BUG("bug"), FEATURE("feature");
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } }
}

/** Issue statuses (FBK-09, FBK-11a). */
enum class IssueStatus(val id: String) {
    NEW("new"), SEEN("seen"), IN_PROGRESS("in_progress"), FIXED("fixed"), WONT_FIX("wont_fix"),
    DUPLICATE("duplicate"), CONFIRMED("confirmed"), REOPENED("reopened");
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } }
}

/**
 * Who moves an issue: Bridgit itself (first developer read), a developer agent, the owner on the phone,
 * the Bridgit team through the feedback backend (FBK-14 status sync), or the agent that filed the
 * issue (FBK-12a follow-ups; it has the owner's Confirm / Still broken on its own issue).
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
    private fun asNew(s: IssueStatus) = if (s == IssueStatus.REOPENED) IssueStatus.NEW else s

    fun allowed(from: IssueStatus, to: IssueStatus, actor: Actor): Boolean {
        val f = asNew(from)
        return when (actor) {
            Actor.AUTO -> f == IssueStatus.NEW && to == IssueStatus.SEEN
            Actor.DEVELOPER -> when (f) {
                IssueStatus.NEW, IssueStatus.SEEN -> to in setOf(IssueStatus.IN_PROGRESS, IssueStatus.DUPLICATE, IssueStatus.WONT_FIX)
                IssueStatus.IN_PROGRESS -> to in setOf(IssueStatus.FIXED, IssueStatus.WONT_FIX)
                else -> false
            }
            // The filing agent's "works now" / "still broken" follow the owner's Confirm / Still broken (FBK-12a).
            Actor.OWNER, Actor.REPORTER -> f in setOf(IssueStatus.FIXED, IssueStatus.WONT_FIX, IssueStatus.DUPLICATE) &&
                to in setOf(IssueStatus.CONFIRMED, IssueStatus.REOPENED)
            // The team may move any issue the owner hasn't closed; it never closes or reopens one itself.
            Actor.TEAM -> f != IssueStatus.CONFIRMED && f != to && when (to) {
                IssueStatus.SEEN -> f == IssueStatus.NEW
                IssueStatus.IN_PROGRESS, IssueStatus.FIXED, IssueStatus.WONT_FIX, IssueStatus.DUPLICATE -> true
                else -> false
            }
        }
    }

    /**
     * FBK-14 status sync: the phone status a team status from the backend should become, or null
     * when it changes nothing ("new", unknown, already there, or a move the team may not make).
     */
    fun fromTeam(phone: IssueStatus, team: String?): IssueStatus? {
        val to = when (team) {
            "seen" -> IssueStatus.SEEN; "in_progress" -> IssueStatus.IN_PROGRESS; "fixed" -> IssueStatus.FIXED
            "wont_fix" -> IssueStatus.WONT_FIX; "duplicate" -> IssueStatus.DUPLICATE
            else -> return null
        }
        return to.takeIf { allowed(phone, it, Actor.TEAM) }
    }

    /** Statuses a developer agent may set from [from], for error messages and the guide. */
    fun nextForDeveloper(from: IssueStatus) = IssueStatus.values().filter { allowed(from, it, Actor.DEVELOPER) }

    /** FBK-12a follow-up outcomes: "works" closes the issue, "still_broken" reopens it. */
    fun outcome(id: String?): IssueStatus? = when (id) {
        "works" -> IssueStatus.CONFIRMED; "still_broken" -> IssueStatus.REOPENED; else -> null
    }

    /** The outcomes the filing agent may send now (empty until someone says it's fixed, won't fix or duplicate). */
    fun outcomesForReporter(from: IssueStatus): List<String> = listOf("works", "still_broken")
        .filter { allowed(from, outcome(it)!!, Actor.REPORTER) }

    /** The owner is told (FBK-11) when an issue becomes one of these. */
    fun notifiesOwner(to: IssueStatus) = to == IssueStatus.FIXED || to == IssueStatus.WONT_FIX || to == IssueStatus.DUPLICATE

    /** Labels for My issues (ui-spec 5.12). */
    fun label(s: IssueStatus) = when (s) {
        IssueStatus.NEW -> "New"; IssueStatus.SEEN -> "Seen by agent"; IssueStatus.IN_PROGRESS -> "In progress"
        IssueStatus.FIXED -> "Fixed — check it"; IssueStatus.WONT_FIX -> "Won't fix"; IssueStatus.DUPLICATE -> "Duplicate"
        IssueStatus.CONFIRMED -> "Closed"; IssueStatus.REOPENED -> "Reopened"
    }
}

/**
 * One line of an issue's history. [kind] is "status" (a move, or the issue being filed) or "comment"
 * (a follow-up that doesn't change the status). [actor] is null on entries from before v3.
 */
data class HistoryItem(val at: Long, val status: IssueStatus, val by: String, val note: String?,
                       val id: Long = 0, val kind: String = HistoryKind.STATUS, val actor: Actor? = null) {
    fun toJson(iso: (Long) -> String) = JSONObject().put("at", iso(at)).put("kind", kind).put("status", status.id).put("by", by)
        .put("note", note ?: JSONObject.NULL)
}

object HistoryKind {
    const val STATUS = "status"
    const val COMMENT = "comment"
}

/** One issue. [images] are file names under filesDir/feedback. [log] holds already-redacted LOG-01 entries. */
data class Issue(
    val id: Long,
    val kind: IssueKind,
    val description: String,
    val noAutofix: Boolean,
    val status: IssueStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val captureSource: String,        // "form" | "agent"
    val agentId: String?,             // filed by (agent issues)
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
     * has the issue; null until it is sent. The local [id] is never shown as a number.
     */
    val ref: String? get() = remoteId?.takeIf { it > 0 }?.let { IssueRef.of(captureSource, it) }
    /** How a sentence names this issue: its team number, or "(not sent yet)". */
    val number: String get() = ref ?: if (upload == UploadState.OFF || upload == UploadState.SKIPPED) "(not sent)" else "(not sent yet)"
    /** Whether agent [agentId] filed this issue through the API. */
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
 * One team-wide number per issue: a letter for the source and the Bridgit team's issue id,
 * zero-padded to 4. M = the owner, from the app's form; C = caught automatically (crashes);
 * A = an agent. Same rule as bridgit-cloud/feedback/src/ref.mjs.
 */
object IssueRef {
    fun letter(captureSource: String) = when (captureSource) {
        "form" -> "M"
        "agent", "agent_api" -> "A"
        else -> "C"
    }
    fun of(captureSource: String, teamId: Long) = letter(captureSource) + teamId.toString().padStart(4, '0')
}

/** The name the Bridgit team's moves carry on the phone. */
const val TEAM_BY = "Bridgit team"

/** FBK-14 upload states, stored per issue and per history entry. */
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
    const val FOLLOWUPS_PER_HOUR_PER_ISSUE = 20
    const val NOTE = 500
    const val MAX_IMAGES = 5
    const val MAX_DESCRIPTION = 10_000
    const val IMAGE_EDGE = 1600
    const val IMAGE_MAX_BYTES = 1_000_000
    const val LOG_ENTRIES = 50
    const val CONTEXT_MAX = 500
}
