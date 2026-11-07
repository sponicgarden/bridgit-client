package cc.bridgit.app.listen

import android.Manifest
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.capability.granted
import cc.bridgit.app.databinding.ActivityWakeWordsBinding
import cc.bridgit.app.databinding.WakeWordRowBinding
import cc.bridgit.app.ui.home.UiActions

/** The owner's wake-word screen (CAP-47): the switch and status, up to 10 wake words each mapped to an agent, and the speech model. */
class WakeWordsActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private lateinit var b: ActivityWakeWordsBinding
    private var agentIndex = 0
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    private val onSettings: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityWakeWordsBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            b.toolbar.updatePadding(top = bars.top)
            b.scroll.updatePadding(bottom = bars.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { finish() }
        b.switchRow.setOnClickListener { toggle() }
        b.phrase.doAfterTextChanged { b.phraseLayout.error = null }
        b.agent.setOnItemClickListener { _, _, pos, _ -> agentIndex = pos }
        b.add.setOnClickListener { add() }
        b.soundRow.setOnClickListener { toggleSounds() }
        b.soundAgent.setOnItemClickListener { _, _, pos, _ -> cc.bridgit.app.ui.home.HomeStates.controlAgents(app).getOrNull(pos)?.let { saveSound { c -> c.copy(agentId = it.id) } } }
        b.sensitivity.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || binding) return@addOnButtonCheckedListener
            val s = when (id) { R.id.sensQuiet -> "quiet"; R.id.sensLoud -> "loud"; else -> "normal" }
            saveSound { it.copy(sensitivity = s) }
        }
        b.soundClip.setOnCheckedChangeListener { _, c -> if (!binding) saveSound { it.copy(sendClip = c) } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.wake.state.collect { render() } }
                launch { app.wake.model.state.collect { render() } }
            }
        }
    }

    override fun onStart() { super.onStart(); app.settings.addListener(onSettings) }
    override fun onStop() { app.settings.removeListener(onSettings); super.onStop() }

    override fun onResume() {
        super.onResume()
        // The microphone type can only be added while Bridgit is visible (ARC-02a): this screen is a good moment.
        if (app.settings.listenerEnabled && !app.listener.value.micReady && granted(Manifest.permission.RECORD_AUDIO)) runCatching { BridgeService.start(this) }
        render()
    }

    private fun toggle() {
        val module = app.registry.byId("wakewords")!!
        val on = !module.enabled
        UiActions.setSwitch(app, module, on)
        if (on && !granted(Manifest.permission.RECORD_AUDIO)) askMic.launch(Manifest.permission.RECORD_AUDIO)
        else if (on && !app.listener.value.micReady) runCatching { BridgeService.start(this) }
        render()
    }

    private fun add() {
        val agents = cc.bridgit.app.ui.home.HomeStates.controlAgents(app)
        if (agents.isEmpty()) return
        val w = WakeWord(WakeRules.normalize(b.phrase.text?.toString().orEmpty()), agents[agentIndex.coerceIn(0, agents.size - 1)].id, b.clip.isChecked)
        val problem = WakeRules.problem(app.wake.words(), w, { app.agents.get(it) != null },
            if (app.wake.model.ready) { word -> app.wake.model.knows(word) } else null)
        if (problem != null) { b.phraseLayout.error = problem; return }
        app.wake.saveWords(app.wake.words() + w)
        app.log.system("audit", "setting", "You added the wake word \"${w.phrase}\" for ${app.agents.get(w.agentId)?.name}")
        // Mapping a wake word to an agent is the owner's grant: an agent with a restricted grant set gets wake words too.
        app.agents.get(w.agentId)?.takeIf { !it.allows("wakewords") }?.let { a ->
            app.agents.update(a.id) { it.copy(caps = it.caps.orEmpty() + "wakewords") }
            app.log.system("audit", "agent", "Owner changed ${a.name}'s grants: added wakewords with a wake word")
        }
        b.phrase.setText(""); b.clip.isChecked = false
        render()
    }

    private fun color(attr: Int) = MaterialColors.getColor(b.root, attr)
    private var binding = false

    /** Changes the sounds setup, starting from the first agent when there's none yet. */
    private fun saveSound(change: (SoundConfig) -> SoundConfig) {
        val base = app.wake.soundConfig() ?: cc.bridgit.app.ui.home.HomeStates.controlAgents(app).firstOrNull()?.let { SoundConfig(it.id) } ?: return
        val next = change(base)
        if (next == app.wake.soundConfig()) return
        app.wake.saveSoundConfig(next)
        app.agents.get(next.agentId)?.takeIf { !it.allows("sounds") }?.let { a ->
            app.agents.update(a.id) { it.copy(caps = it.caps.orEmpty() + "sounds") }
            app.log.system("audit", "agent", "Owner changed ${a.name}'s grants: added sounds")
        }
        app.log.system("audit", "setting", "You set sounds to go to ${app.agents.get(next.agentId)?.name} (${next.sensitivity}, clip ${if (next.sendClip) "on" else "off"})")
        render()
    }

    private fun toggleSounds() {
        val module = app.registry.byId("sounds")!!
        val on = !module.enabled
        if (on && app.wake.soundConfig() == null) saveSound { it }
        UiActions.setSwitch(app, module, on)
        if (on && !granted(Manifest.permission.RECORD_AUDIO)) askMic.launch(Manifest.permission.RECORD_AUDIO)
        else if (on && !app.listener.value.micReady) runCatching { BridgeService.start(this) }
        render()
    }

    private fun renderSounds() {
        binding = true
        val on = app.registry.byId("sounds")!!.enabled
        b.soundToggle.isChecked = on
        b.soundRow.contentDescription = getString(R.string.cap_sounds) + ", " + if (on) "on" else "off"
        val st = app.wake.state.value
        val running = app.wake.running?.sound != null
        val (text, dot) = when {
            !on -> "Off." to com.google.android.material.R.attr.colorOutline
            running && st is WakeListener.State.Paused -> "Paused." to com.google.android.material.R.attr.colorSecondary
            running -> "Listening for sounds." to R.attr.colorSuccess
            else -> "On, but not listening yet: " + when (app.wake.soundsMissing()) {
                "no_agent" -> "pick a control agent."
                "permission:RECORD_AUDIO" -> "allow the microphone."
                "open_app_once", "listener_off" -> "the listener needs a restart."
                else -> app.wake.soundsMissing() ?: "…"
            } to androidx.appcompat.R.attr.colorError
        }
        b.soundStatus.text = app.wake.lastSound?.let { "$text Last: ${cc.bridgit.app.ui.common.Fmt.ago(it.at)}, ${it.seconds.let { s -> Math.round(s * 10) / 10.0 }} s, peak ${it.peakDbfs} dBFS." } ?: text
        b.soundDot.backgroundTintList = ColorStateList.valueOf(color(dot))
        val agents = cc.bridgit.app.ui.home.HomeStates.controlAgents(app)
        val cfg = app.wake.soundConfig()
        b.soundAgent.setSimpleItems(agents.map { it.name }.toTypedArray())
        (cfg?.let { c -> app.agents.get(c.agentId)?.name } ?: "").let { if (b.soundAgent.text.toString() != it) b.soundAgent.setText(it, false) }
        b.sensitivity.check(when (cfg?.sensitivity) { "quiet" -> R.id.sensQuiet; "loud" -> R.id.sensLoud; else -> R.id.sensNormal })
        b.soundClip.isChecked = cfg?.sendClip ?: true
        binding = false
    }

    private fun render() {
        if (!::b.isInitialized) return
        val module = app.registry.byId("wakewords")!!
        val on = module.enabled
        b.toggle.isChecked = on
        b.switchRow.contentDescription = getString(R.string.wake_listen_switch) + ", " + if (on) "on" else "off"

        // Status: green while listening, gold when it heard something or is paused, red when something's missing.
        // Sounds alone keep the mic open; for wake words that still counts as off.
        val s = app.wake.state.value.let {
            if (it !is WakeListener.State.Off && app.wake.running?.words.isNullOrEmpty()) WakeListener.State.Off(app.wake.missing() ?: "error") else it }
        val gold = com.google.android.material.R.attr.colorSecondary
        val (text, dot) = when {
            !on -> "Off. Nothing is listening." to com.google.android.material.R.attr.colorOutline
            s is WakeListener.State.Listening -> "Listening for your wake words." to R.attr.colorSuccess
            s is WakeListener.State.Hearing -> "Heard “${s.phrase}”. Listening to what follows…" to gold
            s is WakeListener.State.Paused -> "Paused: " + when (s.reason) {
                "muted" -> "muted until " + android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(app.wake.mutedUntil)) + "."
                "call" -> "you're on a call."
                else -> "a control agent is recording with the microphone."
            } to gold
            s is WakeListener.State.Off -> "On, but not listening yet: " + when (s.reason) {
                "permission:RECORD_AUDIO" -> "allow the microphone."
                "notifications_off" -> "turn on Bridgit's notifications."
                "listener_off" -> "the listener is paused."
                "open_app_once" -> "Android hasn't given Bridgit the microphone yet."
                "model_missing" -> "download the speech model."
                "no_wake_words" -> "add a wake word."
                else -> s.reason + "."
            } to androidx.appcompat.R.attr.colorError
            else -> "" to com.google.android.material.R.attr.colorOutline
        }
        b.status.text = text
        b.statusDot.backgroundTintList = ColorStateList.valueOf(color(dot))
        val hit = app.wake.lastHit
        b.lastHit.isVisible = hit != null
        hit?.let { b.lastHit.text = getString(R.string.wake_last_heard, cc.bridgit.app.ui.common.Fmt.ago(it.at), it.phrase, it.agentName) +
            if (it.transcript.isNotBlank()) ": “${it.transcript}”" else "" }
        val fix: Pair<String, () -> Unit>? = when ((s as? WakeListener.State.Off)?.reason.takeIf { on }) {
            "permission:RECORD_AUDIO" -> "Allow microphone" to { askMic.launch(Manifest.permission.RECORD_AUDIO) }
            "open_app_once", "listener_off" -> "Start listener" to { app.settings.listenerEnabled = true; runCatching { BridgeService.start(this) }; Unit }
            // The speech model has its own callout at the top of the screen, right above this card.
            "notifications_off" -> "Open notification settings" to { UiActions.openAppNotificationSettings(this) }
            else -> null
        }
        b.fix.isVisible = fix != null
        fix?.let { (label, act) -> b.fix.text = label; b.fix.setOnClickListener { act(); render() } }
        val muted = app.wake.mutedUntil > System.currentTimeMillis()
        b.mute.isVisible = on && (s is WakeListener.State.Listening || s is WakeListener.State.Hearing || muted)
        b.mute.text = getString(if (muted) R.string.wake_unmute else R.string.wake_mute)
        b.mute.setOnClickListener { if (muted) app.wake.unmute() else app.wake.mute(); app.wake.sync(); render() }

        renderWords()
        renderSounds()
        renderModel()
    }

    private fun renderWords() {
        val words = app.wake.words()
        b.count.text = if (words.isEmpty()) getString(R.string.wake_none) else getString(R.string.wake_count, words.size, WakeRules.MAX_WORDS)
        b.words.removeAllViews()
        for (w in words) {
            val r = WakeWordRowBinding.inflate(layoutInflater, b.words, false)
            val phrase = WakeRules.normalize(w.phrase)
            r.phrase.text = "“$phrase”"
            r.agent.text = getString(R.string.wake_goes_to, app.agents.get(w.agentId)?.name ?: "a removed control agent")
            r.audio.isVisible = w.sendClip
            r.remove.contentDescription = getString(R.string.wake_remove, phrase)
            r.remove.setOnClickListener {
                app.wake.saveWords(app.wake.words() - w)
                app.log.system("audit", "setting", "You removed the wake word \"$phrase\"")
                render()
            }
            b.words.addView(r.root)
        }
        val agents = cc.bridgit.app.ui.home.HomeStates.controlAgents(app)
        b.addCard.isVisible = words.size < WakeRules.MAX_WORDS && agents.isNotEmpty()
        val names = agents.map { it.name }
        b.agent.setSimpleItems(names.toTypedArray())
        agentIndex = agentIndex.coerceIn(0, maxOf(0, names.size - 1))
        names.getOrNull(agentIndex)?.let { if (b.agent.text.toString() != it) b.agent.setText(it, false) }
    }

    private fun renderModel() {
        placeModel(atTop = app.wake.model.state.value != WakeModel.State.Ready)
        b.modelName.text = WakeModel.NAME
        b.progress.isVisible = false
        b.modelAction.isVisible = true
        b.modelAction.setTextColor(color(com.google.android.material.R.attr.colorOnPrimary))
        b.modelAction.backgroundTintList = ColorStateList.valueOf(color(androidx.appcompat.R.attr.colorPrimary))
        when (val m = app.wake.model.state.value) {
            WakeModel.State.Absent -> {
                b.modelTitle.text = getString(R.string.wake_model_absent_title)
                b.modelDetail.text = getString(R.string.wake_model_absent)
                b.modelAction.text = getString(R.string.wake_model_download)
                b.modelAction.setOnClickListener { download() }
            }
            is WakeModel.State.Downloading -> {
                b.modelTitle.text = getString(R.string.wake_model_downloading, m.percent)
                b.modelDetail.text = WakeModel.LABEL
                b.progress.isVisible = true
                b.progress.setProgressCompat(m.percent, true)
                b.modelAction.isVisible = false
            }
            is WakeModel.State.Failed -> {
                b.modelTitle.text = getString(R.string.wake_model_failed, m.message)
                b.modelDetail.text = WakeModel.LABEL
                b.modelAction.text = getString(R.string.wake_model_retry)
                b.modelAction.setOnClickListener { download() }
            }
            WakeModel.State.Ready -> {
                b.modelTitle.text = getString(R.string.wake_model_ready_title)
                b.modelDetail.text = getString(R.string.wake_model_ready, WakeModel.LABEL, Formatter.formatShortFileSize(this, app.wake.model.bytesOnDisk()))
                b.modelAction.text = getString(R.string.wake_model_delete)
                b.modelAction.setTextColor(color(androidx.appcompat.R.attr.colorError))
                b.modelAction.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                b.modelAction.setOnClickListener {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Delete the speech model?")
                        .setMessage("Wake words turn off until you download it again.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ -> app.settings.setOn(WakeListener.SWITCH, false); app.wake.model.delete(); render() }
                        .show()
                }
            }
        }
    }

    /**
     * Until the speech model is on the phone nothing else here works, so its card leads the screen as a
     * highlighted "Start here" callout. Once it's downloaded it drops back to the bottom as a plain card.
     */
    private fun placeModel(atTop: Boolean) {
        val c = b.content
        val isTop = c.indexOfChild(b.modelHeader) == 0
        if (isTop != atTop) {
            c.removeView(b.modelHeader); c.removeView(b.modelCard)
            if (atTop) { c.addView(b.modelHeader, 0); c.addView(b.modelCard, 1) }
            else { c.addView(b.modelHeader); c.addView(b.modelCard) }
        }
        val dp = resources.displayMetrics.density
        b.modelHeader.text = getString(if (atTop) R.string.wake_section_model_start else R.string.wake_section_model)
        b.modelHeader.setPadding(b.modelHeader.paddingLeft, ((if (atTop) 8 else 24) * dp).toInt(), b.modelHeader.paddingRight, b.modelHeader.paddingBottom)
        b.modelCard.strokeWidth = if (atTop) (2 * dp).toInt() else 0
        b.modelCard.strokeColor = color(androidx.appcompat.R.attr.colorPrimary)
        b.modelCard.setCardBackgroundColor(color(
            if (atTop) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceContainer))
        (b.modelCard.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin = if (atTop) (16 * dp).toInt() else 0
        b.modelTitle.setTextColor(color(if (atTop) com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorOnSurface))
        b.modelDetail.setTextColor(color(if (atTop) com.google.android.material.R.attr.colorOnPrimaryContainer else com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private fun download() { app.wake.model.startDownload(); render() }
}
