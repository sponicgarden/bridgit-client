package cc.bridgit.app.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.PersistableBundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.ui.common.OwnerScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import cc.bridgit.app.api.ApiError

/**
 * Applets › Connections (docs/applet-reach.md A2): where applets may send. Paste a URL, Bridgit recognises the
 * service and asks only for what that service needs. After approval the URL is shown masked; agents only ever see
 * the name and service. Each connection can send a test, copy its signing secret, rotate it, or be revoked.
 */
class ConnectionsActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private val delivery get() = app.platform.delivery
    private lateinit var screen: OwnerScreen

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        screen = OwnerScreen(this, "Connections")
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        screen.clear()
        screen.note("Applets send messages, data and photos only to the places you add here. Control agents see each connection's name and service, never its address or secrets.")
        screen.card { primary("Add a connection") { add() } }
        val all = app.platform.store.list("owner", "destination", 1000).filter { it.optBoolean("enabled") }.sortedBy { it.optLong("created_at") }
        if (all.isEmpty()) screen.note("No connections yet.")
        else screen.header("Your connections (${all.size} of ${Connections.MAX})")
        for (c in all) {
            val p = Connections.preset(c)
            screen.card(if (c.optBoolean("failing")) OwnerScreen.Accent.NEEDS_YOU else OwnerScreen.Accent.NONE) {
                title(c.optString("name"))
                text(p.label + if (c.optBoolean("allow_private")) " · on your private network" else "")
                text(Connections.masked(c.optString("url")))
                if (c.optBoolean("failing")) warn("The last ${c.optInt("fail_streak")} sends failed. Check the service, or send a test.")
                secondary("More") { more(c) }
                primary("Send a test") { test(c) }
            }
        }
        screen.header("Free to use")
        screen.note(Connections.PRESETS.values.filter { it.id != Connections.LEGACY }.joinToString("\n") { "${it.label}${if (it.free) "" else " (paid plan)"}: ${it.note}" })
    }

    private fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply { this.hint = hint; inputType = type; isSingleLine = true }

    private fun add() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val url = field("Paste the webhook address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val name = field("Name, like Home Assistant")
        val ids = Connections.PRESETS.values.filter { it.id != Connections.LEGACY }
        val service = Spinner(this).apply { adapter = ArrayAdapter(this@ConnectionsActivity, android.R.layout.simple_spinner_dropdown_item, ids.map { it.label + if (it.free) "" else " (paid)" }) }
        val note = TextView(this).apply { setPadding(0, 8, 0, 8) }
        val private = CheckBox(this).apply { text = "On my private network (allows http:// and other ports, only to private addresses)" }
        val chat = field("Telegram chat id", InputType.TYPE_CLASS_TEXT)
        val user = field("Pushover user key")
        val token = field("Pushover app API token")
        val headers = EditText(this).apply { hint = "Extra headers, one per line: Name: value"; minLines = 2 }
        fun chosen() = ids[service.selectedItemPosition.coerceIn(0, ids.size - 1)]
        fun show() {
            val p = chosen()
            note.text = p.note
            chat.visibility = if ("chat_id" in p.extra) View.VISIBLE else View.GONE
            user.visibility = if ("user" in p.extra) View.VISIBLE else View.GONE
            token.visibility = if ("token" in p.extra) View.VISIBLE else View.GONE
            headers.visibility = if (p.headers) View.VISIBLE else View.GONE
        }
        url.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val id = Connections.recognise(s.toString())
                service.setSelection(ids.indexOfFirst { it.id == id }.coerceAtLeast(0))
                if (name.text.isBlank() || ids.any { it.label == name.text.toString() }) name.setText(Connections.PRESETS[id]?.label.orEmpty().takeIf { id != "json" }.orEmpty())
                if (s.toString().startsWith("http://")) private.isChecked = true
                show()
            }
        })
        service.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = show()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        listOf(url, name, service, note, private, chat, user, token, headers).forEach { box.addView(it) }
        show()
        MaterialAlertDialogBuilder(this).setTitle("Add a connection").setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Approve") { _, _ ->
                cc.bridgit.app.security.OwnerCheck.run(this, "Approve this connection") {
                    try {
                        val h = JSONObject()
                        headers.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                            val k = line.substringBefore(':').trim(); val v = line.substringAfter(':', "").trim()
                            if (k.isEmpty() || !line.contains(':')) throw ApiError(422, "invalid_connection", "Write each header as Name: value")
                            h.put(k, v)
                        }
                        val p = chosen()
                        val extra = JSONObject().put("chat_id", chat.text.toString()).put("user", user.text.toString()).put("token", token.text.toString())
                        delivery.destination(name.text.toString(), url.text.toString(), private.isChecked, p.id, h.takeIf { p.headers }, extra)
                        Toast.makeText(this, "Connection approved", Toast.LENGTH_SHORT).show()
                        render()
                    } catch (e: ApiError) {
                        MaterialAlertDialogBuilder(this).setTitle("Can't add it").setMessage(e.message).setPositiveButton("OK", null).show()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun test(c: JSONObject) {
        Toast.makeText(this, "Sending a test to ${c.optString("name")}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { delivery.send("owner", c.getString("id"), JSONObject().put("test", true), "Test from Bridgit: this connection works.", null, waitReply = true, flow = "Connection test") }
            }
            val msg = result.fold({ "${c.optString("name")} answered HTTP ${it.optInt("status")}." }, { "The test didn't arrive: ${it.message}" })
            MaterialAlertDialogBuilder(this@ConnectionsActivity).setTitle("Test send").setMessage(msg).setPositiveButton("OK", null).show()
            render()
        }
    }

    /** The less-used actions of one connection, so its card keeps two buttons. */
    private fun more(c: JSONObject) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += (if (c.optString("whsec").isNotEmpty()) "Copy signing secret" else "Copy secret") to { copy(c) }
        if (c.optString("whsec").isNotEmpty() && Connections.preset(c).signs) actions += "New signing secret" to { rotate(c) }
        actions += "Revoke" to { revoke(c) }
        MaterialAlertDialogBuilder(this).setTitle(c.optString("name")).setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }.setNegativeButton("Cancel", null).show()
    }

    private fun copy(c: JSONObject) {
        val secret = c.optString("whsec").ifEmpty { c.optString("secret") }
        val clip = ClipData.newPlainText("Bridgit signing secret", secret)
        clip.description.extras = PersistableBundle().apply { putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, "Copied. Paste it into the receiving service's signature check.", Toast.LENGTH_LONG).show()
    }

    private fun rotate(c: JSONObject) {
        MaterialAlertDialogBuilder(this).setTitle("New signing secret?")
            .setMessage("For the next 24 hours sends are signed with both the old and the new secret, so you can update the receiving service.")
            .setPositiveButton("Make a new one") { _, _ -> delivery.rotate(c.getString("id")); copy(app.platform.store.require(c.getString("id"), "owner", "destination")); render() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun revoke(c: JSONObject) {
        MaterialAlertDialogBuilder(this).setTitle("Revoke ${c.optString("name")}?")
            .setMessage("Flows that send there stop sending, and waiting sends are cancelled.")
            .setPositiveButton("Revoke") { _, _ -> delivery.revoke(c.getString("id")); render() }
            .setNegativeButton("Cancel", null).show()
    }
}
