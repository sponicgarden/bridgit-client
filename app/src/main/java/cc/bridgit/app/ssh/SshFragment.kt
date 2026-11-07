package cc.bridgit.app.ssh

import android.os.Bundle
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.outbound.Outbound
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.button.MaterialButton
import com.google.android.material.appbar.MaterialToolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import cc.bridgit.app.R
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Owner management: credentials stay on the phone, agents only see their assigned connections. */
class SshFragment : Fragment() {
    private val app get() = requireActivity().application as BridgeApp
    private lateinit var content: LinearLayout
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val root = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(12)) }
        root.addView(MaterialToolbar(requireContext()).apply {
            title = "SSH connections"; setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { findNavController().navigateUp() }
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets -> v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top); insets }
        })
        root.addView(SwitchMaterial(requireContext()).apply {
            text = "Allow SSH access"; isChecked = app.settings.isOn("ssh")
            setOnCheckedChangeListener { _, on -> app.settings.setOn("ssh", on) }
        })
        root.addView(TextView(requireContext()).apply {
            text = "Connect your computers and servers for commands, files and scheduled tasks. Each connection belongs to one control agent. Turning SSH off stops new work and disconnects active sessions; remote commands may continue."
            textSize = 14f
        })
        root.addView(MaterialButton(requireContext()).apply { text = "Add connection"; setOnClickListener { addConnection() } })
        content = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(requireContext()).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }
    override fun onViewCreated(view: View, state: Bundle?) { render() }
    private fun render() {
        viewLifecycleOwner.lifecycleScope.launch {
            try { renderProfiles(withContext(Dispatchers.IO) { app.sshProfiles.all() }) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { show("Connections unavailable", "The saved connection store could not be opened. Existing data has been preserved.") }
        }
    }
    private fun renderProfiles(profiles: List<SshProfile>) {
        content.removeAllViews()
        if (profiles.isEmpty()) content.addView(TextView(requireContext()).apply { text = "No SSH connections yet. Add one here, or let a control agent with SSH permission enroll an authorized device through the API." })
        profiles.forEach { p ->
            content.addView(TextView(requireContext()).apply {
                text = "${p.name}\n${p.username}@${p.host}:${p.port}\nControl agent: ${app.agents.get(p.agentId)?.name ?: "Removed control agent"}\n${p.permissions.joinToString { when(it) { "exec" -> "Run commands"; "files_read" -> "Read files"; else -> "Manage files" } }}\n${p.fingerprint}"
                textSize = 15f; setPadding(0, dp(18), 0, dp(8)); setTextIsSelectable(true)
            })
            content.addView(MaterialButton(requireContext()).apply {
                text = "Test connection"
                setOnClickListener { background(this, { guard ->
                    SshClient {
                        guard()
                        if (!app.settings.isOn("ssh") || Outbound.paused() || app.sshProfiles.all().none { it.id == p.id && it.revision == p.revision }) throw IllegalStateException()
                    }.verify(p)
                }) { show("Connected", "Host identity and login verified. No command was run.") } }
            })
            content.addView(MaterialButton(requireContext()).apply {
                text = "Forget connection"
                setOnClickListener {
                    MaterialAlertDialogBuilder(requireContext()).setTitle("Forget ${p.name}?")
                        .setMessage("Removes the saved credentials and disconnects active sessions. Scheduled tasks using this connection will fail until you update them.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Forget") { _, _ -> viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { app.sshProfiles.remove(p.id) }; render() } }.show()
                }
            })
        }
    }
    private fun show(title: String, message: String) { if (isAdded) MaterialAlertDialogBuilder(requireContext()).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    private fun <T> background(button: Button, work: (() -> Unit) -> T, done: (T) -> Unit) {
        button.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch(Outbound.context(Outbound.Actor.OWNER)) {
            try { val value = withContext(Dispatchers.IO) { val scope = currentCoroutineContext(); work { scope.ensureActive() } }; done(value) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { show("Couldn’t connect", "Check SSH is enabled, the host is reachable, and the fingerprint and login are correct. Nothing was saved on failed verification.") }
            finally { button.isEnabled = true }
        }
    }
    private fun addConnection() {
        val agents = app.agents.all().filter { !it.paused && it.allows("ssh") }
        if (agents.isEmpty()) { show("Choose a control agent first", "Add a control agent and grant it SSH connections access in its permissions, then return here."); return }
        val form = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun field(hint: String, secret: Boolean = false, initial: String = "") = EditText(requireContext()).apply {
            this.hint = hint; setText(initial)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (secret) cc.bridgit.app.ui.common.SecretViews.hideSecret(this) { text?.toString() }
            form.addView(this)
        }
        val agent = Spinner(requireContext()).apply { adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, agents.map { it.name }); form.addView(this) }
        val name = field("Connection name")
        val host = field("Host name or IP address")
        val port = field("Port", initial = "22").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val user = field("Username")
        val password = field("Password (or use private key below)", true)
        val key = field("Private key (optional)", true).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            minLines = 3; maxLines = 5
        }
        val passphrase = field("Key passphrase (optional)", true)
        val pin = field("Verified SHA256 host fingerprint")
        form.addView(MaterialButton(requireContext()).apply {
            text = "Check host fingerprint"
            setOnClickListener {
                val h = host.text.toString(); val p = port.text.toString().toIntOrNull()
                if (p == null || p !in 1..65535) { port.error = "Use a port from 1 to 65535"; return@setOnClickListener }
                background(this, { guard -> SshClient { guard(); if (!app.settings.isOn("ssh") || Outbound.paused()) throw IllegalStateException() }.probe(SshRules.host(h), p) }) { result ->
                    pin.setText(result.getString("fingerprint"))
                    show("Compare this fingerprint", "${result.getString("fingerprint")}\n\nNo login was sent. Compare with the fingerprint shown on the computer or another trusted source before confirming below.")
                }
            }
        })
        val confirmed = CheckBox(requireContext()).apply { text = "I verified this fingerprint with a trusted source"; form.addView(this) }
        val permissions = SshProfile.PERMISSIONS.associateWith { id -> CheckBox(requireContext()).apply {
            text = when(id) { "exec" -> "Run commands (full account access)"; "files_read" -> "Read files"; else -> "Write and manage files" }; form.addView(this)
        } }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Add SSH connection")
            .setView(ScrollView(requireContext()).apply { addView(form) }).setNegativeButton("Cancel", null).setPositiveButton("Verify and save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val selected = agents[agent.selectedItemPosition]
                val body = JSONObject().put("name", name.text.toString()).put("host", host.text.toString()).put("port", port.text.toString().toIntOrNull() ?: 0)
                    .put("username", user.text.toString()).put("password", password.text.toString()).put("private_key", key.text.toString()).put("passphrase", passphrase.text.toString())
                    .put("fingerprint", pin.text.toString()).put("confirm_host_key", confirmed.isChecked)
                    .put("permissions", JSONArray(permissions.filterValues { it.isChecked }.keys.toList()))
                val p = try { SshProfile.draft(body, selected.id) } catch (_: Exception) { show("Check connection details", "Complete the fields, choose password or private key, confirm the fingerprint, and select at least one permission."); return@setOnClickListener }
                background(dialog.getButton(-1), { guard ->
                    SshClient {
                        guard()
                        check(app.settings.isOn("ssh") && !Outbound.paused())
                        val live = app.agents.get(selected.id); check(live != null && !live.paused && live.allows("ssh"))
                    }.verify(p)
                    guard()
                    app.sshProfiles.saveVerified(p, null)
                    p
                }) { _ -> dialog.dismiss(); render() }
            }
        }
        cc.bridgit.app.ui.common.ScreenshotProtection.setSecure(dialog.window)
        dialog.show()
    }
}
