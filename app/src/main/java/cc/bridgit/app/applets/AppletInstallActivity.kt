package cc.bridgit.app.applets

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.outbound.Outbound
import cc.bridgit.app.security.OwnerCheck
import cc.bridgit.app.ui.common.OwnerScreen
import cc.bridgit.app.ui.common.dp
import cc.bridgit.app.ui.common.themeColor
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as M
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The owner's one review screen for an applet package (from an Import link, a file, or an agent's request), and the
 * list of installed applets with Pause, Update and Remove. Nothing is created until Approve.
 */
class AppletInstallActivity : AppCompatActivity() {
    private val app get() = application as BridgeApp
    private lateinit var screen: OwnerScreen
    /** The request on screen (a pending record), and the blanks as the owner set them. */
    private var pending: JSONObject? = null
    private var config = JSONObject()
    private var shownRequirements = ""
    private var busy = false
    private val permissionRequest = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) refreshCaptureService()
        if (grants.isNotEmpty() && grants.values.none { it }) MaterialAlertDialogBuilder(this).setTitle("Permission still needed")
            .setMessage("This applet needs this permission. You can allow it in Android app settings, or decline the installation.")
            .setPositiveButton("Open app settings") { _, _ -> cc.bridgit.app.ui.home.UiActions.openAppDetails(this) }
            .setNegativeButton("Not now", null).show()
        render()
    }
    /** The "this update deletes" lines the review shows now: approving approves losing exactly these. */
    private var shownDeletes: List<String> = emptyList()
    /** Whether the review on screen is an update (an installed copy exists); a stale request can still name a removed one. */
    private var shownUpdate = false
    private var managedInstance: String? = null

    private var archiveTarget: String? = null
    private val pickHistory = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val id = archiveTarget
        archiveTarget = null
        if (id != null && uri != null) act {
            val history = withContext(Dispatchers.IO) {
                requireNotNull(contentResolver.openInputStream(uri)) { "This file could not be opened." }.use {
                    app.applets.importDogHistory(id, it)
                }
            }
            showDogEpisodes(history)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("archive_target", archiveTarget)
    }

    private fun showDogEpisodes(history: JSONObject) {
        val episodes = history.getJSONArray("episodes")
        val format = java.text.DateFormat.getDateTimeInstance()
        val labels = (episodes.length() - 1 downTo 0).map { i ->
            val episode = episodes.getJSONObject(i)
            val start = format.format(java.util.Date(episode.getLong("start")))
            val end = format.format(java.util.Date(episode.getLong("last_seen")))
            "${episode.getString("kind")} · $start – $end · ${episode.getInt("frames")} frames"
        }
        val title = "Imported history · ${labels.size} episodes"
        val builder = MaterialAlertDialogBuilder(this).setTitle(title).setPositiveButton("Close", null)
        if (labels.isEmpty()) builder.setMessage("The archive contains no episodes.")
        else builder.setItems(labels.toTypedArray(), null)
        builder.show()
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        archiveTarget = state?.getString("archive_target")
        screen = OwnerScreen(this, "Applets")
        screen.toolbar.setNavigationOnClickListener {
            // A notification or restored task can open this screen without a parent.
            if (isTaskRoot) {
                startActivity(Intent(this, cc.bridgit.app.ui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            finish()
        }
        handle(intent)
        // The installed list refreshes while it's shown, so the owner sees the applet working on its own.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                var ticks = 0
                while (true) {
                    kotlinx.coroutines.delay(1_000)
                    if (++ticks % 10 == 0 && pending == null && !busy) manage(keepScroll = true)
                    val r = pending
                    if (r != null && !busy) {
                        val now = runCatching {
                            val p = AppletPackage.check(r.getJSONObject("definition"))
                            JSONArray(app.applets.requirements(p, AppletPackage.compile(p, config)).map { it.json() }).toString()
                        }.getOrNull()
                        if (now != null && shownRequirements.isNotEmpty() && now != shownRequirements) {
                            val y = screen.scroll.scrollY
                            render()
                            screen.scroll.post { screen.scroll.scrollTo(0, y) }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    // Back from turning on a switch the review asked for: show the review again, with that blocker gone.
    override fun onResume() { super.onResume(); refreshCaptureService(); if (!busy) { if (pending == null) manage() else render() } }

    private fun handle(i: Intent) {
        managedInstance = i.getStringExtra(EXTRA_INSTANCE)
        val pendingId = i.getStringExtra(EXTRA_PENDING)
        val bundled = i.getStringExtra(EXTRA_BUNDLED)
        val data = i.data
        when {
            bundled != null -> fromBundled(bundled)
            pendingId != null -> app.applets.pendingGet(pendingId)?.let { show(it) } ?: run { pending = null; manage() }
            data != null && (data.scheme == "bridgit" || data.scheme == "https") -> fromLink(data.toString())
            data != null && data.scheme == "content" -> fromFile(data)
            i.getBooleanExtra(EXTRA_UPDATE, false) && managedInstance != null -> {
                pending = null; manage()
                app.applets.get(managedInstance!!)?.let { openUpdate(it) }
            }
            else -> { pending = null; manage() }
        }
    }

    private fun loading(text: String) { screen.clear(); screen.toolbar.title = "Install applet"; screen.note(text) }

    private fun failed(title: String, why: String) {
        busy = false
        screen.clear()
        screen.card(OwnerScreen.Accent.NOTICE) { title(title); line(why, sp = 16, topDp = 8); secondary("Close") { finish() } }
    }

    private fun fromLink(uri: String) {
        val link = AppletPackage.link(uri) ?: return failed("This link can't install an applet", "Bridgit installs applets only from bridgit.cc and Bridgit's template registry.")
        busy = true; loading("Downloading the applet from ${java.net.URI(link.url).host}…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (p,src) = AppletFetch.packageAndSource(app,link,Outbound.Actor.OWNER,"link")
                    propose(p, src)
                }
            }
            result.fold({ show(it) }, { failed("Couldn't open this applet", it.message ?: "Something went wrong.") })
        }
    }

    private fun fromFile(uri: android.net.Uri) {
        busy = true; loading("Reading the applet file…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readNBytes(AppletPackage.MAX_BYTES + 1) } ?: throw AppletPackage.Bad("The file couldn't be read.")
                    propose(AppletPackage.parse(AppletPackage.unwrap(String(bytes, Charsets.UTF_8))), JSONObject().put("kind", "file"))
                }
            }
            result.fold({ show(it) }, { failed("Couldn't open this applet", it.message ?: "Something went wrong.") })
        }
    }

    /** Resolve historical starter intents to an exact registry release. */
    private fun fromBundled(id: String) {
        busy = true; loading("Opening the applet…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppletFetch.fromBody(app, JSONObject().put("bundled", id), Outbound.Actor.OWNER).let { (p, source) -> propose(p, source) } }
            }
            result.fold({ show(it) }, { failed("Couldn't open this applet", it.message ?: "Something went wrong.") })
        }
    }

    /** A link or file: if this applet is installed, it becomes an update request for that instance. */
    private fun propose(p: AppletPackage.Package, src: JSONObject): JSONObject {
        val existing = app.applets.byPackage(p.id)
        return app.applets.propose(p, null, src, null, replaces = existing?.getString("id"), notify = false)
    }

    // ---- the review ---------------------------------------------------------------------------------------------

    private fun show(r: JSONObject) {
        busy = false
        pending = r
        config = JSONObject(r.getJSONObject("config").toString())
        render()
    }

    private fun render() {
        val r = pending ?: return manage()
        val p = runCatching { AppletPackage.check(r.getJSONObject("definition")) }.getOrElse { return failed("This applet can't be installed", it.message ?: "") }
        val review = runCatching { app.applets.review(p, config, r.optString("replaces").takeIf { it.isNotEmpty() && !r.isNull("replaces") }) }
        screen.clear()
        val update = review.getOrNull()?.optJSONObject("update")
        shownDeletes = update?.optJSONArray("deletes")?.let(AppletPackage::strings) ?: emptyList()
        shownUpdate = update != null
        screen.toolbar.title = if (update != null) "Update applet" else "Install applet"
        screen.card(OwnerScreen.Accent.NEEDS_YOU) {
            title(p.name, large = true)
            lead(p.summary)
            text("Version ${p.version}" + (update?.let { u -> u.optString("from_version").let { if (it == p.version) " · Same version as installed" else " · Currently $it" } } ?: ""), gapDp = 8)
            text(sourceLine(r), gapDp = 4)
            if (r.optJSONObject("source")?.has("library_account") == true && p.definition.optJSONObject("permissions")?.optJSONArray("required")?.let(AppletPackage::strings)?.contains("decisions") == true)
                text("Paid library services use the budget of the Bridgit account linked when this edition was downloaded.", gapDp = 4)
            for ((name, media) in AppletPackage.detachedMedia(p.definition))
                text("$name · " + android.text.format.Formatter.formatFileSize(this@AppletInstallActivity, media.size.toLong()), gapDp = 4)
        }
        r.optJSONObject("change")?.let { c ->
            screen.card {
                title("Your requested change")
                line(c.optString("intent"))
                section("Checks reported by your control agent")
                c.optJSONArray("acceptance")?.let(AppletPackage::strings)?.forEach { bullet(it) }
                line(c.optString("evidence"))
                line("These are the control agent's reported checks. The applet changes only after you approve.")
            }
        }
        review.exceptionOrNull()?.let { e ->
            screen.card(OwnerScreen.Accent.NOTICE) { title("This applet doesn't fit this device"); warn(e.message?.takeIf { !InstallProblem.technical(it) } ?: "Try different choices below.") }
        }
        val rv = review.getOrNull()
        shownRequirements = rv?.optJSONArray("requirements")?.toString().orEmpty()
        val blockers = rv?.let { AppletPackage.strings(it.getJSONArray("blockers")) } ?: emptyList()
        if (rv != null) {
            if (blockers.isNotEmpty()) screen.card(OwnerScreen.Accent.NOTICE) {
                title("Before you can install")
                val requirements = rv.getJSONArray("requirements")
                for (i in 0 until requirements.length()) {
                    val requirement = requirements.getJSONObject(i)
                    if (requirement.getString("action") == "unsupported") text(requirement.getString("message"))
                    view(com.google.android.material.button.MaterialButton(this@AppletInstallActivity, null, R.attr.bridgitPillTextStyle).apply {
                        text = requirement.getString("label")
                        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                        setOnClickListener { resolveRequirement(requirement) }
                    })
                }
            }
            update?.optJSONArray("deletes")?.let(AppletPackage::strings)?.takeIf { it.isNotEmpty() }?.let { lost ->
                screen.card(OwnerScreen.Accent.NOTICE) { title("This update changes saved data"); lost.forEach { warnRow(it) } }
            }
            // What it does (design pass): one titled item per part, a card's buttons and a workflow's steps indented under it.
            screen.header("What it does")
            screen.card {
                val items = rv.optJSONArray("items")
                if (items == null) AppletPackage.strings(rv.getJSONArray("parts")).forEachIndexed { i, s -> bullet(s, first = i == 0) }
                else for (i in 0 until items.length()) items.getJSONObject(i).let { o ->
                    val subs = o.optJSONArray("subs")?.let { a -> (0 until a.length()).map { j -> a.getJSONObject(j).let { x -> (if (x.isNull("label")) null else x.optString("label")) to x.optString("text") } } } ?: emptyList()
                    item(o.optString("title"), AppletPackage.strings(o.getJSONArray("lines")), subs, first = i == 0)
                }
            }
            screen.header("What it can use")
            val added = update?.optJSONArray("added_permissions")?.let(AppletPackage::strings)?.toSet() ?: emptySet()
            screen.card {
                val perms = rv.getJSONArray("permissions")
                for (i in 0 until perms.length()) {
                    val o = perms.getJSONObject(i)
                    val (name, why) = permissionParts(o.getString("words"))
                    permission(name, why, isNew = o.getString("id") in added, first = i == 0)
                }
                // Tables this update newly lets the installing agent read: new access, shown like a new permission.
                update?.optJSONArray("shares")?.let(AppletPackage::strings)?.forEachIndexed { i, sh -> permission(sh, null, isNew = true, first = perms.length() == 0 && i == 0) }
                text(if (p.adapter == AppletPackage.DOG_ADAPTER) "This is Bridgit's built-in Pet monitor: it runs inside Bridgit, not as a control agent. Device switches still apply."
                    else "It gets its own entry under Control agents, with only these permissions. Device switches still apply.", gapDp = 12)
            }
        }
        blanks(p)
        screen.bottomBar(null,
            "Decline" to { lifecycleScope.launch { app.applets.decline(r.getString("id")); pending = null; finish() } },
            "Approve" to { approve(r, p) },
            primaryEnabled = rv != null && blockers.isEmpty())
    }

    private fun refreshCaptureService() {
        if (app.settings.listenerEnabled) runCatching { cc.bridgit.app.BridgeService.start(this) }
    }

    private val screenShare = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) startForegroundService(
            Intent(this, cc.bridgit.app.planned.ProjectionService::class.java).putExtra("consent", result.data))
        render()
    }

    /** Only owner taps execute trusted, locally derived actions; templates cannot supply intents or settings keys. */
    private fun resolveRequirement(requirement: JSONObject) {
        val action = requirement.getString("action")
        val target = requirement.optString("target")
        val label = requirement.getString("label")
        when (action) {
            "permission" -> permissionRequest.launch(cc.bridgit.app.capability.Perms.forRequest(arrayOf(target)))
            "module_permission" -> app.registry.byId(target)?.let { permissionRequest.launch(cc.bridgit.app.capability.Perms.forRequest(cc.bridgit.app.ui.home.UiActions.permissionsFor(it))) }
            "location_settings" -> cc.bridgit.app.ui.home.UiActions.tryStart(this, Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            "notifications" -> cc.bridgit.app.ui.home.UiActions.openAppNotificationSettings(this)
            "accessibility" -> cc.bridgit.app.ui.home.UiActions.tryStart(this, Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            "notification_access" -> cc.bridgit.app.ui.home.UiActions.tryStart(this, Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "screen_share" -> screenShare.launch(getSystemService(android.media.projection.MediaProjectionManager::class.java).createScreenCaptureIntent())
            "start_capture" -> { refreshCaptureService(); screen.scroll.postDelayed({ if (!isFinishing && pending != null) render() }, 500) }
            "device_setup" -> startActivity(Intent(this, cc.bridgit.app.platform.ControlCenterActivity::class.java))
            "unsupported" -> MaterialAlertDialogBuilder(this).setTitle("This device can't run this applet yet")
                .setMessage(requirement.getString("message") + " Choose an applet supported by this device, or ask the template's author for a compatible version.")
                .setPositiveButton("Back to library") { _, _ -> finish() }.setNegativeButton("Keep reviewing", null).show()
            "module", "capability", "resume" -> MaterialAlertDialogBuilder(this).setTitle(label)
                .setMessage("Enable for Bridgit and existing authorized control agents?" +
                    (if (target == "decisions" || target == "judgment") " Jev and Clef requests use the applet’s spending budget." else ""))
                .setPositiveButton("Enable") { _, _ -> OwnerCheck.run(this, label) {
                    runCatching {
                        when (action) {
                            "module" -> app.registry.byId(target)?.let { cc.bridgit.app.ui.home.UiActions.setSwitch(app, it, true) }
                            "capability" -> cc.bridgit.app.platform.Capabilities.setEnabled(app, target, true)
                            "resume" -> cc.bridgit.app.ui.home.ListenerCard.resume(app)
                        }
                    }.onFailure { dialog("Couldn't enable feature", it.message ?: "Please try again.") }
                    render()
                } }.setNegativeButton("Not now", null).show()
        }
    }

    /** "Camera: takes photos for this applet" → name and explanation; a word list without one is all name. */
    private fun permissionParts(words: String): Pair<String, String?> {
        val i = listOf(": ", " — ").map { words.indexOf(it) to it }.filter { it.first > 0 }.minByOrNull { it.first } ?: return words to null
        return words.substring(0, i.first) to words.substring(i.first + i.second.length).replaceFirstChar { it.uppercase() }
    }

    /**
     * The device an applet lands on, named when the owner is looking at another device's screens (a satellite on its
     * hub's screens, or a linked portal): installs and budgets are always this device's, so "this device" would be wrong there.
     */
    private fun remoteViewDevice(): String? = runCatching {
        val links = cc.bridgit.app.portal.PortalLinks(app)
        if (links.selectedHub() != null || links.selectedLink() != null) cc.bridgit.app.platform.DeviceName.of(app) else null
    }.getOrNull()

    private fun sourceLine(r: JSONObject): String {
        val s = r.optJSONObject("source") ?: JSONObject()
        val by = r.optString("requested_by_name").takeIf { !r.isNull("requested_by_name") && it.isNotEmpty() }
        return when {
            by != null -> "Requested by your control agent $by"
            s.optString("kind") == "template" -> "From the Bridgit template library (${s.optString("template")}, revision ${s.optInt("revision")})"
            s.optString("kind") == "file" -> "From a file on this device"
            s.optString("kind") == "bundled" -> "Comes with Bridgit"
            else -> "From ${runCatching { java.net.URI(s.optString("url")).host }.getOrNull() ?: "a link"}"
        }
    }

    /**
     * One outlined field per blank (Sol 6.1 design pass): a dropdown for a fixed choice, a switch row for yes/no, a
     * field for numbers and text. Help sits inside the field as helper text; 16dp between fields.
     */
    private fun blanks(p: AppletPackage.Package) {
        val props = p.configSchema.optJSONObject("properties") ?: return
        if (props.length() == 0) return
        screen.header("Your choices")
        screen.card {
            var first = true
            for (k in props.keys()) {
                if (p.adapter == AppletPackage.DOG_ADAPTER && k != "camera") continue // the built-in monitor uses only the camera choice
                val s = props.getJSONObject(k)
                val label = p.inputLabel(k)
                val help = p.inputHelp(k)
                val enum = s.optJSONArray("enum")
                val types = s.opt("type").let { if (it is JSONArray) AppletPackage.strings(it) else listOf(it.toString()) }
                val v = when {
                    enum != null -> choiceField(k, label, help, (0 until enum.length()).map { enum.get(it) })
                    "boolean" in types -> switchRow(k, label, help)
                    else -> textField(k, s, label, help, number = "integer" in types || "number" in types, decimal = "number" in types)
                }
                view(v)
                (v.layoutParams as LinearLayout.LayoutParams).topMargin = dp(if (first) 4 else 16)
                first = false
            }
        }
    }

    private fun outlined(label: String, help: String?, style: Int) = TextInputLayout(this, null, style).apply {
        hint = label
        help?.let { helperText = it }
    }

    private fun choiceField(k: String, label: String, help: String?, values: List<Any>) = outlined(label, help, M.attr.textInputOutlinedExposedDropdownMenuStyle).apply {
        val field = MaterialAutoCompleteTextView(context).apply {
            inputType = InputType.TYPE_NULL
            setSimpleItems(values.map { it.toString() }.toTypedArray())
            setText(values.firstOrNull { it.toString() == config.opt(k)?.toString() }?.toString() ?: values.firstOrNull()?.toString().orEmpty(), false)
            setOnItemClickListener { _, _, pos, _ -> if (config.opt(k)?.toString() != values[pos].toString()) { config.put(k, values[pos]); render() } }
        }
        addView(field)
        endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
    }

    private fun switchRow(k: String, label: String, help: String?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(MaterialSwitch(context).apply {
            text = label; minHeight = dp(48); isChecked = config.optBoolean(k)
            setTextAppearance(M.style.TextAppearance_Material3_BodyLarge)
            setOnCheckedChangeListener { _, on -> config.put(k, on); render() }
        }, LinearLayout.LayoutParams(-1, -2))
        help?.let { h ->
            addView(TextView(context).apply {
                text = h
                setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium)
                setTextColor(themeColor(M.attr.colorOnSurfaceVariant))
            })
        }
    }

    private fun textField(k: String, s: JSONObject, label: String, help: String?, number: Boolean, decimal: Boolean) =
        outlined(label, help, M.attr.textInputOutlinedStyle).apply {
            val layout = this
            addView(TextInputEditText(context).apply {
                setText(config.opt(k)?.takeIf { it != JSONObject.NULL }?.toString() ?: "")
                inputType = if (number) InputType.TYPE_CLASS_NUMBER or (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0) else InputType.TYPE_CLASS_TEXT
                doAfterTextChanged { t ->
                    val v = t?.toString().orEmpty()
                    val value: Any? = when {
                        number -> v.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: v.toDoubleOrNull()
                        s.optString("type") == "array" -> runCatching { JSONArray(v) }.getOrNull() ?: v
                        s.optString("type") == "object" -> runCatching { JSONObject(v) }.getOrNull() ?: v
                        else -> v
                    }
                    if (value != null) config.put(k, value) else config.remove(k)
                    layout.error = AppletPackage.validate(s, value ?: JSONObject.NULL, label).firstOrNull()?.substringAfter(": ")
                }
                setOnFocusChangeListener { _, has -> if (!has) render() }
            })
        }

    private fun approve(r: JSONObject, p: AppletPackage.Package) {
        if (busy) return
        val problems = AppletPackage.validate(p.configSchema, config)
        if (problems.isNotEmpty()) return dialog("Check your choices", problems.joinToString("\n"))
        OwnerCheck.run(this, "Install ${p.name}") {
            busy = true
            lifecycleScope.launch {
                val result = runCatching { app.applets.approve(r.getString("id"), config, shownDeletes) }
                busy = false
                result.fold({ installed ->
                    pending = null
                    // Use the same destination as Open, including the exact package's web portal.
                    startActivity(cc.bridgit.app.ui.applets.AppletNavigation.openIntent(app, installed))
                    finish()
                }, {
                    // More data would be lost than the review showed: show the review again with the new counts.
                    if ((it as? AppletInstances.Refused)?.code == "data_changed") render()
                    failedInstall(it, p, update = !r.isNull("replaces"))
                })
            }
        }
    }

    /** The install didn't go through: plain words, and a one-tap longer interval when that would fit (it doesn't install). */
    private fun failedInstall(e: Throwable, p: AppletPackage.Package, update: Boolean) {
        val interval = p.configSchema.optJSONObject("properties")?.optJSONObject(INTERVAL)
        val w = InstallProblem.words((e as? AppletInstances.Refused)?.code, e.message, (e as? AppletInstances.Refused)?.extra ?: emptyMap(),
            interval?.let { (config.opt(INTERVAL) as? Number)?.toInt() }, interval?.optInt("minimum", 1), interval?.optInt("maximum", 3600), update)
        val d = MaterialAlertDialogBuilder(this).setTitle(w.title).setMessage(w.body)
        val fix = w.fixInterval
        if (fix != null) d.setPositiveButton("Use every $fix seconds") { _, _ -> config.put(INTERVAL, fix); render() }.setNegativeButton("Keep my choice", null)
        else d.setPositiveButton("OK", null)
        d.show()
    }

    private fun dialog(title: String, text: String) { MaterialAlertDialogBuilder(this).setTitle(title).setMessage(text).setPositiveButton("OK", null).show() }

    // ---- installed applets ---------------------------------------------------------------------------------------

    private val words by lazy { AppletCardText(this) }
    // Presentation only: keep an owner's expanded card open during the periodic refresh.
    private val expandedApplets = mutableSetOf<String>()

    private var libraryPage: AppletLibrary.Page? = null
    private var libraryLoading = false
    private var libraryError: String? = null

    private fun browseLibrary(after: String?) {
        if (libraryLoading) return
        libraryLoading = true
        libraryError = null
        manage(keepScroll = true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AppletLibrary.page(app, after) } }
            libraryLoading = false
            result.onSuccess { libraryPage = it }.onFailure { libraryError = it.message ?: "Could not load applets." }
            if (pending == null && !busy) manage(keepScroll = true)
        }
    }

    private fun manage(keepScroll: Boolean = false) {
        pending = null
        val y = screen.scroll.scrollY
        if (keepScroll) screen.scroll.post { screen.scroll.scrollTo(0, y) }
        screen.clear()
        screen.toolbar.title = getString(R.string.applets_title_installed)
        screen.toolbar.minimumHeight = dp(48)
        screen.maxContentWidthDp = 600
        val waiting = app.applets.pending().filter { !it.isNull("requested_by") }
        if (waiting.isNotEmpty()) {
            screen.header(getString(R.string.applets_waiting), topDp = 8)
            for (w in waiting) screen.card(OwnerScreen.Accent.NEEDS_YOU) {
                val d = w.getJSONObject("definition")
                title(d.optString("name"))
                text(getString(if (w.isNull("replaces")) R.string.applets_waiting_asks_install else R.string.applets_waiting_asks_update, w.optString("requested_by_name")))
                primary(getString(R.string.applets_review)) { show(w) }
            }
        }
        val all = app.applets.all().filter { managedInstance == null || it.optString("id") == managedInstance }
        if (all.isEmpty()) screen.empty(R.drawable.ic_nav_applets,
            getString(if (managedInstance == null) R.string.applets_empty_title else R.string.applets_selected_removed),
            if (managedInstance == null) getString(R.string.applets_empty_body) else "")
        for (r in all) screen.detailCard { appletCard(r) }
        val installedIds = all.mapTo(HashSet()) { it.optString("package_id") }
        val available = if (managedInstance != null) emptyList() else libraryPage?.entries.orEmpty().filterNot { it.id in installedIds }
        if (available.isNotEmpty()) {
            screen.header(getString(R.string.applets_available_here), topDp = 12)
            for (e in available) screen.detailCard {
                appletIdentity(e.title, e.desc, e.icon)
                val label = getString(R.string.applets_set_up)
                pill(actions(8), label, R.attr.bridgitPillTonalStyle, cd(label, e.title)) { fromLink(e.installUri) }
            }
        }
        if (managedInstance == null) screen.card {
            libraryError?.let { text(it) }
            val next = libraryPage?.next
            if (libraryLoading) text("Loading applets…")
            else if (libraryPage == null || next != null) secondary(if (next == null) "Browse applet library" else "More applets") { browseLibrary(next) }
            if (!libraryLoading && libraryPage != null) secondary("First page") { browseLibrary(null) }
        }
    }

    /** A scannable applet summary; operational detail is expanded only when requested. */
    private fun OwnerScreen.Card.appletCard(r: JSONObject) {
        val name = r.optString("name")
        val id = r.getString("id")
        val state = AppletLifecycle.state(r)
        val running = state in setOf("running", "starting", "pausing")
        val pauseLabel = if (state == "pausing") "Retry pause" else getString(if (running) R.string.applets_pause else R.string.applets_resume)
        val mode = AppletPackage.runMode(r.getJSONObject("definition")).replaceFirstChar { it.uppercase() }
        appletIdentity(name, "$mode · ${state.replaceFirstChar { it.uppercase() }}",
            R.drawable.ic_nav_applets, running)
        r.optString("problem").takeIf { it.isNotEmpty() }?.let { line(it, colorAttr = M.attr.colorError, topDp = 8) }
        val budget = AppletBudget.status(app, r)
        val budgetState = budget.optString("state")
        if (budgetState == "out" || budgetState == "warning") {
            val (label, _, tone) = words.status(budgetState, null)
            line(label, colorAttr = toneColor(tone, M.attr.colorOnSurfaceVariant), topDp = 8)
        }
        val lastCheck = app.watches.list(r.optString("agent_id")).mapNotNull {
            it.json(false).optJSONObject("last_check")?.optString("at")?.let(AppletBudget::parseTime)
        }.maxOrNull()
        lastCheck?.let { line(words.lastCheck(it), topDp = 8) }
        val agent = r.optString("agent_id")
        val expanded = id in expandedApplets
        val flowRuns = if (agent.isBlank()) emptyList() else app.platform.store.list(agent, "execution", if (expanded) 200 else 1)
            .filter { it.has("workflow_id") }.sortedByDescending { it.optLong("started_at") }
        flowRuns.firstOrNull()?.let { run ->
            line("Latest run · ${runStatus(run)} · ${runTime(run)}", medium = true, colorAttr = runColor(run), topDp = 8)
        }
        app.applets.checkTemplateSoon(id)
        if (expanded) appletDetails(r, flowRuns)
        val bar = actions(8)
        if (app.applets.templateUpdate(r) != null) {
            val label = getString(R.string.applets_update_available)
            pill(bar, label, R.attr.bridgitPillStyle, cd(label, name)) { openUpdate(r) }
        }
        pill(bar, pauseLabel, R.attr.bridgitPillTextStyle, cd(pauseLabel, name)) {
            act { app.applets.setPaused(id, running) }
        }
        val detailsLabel = getString(if (expanded) R.string.applets_hide_details else R.string.applets_details)
        pill(bar, detailsLabel, R.attr.bridgitPillTextStyle, cd(detailsLabel, name)) {
            if (!expandedApplets.add(id)) expandedApplets.remove(id)
            manage(keepScroll = true)
        }.apply {
            androidx.core.view.ViewCompat.setStateDescription(this,
                getString(if (expanded) R.string.applets_details_expanded else R.string.applets_details_collapsed))
        }
        bar.addView(android.widget.ImageButton(this@AppletInstallActivity).apply {
            setImageResource(R.drawable.ic_overflow)
            imageTintList = android.content.res.ColorStateList.valueOf(themeColor(M.attr.colorOnSurfaceVariant))
            background = android.util.TypedValue().let { tv -> theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true); androidx.core.content.ContextCompat.getDrawable(context, tv.resourceId) }
            contentDescription = getString(R.string.applets_more_options_cd, name)
            setOnClickListener { v -> appletMenu(v, r) }
        }, android.view.ViewGroup.LayoutParams(dp(48), dp(48)))
        if (app.applets.hasCard(r)) getString(R.string.applets_open).let { label ->
            bar.alignLastChildToEnd = true
            pill(bar, label, R.attr.bridgitPillStyle, cd(label, name)) { openCard(r) }
        }
    }

    private fun OwnerScreen.Card.appletDetails(r: JSONObject, flowRuns: List<JSONObject>) {
        line(getString(R.string.applets_version, r.optString("version")), topDp = 12)
        for (name in AppletPackage.detachedMedia(r.getJSONObject("definition")).keys) {
            secondary(name) {
                lifecycleScope.launch {
                    val image = withContext(Dispatchers.IO) {
                        runCatching {
                            val file = app.applets.packageMedia.read(r, name)
                            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                            requireNotNull(android.graphics.BitmapFactory.decodeFile(file.path, options))
                        }
                    }
                    image.onSuccess { bitmap ->
                        val view = android.widget.ImageView(this@AppletInstallActivity).apply { setImageBitmap(bitmap); adjustViewBounds = true }
                        MaterialAlertDialogBuilder(this@AppletInstallActivity).setTitle(name).setView(view).setPositiveButton("Close", null).show()
                    }.onFailure { dialog(name, it.message ?: "Cannot open package image.") }
                }
            }
        }
        activitySection(r, flowRuns)
        budgetSection(r)
        scheduleSection(r)
        changesSection(r)
        portalSection(r)
    }

    private fun OwnerScreen.Card.appletIdentity(name: String, subtitle: String, icon: Int, running: Boolean = false) {
        view(LinearLayout(this@AppletInstallActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(android.widget.ImageView(context).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(themeColor(M.attr.colorPrimary))
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(themeColor(M.attr.colorPrimaryContainer))
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = name
                    setTextAppearance(M.style.TextAppearance_Material3_TitleMedium)
                    setTextColor(themeColor(M.attr.colorOnSurface))
                })
                addView(TextView(context).apply {
                    text = subtitle
                    setTextAppearance(cc.bridgit.app.R.style.TextAppearance_Bridgit_BodyMedium)
                    setTextColor(themeColor(if (running) R.attr.colorSuccess else M.attr.colorOnSurfaceVariant))
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }

    private fun modifyApplet(r: JSONObject) {
        cc.bridgit.app.ui.interactions.AppletAgentHandoff.open(this, app, r.getString("id")) {
            startActivity(Intent(this, cc.bridgit.app.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("owner_setup_target", "add_agent"))
        }
    }

    /** Opens the applet's portal (docs/spec-portals-and-cards.md): its page, full screen, the one place the applet is used. */
    private fun openCard(r: JSONObject) {
        app.applets.checkTemplateSoon(r.getString("id"))
        startActivity(cc.bridgit.app.ui.applets.AppletNavigation.openIntent(app, r))
    }

    private fun cd(action: String, name: String) = getString(R.string.applets_action_cd, action, name)

    /** The card's ⋮ menu: Data (when it keeps tables), Check for update (when it came from a link), then Remove in red. */
    private fun appletMenu(anchor: android.view.View, r: JSONObject) {
        val menu = androidx.appcompat.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 7, 0, R.string.modify_applet)
        menu.menu.add(0, 8, 0, R.string.applets_change_budget)
        if (hasTables(r)) menu.menu.add(0, 1, 0, R.string.applets_data)
        if (AppletEvolution.tracking(r) && (r.optJSONObject("source")?.optString("url")?.isNotEmpty() == true || r.optJSONObject("source")?.optString("kind") == "bundled")) menu.menu.add(0, 2, 1, R.string.applets_check_update)
        menu.menu.add(0, 6, 5, "Choose control agent")
        val views=runCatching { AppletPackage.cameraViewBindings(r) }.getOrDefault(emptyMap())
        if (views.isNotEmpty()) menu.menu.add(0,9,6,"Saved camera positions")
        val policy = AppletEvolution.state(r).optString("state")
        if (policy != "detached") {
            menu.menu.add(0, 4, 3, if (policy == "paused") "Resume template updates" else "Pause template updates")
            menu.menu.add(0, 5, 4, "Detach from template")
        }
        menu.menu.add(0, 3, 2, android.text.SpannableString(getString(R.string.applets_remove)).apply {
            setSpan(android.text.style.ForegroundColorSpan(themeColor(M.attr.colorError)), 0, length, 0)
        })
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(Intent(this, AppletDataActivity::class.java).putExtra(AppletDataActivity.EXTRA_INSTANCE, r.getString("id")))
                2 -> checkUpdate(r)
                3 -> confirmRemove(r)
                4 -> changeTemplatePolicy(r, if (policy == "paused") "tracking" else "paused")
                5 -> changeTemplatePolicy(r, "detached")
                6 -> chooseDeveloper(r)
                7 -> modifyApplet(r)
                8 -> openBudget(r)
                9 -> chooseCameraView(r,views)
            }
            true
        }
        menu.show()
    }

    private fun chooseCameraView(record: JSONObject, views: Map<String,String>) {
        val names=views.keys.sorted()
        MaterialAlertDialogBuilder(this).setTitle("Saved camera positions")
            .setItems(names.toTypedArray()) { _,index ->
                val name=names[index]
                val camera=views.getValue(name)
                val agent=record.getString("agent_id")
                val revision=app.cameraViews.status(agent,name).optString("revision").ifEmpty { null }
                fun allowed() {
                    val current=app.applets.get(record.getString("id"))
                    check(current != null && current.optString("agent_id")==agent && AppletPackage.cameraViewBindings(current)[name]==camera &&
                        app.agents.get(agent)?.allows("lan_camera")==true && app.settings.isOn("lan_camera")) {
                        "The applet or its camera access changed. Review the selection again."
                    }
                }
                val dialog=MaterialAlertDialogBuilder(this).setTitle(name)
                    .setMessage("Aim the selected camera ($camera), then save its current position for this applet.")
                    .setPositiveButton("Save position") { _,_ -> OwnerCheck.run(this,"Save camera position") { act {
                        app.cameraViews.savePosition(app,agent,name,camera,revision,::allowed)
                    } } }.setNegativeButton("Cancel",null)
                if (revision != null) dialog.setNeutralButton("Forget position") { _,_ -> OwnerCheck.run(this,"Forget camera position") { act {
                    allowed()
                    app.cameraViews.replace(agent,name,revision,null)
                } } }
                dialog.show()
            }.show()
    }

    /** What the applet has done lately, so it's visible that it runs on its own: one flat row per watch. */
    private fun deliveryStatus(agent: String, type: String, result: JSONObject?): String {
        val alert = type == "alert"
        val id = result?.optString(if (alert) "alert_id" else "delivery_id").orEmpty()
        val record = app.platform.store.find(id, agent, if (alert) "alert" else "outbox")
            ?: return "Delivery status unavailable"
        fun status(state: String) = when (state) {
            "acknowledged" -> "Acknowledged"
            "accepted", "delivered" -> "Received"
            "escalated" -> "Contacting next recipient"
            "queued" -> "Queued"
            "sending" -> "Sending"
            "expired" -> "Expired"
            "unknown_outcome" -> "Delivery unconfirmed"
            "cancelled" -> "Cancelled"
            "failed" -> "Delivery failed"
            else -> "Delivery status unavailable"
        }
        fun recipient(destination: String) =
            app.platform.store.find(destination, "owner", "destination")?.optString("name").orEmpty()
        fun named(name: String, state: String): String {
            val label = name.filterNot(Char::isISOControl).trim().take(80)
            return listOf(label, status(state)).filter { it.isNotBlank() }.joinToString(" · ")
        }
        val state = record.optString("status")
        if (!alert) return named(recipient(record.optString("destination_id")), state)
        if (state == "acknowledged") return named(record.optString("acknowledged_by"), state)
        val destinations = record.optJSONArray("destinations")
        if (record.optBoolean("from_workflow") && destinations != null) {
            val attempts = (0 until destinations.length()).mapNotNull { index ->
                val destination = destinations.getString(index)
                val outboxId = cc.bridgit.app.platform.DeliveryManager.workflowAlertDeliveryId(id, destination)
                app.platform.store.find(outboxId, agent, "outbox")?.let { named(recipient(destination), it.optString("status")) }
            }
            if (attempts.isNotEmpty()) return attempts.joinToString(" · ")
        }
        return status(state)
    }

    private fun runTime(run: JSONObject): String = android.text.format.DateUtils.formatDateTime(this,
        run.optLong("started_at", run.optLong("created_at")), android.text.format.DateUtils.FORMAT_SHOW_TIME or
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)

    private fun runStatus(run: JSONObject): String = when (run.optString("status")) {
        "completed" -> {
            val steps = run.optJSONArray("steps")
            if (steps != null && (0 until steps.length()).any { steps.optJSONObject(it)?.optString("status") == "failed" })
                "Completed with a failed step" else "Completed"
        }
        "unknown_outcome" -> "Result unconfirmed"
        "failed" -> if (run.optString("error") == "camera_unavailable") "Missed check · Camera unavailable" else "Failed"
        "cancelled" -> "Cancelled"
        "running", "dispatching" -> "In progress"
        else -> "Status unavailable"
    }

    private fun runColor(run: JSONObject) = when (run.optString("status")) {
        "completed" -> if (runStatus(run) == "Completed") R.attr.colorSuccess else R.attr.colorWarning
        "unknown_outcome" -> R.attr.colorWarning
        "failed" -> M.attr.colorError
        else -> M.attr.colorOnSurfaceVariant
    }

    private fun OwnerScreen.Card.activitySection(r: JSONObject, runs: List<JSONObject>) {
        section(getString(R.string.applets_activity))
        if (r.optString("native") == "dog") {
            line(getString(if (app.dog.state.value.running) R.string.applets_pet_running else R.string.applets_pet_stopped))
            secondary("Export local Dog Monitor history") {
                OwnerCheck.run(this@AppletInstallActivity, "Export observations, settings and latest photo") {
                    lifecycleScope.launch {
                        try {
                            val built = withContext(Dispatchers.IO) { AppletJsonExport.legacyDog(app, r.getString("id")) }
                            AppletExport.share(this@AppletInstallActivity, built)
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Exception) { failed("Couldn't export Dog Monitor history", e.message.orEmpty()) }
                    }
                }
            }
            return
        }
        if (r.optString("package_id") == "dog-monitor") {
            val id = r.getString("id")
            if (r.optBoolean("converted_from_dog") && app.remoteView.dogBackend) {
                secondary("Open existing Pet monitor gallery") {
                    val portal = cc.bridgit.app.ui.applets.AppletPortalsFragment.own(app,accountWide = false)
                        .firstOrNull { it.id == "dog" }
                    if (portal != null) startActivity(cc.bridgit.app.ui.applets.AppletNavigation.portalIntent(this@AppletInstallActivity, portal.id))
                }
            }
            secondary("Import local Dog Monitor history") {
                OwnerCheck.run(this@AppletInstallActivity, "Import private Dog Monitor history") {
                    archiveTarget = id
                    pickHistory.launch(arrayOf("application/json", "text/plain"))
                }
            }
            secondary("View imported episodes") {
                act {
                    val archive = withContext(Dispatchers.IO) { app.applets.dogHistoryArchive(id) }
                    if (archive == null) dialog("Imported history", "Choose an exported Dog Monitor history file first.")
                    else showDogEpisodes(archive.getJSONObject("tracker"))
                }
            }
        }
        val agentId = r.optString("agent_id")
        val ws = r.optJSONObject("watches") ?: JSONObject()
        val keys = ws.keys().asSequence().toList()
        val ids = keys + keys.map { ws.optJSONObject(it)?.optString("watch_id").orEmpty() }
        var rows = 0
        keys.forEachIndexed { i, k ->
            val w = app.watches.get(agentId, ws.optJSONObject(k)?.optString("watch_id").orEmpty())
            val label = words.watchLabel(w?.spec?.label ?: AppletLabels.definitionWatchLabel(r.optJSONObject("definition"), k), ids, i)
            val lines = if (w == null) listOf(getString(R.string.applets_watch_missing)) else {
                val last = (w.json(false).opt("last_check") as? JSONObject)?.optString("at")?.let { AppletBudget.parseTime(it) }
                listOf(words.checksLine(w.checks, last), words.tallyLine(w.todayLine(), w.matches))
            }
            flatRow(label, lines, divided = rows++ > 0)
        }
        if (r.has("schedule_digest")) {
            val paused = app.schedules.record(agentId)?.optBoolean("paused") == true
            flatRow(getString(R.string.applets_scheduled_actions), listOf(getString(if (paused) R.string.applets_paused else R.string.applets_on)), divided = rows++ > 0)
        }
        val flows = app.platform.store.list(agentId, "workflow", 1000)
        for (flow in flows) {
            val history = runs.filter { it.optString("workflow_id") == flow.optString("id") }
            val latest = history.firstOrNull()
            val name = flow.optJSONObject("definition")?.optString("name")?.takeIf { it.isNotBlank() } ?: "Flow"
            if (rows++ > 0) divider(12, 12)
            line(name, sp = 16, medium = true)
            if (latest == null) { line("No runs recorded"); continue }
            line("${runStatus(latest)} · ${runTime(latest)}", colorAttr = runColor(latest))
            line("Runs on ${cc.bridgit.app.platform.DeviceName.of(app)}")
            if (runStatus(latest) != "Completed") history.firstOrNull { runStatus(it) == "Completed" }?.let {
                line("Last completed · ${runTime(it)}")
            }
            val steps = latest.optJSONArray("steps") ?: continue
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("status") == "not_run") continue
                val type = step.optString("type")
                val result = step.optJSONObject("result")
                val camera = step.optString("camera").ifBlank { result?.optString("camera").orEmpty() }
                val label = when (type) {
                    "photo" -> when {
                        camera.startsWith("lan:") -> runCatching { app.lanCameras.get(camera).name }.getOrDefault("Network camera")
                        camera == "front" -> "Front camera"
                        camera == "rear" -> "Rear camera"
                        else -> "Camera"
                    }
                    "ask_model" -> "Model check"
                    "ask_owner" -> "Owner question"
                    else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
                }
                val detail = when (step.optString("status")) {
                    "skipped" -> step.optString("reason").takeIf { it.isNotBlank() }?.let { "Skipped · $it" } ?: "Skipped"
                    "unknown_outcome" -> "Could not confirm the result"
                    "failed" -> if (step.optString("error") == "camera_unavailable") "Camera unavailable" else "Failed"
                    "ok" -> when {
                        type == "alert" || type == "send" -> deliveryStatus(agentId, type, result)
                        result?.optBoolean("timed_out") == true -> "No answer in time"
                        result?.has("choice") == true -> result.optString("choice").take(100)
                        result?.optJSONObject("answers") != null -> result.getJSONObject("answers").let { answers ->
                            answers.keys().asSequence().filter { answers.opt(it) is String || answers.opt(it) is Boolean || answers.opt(it) is Number }
                                .take(3).joinToString(" · ") { "${it.replace('_', ' ')}: ${answers.opt(it).toString().take(80)}" }.ifBlank { "Completed" }
                        }
                        else -> "Completed"
                    }
                    else -> "In progress"
                }
                val color = when (step.optString("status")) {
                    "unknown_outcome" -> R.attr.colorWarning
                    "failed" -> M.attr.colorError
                    // A successful enqueue can still be awaiting delivery.
                    "ok" -> if (type == "alert" || type == "send") M.attr.colorOnSurface else R.attr.colorSuccess
                    else -> M.attr.colorOnSurfaceVariant
                }
                line("$label · $detail", colorAttr = color, topDp = 4)
            }
        }
        if (rows == 0) line(getString(R.string.applets_no_watches))
    }

    /**
     * The applet's budget as the last sync told it (all devices working for this applet): each limit's use, meter and
     * forecast, the overall state, and what agents may do. Before a sync: the configured limits and this device's spend.
     */
    private fun OwnerScreen.Card.budgetSection(r: JSONObject) {
        section(getString(R.string.applets_budget))
        line(getString(R.string.applets_budget_scope))
        val applet = AppletBudget.appletOf(r)
        val synced = AppletBudget.synced(app, applet)
        val limits = synced?.optJSONArray("limits")?.let { l -> (0 until l.length()).mapNotNull { l.optJSONObject(it) } }?.takeIf { it.isNotEmpty() }
        if (synced == null || limits == null) {
            line(getString(R.string.applets_waiting_totals), sp = 14, colorAttr = M.attr.colorOnSurface, topDp = 12)
            line(words.configuredLimits(AppletBudget.limitsOf(app, r)), topDp = 4)
            val st = AppletBudget.status(app, r)
            line(words.thisDevice(st.optDouble("spent_today_usd", 0.0), st.optDouble("spent_month_usd", 0.0), remoteViewDevice()), topDp = 4)
            if (st.optString("state") == "out") {
                val (title, detail, _) = words.status("out", AppletBudget.outUntil(app, applet).takeIf { it > 0 })
                line(title, medium = true, colorAttr = M.attr.colorError, topDp = 12)
                detail?.let { line(it, colorAttr = M.attr.colorOnSurface, topDp = 2) }
            }
            val mayChange = true
            words.agentLines(mayChange, AppletBudget.limitsOf(app, r).let { l -> words.byPeriod(l) { it.per } }.map { it.id to it.per }, null)
                .forEachIndexed { i, s -> line(s, colorAttr = M.attr.colorOnSurface, topDp = if (i == 0) 12 else 2) }
            return
        }
        val ordered = words.byPeriod(limits) { it.optString("per") }
        val headers = words.periodHeaders(ordered.map { it.optString("per") })
        ordered.forEachIndexed { i, l ->
            val b = words.limitBlock(headers[i], l)
            line(b.header, medium = true, colorAttr = M.attr.colorOnSurface, topDp = if (i == 0) 12 else 16)
            line(b.used, sp = 16, medium = true, colorAttr = M.attr.colorOnSurface, topDp = 4)
            b.topUp?.let { line(it, sp = 12) }
            meter(b.fraction, toneColor(b.tone, M.attr.colorPrimary), b.description)
            line(b.forecast, topDp = 4)
        }
        val resets = AppletBudget.outUntil(app, applet).takeIf { it > 0 }
            ?: ordered.mapNotNull { AppletBudget.parseTime(it.optString("resets_at")) }.filter { it > System.currentTimeMillis() }.minOrNull()
        val (title, detail, tone) = words.status(synced.optString("state").ifEmpty { null }, resets)
        line(title, medium = true, colorAttr = toneColor(tone, M.attr.colorOnSurfaceVariant), topDp = 16)
        detail?.let { line(it, colorAttr = M.attr.colorOnSurface, topDp = 2) }
        words.agentLines(synced.optBoolean("agents_may_change", true), ordered.map { it.optString("id") to it.optString("per") }, synced.optJSONObject("ceiling"))
            .forEachIndexed { i, s -> line(s, colorAttr = M.attr.colorOnSurface, topDp = if (i == 0) 12 else 2) }
        synced.optLong("synced_at", 0L).takeIf { it > 0 }?.let { line(words.lastUpdated(it), sp = 12, topDp = 8) }
    }

    private fun toneColor(t: AppletCardText.Tone, normal: Int) = when (t) {
        AppletCardText.Tone.SUCCESS -> R.attr.colorSuccess
        AppletCardText.Tone.WARNING -> R.attr.colorWarning
        AppletCardText.Tone.ERROR -> M.attr.colorError
        AppletCardText.Tone.QUIET -> M.attr.colorOnSurfaceVariant
        AppletCardText.Tone.NORMAL -> normal
    }

    /** The actions and how often they run (read-only in phase 1; agents and the owner change them through the API). */
    private fun OwnerScreen.Card.scheduleSection(r: JSONObject) {
        section(getString(R.string.applets_schedule))
        val synced = AppletBudget.synced(app, AppletBudget.appletOf(r))
        val local = runCatching { app.budgetSync.actionsOf(r) }.getOrDefault(emptyList())
        // Runs only when the owner uses it: never the schedule an older sync still holds.
        if (r.optString("native") != "dog" && local.isEmpty()) { line(getString(R.string.applets_on_demand)); return }
        val actions = synced?.optJSONArray("actions")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }?.takeIf { it.isNotEmpty() }
            ?: local.map { it.json() }
        if (actions.isEmpty()) { line(getString(R.string.applets_no_actions)); return }
        val labels = AppletLabels.actionLabels(app, r, words, actions.map { it.optString("action") })
        val limits = synced?.optJSONArray("limits")?.let { l -> (0 until l.length()).mapNotNull { l.optJSONObject(it) } }?.map { it.optString("id") to it.optString("per") }
            ?: AppletBudget.limitsOf(app, r).map { it.id to it.per }
        val paidApplet = runCatching { paidApplet(r) }.getOrDefault(true)
        actions.forEachIndexed { i, a ->
            val id = a.optString("action")
            val projected = a.optJSONObject("projected_usd")
            val noCost = projected == null || projected.keys().asSequence().all { (projected.optDouble(it, 0.0)) == 0.0 }
            val free = !paidApplet || (noCost && freeAction(r, local.firstOrNull { it.id == id }))
            val lines = mutableListOf(words.every(a.optInt("every_s")), words.allowedLine(a.optInt("min_s"), a.optInt("max_s"), projected, limits, free))
            a.optInt("places", 0).takeIf { it > 1 }?.let { lines += words.places(it) }
            val label = labels[id] ?: getString(R.string.applets_scheduled_action_n, i + 1)
            // One action named like the applet: its name would only repeat the card's title (design pass).
            if (actions.size == 1 && label == r.optString("name")) lines.forEachIndexed { j, l -> line(l, topDp = if (j == 0) 0 else 2) }
            else flatRow(label, lines, divided = i > 0)
        }
    }

    /** Whether this applet can make paid calls at all (the Pet monitor always can). */
    private fun paidApplet(r: JSONObject): Boolean = AppletBudget.appletOf(r) == AppletBudget.DOG ||
        AppletPackage.paid(AppletPackage.compile(AppletPackage.check(r.getJSONObject("definition")), r.getJSONObject("config")), AppletPackage.check(r.getJSONObject("definition")).declared)

    /** An action whose every watch answers on the device (model none): it never costs anything. */
    private fun freeAction(r: JSONObject, a: AppletActions.Action?): Boolean {
        if (a == null || a.watches.isEmpty()) return false
        val ws = r.optJSONObject("watches") ?: return false
        return a.watches.all { pkg -> ws.optJSONObject(pkg)?.optString("watch_id")?.let { app.watches.get(r.optString("agent_id"), it) }?.spec?.model == "none" }
    }

    /** The last three budget changes, newest first, in plain words (action and limit ids put in words). */
    private fun OwnerScreen.Card.changesSection(r: JSONObject) {
        val applet = AppletBudget.appletOf(r)
        val changes = app.budgetSync.recentChanges(applet).takeLast(3).reversed()
        if (changes.isEmpty()) return
        section(getString(R.string.applets_recent_changes))
        val synced = AppletBudget.synced(app, applet)
        val actionIds = synced?.optJSONArray("actions")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("action") } }?.takeIf { it.isNotEmpty() }
            ?: runCatching { app.budgetSync.actionsOf(r).map { it.id } }.getOrDefault(emptyList())
        val actionLabels = AppletLabels.actionLabels(app, r, words, actionIds)
        val limits = AppletBudget.limitsOf(app, r)
        val limitLabels = words.byPeriod(limits) { it.per }.let { o -> o.map { it.id }.zip(words.fieldLabels(o.map { it.per }, ceiling = false).map { it.lowercase() }) }.toMap()
        changes.forEachIndexed { i, c ->
            val at = AppletBudget.parseTime(c.optString("at"))
            val who = words.actor(c.optString("by_kind").ifEmpty { null }, c.optString("by_name").ifEmpty { null })
            line(if (at != null) words.dot(who, words.dateTime(at)) else who, colorAttr = M.attr.colorOnSurface, topDp = if (i == 0) 0 else 12)
            line(words.changeSummary(c.optString("summary"), actionLabels, limitLabels), topDp = 2)
        }
    }

    private fun hasTables(r: JSONObject) = r.optJSONObject("definition")?.optJSONObject("processing")?.optJSONArray("tables")?.let { it.length() > 0 } == true

    /** The applet's own tab on the owner's web portal (bridgit.cc): off until the owner turns it on here. */
    private fun OwnerScreen.Card.portalSection(r: JSONObject) {
        section(getString(R.string.applets_portal))
        val recordsKind = if (r.optString("native") == "dog") null else cc.bridgit.app.remoteview.PortalRecords.kindOf(r.optJSONObject("definition"))
        val def = r.optJSONObject("definition")
        val tables = r.optString("native") != "dog" && cc.bridgit.app.remoteview.PortalTables.tablesOf(def).isNotEmpty()
        val always = r.optString("native") != "dog" && cc.bridgit.app.remoteview.PortalRecords.always(def)
        if (r.optString("native") == "dog" || (!tables && recordsKind == null && app.watches.list(r.optString("agent_id")).none { it.spec.tally != null })) {
            line(getString(R.string.applets_portal_unavailable), colorAttr = M.attr.colorOnSurface)
            return
        }
        val pkg = r.optString("package_id")
        val st = app.remoteView.packages.status(pkg)
        val on = st.optBoolean("personal_backend")
        val name = r.optString("name")
        // An applet whose card sends results (e.g. each workout): numbers and words only, never photos.
        val title = r.optJSONObject("definition")?.optJSONObject("ui")?.optJSONObject("portal")?.optJSONObject("records")?.optString("title")?.ifBlank { null }
            ?: getString(R.string.applets_portal_results)
        line(getString(when {
            on && app.remoteView.portalLinked() -> R.string.applets_portal_on
            on -> R.string.applets_portal_waiting
            st.optBoolean("backend_deletion_pending") -> R.string.applets_portal_deleting
            else -> R.string.applets_portal_off
        }), colorAttr = M.attr.colorOnSurface)
        if (on && recordsKind == null && !tables && st.optBoolean("photo_sharing")) line(getString(R.string.applets_portal_photo), topDp = 2)
        if (tables) line(getString(R.string.applets_portal_interface), topDp = 2)
        // Part of the applet: no Stop (removing the applet stops it); Show stays, to turn it back on if it went off.
        if (on) secondary(getString(R.string.link_web_view_open)) {
            startActivity(cc.bridgit.app.ui.applets.AppletNavigation.portalIntent(this@AppletInstallActivity, "pkg.$pkg"))
        }
        if (always && on) { line(getString(R.string.applets_portal_always), topDp = 2); return }
        val bar = actions(topDp = 8)
        val label = getString(if (on) R.string.applets_portal_stop else R.string.applets_portal_show)
        pill(bar, label, R.attr.bridgitPillTextStyle, cd(label, name)) {
            if (on) MaterialAlertDialogBuilder(this@AppletInstallActivity).setTitle(getString(R.string.applets_portal_stop_title, name))
                .setMessage(if (tables) getString(R.string.applets_portal_stop_tables) else if (recordsKind != null) getString(R.string.applets_portal_stop_records, title.lowercase()) else getString(R.string.applets_portal_stop_counts))
                .setPositiveButton(R.string.applets_portal_stop_confirm) { _, _ -> act { app.remoteView.packages.configure(pkg, false, null); app.remoteView.syncPackagesSoon() } }
                .setNegativeButton(R.string.applets_cancel, null).show()
            else MaterialAlertDialogBuilder(this@AppletInstallActivity).setTitle(getString(R.string.applets_portal_show_title, name))
                .setMessage(getString(if (tables) R.string.applets_portal_show_tables else if (recordsKind != null) R.string.applets_portal_show_records else R.string.applets_portal_show_counts))
                .setPositiveButton(R.string.applets_portal_show_confirm) { _, _ -> act { app.remoteView.packages.configure(pkg, true, recordsKind == null); app.remoteView.syncPackagesSoon() } }
                .setNegativeButton(R.string.applets_cancel, null).show()
        }
    }

    /** The full-screen budget editor; its result ("Budget saved.", saved offline) shows here as a snackbar. */
    private val budgetEditor = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringExtra(BudgetEditorActivity.EXTRA_MESSAGE)?.let { msg ->
            screen.scroll.post { com.google.android.material.snackbar.Snackbar.make(screen.scroll, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() }
        }
    }

    private fun openBudget(r: JSONObject) {
        budgetEditor.launch(Intent(this, BudgetEditorActivity::class.java).putExtra(BudgetEditorActivity.EXTRA_INSTANCE, r.getString("id")))
    }

    private fun act(block: suspend () -> Any) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val r = runCatching { block() }
            busy = false
            r.exceptionOrNull()?.let { dialog(getString(R.string.applets_needs_attention), it.message ?: getString(R.string.applets_couldnt)) }
            manage()
        }
    }

    private fun confirmRemove(r: JSONObject) {
        MaterialAlertDialogBuilder(this).setTitle(getString(R.string.applets_remove_title, r.optString("name")))
            .setMessage(R.string.applets_remove_body)
            .setNegativeButton(R.string.applets_cancel, null)
            .setPositiveButton(R.string.applets_remove) { _, _ ->
                act {
                    val out = app.applets.remove(r.getString("id"))
                    val left = out.getJSONArray("left_behind")
                    if (left.length() > 0) withContext(Dispatchers.Main) { dialog(getString(R.string.applets_removed_leftovers_title), getString(R.string.applets_removed_leftovers, AppletPackage.strings(left).joinToString())) }
                }
            }.show()
    }

    private fun chooseDeveloper(r: JSONObject) {
        val agents=app.agents.all().filter { it.connection != cc.bridgit.app.core.Connection.APPLET && it.allows("applet_install") }
        if (agents.isEmpty()) return dialog("No eligible control agent", "Add a control agent and allow Install applets first.")
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Choose a control agent")
            .setItems(agents.map { it.name }.toTypedArray()) { _, which ->
                val chosen=agents[which]
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Let ${chosen.name} develop this applet?")
                    .setMessage("This control agent can read the applet's source, settings, customization history and tables shared with its developer, and propose changes. It replaces the previous developer. Other permissions stay unchanged.")
                    .setNegativeButton("Cancel",null).setPositiveButton("Assign control agent") { _, _ ->
                        OwnerCheck.run(this,"Assign ${chosen.name}") { act { app.applets.assignDeveloper(r.getString("id"),chosen.id,r.optLong("revision")) } }
                    }.show()
            }.setNegativeButton("Cancel",null).show()
    }

    private fun changeTemplatePolicy(r: JSONObject, state: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (state == "detached") "Make this your own applet?" else if (state == "paused") "Pause template updates?" else "Resume template updates?")
            .setMessage(if (state == "detached") "This applet keeps working with all its settings and data. It stops checking or accepting updates from its original template. Your control agent can keep changing it. Reconnecting requires a separate compatibility review."
                else "This changes only template syncing. Your applet keeps running with its current settings and data.")
            .setNegativeButton("Cancel",null).setPositiveButton(if(state == "detached") "Detach" else "Confirm") { _, _ ->
                act { app.applets.setTemplatePolicy(r.getString("id"),state,r.optLong("revision"),"Owner selected $state on this device") }
            }.show()
    }

    private fun openUpdate(r: JSONObject) {
        val current = app.applets.get(r.getString("id")) ?: return manage()
        val update = app.applets.templateUpdate(current)
        when (update?.optString("status")) {
            "awaiting_owner" -> show(update)
            "needs_agent", "needs_verification" -> modifyApplet(current)
            else -> checkUpdate(current)
        }
    }

    private fun checkUpdate(r: JSONObject) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) { runCatching { app.applets.checkTemplate(r.getString("id")) } }
            busy=false
            result.fold({ update ->
                when (update.optString("status")) {
                    "awaiting_owner" -> show(update)
                    "up_to_date" -> dialog("No template update", "No newer template was found.")
                    else -> { manage(); app.applets.get(r.getString("id"))?.let { modifyApplet(it) } }
                }
            }, { dialog("Couldn't check for an update",it.message ?: "") })
        }
    }

    companion object {
        const val EXTRA_UPDATE = "applet_review_update"
        const val EXTRA_PENDING = "applet_pending_id"
        /** Compatibility extra for historical starter intents. */
        const val EXTRA_BUNDLED = "applet_bundled_id"
        /** Opens management for this instance only; never defaults to a different applet. */
        const val EXTRA_INSTANCE = "applet_instance_id"
        /** The blank recipes use for seconds between checks; the over-the-limit dialog offers a longer one. */
        private const val INTERVAL = "interval_s"
    }
}
