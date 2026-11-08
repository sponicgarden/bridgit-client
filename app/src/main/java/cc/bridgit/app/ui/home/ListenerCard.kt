package cc.bridgit.app.ui.home

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.R as M
import com.google.android.material.snackbar.Snackbar
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.R
import cc.bridgit.app.capability.address
import cc.bridgit.app.databinding.ListenerCardBinding
import cc.bridgit.app.ui.common.Fmt
import cc.bridgit.app.ui.common.themeColor

/**
 * The listener card (UI-09): is Bridgit listening, over Tailscale, for how long, at which address,
 * with Pause / Resume. It was Home's hero card; since Home became Device Diagnostics it sits
 * at the top of the Agents tab. The owner calls [render] on listener, agent or settings changes
 * and [tick] once a second.
 */
class ListenerCard(private val fragment: Fragment, private val b: ListenerCardBinding, private val onChange: () -> Unit) {
    private val app get() = fragment.requireActivity().application as BridgeApp
    private var description = ""

    init {
        b.pause.setOnClickListener { pause(app); onChange() }
        b.resume.setOnClickListener {
            if (app.agents.count == 0) { fragment.findNavController().navigate(R.id.addAgent); return@setOnClickListener }
            resume(app); onChange()
        }
        b.address.setOnClickListener { copyAddress() }
        accessibility()
    }

    private fun tailscaleAddresses(): List<String> = app.listener.value.boundTo.ifEmpty { BridgeService.tailscaleAddresses() }

    fun addressText(): String? {
        val a = app.address()
        val host = a.magicDns ?: a.ip ?: tailscaleAddresses().firstOrNull { !it.contains(':') } ?: return null
        return fragment.getString(R.string.home_address, host, a.port)
    }

    fun render() {
        if (!fragment.isAdded) return
        val ctx = fragment.requireContext()
        val s = app.listener.value
        val st = app.settings
        val port = if (s.port > 0) s.port else st.port
        val paused = isPaused(app)
        val error = s.bindError != null
        val onCard = ctx.themeColor(if (error) M.attr.colorOnErrorContainer else M.attr.colorOnPrimaryContainer)

        b.hero.setCardBackgroundColor(ctx.themeColor(if (error) M.attr.colorErrorContainer else M.attr.colorPrimaryContainer))
        val (text, color, pulse) = when {
            error -> Triple(fragment.getString(R.string.home_state_bind_error, port), ctx.themeColor(M.attr.colorError), false)
            s.running && s.capturing != null -> Triple(s.capturing!!.ifBlank { fragment.getString(R.string.home_state_capturing) }, ContextCompat.getColor(ctx, R.color.beacon_gold), true)
            s.running && s.boundTo.isEmpty() -> Triple(fragment.getString(R.string.home_state_tailscale_down), ctx.themeColor(M.attr.colorError), false)
            s.running -> Triple(fragment.getString(R.string.home_state_listening), ctx.themeColor(M.attr.colorPrimary), false)
            !paused -> Triple(fragment.getString(R.string.home_state_starting), ctx.themeColor(M.attr.colorOutline), false)
            else -> Triple(fragment.getString(R.string.home_state_paused), ctx.themeColor(M.attr.colorOutline), false)
        }
        b.beacon.set(color, pulse)
        if (b.state.text.toString() != text) b.state.text = text
        b.state.setTextColor(onCard)

        b.pause.isVisible = !paused
        b.resume.isVisible = paused
        b.pause.setTextColor(onCard)

        val addr = addressText()
        b.address.text = addr ?: fragment.getString(R.string.home_no_address)
        b.address.isClickable = addr != null
        b.address.setTextColor(onCard)
        b.address.alpha = if (paused) 0.6f else 1f

        val tsOn = tailscaleAddresses().isNotEmpty()
        b.chipTailscale.text = fragment.getString(if (tsOn) R.string.home_chip_tailscale else R.string.home_chip_tailscale_off)
        val check = if (tsOn) ContextCompat.getDrawable(ctx, R.drawable.ic_check)?.mutate()?.apply {
            val size = ctx.resources.getDimensionPixelSize(R.dimen.home_chip_icon)
            setBounds(0, 0, size, size)
            setTint(onCard)
        } else null
        b.chipTailscale.setCompoundDrawablesRelative(check, null, null, null)
        listOf(b.chipUptime, b.chipTailscale).forEach { it.setTextColor(onCard) }
        tick()

        // One focus stop; uptime is added at focus time so the polite live region doesn't speak every minute.
        val desc = buildString {
            append(fragment.getString(R.string.home_cd_hero, text.replaceFirstChar { it.lowercase() }))
            addr?.let { append(' ').append(fragment.getString(R.string.home_cd_address, it)) }
            append(' ').append(fragment.getString(if (tsOn) R.string.home_cd_tailscale_on else R.string.home_cd_tailscale_off))
        }
        if (desc != description) {
            description = desc
            b.heroInfo.contentDescription = desc
        }
    }

    fun tick() {
        val s = app.listener.value
        val up = s.running && s.startedAt > 0
        b.chipUptime.isVisible = up
        if (up) b.chipUptime.text = Fmt.uptime(System.currentTimeMillis() - s.startedAt)
    }

    private fun accessibility() {
        ViewCompat.setAccessibilityDelegate(b.heroInfo, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val up = b.chipUptime.takeIf { it.isVisible }?.text?.let { " $it." } ?: ""
                info.contentDescription = description + up
                if (addressText() != null) info.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                    R.id.home_action_copy_address, fragment.getString(R.string.home_copy_address)))
            }
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action == R.id.home_action_copy_address) { copyAddress(); return true }
                return super.performAccessibilityAction(host, action, args)
            }
        })
    }

    fun copyAddress() {
        val addr = addressText() ?: return
        UiActions.copy(fragment.requireContext(), "Bridgit address", addr)
        fragment.view?.let { Snackbar.make(it, R.string.home_address_copied, Snackbar.LENGTH_SHORT).show() }
    }

    companion object {
        /** Paused: not running, and not about to start (switched off, or no agent yet). */
        fun isPaused(app: BridgeApp): Boolean {
            val s = app.listener.value
            return !s.running && !(app.settings.listenerEnabled && app.agents.count > 0)
        }

        fun pause(app: BridgeApp) {
            app.settings.listenerEnabled = false // first, so MainActivity.onResume doesn't restart it
            runCatching { BridgeService.pause(app) }
        }

        fun resume(app: BridgeApp) {
            app.settings.listenerEnabled = true
            runCatching { BridgeService.start(app) }
        }
    }
}
