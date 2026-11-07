package cc.bridgit.app.remoteview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.databinding.ActivityLinkWebViewBinding

/** Connect this device to its owner account; viewer invitations live in My applets. */
class LinkWebViewActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private lateinit var b: ActivityLinkWebViewBinding
    private var code: RemoteView.Code? = null
    private var loading = false
    private var manualSetup = false
    private var setupFailed = false
    private val appletOnly get() = intent.getBooleanExtra("applet_only", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLinkWebViewBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.toolbar.updatePadding(top = bars.top)
            b.scroll.updatePadding(bottom = bars.bottom); insets
        }
        b.toolbar.setNavigationOnClickListener { finish() }
        b.copy.setOnClickListener {
            val c = code ?: return@setOnClickListener
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Bridgit code", c.code))
            Toast.makeText(this, R.string.link_web_view_copied, Toast.LENGTH_SHORT).show()
        }
        b.share.setOnClickListener {
            val c = code ?: return@setOnClickListener
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.link_web_view_share_subject))
                .putExtra(Intent.EXTRA_TEXT, getString(R.string.link_web_view_share_text, c.url, pretty(c.code))), null))
        }
        b.openPortal.setOnClickListener {
            if (app.remoteView.linked.value != null) {
                startActivity(cc.bridgit.app.ui.applets.AppletNavigation.portalIntent(this, if (appletOnly) "dog" else ""))
            } else {
                val url = code?.takeIf { it.expiresAt > System.currentTimeMillis() }?.url
                if (url != null) startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                else fetchCode()
            }
        }
        b.manualSetup.setOnClickListener { manualSetup = !manualSetup; render() }
        b.privacyToggle.setOnClickListener {
            b.privacyDetails.isVisible = !b.privacyDetails.isVisible
            b.privacyToggle.setText(if (b.privacyDetails.isVisible) R.string.link_web_view_privacy_hide else R.string.link_web_view_privacy)
        }
        b.newCode.setOnClickListener { fetchCode() }
        b.turnOff.setOnClickListener {
            if (appletOnly) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.link_web_view_delete_title)
                    .setMessage(R.string.link_web_view_delete_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.link_web_view_device_only) { _, _ ->
                        app.remoteView.setDogBackend(false)
                        app.log.system("audit", "setting", "Pet monitor web portal deletion requested")
                        finish()
                    }.show()
            } else {
                app.remoteView.setEnabled(false)
                app.log.system("audit", "setting", getString(R.string.settings_changed_remote_view, getString(R.string.home_off)))
                finish()
            }
        }
        if (!appletOnly && !app.remoteView.enabled) {
            app.remoteView.setEnabled(true)
            app.log.system("audit", "setting", getString(R.string.settings_changed_remote_view, getString(R.string.home_on)))
        }
        b.toolbar.setTitle(if (appletOnly) R.string.link_web_view_applet_title else R.string.link_web_view_title)
        val tablet = app.remoteView.deviceType() == "tablet"
        val name = cc.bridgit.app.platform.DeviceName.of(app)
        b.deviceName.text = getString(R.string.link_web_view_device, name,
            getString(if (tablet) R.string.link_web_view_tablet else R.string.link_web_view_phone))
        b.steps.setText(if (appletOnly) R.string.link_web_view_applet_steps else R.string.link_web_view_steps)
        b.privacyDetails.setText(if (appletOnly) R.string.link_web_view_applet_privacy else R.string.link_web_view_activity_privacy)
        if (appletOnly) b.turnOff.setText(R.string.link_web_view_device_only)
        render()
        if (app.remoteView.linked.value == null) fetchCode()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { app.remoteView.linked.collect { render() } }
        }
        // While open: ask every few seconds whether the code was used, and count the code down.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var n = 0
                while (true) {
                    delay(1000)
                    render()
                    if (++n % 3 == 0) {
                        val before = app.remoteView.linked.value
                        val now = runCatching { app.remoteView.refresh() }.getOrNull()
                        if (now != null && now != before) {
                            app.log.system("audit", "setting", getString(R.string.remote_view_linked_audit, now))
                            code = null // used
                            render()
                        }
                    }
                }
            }
        }
    }

    private fun pretty(c: String) = c.chunked(4).joinToString("-")

    private fun fetchCode() {
        if (loading) return
        loading = true
        setupFailed = false
        code = null
        render()
        lifecycleScope.launch {
            try { code = app.remoteView.linkCode() } catch (e: Exception) {
                setupFailed = true
            }
            loading = false
            render()
        }
    }

    private fun render() {
        val c = code
        val left = c?.let { (it.expiresAt - System.currentTimeMillis()) / 1000 } ?: 0
        val live = c != null && left > 0
        b.code.text = when {
            live -> pretty(c!!.code)
            loading -> "····-····"
            else -> "––––-––––"
        }
        if (live) b.codeNote.text = getString(R.string.link_web_view_code_note, left / 60, left % 60)
        else if (c != null) b.codeNote.setText(R.string.link_web_view_code_expired)
        else if (loading) b.codeNote.setText(R.string.link_web_view_code_loading)
        else if (app.remoteView.linked.value != null) b.codeNote.setText(R.string.link_web_view_code_used)
        val connected = app.remoteView.linked.value != null
        b.openPortal.setText(if (connected) R.string.link_web_view_open else R.string.link_web_view_connect)
        b.openPortal.isEnabled = connected || !loading
        b.manualSetup.isVisible = !connected
        b.codePanel.isVisible = !connected && manualSetup
        b.copy.isEnabled = live
        b.share.isEnabled = live
        b.newCode.isVisible = !loading
        val email = app.remoteView.linked.value
        b.status.text = when {
            email != null -> getString(R.string.link_web_view_linked, email)
            setupFailed -> getString(R.string.link_web_view_setup_failed)
            loading -> getString(R.string.link_web_view_code_loading)
            else -> getString(R.string.link_web_view_not_linked)
        }
    }
}
