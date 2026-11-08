package cc.bridgit.app.ui.feedback

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.R
import cc.bridgit.app.databinding.FeedbackThumbBinding
import cc.bridgit.app.databinding.FragmentReportFormBinding
import cc.bridgit.app.feedback.FeedbackLimits
import cc.bridgit.app.feedback.IssueKind
import cc.bridgit.app.feedback.loadDownscaledJpeg
import cc.bridgit.app.ui.common.dp

/**
 * Report a bug / Request a feature (FBK-03 … FBK-08, UI-20, ui-spec 5.11), one form for
 * both kinds. Behaviour cloned from Spotka's ReportBugScreen: per-kind drafts saved on
 * every keystroke, screenshots never restored, Auto-fix on the label row, last
 * screen + picker capped at 5 in total, importing disables Submit, an error banner the
 * form scrolls to, and a success dialog.
 */
class ReportFormFragment : Fragment() {
    private var _b: FragmentReportFormBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private val vm: ReportFormViewModel by viewModels()
    private lateinit var drafts: FeedbackDrafts
    private var binding = false
    private var dialog: AlertDialog? = null
    private var thumbsKey: String? = null

    private val screen get() = arguments?.getString("screen")
    private val screenContext get() = arguments?.getString("screenContext")
    private val logEntryId get() = arguments?.getLong("logEntryId", -1L) ?: -1L

    private val pick = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(FeedbackLimits.MAX_IMAGES)) { uris ->
        onPicked(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drafts = FeedbackDrafts(requireContext())
        if (!vm.initialized) {
            vm.initialized = true
            // After process death the restored text belongs to the kind that was showing, not the arg's.
            vm.kind = IssueKind.of(savedInstanceState?.getString(K_KIND) ?: arguments?.getString("kind")) ?: IssueKind.BUG
            vm.lastScreen = ReportCaptureHolder.take()
            vm.includeLastScreen = vm.lastScreen != null
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onCancel()
        })
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (vm.initialized) out.putString(K_KIND, vm.kind.id)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentReportFormBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            b.toolbar.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
        b.toolbar.setNavigationOnClickListener { onCancel() }
        b.submit.setOnClickListener { submit() }

        loadDraft()
        b.description.doAfterTextChanged {
            if (binding) return@doAfterTextChanged
            saveDraft()
            if (vm.error != null) { vm.error = null; b.descriptionLayout.error = null }
            render()
        }
        b.noAutofix.setOnCheckedChangeListener { _, _ -> if (!binding) { saveDraft(); render() } }

        b.lastScreenRow.setOnClickListener { b.lastScreenSwitch.toggle() }
        b.lastScreenSwitch.setOnCheckedChangeListener { sw, on ->
            if (binding) return@setOnCheckedChangeListener
            if (on && vm.picked.size >= FeedbackLimits.MAX_IMAGES) {
                // At the cap from picks alone: reject rather than silently drop one (Spotka).
                binding = true; sw.isChecked = false; binding = false
                showError(getString(R.string.feedback_max_screenshots))
            } else {
                vm.includeLastScreen = on
            }
            render()
        }
        b.addScreenshot.setOnClickListener {
            if (FeedbackLimits.MAX_IMAGES - vm.attachedCount() <= 0) { showError(getString(R.string.feedback_max_screenshots)); return@setOnClickListener }
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        b.logSwitch.setOnCheckedChangeListener { _, on -> if (!binding) { vm.includeLog = on; render() } }
        b.logRow.setOnClickListener { b.logSwitch.toggle() }
        b.preview.setOnClickListener {
            if (childFragmentManager.findFragmentByTag("log") == null) LogPreviewSheet.newInstance(logEntryId).show(childFragmentManager, "log")
        }
        b.type.addOnButtonCheckedListener { _, id, checked ->
            if (binding || !checked) return@addOnButtonCheckedListener
            val k = if (id == R.id.type_feature) IssueKind.FEATURE else IssueKind.BUG
            if (k == vm.kind) return@addOnButtonCheckedListener
            saveDraft()          // keep this kind's draft …
            vm.kind = k
            vm.error = null
            b.descriptionLayout.error = null
            loadDraft()          // … and bring back the other one (FBK-04 item 8)
            render()
        }

        if (vm.lastScreen != null && vm.lastScreenThumb == null) {
            val bytes = vm.lastScreen!!
            val edge = requireContext().dp(192)
            viewLifecycleOwner.lifecycleScope.launch {
                vm.lastScreenThumb = withContext(Dispatchers.Default) { decodeThumb(bytes, edge) }
                if (_b != null) render()
            }
        }
        if (vm.logCount < 0) viewLifecycleOwner.lifecycleScope.launch {
            val id = logEntryId
            val (n, exists) = withContext(Dispatchers.IO) {
                runCatching { app.log.query(LogJson.RECENT) }.getOrDefault(emptyList()).let { recent ->
                    recent.size to (id >= 0 && (recent.any { it.id == id } || app.log.get(id) != null))
                }
            }
            vm.logCount = n; vm.pickedEntryExists = exists
            if (_b != null) render()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.submitting.collect { render() } }
                launch {
                    vm.imported.collect { n ->
                        if (n == 0) return@collect
                        render(); if (vm.error != null) scrollToBanner()
                    }
                }
                launch { vm.outcome.collect { o -> if (o != null) onOutcome(o) } }
            }
        }
        render()
    }

    override fun onDestroyView() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss(); dialog = null
        thumbsKey = null
        super.onDestroyView()
        _b = null
    }

    private fun currentText() = b.description.text?.toString() ?: ""

    private fun loadDraft() {
        binding = true
        b.description.setText(drafts.description(vm.kind))
        b.description.setSelection(b.description.text?.length ?: 0)
        b.noAutofix.isChecked = !drafts.noAutofix(vm.kind) // the box reads "Auto-fix": checked = agents may fix it
        binding = false
    }

    private fun saveDraft() = drafts.save(vm.kind, currentText(), !b.noAutofix.isChecked)

    private fun render() {
        val bb = _b ?: return
        binding = true
        val bug = vm.kind == IssueKind.BUG
        val submitting = vm.submitting.value
        bb.toolbar.title = getString(if (bug) R.string.feedback_title_bug else R.string.feedback_title_feature)
        bb.intro.setText(if (bug) R.string.feedback_intro_bug else R.string.feedback_intro_feature)
        bb.label.setText(if (bug) R.string.feedback_label_bug else R.string.feedback_label_feature)
        bb.descriptionLayout.placeholderText = getString(if (bug) R.string.feedback_description_placeholder else R.string.feedback_feature_placeholder)
        // Features are always held for review, so the box is hidden for them (FBK-04 item 3).
        bb.noAutofix.isVisible = bug
        bb.noAutofixHint.isVisible = bug && !bb.noAutofix.isChecked
        bb.type.check(if (bug) R.id.type_bug else R.id.type_feature)

        bb.banner.isVisible = vm.error != null
        bb.bannerText.text = vm.error

        // Last screen (FBK-02): only when the capture worked.
        bb.lastScreenRow.isVisible = vm.lastScreen != null
        bb.lastScreenThumb.setImageBitmap(vm.lastScreenThumb)
        bb.lastScreenSub.text = screen?.let { getString(R.string.feedback_last_screen_sub, it) }
            ?: getString(R.string.feedback_last_screen_sub_generic)
        bb.lastScreenSwitch.isChecked = vm.includeLastScreen
        bb.lastScreenSwitch.contentDescription = getString(R.string.feedback_include_last_screen) + ", " + bb.lastScreenSub.text

        renderThumbs(submitting)
        val full = vm.attachedCount() >= FeedbackLimits.MAX_IMAGES
        bb.addScreenshot.setText(if (vm.picked.isEmpty()) R.string.feedback_add_screenshot else R.string.feedback_add_another)
        bb.addScreenshot.isEnabled = !submitting && !vm.importing && !full
        bb.importing.isVisible = vm.importing

        val n = if (vm.logCount < 0) FeedbackLimits.LOG_ENTRIES else vm.logCount
        val entries = resources.getQuantityString(R.plurals.feedback_log_entries, n, n)
        bb.logSub.text = if (vm.pickedEntryExists) getString(R.string.feedback_log_plus_entry, entries) else entries
        bb.logSwitch.isChecked = vm.includeLog
        bb.logSwitch.contentDescription = getString(R.string.feedback_include_log) + ", " + bb.logSub.text

        val enabled = !submitting
        bb.description.isEnabled = enabled
        bb.noAutofix.isEnabled = enabled
        bb.lastScreenSwitch.isEnabled = enabled && !vm.importing
        bb.lastScreenRow.isEnabled = enabled && !vm.importing
        bb.logSwitch.isEnabled = enabled
        bb.logRow.isEnabled = enabled
        bb.typeBug.isEnabled = enabled
        bb.typeFeature.isEnabled = enabled

        // Submit (FBK-03): disabled while empty or importing; spinner + "Sending…" while saving.
        bb.submit.isEnabled = !submitting && !vm.importing && currentText().isNotBlank()
        bb.submit.setText(if (submitting) R.string.feedback_sending else R.string.feedback_submit)
        bb.submit.icon = if (submitting) spinner(bb.submit.currentTextColor) else null
        bb.toolbar.navigationIcon?.mutate()?.alpha = if (submitting) 97 else 255 // Cancel is inert while saving
        binding = false
    }

    private fun spinner(color: Int) = CircularProgressIndicatorSpec(requireContext(), null, 0,
        com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator_ExtraSmall).let { spec ->
        spec.indicatorColors = intArrayOf(color)
        spec.indicatorSize = requireContext().dp(16)
        spec.trackThickness = requireContext().dp(2)
        IndeterminateDrawable.createCircularDrawable(requireContext(), spec)
    }

    private fun renderThumbs(submitting: Boolean) {
        val offset = if (vm.includeLastScreen && vm.lastScreen != null) 1 else 0
        // Rebuild only when something changed, not on every keystroke.
        val key = "${vm.picked.map { it.id }}|$offset|$submitting|${vm.importing}"
        if (key == thumbsKey) return
        thumbsKey = key
        val row = b.thumbs
        row.removeAllViews()
        b.thumbsScroll.isVisible = vm.picked.isNotEmpty()
        vm.picked.forEachIndexed { i, p ->
            val t = FeedbackThumbBinding.inflate(layoutInflater, row, false)
            t.image.setImageBitmap(p.thumb)
            t.image.contentDescription = getString(R.string.feedback_screenshot_desc, i + 1 + offset)
            t.remove.contentDescription = getString(R.string.feedback_remove_screenshot, i + 1 + offset)
            t.remove.isEnabled = !submitting && !vm.importing
            t.remove.alpha = if (t.remove.isEnabled) 1f else 0.38f
            t.remove.setOnClickListener {
                vm.picked.removeAll { it.id == p.id }
                if (vm.error == getString(R.string.feedback_max_screenshots)) vm.error = null
                render()
            }
            row.addView(t.root)
        }
    }

    private fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty() || _b == null) return
        // Capture capacity before any suspend so a toggle mid-import can't change it (Spotka).
        val slots = FeedbackLimits.MAX_IMAGES - vm.attachedCount()
        if (slots <= 0) { showError(getString(R.string.feedback_max_screenshots)); return }
        vm.import(requireContext().applicationContext, uris.take(slots), uris.size > slots, requireContext().dp(216))
        render()
    }

    private fun showError(msg: String) {
        vm.error = msg
        render()
        scrollToBanner()
    }

    private fun scrollToBanner() {
        val bb = _b ?: return
        bb.scroll.post { bb.scroll.smoothScrollTo(0, 0) }
    }

    private fun submit() {
        if (vm.submitting.value || vm.importing) return
        val text = currentText().trim()
        if (text.isEmpty()) {
            b.descriptionLayout.error = getString(R.string.feedback_description_required)
            showError(getString(R.string.feedback_description_required))
            return
        }
        saveDraft()
        vm.error = null
        requireContext().getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(b.description.windowToken, 0)
        b.description.clearFocus()
        vm.submit(app, vm.kind, text, vm.kind == IssueKind.BUG && !b.noAutofix.isChecked, screen, screenContext, logEntryId)
    }

    private fun onOutcome(o: ReportFormViewModel.Outcome) {
        when (o) {
            is ReportFormViewModel.Outcome.Failed -> { vm.consumeOutcome(); showError(o.message) }
            is ReportFormViewModel.Outcome.Saved -> {
                val message = when {
                    o.issue.ref != null -> getString(R.string.feedback_saved_body_sent, o.issue.ref)
                    o.issue.upload == cc.bridgit.app.feedback.UploadState.PENDING -> getString(R.string.feedback_saved_body)
                    else -> getString(R.string.feedback_saved_body_local)
                }
                // The team number arrives a few seconds after the save: update the open dialog.
                if (dialog?.isShowing == true) { dialog?.setMessage(message); return }
                binding = true; b.description.setText(""); b.noAutofix.isChecked = true; binding = false
                render()
                // FBK-08. Stays up across rotation: the outcome is consumed only by a button.
                val builder = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.feedback_saved_title)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.ok) { _, _ -> vm.consumeOutcome(); findNavController().popBackStack() }
                if (BuildConfig.DEBUG) builder.setNeutralButton(R.string.feedback_view_report) { _, _ ->
                    vm.consumeOutcome()
                    findNavController().navigate(R.id.issueDetail, bundleOf("issueId" to o.issue.id),
                        NavOptions.Builder().setPopUpTo(R.id.reportForm, true).build())
                }
                dialog = builder.show()
            }
        }
    }

    private companion object { const val K_KIND = "kind" }

    /** Cancel / back: ask about the draft when there is text (ui-spec 5.11). */
    private fun onCancel() {
        if (vm.submitting.value || _b == null) return
        if (currentText().isBlank()) { findNavController().popBackStack(); return }
        saveDraft()
        dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.feedback_keep_draft_title)
            .setPositiveButton(R.string.feedback_keep) { _, _ -> findNavController().popBackStack() }
            .setNegativeButton(R.string.feedback_discard) { _, _ ->
                drafts.clear(vm.kind)
                findNavController().popBackStack()
            }
            .show()
    }
}
