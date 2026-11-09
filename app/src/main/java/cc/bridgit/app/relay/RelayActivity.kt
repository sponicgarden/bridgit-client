package cc.bridgit.app.relay

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.R
import cc.bridgit.app.core.Connection
import cc.bridgit.app.databinding.ActivityRelayBinding
import cc.bridgit.app.databinding.RelayAddAgentBinding
import cc.bridgit.app.databinding.RelayAgentRowBinding
import cc.bridgit.app.databinding.RelaySetupBinding
import cc.bridgit.app.platform.Commands
import cc.bridgit.app.ui.common.themeColor
import org.json.JSONObject

/**
 * Relay (4.4): reach this phone without Tailscale. One Turn on registers the phone with this build's relay and
 * connects; Add relay agent gives an agent everything by default and shows its setup text with Copy and Share.
 * Local owner setup only: not exported, no network approval endpoint. Secure window in release builds.
 * Opened from Add agent with `suggested_name`, it turns the relay on if needed and starts adding that agent.
 */
class RelayActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private val store by lazy { RelayStore(app) }
    private lateinit var b: ActivityRelayBinding
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The setup text holds a token. Debug builds allow screenshots so the screen can be checked on a device.
        if (!BuildConfig.DEBUG) cc.bridgit.app.ui.common.ScreenshotProtection.setSecure(window)
        b = ActivityRelayBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            b.toolbar.updatePadding(top = bars.top)
            b.scroll.updatePadding(bottom = bars.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { finish() }
        b.toggle.setOnClickListener { if (store.enabled) turnOff() else turnOn() }
        b.addAgent.setOnClickListener { if (store.enabled) add() else turnOn { add() } }
        b.address.setText(store.base.ifEmpty { store.defaultBase() })
        b.addressLayout.helperText = getString(R.string.relay_address_helper, store.device)
        b.useAddress.setOnClickListener { useAddress() }
        b.showKeys.setOnClickListener { showKeys() }
        render()
        // From a relay-address pop-up: land with the Advanced panel at the top. No focus or keyboard: read first.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_ADVANCED, false)) b.scroll.post {
            val panel = b.addressLayout.parent as android.view.View
            val r = android.graphics.Rect(); panel.getDrawingRect(r)
            b.scroll.offsetDescendantRectToMyCoords(panel, r)
            b.scroll.smoothScrollTo(0, (r.top - (8 * resources.displayMetrics.density).toInt()).coerceAtLeast(0))
        }

        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { renderStatus(); delay(1000) } } }

        if (savedInstanceState == null) {
            intent.getStringExtra("relay_agent_id")?.let { id ->
                app.agents.get(id)?.takeIf { it.connection == Connection.RELAY }?.let { add(it) }
            } ?: intent.getStringExtra("suggested_name")?.let { name ->
                val connector = intent.getStringExtra(EXTRA_CONNECTOR)?.let { v -> ConnectorClient.entries.firstOrNull { it.name == v } }
                val start = { if (connector != null) addConnector(name, connector) else add(suggested = name) }
                if (store.enabled) start() else turnOn { start() }
            }
        }
    }

    private fun render() {
        renderStatus()
        b.agents.removeAllViews()
        // Portal pairings (iPhone/iPad viewers) are on More › Linked devices.
        val configs = store.agents().filterNot { cc.bridgit.app.portal.PortalData.isPortalConfig(RelayDispatcher.strings(it, "routes"), RelayDispatcher.strings(it, "tools")) }
        configs.forEach { c ->
            val id = c.getString("id")
            val name = app.agents.get(id)?.name ?: id
            val sealed = c.getString("mode") == "sealed"
            val r = RelayAgentRowBinding.inflate(layoutInflater, b.agents, false)
            r.icon.setImageResource(if (sealed) R.drawable.ic_lock else R.drawable.ic_cloud)
            r.name.text = name
            r.mode.text = getString(if (sealed) R.string.relay_mode_sealed else R.string.relay_mode_connector)
            r.desc.text = getString(if (sealed) R.string.relay_agent_sealed_desc else R.string.relay_agent_connector_desc)
            r.revoke.contentDescription = getString(R.string.relay_revoke_title, name)
            r.revoke.setOnClickListener {
                MaterialAlertDialogBuilder(this).setTitle(getString(R.string.relay_revoke_title, name))
                    .setMessage(getString(R.string.relay_revoke_body, name))
                    .setPositiveButton(R.string.relay_revoke) { _, _ -> store.remove(id); app.agents.remove(id); render() }
                    .setNegativeButton(R.string.cancel, null).show()
            }
            b.agents.addView(r.root)
        }
        b.agentsEmpty.isVisible = configs.isEmpty()
    }

    private fun renderStatus() {
        val raw = if (store.enabled) BridgeService.running?.relay?.status ?: "Connecting" else "Off"
        val (label, good) = when {
            busy -> getString(R.string.relay_turning_on) to false
            !store.enabled -> getString(R.string.relay_status_off) to false
            raw == "Connected" -> getString(R.string.relay_status_connected) to true
            raw.startsWith("Disconnected") -> getString(R.string.relay_status_retrying) to false
            else -> getString(R.string.relay_status_connecting) to false
        }
        b.status.text = label
        b.status.setChipIconResource(if (good) R.drawable.ic_check else if (store.enabled) R.drawable.ic_cloud else R.drawable.ic_toggle)
        b.status.isChipIconVisible = true
        b.status.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
            themeColor(if (good) R.attr.colorSuccessContainer else com.google.android.material.R.attr.colorSurface))
        b.toggle.isEnabled = !busy
        b.toggle.setText(if (store.enabled) R.string.relay_turn_off else R.string.relay_turn_on)
    }

    private fun turnOn(code: String = "", then: (() -> Unit)? = null) {
        if (busy) return
        busy = true; b.error.isVisible = false; renderStatus()
        lifecycleScope.launch {
            val failure = withContext(Dispatchers.IO) { runCatching { RelaySetup.turnOn(app, store, code) }.exceptionOrNull() }
            busy = false
            b.address.setText(store.base)
            if (failure != null) {
                b.error.text = getString(R.string.relay_failed, failure.message ?: failure.javaClass.simpleName)
                b.error.isVisible = true
            } else then?.invoke()
            render()
        }
    }

    private fun turnOff() {
        BridgeService.running?.relay?.disable() ?: run {
            store.enabled = false; store.agents().forEach { app.platform.invalidate(it.getString("id")) }
        }
        render()
    }

    /** Advanced: a different relay, or a one-time code from a relay you run yourself. */
    private fun useAddress() {
        val address = b.address.text.toString().trim()
        val err = runCatching { store.base = address }.exceptionOrNull()
        if (err != null || !address.endsWith("/d/${store.device}")) {
            b.addressLayout.error = getString(R.string.relay_address_helper, store.device); return
        }
        b.addressLayout.error = null
        val code = b.code.text.toString().trim(); b.code.text = null
        turnOn(code)
    }

    private fun showKeys() {
        val base = store.base.ifEmpty { store.defaultBase() }.ifEmpty { "/d/${store.device}" }
        val keys = store.enrollment().toString(2)
        sheet(icon = R.drawable.ic_lock, title = getString(R.string.relay_keys_title), subtitle = getString(R.string.relay_keys_sub),
            facts = emptyList(),
            steps = listOf(getString(R.string.relay_step_keys_open, base), getString(R.string.relay_step_keys_paste), getString(R.string.relay_step_keys_code)),
            copyLabel = getString(R.string.relay_copy_keys), text = keys, note = null, sensitive = false, showText = true)
    }

    /** The setup sheet: a clear header, a few facts, numbered steps, one big Copy; the raw text only on request. */
    private fun sheet(icon: Int, title: String, subtitle: String, facts: List<Pair<String, String>>, steps: List<String>,
                      copyLabel: String, text: String, note: String?, sensitive: Boolean, showText: Boolean = false,
                      secret: String? = null, second: Pair<String, () -> Unit>? = null, link: Pair<String, () -> Unit>? = null,
                      copied: Int = R.string.relay_copied) {
        val s = RelaySetupBinding.inflate(layoutInflater)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        // Icon 0: a to-do sheet (chat app connector), not a success one: no check, no subtitle.
        if (icon == 0) s.icon.isVisible = false else s.icon.setImageResource(icon)
        s.title.text = title
        s.subtitle.text = subtitle
        s.subtitle.isVisible = subtitle.isNotEmpty()
        s.factsCard.isVisible = facts.isNotEmpty()
        facts.forEach { (label, value) ->
            val r = cc.bridgit.app.databinding.RelayFactRowBinding.inflate(layoutInflater, s.facts, false)
            r.label.text = label; r.value.text = value; s.facts.addView(r.root)
        }
        steps.forEachIndexed { i, t ->
            val r = cc.bridgit.app.databinding.RelayStepRowBinding.inflate(layoutInflater, s.steps, false)
            r.number.text = (i + 1).toString(); r.text.text = t; s.steps.addView(r.root)
        }
        s.copy.text = copyLabel
        s.copy.setOnClickListener { copy(text, sensitive, copied) }
        s.share.setOnClickListener { share(text, keys = !sensitive) }
        s.prompt.text = text
        // AGT-08a: the token stays out of the accessibility tree.
        if (secret != null) cc.bridgit.app.ui.common.SecretViews.hideSecret(s.prompt) { secret }
        fun showRaw(on: Boolean) {
            s.textCard.isVisible = on
            s.toggleText.setText(if (on) R.string.relay_hide_text else R.string.relay_show_text)
        }
        showRaw(showText)
        s.toggleText.setOnClickListener { showRaw(!s.textCard.isVisible) }
        second?.let { (label, action) -> s.share.text = label; s.share.setIconResource(R.drawable.agentact_ic_key); s.share.setOnClickListener { action() } }
        link?.let { (label, action) -> s.textCard.isVisible = false; s.toggleText.text = label; s.toggleText.setOnClickListener { action() } }
        s.note.isVisible = note != null; s.note.text = note
        s.done.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(s.root)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private class Offer(val specs: List<cc.bridgit.app.api.EndpointSpec>, val routes: List<String>, val tools: List<String>)

    private fun offer(): Offer {
        val specs = app.registry.modules.flatMap { it.endpoints }.filter {
            it.auth && !it.privileged && it.capability != "feedback" && it.path != "/mcp" && it.capability !in setOf("tour", "speaker", "video", "screenshare")
        }
        return Offer(specs, specs.map { "${it.method} ${it.fullPath}" }, (Commands.all - RelayDispatcher.excluded).sorted())
    }

    /** New (or re-keyed [existing]) relay agent. Everything this phone offers is allowed unless the owner narrows it. */
    private fun add(existing: cc.bridgit.app.core.Agent? = null, suggested: String? = null) {
        val d = RelayAddAgentBinding.inflate(layoutInflater)
        d.name.setText(existing?.name ?: suggested.orEmpty())
        d.name.isEnabled = existing == null
        d.modes.setOnCheckedChangeListener { _, id -> d.keyLayout.isVisible = id == R.id.sealed }
        val o = offer()
        val selected = BooleanArray(o.routes.size + o.tools.size) { true }
        d.maximumControl.isChecked = true
        var bindingMaximum = false
        d.maximumControl.setOnCheckedChangeListener { _, on -> if (!bindingMaximum) selected.fill(on) }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.relay_add_title).setView(d.root)
            .setPositiveButton(R.string.relay_create, null)
            .setNegativeButton(R.string.cancel, null).create()
        // Keep the action row above the IME; only the form scrolls on short screens.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        dialog.window?.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
        // Floating dialogs can retain their pre-IME height when a field expands.
        // Bound the form to the visible frame, reserving the measured title/actions.
        val decor = dialog.window!!.decorView
        val panel = dialog.findViewById<android.view.View>(androidx.appcompat.R.id.parentPanel)!!
        val visibleFrame = android.graphics.Rect()
        val panelPosition = IntArray(2)
        val margin = (16 * resources.displayMetrics.density).toInt()
        val resize = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            decor.getWindowVisibleDisplayFrame(visibleFrame)
            panel.getLocationOnScreen(panelPosition)
            val chrome = (panel.height - d.root.height).coerceAtLeast(0)
            // The floating window also has top decor insets inside the visible frame.
            val available = visibleFrame.bottom - panelPosition[1]
            val cap = (available - chrome - margin * 2).coerceAtLeast(margin * 3)
            if (d.root.maxHeightPx != cap) d.root.maxHeightPx = cap
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(resize)
        dialog.setOnDismissListener {
            if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnGlobalLayoutListener(resize)
        }
        fun validate(): Triple<String, String, String>? {
            val n = d.name.text.toString().trim()
            app.agents.checkName(n, existing?.id)?.let { d.nameLayout.error = it; return null }
            d.nameLayout.error = null
            val mode = if (d.modes.checkedRadioButtonId == R.id.sealed) "sealed" else "connector"
            val pub = d.key.text.toString().trim()
            if (mode == "sealed" && runCatching { Sealed.validatePublic(pub) }.isFailure) {
                d.keyLayout.error = getString(R.string.relay_sealed_key_needed); return null
            }
            return Triple(n, mode, pub)
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val (n, mode, pub) = validate() ?: return@setOnClickListener
            dialog.dismiss(); create(existing, n, mode, pub, o, selected, d.superPowers.isChecked)
        }
        d.choose.setOnClickListener {
            val name = d.name.text.toString().trim().ifEmpty { getString(R.string.relay_agent_name) }
            val options = (o.routes + o.tools.map { "Tool $it" }).toTypedArray()
            MaterialAlertDialogBuilder(this).setTitle(getString(R.string.relay_choose_title, name))
                .setMultiChoiceItems(options, selected) { _, i, on -> selected[i] = on }
                .setPositiveButton(R.string.relay_done) { _, _ -> bindingMaximum = true; d.maximumControl.isChecked = selected.all { it }; bindingMaximum = false }.show()
        }
    }

    /** The agent and its relay token. Everything selected in [o] is allowed. */
    private fun register(existing: cc.bridgit.app.core.Agent?, n: String, mode: String, pub: String, o: Offer, selected: BooleanArray, superPowers: Boolean = false): Pair<cc.bridgit.app.core.Agent, String> {
        val caps = o.specs.filterIndexed { i, _ -> selected[i] }.map { it.capability }.toSet()
        val a = existing?.let { app.agents.get(it.id) } ?: app.agents.add(n, Connection.RELAY).first
        // Revoke before applying the new legacy list: revoke also removes legacy camera/mic/gallery grants.
        app.platform.revoke(a.id)
        app.agents.update(a.id) { it.copy(caps = caps, fixer = false, superPowers = superPowers) }
        if (selected.all { it }) cc.bridgit.app.power.PowerAccess(app).apply { allSwitchesOn(); grantAll(a.id) }
        else {
            // A narrowed offer must not retain grants from an earlier maximum pairing.
            val legacyScopes = mapOf("camera" to "camera.snapshot", "mic" to "microphone.clip", "gallery" to "gallery.read")
            caps.mapNotNull { legacyScopes[it] }.forEach { app.platform.grant(a.id, it, Long.MAX_VALUE) }
            (Commands.reads + Commands.mutations).filter { tool ->
                tool in o.tools.filterIndexed { i, _ -> selected[o.routes.size + i] }
            }.filter { it != "capabilities" }.map { Commands.scope(it) }.distinct().filter { scope -> cc.bridgit.app.platform.Capabilities.all.any { it.id == scope } }
                .forEach { app.platform.grant(a.id, it, Long.MAX_VALUE) }
        }
        return app.agents.get(a.id)!! to store.addAgent(a.id, mode, pub,
            o.routes.filterIndexed { i, _ -> selected[i] }.toSet(), o.tools.filterIndexed { i, _ -> selected[o.routes.size + i] }.toSet())
    }

    /**
     * Claude Duet, claude.ai or ChatGPT Dots: a connector-mode agent with everything this phone offers. The chat app
     * calls the relay's MCP address from its own servers; the owner approves once on the relay's sign-in page with the relay key.
     */
    private fun addConnector(n: String, c: ConnectorClient) {
        if (app.settings.securityRelaySignedOnly) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.relay_connector_signed_title)
                .setMessage(getString(R.string.relay_connector_signed_body, c.product))
                .setPositiveButton(R.string.relay_connector_signed_off) { _, _ -> app.settings.securityRelaySignedOnly = false; addConnector(n, c) }
                .setNegativeButton(R.string.cancel, null).show()
            return
        }
        app.agents.checkName(n, null)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show(); add(suggested = n); return }
        cc.bridgit.app.security.OwnerCheck.run(this, getString(R.string.settings_max_control)) { finishConnector(n, c) }
    }

    private fun finishConnector(n: String, c: ConnectorClient) {
        val o = offer()
        val (_, token) = register(null, n, "connector", "", o, BooleanArray(o.routes.size + o.tools.size) { true })
        render()
        val url = store.base + "/mcp"
        sheet(icon = 0, title = getString(R.string.relay_connector_title, c.product), subtitle = "",
            facts = listOf(getString(R.string.relay_fact_connector) to url.replace(".", ".\u200B").replace("/", "/\u200B")),
            steps = c.steps.mapIndexed { i, id -> if (i == 1) getString(id, n) else getString(id) },
            copyLabel = getString(R.string.relay_connector_copy_url), text = url, note = null, sensitive = false, secret = token,
            copied = R.string.relay_connector_url_copied,
            second = getString(R.string.relay_connector_copy_key) to { copy(token, sensitive = true, label = R.string.relay_connector_key_copied) },
            link = getString(R.string.relay_connector_open, c.product) to {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(c.openUrl)))
            })
    }

    private fun create(existing: cc.bridgit.app.core.Agent?, n: String, mode: String, pub: String, o: Offer, selected: BooleanArray, superPowers: Boolean = false) {
        cc.bridgit.app.security.OwnerCheck.run(this, getString(R.string.settings_max_control)) {
            createAuthorized(existing, n, mode, pub, o, selected, superPowers)
        }
    }

    /** The relay text's bootstrap: the setup prompt's first calls (with this device's domain name) and its pointers. */
    private fun relayCalls(): String = cc.bridgit.app.api.SetupPrompt.firstCalls(
        cc.bridgit.app.feedback.DeviceNames.cached(app)?.optString("name")?.takeIf { it.isNotBlank() }).trimEnd() +
        "\n\n" + cc.bridgit.app.api.SetupPrompt.pointers()

    private fun createAuthorized(existing: cc.bridgit.app.core.Agent?, n: String, mode: String, pub: String, o: Offer, selected: BooleanArray, superPowers: Boolean = false) {
        val (a, token) = register(existing, n, mode, pub, o, selected, superPowers)
        val config = store.agents().first { it.getString("id") == a.id }
        val details = JSONObject().put("base", store.base).put("device_id", store.device).put("agent_id", a.id)
            .put("generation", a.keyHash).put("mode", mode).put("token", token)
            .put("phone_hpke_public", Sealed.publicKey(store.hpke())).put("mcp_url", store.base + "/mcp").put("allowlist", config).toString(2)
        val prompt = if (mode == "connector") getString(R.string.relay_prompt, store.base, token, relayCalls(), details) else details
        render()
        val routes = o.routes.indices.count { selected[it] }; val tools = o.tools.indices.count { selected[o.routes.size + it] }
        val access = if (routes == o.routes.size && tools == o.tools.size) getString(R.string.relay_access_all)
            else getString(R.string.relay_access_some, routes, tools)
        sheet(icon = R.drawable.ic_check, title = getString(R.string.relay_setup_ready, n), subtitle = getString(R.string.relay_setup_sub, n),
            facts = listOf(getString(R.string.relay_fact_relay) to (java.net.URI(store.base).host ?: store.base).replace(".", ".\u200B"),
                getString(R.string.relay_fact_mode) to getString(if (mode == "sealed") R.string.relay_mode_sealed else R.string.relay_mode_connector),
                getString(R.string.relay_fact_access) to access),
            steps = listOf(getString(R.string.relay_step_copy), getString(R.string.relay_step_paste, n), getString(R.string.relay_step_go, n)),
            copyLabel = getString(R.string.relay_copy_for, n), text = prompt,
            note = getString(R.string.relay_setup_note) + if (mode == "connector") " " + getString(R.string.relay_setup_note_connector) else "",
            sensitive = true, secret = token)
    }

    private fun copy(text: String, sensitive: Boolean = true, label: Int = R.string.relay_copied) {
        val clip = ClipData.newPlainText(getString(R.string.relay_title), text)
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
    }

    /** The setup prompt goes to an agent; the public keys (not sensitive) go to the owner's own relay page. */
    private fun share(text: String, keys: Boolean) {
        val body = if (keys) cc.bridgit.app.ui.common.ShareText.prompt(getString(R.string.share_relay_keys_heading), getString(R.string.share_relay_keys_note), text, getString(R.string.share_relay_keys_target))
            else cc.bridgit.app.ui.common.ShareText.prompt(getString(R.string.share_relay_heading), getString(R.string.share_relay_note), text)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(if (keys) R.string.share_relay_keys_heading else R.string.share_relay_subject))
            .putExtra(Intent.EXTRA_TEXT, body), null))
    }

    companion object {
        /** From Add agent: a [ConnectorClient] name; sets this phone up as that chat app's custom connector. */
        const val EXTRA_CONNECTOR = "connector_client"
        /** Open scrolled to Advanced (relay address and one-time code). */
        const val EXTRA_ADVANCED = "advanced"
    }
}
