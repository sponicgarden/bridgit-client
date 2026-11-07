package cc.bridgit.app.ui.power

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.R as M
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.capability.granted
import cc.bridgit.app.databinding.AccessRowBinding
import cc.bridgit.app.databinding.FragmentPowersBinding
import cc.bridgit.app.ui.MainActivity
import cc.bridgit.app.power.PowerAccess
import cc.bridgit.app.power.PowerPlan
import cc.bridgit.app.power.PowerPlan.Kind
import cc.bridgit.app.power.PowerPlan.Status
import cc.bridgit.app.power.PowerPlan.Step
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import cc.bridgit.app.ui.home.UiActions

/**
 * Maximum powers: shown after the owner confirms (see [confirmMaximum]). On arrival in `auto` mode
 * it turns every switch on and every setting to its most capable choice ([PowerAccess.maxEverything]). A checklist of each Android permission and special access this build declares,
 * with live status; Continue asks with Android's own dialogs, then opens each real settings page
 * in turn. Re-checks on return, skips what's on, and ends with a summary. Nothing here imitates
 * a system dialog (REQ-05). Optional `agentId`: the agent that was just given every grant.
 * With `auto` (Max Agentic Control) it starts at once and moves on after each step by itself, also past
 * a step that comes back off, so every step is offered once (see [PowerPlan.autoNext]); what is still off
 * is listed at the end, each with Try again. On a settings page Bridgit
 * comes back by itself as soon as the switch is on (see [watch]), so the owner never presses Back.
 */
class PowersFragment : Fragment() {
    private var _b: FragmentPowersBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private val access by lazy { PowerAccess(app) }

    private var steps: List<Step> = emptyList()
    /** Steps the owner was shown and came back from still off. */
    private val declined = mutableSetOf<String>()
    /** The settings page opened last; checked when the owner comes back. */
    private var opened: String? = null
    /** Runtime steps in the batch Android is asking about now. */
    private var asking: List<String> = emptyList()
    private var askedAt = 0L
    private var last: Map<String, Status> = emptyMap()
    private val auto get() = arguments?.getBoolean(ARG_AUTO) == true
    private var guidanceDialog: androidx.appcompat.app.AlertDialog? = null
    /** Auto mode already started; kept on the instance so coming back from Features doesn't start it again. */
    private var started = false
    /** The ADB pass ([PowerAccess.grantOverAdb]) has run, or is running ([adbRunning]); it runs once per screen. */
    private var adbTried = false
    private var adbRunning = false
    /** Steps this auto pass has already opened; each opens once (see [PowerPlan.autoNext]). */
    private val tried = mutableSetOf<String>()
    /** Accessibility services still off when Android's list (not one service's page) was opened for [opened]. */
    private var a11yOffAtOpen: Set<String> = emptySet()

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val ctx = context ?: return@registerForActivityResult
        val quick = SystemClock.elapsedRealtime() - askedAt < NO_DIALOG_MS
        for (id in asking) {
            val s = steps.firstOrNull { it.id == id } ?: continue
            if (!s.permissions.all(ctx::granted)) declined += id
        }
        // A quick denial without rationale means Android didn't show the dialog: offer App info.
        if (quick && result.isNotEmpty() && result.values.none { it } && result.keys.none { shouldShowRequestPermissionRationale(it) })
            view?.let { v -> com.google.android.material.snackbar.Snackbar.make(v, R.string.power_denied_settings, 6000)
                .setAction(R.string.perm_open_settings) { UiActions.openAppDetails(ctx) }.setAnchorView(b.bottomBar).show() }
        // The listener adds camera, mic and location service types only when restarted from a visible screen (ARC-03).
        if (result.values.any { it } && app.settings.listenerEnabled) runCatching { BridgeService.start(ctx) }
        val shown = asking
        asking = emptyList()
        // Health Connect's prompt can come back at once without showing anything (seen on a Galaxy Tab, Android 16):
        // its own page for Bridgit is then the way in, so open that instead of leaving the row off.
        val health = steps.firstOrNull { it.id == HEALTH }
        if (shown == listOf(HEALTH) && quick && health != null && !access.granted(health) && access.hasHealthPage()) {
            declined -= HEALTH
            refresh()
            // Posted, like autoNext: the fragment resumes right after this result and would read the page as skipped.
            view?.post { if (_b != null && opened == null && asking.isEmpty()) open(health) }
            return@registerForActivityResult
        }
        refresh()
        // Posted: the fragment resumes right after this result, and a page opened now would read as skipped.
        autoNext()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentPowersBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        savedInstanceState?.let { s ->
            declined += s.getStringArrayList(STATE_DECLINED).orEmpty()
            opened = s.getString(STATE_OPENED)
            asking = s.getStringArrayList(STATE_ASKING).orEmpty()
            adbTried = s.getBoolean(STATE_ADB_TRIED)
            started = s.getBoolean(STATE_STARTED)
            tried += s.getStringArrayList(STATE_TRIED).orEmpty()
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.toolbar.updatePadding(top = bars.top)
            b.bottomBar.updatePadding(bottom = bars.bottom + requireContext().dp(12)); insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        val agent = arguments?.getString(ARG_AGENT)?.let { app.agents.get(it) }
        b.intro.text = when {
            auto && agent != null -> getString(R.string.power_intro_auto_agent, agent.name)
            auto -> getString(R.string.power_intro_auto)
            agent != null -> getString(R.string.power_intro_agent, agent.name)
            else -> getString(R.string.power_intro)
        }
        b.openFeatures.setOnClickListener { findNavController().navigate(R.id.features) }
        b.next.setOnClickListener { continueNext() }
        steps = access.steps()
        if (auto && savedInstanceState == null && !started) {
            started = true
            // Every switch on and every setting at its most capable choice; nothing is left to toggle by hand.
            val undo = access.maxEverything(allAgents = agent == null)
            if (agent == null) com.google.android.material.snackbar.Snackbar.make(b.root, R.string.power_max_switched, 6000)
                .setAction(R.string.undo) { undo(); refresh() }.setAnchorView(b.bottomBar).show()
            adbFirst { autoNext() }
        } else if (savedInstanceState?.getBoolean(STATE_ADB_RUNNING) == true) {
            // Recreated (rotation) while the ADB pass ran: its coroutine was cancelled with the old view, so run the rest.
            adbTried = false
            adbFirst { autoNext() }
        }
    }

    override fun onResume() {
        super.onResume()
        stopWatching()
        // Back from a settings page: still off means the owner skipped it.
        opened?.let { id ->
            opened = null
            val s = steps.firstOrNull { it.id == id }
            if (id == PowerPlan.RESTRICTED) {
                // Restricted settings allowed: try the blocked services again.
                val restrictable = steps.filter(PowerPlan::isRestrictable).map { it.id }.toSet()
                declined -= restrictable
                tried -= restrictable
            }
            // Android's whole accessibility list was open and the owner turned on another Bridgit service there:
            // not a skip, this one simply comes next.
            val otherOn = s != null && a11yOffAtOpen.any { o -> o != id && steps.firstOrNull { it.id == o }?.let(access::granted) == true }
            if (s != null && s.kind != Kind.RESTRICTED && !access.granted(s)) {
                if (otherOn) tried -= id else declined += id
            }
            if (id == PowerPlan.RESTRICTED) declined += id
            a11yOffAtOpen = emptySet()
        }
        refresh()
        // Every return to this screen (a settings page, Feature enablement, another app) picks the pass up again.
        autoNext()
    }

    /**
     * With Bridgit's own ADB link, turns on everything ADB can (installs included) before any page opens, so the
     * owner flips no switch; then [then]. Without it, or once tried, [then] at once.
     */
    private fun adbFirst(then: () -> Unit) {
        if (adbTried || !access.adbReady()) { then(); return }
        adbTried = true
        adbRunning = true
        b.next.isEnabled = false
        b.next.text = getString(R.string.power_adb_working)
        viewLifecycleOwner.lifecycleScope.launch {
            access.grantOverAdb()
            adbRunning = false
            val bb = _b ?: return@launch
            bb.next.isEnabled = true
            refresh()
            then()
        }
    }

    /**
     * Auto mode: the next step this pass hasn't opened yet, whatever happened to the last one; nothing once
     * every step was offered. Waits while a prompt or page is open, or the ADB pass runs.
     */
    private fun autoNext() {
        if (!auto || !started) return
        view?.post {
            if (_b != null && !adbRunning && guidanceDialog == null && opened == null && asking.isEmpty() &&
                PowerPlan.autoNext(steps, status(), tried) != null) continueNext(pass = true)
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putStringArrayList(STATE_DECLINED, ArrayList(declined))
        out.putString(STATE_OPENED, opened)
        out.putStringArrayList(STATE_ASKING, ArrayList(asking))
        out.putBoolean(STATE_ADB_TRIED, adbTried)
        out.putBoolean(STATE_ADB_RUNNING, adbRunning)
        out.putStringArrayList(STATE_TRIED, ArrayList(tried))
        out.putBoolean(STATE_STARTED, started)
    }

    override fun onDestroyView() { guidanceDialog?.dismiss(); guidanceDialog = null; super.onDestroyView(); _b = null }

    override fun onDestroy() { super.onDestroy(); stopWatching() }

    private fun status() = PowerPlan.status(steps, access::granted, declined)

    private fun refresh() {
        val bb = _b ?: return
        val st = status()
        val visible = steps.filter { st[it.id] != null }
        bb.steps.removeAllViews()
        visible.forEach { addRow(bb, it, st.getValue(it.id)) }

        val next = PowerPlan.next(steps, st)
        bb.next.text = if (adbRunning) getString(R.string.power_adb_working)
            else next?.let { getString(R.string.power_continue, access.title(it)) } ?: getString(R.string.power_done)

        val on = visible.count { st[it.id] == Status.ON }
        val skipped = visible.filter { st[it.id] == Status.DECLINED }
        bb.summaryCard.isVisible = next == null
        if (next == null) {
            // A switch left off that Bridgit could turn on itself with ADB set up once: say so, and link there.
            val adbHint = cc.bridgit.app.BuildConfig.PRIVATE_CONTROL && !access.adbReady() &&
                skipped.any { it.kind != Kind.RUNTIME }
            bb.summary.text = buildString {
                append(getString(R.string.power_summary, on, visible.size))
                if (skipped.isNotEmpty()) append("\n").append(getString(R.string.power_summary_skipped, skipped.joinToString(", ") { access.title(it) }))
                if (adbHint) append("\n\n").append(getString(R.string.power_adb_hint))
            }
            bb.summaryCard.isClickable = adbHint
            bb.summaryCard.setOnClickListener(if (adbHint) View.OnClickListener { findNavController().navigate(R.id.adbSetup) } else null)
        }
        // Features: every switch with what it still needs, straight from the registry (later modules join on their own).
        val modules = app.registry.switchable()
        val notReady = modules.filter { !it.enabled || it.blockedReason() != null }
        bb.features.text = if (notReady.isEmpty()) getString(R.string.power_features_ready, modules.size)
            else getString(R.string.power_features_some, modules.size - notReady.size, modules.size, notReady.joinToString(", ") { it.title })

        // Announce only what changed (ui-spec 7).
        if (last.isNotEmpty()) visible.filter { last[it.id] != null && last[it.id] != st[it.id] }.forEach {
            @Suppress("DEPRECATION")
            bb.root.announceForAccessibility(getString(R.string.access_now, access.title(it), label(st.getValue(it.id)).lowercase()))
        }
        last = st
    }

    private fun label(s: Status) = getString(when (s) {
        Status.ON -> R.string.access_status_on
        Status.OFF -> R.string.access_status_off
        Status.DECLINED -> R.string.power_status_skipped
    })

    private fun addRow(bb: FragmentPowersBinding, s: Step, st: Status) {
        val ctx = bb.root.context
        val r = AccessRowBinding.inflate(layoutInflater, bb.steps, false)
        val title = access.title(s)
        val unlocks = access.unlocks(s)
        r.title.text = title
        r.unlocks.text = unlocks
        r.extra.text = access.instructions(s)
        r.extra.isVisible = st != Status.ON && r.extra.text.isNotEmpty()
        r.status.text = label(st)
        val (bg, fg) = when (st) {
            Status.ON -> R.attr.colorSuccessContainer to R.attr.colorOnSuccessContainer
            Status.OFF -> M.attr.colorSurfaceContainerHigh to M.attr.colorOnSurfaceVariant
            Status.DECLINED -> M.attr.colorSecondaryContainer to M.attr.colorOnSecondaryContainer
        }
        r.status.backgroundTintList = ColorStateList.valueOf(ctx.themeColor(bg))
        r.status.setTextColor(ctx.themeColor(fg))
        if (st == Status.ON) {
            val check = ContextCompat.getDrawable(ctx, R.drawable.ic_check)?.mutate()
            check?.setBounds(0, 0, ctx.dp(14), ctx.dp(14))
            check?.setTint(ctx.themeColor(fg))
            r.status.setCompoundDrawablesRelative(check, null, null, null)
            r.action.isVisible = false
            r.root.isClickable = false
            r.root.contentDescription = getString(R.string.access_row_cd, title, label(st).lowercase(), unlocks)
        } else {
            val button = getString(when {
                st == Status.DECLINED -> R.string.power_btn_try_again
                s.kind == Kind.RUNTIME -> R.string.access_btn_grant
                else -> R.string.access_btn_turn_on
            })
            r.action.text = button
            r.action.setOnClickListener { go(s) }
            r.root.setOnClickListener { go(s) }
            r.root.contentDescription = getString(R.string.access_row_cd_button, title, label(st).lowercase(), unlocks, button)
        }
        bb.steps.addView(r.root)
    }

    /**
     * Continue: every pending runtime permission in one batch first (Android shows its dialogs in turn), then each page.
     * [pass]: the auto pass, which takes the next step it hasn't opened yet and never leaves the screen.
     */
    private fun continueNext(pass: Boolean = false) {
        if (adbRunning) return
        if (!adbTried && access.adbReady()) { adbFirst { continueNext(pass) }; return }
        val st = status()
        val next = (if (pass) PowerPlan.autoNext(steps, st, tried) else PowerPlan.next(steps, st))
            ?: run { if (!pass) findNavController().navigateUp(); return }
        if (next.kind == Kind.RUNTIME && next.id != HEALTH && next.id != "background_location") {
            val perms = PowerPlan.pendingRuntime(steps, st, app::granted)
            if (perms.isNotEmpty()) {
                asking = steps.filter { it.kind == Kind.RUNTIME && st[it.id] == Status.OFF && it.permissions.any { p -> p in perms } }.map { it.id }
                tried += asking
                ask(perms)
                return
            }
        }
        go(next)
    }

    /** One step: its runtime prompt, or its settings page when the permissions are in but more is needed (notification channel). */
    private fun go(s: Step) {
        val ctx = requireContext()
        if (s.id == "background_location") {
            if (!ctx.granted(android.Manifest.permission.ACCESS_COARSE_LOCATION) &&
                !ctx.granted(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                declined += s.id
                refresh()
                autoNext()
                return
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                open(s, appInfo = true)
                return
            }
        }
        if (s.kind == Kind.RUNTIME && !s.permissions.all(ctx::granted)) {
            val pending = s.permissions.filter { !ctx.granted(it) }
            // Health Connect turned down once: its own page for Bridgit, where every record type is one switch.
            if (s.id == HEALTH && s.id in declined && access.hasHealthPage()) { open(s); return }
            // Android has stopped asking: its own page is the only place to change it.
            if (s.id in declined && pending.none { shouldShowRequestPermissionRationale(it) } && s.id != HEALTH) { open(s, appInfo = true); return }
            asking = listOf(s.id)
            tried += s.id
            ask(pending)
            return
        }
        open(s)
    }

    private fun ask(perms: List<String>) {
        askedAt = SystemClock.elapsedRealtime()
        permLauncher.launch(perms.toTypedArray())
    }

    private fun open(s: Step, appInfo: Boolean = false, explained: Boolean = false) {
        val instructions = access.instructions(s) ?: if (appInfo) getString(R.string.power_help_app_info) else null
        if (!explained && instructions != null) {
            if (guidanceDialog != null) return
            guidanceDialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(access.title(s))
                .setMessage(instructions)
                .setPositiveButton(R.string.power_help_open) { _, _ ->
                    guidanceDialog = null
                    open(s, appInfo, explained = true)
                }
                .setNegativeButton(R.string.power_help_skip) { _, _ ->
                    guidanceDialog = null
                    declined += s.id
                    tried += s.id
                    refresh()
                    autoNext()
                }
                .setOnCancelListener { guidanceDialog = null }
                .show()
            return
        }
        tried += s.id
        opened = s.id
        a11yOffAtOpen = if (s.id.startsWith(PowerPlan.A11Y))
            steps.filter { it.id.startsWith(PowerPlan.A11Y) && !access.granted(it) }.map { it.id }.toSet() else emptySet()
        val ctx = requireContext()
        if (appInfo) { UiActions.openAppDetails(ctx); return }
        var list = false
        runCatching { startActivity(access.intent(s)) }
            .recoverCatching { list = true; startActivity(access.fallback(s)) }
            .onFailure { UiActions.openAppDetails(ctx) }
        // From Android 11 "Display over other apps" opens the list of every app, not Bridgit's own switch.
        if (s.id == "overlay" && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            android.widget.Toast.makeText(ctx, getString(R.string.power_find_and_turn_on,
                ctx.applicationInfo.loadLabel(ctx.packageManager)), android.widget.Toast.LENGTH_LONG).show()
        // Older Android has no page for one service, only the whole list: say which one to turn on.
        if (list && PowerPlan.isRestrictable(s)) {
            android.widget.Toast.makeText(ctx, getString(R.string.power_pick_in_list, access.serviceName(s)), android.widget.Toast.LENGTH_LONG).show()
            if (s.id.startsWith(PowerPlan.A11Y))
                a11yOffAtOpen = steps.filter { it.id.startsWith(PowerPlan.A11Y) && !access.granted(it) }.map { it.id }.toSet()
        }
        val group = a11yOffAtOpen.mapNotNull { id -> steps.firstOrNull { it.id == id } }.ifEmpty { listOf(s) }
        if (PowerPlan.returnsOnGrant(s)) watch(group)
    }

    /**
     * While a settings page is open, checks its switch every [POLL_MS] and brings Bridgit back once one
     * of [group] is on (on Android's whole accessibility list, whichever Bridgit service the owner turns on).
     * Android allows that from the background once Display over other apps is on
     * (the first special step); without it the start is dropped and the owner presses Back as before.
     */
    private fun watch(group: List<Step>) {
        stopWatching()
        val app = app
        val access = access
        val until = SystemClock.elapsedRealtime() + WATCH_MS
        val tick = object : Runnable {
            override fun run() {
                if (SystemClock.elapsedRealtime() > until) { pending = null; return }
                if (group.none(access::granted)) { handler.postDelayed(this, POLL_MS); return }
                // A moment for the switch animation and any confirm dialog to finish.
                pending = Runnable {
                    pending = null
                    runCatching {
                        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                }.also { handler.postDelayed(it, SETTLE_MS) }
            }
        }
        pending = tick
        handler.postDelayed(tick, POLL_MS)
    }

    companion object {
        const val ARG_AGENT = "agentId"
        const val ARG_AUTO = "auto"
        private const val HEALTH = "health"
        private const val NO_DIALOG_MS = 600L
        private const val POLL_MS = 400L
        private const val SETTLE_MS = 500L
        private const val WATCH_MS = 3 * 60_000L

        /** Kept outside the fragment: it is stopped while the settings page is in front. */
        private val handler = Handler(Looper.getMainLooper())
        private var pending: Runnable? = null

        private fun stopWatching() { pending?.let(handler::removeCallbacks); pending = null }
        private const val STATE_DECLINED = "declined"
        private const val STATE_OPENED = "opened"
        private const val STATE_ASKING = "asking"
        private const val STATE_ADB_TRIED = "adb_tried"
        private const val STATE_ADB_RUNNING = "adb_running"
        private const val STATE_TRIED = "tried"
        private const val STATE_STARTED = "started"

        /**
         * Asks the owner, then turns every switch on (and gives [agentId] every grant) and opens the
         * guided pass. [onCancel] runs when the owner backs out.
         */
        fun confirmMaximum(fragment: Fragment, agentId: String?, onCancel: () -> Unit = {}) {
            val app = fragment.requireActivity().application as BridgeApp
            val agent = agentId?.let { app.agents.get(it) }
            // The title stays one line beside the bolt; the agent's name goes in the lead under it.
            val title = fragment.getString(R.string.settings_max_control)
            val sheet = MaxControlSheet.build(fragment.requireContext(), title, app.registry.switchable(), agent?.name, PowerAccess(app).missingAccess())
            // Smaller top and bottom margins than Material's 80dp, so the whole list, notes, Warning card
            // and buttons fit on one screen.
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setBackgroundInsetTop(fragment.requireContext().dp(20))
                .setBackgroundInsetBottom(fragment.requireContext().dp(20))
                .setView(sheet)
                .setPositiveButton(R.string.power_confirm_yes) { _, _ ->
                    // Maximum control may need the fingerprint check (Settings › Security).
                    cc.bridgit.app.security.OwnerCheck.run(fragment, fragment.getString(R.string.settings_max_control), onCancel) {
                        val access = PowerAccess(app)
                        // The pass itself turns everything on (PowersFragment, auto), so Undo there covers it.
                        if (agent != null) {
                            access.grantAll(agent.id)
                            app.log.system("audit", "agent", fragment.getString(R.string.power_granted_log, agent.name))
                        }
                        fragment.findNavController().navigate(R.id.powers, bundleOf(ARG_AGENT to agent?.id, ARG_AUTO to true))
                    }
                }
                .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
                .setOnCancelListener { onCancel() }
                .apply {
                    if (cc.bridgit.app.BuildConfig.PRIVATE_CONTROL && !PowerAccess(app).adbReady()) {
                        setNeutralButton(R.string.power_adb_first) { _, _ ->
                            onCancel()
                            fragment.findNavController().navigate(R.id.adbSetup)
                        }
                    }
                }
                .show().apply {
                    // Keep the two decision buttons together even when Material stacks its button bar.
                    val yes = getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                    val cancel = getButton(android.content.DialogInterface.BUTTON_NEGATIVE)
                    val bar = yes.parent as ViewGroup
                    bar.removeView(cancel)
                    bar.removeView(yes)
                    val actions = LinearLayout(fragment.requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                        listOf(cancel, yes).forEach { button ->
                            button.textSize = 14f
                            button.minWidth = 0
                            button.minimumWidth = 0
                            button.setPadding(fragment.requireContext().dp(8), button.paddingTop,
                                fragment.requireContext().dp(8), button.paddingBottom)
                            addView(button, LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                        }
                    }
                    bar.addView(actions, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    val adb = getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
                    if (adb.isVisible) {
                        val index = bar.indexOfChild(adb)
                        bar.removeView(adb)
                        val assist = LinearLayout(fragment.requireContext()).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            addView(TextView(context).apply {
                                setText(R.string.power_adb_agent_help)
                                textSize = 14f
                                setTextColor(context.getColor(R.color.b_success))
                            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                            adb.textSize = 14f
                            adb.minWidth = 0
                            adb.minimumWidth = 0
                            adb.setPadding(context.dp(8), adb.paddingTop, context.dp(8), adb.paddingBottom)
                            addView(adb, LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                        }
                        bar.addView(assist, index, ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                }
        }

        /** The designed body of both Max Agentic Control dialogs: badge, title, lead, points panel, Warning card. */
        private fun confirmView(
            fragment: Fragment, title: String, lead: String, warning: String?,
            points: List<Triple<Int, String, String>>,
        ): View {
            val inflater = LayoutInflater.from(fragment.requireContext())
            val root = inflater.inflate(R.layout.power_confirm_dialog, null)
            root.findViewById<TextView>(R.id.title).text = title
            root.findViewById<TextView>(R.id.lead).text = lead
            val list = root.findViewById<LinearLayout>(R.id.points)
            for ((icon, head, detail) in points) {
                val row = inflater.inflate(R.layout.power_confirm_point, list, false)
                row.findViewById<ImageView>(R.id.icon).setImageResource(icon)
                row.findViewById<TextView>(R.id.head).text = head
                row.findViewById<TextView>(R.id.detail).text = detail
                list.addView(row)
            }
            val card = root.findViewById<View>(R.id.warning)
            if (warning == null) card.visibility = View.GONE
            else root.findViewById<TextView>(R.id.warningText).text = warning
            return root
        }

        /** Settings: every switch off after the owner confirms. Agents stay paired; Android permissions stay granted. */
        fun confirmAllOff(fragment: Fragment, onDone: () -> Unit) {
            val app = fragment.requireActivity().application as BridgeApp
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setView(confirmView(fragment, fragment.getString(R.string.power_off_title),
                    fragment.getString(R.string.power_off_lead), warning = null,
                    listOf(
                        Triple(R.drawable.ic_toggle, fragment.getString(R.string.power_off_switches),
                            fragment.getString(R.string.power_off_switches_detail)),
                        Triple(R.drawable.ic_shield, fragment.getString(R.string.power_off_permissions),
                            fragment.getString(R.string.power_off_permissions_detail)),
                    )))
                .setPositiveButton(R.string.power_off_yes) { _, _ -> PowerAccess(app).allSwitchesOff(); onDone() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
