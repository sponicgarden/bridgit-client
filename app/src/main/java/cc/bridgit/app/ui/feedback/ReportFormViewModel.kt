package cc.bridgit.app.ui.feedback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.feedback.FeedbackContext
import cc.bridgit.app.feedback.FeedbackError
import cc.bridgit.app.feedback.FeedbackLimits
import cc.bridgit.app.feedback.Issue
import cc.bridgit.app.feedback.IssueKind
import cc.bridgit.app.feedback.UploadState
import cc.bridgit.app.feedback.loadDownscaledJpeg
import org.json.JSONArray

/**
 * Report form state that must survive rotation (ui-spec 5.11): the last-screen capture,
 * picked screenshots, the import flag and the save itself. Drafts live in [FeedbackDrafts].
 */
class ReportFormViewModel : ViewModel() {
    class Picked(val id: Long, val jpeg: ByteArray, val thumb: Bitmap?)

    sealed class Outcome {
        data class Saved(val issue: Issue) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    var initialized = false
    lateinit var kind: IssueKind
    var lastScreen: ByteArray? = null
    var lastScreenThumb: Bitmap? = null
    var includeLastScreen = false
    var includeLog = true
    val picked = mutableListOf<Picked>()
    var nextId = 1L
    var importing = false
    var error: String? = null
    var logCount: Int = -1
    var pickedEntryExists = false

    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> get() = _submitting
    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> get() = _outcome

    fun consumeOutcome() { _outcome.value = null }

    /**
     * The upload worker usually sends a new issue within seconds. Until it does, the Issue saved
     * dialog says "not sent yet"; once the Bridgit team has it, the dialog shows its number (M0042).
     */
    private suspend fun awaitTeamNumber(app: BridgeApp, id: Long) {
        val sent = withTimeoutOrNull(AWAIT_TEAM_MS) {
            app.feedback.changed.first { withContext(Dispatchers.IO) { app.feedback.get(id) }?.ref != null }
            withContext(Dispatchers.IO) { app.feedback.get(id) }
        } ?: return
        val shown = _outcome.value
        if (shown is Outcome.Saved && shown.issue.id == id) _outcome.value = Outcome.Saved(sent)
    }

    private companion object { const val AWAIT_TEAM_MS = 30_000L }

    private val _imported = MutableStateFlow(0)
    /** Bumps when an import finishes, so whichever view is current re-renders. */
    val imported: StateFlow<Int> get() = _imported

    /**
     * Decodes and downscales picked images (FBK-07) in the ViewModel scope, so rotation
     * doesn't strand a half-done import. [overflow] = more were picked than slots left.
     */
    fun import(ctx: Context, uris: List<Uri>, overflow: Boolean, thumbEdgePx: Int) {
        importing = true
        viewModelScope.launch {
            var failed = false
            try {
                for (uri in uris) {
                    val loaded = withContext(Dispatchers.IO) {
                        loadDownscaledJpeg(ctx, uri)?.let { it to decodeThumb(it, thumbEdgePx) }
                    }
                    if (loaded == null) { failed = true; continue }
                    if (attachedCount() < FeedbackLimits.MAX_IMAGES) picked += Picked(nextId++, loaded.first, loaded.second)
                }
                error = when {
                    failed -> ctx.getString(R.string.feedback_screenshot_failed)
                    overflow -> ctx.getString(R.string.feedback_max_screenshots)
                    else -> null
                }
            } finally {
                importing = false
                _imported.value = _imported.value + 1
            }
        }
    }

    /** Last screen first (when kept), then picks in order, capped at 5 (FBK-04 item 6). */
    fun images(): List<ByteArray> = buildList {
        if (includeLastScreen) lastScreen?.let { add(it) }
        picked.forEach { if (size < FeedbackLimits.MAX_IMAGES) add(it.jpeg) }
    }

    fun attachedCount() = (picked.size + if (includeLastScreen && lastScreen != null) 1 else 0).coerceAtMost(FeedbackLimits.MAX_IMAGES)

    fun submit(app: BridgeApp, kind: IssueKind, text: String, noAutofix: Boolean, screen: String?, screenContext: String?, logEntryId: Long,
               applet: org.json.JSONObject? = null) {
        if (_submitting.value) return
        _submitting.value = true
        // Snapshot before the suspend so a toggle mid-save can't change what is sent.
        val images = images()
        val withLog = includeLog
        viewModelScope.launch {
            var saved: Issue? = null
            try {
                val issue = withContext(Dispatchers.IO) {
                    val ctx = FeedbackContext.build(app, screen, screenContext, "form")
                    if (logEntryId >= 0) ctx.put("log_entry_id", logEntryId)
                    val log = if (!withLog) JSONArray() else {
                        val recent = FeedbackContext.recentLog(app)
                        val already = (0 until recent.length()).any { recent.optJSONObject(it)?.optLong("id") == logEntryId }
                        val named = if (logEntryId >= 0 && !already) app.log.get(logEntryId) else null
                        if (named == null) recent else JSONArray().apply {
                            put(named.toJson(::isoAt, named.agentId?.let { app.agents.get(it)?.name } ?: named.agent))
                            for (i in 0 until recent.length()) put(recent.get(i))
                        }
                    }
                    app.feedback.create(kind, text, noAutofix, images, log, ctx, "form", applet = applet).also {
                        app.log.system("audit", "feedback", app.getString(when (kind) {
                            IssueKind.BUG -> R.string.feedback_filed_log_bug
                            IssueKind.FEATURE -> R.string.feedback_filed_log_feature
                            IssueKind.APPLET -> R.string.feedback_filed_log_applet
                        }))
                        FeedbackDrafts(app).clear(kind)
                    }
                }
                picked.clear()
                error = null
                _outcome.value = Outcome.Saved(issue)
                saved = issue
            } catch (e: FeedbackError) {
                _outcome.value = Outcome.Failed(
                    if (e.code == "rate_limited") app.getString(R.string.feedback_rate_limited)
                    else e.message ?: app.getString(R.string.feedback_save_failed))
            } catch (e: Exception) {
                _outcome.value = Outcome.Failed(app.getString(R.string.feedback_save_failed))
            } finally {
                _submitting.value = false
            }
            saved?.takeIf { it.upload == UploadState.PENDING }?.let { awaitTeamNumber(app, it.id) }
        }
    }
}
