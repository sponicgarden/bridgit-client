package cc.bridgit.app.interact

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One event for an agent (EVT-01, EVT-02). [id] is the cursor. */
data class Event(val id: Long, val agentId: String, val type: String, val at: Long, val data: JSONObject) {
    fun toJson(iso: (Long) -> String) = JSONObject().put("id", id.toString()).put("type", type).put("at", iso(at)).put("data", data)
}

/**
 * Interactions and events (8.13, section 9) in `interactions.db`, separate from the log and feedback
 * stores. Every read is scoped to one agent by the caller; the store never returns another agent's rows
 * through the agent-facing methods ([getFor], [listFor], [eventsFor]).
 */
class InteractionStore(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val db = Helper(context).writableDatabase
    private val dir = File(context.filesDir, "interactions").apply { mkdirs() }
    private val _changed = MutableStateFlow(0L)
    private val listeners = mutableListOf<(Interaction, String) -> Unit>()

    /** Bumps on every change (new interaction, status change, new event). Waits watch it. */
    val changed: StateFlow<Long> get() = _changed

    /** (interaction, what happened) for the UI and notifications: created, seen, answered, dismissed, expired, cancelled. */
    fun addListener(l: (Interaction, String) -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: (Interaction, String) -> Unit) = synchronized(listeners) { listeners -= l }
    private fun tell(i: Interaction, what: String) = synchronized(listeners) { listeners.toList() }.forEach { runCatching { it(i, what) } }

    @Synchronized
    fun create(agentId: String, agent: String, d: Draft, imageJpeg: ByteArray?): Interaction {
        expireDue()
        val now = clock()
        if (d.stack != null && count("agent_id = ? AND stack = ? AND status != 'cancelled'", agentId, d.stack) >= InteractionRules.STACK_MAX)
            throw InteractionError("invalid_parameter", "Stack \"${d.stack}\" already has ${InteractionRules.STACK_MAX} cards. Use a new stack id for a new series.")
        // #0005: an open stack counts once, and a card joining an open stack never hits the cap.
        val joinsOpenStack = d.stack != null && count("agent_id = ? AND stack = ? AND status IN ('pending','seen')", agentId, d.stack) > 0
        when (d.kind) {
            IKind.QUESTION, IKind.HTML -> if (!joinsOpenStack && openQuestionCount(agentId) >= InteractionRules.OPEN_QUESTIONS_PER_AGENT)
                throw InteractionError("too_many_open", "You have ${InteractionRules.OPEN_QUESTIONS_PER_AGENT} open questions (a stack counts as one). Wait for answers or cancel one.")
            else -> if (count("agent_id = ? AND kind IN ('notice','text') AND created_at > ?", agentId, (now - 3_600_000L).toString()) >= InteractionRules.NOTICES_PER_HOUR)
                throw InteractionError("too_many_open", "At most ${InteractionRules.NOTICES_PER_HOUR} notices an hour.")
        }
        db.beginTransaction()
        val id: Long
        var imageName: String? = null
        try {
            id = db.insertOrThrow("interactions", null, ContentValues().apply {
                put("agent_id", agentId); put("agent", agent); put("kind", d.kind.id); put("title", d.title); put("text", d.text)
                put("controls_json", JSONArray(d.controls.map { it.toJson() }).toString()); put("html", d.html)
                put("no_takeover", if (d.noTakeover) 1 else 0)
                put("follow_up", if (d.followUp) 1 else 0)
                put("presentation", d.presentation.id)
                put("priority", d.priority); put("speak", if (d.speak) 1 else 0); put("status", IStatus.PENDING.id)
                put("created_at", now); put("expires_at", now + d.expiresInS * 1000)
                if (d.kind == IKind.TEXT) { put("stream_text", TextPanelRules.keepTail(d.text ?: "")); put("stream_done", 0) }
                if (d.stack != null) {
                    put("stack", d.stack)
                    put("stack_index", d.stackIndex ?: nextStackIndex(agentId, d.stack))
                    d.swipe?.let { put("swipe_json", it.toJson().toString()) }
                }
            })
            if (imageJpeg != null) {
                imageName = "$id.jpg"
                File(dir, imageName).writeBytes(imageJpeg)
                db.update("interactions", ContentValues().apply { put("image", imageName) }, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            imageName?.let { File(dir, it).delete() }
            throw e
        } finally { db.endTransaction() }
        bump()
        return get(id)!!.also { tell(it, "created") }
    }

    /** Open questions and cards of [agentId], a stack counted once (#0005). */
    private fun openQuestionCount(agentId: String): Int = db.rawQuery(
        "SELECT COUNT(DISTINCT COALESCE('s:' || stack, 'i:' || id)) FROM interactions WHERE agent_id = ? AND kind IN ('question','html') AND status IN ('pending','seen')",
        arrayOf(agentId)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun nextStackIndex(agentId: String, stack: String): Int = db.rawQuery(
        "SELECT MAX(stack_index) FROM interactions WHERE agent_id = ? AND stack = ?", arrayOf(agentId, stack))
        .use { if (it.moveToFirst() && !it.isNull(0)) it.getInt(0) + 1 else 0 }

    /**
     * #0005: every card in [i]'s stack in order (stack_index, then post order), cancelled ones left out;
     * just [i] when it isn't stacked. Owner-facing.
     */
    fun stackOf(i: Interaction): List<Interaction> {
        if (i.stack == null) return listOf(i)
        expireDue()
        return db.query("interactions", null, "agent_id = ? AND stack = ? AND status != 'cancelled'", arrayOf(i.agentId, i.stack), null, null,
            "stack_index ASC, id ASC").use { c -> buildList { while (c.moveToNext()) add(row(c)) } }.ifEmpty { listOf(i) }
    }

    /** #0005: the owner swiped a waiting `swipe_answer` card; answered like Bridgit.respond with {swipe, label}. */
    @Synchronized
    fun answerSwipe(id: Long, dir: String): Interaction {
        val i = get(id) ?: throw InteractionError("not_found", "No interaction $id.")
        val s = i.swipe ?: throw InteractionError("invalid_parameter", "This card isn't answered by swiping.")
        if (dir != "left" && dir != "right") throw InteractionError("invalid_parameter", "Swipe left or right.")
        return answerCard(id, SwipeAnswer.data(dir, s))
    }

    /** #0005: the owner closed a whole stack from the inbox; every card still waiting is dismissed. */
    @Synchronized
    fun dismissStack(i: Interaction): List<Interaction> = stackOf(i).filter { it.status.open || it.status == IStatus.EXPIRED }.mapNotNull { dismiss(it.id) }

    /** Agent-facing: the interaction only if it belongs to [agentId]. */
    fun getFor(agentId: String, id: Long): Interaction? = get(id)?.takeIf { it.agentId == agentId }

    /** Owner-facing (phone UI): any interaction. */
    fun get(id: Long): Interaction? {
        expireDue()
        return db.query("interactions", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c -> if (c.moveToFirst()) row(c) else null }
    }

    fun listFor(agentId: String, status: IStatus?, limit: Int): List<Interaction> = list("agent_id = ?" + (status?.let { " AND status = ?" } ?: ""),
        listOfNotNull(agentId, status?.id).toTypedArray(), limit)

    /** Owner-facing list for the Interactions tab: open ones first, then newest. */
    fun listAll(limit: Int = 300): List<Interaction> {
        expireDue()
        return db.query("interactions", null, null, null, null, null,
            "CASE WHEN status IN ('pending','seen') OR ($WAITING_QUESTION) THEN 0 ELSE 1 END, id DESC", limit.toString()).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }
    }

    /** Questions waiting for an answer (expired ones too) plus notices not yet seen: the tab badge. */
    fun unreadCount(): Int = db.rawQuery(
        // #0005: a stack of cards is one inbox row, so it counts once.
        "SELECT COUNT(DISTINCT COALESCE('s:' || agent_id || '/' || stack, 'i:' || id)) FROM interactions WHERE ($WAITING_QUESTION) OR (kind IN ('notice','text') AND status = 'pending')",
        null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Questions and cards the owner hasn't answered or closed, newest first, including expired ones (Home). */
    fun waitingQuestions(limit: Int = 100): List<Interaction> = list(WAITING_QUESTION, emptyArray(), limit)

    private fun list(where: String, args: Array<String>, limit: Int): List<Interaction> {
        expireDue()
        return db.query("interactions", null, where, args, null, null, "id DESC", limit.toString()).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }
    }

    /** The owner opened or saw it (a notice becomes "seen"; a question stays answerable). */
    @Synchronized
    fun markSeen(id: Long): Interaction? {
        val i = get(id) ?: return null
        if (i.status != IStatus.PENDING) return i
        update(id, ContentValues().apply { put("status", IStatus.SEEN.id); put("seen_at", clock()) })
        event(i.agentId, "interaction.seen", JSONObject().put("interaction_id", id))
        return get(id)!!.also { tell(it, "seen") }
    }

    /** Explicit reminder for the caller's open card; preserve its seen/answer state. */
    @Synchronized
    fun renotify(agentId: String, id: Long): Interaction {
        expireDue()
        val i = getFor(agentId, id) ?: throw InteractionError("not_found", "No interaction $id of yours.")
        if (!i.status.open) throw InteractionError("conflict", "This card is ${i.status.id}; create a new one.")
        event(i.agentId, "interaction.renotified", JSONObject().put("interaction_id", id))
        tell(i, "renotified")
        return i
    }

    /** The owner answered on the phone or in the notification. */
    @Synchronized
    fun answer(id: Long, button: String?, values: Map<String, String>, resubmission: Boolean = false): Interaction {
        val i = get(id) ?: throw InteractionError("not_found", "No interaction $id.")
        if (!i.status.acceptsAnswer(resubmission)) throw InteractionError("invalid_parameter", "This ${i.kind.id} is already ${i.status.id}.")
        if (i.kind != IKind.QUESTION) throw InteractionError("invalid_parameter", "Only questions take answers.")
        val ans = InteractionRules.checkAnswer(i.controls, button, values)
        val now = clock()
        update(id, ContentValues().apply { put("status", IStatus.ANSWERED.id); put("answer_json", ans.toString()); put("answered_at", now); put("closed_at", now) })
        event(i.agentId, "interaction.answered", JSONObject().put("interaction_id", id).put("answer", ans).apply {
            if (i.status == IStatus.ANSWERED) put("resubmission", true).put("previous_answer", i.answer)
            if (i.status == IStatus.EXPIRED) put("late", true)
            followUp(i)
        })
        return get(id)!!.also { tell(it, "answered") }
    }

    /**
     * An HTML card's page called Bridgit.respond(json) (CAP-34). Completes it like a button answer: status
     * answered, `interaction.answered` with `answer.data`, waits return. Explicit phone resubmission also accepts answered cards.
     */
    @Synchronized
    fun answerCard(id: Long, data: Any, resubmission: Boolean = false): Interaction {
        val i = get(id) ?: throw InteractionError("not_found", "No interaction $id.")
        if (i.kind != IKind.HTML) throw InteractionError("invalid_parameter", "Only html cards take a card answer.")
        if (!i.status.acceptsAnswer(resubmission)) throw InteractionError("conflict", "This card is already ${i.status.id}.")
        val ans = JSONObject().put("button", JSONObject.NULL).put("values", JSONObject()).put("data", data)
        val now = clock()
        update(id, ContentValues().apply { put("status", IStatus.ANSWERED.id); put("answer_json", ans.toString()); put("answered_at", now); put("closed_at", now) })
        event(i.agentId, "interaction.answered", JSONObject().put("interaction_id", id).put("answer", ans).apply {
            if (i.status == IStatus.ANSWERED) put("resubmission", true).put("previous_answer", i.answer)
            if (i.status == IStatus.EXPIRED) put("late", true)
            if (i.stack != null) put("stack", i.stack).put("stack_index", i.stackIndex)
            followUp(i)
        })
        return get(id)!!.also { tell(it, "answered") }
    }

    /**
     * D26: adds [append] to the agent's own text panel and keeps the last 200 KB; [done] finishes it.
     * not_found for another agent's or a missing one, conflict once it is finished, closed or cancelled.
     * No listener call per append (only [changed] bumps), so notifications stay quiet.
     */
    @Synchronized
    fun appendText(agentId: String, id: Long, append: String, done: Boolean): Interaction {
        val i = getFor(agentId, id) ?: throw InteractionError("not_found", "No interaction $id of yours.")
        if (i.kind != IKind.TEXT) throw InteractionError("invalid_parameter", "Interaction $id is a ${i.kind.id}, not a text panel.")
        if (!i.status.open) throw InteractionError("conflict", "This text panel is ${i.status.id}; create a new one.")
        if (i.streamDone) throw InteractionError("conflict", "This text panel is finished (done was sent); create a new one.")
        val text = if (append.isEmpty()) i.streamText else TextPanelRules.keepTail(i.streamText + append)
        update(id, ContentValues().apply { put("stream_text", text); if (done) put("stream_done", 1) })
        return get(id)!!.also { if (done) tell(it, "stream_done") }
    }

    /** The newest open text panel (D26), for the phone's panel when the owner hasn't picked one. */
    fun latestOpenText(): Interaction? {
        expireDue()
        return db.query("interactions", null, "kind = 'text' AND status IN ('pending','seen')", null, null, null, "id DESC", "1")
            .use { c -> if (c.moveToFirst()) row(c) else null }
    }

    /** The owner closed it (D26); the agent is told `dismissed`. */
    @Synchronized
    fun dismiss(id: Long): Interaction? = close(id, IStatus.DISMISSED, "interaction.dismissed")

    /** The agent cancelled it (DELETE). */
    @Synchronized
    fun cancel(agentId: String, id: Long): Interaction {
        val i = getFor(agentId, id) ?: throw InteractionError("not_found", "No interaction $id of yours.")
        return close(i.id, IStatus.CANCELLED, null) ?: i
    }

    /**
     * AGT-12: the owner removed [agentId]. Its questions and cards still waiting for the owner (open or
     * expired) and its open notices and text panels become cancelled, so their notifications, the Home banner
     * and the badge go away. No event: nobody can receive it any more. Returns how many were closed.
     */
    @Synchronized
    fun closeAllFor(agentId: String): Int {
        val now = clock()
        // Not get()/expireDue(): that could emit interaction.expired to the removed agent.
        val ids = db.query("interactions", arrayOf("id"), "agent_id = ? AND (status IN ('pending','seen') OR ($WAITING_QUESTION))",
            arrayOf(agentId), null, null, null).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        for (id in ids) {
            update(id, ContentValues().apply { put("status", IStatus.CANCELLED.id); put("closed_at", now) })
            row(id)?.let { tell(it, IStatus.CANCELLED.id) }
        }
        return ids.size
    }

    /** Agents that still have something open or waiting in Bridgit (to sweep ones removed while the fix wasn't there). */
    fun agentsWithOpen(): Set<String> = db.rawQuery("SELECT DISTINCT agent_id FROM interactions WHERE status IN ('pending','seen') OR ($WAITING_QUESTION)", null)
        .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    /** follow_up cards: the phone keeps the card screen open this long for the agent's next card. */
    private fun JSONObject.followUp(i: Interaction) {
        if (i.followUp && !i.noTakeover) put("follow_up_seconds", NotifRules.FOLLOW_UP_HOLD_MS / 1000)
    }

    private fun close(id: Long, to: IStatus, eventType: String?): Interaction? {
        val i = get(id) ?: return null
        // An expired question still waits for the owner, so the owner can close it; the agent can't (it already got expired).
        val ownerClosesExpired = to == IStatus.DISMISSED && i.status == IStatus.EXPIRED && i.kind in setOf(IKind.QUESTION, IKind.HTML)
        if (!i.status.open && !ownerClosesExpired) return i
        update(id, ContentValues().apply { put("status", to.id); put("closed_at", clock()) })
        eventType?.let { event(i.agentId, it, JSONObject().put("interaction_id", id).apply { if (to == IStatus.DISMISSED) followUp(i) }) }
        return get(id)!!.also { tell(it, to.id) }
    }

    /** Moves overdue open interactions to expired (CAP-31) and prunes old rows and events. Cheap; called on every read. */
    @Synchronized
    fun expireDue() {
        val now = clock()
        val due = db.query("interactions", arrayOf("id", "agent_id"), "status IN ('pending','seen') AND expires_at < ?", arrayOf(now.toString()), null, null, null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }
        for ((id, agentId) in due) {
            update(id, ContentValues().apply { put("status", IStatus.EXPIRED.id); put("closed_at", now) })
            event(agentId, "interaction.expired", JSONObject().put("interaction_id", id))
            row(id)?.let { tell(it, "expired") }
        }
        if (now - lastPrune > 3_600_000L) {
            lastPrune = now
            val old = db.query("interactions", arrayOf("id", "image"), "status NOT IN ('pending','seen') AND closed_at < ?",
                arrayOf((now - 30L * 86_400_000).toString()), null, null, null).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }
            old.forEach { (id, img) -> img?.let { File(dir, it).delete() }; db.delete("interactions", "id = ?", arrayOf(id.toString())) }
            // Events are also pruned on every insert (count and age); this catches age when nothing new arrives.
            db.delete("events", "at < ?", arrayOf((now - EVENT_KEEP_MS).toString()))
        }
    }
    private var lastPrune = 0L
    private var lastEventAgePrune = 0L

    // ---- events (section 9) -----------------------------------------------------------------------

    /**
     * Adds an event for [agentId]. Retention (7 days, the newest [EVENTS_PER_AGENT] per agent) is enforced
     * here on every insert, so a tour- or events-only workload can't grow the table without bound.
     */
    @Synchronized
    fun event(agentId: String, type: String, data: JSONObject): Event {
        val now = clock()
        val id = db.insert("events", null, ContentValues().apply { put("agent_id", agentId); put("type", type); put("at", now); put("data", data.toString()) })
        // Count cap for this agent: everything older than its 1,000th newest event goes (uses the (agent_id, id) index).
        db.execSQL("DELETE FROM events WHERE agent_id = ? AND id <= (SELECT id FROM events WHERE agent_id = ? ORDER BY id DESC LIMIT 1 OFFSET $EVENTS_PER_AGENT)",
            arrayOf(agentId, agentId))
        // Age limit for everyone, at most once a minute.
        if (now - lastEventAgePrune > 60_000L) {
            lastEventAgePrune = now
            db.delete("events", "at < ?", arrayOf((now - EVENT_KEEP_MS).toString()))
        }
        bump()
        // Callbacks (EVT-06): listeners only enqueue; this runs under the store's lock.
        return Event(id, agentId, type, now, data).also { e -> synchronized(eventListeners) { eventListeners.toList() }.forEach { runCatching { it(e) } } }
    }

    private val eventListeners = mutableListOf<(Event) -> Unit>()

    /** Called with every new event, on the inserting thread. Must not block. */
    fun addEventListener(l: (Event) -> Unit) = synchronized(eventListeners) { eventListeners += l }

    /**
     * TOUR-02: once tour [tourId] ends, its stored tick and question events lose location, compass and photo
     * links ([TourPrivacy.scrub]); the shells (type, tour_id, time) stay so cursors and history still work.
     */
    @Synchronized
    fun scrubTourEvents(agentId: String, tourId: String) {
        val types = cc.bridgit.app.tour.TourPrivacy.EVENT_TYPES.toList()
        val rows = db.query("events", arrayOf("id", "data"),
            "agent_id = ? AND type IN (${types.joinToString(",") { "?" }}) AND data LIKE ?",
            (listOf(agentId) + types + "%$tourId%").toTypedArray(), null, null, null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }
        for ((id, json) in rows) {
            val data = runCatching { JSONObject(json) }.getOrNull()
            if (data == null) { db.delete("events", "id = ?", arrayOf(id.toString())); continue }
            if (data.optString("tour_id") != tourId) continue
            val clean = cc.bridgit.app.tour.TourPrivacy.scrub(data) ?: continue
            db.update("events", ContentValues().apply { put("data", clean.toString()) }, "id = ?", arrayOf(id.toString()))
        }
    }

    /** A process restart ends all in-memory tours. Scrub before the listener can serve history. */
    @Synchronized
    fun recoverInterruptedTours() {
        val rows = db.query("events", arrayOf("agent_id", "type", "data"), "type LIKE 'tour.%'", null, null, null, "id ASC")
            .use { c -> buildList { while (c.moveToNext()) {
                val data = runCatching { JSONObject(c.getString(2)) }.getOrNull()
                if (data != null) add(Triple(c.getString(0), c.getString(1), data))
            } } }
        val tours = rows.mapNotNull { (agent, _, data) -> data.optString("tour_id").takeIf { it.isNotBlank() }?.let { agent to it } }.toSet()
        val ended = rows.filter { it.second == "tour.ended" }.map { it.first to it.third.optString("tour_id") }.toSet()
        tours.forEach { (agent, id) ->
            scrubTourEvents(agent, id)
            if ((agent to id) !in ended) event(agent, "tour.ended", JSONObject().put("tour_id", id).put("reason", "restart"))
        }
    }

    @Synchronized
    fun eventById(id: Long, agentId: String): Event? = db.query("events", null, "id = ? AND agent_id = ?", arrayOf(id.toString(), agentId), null, null, null)
        .use { c -> if (c.moveToFirst()) Event(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), JSONObject(c.getString(4))) else null }

    /** When each agent last called GET /v1/events (memory only): City tour checks the agent is listening. */
    private val polledAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Called at the start and end of every GET /v1/events, so an agent holding a 55 s wait stays "listening". */
    fun notePoll(agentId: String) { polledAt[agentId] = clock() }

    /** When [agentId] last called GET /v1/events since the app started; 0 = never. */
    fun lastPoll(agentId: String): Long = polledAt[agentId] ?: 0L

    /**
     * The agent's events after [sinceId] (oldest first, at most [limit], default 100), or without [sinceId] its newest
     * events (default 50), also oldest first so the last one's id is the cursor to continue from.
     */
    fun eventsFor(agentId: String, sinceId: Long?, types: Set<String>, limit: Int? = null): List<Event> {
        val n = limit ?: if (sinceId == null) 50 else 100
        // Never older than the retention window, even before the next prune has run.
        val where = StringBuilder("agent_id = ? AND at >= ?"); val args = mutableListOf(agentId, (clock() - EVENT_KEEP_MS).toString())
        sinceId?.let { where.append(" AND id > ?"); args += it.toString() }
        if (types.isNotEmpty()) { where.append(" AND type IN (${types.joinToString(",") { "?" }})"); args += types }
        val order = if (sinceId == null) "id DESC" else "id ASC"
        val rows = db.query("events", null, where.toString(), args.toTypedArray(), null, null, order, n.toString())
            .use { c -> buildList { while (c.moveToNext()) add(Event(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), JSONObject(c.getString(4)))) } }
        return if (sinceId == null) rows.reversed() else rows
    }

    fun imageFile(name: String?): File? = name?.let { File(dir, it) }?.takeIf { it.exists() && it.parentFile == dir }

    private fun bump() { _changed.value = _changed.value + 1 }

    private fun update(id: Long, v: ContentValues) { db.update("interactions", v, "id = ?", arrayOf(id.toString())); bump() }

    private fun count(where: String, vararg args: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM interactions WHERE $where", args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun row(id: Long): Interaction? =
        db.query("interactions", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c -> if (c.moveToFirst()) row(c) else null }

    private fun row(c: Cursor): Interaction {
        fun s(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getString(it) }
        fun l(n: String) = c.getColumnIndexOrThrow(n).let { if (c.isNull(it)) null else c.getLong(it) }
        val ctr = JSONArray(s("controls_json") ?: "[]")
        val stack = s("stack")
        val agentId = s("agent_id")!!
        val (size, answered) = if (stack == null) 1 to 0 else db.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN status = 'answered' THEN 1 ELSE 0 END) FROM interactions WHERE agent_id = ? AND stack = ? AND status != 'cancelled'",
            arrayOf(agentId, stack)).use { if (it.moveToFirst()) it.getInt(0).coerceAtLeast(1) to it.getInt(1) else 1 to 0 }
        return Interaction(l("id")!!, s("agent_id")!!, s("agent") ?: "", IKind.of(s("kind"))!!, s("title") ?: "", s("text"), s("image"),
            (0 until ctr.length()).map { Control.fromJson(ctr.getJSONObject(it)) }, s("html"), s("priority") ?: "normal", l("speak") == 1L,
            IStatus.of(s("status"))!!, s("answer_json")?.let { JSONObject(it) }, l("created_at")!!, l("expires_at")!!,
            l("seen_at"), l("answered_at"), l("closed_at"), s("stream_text") ?: "", l("stream_done") == 1L,
            stack, (l("stack_index") ?: 0L).toInt(), size, SwipeAnswer.fromJson(s("swipe_json")), answered, l("no_takeover") == 1L, l("follow_up") == 1L,
            Presentation.of(s("presentation")) ?: Presentation.STANDARD)
    }

    companion object {
        const val EVENTS_PER_AGENT = 1000
        const val EVENT_KEEP_MS = 7L * 86_400_000
        /** A question or card the owner still owes an answer: open, or expired without one (late answers are allowed). */
        const val WAITING_QUESTION = "kind IN ('question','html') AND status IN ('pending','seen','expired')"
    }

    /** Version 5 adds presentation (A0074); version 4 added follow_up; version 3 added the per-call no_takeover flag; version 2 added stack fields. Internal so the migration is unit-tested. */
    internal class Helper(ctx: Context, name: String? = "interactions.db") : SQLiteOpenHelper(ctx, name, null, 5) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE interactions (id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id TEXT NOT NULL, agent TEXT, kind TEXT NOT NULL,
                title TEXT NOT NULL, text TEXT, image TEXT, controls_json TEXT, html TEXT, priority TEXT, speak INTEGER, status TEXT NOT NULL,
                answer_json TEXT, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, seen_at INTEGER, answered_at INTEGER, closed_at INTEGER,
                stream_text TEXT, stream_done INTEGER, stack TEXT, stack_index INTEGER, swipe_json TEXT, no_takeover INTEGER NOT NULL DEFAULT 0,
                follow_up INTEGER NOT NULL DEFAULT 0, presentation TEXT NOT NULL DEFAULT 'standard')""")
            db.execSQL(STACK_INDEX)
            db.execSQL("CREATE INDEX interactions_agent ON interactions(agent_id, id)")
            db.execSQL("CREATE INDEX interactions_status ON interactions(status, expires_at)")
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, agent_id TEXT NOT NULL, type TEXT NOT NULL, at INTEGER NOT NULL, data TEXT NOT NULL)")
            db.execSQL("CREATE INDEX events_agent ON events(agent_id, id)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 5) db.execSQL("ALTER TABLE interactions ADD COLUMN presentation TEXT NOT NULL DEFAULT 'standard'")
            if (oldVersion < 4) db.execSQL("ALTER TABLE interactions ADD COLUMN follow_up INTEGER NOT NULL DEFAULT 0")
            if (oldVersion < 3) db.execSQL("ALTER TABLE interactions ADD COLUMN no_takeover INTEGER NOT NULL DEFAULT 0")
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE interactions ADD COLUMN stack TEXT")
                db.execSQL("ALTER TABLE interactions ADD COLUMN stack_index INTEGER")
                db.execSQL("ALTER TABLE interactions ADD COLUMN swipe_json TEXT")
                db.execSQL(STACK_INDEX)
            }
        }
        private companion object { const val STACK_INDEX = "CREATE INDEX interactions_stack ON interactions(agent_id, stack)" }
    }
}
