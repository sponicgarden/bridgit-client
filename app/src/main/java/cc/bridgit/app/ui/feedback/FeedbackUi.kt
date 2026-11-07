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
import cc.bridgit.app.feedback.ReportKind
import cc.bridgit.app.feedback.ReportStatus
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
        R.id.feedback_report_bug -> ReportKind.BUG
        R.id.feedback_request_feature -> ReportKind.FEATURE
        else -> return false
    }
    openReport(this, kind, screen, screenContext())
    return true
}

/**
 * FBK-05 drafts: description and Don't auto-fix per kind, saved on every keystroke in
 * private SharedPreferences "feedback_drafts". Screenshots are never stored.
 */
class FeedbackDrafts(context: Context) {
    private val prefs = context.getSharedPreferences("feedback_drafts", Context.MODE_PRIVATE)

    fun description(kind: ReportKind): String = prefs.getString("${kind.id}.description", "") ?: ""
    fun noAutofix(kind: ReportKind): Boolean = prefs.getBoolean("${kind.id}.no_autofix", false)

    fun save(kind: ReportKind, description: String, noAutofix: Boolean) {
        prefs.edit().putString("${kind.id}.description", description).putBoolean("${kind.id}.no_autofix", noAutofix).apply()
    }

    fun clear(kind: ReportKind) {
        prefs.edit().remove("${kind.id}.description").remove("${kind.id}.no_autofix").apply()
    }
}

/** Status chip look (ui-spec 5.12). The label is always shown: colour is never the only signal. */
object StatusChip {
    fun style(v: TextView, s: ReportStatus) {
        val ctx = v.context
        val (bg, fg) = when (s) {
            ReportStatus.SEEN -> M.attr.colorPrimaryContainer to M.attr.colorOnPrimaryContainer
            ReportStatus.IN_PROGRESS -> M.attr.colorSecondaryContainer to M.attr.colorOnSecondaryContainer
            ReportStatus.FIXED -> R.attr.colorSuccessContainer to R.attr.colorOnSuccessContainer
            ReportStatus.REOPENED -> M.attr.colorErrorContainer to M.attr.colorOnErrorContainer
            ReportStatus.CONFIRMED -> 0 to M.attr.colorOnSurfaceVariant
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

/** The log entries a report carries are stored as LOG-01 JSON; this turns them back into rows. */
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
 * "Preview" from the form (FBK-04 item 7): the redacted entries the report would carry,
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
