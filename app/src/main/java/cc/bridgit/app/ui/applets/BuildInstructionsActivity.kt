package cc.bridgit.app.ui.applets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiDocs
import cc.bridgit.app.core.Agent
import cc.bridgit.app.ui.common.OwnerScreen
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.home.HomeStates
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Native prompt handoff. Copy, share and agent delivery all carry the same instructions. */
class BuildInstructionsActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private lateinit var sendButton: MaterialButton
    private var sending = false
    private val prompt get() = getString(R.string.applets_build_share_text, ApiDocs.APPLET_URL)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val screen = OwnerScreen(this, getString(R.string.build_instructions_title))
        screen.maxContentWidthDp = 600
        sendButton = MaterialButton(this).apply {
            text = getString(R.string.build_instructions_send)
            setOnClickListener { chooseAgent() }
            visibility = View.GONE
        }
        screen.add(sendButton, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(16), 0, dp(16), dp(8))
        })
        screen.note(getString(R.string.build_instructions_help))
        screen.detailCard {
            secondary(getString(R.string.build_instructions_copy)) {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.build_instructions_title), prompt))
                Toast.makeText(this@BuildInstructionsActivity, R.string.build_instructions_copied, Toast.LENGTH_SHORT).show()
            }
            secondary(getString(R.string.applets_build_share)) {
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.build_instructions_title))
                    .putExtra(Intent.EXTRA_TEXT, prompt)
                startActivity(Intent.createChooser(intent, getString(R.string.applets_build_share_title)))
            }
            quote(prompt)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    sendButton.isVisible = recipients().isNotEmpty()
                    sendButton.isEnabled = !sending
                    delay(2_000)
                }
            }
        }
    }

    private fun recipients(): List<Agent> = if (!app.settings.listenerEnabled || !app.listener.value.running || app.registry.byId("interactions")?.enabled != true) emptyList()
        else BuildInstructionRecipients.available(HomeStates.controlAgents(app))

    private fun chooseAgent() {
        if (sending) return
        val agents = recipients()
        when (agents.size) {
            0 -> { sendButton.isVisible = false; Toast.makeText(this, R.string.build_instructions_unavailable, Toast.LENGTH_SHORT).show() }
            1 -> send(agents.single().id)
            else -> MaterialAlertDialogBuilder(this).setTitle(R.string.build_instructions_send)
                .setItems(agents.map { it.name }.toTypedArray()) { _, index -> send(agents[index].id) }
                .setNegativeButton(R.string.cancel, null).show()
        }
    }

    private fun send(id: String) {
        // Recheck after the picker: a paused, removed or stale agent must not receive the prompt.
        val agent = recipients().firstOrNull { it.id == id } ?: run {
            Toast.makeText(this, R.string.build_instructions_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        sending = true
        sendButton.isEnabled = false
        val text = prompt
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    app.interactions.event(agent.id, "applet.create_requested", JSONObject()
                        .put("instructions", ApiDocs.APPLET_URL).put("prompt", text))
                }.isSuccess
            }
            sending = false
            sendButton.isEnabled = true
            Toast.makeText(this@BuildInstructionsActivity,
                if (ok) getString(R.string.build_instructions_queued, agent.name)
                else getString(R.string.build_instructions_failed), Toast.LENGTH_LONG).show()
        }
    }
}
