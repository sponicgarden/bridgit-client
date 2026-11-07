package cc.bridgit.app.schedule

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.ui.common.OwnerScreen
import org.json.JSONObject

/** Owner-only review of agents' action manifests. Approval is a tap here; no remote intent can approve. */
class ScheduleActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private lateinit var screen: OwnerScreen
    private val onChange: () -> Unit = { runOnUiThread { render() } }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        title = getString(R.string.schedule_title)
        screen = OwnerScreen(this, getString(R.string.schedule_title))
    }
    override fun onResume() { super.onResume(); app.schedules.addListener(onChange); render() }
    override fun onPause() { app.schedules.removeListener(onChange); super.onPause() }

    /** Runs an owner action, then redraws; a failure shows as a toast. */
    private fun act(action: () -> Unit): () -> Unit = {
        runCatching(action).onFailure { Toast.makeText(this, it.message ?: "Failed", Toast.LENGTH_LONG).show() }
        render()
    }
    private fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotEmpty() && it != "null" }

    private fun render() {
        if (!::screen.isInitialized) return
        val position = screen.scroll.scrollY
        screen.clear()
        if (!app.settings.isOn(Schedules.SWITCH)) screen.card(OwnerScreen.Accent.NOTICE) { text(getString(R.string.schedule_switch_off), quiet = false) }
        screen.note(getString(R.string.schedule_intro))
        val module = app.registry.byId("schedule") as? ScheduleModule
        val views = app.schedules.all().keys.mapNotNull { id -> app.agents.get(id)?.let { a -> Triple(id, a, module?.view(id) ?: JSONObject()) } }
        if (views.isEmpty()) { screen.note(getString(R.string.schedule_empty)); return }

        // New lists waiting for approval come first, as raised cards.
        val pending = views.filter { it.third.optJSONObject("pending") != null }
        if (pending.isNotEmpty()) {
            screen.header(getString(R.string.schedule_section_waiting, pending.size))
            pending.forEach { (agentId, agent, v) ->
                val actions = summaries(v.getJSONObject("pending"))
                screen.card(OwnerScreen.Accent.NEEDS_YOU) {
                    title(getString(R.string.schedule_pending_title, agent.name, actions.size))
                    actions.forEach { text("• $it", quiet = false) }
                    text(getString(R.string.schedule_pending_note))
                    secondary(getString(R.string.schedule_decline), action = act { app.schedules.decline(agentId) })
                    primary(getString(R.string.schedule_approve), act {
                        app.schedules.approve(agentId)
                        Toast.makeText(this@ScheduleActivity, getString(R.string.schedule_approved_toast), Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }

        // Each agent's running (or paused) list.
        screen.header(getString(R.string.schedule_section_agents))
        views.forEach { (agentId, agent, v) ->
            val running = v.optJSONObject("active")
            val paused = v.optString("status") == "paused"
            screen.card(if (running != null && !paused) OwnerScreen.Accent.NOTICE else OwnerScreen.Accent.NONE) {
                title(agent.name)
                if (running != null && !paused) ok(getString(R.string.schedule_state_running))
                else text(getString(if (running == null) R.string.schedule_state_none else R.string.schedule_state_paused))
                val actions = running?.optJSONArray("actions")
                for (i in 0 until (actions?.length() ?: 0)) {
                    val a = actions!!.getJSONObject(i)
                    text(a.optString("summary"), quiet = false, gapDp = 10)
                    val detail = listOfNotNull(
                        a.str("next_at")?.let { getString(R.string.schedule_next, it.take(16).replace('T', ' ')) },
                        if (a.optInt("runs") > 0) getString(R.string.schedule_runs, a.optInt("runs"), a.optString("last_status")) else null,
                    ).joinToString(" · ")
                    if (detail.isNotEmpty()) text(detail)
                    a.str("last_error")?.let { warn(it) }
                }
                v.optJSONObject("source")?.let { src ->
                    text(getString(R.string.schedule_source, src.optString("url"), src.optInt("poll_minutes")))
                    src.str("last_error")?.let { warn(it) }
                }
                v.optJSONArray("assets")?.takeIf { it.length() > 0 }?.let { assets ->
                    val bytes = (0 until assets.length()).sumOf { assets.getJSONObject(it).optLong("size") }
                    text(getString(R.string.schedule_assets, assets.length(), bytes / 1024))
                }
                secondary(getString(R.string.schedule_remove), danger = true, action = {
                    AlertDialog.Builder(this@ScheduleActivity).setMessage(getString(R.string.schedule_remove_confirm, agent.name))
                        .setPositiveButton(R.string.schedule_remove) { _, _ ->
                            app.schedules.remove(agentId)
                            app.log.system("audit", "schedule", "Owner removed ${agent.name}'s scheduled actions")
                            render()
                        }.setNegativeButton(android.R.string.cancel, null).show()
                })
                if (running != null) primary(getString(if (paused) R.string.schedule_resume else R.string.schedule_pause), act { app.schedules.setPaused(agentId, !paused, byOwner = true) })
            }
        }
        screen.scroll.post { screen.scroll.scrollTo(0, position) }
    }

    private fun summaries(o: JSONObject): List<String> {
        val a = o.optJSONArray("actions") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it).optString("summary") }
    }
}
