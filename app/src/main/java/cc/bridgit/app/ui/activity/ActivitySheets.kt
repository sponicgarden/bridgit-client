package cc.bridgit.app.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.capability.isoAt
import cc.bridgit.app.databinding.ActivitySheetEntryBinding
import cc.bridgit.app.databinding.ActivitySheetExportBinding
import cc.bridgit.app.ui.common.Sentences
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import org.json.JSONObject
import java.text.NumberFormat

/**
 * Entry sheet (UI-17, ui-spec 5.7): title = the sentence, every LOG-01 field as a
 * key/value list in mono, Copy as JSON and "<Agent>'s activity".
 */
class EntrySheet : BottomSheetDialogFragment() {
    private var _b: ActivitySheetEntryBinding? = null
    private val app get() = requireActivity().application as BridgeApp

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = ActivitySheetEntryBinding.inflate(inflater, container, false)
        return _b!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val id = requireArguments().getLong(ARG_ID)
        viewLifecycleOwner.lifecycleScope.launch {
            val e = withContext(Dispatchers.IO) { app.log.get(id) }
            val b = _b ?: return@launch
            if (e == null) {
                b.title.setText(R.string.activity_sheet_missing)
                b.copy.isVisible = false; b.agent.isVisible = false; b.report.isVisible = false
                return@launch
            }
            b.report.setOnClickListener {
                (parentFragment as? ActivityFragment)?.reportAbout(e.id)
                dismiss()
            }
            val name = e.agentId?.let { app.agents.get(it)?.name } ?: e.agent
            val json = e.toJson(::isoAt, name)
            b.title.text = Sentences.of(e, name)
            for (key in FIELDS) addField(b.fields, key, json.opt(key))

            b.copy.setOnClickListener {
                val cm = requireContext().getSystemService(ClipboardManager::class.java)
                // Plain clip: log entries never contain keys (LOG-02), so no sensitive flag and no auto-clear label.
                cm?.setPrimaryClip(ClipData.newPlainText(getString(R.string.activity_sheet_clip_label), json.toString(2)))
                Snackbar.make(b.root, R.string.activity_sheet_copied, Snackbar.LENGTH_SHORT).show()
            }
            val agentName = name ?: if (e.agentId == null) "Bridgit" else null
            b.agent.isVisible = agentName != null
            b.agent.text = getString(R.string.activity_sheet_agent, agentName ?: "")
            b.agent.setOnClickListener {
                (parentFragment as? ActivityFragment)?.filterToAgent(e.agentId ?: "")
                dismiss()
            }
        }
    }

    private fun addField(parent: LinearLayout, key: String, value: Any?) {
        val ctx = parent.context
        val text = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> if (value.length() == 0) "{}" else value.toString(2)
            else -> value.toString()
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ctx.dp(3), 0, ctx.dp(3))
            isFocusable = true
            contentDescription = "$key: $text"
        }
        row.addView(TextView(ctx).apply {
            setTextAppearance(R.style.TextAppearance_Bridgit_Mono)
            setTextColor(ctx.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            this.text = key
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ctx.dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(ctx).apply {
            setTextAppearance(R.style.TextAppearance_Bridgit_Mono_Prompt)
            setTextColor(ctx.themeColor(com.google.android.material.R.attr.colorOnSurface))
            this.text = text
            setTextIsSelectable(true)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row)
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    companion object {
        private const val ARG_ID = "id"
        /** LOG-01 fields in schema order. */
        val FIELDS = listOf("id", "ts", "channel", "level", "kind", "agent", "agent_id", "source_ip", "method", "endpoint",
            "params", "http", "result", "bytes_in", "bytes_out", "duration_ms", "request_id", "message", "data")

        fun newInstance(id: Long) = EntrySheet().apply { arguments = bundleOf(ARG_ID to id) }
    }
}

/**
 * Export sheet (ui-spec 5.7, LOG-10): JSONL or CSV of the entries matching the current
 * filters. The parent ActivityFragment owns the "Create document" launcher.
 */
class ExportSheet : BottomSheetDialogFragment() {
    private var _b: ActivitySheetExportBinding? = null
    private val app get() = requireActivity().application as BridgeApp

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = ActivitySheetExportBinding.inflate(inflater, container, false)
        return _b!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val parent = parentFragment as? ActivityFragment ?: run { dismiss(); return }
        val query = parent.baseQuery()
        viewLifecycleOwner.lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { app.log.count(query) }
            val b = _b ?: return@launch
            b.count.text = getString(R.string.activity_export_count, NumberFormat.getIntegerInstance().format(n))
        }
        _b!!.export.setOnClickListener {
            val format = if (_b?.format?.checkedButtonId == R.id.csv) "csv" else "jsonl"
            dismiss()
            parent.startExport(format)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}
