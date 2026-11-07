package cc.bridgit.app.planned

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.capability.Perms
import org.json.JSONObject
import androidx.core.content.IntentCompat
import cc.bridgit.app.core.Compat
import cc.bridgit.app.ui.common.Fmt
import cc.bridgit.app.ui.common.OwnerScreen

/** Owner-only actions, reached from Features or a notification. No remote intent may invoke an approval. */
class OwnerToolsActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private var projectionRequest: JSONObject? = null
    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request = projectionRequest; projectionRequest = null
        if (request != null) {
            if (result.resultCode == RESULT_OK && result.data != null) {
                val bounds = Compat.windowBounds(this)
                startForegroundService(Intent(this, ProjectionService::class.java).putExtra("agent_id", request.getString("agent_id"))
                    .putExtra("grant_id", request.getString("id")).putExtra("data", result.data).putExtra("width", bounds.width()).putExtra("height", bounds.height()))
            } else app.screenGrants.decide(request.getString("id"), "denied")
        }
        render()
    }
    private var exporting: JSONObject? = null
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val o = exporting; exporting = null
        if (uri != null && o != null) lifecycleScope.launch(Dispatchers.IO) { runCatching { contentResolver.openOutputStream(uri)?.use { out -> app.transfers.file(o).inputStream().use { it.copyTo(out) } } } }
    }
    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) chooseAgent("downloads", uri, null) }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { render() }
    private lateinit var screen: OwnerScreen
    override fun onCreate(s: Bundle?) { super.onCreate(s)
        projectionRequest = s?.getString("projection_request")?.let { id -> app.screenGrants.all().firstOrNull { it.optString("id") == id } }
        exporting = s?.getString("exporting")?.let { id -> runCatching { app.transfers.get(id) }.getOrNull() }
        screen = OwnerScreen(this, getString(R.string.files_android_access))
    }
    override fun onSaveInstanceState(out: Bundle) {
        projectionRequest?.let { out.putString("projection_request", it.getString("id")) }
        exporting?.let { out.putString("exporting", it.getString("id")) }
        super.onSaveInstanceState(out)
    }
    override fun onResume() { super.onResume(); render() }
    /** Runs an owner action; a failure shows as a toast, never a crash. */
    private fun act(action: () -> Unit): () -> Unit = { runCatching(action).onFailure { Toast.makeText(this, it.message ?: "Action failed", Toast.LENGTH_LONG).show() } }
    private fun open(action: String, data: Uri? = null) { runCatching { startActivity(Intent(action, data)) }.onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
    private fun granted(vararg p: String) = p.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun state(on: Boolean) = if (on) "On" else "Off · tap to allow"
    private fun agentName(o: JSONObject) = app.agents.get(o.optString("agent_id"))?.name ?: "Removed control agent"
    private fun render() {
        if (!::screen.isInitialized) return
        val position = screen.scroll.scrollY
        screen.clear()

        // Waiting for the owner: screen sharing and clipboard requests, as raised cards with an accent pill.
        val shares = app.screenGrants.all().filter { it.optString("status") == "pending" }
        val clips = app.clipboardRequests.all().filter { it.optString("status") == "pending" }
        if (shares.isNotEmpty() || clips.isNotEmpty()) {
            screen.header("Needs you · ${shares.size + clips.size}")
            shares.forEach { o ->
                val seconds = o.getInt("duration_seconds")
                screen.card(OwnerScreen.Accent.NEEDS_YOU) {
                    title("${agentName(o)} wants to see your screen")
                    text("${if (seconds == 0) "One capture" else "For ${seconds / 3600} hour(s)"}. Android asks you which screen to share.")
                    secondary("Deny", action = act { app.screenGrants.decide(o.getString("id"), "denied"); render() })
                    primary("Review", act {
                        if (ProjectionService.instance != null) { Toast.makeText(this@OwnerToolsActivity, "Stop the active screen share first", Toast.LENGTH_LONG).show(); return@act }
                        projectionRequest = o
                        projectionConsent.launch(getSystemService(android.media.projection.MediaProjectionManager::class.java).createScreenCaptureIntent())
                    })
                }
            }
            clips.forEach { o ->
                screen.card(OwnerScreen.Accent.NEEDS_YOU) {
                    title("${agentName(o)} wants to copy text")
                    text("Check the exact text. Your tap copies it; nothing sends a message.")
                    quote(o.optString("text"))
                    secondary("Deny", action = act { app.clipboardRequests.decide(o.getString("id"), false); render() })
                    primary("Copy this text", act { app.clipboardRequests.decide(o.getString("id"), true); render() })
                }
            }
        }

        screen.header("Active controls")
        if (ProjectionService.instance != null) screen.card(OwnerScreen.Accent.NOTICE) {
            title("Screen sharing is on")
            text("A control agent can see your screen until you stop it.")
            primary("Stop screen sharing", act { ProjectionService.instance?.stopSelf(); render() })
        }
        screen.row("Turn flashlight off", "If a control agent left it on", act { app.hardware.light(false, 1); app.log.system("audit", "setting", "Owner turned the flashlight off") })
        screen.row("Stop ringing", "Silences Find my phone", act { app.hardware.ring(false, 1); app.log.system("audit", "setting", "Owner stopped ringing") })
        screen.row("Undo timed phone settings", "Puts back volume, Do Not Disturb and other settings a control agent changed for a while", act { app.hardware.restoreDue(true); app.log.system("audit", "setting", "Owner requested undo of timed phone settings"); render() })

        screen.row(getString(R.string.schedule_open), "Lists of timed actions control agents gave the phone") { startActivity(Intent(this, cc.bridgit.app.schedule.ScheduleActivity::class.java)) }
        screen.header("Files")
        screen.note("Private storage, kept 30 days. Control agents see only their own files.")
        screen.row("Choose a file from Downloads", "Makes a private copy for one control agent", act {
            if (!app.settings.isOn("downloads")) Toast.makeText(this, "Enable Selected Downloads in Feature enablement first", Toast.LENGTH_LONG).show() else pick.launch(arrayOf("*/*"))
        })
        val files = app.transfers.list(kind = intent.getStringExtra("filter"))
        if (files.isEmpty()) screen.note("No files yet.")
        files.forEach { o ->
            screen.card {
                title(o.getString("name"))
                text("${Fmt.bytes(o.getLong("size_bytes"))} · ${agentName(o)} · ${o.getString("kind")}")
                secondary("Delete", danger = true, action = act { app.transfers.delete(o.getString("id")); app.log.system("audit", "setting", "Owner deleted a transfer"); render() })
                secondary("Save a copy", action = act { exporting = o; export.launch(o.getString("name")) })
                primary("Open", act {
                    if (Rules.installable(o.getString("name"), o.getString("mime"))) error("App packages cannot be opened here. Use the verified updater.")
                    val uri = FileProvider.getUriForFile(this@OwnerToolsActivity, "$packageName.fileprovider", app.transfers.file(o))
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, o.getString("mime")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                })
            }
        }
        screen.note("Up to 25 MB a file and 100 MB in all. Control agent uploads: 25 files or 25 MB per control agent, 50 files or 50 MB together.")

        screen.header("Special access")
        screen.note("Grant only what you want to use. Feature switches still apply.")
        screen.row("Do Not Disturb access", state(getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted)) { open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
        screen.row("Modify system settings", state(Settings.System.canWrite(this))) { open(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")) }
        if (!cc.bridgit.app.BuildConfig.PLAY_STORE) screen.row("Usage access", state(UsageModule.usageAllowed(this))) { open(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName")) }
        if (!cc.bridgit.app.BuildConfig.PLAY_STORE) screen.row("Notification access", state(NotificationMemory.allowed(this))) { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        screen.row("Video access", state(granted(Manifest.permission.READ_MEDIA_VIDEO))) { permissions.launch(Perms.forRequest(arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))) }
        screen.row("Step sensor", state(granted(Manifest.permission.ACTIVITY_RECOGNITION))) { permissions.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)) }
        if (!cc.bridgit.app.BuildConfig.PLAY_STORE) {
            val health = arrayOf("android.permission.health.READ_STEPS", "android.permission.health.READ_SLEEP", "android.permission.health.READ_HEART_RATE")
            screen.row("Health Connect reads", if (granted(*health)) "On · steps, sleep, heart rate" else "Steps, sleep, heart rate · tap to allow") { permissions.launch(health) }
            screen.row("Open Health Connect", "Review what Bridgit may read") { open("android.health.connect.action.HEALTH_HOME_SETTINGS") }
        }
        screen.scroll.post { screen.scroll.scrollTo(0, position) }
    }
    private fun chooseAgent(kind: String, uri: Uri?, text: String?) {
        val agents = app.agents.all().filter { it.allows(kind) }
        if (agents.isEmpty()) { Toast.makeText(this, "Add a control agent with $kind access first", Toast.LENGTH_LONG).show(); return }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Send selected file to…").setItems(agents.map { it.name }.toTypedArray()) { _, n ->
            val agent = agents[n]
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        if (!app.settings.isOn(kind) || app.agents.get(agent.id)?.allows(kind) != true) error("Access was disabled")
                        val name = uri?.let { u -> contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } } ?: "shared.txt"
                        val input = uri?.let { contentResolver.openInputStream(it) } ?: text.orEmpty().byteInputStream()
                        val o = app.transfers.save(agent.id, name, uri?.let { contentResolver.getType(it) } ?: "text/plain", kind, input)
                        app.interactions.event(agent.id, "share.created", JSONObject().put("id", o.getString("id")).put("kind", kind).put("name", name))
                        app.log.system("audit", "share", "Owner shared one $kind file with ${agent.name}")
                    }
                }.onFailure { Toast.makeText(this@OwnerToolsActivity, it.message ?: "Could not share", Toast.LENGTH_LONG).show() }
                render()
            }
        }.setNegativeButton("Cancel", null).show()
    }
    companion object {
        fun notify(app: BridgeApp, title: String, text: String, id: Int = 701) {
            val open = PendingIntent.getActivity(app, id, Intent(app, OwnerToolsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            app.getSystemService(NotificationManager::class.java).notify(id, NotificationCompat.Builder(app, BridgeApp.CHANNEL_CAPTURE)
                .setSmallIcon(R.drawable.ic_nav_more).setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}

/** Android share sheet receiver: explicit recipient selection, then bounded copy into private storage. */
class ShareActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (!app.settings.isOn("shares")) { fail("Turn on Share to a control agent in Feature enablement first."); return }
        val agents = app.agents.all().filter { it.allows("shares") }
        if (agents.isEmpty()) { fail("Add a control agent with sharing access first."); return }
        val uris = if (intent.action == Intent.ACTION_SEND_MULTIPLE) IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (uris.size > 10 || uris.any { it.scheme != "content" || it.authority == "$packageName.fileprovider" } || (uris.isEmpty() && sharedText.isNullOrBlank()) || (sharedText?.length ?: 0) > 200_000) { fail("Share up to ten content files or 200 KB of text."); return }
        val view = TextView(this).apply { text = "Choose which control agent receives this share. It may retain a copy on its own servers."; setPadding(32, 48, 32, 32) }; setContentView(view)
        lifecycleScope.launch {
            val preview = withContext(Dispatchers.IO) { runCatching {
                if (uris.isEmpty()) sharedText.orEmpty() else uris.mapIndexed { n, uri ->
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) "${n + 1}. ${c.getString(0)} (${if (c.isNull(1)) "unknown size" else "${c.getLong(1)} bytes"})" else "${n + 1}. Unnamed file"
                    } ?: "${n + 1}. Unnamed file"
                }.joinToString("\n")
            }.getOrElse { "Could not inspect the shared files" } }
            view.text = "${if (uris.isEmpty()) "Shared text" else "${uris.size} shared file(s)"}:\n\n$preview\n\nChoose a control agent below. It may retain a copy on its own servers."
            androidx.appcompat.app.AlertDialog.Builder(this@ShareActivity).setTitle("Review share")
                .setMessage(view.text).setPositiveButton("Choose control agent") { _, _ -> chooseRecipient(agents, uris, sharedText) }
                .setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
        }
    }
    private fun chooseRecipient(agents: List<cc.bridgit.app.core.Agent>, uris: List<Uri>, sharedText: String?) {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Share to control agent").setItems(agents.map { it.name }.toTypedArray()) { _, index ->
            val a = agents[index]
            lifecycleScope.launch {
                val result = runCatching { withContext(Dispatchers.IO) {
                    if (!app.settings.isOn("shares") || app.agents.get(a.id)?.allows("shares") != true) error("Sharing access was revoked")
                    val sources = if (uris.isEmpty()) listOf<Uri?>(null) else uris
                    sources.forEach { uri ->
                        val name = uri?.let { contentResolver.query(it, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } } ?: "shared.txt"
                        val input = uri?.let { contentResolver.openInputStream(it) } ?: sharedText.orEmpty().byteInputStream()
                        val o = app.transfers.save(a.id, name, uri?.let { contentResolver.getType(it) } ?: "text/plain", "shares", input)
                        app.interactions.event(a.id, "share.created", JSONObject().put("id", o.getString("id")).put("kind", "shares").put("name", name))
                    }
                } }
                if (result.isFailure) fail(result.exceptionOrNull()?.message ?: "Could not share") else { Toast.makeText(this@ShareActivity, "Shared with ${a.name}", Toast.LENGTH_SHORT).show(); finish() }
            }
        }.setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
    private fun fail(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); finish() }
}
