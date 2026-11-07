package cc.bridgit.app.tour

import android.location.Location
import io.ktor.server.routing.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.R
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.Reply
import cc.bridgit.app.api.Sizing
import cc.bridgit.app.capability.Capability
import cc.bridgit.app.capability.Compass
import cc.bridgit.app.capability.Gallery
import cc.bridgit.app.capability.ImageUtil
import cc.bridgit.app.capability.LocationReader
import cc.bridgit.app.capability.envelope
import cc.bridgit.app.capability.rawReply
import cc.bridgit.app.capability.wantsRaw
import cc.bridgit.app.capability.isoAt
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** City tour (D30, 8.25): tour kinds the owner can pick. */
object TourKinds {
    val all = listOf("history", "architecture", "environment", "food", "art", "anything")
    val labels = mapOf("history" to "History", "architecture" to "Architecture", "environment" to "Environment",
        "food" to "Food", "art" to "Art", "anything" to "Anything interesting")
    val intervals = listOf(30, 60, 120, 300)
    const val MAX_HOURS = 4

    /**
     * What the tour's agent is asked to do (TOUR-06, owner request 27 Oct 2026). Sent with
     * tour.started and GET /v1/tour, and in the guide; ticks carry only data.
     */
    fun brief(kinds: List<String>): String {
        val topics = kinds.joinToString(", ") { labels[it]?.lowercase() ?: it }
        return listOf(
            "You are the owner's walking tour guide. Topics: $topics. They are walking now and listening on their phone's speaker.",
            "Keep listening until tour.ended: loop GET /v1/events?since=<next_cursor>&wait=55 (60 s timeout). If your " +
                "harness only acts during a turn, run each wait as a background command so the next event wakes you. " +
                "Don't stop or end your turn while the tour runs; if you stop, they walk in silence.",
            "The first tour.tick comes seconds after tour.started: greet them in 1-2 sentences, say where they are and what's " +
                "around for these topics. Every tour.tick has location {latitude, longitude, accuracy_m}, compass " +
                "{azimuth_deg = the way the phone points, 0 = north; direction}, moved_m and new_photos (photos they took since the last tick).",
            "Speak with POST /v1/speaker/say {\"text\",\"tour_id\"}: 2-4 short sentences per tick, spoken style, no lists. " +
                "Don't repeat what you already said; if they haven't moved and you have nothing new, stay silent.",
            "Say where things are relative to them: work out the bearing from their position to the place, compare it with " +
                "the compass and say \"ahead\", \"on your left\", \"behind you\" plus the compass direction and distance " +
                "(\"about 200 m to your right, to the east\").",
            "When there is nothing more of interest right here, say so in one sentence, then name the 2-3 nearest " +
                "interesting places for these topics with their direction relative to them and distance, so they can choose where to walk.",
            "tour.question is them asking; answer it first with \"urgent\": true. tour.photo is a photo they just took for you: " +
                "GET its fetch link, say what it shows and why it matters for these topics. Fetch new_photos on a tick the same way.",
            "If location accuracy is worse than 100 m, say places are approximate. Never invent facts; say when you're not sure.",
        ).joinToString("\n")
    }

    /**
     * What the owner pastes into the agent's chat when it isn't listening for events (owner report 27 Oct
     * 2026: agents never answered because nothing woke them). No key: the agent has it from pairing.
     */
    fun startPrompt(agentName: String, baseUrl: String, kinds: List<String>, tourRunning: Boolean): String = buildString {
        appendLine("Bridgit City tour: the owner ${if (tourRunning) "has started" else "is starting"} a walking tour with you ($agentName) " +
            "and you are not listening for Bridgit events yet, so nothing reaches you.")
        appendLine("Bridgit is at $baseUrl (use your Bridgit key from pairing, as before).")
        appendLine("1. GET /v1/tour now. If a tour runs, its brief is there; follow it.")
        appendLine("2. GET /v1/events (no since) and keep its next_cursor, then loop GET /v1/events?since=<next_cursor>&wait=55 " +
            "with a 60 s timeout until tour.ended. If your harness only acts during a turn, run each wait as a background " +
            "command so each event wakes you, answer, then start the next wait.")
        appendLine("3. Answer every tour.tick, tour.question and tour.photo by speaking with POST /v1/speaker/say {\"text\",\"tour_id\"}.")
        appendLine()
        appendLine("The brief:")
        append(brief(kinds))
    }
}

/** What was said during the tour, for the tour screen's history. */
data class Said(val at: Long, val who: String, val text: String)

/** A Take photo picture: where Android's camera saved it (in the owner's gallery). */
data class Shot(val uri: android.net.Uri, val at: Long)

data class Tour(
    val id: String, val agentId: String, val agent: String, val kinds: List<String>, val intervalS: Int,
    val startedAt: Long, val paused: Boolean = false, val lastTickAt: Long = 0, val ticks: Int = 0,
    val distanceM: Double = 0.0, val lastLat: Double? = null, val lastLon: Double? = null, val photos: Int = 0,
    val heading: Double? = null, val caption: String? = null, val said: List<Said> = emptyList(),
    /** TOUR-04: battery percentage at the start; [charged] once the phone was seen charging during the tour. */
    val batteryStartPct: Int? = null, val charged: Boolean = false,
    /** Photos the owner took with Take photo on this tour, in order; /v1/tour/photo/{n} is 1-based. */
    val shots: List<Shot> = emptyList(),
) {
    fun json(): JSONObject = JSONObject().put("tour_id", id).put("kinds", JSONArray(kinds)).put("interval_s", intervalS)
        .put("started_at", isoAt(startedAt)).put("paused", paused).put("ticks", ticks)
        .put("last_tick_at", if (lastTickAt > 0) isoAt(lastTickAt) else JSONObject.NULL)
        .put("distance_m", Math.round(distanceM)).put("photos_sent", photos).put("brief", TourKinds.brief(kinds))
}

/**
 * The running tour, every change made under one lock. Pure (no Android), unit-tested.
 * [update] changes the tour only while [Tour.id] is still the current one and always starts from the
 * current value, so a concurrent Pause, End or replacement is never undone by a stale snapshot.
 */
class TourState {
    private val _state = MutableStateFlow<Tour?>(null)
    val flow: StateFlow<Tour?> get() = _state

    fun current(): Tour? = _state.value

    /** Runs [block] with the lock held. Tour-event emission and End use it, so nothing is sent after End. */
    @Synchronized fun <R> locked(block: () -> R): R = block()

    /** Makes [t] the running tour; returns the one it replaced (the caller ends that first). */
    @Synchronized fun begin(t: Tour): Tour? = _state.value.also { _state.value = t }

    /** Applies [f] to the current tour if it is [tourId]; a no-op returning null otherwise. [f] may not change the id. */
    @Synchronized fun update(tourId: String, f: (Tour) -> Tour): Tour? {
        val cur = _state.value?.takeIf { it.id == tourId } ?: return null
        val next = f(cur)
        require(next.id == cur.id) { "update can't replace the tour" }
        _state.value = next
        return next
    }

    /** Pauses or resumes the current tour (only if it is [tourId], when given). */
    @Synchronized fun setPaused(p: Boolean, tourId: String? = null): Tour? {
        val cur = _state.value ?: return null
        if (tourId != null && cur.id != tourId) return null
        return cur.copy(paused = p).also { _state.value = it }
    }

    /** Clears the current tour (only if it is [tourId], when given) and returns it. */
    @Synchronized fun take(tourId: String? = null): Tour? {
        val cur = _state.value ?: return null
        if (tourId != null && cur.id != tourId) return null
        _state.value = null
        return cur
    }

    /** Clears the current tour only if [agentId] runs it (and it is [tourId], when given): check and end in one step. */
    @Synchronized fun takeIfOwned(agentId: String, tourId: String? = null): Tour? {
        val cur = _state.value ?: return null
        if (cur.agentId != agentId || (tourId != null && cur.id != tourId)) return null
        _state.value = null
        return cur
    }
}

/** TOUR-04: battery used so far. Pure, unit-tested. */
object TourBattery {
    /** Percentage points used since the start, or null when unknown or the phone charged during the tour. */
    fun used(startPct: Int?, nowPct: Int?, charged: Boolean): Int? {
        if (charged || startPct == null || nowPct == null) return null
        return (startPct - nowPct).coerceAtLeast(0)
    }
}

/** TOUR-02: what an ended tour's stored events keep. Pure, unit-tested. */
object TourPrivacy {
    val EVENT_TYPES = setOf("tour.tick", "tour.question", "tour.photo")
    /** Location, compass and photo ids with their fetch links: removed once the tour ends. */
    val SENSITIVE = setOf("location", "compass", "latest_photo", "new_photos", "fetch")

    /** [data] without the [SENSITIVE] fields (the event shell stays), or null if there is nothing to remove. */
    fun scrub(data: JSONObject): JSONObject? {
        if (SENSITIVE.none { data.has(it) }) return null
        val out = JSONObject(data.toString())
        (out.opt("new_photos") as? JSONArray)?.let { out.put("new_photos_count", it.length()) }
        SENSITIVE.forEach { out.remove(it) }
        return out.put("removed", "The tour ended; its location, compass and photos are not kept.")
    }
}

/**
 * Runs one tour at a time. Ticks go to the tour's agent as `tour.tick` events (section 9), the
 * agent answers with /v1/speaker (tour_id). The owner's questions go as `tour.question`.
 * Location is sent only to that agent, only during the tour (TOUR-02): every tour event is emitted
 * under the lock End takes, End strips location from that tour's stored events, and the Location
 * switch is re-checked right before each tick or question is built.
 */
class TourManager(private val app: BridgeApp) {
    companion object {
        /** A long-poll holds 55 s, so an agent that polled within 90 s is still looping. */
        const val LISTEN_WINDOW_MS = 90_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val tour = TourState()
    val state: StateFlow<Tour?> get() = tour.flow
    private val reader = LocationReader(app)

    fun current(): Tour? = tour.current()

    init {
        // The ongoing tour notification (8.25 step 5) follows the state; it goes when the tour ends.
        scope.launch { TourNotifier.follow(app, tour.flow) }
    }

    private val battery get() = app.getSystemService(android.os.BatteryManager::class.java)

    /** Battery level 0-100, or null if Android doesn't say. */
    fun batteryPct(): Int? = runCatching { battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) }
        .getOrNull()?.takeIf { it in 0..100 }

    fun charging(): Boolean = runCatching { battery?.isCharging == true }.getOrDefault(false)

    /** TOUR-04: battery used so far by tour [t], or null (unknown, or charged during the tour). Notes charging it sees. */
    fun batteryUsed(t: Tour): Int? {
        val chargingNow = charging()
        val cur = if (chargingNow && !t.charged) tour.update(t.id) { it.copy(charged = true) } ?: t.copy(charged = true) else t
        return TourBattery.used(cur.batteryStartPct, batteryPct(), cur.charged || chargingNow)
    }

    /** The agent hears its events: it has a callback URL, or called GET /v1/events in the last [LISTEN_WINDOW_MS]. */
    fun listening(agentId: String): Boolean = app.callbacks.get(agentId) != null ||
        System.currentTimeMillis() - app.interactions.lastPoll(agentId) < LISTEN_WINDOW_MS

    /** The tour's agent has fetched its events since [t] started (or gets them pushed to a callback URL). */
    fun pickedUp(t: Tour): Boolean = app.callbacks.get(t.agentId) != null || app.interactions.lastPoll(t.agentId) >= t.startedAt

    /** What's missing before a tour can start (TOUR-01); empty = ready. */
    fun missing(): List<String> = buildList {
        if (!app.settings.isOn("interactions")) add("Interactions switch")
        if (!app.settings.isOn("location")) add("Location switch")
        if (reader.precision() == null) add("Location permission")
        if (!reader.enabled()) add("Android's Location setting")
        if (!app.settings.isOn("speaker")) add("Speaker switch")
        if (!app.captureNotificationsOn()) add("Notifications")
    }

    fun start(agentId: String, kinds: List<String>, intervalS: Int): Tour {
        val a = app.agents.get(agentId) ?: throw IllegalArgumentException("No such agent")
        require(a.allows("tour") && a.allows("interactions") && a.allows("location") && a.allows("speaker")) { "This agent needs Tour, Location, Interactions and Speaker access" }
        require(kinds.isNotEmpty() && kinds.all { it in TourKinds.all }) { "Pick at least one kind of tour" }
        require(intervalS in TourKinds.intervals) { "Bad interval" }
        val t = Tour("t" + UUID.randomUUID().toString().replace("-", "").take(10), a.id, a.name, kinds, intervalS, System.currentTimeMillis(),
            batteryStartPct = batteryPct(), charged = charging())
        tour.locked {
            tour.take()?.let { finish(it, "a new tour started") }
            tour.begin(t)
            app.interactions.event(a.id, "tour.started", JSONObject().put("tour_id", t.id).put("kinds", JSONArray(kinds))
                .put("interval_s", intervalS).put("brief", TourKinds.brief(kinds))
                .put("how_to_reply", "POST /v1/speaker/say {\"text\",\"tour_id\"} or /v1/speaker/play; newer narration replaces queued")
                .put("report_model", if (a.model == null) "Tell the owner which model you are: POST /v1/me {\"model\",\"harness\"}" else JSONObject.NULL))
            job = scope.launch { loop(t.id) }
        }
        app.log.system("audit", "agent", "You started a ${kinds.joinToString("/")} city tour with ${a.name}")
        return t
    }

    private suspend fun loop(id: String) {
        while (scope.isActive) {
            val t = tour.current()?.takeIf { it.id == id } ?: return
            if (System.currentTimeMillis() - t.startedAt > TourKinds.MAX_HOURS * 3_600_000L) { endTour(id, "time limit"); return }
            if (!t.paused) {
                try { tick(id) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    app.log.system("diagnostic", "service", "Tour tick failed: ${e.javaClass.simpleName}", level = "warn")
                }
            }
            delay(t.intervalS * 1000L)
        }
    }

    /** False, and the tour ended, once the Location switch is off (TOUR-02 consent). */
    private fun locationStillOn(id: String): Boolean {
        val a = tour.current()?.takeIf { it.id == id }?.let { app.agents.get(it.agentId) }
        if (app.settings.isOn("location") && a?.allows("tour") == true && a.allows("interactions") && a.allows("location") && a.allows("speaker")) return true
        endTour(id, "location switched off")
        return false
    }

    private fun galleryAllowed(agentId: String) = app.settings.isOn("gallery") && app.agents.get(agentId)?.allows("gallery") == true

    private suspend fun tick(id: String) {
        if (!locationStillOn(id)) return
        val t = tour.current()?.takeIf { it.id == id && !it.paused } ?: return
        val fix: Location? = reader.fresh(high = false, timeoutMs = 10_000) ?: reader.lastKnown()
        val compass = Compass.read(app)
        val chargingNow = charging()
        val since = if (t.lastTickAt > 0) t.lastTickAt else t.startedAt
        val photos = if (galleryAllowed(t.agentId) && Gallery(app).accessMode() != null)
            Gallery(app).recent(20).filter { it.addedAt > since }.take(10) else emptyList()
        tour.locked {
            // After the suspensions: still this tour, still running, still allowed to send location?
            val cur = tour.current()?.takeIf { it.id == id && !it.paused } ?: return@locked
            if (!locationStillOn(id)) return@locked
            var moved = 0.0
            if (fix != null && cur.lastLat != null && cur.lastLon != null) {
                val r = FloatArray(1); Location.distanceBetween(cur.lastLat, cur.lastLon, fix.latitude, fix.longitude, r); moved = r[0].toDouble()
            }
            val data = JSONObject().put("tour_id", id).put("kinds", JSONArray(cur.kinds)).put("tick", cur.ticks + 1)
                .put("location", fix?.let { LocationReader.json(it, reader.precision() ?: "none", ::isoAt) } ?: JSONObject.NULL)
                .put("compass", compass?.let { Compass.json(it) } ?: JSONObject.NULL)
                .put("moved_m", Math.round(moved))
                .put("new_photos", JSONArray(photos.filter { galleryAllowed(cur.agentId) }.map { p -> JSONObject().put("id", p.id.toString()).put("taken_at", isoAt(p.takenAt))
                    .put("fetch", "/v1/gallery/photo/${p.id}?size=agent") }))
                .put("playing", app.speaker.current?.takeIf { it.agentId == cur.agentId }?.caption ?: JSONObject.NULL)
            app.interactions.event(cur.agentId, "tour.tick", data)
            tour.update(id) { c -> c.copy(lastTickAt = System.currentTimeMillis(), ticks = c.ticks + 1,
                distanceM = c.distanceM + moved, lastLat = fix?.latitude ?: c.lastLat, lastLon = fix?.longitude ?: c.lastLon,
                photos = c.photos + photos.size, heading = compass?.azimuthDeg ?: c.heading, charged = c.charged || chargingNow) }
            Unit
        }
    }

    /** The agent's narration arrived (for the caption and history). */
    fun narrated(tourId: String, text: String?) {
        if (text.isNullOrBlank()) return
        tour.update(tourId) { t -> t.copy(caption = text, said = (t.said + Said(System.currentTimeMillis(), t.agent, text)).takeLast(100)) }
    }

    /** The owner's question (TOUR, owner request): typed, or the transcript of what they said. */
    suspend fun ask(text: String, spoken: Boolean) {
        val t = tour.current() ?: return
        val q = text.trim().take(1000).ifEmpty { return }
        if (!locationStillOn(t.id)) return
        val fix = reader.lastKnown()
        val compass = Compass.read(app)
        val latest = if (galleryAllowed(t.agentId) && Gallery(app).accessMode() != null) Gallery(app).recent(1).firstOrNull() else null
        val sent = tour.locked {
            val cur = tour.current()?.takeIf { it.id == t.id } ?: return@locked false
            if (!locationStillOn(cur.id)) return@locked false
            app.interactions.event(cur.agentId, "tour.question", JSONObject().put("tour_id", cur.id).put("question", q).put("spoken", spoken)
                .put("location", fix?.let { LocationReader.json(it, reader.precision() ?: "none", ::isoAt) } ?: JSONObject.NULL)
                .put("compass", compass?.let { Compass.json(it) } ?: JSONObject.NULL)
                .put("latest_photo", latest?.takeIf { galleryAllowed(cur.agentId) }?.let { JSONObject().put("id", it.id.toString()).put("fetch", "/v1/gallery/photo/${it.id}?size=agent") } ?: JSONObject.NULL)
                .put("how_to_answer", "POST /v1/speaker/say {\"text\",\"tour_id\",\"urgent\":true}"))
            tour.update(cur.id) { c -> c.copy(said = (c.said + Said(System.currentTimeMillis(), "You", q)).takeLast(100)) }
            true
        }
        if (sent) app.log.system("audit", "agent", "You asked ${t.agent} a question on the tour")
    }

    /** The owner took a photo with Take photo (TOUR-07): it goes to the agent now, not at the next tick. */
    suspend fun photoTaken(uri: android.net.Uri) {
        val t = tour.current() ?: return
        if (!locationStillOn(t.id)) return
        val at = System.currentTimeMillis()
        val fix = reader.lastKnown()
        val compass = Compass.read(app)
        val sent = tour.locked {
            val cur = tour.current()?.takeIf { it.id == t.id } ?: return@locked false
            if (!locationStillOn(cur.id)) return@locked false
            val n = cur.shots.size + 1
            app.interactions.event(cur.agentId, "tour.photo", JSONObject().put("tour_id", cur.id).put("n", n).put("taken_at", isoAt(at))
                .put("fetch", "/v1/tour/photo/$n?size=agent")
                .put("location", fix?.let { LocationReader.json(it, reader.precision() ?: "none", ::isoAt) } ?: JSONObject.NULL)
                .put("compass", compass?.let { Compass.json(it) } ?: JSONObject.NULL)
                .put("how_to_answer", "GET the photo, then POST /v1/speaker/say {\"text\",\"tour_id\",\"urgent\":true}"))
            tour.update(cur.id) { c -> c.copy(shots = c.shots + Shot(uri, at), photos = c.photos + 1,
                said = (c.said + Said(at, "You", app.getString(R.string.tour_said_photo))).takeLast(100)) }
            true
        }
        if (sent) app.log.system("audit", "agent", "You sent ${t.agent} a photo on the tour")
    }

    /** Pause/Resume; keeps everything else as it is now. */
    fun setPaused(p: Boolean, tourId: String? = null) { tour.setPaused(p, tourId) }

    /** Ends whatever tour runs (the owner's End, the test hook). */
    fun end(reason: String) { tour.locked { tour.take()?.let { finish(it, reason) } } }

    /** Ends tour [tourId] only if it is still the current one (the notification's End). */
    fun endIfCurrent(tourId: String, reason: String) = endTour(tourId, reason)

    /** Ends tour [tourId] only if it is still the current one. */
    private fun endTour(tourId: String, reason: String) { tour.locked { tour.take(tourId)?.let { finish(it, reason) } } }

    /** DELETE /v1/tour: ends the tour only if [agentId] runs it (and it is [tourId], when given), in one step. */
    fun endIfOwned(agentId: String, tourId: String?, reason: String): Tour? =
        tour.locked { tour.takeIfOwned(agentId, tourId)?.also { finish(it, reason) } }

    /** With the lock held, after [t] was cleared from the state. */
    private fun finish(t: Tour, reason: String) {
        job?.cancel(); job = null
        app.speaker.stop(t.agentId)
        // TOUR-02: nothing kept after the tour; the event shells stay so cursors and history still make sense.
        runCatching { app.interactions.scrubTourEvents(t.agentId, t.id) }.onFailure {
            app.log.system("diagnostic", "service", "Couldn't clear the tour's locations: ${it.javaClass.simpleName}", level = "warn")
        }
        val chargingNow = charging()
        val used = TourBattery.used(t.batteryStartPct, batteryPct(), t.charged || chargingNow)
        app.interactions.event(t.agentId, "tour.ended", JSONObject().put("tour_id", t.id).put("reason", reason)
            .put("duration_s", (System.currentTimeMillis() - t.startedAt) / 1000).put("distance_m", Math.round(t.distanceM))
            .put("ticks", t.ticks).put("photos_sent", t.photos)
            .put("battery_used_pct", used ?: JSONObject.NULL).put("charged_during_tour", t.charged || chargingNow))
        app.log.system("audit", "agent", "City tour with ${t.agent} ended ($reason)")
    }
}

/** TOUR-03: the tour's agent reads or ends its tour. */
class TourModule(app: BridgeApp) : Capability(app) {
    override val id = "tour"
    override val titleRes = R.string.cap_tour
    override val descriptionRes = R.string.cap_tour_desc
    override val group = "apps"
    override val endpoints = listOf(Endpoints.TOUR_GET, Endpoints.TOUR_PHOTO, Endpoints.TOUR_END)

    override fun Route.install(api: ApiServer) = with(api) {
        endpoint(Endpoints.TOUR_GET) {
            val t = app.tours.current()?.takeIf { it.agentId == agent!!.id }
            Reply.Json(JSONObject().put("tour", t?.json() ?: JSONObject.NULL))
        }
        endpoint(Endpoints.TOUR_PHOTO) {
            val t = app.tours.current()?.takeIf { it.agentId == agent!!.id } ?: throw ErrorCatalog.error("not_found", "You have no tour running.")
            val n = path("n")?.toIntOrNull() ?: throw ErrorCatalog.error("invalid_parameter", "n must be the number from tour.photo.")
            val shot = t.shots.getOrNull(n - 1) ?: throw ErrorCatalog.error("photo_not_found", "No tour photo $n; this tour has ${t.shots.size}.")
            val bytes = runCatching { app.contentResolver.openInputStream(shot.uri)?.use { it.readBytes() } }.getOrNull()
                ?: throw ErrorCatalog.error("photo_not_found", "That photo could not be opened (deleted?).")
            val img = ImageUtil.prepare(bytes, "image/jpeg", Sizing.resolve({ arg(it) }, "agent"))
            if (wantsRaw()) rawReply(img, mapOf("X-Bridgit-Taken-At" to isoAt(shot.at)))
            else Reply.Json(envelope(img, isoAt(shot.at), "taken_at", null).put("source", "tour").put("n", n))
        }
        endpoint(Endpoints.TOUR_END) {
            // One step: a tour the owner replaced meanwhile (another agent's) is never ended by this request.
            val t = app.tours.endIfOwned(agent!!.id, query("tour_id")?.takeIf { it.isNotBlank() }, "ended by $agentName")
                ?: throw ErrorCatalog.error("not_found", "You have no tour running.")
            Reply.Json(JSONObject().put("ended", t.id))
        }
    }
}
