package cc.bridgit.app.ui.more

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.BuildConfig
import cc.bridgit.app.R
import cc.bridgit.app.alerts.AlertFrequency
import cc.bridgit.app.alerts.AlertMessage
import cc.bridgit.app.alerts.AlertRelay
import cc.bridgit.app.alerts.AlertSender
import cc.bridgit.app.alerts.Alerts
import cc.bridgit.app.capability.appVersion
import cc.bridgit.app.databinding.FragmentSettingsBinding
import cc.bridgit.app.databinding.SettingsDialogFieldBinding
import cc.bridgit.app.databinding.SettingsRowBinding
import cc.bridgit.app.databinding.SettingsSwitchRowBinding
import cc.bridgit.app.interact.InteractionNotifier
import cc.bridgit.app.judgment.OwnKeys
import cc.bridgit.app.log.LogQuery
import cc.bridgit.app.power.PowerAccess
import cc.bridgit.app.ui.common.themeColor
import cc.bridgit.app.ui.home.UiActions
import cc.bridgit.app.update.UpdateDialog
import java.security.MessageDigest
import java.text.NumberFormat

/**
 * Settings (UI-19, ui-spec 5.10), built from our own rows. Sections:
 * Max Agentic Control (one switch: every feature, every switch on this screen and every Android permission on, or all of them off), Listener, Privacy (log visibility, lock-screen question text, urgent read-aloud in DND),
 * Log, Notifications, Appearance, About.
 * Allow internet callbacks (EVT-05) sits under Listener. Email alerts sit after Notifications.
 * Send crash reports (D32) and Send feature-use tallies (with See what would be sent) sit under Privacy.
 * About links the privacy policy and terms; Reset deletes all Bridgit data.
 * Not shown yet (later phases): Screenshots, Feedback,
 * precise locations, address lookup, Licences, and the Files / Feedback notification channels.
 */
class SettingsFragment : Fragment() {
    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!
    private val app get() = requireActivity().application as BridgeApp
    private val onSettings: () -> Unit = { view?.post { bind() } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.toolbar.updatePadding(top = bars.top)
            b.scroll.updatePadding(bottom = bars.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        // Max Agentic Control: the same Maximum powers pass as Add agent when turned on, Turn everything off when turned off.
        b.maxControl.title.setText(R.string.settings_max_control)
        UiActions.asSwitch(b.maxControl.root) { PowerAccess(app).everythingOn() }
        b.maxControl.root.setOnClickListener {
            if (PowerAccess(app).everythingOn()) cc.bridgit.app.ui.power.PowersFragment.confirmAllOff(this) {
                bind(); Snackbar.make(b.root, R.string.power_off_done, Snackbar.LENGTH_SHORT).show()
            } else cc.bridgit.app.ui.power.PowersFragment.confirmMaximum(this, null) { bind() }
        }

        // Listener
        b.alwaysOn.title.setText(R.string.settings_always_on)
        b.alwaysOn.value.setText(R.string.settings_always_on_desc)
        b.alwaysOn.root.contentDescription = getString(R.string.settings_always_on) + ", " + getString(R.string.settings_always_on_desc)
        UiActions.asSwitch(b.alwaysOn.root) { app.settings.listenerEnabled }
        b.alwaysOn.root.setOnClickListener { setListener(!app.settings.listenerEnabled) }
        row(b.port, R.string.settings_port, mono = true, inline = true) { askPort() }
        row(b.bind, R.string.settings_bind, mono = true, inline = true, onClick = null)
        prefSwitch(b.internetCallbacks, R.string.settings_internet_callbacks, R.string.settings_internet_callbacks_desc,
            cc.bridgit.app.core.Settings.PREF_INTERNET_CALLBACKS, R.string.settings_changed_internet_callbacks)

        // Privacy
        row(b.logVisibility, R.string.settings_log_visibility, inline = true) { askVisibility() }
        prefSwitch(b.lockscreenText, R.string.settings_lockscreen_text, R.string.settings_lockscreen_text_desc,
            InteractionNotifier.PREF_LOCKSCREEN_TEXT, R.string.settings_changed_lockscreen)
        prefSwitch(b.speakUrgent, R.string.settings_speak_urgent, R.string.settings_speak_urgent_desc,
            InteractionNotifier.PREF_SPEAK_URGENT_IN_DND, R.string.settings_changed_speak_urgent)
        crashSwitch()
        // The outbound log (GET /v1/outbound): default on. The change is audited with "You" by boolSwitch.
        boolSwitch(b.outboundLog, R.string.settings_outbound_log, R.string.settings_outbound_log_desc,
            { app.settings.outboundLog }) { app.settings.setOutboundLog(it, "owner") }
        usageSwitch()
        feedbackUploadSwitch()
        deviceSpecsSwitch()
        remoteViewRows()
        row(b.usageWhat, R.string.settings_usage_what) { showUsage() }
        b.usageWhat.value.setText(R.string.settings_usage_what_desc)

        // Security (bridgit.cc/security): agents may read these but never change them.
        guardedSwitch(b.securityDeviceLock, R.string.settings_security_device_lock, R.string.settings_security_device_lock_desc,
            { app.settings.securityDeviceLock }) { app.settings.securityDeviceLock = it }
        guardedSwitch(b.securityAlerts, R.string.settings_security_alerts, R.string.settings_security_alerts_desc,
            { app.settings.securityPlaceAlerts }) { app.settings.securityPlaceAlerts = it }
        // Turning the fingerprint check off needs the fingerprint too, or a screen-tapping agent could just switch it off.
        guardedSwitch(b.securityBiometric, R.string.settings_security_biometric, R.string.settings_security_biometric_desc,
            { app.settings.securityBiometric }) { app.settings.securityBiometric = it }
        guardedSwitch(b.securitySigned, R.string.settings_security_signed, R.string.settings_security_signed_desc,
            { app.settings.securityRelaySignedOnly }) { app.settings.securityRelaySignedOnly = it }
        row(b.securityLearn, R.string.settings_security_learn) {
            runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://bridgit.cc/security/"))) }
        }
        b.securityLearn.value.setText(R.string.settings_security_learn_desc)

        // Captures (SEC-13, D33): what agents may do with what they capture, and the owner's own copy.
        boolSwitch(b.capturesAgentFiles, R.string.settings_captures_agent_files, R.string.settings_captures_agent_files_desc,
            { app.settings.capturesAgentFiles }) { app.settings.capturesAgentFiles = it }
        boolSwitch(b.capturesAgentUpload, R.string.settings_captures_agent_upload, R.string.settings_captures_agent_upload_desc,
            { app.settings.capturesAgentUpload }) { app.settings.capturesAgentUpload = it }
        row(b.capturesKeep, R.string.settings_captures_keep, inline = true) { askCaptureKeep() }
        boolSwitch(b.capturesShareAll, R.string.settings_captures_share_all, R.string.settings_captures_share_all_desc,
            { app.settings.capturesShareAll }) { app.settings.capturesShareAll = it }
        boolSwitch(b.capturesOwnerFiles, R.string.settings_captures_owner_files, R.string.settings_captures_owner_files_desc,
            { app.settings.capturesOwnerFiles }) { app.settings.capturesOwnerFiles = it }
        // A0049: off lets agents' photos go ahead while the phone is locked, so switching it off is guarded like security.
        guardedSwitch(b.capturesLockedApproval, R.string.settings_captures_locked_approval, R.string.settings_captures_locked_approval_desc,
            { app.settings.capturesLockedCameraApproval }) { app.settings.capturesLockedCameraApproval = it }
        row(b.capturesOwnerUpload, R.string.settings_captures_owner_upload) { askCaptureUpload() }

        // Log
        row(b.keep, R.string.settings_keep, inline = true) { askKeep() }
        row(b.export, R.string.settings_export) {
            val nav = findNavController()
            nav.navigate(R.id.activity, null, NavOptions.Builder().setLaunchSingleTop(true).setRestoreState(true)
                .setPopUpTo(nav.graph.startDestinationId, inclusive = false, saveState = true).build())
        }
        b.export.value.setText(R.string.settings_export_desc)
        row(b.clear, R.string.settings_clear) { askClear() }
        b.clear.value.setText(R.string.settings_clear_desc)
        resourceMonitorRows()

        // Notifications: one row per channel P1a creates.
        row(b.notifListener, R.string.channel_listener) { UiActions.openChannelSettings(requireContext(), BridgeApp.CHANNEL_LISTENER) }
        b.notifListener.value.setText(R.string.channel_listener_desc)
        row(b.notifCapture, R.string.channel_capture) { UiActions.openChannelSettings(requireContext(), BridgeApp.CHANNEL_CAPTURE) }
        b.notifCapture.value.setText(R.string.channel_capture_desc)
        row(b.notifQuestions, R.string.channel_questions) { UiActions.openChannelSettings(requireContext(), InteractionNotifier.CHANNEL_QUESTIONS) }
        b.notifQuestions.value.setText(R.string.channel_questions_desc)
        row(b.notifNotices, R.string.channel_notices) { UiActions.openChannelSettings(requireContext(), InteractionNotifier.CHANNEL_NOTICES) }
        b.notifNotices.value.setText(R.string.channel_notices_desc)
        openOnArrivalSwitch()

        // Email alerts: one confirmed address for every agent, sent through bridgit.cc.
        row(b.alertFrequency, R.string.alerts_frequency) { askAlertFrequency() }
        row(b.alertRecipient, R.string.alerts_recipient) { onAlertRecipient() }
        row(b.alertTest, R.string.alerts_test) { sendTestAlert() }
        row(b.alertWhat, R.string.alerts_what, onClick = null)
        b.alertWhat.value.setText(R.string.alerts_what_desc)

        // Your own AI keys: one row per provider, used only when Bridgit's Jev/Clef allowance runs out.
        row(b.ownKeysWhat, R.string.own_keys_what, onClick = null)
        b.ownKeysWhat.value.setText(R.string.own_keys_what_desc)
        b.ownKeys.removeAllViews()
        ownKeyRows.clear()
        for (p in OwnKeys.PROVIDERS) {
            val r = SettingsRowBinding.inflate(layoutInflater, b.ownKeys, true)
            row(r, p.label) { editOwnKey(p) }
            ownKeyRows[p.id] = r
        }

        // Appearance
        row(b.theme, R.string.settings_theme) { askAppearance() }

        // About
        row(b.version, R.string.settings_version, onClick = null)
        b.version.value.text = getString(R.string.settings_version_value, app.appVersion(), BuildConfig.BUILD_TYPE)
        // UPD-02, UPD-06: debug builds don't check; the row says how to update them.
        row(b.checkUpdates, R.string.update_check) { UpdateDialog.checkForUpdates(requireActivity(), manual = true) }
        b.checkUpdates.value.text = if (UpdateDialog.isDebugBuild) getString(R.string.update_debug) else app.appVersion()
        autoUpdateInstallSwitch()
        autoUpdateOffersSwitch()
        row(b.commit, R.string.settings_commit, mono = true, onClick = null)
        b.commit.value.text = if (BuildConfig.DIRTY_BUILD) getString(R.string.more_dirty, BuildConfig.GIT_COMMIT) else BuildConfig.GIT_COMMIT
        val cert = certificateSha256()
        row(b.cert, R.string.settings_cert, mono = true) {
            if (cert != null) {
                UiActions.copy(requireContext(), "Bridgit certificate", cert)
                Snackbar.make(b.root, R.string.settings_cert_copied, Snackbar.LENGTH_SHORT).show()
            }
        }
        b.cert.value.text = cert ?: "?"
        row(b.spec, R.string.settings_spec, onClick = null)
        b.spec.value.setText(R.string.settings_spec_value)
        row(b.privacyPolicy, R.string.settings_privacy_policy) { openUrl(R.string.settings_privacy_url) }
        b.privacyPolicy.value.setText(R.string.settings_privacy_url)
        row(b.terms, R.string.settings_terms) { openUrl(R.string.settings_terms_url) }
        b.terms.value.setText(R.string.settings_terms_url)

        // Reset
        row(b.deleteAll, R.string.settings_delete_all) { askDeleteAll() }
        b.deleteAll.value.setText(R.string.settings_delete_all_desc)

        bind()
        val target = arguments?.getInt(MoreSettingsSearch.ARG_ROW) ?: 0
        val binding = b
        if (target != 0) binding.scroll.post {
            if (_b !== binding) return@post
            val provider = arguments?.getString(MoreSettingsSearch.ARG_PROVIDER)
            val row = (if (provider != null) ownKeyRows[provider]?.root
                else binding.root.findViewById<View>(target)) ?: return@post
            val bounds = android.graphics.Rect()
            row.getDrawingRect(bounds)
            row.isFocusableInTouchMode = true
            row.requestFocus()
            binding.scroll.offsetDescendantRectToMyCoords(row, bounds)
            binding.scroll.smoothScrollTo(0, bounds.top)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { app.listener.collect { bind() } }
        }
    }

    override fun onStart() {
        super.onStart()
        app.settings.addListener(onSettings)
        if (app.settings.alertEmail != null && app.settings.alertState == Alerts.STATE_PENDING) checkAlertStatus(manual = false)
    }
    override fun onStop() { app.settings.removeListener(onSettings); super.onStop() }
    /** Back from Android's "Display over other apps" page (CAP-56). */
    override fun onResume() { super.onResume(); bind() }
    override fun onDestroyView() { super.onDestroyView(); _b = null }

    private fun row(r: SettingsRowBinding, title: Int, mono: Boolean = false, inline: Boolean = false, onClick: (() -> Unit)?) {
        r.title.setText(title)
        // Short values sit on the title's line, right-aligned, in a shorter row (owner, 2 Nov 2026: less white space).
        if (inline) {
            val dp = resources.displayMetrics.density
            r.root.orientation = android.widget.LinearLayout.HORIZONTAL
            r.root.minimumHeight = (56 * dp).toInt()
            r.root.setPadding(r.root.paddingLeft, (8 * dp).toInt(), r.root.paddingRight, (8 * dp).toInt())
            r.title.layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            r.value.layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = (12 * dp).toInt() }
            r.value.gravity = android.view.Gravity.END
            r.value.maxWidth = (resources.displayMetrics.widthPixels * 0.6).toInt()
        }
        if (mono) {
            r.value.setTextAppearance(R.style.TextAppearance_Bridgit_Mono)
            r.value.setTextColor(requireContext().themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        if (onClick == null) { r.root.isClickable = false; r.root.isFocusable = true; r.root.background = null }
        else r.root.setOnClickListener { onClick() }
    }

    private val ownKeyRows = LinkedHashMap<String, SettingsRowBinding>()

    private fun bindOwnKeys() {
        val keys = OwnKeys(app)
        for (p in OwnKeys.PROVIDERS) {
            val r = ownKeyRows[p.id] ?: continue
            val hint = keys.hint(p.id)
            r.value.text = getString(p.summary) + "\n" + (if (hint != null) getString(R.string.own_key_saved, hint) else getString(R.string.own_key_none))
        }
    }

    /** Paste, replace or remove one provider's key. The key is never shown again, only its last 4 characters. */
    private fun editOwnKey(p: OwnKeys.Provider) {
        val keys = OwnKeys(app)
        val f = SettingsDialogFieldBinding.inflate(layoutInflater)
        val fields = listOf(f.field1 to f.input1, f.field2 to f.input2)
        p.fields.forEachIndexed { i, spec ->
            val (layout, input) = fields[i]
            layout.isVisible = true
            layout.hint = getString(spec.hint)
            input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (spec.secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else 0)
            input.filters = arrayOf(android.text.InputFilter.LengthFilter(400))
            if (spec.secret) layout.endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val label = getString(p.label)
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(p.label)
            .setMessage(p.summary)
            .setView(f.root)
            .setPositiveButton(R.string.own_key_save, null)
            .setNegativeButton(R.string.cancel, null)
        if (keys.has(p.id)) builder.setNeutralButton(R.string.own_key_remove) { _, _ ->
            keys.clear(p.id)
            audit(getString(R.string.settings_changed_own_key_removed, label))
            _b?.let { Snackbar.make(it.root, getString(R.string.own_key_removed_toast, label), Snackbar.LENGTH_SHORT).show() }
            bind()
        }
        val d = builder.create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = p.fields.indices.map { fields[it].second.text?.toString().orEmpty() }
                val secret = OwnKeys.secretOf(p, values)
                if (secret == null) {
                    p.fields.forEachIndexed { i, spec -> fields[i].first.error = if (spec.pattern.matches(values[i].trim())) null else getString(R.string.own_key_invalid) }
                    return@setOnClickListener
                }
                keys.set(p.id, secret)
                // The log says that a key was saved, never the key: agents can read the log.
                audit(getString(R.string.settings_changed_own_key_set, label))
                d.dismiss()
                _b?.let { Snackbar.make(it.root, getString(R.string.own_key_saved_toast, label), Snackbar.LENGTH_SHORT).show() }
                bind()
            }
        }
        d.show()
    }

    private fun bind() {
        if (_b == null) return
        bindOwnKeys()
        val st = app.settings
        val s = app.listener.value
        val max = PowerAccess(app).everythingOn()
        b.maxControl.toggle.isChecked = max
        b.maxControl.value.setText(if (max) R.string.settings_max_control_on_desc else R.string.settings_max_control_off_desc)
        b.maxControl.root.contentDescription = "${b.maxControl.title.text}, ${b.maxControl.value.text}"
        if (b.alwaysOn.toggle.isChecked != st.listenerEnabled) b.alwaysOn.toggle.isChecked = st.listenerEnabled
        b.lockscreenText.toggle.isChecked = prefs().getBoolean(InteractionNotifier.PREF_LOCKSCREEN_TEXT, false)
        b.speakUrgent.toggle.isChecked = prefs().getBoolean(InteractionNotifier.PREF_SPEAK_URGENT_IN_DND, false)
        // These three were only set on tap, so they showed off until touched (and after Max power).
        b.internetCallbacks.toggle.isChecked = st.allowInternetCallbacks
        b.crashReports.toggle.isChecked = cc.bridgit.app.core.CrashReports.enabled(requireContext())
        b.outboundLog.toggle.isChecked = st.outboundLog
        b.usageTallies.toggle.isChecked = app.usage.enabled()
        if (app.remoteView.configured) bindRemoteView()
        val overlay = android.provider.Settings.canDrawOverlays(requireContext())
        b.openOnArrival.toggle.isChecked = overlay && prefs().getBoolean(InteractionNotifier.PREF_OPEN_ON_ARRIVAL, true)
        b.openOnArrival.value.setText(if (overlay) R.string.settings_open_on_arrival_desc else R.string.settings_open_on_arrival_needs)
        b.openOnArrival.root.contentDescription = "${b.openOnArrival.title.text}, ${b.openOnArrival.value.text}"
        // The port with the phone's IP, the address agents reach it on.
        val ip = s.boundTo.firstOrNull { !it.contains(':') } ?: BridgeService.tailscaleAddresses().firstOrNull { !it.contains(':') }
        b.port.value.text = if (ip != null) "${st.port} · IP $ip" else st.port.toString()
        b.bind.value.text = if (s.running && s.boundTo.isNotEmpty())
            s.boundTo.joinToString(", ") { if (it.contains(':')) "[$it]:${s.port}" else "$it:${s.port}" }
            else getString(R.string.settings_not_listening)
        b.logVisibility.value.setText(if (st.logVisibility == "own") R.string.settings_log_own else R.string.settings_log_all)
        b.capturesAgentFiles.toggle.isChecked = st.capturesAgentFiles
        b.capturesAgentUpload.toggle.isChecked = st.capturesAgentUpload
        b.capturesShareAll.toggle.isChecked = st.capturesShareAll
        b.capturesOwnerFiles.toggle.isChecked = st.capturesOwnerFiles
        b.capturesLockedApproval.toggle.isChecked = st.capturesLockedCameraApproval
        b.securityDeviceLock.toggle.isChecked = st.securityDeviceLock
        b.securityAlerts.toggle.isChecked = st.securityPlaceAlerts
        b.securityBiometric.toggle.isChecked = st.securityBiometric
        b.securitySigned.toggle.isChecked = st.securityRelaySignedOnly
        b.capturesKeep.value.text = keepLabel(st.capturesMaxKeepMinutes)
        b.capturesOwnerUpload.value.text = st.capturesOwnerUploadUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() ?: it }
            ?: getString(R.string.settings_captures_owner_upload_none)
        b.keep.value.text = if (st.retentionEntries == 0 && st.retentionDays == 0) getString(R.string.settings_keep_forever)
            else getString(R.string.settings_keep_value, if (st.retentionEntries == 0) "∞" else NumberFormat.getIntegerInstance().format(st.retentionEntries), st.retentionDays)
        bindResourceMonitor()
        b.theme.value.setText(when (st.appearance) {
            "light" -> R.string.settings_theme_light
            "dark" -> R.string.settings_theme_dark
            else -> R.string.settings_theme_system
        })
        val freq = AlertFrequency.of(st.alertFrequency)
        val confirmed = st.alertState == Alerts.STATE_CONFIRMED
        b.alertFrequency.value.text = getString(frequencyLabel(freq)).let {
            if (freq != AlertFrequency.NONE && !confirmed) getString(R.string.alerts_needs_address, it) else it
        }
        val email = st.alertEmail
        b.alertRecipient.value.text = when {
            email == null -> getString(R.string.alerts_recipient_none)
            st.alertState == Alerts.STATE_NONE -> getString(R.string.alerts_recipient_saved, email)
            confirmed -> getString(R.string.alerts_recipient_confirmed, email)
            else -> getString(R.string.alerts_recipient_pending, email)
        }
        b.alertTest.root.isEnabled = confirmed
        b.alertTest.root.alpha = if (confirmed) 1f else 0.5f
        b.alertTest.value.setText(if (confirmed) R.string.alerts_test_ready else R.string.alerts_test_desc)
        listOf(b.port, b.bind, b.logVisibility, b.keep, b.theme, b.alertFrequency, b.alertRecipient, b.alertTest, b.alertWhat, b.version, b.checkUpdates, b.commit, b.cert, b.spec, b.privacyPolicy, b.terms, b.deleteAll, b.export, b.clear,
            b.notifListener, b.notifCapture, b.notifQuestions, b.notifNotices, b.capturesKeep, b.capturesOwnerUpload).forEach { it.root.contentDescription = "${it.title.text}, ${it.value.text}" }
    }

    private fun audit(message: String) = app.log.system("audit", "setting", message)

    private fun prefs() = requireContext().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    /** A switch row for a plain boolean in the "settings" prefs (default off); every change is audited (UI-18). */
    private fun prefSwitch(r: SettingsSwitchRowBinding, title: Int, desc: Int, key: String, changed: Int) {
        r.title.setText(title)
        r.value.setText(desc)
        r.root.contentDescription = getString(title) + ", " + getString(desc)
        UiActions.asSwitch(r.root) { prefs().getBoolean(key, false) }
        r.root.setOnClickListener {
            val on = !prefs().getBoolean(key, false)
            prefs().edit().putBoolean(key, on).apply()
            r.toggle.isChecked = on
            audit(getString(changed, getString(if (on) R.string.home_on else R.string.home_off)))
        }
    }

    /**
     * CAP-56: on only when the pref is set and "Display over other apps" is granted. Turning it on without
     * the grant sets the pref and opens Android's page for Bridgit; [onResume] shows the result.
     */
    private fun openOnArrivalSwitch() {
        val r = b.openOnArrival
        val key = InteractionNotifier.PREF_OPEN_ON_ARRIVAL
        r.title.setText(R.string.settings_open_on_arrival)
        UiActions.asSwitch(r.root) { android.provider.Settings.canDrawOverlays(requireContext()) && prefs().getBoolean(key, true) }
        r.root.setOnClickListener {
            val granted = android.provider.Settings.canDrawOverlays(requireContext())
            val on = !(granted && prefs().getBoolean(key, true))
            prefs().edit().putBoolean(key, on).apply()
            if (on && !granted) runCatching {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${requireContext().packageName}")))
            }
            bind()
            audit(getString(R.string.settings_changed_open_on_arrival, getString(if (on) R.string.home_on else R.string.home_off)))
        }
    }

    /** A0050: install updates without a tap when the phone allows it; on by default. */
    private fun autoUpdateInstallSwitch() {
        val r = b.autoUpdateInstall
        if (!cc.bridgit.app.update.AutoUpdate.supported) { r.root.visibility = View.GONE; return }
        r.title.setText(R.string.update_auto_install)
        r.value.visibility = View.GONE
        r.root.contentDescription = getString(R.string.update_auto_install)
        UiActions.asSwitch(r.root) { cc.bridgit.app.update.AutoUpdate.enabled(requireContext()) }
        r.toggle.isChecked = cc.bridgit.app.update.AutoUpdate.enabled(requireContext())
        r.root.setOnClickListener {
            val on = !cc.bridgit.app.update.AutoUpdate.enabled(requireContext())
            cc.bridgit.app.update.AutoUpdate.setEnabled(requireContext(), on)
            r.toggle.isChecked = on
        }
    }

    /** UPD-02: the daily check's own offer; "Don't show again" on that offer turns this off. */
    private fun autoUpdateOffersSwitch() {
        val r = b.autoUpdateOffers
        if (UpdateDialog.isDebugBuild || UpdateDialog.playUpdates) { r.root.visibility = View.GONE; return }
        r.title.setText(R.string.update_auto_offers)
        r.value.setText(R.string.update_auto_offers_desc)
        r.root.contentDescription = getString(R.string.update_auto_offers) + ", " + getString(R.string.update_auto_offers_desc)
        UiActions.asSwitch(r.root) { UpdateDialog.autoOffersOn(requireContext()) }
        r.toggle.isChecked = UpdateDialog.autoOffersOn(requireContext())
        r.root.setOnClickListener {
            val on = !UpdateDialog.autoOffersOn(requireContext())
            UpdateDialog.setAutoOffers(requireContext(), on)
            r.toggle.isChecked = on
        }
    }

    /** D32: crash reports are on unless turned off, so this switch reads the inverted pref. */
    private fun crashSwitch() {
        val r = b.crashReports
        if (!cc.bridgit.app.core.CrashReports.configured) { r.root.visibility = View.GONE; return }
        val key = cc.bridgit.app.core.CrashReports.PREF
        r.title.setText(R.string.settings_crash_reports)
        r.value.setText(R.string.settings_crash_reports_desc)
        r.root.contentDescription = getString(R.string.settings_crash_reports) + ", " + getString(R.string.settings_crash_reports_desc)
        UiActions.asSwitch(r.root) { !prefs().getBoolean(key, false) }
        r.root.setOnClickListener {
            val on = prefs().getBoolean(key, false) // currently off, so this turns it on
            cc.bridgit.app.capability.SettingsController(app).setCrashReports(on)
            r.toggle.isChecked = on
            audit(getString(R.string.settings_changed_crash_reports, getString(if (on) R.string.home_on else R.string.home_off)))
        }
    }

    /**
     * A0077: store the static spec sheet with Bridgit (on by default). The same [cc.bridgit.app.feedback.DeviceSpecs.setShare]
     * as POST /v1/owner/configuration/specs; off deletes the stored sheet.
     */
    private fun deviceSpecsSwitch() {
        val r = b.deviceSpecs
        if (!cc.bridgit.app.feedback.FeedbackUploader.configured) { r.root.visibility = View.GONE; return }
        r.title.setText(R.string.settings_device_specs)
        r.value.visibility = View.GONE
        r.root.contentDescription = getString(R.string.settings_device_specs)
        r.toggle.isChecked = cc.bridgit.app.feedback.DeviceSpecs.shared(app)
        UiActions.asSwitch(r.root) { cc.bridgit.app.feedback.DeviceSpecs.shared(app) }
        r.root.setOnClickListener {
            val on = !cc.bridgit.app.feedback.DeviceSpecs.shared(app)
            r.toggle.isChecked = on
            val bridge = app
            viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { cc.bridgit.app.feedback.DeviceSpecs.setShare(bridge, on) } }
            audit(getString(R.string.settings_changed_device_specs, getString(if (on) R.string.home_on else R.string.home_off)))
        }
    }

    /** FBK-14: issues go to the Bridgit team unless turned off. Off keeps anything not yet sent on the phone. */
    private fun feedbackUploadSwitch() {
        val r = b.feedbackUpload
        if (!cc.bridgit.app.feedback.FeedbackUploader.configured) { r.root.visibility = View.GONE; return }
        r.title.setText(R.string.settings_feedback_upload)
        // CID-02: the install ID the team sees on its issues, and whether this build proved it's genuine.
        val install = runCatching { cc.bridgit.app.feedback.InstallIdentity.status(requireContext()) }.getOrNull()
        val desc = getString(R.string.settings_feedback_upload_desc) + (install?.let {
            "\n\n" + getString(R.string.settings_install_id, it.installId,
                if (it.trust == "attested") getString(R.string.settings_install_attested) else getString(R.string.settings_install_unverified, it.reason ?: "?"))
        } ?: "")
        r.value.text = desc
        r.root.contentDescription = getString(R.string.settings_feedback_upload) + ", " + desc
        if (install != null) r.root.setOnLongClickListener {
            UiActions.copy(requireContext(), "Bridgit install ID", install.installId)
            Snackbar.make(b.root, R.string.settings_install_id_copied, Snackbar.LENGTH_SHORT).show(); true
        }
        UiActions.asSwitch(r.root) { app.settings.feedbackUpload }
        r.root.setOnClickListener {
            val on = !app.settings.feedbackUpload
            cc.bridgit.app.capability.SettingsController(app).setFeedbackUpload(on)
            r.toggle.isChecked = on
            audit(getString(R.string.settings_changed_feedback_upload, getString(if (on) R.string.home_on else R.string.home_off)))
        }
    }

    /** Remote view (bridgit.cc/watch): off by default. Linking happens in More › Link web view, which turning it on opens. */
    private fun remoteViewRows() {
        val r = b.remoteView
        val rv = app.remoteView
        if (!rv.configured) { r.root.visibility = View.GONE; return }
        r.title.setText(R.string.settings_remote_view)
        UiActions.asSwitch(r.root) { rv.enabled }
        r.root.setOnClickListener {
            if (rv.enabled) {
                rv.setEnabled(false)
                audit(getString(R.string.settings_changed_remote_view, getString(R.string.home_off)))
                bindRemoteView()
            } else startActivity(android.content.Intent(requireContext(), cc.bridgit.app.remoteview.LinkWebViewActivity::class.java))
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { rv.linked.collect { bindRemoteView() } }
        }
        bindRemoteView()
    }

    private fun bindRemoteView() {
        if (_b == null) return
        val rv = app.remoteView
        b.remoteView.toggle.isChecked = rv.enabled
        val email = rv.linked.value
        b.remoteView.value.text = getString(R.string.settings_remote_view_desc) +
            if (rv.enabled && email != null) "\n\n" + getString(R.string.link_web_view_linked, email) else ""
        b.remoteView.root.contentDescription = "${b.remoteView.title.text}, ${b.remoteView.value.text}"
    }

    /** Feature-use tallies: on unless turned off; turning them off also deletes the counts not yet sent. */
    private fun usageSwitch() {
        val r = b.usageTallies
        r.title.setText(R.string.settings_usage_tallies)
        r.value.setText(R.string.settings_usage_tallies_desc)
        r.root.contentDescription = getString(R.string.settings_usage_tallies) + ", " + getString(R.string.settings_usage_tallies_desc)
        UiActions.asSwitch(r.root) { app.usage.enabled() }
        r.root.setOnClickListener {
            val on = !app.usage.enabled()
            app.usage.setEnabled(on)
            r.toggle.isChecked = on
            audit(getString(R.string.settings_changed_usage_tallies, getString(if (on) R.string.home_on else R.string.home_off)))
        }
    }

    /** The exact counts the next batch would carry, so the owner can check there's nothing personal in it. */
    private fun showUsage() {
        val pending = app.usage.pending()
        val text = if (pending.isEmpty()) getString(R.string.settings_usage_none)
            else pending.entries.sortedBy { it.key }.joinToString("\n") { "${it.value}  ${it.key}" }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_usage_what)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setListener(on: Boolean) {
        app.settings.listenerEnabled = on
        runCatching { if (on) BridgeService.start(requireContext()) else BridgeService.pause(requireContext()) }
        audit(getString(R.string.settings_changed_listener, getString(if (on) R.string.home_on else R.string.home_off)))
        bind()
    }

    // ---- Dialogs ----

    /** A dialog whose positive button only closes when [onSave] returns true. */
    private fun fieldDialog(title: Int, setup: (SettingsDialogFieldBinding) -> Unit, onSave: (SettingsDialogFieldBinding) -> Boolean) {
        val f = SettingsDialogFieldBinding.inflate(layoutInflater)
        setup(f)
        val d = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(f.root)
            .setPositiveButton(R.string.settings_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (onSave(f)) d.dismiss() }
        }
        d.show()
    }

    private fun askPort() {
        fieldDialog(R.string.settings_port, { f ->
            f.field1.hint = getString(R.string.settings_port_hint)
            f.input1.setText(app.settings.port.toString())
        }) { f ->
            val n = f.input1.text?.toString()?.trim()?.toIntOrNull()
            if (n == null || n !in 1024..65535) { f.field1.error = getString(R.string.settings_port_invalid); return@fieldDialog false }
            if (n != app.settings.port) confirmPort(n)
            true
        }
    }

    private fun confirmPort(n: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.settings_port_warning)
            .setPositiveButton(R.string.settings_change_port) { _, _ ->
                app.settings.port = n
                BridgeService.running?.poke("port changed")
                audit(getString(R.string.settings_changed_port, n))
                bind()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** A switch row for a Settings property; every change is audited (UI-18). */
    private fun boolSwitch(r: SettingsSwitchRowBinding, title: Int, desc: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        r.title.setText(title)
        r.value.setText(desc)
        r.root.contentDescription = getString(title) + ", " + getString(desc)
        UiActions.asSwitch(r.root) { get() }
        r.root.setOnClickListener {
            val on = !get()
            set(on)
            r.toggle.isChecked = on
            audit(getString(R.string.settings_captures_changed, getString(title), getString(if (on) R.string.home_on else R.string.home_off)))
        }
    }

    /**
     * A security switch whose weakening needs the fingerprint check when that check is on (any of the four
     * switched off). Turning protection on never asks.
     */
    private fun guardedSwitch(r: SettingsSwitchRowBinding, title: Int, desc: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        boolSwitch(r, title, desc, get, set)
        r.root.setOnClickListener {
            val on = !get()
            val apply = {
                set(on)
                r.toggle.isChecked = on
                audit(getString(R.string.settings_captures_changed, getString(title), getString(if (on) R.string.home_on else R.string.home_off)))
            }
            if (on || !app.settings.securityBiometric) apply()
            else cc.bridgit.app.security.OwnerCheck.confirm(this, getString(title)) { apply() }
        }
    }

    private val keepChoices = listOf(10, 60, 24 * 60, 7 * 24 * 60)
    private fun keepLabel(min: Int): String = when {
        min % (24 * 60) == 0 -> resources.getQuantityString(R.plurals.settings_days, min / (24 * 60), min / (24 * 60))
        min % 60 == 0 -> resources.getQuantityString(R.plurals.settings_hours, min / 60, min / 60)
        else -> resources.getQuantityString(R.plurals.settings_minutes, min, min)
    }

    private fun askCaptureKeep() {
        val current = app.settings.capturesMaxKeepMinutes
        val values = (keepChoices + current).distinct().sorted()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_captures_keep)
            .setSingleChoiceItems(values.map { keepLabel(it) }.toTypedArray(), values.indexOf(current)) { d, which ->
                val v = values[which]
                if (v != current) {
                    app.settings.capturesMaxKeepMinutes = v
                    audit(getString(R.string.settings_captures_changed, getString(R.string.settings_captures_keep), keepLabel(v)))
                }
                d.dismiss(); bind()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askCaptureUpload() {
        fieldDialog(R.string.settings_captures_owner_upload, { f ->
            f.field1.hint = getString(R.string.settings_captures_owner_upload_hint)
            f.input1.setText(app.settings.capturesOwnerUploadUrl.orEmpty())
        }) { f ->
            val url = f.input1.text?.toString()?.trim().orEmpty()
            if (url.isNotEmpty() && cc.bridgit.app.core.CallbackRules.check(url,
                    cc.bridgit.app.core.CallbackRules.tailnetDomain(app.listener.value.magicDns), true) !is cc.bridgit.app.core.CallbackRules.Check.Ok) {
                f.field1.error = getString(R.string.settings_captures_owner_upload_invalid); return@fieldDialog false
            }
            app.settings.capturesOwnerUploadUrl = url.ifEmpty { null }
            audit(getString(R.string.settings_captures_changed, getString(R.string.settings_captures_owner_upload),
                url.ifEmpty { getString(R.string.settings_captures_owner_upload_none) }.let { runCatching { java.net.URI(it).host }.getOrNull() ?: it }))
            bind(); true
        }
    }

    /**
     * A0189/A0190: Monitor resources (off by default) and its sampling interval. The same Settings properties as
     * device.resource_monitor / device.resource_monitor_interval in POST /v1/settings and /v1/owner/configuration/settings;
     * monitor.ResourceMonitor follows them.
     */
    private fun resourceMonitorRows() {
        val r = b.resourceMonitor
        r.title.setText(R.string.settings_resource_monitor)
        UiActions.asSwitch(r.root) { app.settings.resourceMonitor }
        r.root.setOnClickListener {
            val on = !app.settings.resourceMonitor
            app.settings.resourceMonitor = on
            audit(getString(R.string.settings_changed_resource_monitor, getString(if (on) R.string.home_on else R.string.home_off)))
            bind()
        }
        row(b.resourceInterval, R.string.settings_resource_interval, inline = true) { askResourceInterval() }
    }

    private fun intervalLabel(v: String) = getString(when (v) {
        "1s" -> R.string.settings_resource_interval_1s
        "10s" -> R.string.settings_resource_interval_10s
        "1h" -> R.string.settings_resource_interval_1h
        else -> R.string.settings_resource_interval_1m
    })

    private fun bindResourceMonitor() {
        val st = app.settings
        val r = b.resourceMonitor
        r.toggle.isChecked = st.resourceMonitor
        val since = st.resourceMonitorChangedAt.takeIf { st.resourceMonitor && it > 0 }
        r.value.isVisible = since != null
        if (since != null) r.value.text = getString(R.string.settings_resource_monitor_since,
            android.text.format.DateUtils.formatDateTime(requireContext(), since, android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH))
        r.root.contentDescription = if (since != null) "${r.title.text}, ${r.value.text}" else r.title.text
        b.resourceInterval.value.text = intervalLabel(st.resourceMonitorInterval)
        b.resourceInterval.root.contentDescription = "${b.resourceInterval.title.text}, ${b.resourceInterval.value.text}"
    }

    private fun askResourceInterval() {
        val values = cc.bridgit.app.monitor.ResourceLog.INTERVALS
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_resource_interval)
            .setSingleChoiceItems(values.map { intervalLabel(it) }.toTypedArray(), values.indexOf(app.settings.resourceMonitorInterval).coerceAtLeast(0)) { d, which ->
                val v = values[which]
                if (v != app.settings.resourceMonitorInterval) {
                    app.settings.resourceMonitorInterval = v
                    audit(getString(R.string.settings_changed_resource_interval, intervalLabel(v)))
                }
                d.dismiss()
                bind()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askVisibility() {
        val values = arrayOf("all", "own")
        val labels = arrayOf(getString(R.string.settings_log_all), getString(R.string.settings_log_own))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_log_visibility)
            .setSingleChoiceItems(labels, values.indexOf(app.settings.logVisibility).coerceAtLeast(0)) { d, which ->
                val v = values[which]
                if (v != app.settings.logVisibility) {
                    app.settings.logVisibility = v
                    audit(getString(R.string.settings_changed_visibility,
                        getString(if (v == "own") R.string.settings_visibility_own else R.string.settings_visibility_all)))
                }
                d.dismiss()
                bind()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askKeep() {
        fieldDialog(R.string.settings_keep, { f ->
            f.field1.hint = getString(R.string.settings_keep_entries)
            f.input1.setText(app.settings.retentionEntries.toString())
            f.field2.isVisible = true
            f.field2.hint = getString(R.string.settings_keep_days)
            f.input2.setText(app.settings.retentionDays.toString())
        }) { f ->
            val entries = f.input1.text?.toString()?.trim()?.replace(",", "")?.toIntOrNull()
            val days = f.input2.text?.toString()?.trim()?.toIntOrNull()
            val okEntries = entries != null && entries in 0..100_000
            val okDays = days != null && days in 0..365
            f.field1.error = if (okEntries) null else getString(R.string.settings_keep_entries_invalid)
            f.field2.error = if (okDays) null else getString(R.string.settings_keep_days_invalid)
            if (!okEntries || !okDays) return@fieldDialog false
            if (entries != app.settings.retentionEntries || days != app.settings.retentionDays) {
                app.settings.retentionEntries = entries!!
                app.settings.retentionDays = days!!
                audit(getString(R.string.settings_changed_keep, entries, days))
            }
            bind()
            true
        }
    }

    /** LOG-05: deletes the audit and agent channels; LogStore.clear writes the entry recording it. */
    private fun askClear() {
        viewLifecycleOwner.lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { app.log.count(LogQuery(channels = listOf("audit", "agent"))) }
            if (_b == null) return@launch
            MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.settings_clear_confirm, n))
                .setPositiveButton(R.string.settings_clear_action) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { app.log.clear("You") }
                        _b?.let { Snackbar.make(it.root, R.string.settings_cleared, Snackbar.LENGTH_SHORT).show() }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun openUrl(url: Int) {
        val uri = getString(url)
        if (!UiActions.openUrl(requireContext(), uri)) Snackbar.make(b.root, getString(R.string.settings_no_browser, uri), Snackbar.LENGTH_LONG).show()
    }

    /**
     * Wipes every store at once with Android's own "Clear storage" (prefs, databases, files, cache and
     * Keystore keys), so no store can be missed; Android then kills the process and the next start is first run.
     * Before that: put back phone settings agents changed (their leases live in that storage) and cancel email alerts.
     */
    private fun askDeleteAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_delete_all_title)
            .setMessage(R.string.settings_delete_all_confirm)
            .setPositiveButton(R.string.settings_delete_all_action) { _, _ ->
                val ctx = requireContext().applicationContext
                b.deleteAll.root.isEnabled = false
                // NonCancellable: leaving or rotating mid-way must not skip the wipe after the restores ran.
                viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.NonCancellable) {
                    withContext(Dispatchers.IO) {
                        runCatching { app.hardware.stop(owner = true) }
                        if (app.settings.alertEmail != null) runCatching { app.alerts.relay.unsubscribe() }
                    }
                    val am = ctx.getSystemService(android.app.ActivityManager::class.java)
                    if (!am.clearApplicationUserData()) {
                        _b?.let { it.deleteAll.root.isEnabled = true; Snackbar.make(it.root, R.string.settings_delete_all_failed, Snackbar.LENGTH_LONG).show() }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askAppearance() {
        val values = arrayOf("system", "light", "dark")
        val labels = arrayOf(getString(R.string.settings_theme_system), getString(R.string.settings_theme_light), getString(R.string.settings_theme_dark))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(labels, values.indexOf(app.settings.appearance).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                if (values[which] != app.settings.appearance) {
                    cc.bridgit.app.capability.SettingsController(app).setAppearance(values[which])
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Email alerts ----

    private fun frequencyLabel(f: AlertFrequency) = when (f) {
        AlertFrequency.EVERY_ACTION -> R.string.alerts_every_short
        AlertFrequency.HOURLY -> R.string.alerts_hourly
        AlertFrequency.DAILY -> R.string.alerts_daily
        AlertFrequency.WEEKLY -> R.string.alerts_weekly
        AlertFrequency.NONE -> R.string.alerts_none
    }

    private fun askAlertFrequency() {
        val values = AlertFrequency.values()
        val labels = values.map { getString(if (it == AlertFrequency.EVERY_ACTION) R.string.alerts_every else frequencyLabel(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.alerts_frequency)
            .setSingleChoiceItems(labels, values.indexOf(AlertFrequency.of(app.settings.alertFrequency))) { d, which ->
                val v = values[which]
                d.dismiss()
                if (v.id != app.settings.alertFrequency) {
                    app.settings.alertFrequency = v.id
                    audit(getString(R.string.settings_changed_alerts_frequency, getString(frequencyLabel(v))))
                    background { app.alerts.poke() }
                }
                bind()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Pending: tapping checks whether the link was clicked. Otherwise: set, change or remove the address. */
    private fun onAlertRecipient() {
        if (app.settings.alertEmail != null && app.settings.alertState == Alerts.STATE_PENDING) { checkAlertStatus(manual = true); return }
        val f = SettingsDialogFieldBinding.inflate(layoutInflater)
        f.field1.hint = getString(R.string.alerts_recipient_hint)
        f.input1.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        f.input1.filters = arrayOf(android.text.InputFilter.LengthFilter(254))
        f.input1.setText(app.settings.alertEmail ?: "")
        cc.bridgit.app.ui.EmailSuffixChips.attach(f.root as android.widget.LinearLayout, f.input1)
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.alerts_recipient)
            .setView(f.root)
            .setPositiveButton(R.string.alerts_send_confirm, null)
            .setNegativeButton(R.string.cancel, null)
        if (app.settings.alertEmail != null) builder.setNeutralButton(R.string.alerts_remove) { _, _ -> removeAlertAddress() }
        val d = builder.create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val email = f.input1.text?.toString()?.trim()?.lowercase().orEmpty()
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { f.field1.error = getString(R.string.alerts_recipient_invalid); return@setOnClickListener }
                d.dismiss()
                subscribeAlerts(email)
            }
        }
        d.show()
    }

    private fun subscribeAlerts(email: String) = viewLifecycleOwner.lifecycleScope.launch {
        // Logs "You set the owner's email address" itself, never the address: agents can read the log.
        val r = withContext(Dispatchers.IO) { app.alerts.subscribe(email, "You") }
        _b?.let { Snackbar.make(it.root, when (r) {
            AlertRelay.Subscribe.CONFIRMED -> getString(R.string.email_verify_done)
            AlertRelay.Subscribe.PENDING -> getString(R.string.alerts_confirm_sent, email)
            AlertRelay.Subscribe.BAD_EMAIL -> getString(R.string.alerts_recipient_invalid)
            AlertRelay.Subscribe.RATE_LIMITED -> getString(R.string.alerts_rate_limited)
            AlertRelay.Subscribe.FAILED -> getString(R.string.alerts_failed)
        }, Snackbar.LENGTH_LONG).show() }
        bind()
    }

    private fun removeAlertAddress() = viewLifecycleOwner.lifecycleScope.launch {
        if (!withContext(Dispatchers.IO) { app.alerts.removeAddress() }) {
            _b?.let { Snackbar.make(it.root, R.string.alerts_failed, Snackbar.LENGTH_LONG).show() }
            return@launch
        }
        audit(getString(R.string.settings_changed_alerts_removed))
        _b?.let { Snackbar.make(it.root, R.string.alerts_removed, Snackbar.LENGTH_SHORT).show() }
        bind()
    }

    /** Asks bridgit.cc whether the confirmation link was clicked; called on start while pending, and on tap. */
    private fun checkAlertStatus(manual: Boolean) = viewLifecycleOwner.lifecycleScope.launch {
        val before = app.settings.alertState
        val s = withContext(Dispatchers.IO) { app.alerts.refreshVerification() }
        when (s) {
            Alerts.STATE_CONFIRMED -> if (before != Alerts.STATE_CONFIRMED)
                _b?.let { Snackbar.make(it.root, R.string.alerts_now_confirmed, Snackbar.LENGTH_SHORT).show() }
            Alerts.STATE_PENDING -> if (manual) _b?.let { Snackbar.make(it.root, R.string.alerts_still_pending, Snackbar.LENGTH_LONG).show() }
            else -> if (manual) _b?.let { Snackbar.make(it.root, R.string.alerts_failed, Snackbar.LENGTH_LONG).show() }
        }
        bind()
    }

    private fun sendTestAlert() = viewLifecycleOwner.lifecycleScope.launch {
        val freq = getString(frequencyLabel(AlertFrequency.of(app.settings.alertFrequency)))
        val msg = AlertMessage(getString(R.string.alerts_test_subject),
            getString(R.string.alerts_test_text, android.os.Build.MODEL ?: "your phone", freq), "test-${System.currentTimeMillis()}")
        val r = withContext(Dispatchers.IO) { app.alerts.relay.send(msg) }
        if (r == AlertSender.Result.GONE) app.settings.alertState = Alerts.STATE_NONE
        _b?.let { Snackbar.make(it.root, if (r == AlertSender.Result.SENT) R.string.alerts_test_sent else R.string.alerts_failed, Snackbar.LENGTH_SHORT).show() }
        bind()
    }

    private fun background(block: () -> Unit) { viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) { runCatching(block) } }

    /** SHA-256 of the current signing certificate, as colon-separated upper-case hex. */
    private fun certificateSha256(): String? = runCatching {
        val ctx = requireContext()
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
    }.getOrNull()
}
