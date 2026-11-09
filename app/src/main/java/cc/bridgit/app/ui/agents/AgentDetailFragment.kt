package cc.bridgit.app.ui.agents

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.core.Agent
import cc.bridgit.app.databinding.AgentsRenameDialogBinding
import cc.bridgit.app.databinding.FragmentAgentDetailBinding
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.ui.common.modelText
import cc.bridgit.app.ui.common.ActivityRows
import cc.bridgit.app.ui.common.Fmt
import cc.bridgit.app.ui.common.themeColor
import cc.bridgit.app.ui.feedback.handleFeedbackMenu
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Agent detail (UI-14, AGT-12 … AGT-15, ui-spec 5.5), with the Callback URL row (EVT-05). */
class AgentDetailFragment : Fragment() {
    private var _b: FragmentAgentDetailBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private lateinit var id: String
    private lateinit var activityAdapter: AgentActivityAdapter
    private var bindingSwitch = false
    private var savedNotes: String? = null

    private val agentsChanged: () -> Unit = { view?.post { if (_b != null) bindAgent() } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentAgentDetailBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        id = requireArguments().getString("agentId") ?: run { findNavController().popBackStack(); return }
        if (app.agents.get(id) == null) { findNavController().popBackStack(); return }

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            b.toolbar.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().popBackStack() }
        b.toolbar.inflateMenu(R.menu.agents_detail_menu)
        b.toolbar.inflateMenu(R.menu.feedback_overflow) // Report a bug / Request a feature (FBK-01)
        b.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.agents_rename) { rename(); true }
            // Agent names go in the context only from an agent screen (FBK-06).
            else handleFeedbackMenu(it, "Control agent detail") { agent()?.name?.let { n -> "Control agent: $n" } }
        }

        // Rows are a summary (polls collapsed, #0004); a tap opens this agent's full log in Activity.
        activityAdapter = AgentActivityAdapter { findNavController().navigate(R.id.activity, bundleOf("agentId" to id)) }
        b.activityList.adapter = activityAdapter
        b.seeAll.setOnClickListener { findNavController().navigate(R.id.activity, bundleOf("agentId" to id)) }

        b.powersCard.setOnClickListener { AgentPermissionsDialog.show(this, id) { bindAgent() } }
        b.rotate.setOnClickListener { confirmRotate() }
        b.deviceUnlock.setOnClickListener { confirmUnlock() }
        b.remove.setOnClickListener { confirmRemove() }
        b.fixerMode.setOnCheckedChangeListener { _, on ->
            if (bindingSwitch) return@setOnCheckedChangeListener
            val a = app.agents.update(id) { it.copy(fixer = on) } ?: return@setOnCheckedChangeListener
            app.log.system("audit", "agent", getString(if (on) R.string.agents_fixer_on_log else R.string.agents_fixer_off_log, a.name))
        }
        b.superPowers.setOnCheckedChangeListener { _, on ->
            if (bindingSwitch) return@setOnCheckedChangeListener
            val apply = {
                app.agents.update(id) { it.copy(superPowers = on) }?.let { a ->
                    app.log.system("audit", "agent", getString(if (on) R.string.agents_super_on_log else R.string.agents_super_off_log, a.name))
                }
                b.revokeSuper.isVisible = on
            }
            // Turning Super Powers on may need the fingerprint check (Settings › Security); off never does.
            if (!on) apply()
            else cc.bridgit.app.security.OwnerCheck.run(this, getString(R.string.biometric_super, agent()?.name ?: ""), onCancel = { bindAgent() }) { apply() }
        }
        b.revokeSuper.setOnClickListener { cc.bridgit.app.ui.adb.RevokeSuperPowersDialog.show(this) { bindAgent() } }
        b.notes.setOnFocusChangeListener { _, has -> if (!has) saveNotes() }
        b.callbackSet.setOnClickListener { askCallbackUrl() }
        b.callbackRemove.setOnClickListener { confirmRemoveCallback() }

        bindAgent()
        app.agents.addListener(agentsChanged)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.log.latestId.collectLatest { delay(300); loadLog() } }
                launch { while (true) { delay(30_000); bindAgent() } }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (_b != null) saveNotes()
    }

    override fun onDestroyView() {
        if (this::id.isInitialized) app.agents.removeListener(agentsChanged)
        super.onDestroyView()
        _b = null
    }

    private fun agent(): Agent? = app.agents.get(id)

    /** Unlock the device lock, so the agent can move to a new machine (may need the fingerprint check). */
    private fun confirmUnlock() {
        val a = agent() ?: return
        MaterialAlertDialogBuilder(requireContext()).setTitle(getString(R.string.agents_device_unlock_title, a.name))
            .setMessage(R.string.agents_device_unlock_text)
            .setPositiveButton(R.string.agents_device_unlock) { _, _ ->
                cc.bridgit.app.security.OwnerCheck.run(this, getString(R.string.biometric_unlock, a.name)) {
                    app.agents.unlockDevice(id)
                    app.log.system("audit", "security", getString(R.string.security_log_unlocked, a.name))
                    bindAgent()
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun bindAgent() {
        val a = agent() ?: return
        if (b.recommendedPermissions.childCount == 0) {
            b.recommendedPermissions.addView(AgentPermissionsDialog.content(this, id))
        } else {
            (b.recommendedPermissions.getChildAt(0).tag as? Runnable)?.run()
        }
        b.toolbar.title = a.name
        b.avatar.setAgent(a.name)
        b.name.text = a.name
        b.connection.text = if(a.connection==cc.bridgit.app.core.Connection.RELAY) {
            val mode=cc.bridgit.app.relay.RelayStore(app).agents().firstOrNull { it.optString("id")==a.id }?.optString("mode")
            getString(if(mode=="connector") R.string.relay_connector_disclosure else if(mode=="sealed") R.string.relay_sealed_label else R.string.relay_setup_required)
        } else getString(R.string.agents_model_via, a.modelText(requireContext()), a.connection.label)
        val added = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(a.createdAt))
        if (a.lastUsedAt > 0) {
            b.added.text = getString(R.string.agents_added_line, added, Fmt.ago(a.lastUsedAt))
            b.lastIp.text = a.lastSourceIp ?: ""
            b.lastIp.isVisible = a.lastSourceIp != null
        } else {
            b.added.text = getString(R.string.agents_added_line_never, added)
            b.lastIp.isVisible = false
        }
        b.header.contentDescription = listOf(a.name, b.connection.text, b.added.text,
            if (b.lastIp.isVisible) b.lastIp.text else null).filterNotNull().joinToString(", ")
        // Relay agents and portals have no Tailscale device to lock to.
        b.deviceLockRow.isVisible = a.connection != cc.bridgit.app.core.Connection.RELAY
        b.deviceLock.text = if (a.boundIps.isEmpty()) getString(R.string.agents_device_unbound)
            else getString(R.string.agents_device_locked, a.boundIps.joinToString(", "))
        b.deviceUnlock.isVisible = a.boundIps.isNotEmpty()
        b.keyPrefix.text = a.keyPrefix
        b.keyPrefix.contentDescription = getString(R.string.agents_key_desc, a.keyPrefix.trimEnd('…'))
        bindingSwitch = true
        b.fixerMode.isChecked = a.fixer
        b.superPowers.isChecked = a.superPowers
        b.revokeSuper.isVisible = a.superPowers
        bindingSwitch = false
        if (!b.notes.hasFocus() && b.notes.text?.toString() != a.notes) {
            b.notes.setText(a.notes)
            savedNotes = a.notes
        }
        if (savedNotes == null) savedNotes = a.notes
        b.activityEmpty.text = getString(R.string.agents_no_calls, a.name)
        bindCallback(a)
        val max = cc.bridgit.app.power.PowerAccess(app).isMaximum(a.id)
        b.powersValue.setText(if (max) R.string.agents_powers_maximum else R.string.agents_powers_limited)
        b.powersCard.contentDescription = getString(R.string.agents_powers) + ", " + b.powersValue.text
    }

    // ---- Callback URL (EVT-05) ----

    private fun bindCallback(a: Agent) {
        val cb = app.callbacks.get(a.id)
        b.callbackValue.text = if (cb != null) getString(R.string.callback_set_to, cb.host) else getString(R.string.callback_not_set, a.name)
        b.callbackSet.setText(if (cb != null) R.string.callback_change else R.string.callback_set)
        b.callbackRemove.isVisible = cb != null
    }

    private fun askCallbackUrl() {
        val a = agent() ?: return
        val ctx = requireContext()
        val layout = com.google.android.material.textfield.TextInputLayout(ctx, null,
            com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.callback_dialog_label)
            placeholderText = getString(R.string.callback_dialog_hint)
        }
        val input = com.google.android.material.textfield.TextInputEditText(layout.context).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setText(app.callbacks.get(a.id)?.url ?: "")
        }
        layout.addView(input)
        val box = android.widget.FrameLayout(ctx).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(layout)
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.callback_dialog_title, a.name))
            .setView(box)
            .setPositiveButton(R.string.callback_next, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val url = input.text?.toString()?.trim() ?: ""
            val check = cc.bridgit.app.core.CallbackRules.check(url,
                cc.bridgit.app.core.CallbackRules.tailnetDomain(app.listener.value.magicDns), app.settings.allowInternetCallbacks)
            if (check is cc.bridgit.app.core.CallbackRules.Check.Bad) { layout.error = check.reason; return@setOnClickListener }
            dialog.dismiss()
            confirmCallback(url)
        }
    }

    /** The full URL is shown before saving (EVT-05). */
    private fun confirmCallback(url: String) {
        val a = agent() ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.callback_confirm_title, a.name))
            .setMessage(getString(R.string.callback_confirm_body, a.name, url))
            .setPositiveButton(R.string.callback_save) { _, _ ->
                val cur = agent() ?: return@setPositiveButton
                app.callbackDispatcher.cancel(cur.id)
                val secret = app.callbacks.set(cur.id, url)
                app.log.system("audit", "agent", getString(R.string.callback_set_log, cur.name, app.callbacks.get(cur.id)?.host ?: "?"))
                bindCallback(cur)
                showSecretOnce(cur.name, secret)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Shown once; never stored in the UI state, the log or saved instance state. */
    private fun showSecretOnce(agentName: String, secret: String) {
        val ctx = requireContext()
        val view = android.widget.TextView(ctx).apply {
            text = getString(R.string.callback_secret_body, agentName) + "\n\n" + secret
            setTextIsSelectable(false)
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        }
        cc.bridgit.app.ui.common.SecretViews.hideSecret(view) { secret } // AGT-08a
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.callback_secret_title, agentName))
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.callback_copy) { _, _ ->
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java) ?: return@setPositiveButton
                val clip = android.content.ClipData.newPlainText(cc.bridgit.app.ui.MainActivity.CLIP_LABEL_SECRET, secret)
                // SEC-11: Android hides the preview of a sensitive clip; MainActivity clears it on return.
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
                cm.setPrimaryClip(clip)
                view.post { com.google.android.material.snackbar.Snackbar.make(b.root, R.string.callback_copied,
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() }
            }
            .setNegativeButton(R.string.callback_done, null)
            .create().also { dialog ->
                cc.bridgit.app.ui.common.ScreenshotProtection.setSecure(dialog.window)
                dialog.show()
            }
    }

    private fun confirmRemoveCallback() {
        val a = agent() ?: return
        val cb = app.callbacks.get(a.id) ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.callback_remove_confirm, a.name, cb.host))
            .setPositiveButton(R.string.callback_remove) { _, _ ->
                app.callbacks.remove(a.id)
                app.callbackDispatcher.cancel(a.id)
                app.log.system("audit", "agent", getString(R.string.callback_removed_log, a.name, cb.host))
                agent()?.let { bindCallback(it) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadLog() {
        val lifecycle = viewLifecycleOwner
        lifecycle.lifecycleScope.launch {
            val (today, entries) = withContext(Dispatchers.IO) {
                // A wide window so a stack of long-polls doesn't push out the call that started them (#0004).
                val log = runCatching { app.log.query(LogQuery(agentIds = listOf(id), limit = 200)) }.getOrDefault(emptyList())
                val name = agent()?.name
                val lookup = { iid: Long ->
                    app.interactions.getFor(id, iid)?.let { ActivityRows.InteractionInfo(it.title, it.kind.id, it.status.id) }
                }
                AgentStats.today(app.log, id) to ActivityRows.collapse(log).take(MAX_ROWS).map { ActivityRows.row(it, name, lookup) }
            }
            if (_b == null) return@launch
            b.statRequests.text = today.requests.toString()
            b.statErrors.text = today.errors.toString()
            b.statSent.text = if (today.bytesOut == 0L) "0 B" else Fmt.bytes(today.bytesOut)
            b.tileRequests.contentDescription = "${today.requests} ${getString(R.string.agents_stat_requests)}"
            b.tileErrors.contentDescription = "${today.errors} ${getString(R.string.agents_stat_errors)}"
            b.tileSent.contentDescription = "${b.statSent.text} ${getString(R.string.agents_stat_sent)}"
            activityAdapter.submitList(entries)
            b.activityEmpty.isVisible = entries.isEmpty()
            b.activityList.isVisible = entries.isNotEmpty()
        }
    }

    private companion object { const val MAX_ROWS = 15 }

    private fun saveNotes() {
        val text = b.notes.text?.toString()?.take(200) ?: return
        if (text == savedNotes) return
        savedNotes = text
        app.agents.update(id) { it.copy(notes = text) }
    }

    private fun rename() {
        val a = agent() ?: return
        val db = AgentsRenameDialogBinding.inflate(layoutInflater)
        db.input.setText(a.name)
        db.input.setSelection(a.name.length)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.agents_rename_title, a.name))
            .setView(db.root)
            .setPositiveButton(R.string.agents_save, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = db.input.text?.toString()?.trim() ?: ""
            if (name == a.name) { dialog.dismiss(); return@setOnClickListener }
            val err = app.agents.checkName(name, exceptId = id)
            if (err != null) { db.layout.error = err; return@setOnClickListener }
            app.agents.update(id) { it.copy(name = name) }
            app.log.system("audit", "agent", getString(R.string.agents_renamed_log, a.name, name))
            dialog.dismiss()
        }
    }

    private fun confirmRotate() {
        val a = agent() ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.agents_rotate_body, a.name))
            .setPositiveButton(R.string.agents_rotate_confirm) { _, _ ->
                val (rotated, key) = app.agents.rotate(id)
                if(rotated.connection==cc.bridgit.app.core.Connection.RELAY){
                    cc.bridgit.app.relay.RelayStore(app).remove(id)
                    startActivity(android.content.Intent(requireContext(),cc.bridgit.app.relay.RelayActivity::class.java).putExtra("relay_agent_id",id))
                    return@setPositiveButton
                }
                app.vault.put(id, key, rotated = true)
                app.log.system("audit", "agent", getString(R.string.agents_rotated_log, rotated.name))
                // "fromRotate" isn't declared in nav_main.xml; Navigation passes extra bundle keys through.
                findNavController().navigate(R.id.addAgent, bundleOf("agentId" to id, "fromRotate" to true))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemove() {
        val a = agent() ?: return
        val last = app.agents.count == 1
        val body = getString(R.string.agents_remove_body, a.name) +
            (if (last) " " + getString(R.string.agents_remove_last) else "")
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(body)
            .setPositiveButton(R.string.agents_remove_confirm) { _, _ ->
                val removed = app.agents.remove(id) ?: return@setPositiveButton
                app.log.system("audit", "agent", getString(R.string.agents_removed_log, removed.name))
                if (app.agents.count == 0) {
                    app.settings.listenerEnabled = false
                    app.log.system("audit", "setting", "Bridgit stopped listening: no control agents left")
                    runCatching { BridgeService.pauseSilently(requireContext()) }
                }
                findNavController().popBackStack()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .also { d ->
                // The confirming action is destructive: error colour (ui-spec 5.5).
                d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(
                    requireContext().themeColor(com.google.android.material.R.attr.colorError))
            }
    }
}
