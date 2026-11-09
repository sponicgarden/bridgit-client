package cc.bridgit.app.ui.feedback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.databinding.FeedbackSheetLogBinding
import cc.bridgit.app.feedback.FeedbackLimits
import cc.bridgit.app.feedback.IssueKind
import cc.bridgit.app.feedback.IssueStatus
import cc.bridgit.app.log.LogEntry
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.ui.common.LogRowAdapter
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import org.json.JSONObject
import com.google.android.material.R as M

/** FBK-01: handles the two overflow items from res/menu/feedback_overflow.xml. Returns false for other items. */
fun Fragment.handleFeedbackMenu(item: MenuItem, screen: String, screenContext: () -> String? = { null }): Boolean {
    val kind = when (item.itemId) {
        R.id.feedback_report_bug -> IssueKind.BUG
        R.id.feedback_request_feature -> IssueKind.FEATURE
        else -> return false
    }
    openReport(this, kind, screen, screenContext())
    return true
}

/** The icon and name an issue kind is shown with (My issues, the issue screen). */
val IssueKind.iconRes get() = when (this) {
    IssueKind.BUG -> R.drawable.feedback_ic_bug; IssueKind.FEATURE -> R.drawable.feedback_ic_lightbulb; IssueKind.APPLET -> R.drawable.ic_nav_applets
}
val IssueKind.nameRes get() = when (this) {
    IssueKind.BUG -> R.string.feedback_kind_bug; IssueKind.FEATURE -> R.string.feedback_kind_feature; IssueKind.APPLET -> R.string.feedback_kind_applet
}

/**
 * FBK-05 drafts: description and Don't auto-fix per kind, saved on every keystroke in
 * private SharedPreferences "feedback_drafts". Screenshots are never stored.
 */
class FeedbackDrafts(context: Context) {
    private val prefs = context.getSharedPreferences("feedback_drafts", Context.MODE_PRIVATE)

    fun description(kind: IssueKind): String = prefs.getString("${kind.id}.description", "") ?: ""
    fun noAutofix(kind: IssueKind): Boolean = prefs.getBoolean("${kind.id}.no_autofix", false)

    fun save(kind: IssueKind, description: String, noAutofix: Boolean) {
        prefs.edit().putString("${kind.id}.description", description).putBoolean("${kind.id}.no_autofix", noAutofix).apply()
    }

    fun clear(kind: IssueKind) {
        prefs.edit().remove("${kind.id}.description").remove("${kind.id}.no_autofix").remove("${kind.id}.fields").apply()
    }

    /** A0178: the applet request's other boxes as typed (name, devices, data used, portal), keyed by [AppletForm] field. */
    fun appletFields(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString("${IssueKind.APPLET.id}.fields", null) ?: "{}")
        o.keys().asSequence().associateWith { o.optString(it) }
    }.getOrDefault(emptyMap())

    fun saveAppletFields(fields: Map<String, String>) {
        prefs.edit().putString("${IssueKind.APPLET.id}.fields", JSONObject(fields).toString()).apply()
    }
}

/**
 * A0178: the applet form's boxes into the `applet` object. The description box is what it does
 * (up to its 2,000-character limit; the full text stays in the description); devices and data
 * used are comma-separated.
 */
object AppletForm {
    const val NAME = "name"
    const val DEVICES = "devices"
    const val DATA = "data_used"
    const val PORTAL = "portal_shows"

    fun build(description: String, fields: Map<String, String>): JSONObject = JSONObject().apply {
        description.trim().take(cc.bridgit.app.feedback.AppletRequest.WHAT_IT_DOES).trim().takeIf { it.isNotEmpty() }?.let { put("what_it_does", it) }
        fields[NAME]?.trim()?.takeIf { it.isNotEmpty() }?.let { put("name", it) }
        fields[DEVICES]?.let { cc.bridgit.app.feedback.AppletRequest.split(it) }?.takeIf { it.length() > 0 }?.let { put("devices", it) }
        fields[DATA]?.let { cc.bridgit.app.feedback.AppletRequest.split(it) }?.takeIf { it.length() > 0 }?.let { put("data_used", it) }
        fields[PORTAL]?.trim()?.takeIf { it.isNotEmpty() }?.let { put("portal_shows", it) }
    }
}

/** Status chip look (ui-spec 5.12). The label is always shown: colour is never the only signal. */
object StatusChip {
    fun style(v: TextView, s: IssueStatus) {
        val ctx = v.context
        val (bg, fg) = when (s) {
            IssueStatus.SEEN -> M.attr.colorPrimaryContainer to M.attr.colorOnPrimaryContainer
            IssueStatus.IN_PROGRESS -> M.attr.colorSecondaryContainer to M.attr.colorOnSecondaryContainer
            IssueStatus.FIXED -> R.attr.colorSuccessContainer to R.attr.colorOnSuccessContainer
            IssueStatus.REOPENED -> M.attr.colorErrorContainer to M.attr.colorOnErrorContainer
            IssueStatus.CONFIRMED -> 0 to M.attr.colorOnSurfaceVariant
            else -> M.attr.colorSurfaceContainerHigh to M.attr.colorOnSurfaceVariant
        }
        v.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(8).toFloat()
            if (bg == 0) {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(ctx.dp(1), ctx.themeColor(M.attr.colorOutline))
            } else setColor(ctx.themeColor(bg))
        }
        v.setTextColor(ctx.themeColor(fg))
        v.text = cc.bridgit.app.feedback.StatusMachine.label(s)
    }
}

/** The log entries an issue carries are stored as LOG-01 JSON; this turns them back into rows. */
object LogJson {
    fun entry(o: JSONObject): LogEntry {
        fun s(k: String) = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotEmpty() }
        fun l(k: String) = if (o.isNull(k) || !o.has(k)) null else o.optLong(k)
        return LogEntry(
            id = o.optLong("id"), ts = s("ts")?.let { LogQuery.iso(it) } ?: 0L,
            channel = s("channel") ?: "audit", level = s("level") ?: "info", kind = s("kind") ?: "",
            agentId = s("agent_id"), agent = s("agent"), sourceIp = s("source_ip"), method = s("method"),
            endpoint = s("endpoint"), params = o.optJSONObject("params"), http = l("http")?.toInt(),
            result = s("result"), bytesIn = l("bytes_in"), bytesOut = l("bytes_out"), durationMs = l("duration_ms"),
            requestId = s("request_id"), message = o.optString("message", ""), data = o.optJSONObject("data"),
        )
    }

    /** The same entries FeedbackContext.recentLog attaches (FBK-04 item 7). */
    val RECENT = LogQuery(channels = listOf("audit", "diagnostic"), limit = FeedbackLimits.LOG_ENTRIES)
}

/** Decodes a JPEG for a thumbnail no longer than [edgePx] on its long edge. */
fun decodeThumb(bytes: ByteArray, edgePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edgePx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Decodes an image file for display, no longer than [edgePx] on its long edge. */
fun decodeFileThumb(path: String, edgePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edgePx) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * "Preview" from the form (FBK-04 item 7): the redacted entries the issue would carry,
 * as Activity rows (shared LogRowAdapter), plus the entry the owner picked, if any.
 */
class LogPreviewSheet : BottomSheetDialogFragment() {
    private var _b: FeedbackSheetLogBinding? = null
    private val app get() = requireActivity().application as BridgeApp

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FeedbackSheetLogBinding.inflate(inflater, container, false)
        return _b!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val picked = requireArguments().getLong(ARG_ENTRY, -1)
        val adapter = LogRowAdapter(app.agents) { }
        _b!!.list.adapter = adapter
        viewLifecycleOwner.lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val recent = runCatching { app.log.query(LogJson.RECENT) }.getOrDefault(emptyList())
                val extra = if (picked >= 0 && recent.none { it.id == picked }) app.log.get(picked) else null
                listOfNotNull(extra) + recent
            }
            val b = _b ?: return@launch
            adapter.submitList(entries)
            b.empty.isVisible = entries.isEmpty()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    companion object {
        private const val ARG_ENTRY = "entry"
        fun newInstance(logEntryId: Long) = LogPreviewSheet().apply { arguments = bundleOf(ARG_ENTRY to logEntryId) }
    }
}
