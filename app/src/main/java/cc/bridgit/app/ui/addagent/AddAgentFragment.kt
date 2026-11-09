package cc.bridgit.app.ui.addagent

import android.animation.AnimatorSet
import cc.bridgit.app.relay.ConnectorClient
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.PersistableBundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.capability.address
import cc.bridgit.app.core.Connection
import cc.bridgit.app.databinding.AddagentCheckRowBinding
import cc.bridgit.app.databinding.AddagentQrSheetBinding
import cc.bridgit.app.databinding.AddagentTextSheetBinding
import cc.bridgit.app.databinding.FragmentAddAgentBinding
import cc.bridgit.app.ui.MainActivity
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.R as M

/**
 * Add agent (6.2, UI-13, ui-spec 5.4): Name → Connection → Check → Setup prompt,
 * then waiting for the first call on the same screen. Opened with `agentId` it
 * shows the prompt for an existing agent (after Rotate, or after a crash, AGT-05a).
 */
class AddAgentFragment : Fragment() {
    private var _b: FragmentAddAgentBinding? = null
    private val b get() = _b!!
    private val vm: AddAgentViewModel by viewModels()
    private val app get() = requireActivity().application as BridgeApp
    private var pulse: AnimatorSet? = null
    private var bindingName = false
    private var sheet: BottomSheetDialog? = null

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = onBack()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAddAgentBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        vm.init(arguments?.getString("agentId"), arguments?.getBoolean("fromRotate") == true)
        if (vm.step.value == 4 && vm.agentId != null && !vm.agentExists) { findNavController().popBackStack(); return }

        // Step 0 (AGT-03): opening Add agent turns the listener on.
        if (!vm.step0Done) {
            vm.step0Done = true
            if (!app.settings.listenerEnabled) app.settings.listenerEnabled = true
            if (BridgeService.running == null) runCatching { BridgeService.start(requireContext()) }
        }

        // AGT-08a: the prompt holds the key; keep it out of the accessibility tree.
        cc.bridgit.app.ui.common.SecretViews.hideSecret(b.promptText) { vm.key }

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            b.toolbar.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            v.updatePadding(bottom = bars.bottom)
            insets
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        b.toolbar.setNavigationOnClickListener { onClose() }
        b.toolbar.inflateMenu(R.menu.addagent_menu)
        b.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.addagent_copy_key -> { vm.key?.let { k -> warnFirst { copy(k) } }; true }
                R.id.addagent_pair -> { warnFirst { openPairing() }; true }
                else -> false
            }
        }

        setupName()
        setupConnection()
        setupCheckRows()
        setupPrompt()

        b.back.setOnClickListener { vm.step.value = (vm.step.value - 1).coerceAtLeast(1) }
        b.next.setOnClickListener { next() }
        b.doneOutlined.setOnClickListener { done() }
        b.doneFilled.setOnClickListener { done() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.step.collect { render(it) } }
                launch { vm.checks.collectLatest {
                    renderChecks(it)
                    if (vm.step.value == 3 && it.allPassed) {
                        b.checkHeading.setText(R.string.addagent_check_ready)
                        delay(700)
                        if (vm.step.value == 3 && vm.checks.value.allPassed) {
                            snack(getString(R.string.addagent_check_ready))
                            next()
                        }
                    }
                } }
                launch { vm.wait.collect { renderWait(it) } }
                launch { vm.keyGone.collect { renderPrompt() } }
                launch { app.listener.collect { renderPrompt() } }
            }
        }

        vm.resumedMinutes?.let { m ->
            vm.resumedMinutes = null
            snack(getString(R.string.addagent_resumed, m))
        }
    }

    override fun onResume() {
        super.onResume()
        if (vm.step.value == 3) vm.runChecks()
        if (vm.step.value == 4) { setSecure(true); renderPowers() } // back from the Max power permission pass
        // A key fetched with a pairing code counts as acknowledged.
        val redeemed = runCatching {
            findNavController().currentBackStackEntry?.savedStateHandle?.get<Boolean>(PairCodeFragment.KEY_REDEEMED)
        }.getOrNull() == true
        if ((redeemed || vm.pairWindow?.redeemed == true) && !vm.acknowledged) vm.acknowledge()
        if (vm.step.value == 4) renderWait(vm.wait.value)
    }

    override fun onStop() {
        exportJob?.cancel()
        super.onStop()
        // Keep FLAG_SECURE while this flow or Pair with code is on screen; clear it when leaving.
        val dest = runCatching { findNavController().currentDestination?.id }.getOrNull()
        if (dest != R.id.addAgent && dest != R.id.pairCode) setSecure(false)
    }

    override fun onDestroyView() {
        pulse?.cancel(); pulse = null
        sheet?.dismiss(); sheet = null
        super.onDestroyView()
        _b = null
    }

    // ---- Rendering

    private fun render(step: Int) {
        val title = if (step == 1 || vm.name.isBlank()) getString(R.string.addagent_title)
        else getString(R.string.addagent_title_named, vm.name.trim())
        b.toolbar.title = title
        b.toolbar.menu.findItem(R.id.addagent_copy_key).isVisible = step == 4 && vm.key != null
        b.toolbar.menu.findItem(R.id.addagent_pair).isVisible = step == 4 && vm.key != null

        val segs = listOf(b.seg1, b.seg2, b.seg3, b.seg4)
        val labs = listOf(b.lab1, b.lab2, b.lab3, b.lab4)
        val primary = requireContext().themeColor(M.attr.colorPrimary)
        val track = requireContext().themeColor(M.attr.colorSurfaceContainerHighest)
        val variant = requireContext().themeColor(M.attr.colorOnSurfaceVariant)
        segs.forEachIndexed { i, v -> v.background.mutate().setTint(if (i < step) primary else track) }
        labs.forEachIndexed { i, t ->
            t.setTextColor(if (i == step - 1) primary else variant)
            t.paint.isFakeBoldText = i == step - 1
            t.invalidate()
        }
        b.progress.contentDescription = getString(R.string.addagent_progress_desc, step, labs[step - 1].text)

        b.stepName.isVisible = step == 1
        b.stepConnection.isVisible = step == 2
        b.stepCheck.isVisible = step == 3
        b.stepPrompt.isVisible = step == 4

        b.back.visibility = when (step) { 1 -> View.INVISIBLE; 4 -> View.GONE; else -> View.VISIBLE }
        b.next.isVisible = step in 1..3
        when (step) {
            1 -> { b.next.text = getString(R.string.addagent_next); updateNextForName() }
            2 -> {
                b.next.text = getString(R.string.addagent_next); b.next.isEnabled = true
                b.connHeading.text = getString(R.string.addagent_conn_heading, vm.name.trim())
                renderConnection()
            }
            3 -> {
                b.checkHeading.text = getString(R.string.addagent_check_heading, vm.name.trim())
                vm.runChecks()
                renderChecks(vm.checks.value)
            }
            4 -> {
                setSecure(true)
                renderPrompt()
                renderWait(vm.wait.value)
            }
        }
        if (step != 4) {
            setSecure(false)
            b.doneOutlined.isVisible = false; b.doneFilled.isVisible = false
        }
        b.scroll.scrollTo(0, 0)
    }

    // ---- Step 1: Name

    private fun setupName() {
        bindingName = true
        b.nameInput.setText(vm.name)
        bindingName = false
        b.nameInput.doAfterTextChanged {
            if (bindingName) return@doAfterTextChanged
            vm.name = it?.toString() ?: ""
            vm.nameError = null
            b.nameLayout.error = null
            updateNextForName()
        }
        b.nameInput.setOnEditorActionListener { _, _, _ -> if (b.next.isEnabled) next(); true }
        val used = app.agents.all().map { it.name }
        b.suggestions.removeAllViews()
        AgentSuggestions.all.forEach { suggestion ->
            b.suggestions.addView(Chip(requireContext()).apply {
                text = suggestion.label
                setChipIconResource(suggestion.icon)
                chipIconTint = suggestion.color?.let { color -> android.content.res.ColorStateList.valueOf(
                    if (color == 0xFF101010.toInt()) requireContext().themeColor(M.attr.colorOnSurface) else color) }
                isChipIconVisible = true
                chipIconSize = requireContext().dp(24).toFloat()
                minHeight = requireContext().dp(48)
                setOnClickListener {
                    val name = AgentSuggestions.availableName(suggestion.name, used)
                    b.nameInput.setText(name)
                    b.nameInput.setSelection(name.length)
                    // Claude Duet and ChatGPT Dots start the Connection step on their connector setup; other picks undo that.
                    if (suggestion.connector != null) { vm.connection = Connection.RELAY; vm.connector = suggestion.connector }
                    else if (vm.connector != null) { vm.connection = Connection.DIRECT; vm.connector = null }
                }
            })
        }
        b.suggestions.isVisible = b.suggestions.childCount > 0
        vm.nameError?.let { b.nameLayout.error = it }
    }

    private fun updateNextForName() {
        if (vm.step.value == 1) b.next.isEnabled = vm.name.isNotBlank()
    }

    // ---- Step 2: Connection

    private fun setupConnection() {
        b.connectionAdvanced.setOnClickListener {
            vm.connectionAdvanced = !vm.connectionAdvanced
            renderConnection()
        }
        val cards = connectionCards()
        cards.forEachIndexed { i, (card, choice) ->
            val (conn, connector) = choice
            card.setOnClickListener { vm.connection = conn; vm.connector = connector; renderConnection() }
            ViewCompat.setAccessibilityDelegate(card, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = vm.connection == conn && vm.connector == connector
                    info.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(
                        i, 1, 0, 1, false, info.isChecked))
                }
            })
        }
        ViewCompat.setAccessibilityDelegate(b.connGroup, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = RadioGroup::class.java.name
                info.setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(
                    cards.size, 1, false, AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE))
            }
        })
    }

    /** Each card and the choice it makes: connection, and which chat app's connector setup (if any). */
    private fun connectionCards(): List<Pair<com.google.android.material.card.MaterialCardView, Pair<Connection, ConnectorClient?>>> =
        listOf(b.cardDirect to (Connection.DIRECT to null), b.cardRelay to (Connection.RELAY to null),
        b.cardClaude to (Connection.RELAY to ConnectorClient.CLAUDE), b.cardChatgpt to (Connection.RELAY to ConnectorClient.CHATGPT),
        b.cardUserspace to (Connection.USERSPACE to null))

    private fun renderConnection() {
        val ctx = requireContext()
        b.cardUserspace.isVisible = vm.connection != Connection.RELAY &&
            (vm.connectionAdvanced || vm.connection == Connection.USERSPACE)
        b.connectionAdvanced.isVisible = vm.connection != Connection.RELAY
        connectionCards().forEach { (card, choice) ->
            val on = vm.connection == choice.first && vm.connector == choice.second
            card.isChecked = on
            card.strokeWidth = ctx.dp(if (on) 2 else 1)
            card.strokeColor = ctx.themeColor(if (on) M.attr.colorPrimary else M.attr.colorOutlineVariant)
            card.setCardBackgroundColor(if (on) ctx.themeColor(M.attr.colorPrimaryContainer)
                else ctx.themeColor(M.attr.colorSurface))
            // The checked card's own state layer greys the container in light mode; the fill and stroke show selection.
            card.setCardForegroundColor(android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT))
        }
    }

    // ---- Step 3: Check

    private fun setupCheckRows() {
        b.checkListener.fix.setOnClickListener {
            app.settings.listenerEnabled = true
            runCatching { BridgeService.start(requireContext()) }
            vm.runChecks()
        }
        b.checkTailscale.fix.setOnClickListener { openTailscale() }
        b.checkPort.fix.setOnClickListener { openTailscale() }
    }

    /** The Tailscale setup screen walks through install, sign-in and turning it on; Back returns to the checks. */
    private fun openTailscale() = findNavController().navigate(R.id.tailscaleSetup)

    private fun renderChecks(c: AddAgentViewModel.Checks) {
        if (_b == null) return
        row(b.checkListener, getString(R.string.addagent_check_listener), c.listener,
            getString(R.string.addagent_check_listener_fail), fix = true)
        row(b.checkTailscale, c.ip?.let { getString(R.string.addagent_check_tailscale, it) }
            ?: getString(R.string.addagent_check_tailscale_none), c.tailscale,
            getString(R.string.addagent_check_tailscale_fail), fix = true)
        val portLabel = getString(R.string.addagent_check_port, c.portNumber)
        when {
            c.port == AddAgentViewModel.Check.FAIL ->
                row(b.checkPort, portLabel, c.port, getString(R.string.addagent_check_port_blocked), fix = false)
            else -> row(b.checkPort, portLabel, c.port, getString(R.string.addagent_check_port_fail), fix = false)
        }
        if (vm.step.value == 3) {
            b.next.isEnabled = c.canContinue
            b.next.text = getString(if (c.port == AddAgentViewModel.Check.WARN) R.string.addagent_continue_anyway
                else R.string.addagent_next)
        }
    }

    private fun row(r: AddagentCheckRowBinding, label: String, state: AddAgentViewModel.Check, reason: String, fix: Boolean) {
        val ctx = requireContext()
        r.label.text = label
        r.spinner.isVisible = state == AddAgentViewModel.Check.PENDING
        r.icon.isVisible = state != AddAgentViewModel.Check.PENDING
        val (icon, tint) = when (state) {
            AddAgentViewModel.Check.OK -> R.drawable.ic_check to ctx.themeColor(R.attr.colorSuccess)
            AddAgentViewModel.Check.FAIL -> R.drawable.ic_error to ctx.themeColor(M.attr.colorError)
            AddAgentViewModel.Check.WARN -> R.drawable.ic_warning to ctx.themeColor(M.attr.colorOnSecondaryContainer)
            AddAgentViewModel.Check.PENDING -> R.drawable.ic_check to ctx.themeColor(M.attr.colorOnSurfaceVariant)
        }
        r.icon.setImageResource(icon)
        r.icon.imageTintList = android.content.res.ColorStateList.valueOf(tint)
        val showReason = state == AddAgentViewModel.Check.FAIL || state == AddAgentViewModel.Check.WARN
        r.reason.isVisible = showReason
        r.reason.text = reason
        r.fix.isVisible = fix && state == AddAgentViewModel.Check.FAIL
        if (state == AddAgentViewModel.Check.WARN) {
            r.root.background = GradientDrawable().apply {
                cornerRadius = ctx.dp(12).toFloat()
                setColor(ctx.themeColor(M.attr.colorSecondaryContainer))
            }
            r.label.setTextColor(ctx.themeColor(M.attr.colorOnSecondaryContainer))
            r.reason.setTextColor(ctx.themeColor(M.attr.colorOnSecondaryContainer))
        } else {
            r.root.background = null
            r.label.setTextColor(ctx.themeColor(M.attr.colorOnSurface))
            r.reason.setTextColor(ctx.themeColor(M.attr.colorOnSurfaceVariant))
        }
        val stateWord = getString(when (state) {
            AddAgentViewModel.Check.PENDING -> R.string.addagent_state_pending
            AddAgentViewModel.Check.OK -> R.string.addagent_state_ok
            AddAgentViewModel.Check.FAIL -> R.string.addagent_state_fail
            AddAgentViewModel.Check.WARN -> R.string.addagent_state_warn
        })
        r.texts.contentDescription = "$label, $stateWord" + (if (showReason) ". $reason" else "")
    }

    // ---- Navigation between steps

    private fun next() {
        when (vm.step.value) {
            1 -> {
                if (!vm.validateName()) { b.nameLayout.error = vm.nameError; return }
                b.nameLayout.error = null
                hideKeyboard()
                vm.step.value = 2
            }
            2 -> if (vm.connection == Connection.RELAY) {
                startActivity(Intent(requireContext(), cc.bridgit.app.relay.RelayActivity::class.java)
                    .putExtra("suggested_name", vm.name.trim())
                    .putExtra(cc.bridgit.app.relay.RelayActivity.EXTRA_CONNECTOR, vm.connector?.name))
            } else vm.step.value = 3
            3 -> {
                if (!vm.checks.value.canContinue) return
                cc.bridgit.app.security.OwnerCheck.run(this, getString(R.string.settings_max_control)) {
                    if (!vm.createAgent()) {
                        // Name taken meanwhile, or 16 agents: back to the name with the reason.
                        vm.step.value = 1
                        b.nameLayout.error = vm.nameError
                        return@run
                    }
                    vm.step.value = 4
                }
            }
        }
    }

    private fun onBack() {
        when (vm.step.value) {
            2, 3 -> vm.step.value -= 1
            1 -> leave()
            else -> onClose()
        }
    }

    /**
     * ✕, system back or Done on the prompt step: ask first if the key hasn't been taken (AGT-05).
     * Copy stays on the screen with the usual confirmation, so the key is never dropped unseen;
     * dismissing the dialog also stays (A0217).
     */
    private fun onClose() {
        if (vm.step.value == 4 && vm.key != null && !vm.acknowledged) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.addagent_leave_title)
                .setMessage(R.string.addagent_leave_body)
                .setPositiveButton(R.string.addagent_leave_copy) { _, _ -> warnFirst { exportSetup { copy(it.prompt) } } }
                .setNegativeButton(R.string.addagent_leave) { _, _ -> leave() }
                .show()
        } else leave()
    }

    private fun done() = onClose()

    private fun leave() {
        if (vm.step.value == 4) vm.acknowledge()
        hideKeyboard()
        setSecure(false)
        back.isEnabled = false
        if (!findNavController().popBackStack()) requireActivity().finish()
    }

    // ---- Step 4: Setup prompt

    private fun setupPrompt() {
        b.expandPrompt.setOnClickListener {
            vm.promptExpanded = !vm.promptExpanded
            renderPromptExpansion()
        }
        renderPromptExpansion()
        b.copyPrompt.setOnClickListener { warnFirst { exportSetup { copy(it.prompt) } } }
        b.share.setOnClickListener { warnFirst { exportSetup { share(it.prompt) } } }
        b.qr.setOnClickListener { warnFirst { exportSetup { showQr(it.qr) } } }
        b.troubleshoot.setOnClickListener { showTroubleshooting() }
        b.keyGoneRotate.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.agents_rotate_body, vm.name))
                .setPositiveButton(R.string.agents_rotate_confirm) { _, _ -> vm.rotate(); render(4) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun renderPrompt() {
        if (_b == null || vm.step.value != 4) return
        val gone = vm.keyGone.value || vm.key == null
        b.keyGone.isVisible = gone
        b.promptSection.isVisible = !gone
        b.waitCard.isVisible = vm.agentId != null
        renderPowers()
        b.toolbar.menu.findItem(R.id.addagent_copy_key).isVisible = !gone
        b.toolbar.menu.findItem(R.id.addagent_pair).isVisible = !gone
        if (gone) return
        val n = vm.name.trim()
        b.promptIntro.text = getString(R.string.addagent_prompt_intro, n)
        b.qr.isVisible = vm.connection != Connection.USERSPACE
        // The address may only be known now (Tailscale came up during the check).
        vm.refreshPrompt()
        val text = vm.prompt ?: return
        val span = SpannableString(text)
        vm.key?.let { k ->
            val i = text.indexOf(k)
            if (i >= 0) {
                val start = text.lastIndexOf('\n', i).let { if (it < 0) 0 else it + 1 }
                val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                val ctx = requireContext()
                span.setSpan(BackgroundColorSpan(ctx.themeColor(M.attr.colorSecondaryContainer)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(ForegroundColorSpan(ctx.themeColor(M.attr.colorOnSecondaryContainer)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        b.promptText.text = span
        renderPromptExpansion()
    }

    private fun renderPromptExpansion() {
        val expanded = vm.promptExpanded
        b.promptText.maxLines = if (expanded) Int.MAX_VALUE else 3
        b.promptText.ellipsize = if (expanded) null else android.text.TextUtils.TruncateAt.END
        b.promptScroll.layoutParams = b.promptScroll.layoutParams.apply {
            height = if (expanded) (resources.displayMetrics.heightPixels * 0.45f).toInt()
                else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        b.promptFade.isVisible = expanded
        b.expandPrompt.setText(if (expanded) R.string.addagent_prompt_collapse else R.string.addagent_prompt_expand)
        if (!expanded) b.promptScroll.scrollTo(0, 0)
    }

    // ---- Max Agentic Control, at the top of step 4: every feature on and one guided permission pass.
    // Limited (nothing new turned on) stays one tap away.

    private fun isMaximum() = vm.agentId?.let { cc.bridgit.app.power.PowerAccess(app).isMaximum(it) } == true

    private fun renderPowers() {
        if (_b == null) return
        val panel = b.powers.root
        val id = vm.agentId
        panel.isVisible = id != null
        if (id != null) {
            // Built once per agent, then refreshed in place so open groups stay open.
            if (panel.tag != id || panel.childCount == 0) {
                panel.tag = id
                panel.removeAllViews()
                panel.addView(cc.bridgit.app.ui.agents.AgentPermissionsDialog.content(this, id))
            } else {
                (panel.getChildAt(0).tag as? Runnable)?.run()
            }
        }
    }

    private fun renderWait(w: AddAgentViewModel.Wait) {
        if (_b == null || vm.step.value != 4) return
        val ctx = requireContext()
        val n = vm.name.trim()
        val connected = w.kind == AddAgentViewModel.WaitKind.CONNECTED
        elevateWaiting(connected || vm.acknowledged)
        b.doneOutlined.isVisible = !connected
        b.doneFilled.isVisible = connected
        when (w.kind) {
            AddAgentViewModel.WaitKind.WAITING -> {
                b.waitCard.setCardBackgroundColor(ctx.themeColor(M.attr.colorSecondaryContainer))
                val on = ctx.themeColor(M.attr.colorOnSecondaryContainer)
                b.waitTitle.setTextColor(on); b.waitSub.setTextColor(on)
                b.waitTitle.text = getString(R.string.addagent_waiting, n)
                b.waitSub.isVisible = false
                b.pulseDot.isVisible = true; b.pulseRing.isVisible = true; b.waitIcon.isVisible = false
                b.troubleshoot.isVisible = false
                startPulse()
            }
            AddAgentViewModel.WaitKind.CONNECTED -> {
                stopPulse()
                b.waitCard.setCardBackgroundColor(ctx.themeColor(R.attr.colorSuccessContainer))
                val on = ctx.themeColor(R.attr.colorOnSuccessContainer)
                b.waitTitle.setTextColor(on); b.waitSub.setTextColor(on)
                b.waitTitle.text = getString(R.string.addagent_connected, n)
                val e = w.entry
                b.waitSub.isVisible = e != null
                if (e != null) {
                    b.waitSub.text = listOfNotNull("${e.method ?: ""} ${e.endpoint ?: ""}".trim().ifEmpty { null },
                        e.durationMs?.let { "$it ms" }, e.sourceIp).joinToString(" · ")
                    b.waitSub.setTextAppearance(R.style.TextAppearance_Bridgit_Mono)
                    b.waitSub.setTextColor(on)
                }
                b.pulseDot.isVisible = false; b.pulseRing.isVisible = false
                b.waitIcon.isVisible = true
                b.waitIcon.setImageResource(R.drawable.ic_check)
                b.waitIcon.imageTintList = android.content.res.ColorStateList.valueOf(on)
                b.troubleshoot.isVisible = false
            }
            AddAgentViewModel.WaitKind.NO_CALL -> {
                stopPulse()
                b.waitCard.setCardBackgroundColor(ctx.themeColor(M.attr.colorSurfaceContainer))
                b.waitTitle.setTextColor(ctx.themeColor(M.attr.colorOnSurface))
                b.waitTitle.text = getString(R.string.addagent_no_call)
                b.waitSub.isVisible = false
                b.pulseDot.isVisible = false; b.pulseRing.isVisible = false
                b.waitIcon.isVisible = true
                b.waitIcon.setImageResource(R.drawable.ic_info)
                b.waitIcon.imageTintList = android.content.res.ColorStateList.valueOf(ctx.themeColor(M.attr.colorOnSurfaceVariant))
                b.troubleshoot.isVisible = true
            }
        }
    }

    /** Bring the live connection status above the powers and prompt once handed over. */
    private fun elevateWaiting(elevated: Boolean) {
        if (!elevated) return
        val parent = b.waitCard.parent as ViewGroup
        if (parent.indexOfChild(b.waitCard) == 0) return
        parent.removeView(b.waitCard)
        b.waitCard.layoutParams = b.waitCard.layoutParams.apply {
            (this as? ViewGroup.MarginLayoutParams)?.apply {
                topMargin = 0
                bottomMargin = requireContext().dp(16)
            }
        }
        parent.addView(b.waitCard, 0)
        b.scroll.post { _b?.scroll?.smoothScrollTo(0, 0) }
    }

    /** Soft breathing pulse; a static dot when Android animations are disabled. */
    private fun startPulse() {
        if (pulse != null) return
        if (!ValueAnimator.areAnimatorsEnabled()) { b.pulseRing.isVisible = false; return }
        val ring = b.pulseRing
        val sx = ObjectAnimator.ofFloat(ring, View.SCALE_X, 1f, 1.35f)
        val sy = ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1f, 1.35f)
        val a = ObjectAnimator.ofFloat(ring, View.ALPHA, 0.3f, 0f)
        listOf(sx, sy, a).forEach { it.repeatCount = ValueAnimator.INFINITE; it.duration = 2400 }
        pulse = AnimatorSet().apply {
            playTogether(sx, sy, a)
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulse() { pulse?.cancel(); pulse = null }

    /** Before the key first leaves the phone (copy, share, QR, pairing), say what handing it over means. Once per key. */
    private fun warnFirst(then: () -> Unit) {
        if (vm.trustWarned) return then()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.addagent_trust_title)
            .setMessage(R.string.addagent_trust_body)
            .setPositiveButton(R.string.addagent_trust_confirm) { _, _ -> vm.trustWarned = true; then() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var exporting = false
    private var exportJob: Job? = null

    private fun exportSetup(then: (AddAgentViewModel.Export) -> Unit) {
        if (exporting) return
        exporting = true
        exportJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val export = vm.prepareExport()
                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || vm.step.value != 4) return@launch
                if (export == null) {
                    // Reuse the connection checks; do not hand out a dead endpoint or lose the key.
                    vm.step.value = 3
                    vm.runChecks()
                } else then(export)
            } finally {
                exporting = false
            }
        }
    }

    private fun copy(text: String, quiet: Boolean = false) {
        if (text.isEmpty()) return
        val cm = requireContext().getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(MainActivity.CLIP_LABEL, text)
        // SEC-11: Android hides the preview of a sensitive clip.
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        vm.acknowledge()
        renderWait(vm.wait.value)
        if (!quiet) snack(getString(R.string.addagent_copied, vm.name.trim()))
    }

    private fun share(prompt: String) {
        val name = vm.name.trim()
        // A0213: name the device by its domain name first; the friendly name is display only.
        val id = AddAgentViewModel.identity(app)
        val device = id.domainName?.let { "$it (${id.friendlyName})" } ?: id.friendlyName
        // Share sends a note for the person on top of the prompt; Copy still copies the bare prompt.
        val text = cc.bridgit.app.ui.common.ShareText.prompt(getString(R.string.share_agent_heading, name, device), getString(R.string.share_agent_note, name, device), prompt)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.addagent_share_subject, name, device))
        vm.acknowledge()
        renderWait(vm.wait.value)
        startActivity(Intent.createChooser(send, getString(R.string.addagent_share_title)))
    }

    private fun showQr(payload: String) {
        val ctx = requireContext()
        val sb = AddagentQrSheetBinding.inflate(layoutInflater)
        val size = ctx.dp(264)
        sb.qr.setImageBitmap(qrBitmap(payload, size))
        sb.qr.contentDescription = getString(R.string.addagent_qr_desc, vm.name.trim())
        showSheet(sb.root)
    }

    private fun qrBitmap(payload: String, size: Int): Bitmap = cc.bridgit.app.ui.common.qrBitmap(requireContext(), payload, size)

    private fun showTroubleshooting() {
        val sb = AddagentTextSheetBinding.inflate(layoutInflater)
        sb.title.text = getString(R.string.addagent_troubleshooting)
        sb.body.text = if (vm.connection == Connection.USERSPACE) getString(R.string.addagent_trouble_userspace)
            else getString(R.string.addagent_trouble_direct, app.address().baseUrl)
        showSheet(sb.root)
    }

    private fun showSheet(content: View) {
        sheet?.dismiss()
        sheet = BottomSheetDialog(requireContext()).apply {
            setContentView(content)
            // The sheet is its own window: it needs FLAG_SECURE too (AGT-08).
            cc.bridgit.app.ui.common.ScreenshotProtection.setSecure(window)
            show()
        }
    }

    // ---- Pair with code (AGT-13)

    private fun openPairing() {
        val id = vm.agentId ?: return
        val k = vm.key ?: return
        vm.pairWindow = app.pairing.open(id, vm.name.trim(), k)
        findNavController().navigate(R.id.pairCode)
    }

    // ---- Helpers

    private fun setSecure(on: Boolean) {
        val w = activity?.window ?: return
        if (on) cc.bridgit.app.ui.common.ScreenshotProtection.setSecure(w)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun snack(text: String) {
        Snackbar.make(b.root, text, Snackbar.LENGTH_LONG).setAnchorView(b.bottomBar).show()
    }

    private fun hideKeyboard() {
        val v = view ?: return
        ViewCompat.getWindowInsetsController(v)?.hide(WindowInsetsCompat.Type.ime())
    }
}
