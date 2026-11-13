package cc.bridgit.app.ui.setup

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.core.profile.AndroidProfile
import cc.bridgit.app.core.profile.SetupState
import cc.bridgit.app.ui.common.OwnerScreen

/**
 * The one set-up view (Client Core architecture 4.8): the steps in order, each with its state, and for a step the
 * owner can act on now, one tap to where they act. The same list agents read in GET /v1/device/profile.
 */
class SetupStepsActivity : AppCompatActivity() {
    private lateinit var screen: OwnerScreen
    private val app get() = application as BridgeApp
    private val permissions = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { render() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        screen = OwnerScreen(this, getString(R.string.setup_steps_title))
        screen.listToolbar()
    }

    override fun onResume() { super.onResume(); render() }

    private fun label(id: String): Int = when (id) {
        "device_name" -> R.string.setup_step_device_name
        "reach" -> R.string.setup_step_reach
        "first_agent" -> R.string.setup_step_first_agent
        "owner_email" -> R.string.setup_step_owner_email
        "notifications" -> R.string.setup_step_notifications
        "open_app" -> R.string.setup_step_open_app
        "permissions" -> R.string.setup_step_permissions
        "battery" -> R.string.setup_step_battery
        else -> R.string.setup_step_diagnostic
    }

    private fun icon(id: String): Int = when (id) {
        "device_name" -> R.drawable.ic_nav_device
        "reach" -> R.drawable.ic_network_link
        "first_agent" -> R.drawable.ic_nav_agents
        "owner_email" -> R.drawable.ic_setup_mail
        "notifications" -> R.drawable.ic_setup_bell
        "open_app" -> R.drawable.ic_camera
        "permissions" -> R.drawable.ic_shield
        "battery" -> R.drawable.ic_sheet_battery
        else -> R.drawable.ic_check
    }

    /** The state word, its mark and the mark's colour (Sol design pass, 11 Nov 2026): shape and word carry it, colour helps. */
    private data class Mark(val word: Int, val icon: Int, val color: Int)
    private fun mark(state: String): Mark = when (state) {
        SetupState.DONE -> Mark(R.string.setup_state_done, R.drawable.ic_check_circle, R.attr.colorSuccess)
        SetupState.WAITING -> Mark(R.string.setup_state_waiting, R.drawable.ic_refresh, com.google.android.material.R.attr.colorTertiary)
        SetupState.NEEDS_OWNER -> Mark(R.string.setup_state_needs_owner, R.drawable.ic_info, com.google.android.material.R.attr.colorPrimary)
        else -> Mark(R.string.setup_state_blocked, R.drawable.ic_warning, com.google.android.material.R.attr.colorError)
    }

    private fun main(target: String) = startActivity(Intent(this, cc.bridgit.app.ui.MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("owner_setup_target", target))

    /** Where the owner acts on [s] now, or null when there is nothing to do here. */
    private fun action(s: SetupState.Step, facts: SetupState.Facts): (() -> Unit)? {
        if (s.state == SetupState.DONE || s.state == SetupState.WAITING) return null
        return when (s.id) {
            // The Device Domain Name is registered by the agent, after asking the owner; there is no screen for it here.
            "device_name" -> null
            "owner_email" -> { { main("settings") } }
            "reach" -> { { main("tailscale") } }
            "first_agent" -> { { main("add_agent") } }
            "diagnostic" -> { { main("home") } }
            "notifications" -> { { cc.bridgit.app.ui.home.UiActions.openAppNotificationSettings(this) } }
            "open_app" -> { { main("home") } }
            "permissions" -> { { permissions.launch(facts.missingPermissions.distinct().toTypedArray()) } }
            "battery" -> { { cc.bridgit.app.ui.home.UiActions.requestBatteryExemption(this) } }
            else -> null
        }
    }

    private fun render() {
        screen.clear()
        val facts = AndroidProfile.setupFacts(app)
        val steps = SetupState.steps(facts)
        steps.forEachIndexed { i, s ->
            val act = action(s, facts)
            val m = mark(s.state)
            val title = getString(label(s.id))
            val word = getString(m.word)
            val spoken = if (s.state == SetupState.BLOCKED && s.dependencyKind == "os") "$word, ${getString(R.string.setup_state_android_permission)}" else word
            screen.statusRow(title, icon(s.id), word, m.icon, m.color, spoken, act?.let { getString(R.string.setup_open, title) }, act)
            if (i < steps.lastIndex) screen.listDivider()
        }
    }
}
