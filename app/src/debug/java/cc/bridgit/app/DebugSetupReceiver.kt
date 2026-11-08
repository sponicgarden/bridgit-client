package cc.bridgit.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import cc.bridgit.app.core.Connection

/**
 * Debug-only test hook (TST-02). Only the ADB shell holds DUMP, so only ADB can send it.
 *   am broadcast -a cc.bridgit.app.debug.SETUP --es agent Tester --ez camera true ...
 * Extras: camera/mic/gallery (bool switches), agent (add + open a pairing window),
 * rotate (agent name: new key + pairing window), remove (agent name), developer / fixer / super_powers ("name=true|false"; developer sets both), revoke_super_powers (bool), adb_connect (bool), adb_pair_code (6 digits), log_visibility (all|own),
 * listener (bool, default true), wake_word ("<agent name>:<phrase>[:clip]" adds a wake word), wake_clear (removes them all),
 * card_html ("<agent name>|<base64 page>" sends an html card from that agent), card_status (interaction id: logs
 * its status and answer under the tag BridgitDebug), jev_test (agent name, or true for the owner: asks Jev through the team backend
 * with two conditional actions, runs the fired ones as that agent, logs "jev_test ok ..." or "jev_test failed ..." under BridgitDebug).
 * email_grace_hours_ago (int: moves the 24-hour email clock back, 0 clears it), email_state (none|pending|confirmed, with
 * email=<address> to set one; confirmed lets long test runs skip email verification).
 * software_manifest_url (test manifest URL on Bridgit's download host, or "default").
 * device_tools ("workflows,decisions": Device tools features on; "-id" turns one off).
 * Result data is the pairing code for a new agent (never the key); the test
 * redeems it with POST /v1/pair, the same way an agent would.
 */
class DebugSetupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BridgeApp
        if (intent.hasExtra("port")) {
            app.settings.port = intent.getIntExtra("port", BuildConfig.DEFAULT_PORT).also { require(it in 1024..65535) }
            BridgeService.running?.poke("test port changed")
        }
        intent.getStringExtra("speaker_ceiling")?.toIntOrNull()?.let { app.settings.speakerCeiling = it }
        for (m in app.registry.switchable()) {
            val k = m.switchKey!!
            if (intent.hasExtra(k)) app.settings.setOn(k, intent.getBooleanExtra(k, false))
        }
        // device_tools "workflows,decisions": turns those Device tools features on (prefix one with - to turn it off).
        intent.getStringExtra("device_tools")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { t ->
            runCatching { cc.bridgit.app.platform.Capabilities.setEnabled(app, t.removePrefix("-"), !t.startsWith("-")) }
                .onFailure { android.util.Log.w("BridgitDebug", "device_tools $t: ${it.message}") }
        }
        intent.getStringExtra("log_visibility")?.let { app.settings.logVisibility = it }
        // software_manifest_url=<https://<download host>/bridget/...json> reads a test manifest (channel test); "default" goes back.
        intent.getStringExtra("software_manifest_url")?.let { u ->
            runCatching { app.onDeviceVision.models.setManifestUrl(u.takeIf { it != "default" }) }
                .onFailure { android.util.Log.w("BridgitDebug", "software_manifest_url refused: ${it.message}") }
        }
        // Owner email verification (alerts.EmailGate) without waiting a day or clicking a real link.
        intent.getStringExtra("email_grace_hours_ago")?.toLongOrNull()?.let { h ->
            app.settings.emailGraceStartedAt = if (h <= 0) 0L else System.currentTimeMillis() - h * 3_600_000L
        }
        intent.getStringExtra("email")?.let { app.settings.alertEmail = it.ifBlank { null } }
        intent.getStringExtra("email_state")?.let { st ->
            if (st == "confirmed") { if (app.settings.alertEmail == null) app.settings.alertEmail = "test@bridgit.cc"; app.settings.alertState = "pending"; app.alerts.markConfirmed() }
            else { app.settings.alertState = st; app.settings.emailVerifiedAt = 0L }
        }
        if (intent.hasExtra("email_grace_hours_ago") || intent.hasExtra("email_state"))
            android.util.Log.i("BridgitDebug", "email_gate ${app.alerts.gate()}")
        // answer=<id>:<button> and dismiss=<id> stand in for the owner's tap in tests.
        intent.getStringExtra("answer")?.let { spec ->
            val (id, button) = spec.split(":", limit = 2).let { it[0].toLong() to it.getOrNull(1) }
            runCatching { app.interactions.answer(id, button, emptyMap()) }
        }
        intent.getStringExtra("dismiss")?.let { runCatching { app.interactions.dismiss(it.toLong()) } }
        // card_html=<agent name>|<base64 HTML> posts an html card the way POST /v1/interactions would (no tailnet needed).
        intent.getStringExtra("card_html")?.let { spec ->
            runCatching {
                val (name, b64) = spec.split("|", limit = 2).let { it[0] to it[1] }
                val a = requireNotNull(app.agents.byName(name)) { "no agent called $name" }
                val html = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
                val d = cc.bridgit.app.interact.InteractionRules.parse(org.json.JSONObject().put("kind", "html").put("title", "Debug card").put("html", html))
                app.interactions.create(a.id, a.name, d, null)
            }.onSuccess { android.util.Log.i("BridgitDebug", "card_html created ${it.id}") }
                .onFailure { android.util.Log.w("BridgitDebug", "card_html failed: $it") }
        }
        intent.getStringExtra("card_status")?.let { id ->
            val i = app.interactions.get(id.toLong())
            android.util.Log.i("BridgitDebug", "card_status $id ${i?.status} ${i?.answer}")
        }
        // appearance=light|dark|system: this build's own theme, so a screenshot needn't change the shared device's dark mode.
        intent.getStringExtra("appearance")?.let { app.settings.appearance = it; android.os.Handler(android.os.Looper.getMainLooper()).post { app.applyAppearance() } }
        // linked_sheet=viewer|viewer_live|satellite|satellite_off|hub|hub_stale|network|network_off|this opens Linked
        // devices with a sample device dialog (the app must be in front).
        intent.getStringExtra("linked_sheet")?.let { k ->
            context.startActivity(Intent(context, cc.bridgit.app.portal.PortalActivity::class.java)
                .putExtra(cc.bridgit.app.portal.PortalActivity.EXTRA_SAMPLE, k).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        // Satellites (docs/spec-satellites.md): satellite_make=<name> logs a pairing link ("satellite_link …", one-time
        // claim code, 15 min); satellite_add=<link> claims it on the hub; satellite_grant=<sat id>:<agent name>[:false];
        // satellite_refresh=<sat id>; satellite_list logs the hub's satellites; satellite_unlink_all removes this phone's hub links.
        intent.getStringExtra("satellite_make")?.let { n ->
            runCatching { app.hubLinks.make(n) }.onSuccess { android.util.Log.i("BridgitDebug", "satellite_link $it") }
                .onFailure { android.util.Log.w("BridgitDebug", "satellite_make failed: $it") }
        }
        intent.getStringExtra("satellite_add")?.let { link ->
            Thread {
                runCatching { cc.bridgit.app.satellite.SatelliteHub.add(app, link) }
                    .onSuccess { android.util.Log.i("BridgitDebug", "satellite_add ok ${it.id} ${it.name} seen=${it.lastSeen}") }
                    .onFailure { android.util.Log.w("BridgitDebug", "satellite_add failed: $it") }
            }.start()
        }
        intent.getStringExtra("satellite_grant")?.let { spec ->
            val p = spec.split(":")
            val sat = "${p[0]}:${p[1]}"; val a = app.agents.byName(p.getOrElse(2) { "" })
            if (a == null) android.util.Log.w("BridgitDebug", "satellite_grant: no agent ${p.getOrNull(2)}")
            else android.util.Log.i("BridgitDebug", "satellite_grant ${app.satellites.setAgent(sat, a.id, p.getOrNull(3) != "false")?.agents}")
        }
        intent.getStringExtra("satellite_refresh")?.let { id ->
            Thread { android.util.Log.i("BridgitDebug", "satellite_refresh ${cc.bridgit.app.satellite.SatelliteHub.refresh(app, id)?.let { "${it.id} err=${it.lastError} seen=${it.lastSeen}" }}") }.start()
        }
        if (intent.hasExtra("satellite_list")) app.satellites.all().forEach {
            android.util.Log.i("BridgitDebug", "satellite ${it.id} ${it.name} ${it.host}:${it.port} err=${it.lastError} seen=${it.lastSeen} agents=${it.agents}")
        }
        if (intent.hasExtra("satellite_unlink_all")) app.hubLinks.ids().forEach { app.hubLinks.unlink(it) }
        // satellite_remove=<sat id> removes a satellite on the hub (and tells it); hub_list logs this phone's hub links;
        // hub_check asks each hub now (a hub that forgot this phone drops its link).
        // appearance=light|dark|system: this app's theme, without changing the shared device's dark mode.
        intent.getStringExtra("appearance")?.let { v -> if (v in setOf("light", "dark", "system")) { app.settings.appearance = v; app.applyAppearance() } }
        // portal_first_call=latest|<agent id>[|<client name>]: acts as a portal claiming that pairing's link and making its
        // first call (the newest with "latest"), as if the iPhone had scanned its code: the sheet turns Connected.
        intent.getStringExtra("portal_first_call")?.let { spec ->
            val (which, client) = spec.split('|', limit = 2).let { it[0] to it.getOrNull(1) }
            val portals = cc.bridgit.app.portal.PortalPairing.paired(app, cc.bridgit.app.relay.RelayStore(app))
            val a = if (which == "latest") portals.maxByOrNull { it.createdAt } else portals.firstOrNull { it.id == which }
            if (a == null) android.util.Log.w("BridgitDebug", "portal_first_call: no such pairing $which")
            else {
                // Like the portal app: claim the single-use link with a token of its own first, then read.
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(java.util.UUID.randomUUID().toString().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                cc.bridgit.app.relay.RelayStore(app).claimPortal(a.id, hash)
                client?.let { cc.bridgit.app.portal.PortalModule.noteClient(a.id, it) }
                app.agents.recordUse(a.id, "relay:debug")
                android.util.Log.i("BridgitDebug", "portal_first_call ${a.id} ${a.name}")
            }
        }
        intent.getStringExtra("satellite_remove")?.let { id -> cc.bridgit.app.satellite.SatelliteHub.remove(app, id) }
        // Linked Bridgits: link_resolve=<name>|<code> asks the names backend for that name's address with a proof of the
        // code (spec-linked-bridgits §14.4) and logs "link_resolve ok <host>:<port>" or "link_resolve failed <error>".
        intent.getStringExtra("link_resolve")?.let { spec ->
            val (n, c) = spec.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            Thread {
                runCatching {
                    val own = cc.bridgit.app.feedback.InstallIdentity.status(app)?.installId ?: error("no install id")
                    val code = cc.bridgit.app.link.LinkCode.normalise(c) ?: error("not a code")
                    cc.bridgit.app.link.LinkResolver.resolve(app, n, cc.bridgit.app.link.LinkCode.keys(code, n).proof(n, own))
                }.onSuccess { android.util.Log.i("BridgitDebug", "link_resolve ok ${it.first}:${it.second}") }
                    .onFailure { android.util.Log.w("BridgitDebug", "link_resolve failed ${(it as? cc.bridgit.app.api.ApiError)?.code ?: it.javaClass.simpleName} ${it.message}") }
            }.start()
        }
        if (intent.hasExtra("hub_list")) app.hubLinks.agents().forEach { a ->
            val info = app.hubLinks.hubs().firstOrNull { it.linkId == a.id }
            android.util.Log.i("BridgitDebug", "hub_link ${a.id} ${a.name} info=${info?.name}@${info?.host}:${info?.port}")
        }.also { android.util.Log.i("BridgitDebug", "hub_list count=${app.hubLinks.ids().size}") }
        if (intent.hasExtra("hub_check")) Thread { app.hubLinks.refreshFromHubs(); android.util.Log.i("BridgitDebug", "hub_check count=${app.hubLinks.ids().size}") }.start()
        // tour_start=<agent name>:<kind,kind>:<interval s>, tour_ask=<text>, tour_end=1 stand in for the City tour screen.
        intent.getStringExtra("tour_start")?.let { spec ->
            val p = spec.split(":")
            app.agents.byName(p[0])?.let { a -> runCatching { app.tours.start(a.id, p.getOrElse(1) { "history" }.split(","), p.getOrElse(2) { "60" }.toInt()) } }
        }
        if (intent.hasExtra("tour_end")) app.tours.end("ended from the test hook")
        // dog_fake=<dir> feeds the Pet monitor the .jpg files in that folder (in name order, round and round)
        // instead of the camera; dog_fake=off goes back to the camera. dog_start / dog_stop / dog_clear stand in
        // for the screen's buttons. dog_status logs the latest reading and today's totals under BridgitDebug.
        intent.getStringExtra("dog_fake")?.let { dir ->
            app.dog.fakePhotos = if (dir == "off") null else java.io.File(dir).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name }
            android.util.Log.i("BridgitDebug", "dog_fake ${app.dog.fakePhotos?.map { it.name }}")
        }
        if (intent.hasExtra("dog_clear")) app.dog.clearHistory()
        // dog_camera=auto|rear|front picks the camera like the screen's Auto/Rear/Front; dog_camera=find is "Find the best camera".
        intent.getStringExtra("dog_camera")?.let { if (it == "find") app.dog.findBestCamera() else app.dog.setCameraMode(it) }
        // dog_backend_test=true marks Pet monitor's personal backend as on without the Google link (test builds only),
        // so its Clef checks run. dog_mic=on|off ticks the built-in microphone (barking detection).
        if (intent.hasExtra("dog_backend_test")) app.getSharedPreferences("remote_view", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("backend_dog_chosen", true).putBoolean("backend_dog", intent.getBooleanExtra("dog_backend_test", true)).commit()
        intent.getStringExtra("dog_mic")?.let { v ->
            if (v == "on") app.settings.setOn("mic", true)
            val mics = app.dog.state.value.microphones
            app.dog.setMicrophones(if (v == "on") (mics + cc.bridgit.app.dog.DogAudio.BUILTIN).distinct() else mics - cc.bridgit.app.dog.DogAudio.BUILTIN)
        }
        // people_jpeg=<path to a JPEG readable by the app> runs the People face pipeline (faces, crops, embedding when the
        // model is ready) on it as a one-photo burst of the first people watch (people_watch=<watch id> picks one),
        // bypassing the battery gate and hourly cap, and queues the sightings for the applet's portal push.
        // Logs "people_jpeg ..." under BridgitDebug.
        intent.getStringExtra("people_jpeg")?.let { path ->
            val wid = intent.getStringExtra("people_watch")
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val w = app.watches.peopleWatches().firstOrNull { wid == null || it.id == wid }
                    if (w == null) android.util.Log.i("BridgitDebug", "people_jpeg no people watch${wid?.let { " $it" } ?: ""}")
                    else android.util.Log.i("BridgitDebug", "people_jpeg " + app.watches.people.runFile(w, java.io.File(path).readBytes()))
                } catch (e: Exception) { android.util.Log.i("BridgitDebug", "people_jpeg failed: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
        // owner_photo=<path to a JPEG> sends it as an owner photo (the Camera tab's path) to the photo watches of
        // owner_targets=<agent id,...> (default all), owner_camera=rear|front; logs "owner_photo <result json>" under BridgitDebug.
        intent.getStringExtra("owner_photo")?.let { path ->
            val ids = intent.getStringExtra("owner_targets")?.split(',')?.filter { it.isNotBlank() }
            val cam = intent.getStringExtra("owner_camera") ?: "rear"
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    android.util.Log.i("BridgitDebug", "owner_photo targets " + app.ownerPhotos.targetsJson())
                    android.util.Log.i("BridgitDebug", "owner_photo " + app.ownerPhotos.submit(java.io.File(path).readBytes(), cam, ids, "debug"))
                } catch (e: Exception) { android.util.Log.i("BridgitDebug", "owner_photo failed: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
        // people_compare=<path>,<path>,... runs faces + embeddings on each JPEG (nothing queued) and logs the cosine of
        // every pair of first faces, to check the on-phone face model ("people_compare ..." under BridgitDebug).
        intent.getStringExtra("people_compare")?.let { paths ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val w = app.watches.peopleWatches().firstOrNull() ?: run { android.util.Log.i("BridgitDebug", "people_compare no people watch"); return@launch }
                    val o = w.spec.people!!
                    app.watches.people.embedder.ensure()
                    val names = paths.split(",")
                    val embs = names.map { p ->
                        val r = app.watches.people.analyse(listOf(java.io.File(p).readBytes() to System.currentTimeMillis()), o, w.agentId, w.id, "debug")
                        r.burst?.sightings?.firstOrNull()?.embedding
                    }
                    val out = StringBuilder("people_compare embedder=${app.watches.people.embedder.state}")
                    for (i in names.indices) for (j in i + 1 until names.size) {
                        val a = embs[i]; val b = embs[j]
                        val c = if (a == null || b == null) null else a.indices.sumOf { (a[it] * b[it]).toDouble() }
                        out.append(" | ${java.io.File(names[i]).name}~${java.io.File(names[j]).name}=${c?.let { "%.3f".format(it) } ?: "none"}")
                    }
                    android.util.Log.i("BridgitDebug", out.toString())
                } catch (e: Exception) { android.util.Log.i("BridgitDebug", "people_compare failed: ${e.javaClass.simpleName}: ${e.message}") }
            }
        }
        // loud_wav=<path to a 16 kHz mono WAV readable by the app> hands that clip to every loud-sound subscriber,
        // as if the microphone had just heard it (sound watches, Pet monitor barking).
        intent.getStringExtra("loud_wav")?.let { path ->
            val wav = java.io.File(path).readBytes()
            val ms = ((wav.size - 44).coerceAtLeast(0) / 32L)
            val subs = app.wake.loud.all()
            android.util.Log.i("BridgitDebug", "loud_wav ${wav.size} bytes (${ms} ms) to ${subs.map { it.id }}")
            subs.filter { cc.bridgit.app.sound.LoudSounds.BUILTIN in it.sources }.forEach { it.onSound(cc.bridgit.app.sound.LoudSounds.BUILTIN, wav, ms, System.currentTimeMillis()) }
        }
        // lanmic_enroll=<path to a JSON file {id,name,host,username,password}> enrolls that camera, turns the
        // lan_camera and lan_camera_audio switches on and gives the Pet monitor the camera. The file is deleted after
        // (it holds the camera's password; push it to the app's own files folder).
        intent.getStringExtra("lanmic_enroll")?.let { path ->
            val f = java.io.File(path)
            runCatching {
                val o = org.json.JSONObject(f.readText())
                val cam = cc.bridgit.app.lancamera.LanCamera(o.getString("id"), o.getString("name"), o.getString("host"), o.getString("username"), o.getString("password"))
                app.lanCameras.save(cam)
                app.settings.setOn("lan_camera", true); app.settings.setOn("lan_camera_audio", true)
                app.appDevices.setApps(cam.id, app.appDevices.appsFor(cam.id) + cc.bridgit.app.appdevices.AppDeviceAccess.DOG)
                android.util.Log.i("BridgitDebug", "lanmic_enroll ${cam.id} ${cam.host}")
            }.onFailure { android.util.Log.i("BridgitDebug", "lanmic_enroll failed: ${it.javaClass.simpleName} ${it.message}") }
            f.delete()
        }
        // dog_lanmic=<lan:id>:on|off ticks that camera's microphone in the Pet monitor.
        intent.getStringExtra("dog_lanmic")?.let { v ->
            val id = v.substringBeforeLast(':'); val on = v.endsWith(":on")
            val mics = app.dog.state.value.microphones
            runCatching { app.dog.setMicrophones(if (on) (mics + id).distinct() else mics - id) }
                .onFailure { android.util.Log.i("BridgitDebug", "dog_lanmic failed: ${it.message}") }
        }
        // lanmic_wav=<lan:id>|<path to a 16 kHz mono WAV> feeds that clip through the camera stream's loudness
        // detectors, as if the camera's microphone had just sent it (CameraMics; the same path a real bark takes).
        intent.getStringExtra("lanmic_wav")?.let { v ->
            val parts = v.split('|', limit = 2)
            if (parts.size != 2) { android.util.Log.i("BridgitDebug", "lanmic_wav wants <lan:id>|<path>"); return@let }
            val (id, path) = parts
            val wav = java.io.File(path).readBytes()
            val frames = app.cameraMics.inject(id, wav.copyOfRange(minOf(44, wav.size), wav.size))
            android.util.Log.i("BridgitDebug", "lanmic_wav $id $frames frames")
        }
        intent.getStringExtra("lanmic_status")?.let { id -> android.util.Log.i("BridgitDebug", "lanmic_status $id ${app.cameraMics.status(id)}") }
        // dog_ptz=<lan:id> runs the portal's PTZ search on that camera (ring views, then centring) as if the portal
        // had asked. Each step and result is logged under BridgitDebug; photos go to files/dog_ptz/<view>.jpg with
        // the pet box in <view>.json. Needs dog_backend_test first.
        intent.getStringExtra("dog_ptz")?.let { camera ->
            val dir = java.io.File(app.filesDir, "dog_ptz").apply { deleteRecursively(); mkdirs() }
            val keys = cc.bridgit.app.lancamera.PtzRing.VIEWS.map { "$camera|$it" } + "$camera|${cc.bridgit.app.lancamera.PtzRing.CENTERED}"
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val started = System.currentTimeMillis()
                runCatching {
                    app.dog.portalPhotos(keys, authorized = {}, progress = { key, step -> android.util.Log.i("BridgitDebug", "dog_ptz progress +${System.currentTimeMillis() - started}ms $key $step") }) { key, captured, error, extra ->
                        val view = key.substringAfter('|')
                        captured?.let { java.io.File(dir, "$view.jpg").writeBytes(it.first) }
                        val out = org.json.JSONObject().put("key", key).put("error", error ?: org.json.JSONObject.NULL)
                            .put("dog_p", captured?.second?.dogP ?: org.json.JSONObject.NULL).put("extra", extra ?: org.json.JSONObject())
                        java.io.File(dir, "$view.json").writeText(out.toString())
                        android.util.Log.i("BridgitDebug", "dog_ptz result +${System.currentTimeMillis() - started}ms $out")
                    }
                }.onFailure { android.util.Log.i("BridgitDebug", "dog_ptz failed: ${it.javaClass.simpleName} ${it.message}") }
                android.util.Log.i("BridgitDebug", "dog_ptz done +${System.currentTimeMillis() - started}ms")
            }
        }
        // jev_box=<path to a JPEG readable by the app>|<what to find> asks Jev for a box around it (the question the
        // PTZ search asks about pets) and logs the answer under BridgitDebug.
        intent.getStringExtra("jev_box")?.let { v ->
            val (path, what) = v.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "the pet animal" } }
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching {
                    val ref = "box-" + java.util.UUID.randomUUID()
                    val q = org.json.JSONObject().put("seen", org.json.JSONObject().put("type", "noul").put("instructions", "Is $what visible in `photo`?"))
                        .put("box", org.json.JSONObject().put("type", "box").put("instructions", "$what in `photo`"))
                    val a = cc.bridgit.app.judgment.Jev.ask(app, org.json.JSONObject().put("photo", ref), q, assets = org.json.JSONObject().put(ref, cc.bridgit.app.judgment.Jev.image(java.io.File(path).readBytes())), applet = "dog")
                    android.util.Log.i("BridgitDebug", "jev_box seen=${a.noul("seen")} box=${a.box("box")}")
                }.onFailure { android.util.Log.i("BridgitDebug", "jev_box failed: ${it.javaClass.simpleName} ${it.message}") }
            }
        }
        if (intent.hasExtra("dog_start")) app.dog.start()
        if (intent.hasExtra("dog_stop")) app.dog.stop()
        if (intent.hasExtra("dog_status")) {
            val s = app.dog.state.value
            android.util.Log.i("BridgitDebug", "dog_status running=${s.running} photos=${s.photos} asked=${s.asked} error=${s.error} mode=${s.cameraMode} cameras=${s.cameras} scanning=${s.scanning} choice=${s.choice} next=${s.nextScanAt} " +
                "latest=${app.dog.tracker.latest()?.json()} day=${app.dog.tracker.day(System.currentTimeMillis())} episodes=${app.dog.tracker.episodes()} " +
                "mics=${s.microphones} mic_status=\"${s.microphoneStatus}\" bark=${s.bark} listener=${app.wake.running?.loud} subs=${app.wake.loud.all().map { it.id + it.sources }} cams=${s.microphones.filter { it.startsWith("lan:") }.associateWith { app.cameraMics.status(it) }} audio=${s.audioReadings.takeLast(3)}")
        }
        // feedback_upload=false keeps test reports (acceptance F01-F05) off the team's backend.
        if (intent.hasExtra("feedback_upload")) {
            val on = intent.getBooleanExtra("feedback_upload", true)
            app.settings.feedbackUpload = on
            if (on) cc.bridgit.app.feedback.FeedbackUploadWorker.enqueue(app) else app.feedback.stopPendingUploads()
        }
        // feedback_sync runs the team status sync now (FBK-14), as the 6-hourly job would.
        if (intent.hasExtra("feedback_sync")) Thread { runCatching { cc.bridgit.app.feedback.FeedbackStatusSync.run(app) } }.start()
        // install_chain=<hex challenge> makes a fresh install key with that challenge and logs its attestation
        // chain (base64 DER, leaf first) under BridgitDebug, for the backend's test fixtures. It drops the
        // registration, so the next upload registers again.
        intent.getStringExtra("install_chain")?.let { hex ->
            Thread {
                runCatching {
                    cc.bridgit.app.feedback.InstallIdentity.forget(app)
                    cc.bridgit.app.feedback.InstallIdentity.makeKeyForTest(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                    cc.bridgit.app.feedback.InstallIdentity.chain().forEachIndexed { i, c -> android.util.Log.i("BridgitDebug", "install_chain $i $c") }
                }.onFailure { android.util.Log.w("BridgitDebug", "install_chain failed", it) }
            }.start()
        }
        // install_status logs the registered install ID and trust (registering first if needed).
        if (intent.hasExtra("install_status")) Thread {
            val s = runCatching { cc.bridgit.app.feedback.InstallIdentity.ensure(app, BuildConfig.FEEDBACK_URL.trimEnd('/')) }
            android.util.Log.i("BridgitDebug", "install_status ${s.getOrNull() ?: s.exceptionOrNull()}")
        }.start()
        // workflow_test=<base64 workflow JSON> (docs/applet-flows.md): as agent workflow_agent (default FlowTester, added if
        // missing) switches on and grants the scopes it needs, proposes, activates and runs it once, and logs
        // "workflow_test <result>". workflow_pending=true logs the open workflow questions; workflow_answer=<id>:<choice>
        // answers one, standing in for the owner's tap. workflow_runs=<id> logs that flow's newest runs and its counters.
        intent.getStringExtra("workflow_runs")?.let { id ->
            val r = app.platform.store.find(id, kind = "workflow")
            android.util.Log.i("BridgitDebug", "workflow_runs $id skipped=${r?.optInt("skipped_runs")} dropped=${r?.optInt("dropped_runs")} day_runs=${r?.optInt("day_runs")} ${r?.let { runCatching { app.platform.workflows.runs(it.getString("agent_id"), id, 5) }.getOrNull() }}")
            android.util.Log.i("BridgitDebug", "power_state ${cc.bridgit.app.power.PowerState.load(app)}")
        }
        intent.getStringExtra("workflow_test")?.let { b64 ->
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val r = runCatching {
                    val def = org.json.JSONObject(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8))
                    val name = intent.getStringExtra("workflow_agent") ?: "FlowTester"
                    val agent = app.policy.agents().firstOrNull { it.name == name }?.id ?: app.policy.addAgent(name).first.id
                    val scopes = app.platform.workflows.validate(def).getJSONArray("required_scopes")
                    for (i in 0 until scopes.length()) { cc.bridgit.app.platform.Capabilities.setEnabled(app, scopes.getString(i), true); app.platform.grant(agent, scopes.getString(i), System.currentTimeMillis() + 3_600_000) }
                    val id = app.platform.workflows.propose(agent, def).getString("id")
                    app.platform.workflows.activate(id)
                    android.util.Log.i("BridgitDebug", "workflow_test started $id: ${app.platform.workflows.validate(def).getString("summary")}")
                    // workflow_no_run=true only activates it, for flows a trigger starts (power, sun, battery).
                    if (intent.getBooleanExtra("workflow_no_run", false)) "activated $id" else app.platform.workflows.runOnce(agent, id)
                }
                android.util.Log.i("BridgitDebug", "workflow_test ${r.getOrNull() ?: ("failed " + r.exceptionOrNull() + " " + ((r.exceptionOrNull() as? cc.bridgit.app.api.ApiError)?.extra ?: ""))}")
            }
        }
        if (intent.getBooleanExtra("workflow_pending", false)) app.platform.store.list(kind = "interaction").filter { it.optString("status") == "pending" }
            .forEach { android.util.Log.i("BridgitDebug", "workflow_pending ${it.getString("id")} ${it.optString("title")} ${it.optJSONArray("choices")}") }
        intent.getStringExtra("workflow_answer")?.let { spec ->
            val (id, choice) = spec.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            android.util.Log.i("BridgitDebug", "workflow_answer ${runCatching { app.platform.answer(id, choice) }.exceptionOrNull() ?: "ok"}")
        }
        // jev_test=<agent name> (or true) asks Jev one canned question through POST /v1/jev with two conditional
        // actions, runs the ones that fired as that agent (or the owner), and logs answer and actions.
        (intent.extras?.get("jev_test"))?.takeIf { it != false }?.let { v ->
            val who = (v as? String)?.let { app.agents.byName(it) }
            Thread {
                val q = org.json.JSONObject().put("charge", org.json.JSONObject().put("type", "choice")
                    .put("instructions", "Given the phone state, should the owner plug the phone in before a two-hour video call?")
                    .put("criteria", org.json.JSONObject().put("yes", "Plug in first").put("no", "Fine as it is")))
                val state = org.json.JSONObject().put("battery_percent", 12).put("charging", false).put("screen_on", true)
                val rules = org.json.JSONArray()
                    .put(org.json.JSONObject().put("id", "plug_in").put("when", org.json.JSONObject().put("answer", "charge").put("choice", "yes").put("min_confidence", 0.6))
                        .put("do", org.json.JSONObject().put("type", "notice").put("title", "Plug in before your call").put("text", "Jev thinks 12% won't last a two-hour call.")))
                    .put(org.json.JSONObject().put("id", "fine").put("when", org.json.JSONObject().put("answer", "charge").put("choice", "no").put("min_confidence", 0.6))
                        .put("do", org.json.JSONObject().put("type", "log").put("message", "Battery is fine for the call.")))
                runCatching { cc.bridgit.app.judgment.Jev.ask(app, state, q, rules) }
                    .onSuccess { a ->
                        val c = a.choice("charge", listOf("yes", "no"))
                        val ran = cc.bridgit.app.judgment.JevActions.run(app, who?.id ?: "owner", who?.name ?: "Bridgit", a.actionsSchema, a.actions)
                        android.util.Log.i("BridgitDebug", "jev_test ok ${c?.first} ${c?.second} model=${a.model} tokens=${a.inputTokens} cost=${a.costUsd} ms=${a.elapsedMs} actions=$ran skipped=${a.skipped}")
                    }
                    .onFailure { e ->
                        val r = (e as? cc.bridgit.app.judgment.Jev.Failure)?.let { "${it.reason} http=${it.http}" } ?: e.javaClass.simpleName
                        android.util.Log.w("BridgitDebug", "jev_test failed $r ${e.message}")
                    }
            }.start()
        }
        intent.getStringExtra("remove")?.let { n -> app.agents.byName(n)?.let { app.agents.remove(it.id) } }
        // developer (old name) sets both; fixer and super_powers set one each.
        for (key in listOf("developer", "fixer", "super_powers")) intent.getStringExtra(key)?.let { spec ->
            val (n, v) = spec.split("=").let { it[0] to (it.getOrNull(1) != "false") }
            app.agents.byName(n)?.let { a -> app.agents.update(a.id) {
                when (key) { "fixer" -> it.copy(fixer = v); "super_powers" -> it.copy(superPowers = v); else -> it.copy(fixer = v, superPowers = v) }
            } }
        }
        // adb_connect: connect Bridgit's own ADB client (an allowed adb tcpip port, or a pairing it already has);
        // adb_pair_code: the 6-digit code from Wireless debugging → Pair device with pairing code. Logged under BridgitDebug.
        if (intent.hasExtra("adb_connect")) Thread {
            val r = runCatching { kotlinx.coroutines.runBlocking { app.adb.connect() } }
            android.util.Log.i("BridgitDebug", "adb_connect ${r.fold({ "ok connected=${app.adb.connected}" }, { "failed ${it.javaClass.simpleName}: ${it.message}" })}")
        }.start()
        intent.getStringExtra("adb_pair_code")?.let { code ->
            cc.bridgit.app.adb.AdbPairing.start(app); cc.bridgit.app.adb.AdbPairing.submit(app, code)
            android.util.Log.i("BridgitDebug", "adb_pair_code submitted")
        }
        // update_apk=<file name in files/>: verifies that APK like a download, then installs it over Bridgit's ADB link
        // (the no-confirm path). Logs "update_apk ..." under BridgitDebug; on success Android stops and restarts Bridgit.
        intent.getStringExtra("update_apk")?.let { name ->
            Thread {
                val f = java.io.File(app.filesDir, name.substringAfterLast('/'))
                kotlinx.coroutines.runBlocking {
                    when (val r = cc.bridgit.app.update.UpdateInstaller.verifyLocalForTest(app, f)) {
                        is cc.bridgit.app.update.UpdateInstaller.Download.Refused -> android.util.Log.i("BridgitDebug", "update_apk refused ${r.problem}")
                        is cc.bridgit.app.update.UpdateInstaller.Download.Ready -> {
                            android.util.Log.i("BridgitDebug", "update_apk verified ${r.manifest.versionCode}, installing over ADB")
                            val res = cc.bridgit.app.update.UpdateInstaller.installViaAdb(app, r.file, r.manifest)
                            android.util.Log.i("BridgitDebug", "update_apk result $res")
                        }
                    }
                }
            }.start()
        }
        // update_apk_session=<file name in files/>: verifies that APK like a download, then installs it the way the
        // Install button does on Android 12+ (PackageInstaller, no Android confirm). Logs "update_apk_session ..." under BridgitDebug.
        intent.getStringExtra("update_apk_session")?.let { name ->
            Thread {
                val f = java.io.File(app.filesDir, name.substringAfterLast('/'))
                kotlinx.coroutines.runBlocking {
                    when (val r = cc.bridgit.app.update.UpdateInstaller.verifyLocalForTest(app, f)) {
                        is cc.bridgit.app.update.UpdateInstaller.Download.Refused -> android.util.Log.i("BridgitDebug", "update_apk_session refused ${r.problem}")
                        is cc.bridgit.app.update.UpdateInstaller.Download.Ready -> {
                            android.util.Log.i("BridgitDebug", "update_apk_session verified ${r.manifest.versionCode}, installing")
                            val res = cc.bridgit.app.update.UpdateInstaller.installViaSession(app, r.file, r.manifest)
                            android.util.Log.i("BridgitDebug", "update_apk_session result $res")
                        }
                    }
                }
            }.start()
        }
        // tailscale_install=adb|session: downloads Tailscale from pkgs.tailscale.com and installs it even if it's
        // already there (an update keeps its sign-in), over ADB or with Android's installer. Logs "tailscale_install ...".
        intent.getStringExtra("tailscale_install")?.let { how ->
            Thread {
                var lastPct = -10
                val r = kotlinx.coroutines.runBlocking {
                    cc.bridgit.app.update.TailscaleInstaller.install(app, force = true, allowAdb = how == "adb") { pct ->
                        if (pct >= lastPct + 10 || pct > 100) { lastPct = pct; android.util.Log.i("BridgitDebug", "tailscale_install progress $pct") }
                    }
                }
                android.util.Log.i("BridgitDebug", "tailscale_install result $r")
                if (r is cc.bridgit.app.update.TailscaleInstaller.Result.AskedAndroid)
                    runCatching { app.startActivity(r.confirm.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { android.util.Log.i("BridgitDebug", "tailscale_install confirm failed $it") }
            }.start()
        }
        // revoke_super_powers: the owner's Revoke Super Powers button; each step is logged under BridgitDebug.
        if (intent.hasExtra("revoke_super_powers")) Thread {
            val lines = kotlinx.coroutines.runBlocking { cc.bridgit.app.adb.SuperPowers.revoke(app) }
            lines.forEach { android.util.Log.i("BridgitDebug", "revoke ${if (it.ok) "ok  " else "FAIL"} ${it.what}${it.how?.let { h -> " — $h" } ?: ""}") }
        }.start()
        intent.getStringExtra("caps")?.let { spec ->
            val (name, grants) = spec.split("=", limit = 2)
            app.agents.byName(name)?.let { a -> app.agents.update(a.id) { it.copy(caps = if (grants == "*") null else grants.split(',').filter(String::isNotBlank).toSet()) } }
        }
        intent.getStringExtra("clipboard_decide")?.let { spec ->
            val p = spec.split(":", limit = 2)
            app.clipboardRequests.decide(p[0], p.getOrNull(1) == "approve")
        }
        // sounds_agent=<agent name>[:quiet|normal|loud] stands in for the "Any sounds" card.
        intent.getStringExtra("sounds_agent")?.let { spec ->
            val p = spec.split(":")
            app.agents.byName(p[0])?.let { a -> app.wake.saveSoundConfig(cc.bridgit.app.listen.SoundConfig(a.id, p.getOrElse(1) { "normal" })) }
        }
        if (intent.hasExtra("wake_clear")) app.wake.saveWords(emptyList())
        intent.getStringExtra("wake_word")?.let { spec ->
            val p = spec.split(":")
            app.agents.byName(p[0])?.let { a -> app.wake.saveWords(app.wake.words() + cc.bridgit.app.listen.WakeWord(p[1], a.id, p.getOrNull(2) == "clip")) }
        }
        var code = ""
        intent.getStringExtra("rotate")?.let { n ->
            app.agents.byName(n)?.let { a ->
                val (agent, key) = app.agents.rotate(a.id)
                code = app.pairing.open(agent.id, agent.name, key).code
            }
        }
        intent.getStringExtra("agent")?.let { name ->
            app.agents.byName(name)?.let { app.agents.remove(it.id) }
            val (agent, key) = app.agents.add(name, Connection.of(intent.getStringExtra("connection")))
            code = app.pairing.open(agent.id, agent.name, key).code
            app.log.system("audit", "agent", "Debug hook added test agent ${agent.name}")
        }
        if (intent.getBooleanExtra("listener", true)) {
            app.settings.listenerEnabled = true
            BridgeService.start(context)
        } else if (intent.hasExtra("listener")) {
            app.settings.listenerEnabled = false
            BridgeService.pauseSilently(context)
        }
        intent.getStringExtra("platform_grants")?.let { spec ->
            val parts=spec.split(":",limit=2)
            app.agents.byName(parts[0])?.let { a -> parts.getOrElse(1) { "" }.split(',').filter { it.isNotBlank() }.forEach { id ->
                cc.bridgit.app.platform.Capabilities.setEnabled(app,id,true)
                app.platform.grant(a.id,id,System.currentTimeMillis()+3600_000)
            } }
        }
        // connection_add=<base64 JSON {name, url, allow_private, preset?, extra?, headers?}> approves a connection as the
        // owner would in Applets › Connections (base64 keeps the URL's secret out of the shell); logs "connection <id> <preset>".
        intent.getStringExtra("connection_add")?.let { b64 ->
            runCatching {
                val o = org.json.JSONObject(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT)))
                val c = app.platform.delivery.destination(o.getString("name"), o.getString("url"), o.optBoolean("allow_private"), o.optString("preset").ifEmpty { null }, o.optJSONObject("headers"), o.optJSONObject("extra"))
                android.util.Log.i("BridgitDebug", "connection ${c.getString("id")} ${c.getString("preset")}")
                resultData = c.getString("id")
            }.onFailure { android.util.Log.w("BridgitDebug", "connection_add failed: ${it.message}") }
        }
        // send_queued=<connection id>|<text> queues one send as the owner (no reply wait), for the network-back flush test;
        // logs "send_queued <delivery id>". Its status is in send_status=<delivery id> ("send_status <status> <attempts>").
        intent.getStringExtra("send_queued")?.let { spec ->
            val p = spec.split("|", limit = 2)
            runCatching { app.platform.delivery.send("owner", p[0], org.json.JSONObject(), p.getOrElse(1) { "test" }, null, waitReply = false, flow = "Flush test") }
                .onSuccess { android.util.Log.i("BridgitDebug", "send_queued ${it.optString("delivery_id")}") }.onFailure { android.util.Log.w("BridgitDebug", "send_queued failed: ${it.message}") }
        }
        intent.getStringExtra("send_status")?.let { id -> app.platform.store.find(id, "owner")?.let { android.util.Log.i("BridgitDebug", "send_status ${it.optString("status")} ${it.optInt("attempts")} ${it.optString("error")}") } }
        // connection_secret=<id> logs that connection's whsec_ secret, for a test receiver on the developer's machine.
        intent.getStringExtra("connection_secret")?.let { id -> app.platform.store.find(id, "owner", "destination")?.let { android.util.Log.i("BridgitDebug", "connection_secret ${it.optString("whsec")}") } }
        intent.getStringExtra("schedule_approve")?.let { n -> app.agents.byName(n)?.let { app.schedules.approve(it.id) } }
        intent.getStringExtra("platform_session_approve")?.let { app.platform.approveSession(it) }
        intent.getStringExtra("platform_operation_approve")?.let { app.platform.approveOperation(it) }
        intent.getStringExtra("platform_workflow_activate")?.let { app.platform.workflows.activate(it) }
        intent.getStringExtra("platform_answer")?.let { value -> val p=value.split(":",limit=2); app.platform.answer(p[0],p.getOrElse(1) { "" }) }
        if (intent.hasExtra("open_on_arrival"))
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean(cc.bridgit.app.interact.InteractionNotifier.PREF_OPEN_ON_ARRIVAL, intent.getBooleanExtra("open_on_arrival", false)).apply()
        // interaction_html=<agent name> stands in for an agent sending a sample HTML card (CAP-34). Send it in its own
        // broadcast after the one that adds the agent.
        intent.getStringExtra("interaction_html")?.let { name ->
            app.agents.byName(name)?.let { a ->
                val draft = cc.bridgit.app.interact.Draft(cc.bridgit.app.interact.IKind.HTML, "Pick a table", null, emptyList(),
                    "normal", false, 600, null, null, SAMPLE_CARD)
                runCatching { app.interactions.create(a.id, a.name, draft, null) }
                    .onFailure { app.log.system("diagnostic", "interaction", "Debug hook couldn't create a card: ${it.message}", level = "warn") }
            }
        }
        if (resultData == null) resultData = code
        // Relay stand-ins for More → Relay on a locked test phone. relay_keys returns the public enrolment JSON;
        // relay_base sets the relay URL; relay_agent=<name>:<sealed|connector>[:<agent HPKE public key>] adds an
        // agent with RelayActivity's default allowlist and returns its one-time setup JSON; relay_enroll=<token>
        // (empty to resume) confirms enrolment; relay_disable=1 turns the relay off.
        val relay = cc.bridgit.app.relay.RelayStore(app)
        if (intent.hasExtra("relay_keys")) resultData = relay.enrollment().toString()
        intent.getStringExtra("relay_base")?.let { relay.base = it }
        intent.getStringExtra("relay_agent")?.let { spec ->
            val p = spec.split(":", limit = 3)
            app.agents.byName(p[0])?.let { app.agents.remove(it.id) }
            val specs = app.registry.modules.flatMap { it.endpoints }.filter { it.auth && it.power == null && it.capability != "feedback" && it.path != "/mcp" && it.capability !in setOf("tour", "speaker", "video", "screenshare") }
            val routes = specs.filter { it.capability == "status" }
            val tools = (cc.bridgit.app.platform.Commands.reads + cc.bridgit.app.platform.Commands.mutations - cc.bridgit.app.relay.RelayDispatcher.excluded)
                .filter { it in setOf("diagnostics", "phone.status", "interaction.create", "interaction.get", "interaction.list", "events", "operation.get") }.toSet()
            val a = app.agents.add(p[0], Connection.RELAY).first
            app.agents.update(a.id) { it.copy(caps = routes.map { r -> r.capability }.toSet(), fixer = false, superPowers = false) }
            val token = relay.addAgent(a.id, p[1], p.getOrNull(2), routes.map { "${it.method} ${it.fullPath}" }.toSet(), tools)
            resultData = org.json.JSONObject().put("base", relay.base).put("device_id", relay.device).put("agent_id", a.id).put("generation", app.agents.get(a.id)!!.keyHash)
                .put("mode", p[1]).put("token", token).put("phone_hpke_public", cc.bridgit.app.relay.Sealed.publicKey(relay.hpke())).put("mcp_url", relay.base + "/mcp").toString()
        }
        if (intent.hasExtra("relay_disable")) BridgeService.running?.relay?.disable()
        intent.getStringExtra("relay_enroll")?.let { token ->
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    var service = BridgeService.running
                    for (i in 0 until 50) { if (service != null) break; kotlinx.coroutines.delay(100); service = BridgeService.running }
                    (service?.relay ?: error("Resume the listener first")).enroll(token)
                    pending.resultData = "enrolled"
                } catch (e: Exception) { pending.resultData = "error: ${e.message}" } finally { pending.finish() }
            }
            return
        }
        if (intent.getBooleanExtra("speech_cloud_fixture", false)) {
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val fixture = java.io.File(app.filesDir, "speech-fixture.m4a")
                    val expected = "The kettle is ready. Please turn down the heat and stir the soup."
                    val t = System.currentTimeMillis()
                    val hearing = cc.bridgit.app.speech.SpeechEngine.select(app, "hear", true)
                    val heard = cc.bridgit.app.speech.SpeechEngine.hear(app, hearing, fixture.readBytes(), "en-US")
                    android.util.Log.i("BridgitDebug", "speech_cloud_fixture stt_match=${heard.optString("transcript").trim() == expected} model=${heard.optString("model")} elapsed_ms=${System.currentTimeMillis()-t} cost=${heard.optDouble("cost_usd")}")
                    val voice = cc.bridgit.app.speech.SpeechEngine.select(app, "speak", true)
                    val out = app.speaker.newFile("audio")
                    try {
                        val started = System.currentTimeMillis()
                        val answer = cc.bridgit.app.speech.SpeechEngine.synth(app, voice, expected, out, "en-US")
                        val media = android.media.MediaMetadataRetriever()
                        val duration = try { media.setDataSource(out.path); media.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION) } finally { media.release() }
                        android.util.Log.i("BridgitDebug", "speech_cloud_fixture tts_bytes=${out.length()} duration_ms=$duration model=${answer.optString("model")} elapsed_ms=${System.currentTimeMillis()-started} cost=${answer.optDouble("cost_usd")}")
                    } finally { out.delete() }
                } catch (e: Exception) { android.util.Log.i("BridgitDebug", "speech_cloud_fixture failed ${e.javaClass.simpleName}: ${e.message}") }
                finally { pending.finish() }
            }
        }
        // Synthetic fixture only: native speech acceptance without logging words or uploading audio.
        if (intent.getBooleanExtra("speech_fixture", false)) {
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val started = System.currentTimeMillis()
                    val fixture = java.io.File(app.filesDir, "speech-fixture.m4a")
                    val expected = "The kettle is ready. Please turn down the heat and stir the soup."
                    val text = cc.bridgit.app.planned.Transcriber.transcribe(app, fixture.readBytes(), "en-US")
                    val normalized: (String) -> String = { it.lowercase().replace(Regex("[^a-z ]"), "").trim() }
                    val expectedWords = normalized(expected).split(" ")
                    val heardWords = normalized(text).split(" ")
                    var distances = IntArray(expectedWords.size + 1) { it }
                    for ((i, word) in heardWords.withIndex()) {
                        val next = IntArray(expectedWords.size + 1); next[0] = i + 1
                        for (j in expectedWords.indices) next[j+1] = minOf(next[j]+1, distances[j+1]+1, distances[j]+if(word == expectedWords[j]) 0 else 1)
                        distances = next
                    }
                    android.util.Log.i("BridgitDebug", "speech_fixture stt_match=${normalized(text) == normalized(expected)} words=${heardWords.size} word_errors=${distances.last()} expected_words=${expectedWords.size} elapsed_ms=${System.currentTimeMillis()-started}")
                    val out = app.speaker.newFile("wav")
                    try {
                        val t = System.currentTimeMillis()
                        val ok = app.speaker.tts.synth(expected, out, "en-US", 1f, 1f)
                        android.util.Log.i("BridgitDebug", "speech_fixture tts_ok=$ok bytes=${out.length()} elapsed_ms=${System.currentTimeMillis()-t}")
                    } finally { out.delete() }
                } catch (e: Exception) { android.util.Log.i("BridgitDebug", "speech_fixture failed ${e.javaClass.simpleName}: ${e.message}") }
                finally { pending.finish() }
            }
        }
        // Last, after the result is set: never block the main thread (ask() reads the compass on it).
        intent.getStringExtra("tour_ask")?.let { q ->
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try { app.tours.ask(q, spoken = false) } finally { pending.finish() }
            }
        }
    }

    private companion object {
        /** A card that fills its frame, so a screenshot shows how much of the screen the page gets. */
        const val SAMPLE_CARD = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;font-family:sans-serif}
body{display:flex;flex-direction:column;background:linear-gradient(160deg,#0f766e,#1e3a8a);color:#fff}
header{padding:20px}h1{margin:0 0 6px;font-size:26px}p{margin:0;opacity:.85}
main{flex:1;display:grid;grid-template-columns:1fr 1fr;gap:12px;padding:0 20px}
button{border:0;border-radius:16px;font-size:20px;background:rgba(255,255,255,.18);color:#fff}
footer{padding:14px 20px 20px;text-align:center;font-size:14px;opacity:.8}</style></head>
<body><header><h1>Where shall we sit?</h1><p>This whole area is the agent's HTML page.</p></header>
<main><button onclick="Bridget.respond(JSON.stringify({table:'window'}))">Window</button><button onclick="Bridget.respond(JSON.stringify({table:'patio'}))">Patio</button>
<button onclick="Bridget.respond(JSON.stringify({table:'bar'}))">Bar</button><button onclick="Bridget.respond(JSON.stringify({table:'booth'}))">Booth</button></main>
<footer>&#9650; top of page touches Bridgit's bar &middot; bottom of page touches the screen edge &#9660;</footer></body></html>"""
    }
}
