package cc.bridgit.app.ui.feedback

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.databinding.FeedbackReportRowBinding
import cc.bridgit.app.databinding.FragmentMyReportsBinding
import cc.bridgit.app.feedback.FeedbackStatusSync
import cc.bridgit.app.feedback.Report
import cc.bridgit.app.feedback.ReportKind
import cc.bridgit.app.feedback.ReportStatus
import cc.bridgit.app.feedback.StatusMachine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * My reports (FBK-09, UI-20, ui-spec 5.12): every report, newest first, with kind,
 * first line, date, who last updated it and a status chip. Filters All · Bugs ·
 * Features · Open ("Open" = anything not yet confirmed closed).
 */
class MyReportsFragment : Fragment() {
    private var _b: FragmentMyReportsBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private val adapter = Adapter { r -> findNavController().navigate(R.id.reportDetail, bundleOf("reportId" to r.id)) }
    private var all: List<Report> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentMyReportsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            b.toolbar.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            b.list.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        b.list.adapter = adapter
        b.filters.setOnCheckedStateChangeListener { _, _ -> show() }
        if (savedInstanceState == null) b.filters.check(if (arguments?.getString("reportKind") == ReportKind.FEATURE.id) R.id.filter_features else R.id.filter_bugs)
        b.newReport.setOnClickListener { openReport(this, if (b.filters.checkedChipId == R.id.filter_features) ReportKind.FEATURE else ReportKind.BUG, "My reports") }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.feedback.changed.collect { load() }
            }
        }
        // FBK-14: pull the Bridgit team's latest statuses (at most once a minute); the list reloads when they land.
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { FeedbackStatusSync.freshen(app) }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    private suspend fun load() {
        all = withContext(Dispatchers.IO) { runCatching { app.feedback.list(limit = 1000) }.getOrDefault(emptyList()) }
        show()
    }

    private fun show() {
        val bb = _b ?: return
        bb.newReport.setText(if (bb.filters.checkedChipId == R.id.filter_features) R.string.feedback_menu_request_feature else R.string.feedback_menu_report_bug)
        val shown = when (bb.filters.checkedChipId) {
            R.id.filter_bugs -> all.filter { it.kind == ReportKind.BUG }
            R.id.filter_features -> all.filter { it.kind == ReportKind.FEATURE }
            R.id.filter_open -> all.filter { it.status != ReportStatus.CONFIRMED }
            else -> all
        }
        adapter.submitList(shown)
        bb.empty.isVisible = shown.isEmpty()
        bb.empty.setText(if (all.isEmpty()) R.string.feedback_empty else R.string.feedback_empty_filtered)
        bb.list.isVisible = shown.isNotEmpty()
    }

    private class Adapter(private val onClick: (Report) -> Unit) : ListAdapter<Report, Adapter.Holder>(DIFF) {
        class Holder(val b: FeedbackReportRowBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(FeedbackReportRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val r = getItem(position)
            val ctx = h.b.root.context
            val bug = r.kind == ReportKind.BUG
            h.b.icon.setImageResource(if (bug) R.drawable.feedback_ic_bug else R.drawable.feedback_ic_lightbulb)
            h.b.firstLine.text = r.firstLine
            val date = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(r.createdAt))
            val updatedBy = r.lastBy?.takeIf { r.updatedAt > r.createdAt }
            val number = r.ref ?: ctx.getString(R.string.feedback_not_sent_yet)
            h.b.meta.text = if (updatedBy != null) ctx.getString(R.string.feedback_row_meta_updated, number, date, updatedBy)
                else ctx.getString(R.string.feedback_row_meta, number, date)
            StatusChip.style(h.b.status, r.status)
            h.b.root.contentDescription = ctx.getString(R.string.feedback_row_desc,
                ctx.getString(if (bug) R.string.feedback_kind_bug else R.string.feedback_kind_feature),
                number, r.firstLine, StatusMachine.label(r.status), h.b.meta.text)
            h.b.root.setOnClickListener { onClick(r) }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<Report>() {
                override fun areItemsTheSame(a: Report, b: Report) = a.id == b.id
                override fun areContentsTheSame(a: Report, b: Report) =
                    a.status == b.status && a.updatedAt == b.updatedAt && a.description == b.description && a.lastBy == b.lastBy &&
                        a.remoteId == b.remoteId && a.upload == b.upload
            }
        }
    }
}
