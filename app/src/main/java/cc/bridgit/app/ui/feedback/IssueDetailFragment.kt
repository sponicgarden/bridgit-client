package cc.bridgit.app.ui.feedback

import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.databinding.FeedbackHistoryRowBinding
import cc.bridgit.app.databinding.FragmentIssueDetailBinding
import cc.bridgit.app.feedback.Actor
import cc.bridgit.app.feedback.HistoryKind
import cc.bridgit.app.feedback.Issue
import cc.bridgit.app.feedback.UploadState
import cc.bridgit.app.feedback.IssueKind
import cc.bridgit.app.feedback.IssueStatus
import cc.bridgit.app.feedback.StatusMachine
import cc.bridgit.app.log.LogEntry
import cc.bridgit.app.ui.common.Fmt
import cc.bridgit.app.ui.common.LogRowAdapter
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.R as M

/**
 * Issue detail (UI-20, FBK-11, ui-spec 5.12): the text, screenshots (tap to view
 * bigger), Don't auto-fix flag, the attached log (collapsed), a context summary and
 * the status history. Fixed / won't fix / duplicate get Still broken · Confirm.
 */
class IssueDetailFragment : Fragment() {
    private var _b: FragmentIssueDetailBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private var id = -1L
    private var logExpanded = false
    private var logEntries: List<LogEntry> = emptyList()
    private var shownImages: List<String> = emptyList()
    private lateinit var logAdapter: LogRowAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentIssueDetailBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        id = arguments?.getLong("issueId", -1L) ?: -1L
        logExpanded = savedInstanceState?.getBoolean(K_LOG) ?: false
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            b.toolbar.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            b.verifyBar.updatePadding(bottom = requireContext().dp(12) + nav)
            b.scroll.updatePadding(bottom = if (b.verifyBar.isVisible) 0 else nav)
            insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        logAdapter = LogRowAdapter(app.agents) { }
        b.logList.adapter = logAdapter
        b.logToggle.setOnClickListener { logExpanded = !logExpanded; renderLog() }
        b.confirm.setOnClickListener { move(IssueStatus.CONFIRMED) }
        b.stillBroken.setOnClickListener { move(IssueStatus.REOPENED) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.feedback.changed.collect { load() }
            }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean(K_LOG, logExpanded)
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null; shownImages = emptyList() }

    private suspend fun load() {
        val r = withContext(Dispatchers.IO) { runCatching { app.feedback.get(id) }.getOrNull() }
        val bb = _b ?: return
        bb.missing.isVisible = r == null
        bb.content.isVisible = r != null
        if (r == null) { bb.verifyBar.isVisible = false; bb.toolbar.title = getString(R.string.feedback_my_issues); return }
        bind(r)
    }

    /** FBK-14: where the issue stands with the Bridgit team. */
    private fun uploadLine(r: Issue): String = when (r.upload) {
        UploadState.SENT -> getString(R.string.feedback_upload_sent, Fmt.dateTime(r.uploadedAt))
        UploadState.PENDING -> getString(R.string.feedback_upload_pending)
        UploadState.FAILED -> getString(R.string.feedback_upload_failed, r.uploadError ?: "")
        UploadState.OFF -> getString(R.string.feedback_upload_off)
        else -> getString(R.string.feedback_upload_skipped)
    }

    /** A0178: the description, then an applet request's fields one per line ("What it does" only when it isn't the description). */
    private fun appletText(description: String, a: org.json.JSONObject): String {
        fun list(k: String) = a.optJSONArray(k)?.let { arr -> (0 until arr.length()).joinToString { arr.optString(it) } }
        val what = a.optString("what_it_does").takeIf { it.isNotEmpty() && !description.trim().startsWith(it) }
        val lines = listOf(R.string.feedback_applet_name to a.optString("name"), R.string.feedback_applet_what to what,
            R.string.feedback_applet_devices to list("devices"), R.string.feedback_applet_data to list("data_used"),
            R.string.feedback_applet_portal to a.optString("portal_shows"))
            .filter { !it.second.isNullOrEmpty() }.map { (label, v) -> getString(label) + ": " + v }
        return if (lines.isEmpty()) description else description + "\n\n" + lines.joinToString("\n")
    }

    private fun bind(r: Issue) {
        val bug = r.kind == IssueKind.BUG
        val kindName = getString(r.kind.nameRes)
        b.toolbar.title = r.ref?.let { getString(R.string.feedback_detail_title, kindName, it) }
            ?: getString(R.string.feedback_detail_title_unsent, kindName)
        StatusChip.style(b.status, r.status)
        b.status.contentDescription = "Status: " + StatusMachine.label(r.status)
        // Features are always held (FBK-04), so the flag only says something on bugs.
        b.noAutofix.isVisible = r.noAutofix && bug
        b.filed.text = getString(R.string.feedback_detail_filed, Fmt.dateTime(r.createdAt), r.agent ?: "owner") + "\n" + uploadLine(r)
        b.description.text = r.applet?.let { appletText(r.description, it) } ?: r.description

        bindImages(r)

        logEntries = (0 until r.log.length()).mapNotNull { i -> r.log.optJSONObject(i)?.let { runCatching { LogJson.entry(it) }.getOrNull() } }
        renderLog()
        bindContext(r)
        bindHistory(r)

        val verify = r.status == IssueStatus.FIXED || r.status == IssueStatus.WONT_FIX || r.status == IssueStatus.DUPLICATE
        b.verifyBar.isVisible = verify
        if (verify) b.verifyHint.text = getString(
            if (r.lastBy == cc.bridgit.app.feedback.FeedbackStatusSync.BY) R.string.feedback_verify_hint_team else R.string.feedback_verify_hint,
            StatusMachine.label(r.status).substringBefore(" —").lowercase())
        ViewCompat.requestApplyInsets(b.root)
    }

    private fun bindImages(r: Issue) {
        b.imagesSection.isVisible = r.images.isNotEmpty()
        if (r.images == shownImages) return
        shownImages = r.images
        val row = b.images
        row.removeAllViews()
        val ctx = requireContext()
        val size = ctx.dp(96)
        r.images.forEachIndexed { i, name ->
            val iv = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = ctx.dp(8) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.feedback_bg_thumb)
                setPadding(ctx.dp(1), ctx.dp(1), ctx.dp(1), ctx.dp(1))
                contentDescription = getString(R.string.feedback_image_desc, i + 1, r.images.size)
                isFocusable = true
                setOnClickListener { showImage(name) }
            }
            row.addView(iv)
            viewLifecycleOwner.lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { app.feedback.imageFile(name)?.let { decodeFileThumb(it.path, size * 2) } }
                iv.setImageBitmap(bmp)
            }
        }
    }

    private fun showImage(name: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp: Bitmap? = withContext(Dispatchers.IO) { app.feedback.imageFile(name)?.let { decodeFileThumb(it.path, 1600) } }
            if (_b == null) return@launch
            if (bmp == null) { Snackbar.make(b.root, R.string.feedback_image_unavailable, Snackbar.LENGTH_SHORT).show(); return@launch }
            val iv = ImageView(requireContext()).apply {
                adjustViewBounds = true
                maxHeight = (resources.displayMetrics.heightPixels * 0.7).toInt() // tall captures fit the dialog
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bmp)
                contentDescription = getString(R.string.feedback_section_images)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setView(iv)
                .setPositiveButton(R.string.feedback_close, null)
                .show()
        }
    }

    private fun renderLog() {
        val bb = _b ?: return
        val n = logEntries.size
        bb.logNone.isVisible = n == 0
        bb.logToggle.isVisible = n > 0
        val count = resources.getQuantityString(R.plurals.feedback_log_count, n, n)
        bb.logToggle.text = "$count · " + getString(if (logExpanded) R.string.feedback_log_collapse else R.string.feedback_log_expand)
        ViewCompat.setStateDescription(bb.logToggle, getString(if (logExpanded) R.string.feedback_log_collapse else R.string.feedback_log_expand))
        bb.logList.isVisible = logExpanded && n > 0
        if (logExpanded) logAdapter.submitList(logEntries)
    }

    private fun bindContext(r: Issue) {
        val c = r.context
        fun s(k: String) = if (c.isNull(k)) null else c.optString(k).takeIf { it.isNotEmpty() }
        val version = listOfNotNull(
            s("app_version")?.let { v -> c.optLong("version_code").takeIf { it > 0 }?.let { "$v ($it)" } ?: v },
            s("git_commit")?.let { if (c.optBoolean("dirty_build")) "$it-dirty" else it },
            s("build_type"),
        ).joinToString(" · ")
        val device = listOfNotNull(s("device_model"), s("android_version")?.let { "Android $it" }).joinToString(" · ")
        val screen = listOfNotNull(s("screen"), s("screen_context")).joinToString(" — ")
        val source = if (r.captureSource == "agent") getString(R.string.feedback_source_agent, r.agent ?: "?")
            else getString(R.string.feedback_source_form)
        val rows = listOf(
            R.string.feedback_context_version to version,
            R.string.feedback_context_device to device,
            R.string.feedback_context_screen to screen,
            R.string.feedback_context_source to source,
        ).filter { it.second.isNotEmpty() }
        b.context.removeAllViews()
        rows.forEach { (label, value) -> addKeyValue(b.context, getString(label), value) }
    }

    private fun addKeyValue(parent: LinearLayout, key: String, value: String) {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dp(4), 0, ctx.dp(4))
            isFocusable = true
            contentDescription = "$key: $value"
        }
        row.addView(TextView(ctx).apply {
            setTextAppearance(M.style.TextAppearance_Material3_LabelMedium)
            setTextColor(ctx.themeColor(M.attr.colorOnSurfaceVariant))
            text = key
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        row.addView(TextView(ctx).apply {
            setTextAppearance(R.style.TextAppearance_Bridgit_Mono)
            setTextColor(ctx.themeColor(M.attr.colorOnSurface))
            text = value
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        parent.addView(row)
    }

    /** Vertical timeline, oldest first (ui-spec 5.12). */
    private fun bindHistory(r: Issue) {
        val parent = b.history
        parent.removeAllViews()
        val ctx = requireContext()
        r.history.forEachIndexed { i, h ->
            val row = FeedbackHistoryRowBinding.inflate(layoutInflater, parent, false)
            val what = if (h.kind == HistoryKind.COMMENT) getString(R.string.feedback_history_comment) else StatusMachine.label(h.status)
            row.status.text = what
            val time = Fmt.dateTime(h.at)
            row.who.text = "${h.by} · $time"
            row.note.isVisible = !h.note.isNullOrBlank()
            row.note.text = h.note
            row.dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ctx.themeColor(if (i == r.history.lastIndex) M.attr.colorPrimary else M.attr.colorOutline))
            }
            row.line.visibility = if (i == r.history.lastIndex) View.INVISIBLE else View.VISIBLE
            row.root.contentDescription = getString(R.string.feedback_history_row_desc, what, h.by, time) +
                (h.note?.takeIf { it.isNotBlank() }?.let { ". $it" } ?: "")
            parent.addView(row.root)
        }
    }

    /** FBK-11a: the owner confirms or reopens; mirrors the notification actions. */
    private fun move(to: IssueStatus) {
        b.confirm.isEnabled = false; b.stillBroken.isEnabled = false
        val app = app
        val logRes = if (to == IssueStatus.CONFIRMED) R.string.feedback_confirmed_log else R.string.feedback_reopened_log
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val r = app.feedback.move(id, to, Actor.OWNER, "owner", null)
                    app.log.system("audit", "feedback", app.getString(logRes, r.number))
                    // The notification for this issue is out of date now (same id as FeedbackNotifier).
                    app.getSystemService(android.app.NotificationManager::class.java)?.cancel(5000 + id.toInt())
                    r
                }
            }
            val bb = _b ?: return@launch
            bb.confirm.isEnabled = true; bb.stillBroken.isEnabled = true
            result.onFailure { e -> Snackbar.make(bb.root, e.message ?: getString(R.string.feedback_save_failed), Snackbar.LENGTH_LONG).show() }
            // On success, app.feedback.changed re-binds the screen.
        }
    }

    companion object {
        private const val K_LOG = "log_expanded"
    }
}
