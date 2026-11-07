package cc.bridgit.app.ui.tour

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings as SysSettings
import android.text.InputType
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.capability.Compass
import cc.bridgit.app.capability.LocationReader
import cc.bridgit.app.capability.address
import cc.bridgit.app.capability.granted
import cc.bridgit.app.databinding.FragmentTourBinding
import cc.bridgit.app.databinding.TourRowAgentBinding
import cc.bridgit.app.databinding.TourRowNeededBinding
import cc.bridgit.app.tour.Tour
import cc.bridgit.app.tour.TourKinds
import cc.bridgit.app.ui.common.modelText
import cc.bridgit.app.ui.common.Fmt
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import cc.bridgit.app.ui.feedback.handleFeedbackMenu
import cc.bridgit.app.ui.home.PermissionAsker
import cc.bridgit.app.ui.home.UiActions
import kotlin.math.roundToInt

/**
 * City tour (8.25, D30). One screen with two states: setup (pick kinds, agent, interval, fix
 * what's needed, Start) while no tour runs, and the running tour (status, heading, now playing,
 * history, Ask / Type / Pause / Skip / End). The state follows app.tours.state, so a tour the
 * agent ends (DELETE /v1/tour) or that hits the time limit drops back to setup by itself.
 *
 * The ongoing notification with Ask, Pause and End is TourNotifier (it follows app.tours.state).
 */
class TourFragment : Fragment() {
    private var _b: FragmentTourBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp

    private val perms = PermissionAsker(this) { requestRender() }

    /** Take photo (TOUR-07): Android's camera saves into the owner's gallery, then the photo goes to the agent. */
    private var pendingShot: android.net.Uri? = null
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingShot ?: return@registerForActivityResult
        pendingShot = null
        val ctx = context ?: return@registerForActivityResult
        if (ok) {
            val tours = app.tours
            lifecycleScope.launch {
                tours.photoTaken(uri)
                view?.let { Snackbar.make(it, getString(R.string.tour_photo_sent, tours.current()?.agent ?: ""), Snackbar.LENGTH_SHORT).show() }
            }
        } else runCatching { ctx.contentResolver.delete(uri, null, null) }
    }

    /** Bumped by settings, agents and speaker listeners (any thread); collected on main. */
    private val dirty = MutableStateFlow(0)
    private val onChange: () -> Unit = { dirty.update { it + 1 } }

    // Setup choices (kept across rotation).
    private val kinds = linkedSetOf<String>()
    private var agentId: String? = null
    private var intervalS = DEFAULT_INTERVAL
    private var agentsKey = ""
    private var neededKey = ""
    private var starting = false

    // Running state.
    private var shownTourId: String? = null
    private var liveAzimuth: Double? = null
    private var accuracyM: Float? = null
    private var lat: Double? = null
    private var lon: Double? = null
    private var batteryLine: String? = null
    private var haveFix = false
    private val said = TourSaidAdapter()

    // Ask.
    private var speech: TourSpeech? = null
    private var pressStartedListening = false
    private var pressDownAt = 0L
    private var askLimit: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentTourBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        savedInstanceState?.let { s ->
            s.getStringArrayList(KEY_KINDS)?.let { kinds.clear(); kinds.addAll(it) }
            pendingShot = androidx.core.os.BundleCompat.getParcelable(s, KEY_SHOT, android.net.Uri::class.java)
            agentId = s.getString(KEY_AGENT)
            intervalS = s.getInt(KEY_INTERVAL, DEFAULT_INTERVAL)
        }
        ViewCompat.setOnApplyWindowInsetsListener(b.toolbar) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets
        }
        val setupPad = b.setup.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.setup.root) { v, insets ->
            v.updatePadding(bottom = setupPad + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom); insets
        }
        val controlsPad = b.running.controls.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(b.running.controls) { v, insets ->
            v.updatePadding(bottom = controlsPad + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        val deviceAccess = b.toolbar.menu.add(R.string.app_device_access)
        deviceAccess.setOnMenuItemClickListener {
            cc.bridgit.app.appdevices.DeviceAccessDialogs.forApp(requireContext(), app, cc.bridgit.app.appdevices.AppDeviceAccess.TOUR)
            true
        }
        b.toolbar.inflateMenu(R.menu.feedback_overflow) // Report a bug / Request a feature (FBK-01)
        b.toolbar.setOnMenuItemClickListener {
            handleFeedbackMenu(it, "City tour") { app.tours.current()?.let { t -> "Tour with ${t.agent}" } }
        }

        buildSetup()
        buildRunning()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.tours.state.collect { render() } }
                launch { dirty.collect { render(); delay(250) } }
                launch { compassLoop() }
                // Whether the picked agent is listening changes with its polls, which fire no listener.
                launch { while (true) { delay(LISTEN_CHECK_MS); if (app.tours.current() == null) requestRender() } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.settings.addListener(onChange)
        app.agents.addListener(onChange)
        app.speaker.addListener(onChange)
    }

    override fun onResume() {
        super.onResume()
        requestRender() // permissions or Android's Location setting may have changed while away
    }

    override fun onStop() {
        app.settings.removeListener(onChange)
        app.agents.removeListener(onChange)
        app.speaker.removeListener(onChange)
        if (speech?.listening == true) cancelAsk()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_KINDS, ArrayList(kinds))
        outState.putString(KEY_AGENT, agentId)
        outState.putInt(KEY_INTERVAL, intervalS)
        outState.putParcelable(KEY_SHOT, pendingShot)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        askLimit?.cancel()
        speech?.destroy(); speech = null
        agentsKey = ""; neededKey = ""; shownTourId = null
        _b = null
    }

    private fun requestRender() = dirty.update { it + 1 }

    private fun render() {
        if (_b == null) return
        val t = app.tours.current()
        b.setup.root.isVisible = t == null
        b.running.root.isVisible = t != null
        if (t == null) { shownTourId = null; renderSetup() } else renderRunning(t)
    }

    // ---- Setup ----

    private fun buildSetup() {
        val s = b.setup
        s.kinds.removeAllViews()
        for (k in TourKinds.all) {
            val c = layoutInflater.inflate(R.layout.tour_chip, s.kinds, false) as Chip
            c.text = TourKinds.labels[k] ?: k
            c.isChecked = k in kinds
            c.setOnCheckedChangeListener { _, on -> if (on) kinds += k else kinds -= k; renderStart() }
            s.kinds.addView(c)
        }
        s.intervals.removeAllViews()
        for (sec in TourKinds.intervals) {
            val c = layoutInflater.inflate(R.layout.tour_chip, s.intervals, false) as Chip
            c.id = View.generateViewId()
            c.text = getString(intervalLabel(sec))
            c.tag = sec
            s.intervals.addView(c)
            if (sec == intervalS) s.intervals.check(c.id)
        }
        s.intervals.setOnCheckedStateChangeListener { group, ids ->
            val sec = ids.firstOrNull()?.let { group.findViewById<View>(it)?.tag as? Int } ?: return@setOnCheckedStateChangeListener
            intervalS = sec
            renderIntro()
        }
        s.addAgent.setOnClickListener { findNavController().navigate(R.id.addAgent) }
        s.start.setOnClickListener { start() }
        renderIntro()
    }

    private fun intervalLabel(sec: Int) = when (sec) {
        30 -> R.string.tour_interval_30; 120 -> R.string.tour_interval_120; 300 -> R.string.tour_interval_300
        else -> R.string.tour_interval_60
    }

    private fun intervalWord(sec: Int) = getString(when (sec) {
        30 -> R.string.tour_interval_word_30; 120 -> R.string.tour_interval_word_120; 300 -> R.string.tour_interval_word_300
        else -> R.string.tour_interval_word_60
    })

    private fun renderIntro() { _b?.setup?.intro?.text = getString(R.string.tour_intro, intervalWord(intervalS)) }

    private fun renderSetup() {
        renderAgents()
        renderNeeded()
        renderStart()
    }

    private fun renderAgents() {
        val s = b.setup
        val agents = app.agents.all().filter { it.allows("tour") && it.allows("interactions") && it.allows("location") && it.allows("speaker") }.sortedBy { it.name.lowercase() }
        if (agentId != null && agents.none { it.id == agentId }) agentId = null
        if (agentId == null && agents.size == 1) agentId = agents.first().id
        s.noAgents.isVisible = agents.isEmpty()
        s.addAgent.isVisible = agents.isEmpty()
        val key = agents.joinToString("|") { "${it.id}/${it.name}/${it.modelLabel}" } + "#" + agentId
        if (key == agentsKey) return
        agentsKey = key
        s.agents.removeAllViews()
        for (a in agents) {
            val r = TourRowAgentBinding.inflate(layoutInflater, s.agents, false)
            val checked = a.id == agentId
            r.avatar.setAgent(a.name)
            r.name.text = a.name
            r.connection.text = a.modelText(requireContext())
            r.radio.isChecked = checked
            r.root.isChecked = false
            r.root.strokeWidth = if (checked) requireContext().dp(2) else 0
            r.root.contentDescription = getString(R.string.tour_cd_agent, a.name, a.modelText(requireContext()))
            ViewCompat.setAccessibilityDelegate(r.root, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = checked
                }
            })
            r.root.setOnClickListener { agentId = a.id; renderAgents(); renderStart(); requestRender() }
            s.agents.addView(r.root)
        }
    }

    /** [label], when set, is shown instead of [text] (a string with the agent's name). */
    private data class Need(val key: String, val text: Int, val action: Int, val label: String? = null, val fix: () -> Unit)

    private fun needs(): List<Need> = listenerNeed() + app.tours.missing().map { m ->
        when (m) {
            "Interactions switch" -> Need(m, R.string.tour_need_interactions_switch, R.string.tour_fix_turn_on) { turnOn("interactions") }
            "Location switch" -> Need(m, R.string.tour_need_location_switch, R.string.tour_fix_turn_on) { turnOn("location") }
            "Location permission" -> Need(m, R.string.tour_need_location_permission, R.string.tour_fix_allow) {
                perms.request(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            "Android's Location setting" -> Need(m, R.string.tour_need_location_setting, R.string.tour_fix_open_settings) {
                UiActions.tryStart(requireContext(), Intent(SysSettings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            "Speaker switch" -> Need(m, R.string.tour_need_speaker_switch, R.string.tour_fix_turn_on) { turnOn("speaker") }
            "Notifications" -> Need(m, R.string.tour_need_notifications, R.string.tour_fix_turn_on) {
                val ctx = requireContext()
                if (!ctx.granted(Manifest.permission.POST_NOTIFICATIONS)) perms.request(Manifest.permission.POST_NOTIFICATIONS)
                else UiActions.openAppNotificationSettings(ctx)
            }
            else -> Need(m, 0, 0) {}
        }.let { n -> if (n.text == 0) Need(m, 0, 0) {} else n }
    }

    /** Not blocking (the agent may start polling after Start), but without it nothing reaches the agent. */
    private fun listeningNeed(): List<Need> {
        val a = agentId?.let { app.agents.get(it) } ?: return emptyList()
        if (app.tours.listening(a.id)) return emptyList()
        return listOf(Need("listening:${a.id}", R.string.tour_need_agent_listening, R.string.tour_fix_copy_prompt,
            label = getString(R.string.tour_need_agent_listening, a.name)) { copyStartPrompt(a.name, TourKinds.all.filter { it in kinds }.ifEmpty { listOf("anything") }, false) })
    }

    /** The prompt that gets a non-listening agent onto GET /v1/events (TourKinds.startPrompt). */
    private fun copyStartPrompt(agentName: String, tourKinds: List<String>, running: Boolean) {
        val ctx = context ?: return
        UiActions.copy(ctx, "City tour prompt", TourKinds.startPrompt(agentName, app.address().baseUrl, tourKinds, running))
        Snackbar.make(b.coordinator, getString(R.string.tour_prompt_copied, agentName), Snackbar.LENGTH_LONG).show()
    }

    /** The agent gets ticks only while the listener runs; BridgeService.start does nothing while paused. */
    private fun listenerNeed(): List<Need> = if (app.settings.listenerEnabled) emptyList() else listOf(
        Need("listener", R.string.tour_need_listener, R.string.tour_fix_resume) {
            app.settings.listenerEnabled = true
            app.log.system("audit", "setting", getString(R.string.tour_resumed_audit))
            runCatching { BridgeService.start(requireContext()) }
            requestRender()
        })

    private fun optional(): List<Need> = buildList {
        if (!app.settings.isOn("gallery")) add(Need("gallery", R.string.tour_opt_gallery, R.string.tour_fix_turn_on) { turnOn("gallery") })
        if (!app.settings.isOn(MIC_SWITCH))
            add(Need("mic_switch", R.string.tour_opt_mic_switch, R.string.tour_fix_turn_on) { turnOn(MIC_SWITCH) })
        if (!requireContext().granted(Manifest.permission.RECORD_AUDIO))
            add(Need("mic", R.string.tour_opt_mic, R.string.tour_fix_allow) { perms.request(Manifest.permission.RECORD_AUDIO) })
    }

    /** Turns a master switch on with its audit line (UI-18), and asks for its permission if that's missing. */
    private fun turnOn(switchKey: String) {
        val m = app.registry.switchable().firstOrNull { it.switchKey == switchKey }
        if (m != null) {
            UiActions.setSwitch(app, m, true)
            if (m.blockedReason()?.startsWith("permission:") == true) perms.request(*UiActions.permissionsFor(m))
        } else {
            app.settings.setOn(switchKey, true)
        }
        requestRender()
    }

    private fun renderNeeded() {
        val s = b.setup
        val need = needs() + listeningNeed()
        val opt = optional()
        val key = need.joinToString("|") { it.key } + "#" + opt.joinToString("|") { it.key }
        s.ready.isVisible = need.isEmpty()
        s.optionalHeader.isVisible = opt.isNotEmpty()
        if (key == neededKey) return
        neededKey = key
        fill(s.needed, need, warn = true)
        fill(s.optional, opt, warn = false)
    }

    private fun fill(parent: ViewGroup, items: List<Need>, warn: Boolean) {
        parent.removeAllViews()
        for (n in items) {
            val r = TourRowNeededBinding.inflate(layoutInflater, parent, false)
            r.text.text = n.label ?: if (n.text != 0) getString(n.text) else n.key
            if (!warn) {
                r.icon.setImageResource(R.drawable.ic_info)
                r.icon.imageTintList = android.content.res.ColorStateList.valueOf(
                    requireContext().themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }
            r.fix.isVisible = n.action != 0
            if (n.action != 0) {
                r.fix.setText(n.action)
                r.fix.contentDescription = "${getString(n.action)}: ${r.text.text}"
                r.fix.setOnClickListener { n.fix() }
            }
            parent.addView(r.root)
        }
    }

    private fun renderStart() {
        val s = _b?.setup ?: return
        val hint = when {
            kinds.isEmpty() -> R.string.tour_start_hint_kinds
            agentId == null -> R.string.tour_start_hint_agent
            app.tours.missing().isNotEmpty() || !app.settings.listenerEnabled -> R.string.tour_start_hint_needed
            else -> 0
        }
        s.start.isEnabled = hint == 0 && !starting
        s.startHint.isVisible = hint != 0
        if (hint != 0) s.startHint.setText(hint)
    }

    private fun start() {
        val id = agentId ?: return
        val chosen = TourKinds.all.filter { it in kinds }
        starting = true; renderStart()
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { app.tours.start(id, chosen, intervalS) } }
            starting = false
            if (_b == null) return@launch
            r.onSuccess {
                // A visible start, so the listener gets the location type (TOUR-01, ARC-02a).
                runCatching { BridgeService.start(requireContext()) }
            }.onFailure { e ->
                Snackbar.make(b.coordinator, getString(R.string.tour_start_failed, e.message ?: e.javaClass.simpleName), Snackbar.LENGTH_LONG).show()
            }
            render()
        }
    }

    // ---- Running ----

    @SuppressLint("ClickableViewAccessibility")
    private fun buildRunning() {
        val r = b.running
        r.history.adapter = said
        r.ask.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (speech?.listening == true) { finishAsk(); pressStartedListening = false }
                    else { pressStartedListening = beginAsk(); pressDownAt = SystemClock.uptimeMillis() }
                    r.ask.isPressed = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    r.ask.isPressed = false
                    // Held: let go to send. A short tap keeps listening until the next tap.
                    if (pressStartedListening && SystemClock.uptimeMillis() - pressDownAt >= HOLD_MS) finishAsk()
                    pressStartedListening = false
                }
            }
            true
        }
        // TalkBack and switch access can't hold: a click toggles listening.
        r.ask.setOnClickListener { if (speech?.listening == true) finishAsk() else beginAsk() }
        r.type.setOnClickListener { typeDialog() }
        r.photo.setOnClickListener { startPhoto() }
        r.pause.setOnClickListener {
            val t = app.tours.current() ?: return@setOnClickListener
            app.tours.setPaused(!t.paused)
        }
        r.skip.setOnClickListener { app.speaker.skip() }
        r.end.setOnClickListener { confirmEnd() }
    }

    private fun startPhoto() {
        val v = view ?: return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Bridgit tour ${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Bridgit")
        }
        val uri = runCatching { requireContext().contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            ?: run { Snackbar.make(v, R.string.tour_photo_failed, Snackbar.LENGTH_LONG).show(); return }
        pendingShot = uri
        // Bridgit declares CAMERA, so Android refuses ACTION_IMAGE_CAPTURE until it's granted.
        if (!requireContext().granted(Manifest.permission.CAMERA)) { perms.request(Manifest.permission.CAMERA); pendingShot = null
            runCatching { requireContext().contentResolver.delete(uri, null, null) }; return }
        runCatching { takePhoto.launch(uri) }.onFailure {
            pendingShot = null; runCatching { requireContext().contentResolver.delete(uri, null, null) }
            Snackbar.make(v, R.string.tour_photo_no_camera, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun renderRunning(t: Tour) {
        val r = b.running
        val ctx = requireContext()
        if (shownTourId != t.id) {
            shownTourId = t.id
            liveAzimuth = null
            said.submitList(emptyList())
        }
        r.avatar.setAgent(t.agent)
        r.with.text = getString(R.string.tour_with, t.agent)
        r.kindsText.text = t.kinds.joinToString(", ") { TourKinds.labels[it] ?: it }
        r.stats.text = listOf(
            getString(R.string.tour_started, Fmt.time(t.startedAt)),
            distance(t.distanceM),
            resources.getQuantityString(R.plurals.tour_updates, t.ticks, t.ticks),
            resources.getQuantityString(R.plurals.tour_photos_sent, t.photos, t.photos),
            batteryLine,
        ).filterNotNull().joinToString(" · ")
        renderHeading(t)
        // Coordinates to 4 decimals on screen only; the log keeps 3 (LOG-02).
        val coords = if (lat != null && lon != null) String.format(java.util.Locale.US, "%.4f, %.4f", lat, lon) else null
        r.accuracy.text = when {
            coords != null && accuracyM != null -> getString(R.string.tour_coords, coords, accuracyM!!.roundToInt())
            coords != null -> coords
            accuracyM != null -> getString(R.string.tour_accuracy, accuracyM!!.roundToInt())
            !haveFix -> getString(R.string.tour_no_fix)
            else -> ""
        }
        r.accuracy.isVisible = r.accuracy.text.isNotEmpty()
        r.paused.isVisible = t.paused
        val waiting = !app.tours.pickedUp(t) && System.currentTimeMillis() - t.startedAt > PICKUP_GRACE_MS
        r.waitingCard.isVisible = waiting
        if (waiting) {
            r.waitingText.text = getString(R.string.tour_waiting_agent, t.agent)
            r.waitingCopy.setOnClickListener { copyStartPrompt(t.agent, t.kinds, true) }
        }
        r.pause.setText(if (t.paused) R.string.tour_resume else R.string.tour_pause)

        val playing = app.speaker.current?.takeIf { it.agentId == t.agentId }
        val caption = playing?.caption?.takeIf { it.isNotBlank() }
        r.playingLabel.setText(if (playing != null || t.caption == null) R.string.tour_now_playing else R.string.tour_last_said)
        val text = caption ?: t.caption ?: getString(R.string.tour_nothing_playing, t.agent)
        if (r.caption.text.toString() != text) r.caption.text = text
        r.skip.isEnabled = playing != null

        r.historyEmpty.isVisible = t.said.isEmpty()
        if (said.currentList != t.said) {
            val atBottom = !r.history.canScrollVertically(1)
            val first = said.currentList.isEmpty()
            said.submitList(t.said) {
                val rv = _b?.running?.history ?: return@submitList
                if ((atBottom || first) && t.said.isNotEmpty()) rv.scrollToPosition(t.said.size - 1)
            }
        }
        if (ctx.granted(Manifest.permission.RECORD_AUDIO).not() && speech?.listening == true) cancelAsk()
    }

    private fun renderHeading(t: Tour) {
        val r = _b?.running ?: return
        val az = liveAzimuth ?: t.heading
        if (az == null) {
            r.heading.setText(R.string.tour_facing_unknown)
            r.arrow.isVisible = false
            r.headingRow.contentDescription = getString(R.string.tour_facing_unknown)
        } else {
            val deg = az.roundToInt() % 360
            val dir = Compass.cardinal(az)
            r.heading.text = getString(R.string.tour_facing, dir, deg)
            r.arrow.isVisible = true
            r.arrow.rotation = az.toFloat()
            r.headingRow.contentDescription = getString(R.string.tour_cd_facing, dir, deg)
        }
    }

    private fun distance(m: Double): String =
        if (m < 1000) getString(R.string.tour_metres, m.roundToInt()) else getString(R.string.tour_km, m / 1000.0)

    /** While the running tour is on screen: the compass every 2 s, and the last fix's accuracy. */
    private suspend fun compassLoop() {
        val reader = LocationReader(app)
        while (true) {
            val t = app.tours.current()
            if (t != null) {
                val reading = withContext(Dispatchers.IO) { runCatching { Compass.read(app) }.getOrNull() }
                val fix = withContext(Dispatchers.IO) { runCatching { reader.lastKnown() }.getOrNull() }
                liveAzimuth = reading?.azimuthDeg ?: liveAzimuth
                haveFix = fix != null
                accuracyM = fix?.takeIf { it.hasAccuracy() }?.accuracy
                lat = fix?.latitude; lon = fix?.longitude
                val used = withContext(Dispatchers.IO) { runCatching { app.tours.batteryUsed(t) }.getOrNull() }
                val charged = app.tours.current()?.charged == true
                batteryLine = when {
                    used != null -> getString(R.string.tour_battery_used, used)
                    charged -> getString(R.string.tour_battery_charging)
                    else -> getString(R.string.tour_battery_unknown)
                }
                app.tours.current()?.let { if (_b != null) renderRunning(it) }
            }
            delay(COMPASS_MS)
        }
    }

    // ---- Ask ----

    private fun speechOrNull(): TourSpeech? {
        speech?.let { return it }
        val s = TourSpeech(requireContext(), onPartial = { text ->
            _b?.running?.partial?.apply { isVisible = true; this.text = text }
        }, onDone = { text -> asked(text) })
        if (!s.available()) return null
        speech = s
        return s
    }

    /** Starts listening. False if it didn't (no mic permission yet, or no recognizer). */
    private fun beginAsk(): Boolean {
        if (app.tours.current() == null) return false
        val ctx = requireContext()
        // TOUR-01: asking out loud needs the Microphone switch; typing doesn't.
        if (!app.settings.isOn(MIC_SWITCH)) { micOffDialog(); return false }
        if (!ctx.granted(Manifest.permission.RECORD_AUDIO)) {
            perms.request(Manifest.permission.RECORD_AUDIO)
            return false
        }
        val s = speechOrNull()
        if (s == null || !s.start()) {
            Snackbar.make(b.coordinator, R.string.tour_ask_unavailable, Snackbar.LENGTH_LONG).show()
            typeDialog()
            return false
        }
        val r = b.running
        r.ask.setText(R.string.tour_ask_listening)
        r.ask.contentDescription = getString(R.string.tour_ask_cd_listening)
        r.partial.text = ""
        r.partial.isVisible = false
        r.ask.announceForAccessibility(getString(R.string.tour_announce_listening))
        askLimit?.cancel()
        askLimit = viewLifecycleOwner.lifecycleScope.launch { delay(MAX_ASK_MS); finishAsk() }
        return true
    }

    /** The Microphone switch is off: explain, with a one-tap fix (switch on + audit line) or Type. */
    private fun micOffDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tour_mic_off_title)
            .setMessage(R.string.tour_mic_off_body)
            .setPositiveButton(R.string.tour_mic_turn_on) { _, _ -> turnOn(MIC_SWITCH) }
            .setNeutralButton(R.string.tour_mic_type_instead) { _, _ -> typeDialog() }
            .setNegativeButton(R.string.tour_cancel, null)
            .show()
    }

    private fun finishAsk() {
        askLimit?.cancel(); askLimit = null
        speech?.stop()
    }

    private fun cancelAsk() {
        askLimit?.cancel(); askLimit = null
        speech?.destroy(); speech = null
        resetAsk()
    }

    private fun resetAsk() {
        val r = _b?.running ?: return
        r.ask.setText(R.string.tour_ask)
        r.ask.contentDescription = getString(R.string.tour_ask_cd)
        r.partial.isVisible = false
    }

    private fun asked(text: String?) {
        askLimit?.cancel(); askLimit = null
        resetAsk()
        if (_b == null) return
        if (text.isNullOrBlank()) {
            Snackbar.make(b.coordinator, R.string.tour_ask_heard_nothing, Snackbar.LENGTH_LONG).show()
            return
        }
        send(text, spoken = true)
    }

    private fun send(text: String, spoken: Boolean) {
        val q = text.trim().ifEmpty { return }
        b.running.ask.announceForAccessibility(getString(R.string.tour_announce_sent))
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { runCatching { app.tours.ask(q, spoken) } }
        }
    }

    private fun typeDialog() {
        val t = app.tours.current() ?: return
        val ctx = requireContext()
        val field = EditText(ctx).apply {
            hint = getString(R.string.tour_type_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 5
        }
        val box = FrameLayout(ctx).apply {
            setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0)
            addView(field)
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.tour_type_title, t.agent))
            .setView(box)
            .setPositiveButton(R.string.tour_send) { _, _ -> send(field.text.toString(), spoken = false) }
            .setNegativeButton(R.string.tour_cancel, null)
            .show()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        field.requestFocus()
    }

    private fun confirmEnd() {
        val t = app.tours.current() ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.tour_end_confirm, t.agent))
            .setPositiveButton(R.string.tour_end) { _, _ ->
                val reason = getString(R.string.tour_end_reason)
                if (speech?.listening == true) cancelAsk()
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { runCatching { app.tours.end(reason) } }
                    _b?.let { Snackbar.make(it.coordinator, R.string.tour_ended, Snackbar.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton(R.string.tour_cancel, null)
            .show()
    }

    companion object {
        private const val DEFAULT_INTERVAL = 60
        /** MicModule.switchKey. */
        private const val MIC_SWITCH = "mic"
        private const val HOLD_MS = 400L
        private const val MAX_ASK_MS = 30_000L
        private const val COMPASS_MS = 2_000L
        /** How long after Start the agent has to fetch its events before the screen says it isn't listening. */
        private const val PICKUP_GRACE_MS = 20_000L
        private const val LISTEN_CHECK_MS = 5_000L
        private const val KEY_KINDS = "tour_kinds"
        private const val KEY_AGENT = "tour_agent"
        private const val KEY_INTERVAL = "tour_interval"
        private const val KEY_SHOT = "tour_shot"
    }
}
