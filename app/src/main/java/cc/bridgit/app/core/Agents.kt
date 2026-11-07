package cc.bridgit.app.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** How an agent reaches the phone (6.5). Relay is reserved until Q10. */
enum class Connection(val id: String, val label: String) {
    DIRECT("direct", "Tailscale"), USERSPACE("userspace", "Userspace"), RELAY("relay", "Relay"),
    /** An installed applet's own principal (AppletInstances): it holds the applet's permissions; its key is never usable. */
    APPLET("applet", "Installed applet");
    companion object { fun of(id: String?) = values().firstOrNull { it.id == id } ?: DIRECT }
}

/**
 * The two kinds of extra trust the owner can give one agent (AGT-15, replaces the single Developer switch).
 * [FIXER]: Bridgit Fixer Mode, for agents that work on Bridgit: every bug report with its screenshots,
 * context and log, moving reports, attaching images. [SUPER]: Agent Super Powers, full control of the phone:
 * ADB shell and grants, changing settings, and reading every agent's log.
 */
enum class Power(val id: String, val label: String, val reason: String) {
    FIXER("fixer_mode", "Bridgit Fixer Mode", "fixer_mode_off"),
    SUPER("super_powers", "Agent Super Powers", "super_powers_off"),
}

/** The agent record, AGT-01. `caps` is an explicit grant set; null preserves unrestricted legacy access. */
data class Agent(
    val id: String,
    val name: String,
    val keyHash: String,
    val keySalt: String,
    val keyPrefix: String,
    val createdAt: Long,
    val lastUsedAt: Long = 0,
    val lastSourceIp: String? = null,
    val requestCount: Long = 0,
    val connection: Connection = Connection.DIRECT,
    val notes: String = "",
    /** Bridgit Fixer Mode (Power.FIXER). */
    val fixer: Boolean = false,
    /** Agent Super Powers (Power.SUPER). */
    val superPowers: Boolean = false,
    val keyCreatedAt: Long = createdAt,
    /** The API revision this agent last read about (guide or changes); 0 = never (API-14). */
    val knownApiRevision: Int = 0,
    /** What the agent says it runs on (POST /v1/me, AGT-16): the model and the program around it. Unverified. */
    val model: String? = null,
    val harness: String? = null,
    val modelReportedAt: Long = 0,
    val caps: Set<String>? = null,
    /** Owner chose maximum control, including capabilities added by later updates. */
    val maximumControl: Boolean = false,
    /** Paused by the owner (Agents tab): the key still matches, but every request is refused until resumed. */
    val paused: Boolean = false,
    /**
     * Device lock: the Tailscale addresses this agent's key is accepted from (a node has a 100.x and an fd7a: one).
     * Empty = not yet bound; the first direct request binds it. Cleared when the key is rotated.
     */
    val boundIps: List<String> = emptyList(),
    /** Networks a relay agent has called from ("AS13335 US"), so a new one can be reported. Newest last, capped. */
    val knownPlaces: List<String> = emptyList(),
) {
    /** status and feedback are always allowed: an agent with trimmed permissions can still say what's wrong. */
    fun allows(capability: String): Boolean = capability in ALWAYS_ALLOWED || caps == null || capability in caps

    fun has(p: Power): Boolean = when (p) { Power.FIXER -> fixer; Power.SUPER -> superPowers }

    /** "Claude Opus 5.5 · Claude Code", or null until the agent reports it. */
    val modelLabel: String? get() = model?.let { m -> harness?.let { "$m · $it" } ?: m }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("key_hash", keyHash).put("key_salt", keySalt)
        .put("key_prefix", keyPrefix).put("created_at", createdAt).put("last_used_at", lastUsedAt)
        .put("last_source_ip", lastSourceIp ?: JSONObject.NULL).put("request_count", requestCount)
        .put("connection", connection.id).put("notes", notes)
        .put("fixer_mode", fixer).put("super_powers", superPowers).put("maximum_control", maximumControl)
        .put("key_created_at", keyCreatedAt).put("known_api_revision", knownApiRevision).put("caps", caps?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL)
        .put("model", model ?: JSONObject.NULL).put("harness", harness ?: JSONObject.NULL).put("model_reported_at", modelReportedAt)
        .put("paused", paused).put("bound_ips", JSONArray(boundIps)).put("known_places", JSONArray(knownPlaces))

    companion object {
        /** Capabilities no grant list can take away: the status check, and filing and following bug reports. */
        val ALWAYS_ALLOWED = setOf("status", "feedback", "captures", "outbound")

        fun fromJson(o: JSONObject) = Agent(
            id = o.getString("id"), name = o.getString("name"), keyHash = o.getString("key_hash"),
            keySalt = o.getString("key_salt"), keyPrefix = o.optString("key_prefix", ""),
            createdAt = o.getLong("created_at"), lastUsedAt = o.optLong("last_used_at", 0),
            lastSourceIp = o.optString("last_source_ip", "").ifEmpty { null }.takeIf { !o.isNull("last_source_ip") },
            requestCount = o.optLong("request_count", 0), connection = Connection.of(o.optString("connection")),
            notes = o.optString("notes", ""),
            // Before the split, one Developer switch gave both; an agent that had it keeps both.
            fixer = if (o.has("fixer_mode")) o.optBoolean("fixer_mode") else o.optBoolean("developer", false),
            superPowers = if (o.has("super_powers")) o.optBoolean("super_powers") else o.optBoolean("developer", false),
            keyCreatedAt = o.optLong("key_created_at", o.getLong("created_at")),
            knownApiRevision = o.optInt("known_api_revision", 0),
            model = o.optString("model", "").takeIf { it.isNotEmpty() && !o.isNull("model") },
            harness = o.optString("harness", "").takeIf { it.isNotEmpty() && !o.isNull("harness") },
            modelReportedAt = o.optLong("model_reported_at", 0),
            caps = o.optJSONArray("caps")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
            maximumControl = o.optBoolean("maximum_control", false),
            paused = o.optBoolean("paused", false),
            boundIps = o.optJSONArray("bound_ips")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                // Agents from before the device lock are locked to the Tailscale address they last used.
                ?: listOfNotNull(o.optString("last_source_ip", "").takeIf { !o.isNull("last_source_ip") && Net.isTailnet(it) }?.let(Net::normalise)),
            knownPlaces = o.optJSONArray("known_places")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        )
    }
}

/** A key that no longer works, kept 7 days so the agent learns why (AGT-12). Holds only the hash. */
data class Tombstone(val agentId: String, val agentName: String, val keyHash: String, val keySalt: String,
                     val reason: String, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("agent_id", agentId).put("agent_name", agentName)
        .put("key_hash", keyHash).put("key_salt", keySalt).put("reason", reason).put("at", at)
    companion object {
        fun fromJson(o: JSONObject) = Tombstone(o.getString("agent_id"), o.getString("agent_name"),
            o.getString("key_hash"), o.getString("key_salt"), o.getString("reason"), o.getLong("at"))
    }
}

/** A model name an agent may report: 1-60 printable characters, e.g. claude-opus-5-5. */
fun validModel(m: String) = m.isNotEmpty() && m.length <= 60 && m.none { it.isISOControl() }
/** A harness name: 1-40 printable characters, e.g. Claude Code. */
fun validHarness(h: String) = h.isNotEmpty() && h.length <= 40 && h.none { it.isISOControl() }

/** Where agents are persisted. Android uses SharedPreferences; tests use memory. */
interface AgentStorage {
    fun load(): String?
    fun save(json: String)
}

sealed class AuthResult {
    data class Ok(val agent: Agent) : AuthResult()
    /** Key matches a rotated or removed agent; [reason] is "key_rotated" or "agent_removed". */
    data class Revoked(val reason: String, val agentName: String) : AuthResult()
    object Invalid : AuthResult()
}

/**
 * All agents, cached in memory because auth reads them on every request.
 * Writes are synchronised and persisted immediately; per-request usage counters
 * ([recordUse]) are persisted at most every few seconds.
 */
class AgentRegistry(private val storage: AgentStorage, private val clock: () -> Long = System::currentTimeMillis,
                    private val rnd: SecureRandom = SecureRandom()) {
    @Volatile private var agents: List<Agent> = emptyList()
    @Volatile private var tombstones: List<Tombstone> = emptyList()
    private var lastFlush = 0L
    private val listeners = mutableListOf<() -> Unit>()

    /** FBK-15: called with the agent id after its key is rotated or the agent is removed, so its team backend key goes too. */
    @Volatile var onKeyRetired: ((String) -> Unit)? = null

    /** First authenticated use of a new or rotated key, independent of any setup screen. */
    @Volatile var onFirstUse: ((Agent) -> Unit)? = null

    init { load() }

    fun all(): List<Agent> = agents
    fun get(id: String): Agent? = agents.firstOrNull { it.id == id }
    fun byName(name: String): Agent? = agents.firstOrNull { it.name.equals(name, ignoreCase = true) }
    val count get() = agents.size

    fun addListener(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(listeners) { listeners -= l }
    private fun changed() = synchronized(listeners) { listeners.toList() }.forEach { it() }

    /** Returns an error sentence for a proposed name, or null if it's fine. */
    fun checkName(name: String, exceptId: String? = null): String? = when {
        !NAME_RE.matches(name) -> "Use letters, numbers, spaces, - or _ (1 to 24)."
        agents.any { it.id != exceptId && it.name.equals(name, ignoreCase = true) } ->
            "You already have a control agent called ${agents.first { it.name.equals(name, true) }.name}."
        exceptId == null && agents.count { it.connection != Connection.APPLET } >= MAX_AGENTS -> "Bridgit holds up to $MAX_AGENTS control agents. Remove one to add another."
        else -> null
    }

    /** Adds an agent and returns it with its new key. The key is never stored. */
    @Synchronized
    fun add(name: String, connection: Connection, limited: Boolean = true, beforeSave: (Agent) -> Unit = {}): Pair<Agent, String> {
        val n = name.trim()
        // Installed applets and a satellite's return link don't use up the owner's agent slots.
        checkName(n)?.takeIf { (connection != Connection.APPLET && limited) || !it.startsWith("Bridgit holds") }?.let { throw IllegalArgumentException(it) }
        val key = Keys.generate(rnd)
        val salt = Keys.newSalt(rnd)
        val now = clock()
        val agent = Agent(newId(), n, Keys.hash(salt, key), salt, Keys.displayPrefix(key), now, connection = connection)
        beforeSave(agent)
        agents = agents + agent
        persist(); changed()
        return agent to key
    }

    /** New key for an agent. The old key stops working now and gets "key_rotated" for 7 days. */
    @Synchronized
    fun rotate(id: String): Pair<Agent, String> {
        val old = get(id) ?: throw IllegalArgumentException("No such control agent")
        val key = Keys.generate(rnd)
        val salt = Keys.newSalt(rnd)
        val now = clock()
        val updated = old.copy(keyHash = Keys.hash(salt, key), keySalt = salt, keyPrefix = Keys.displayPrefix(key),
            keyCreatedAt = now, lastUsedAt = 0, boundIps = emptyList())
        tombstones = tombstones + Tombstone(old.id, old.name, old.keyHash, old.keySalt, "key_rotated", now)
        agents = agents.map { if (it.id == id) updated else it }
        persist(); changed()
        runCatching { onKeyRetired?.invoke(id) }
        return updated to key
    }

    @Synchronized
    fun remove(id: String): Agent? {
        val old = get(id) ?: return null
        tombstones = tombstones + Tombstone(old.id, old.name, old.keyHash, old.keySalt, "agent_removed", clock())
        agents = agents.filterNot { it.id == id }
        persist(); changed()
        runCatching { onKeyRetired?.invoke(id) }
        return old
    }

    @Synchronized
    fun update(id: String, f: (Agent) -> Agent): Agent? {
        val old = get(id) ?: return null
        val proposed = f(old)
        // Narrowing an owner grant exits durable maximum mode; an update must never put it back.
        val narrowed = (proposed.caps != null && (old.caps == null || !proposed.caps.containsAll(old.caps))) ||
            (old.fixer && !proposed.fixer)
        val new = if (old.maximumControl && narrowed) proposed.copy(maximumControl = false) else proposed
        agents = agents.map { if (it.id == id) new else it }
        persist(); changed()
        return new
    }

    /** Checks a presented key against every agent in constant time per agent, then against tombstones. */
    fun authenticate(candidate: String?): AuthResult {
        if (!Keys.isWellFormed(candidate)) return AuthResult.Invalid
        val key = candidate!!
        var match: Agent? = null
        for (a in agents) if (a.connection != Connection.APPLET && Keys.matches(a.keySalt, a.keyHash, key)) match = a
        if (match != null) return AuthResult.Ok(match)
        val cutoff = clock() - TOMBSTONE_MS
        for (t in tombstones) if (t.at >= cutoff && Keys.matches(t.keySalt, t.keyHash, key))
            return AuthResult.Revoked(t.reason, t.agentName)
        return AuthResult.Invalid
    }

    /**
     * Device lock check for a direct request from [sourceIp] (already normalised, see [Net.normalise]).
     * An unbound agent is bound to [sourceIp] now. Returns false when the agent is bound to other addresses.
     */
    @Synchronized
    fun checkDevice(id: String, sourceIp: String): Boolean {
        val a = get(id) ?: return false
        if (sourceIp in a.boundIps) return true
        if (a.boundIps.isNotEmpty()) return false
        agents = agents.map { if (it.id == id) it.copy(boundIps = listOf(sourceIp)) else it }
        persist(); changed()
        return true
    }

    /** The owner allowed [ip] for this agent (from the new-device alert). Keeps at most [MAX_BOUND] addresses. */
    @Synchronized
    fun allowDevice(id: String, ip: String): Agent? = update(id) { it.copy(boundIps = (it.boundIps - ip + ip).takeLast(MAX_BOUND)) }

    /** The owner unlocked the agent: the next device that uses the key becomes its device. */
    @Synchronized
    fun unlockDevice(id: String): Agent? = update(id) { it.copy(boundIps = emptyList()) }

    /**
     * Records the network a relay request came from. Returns true when it is new for an agent that already had
     * one (worth telling the owner); the first network is only remembered.
     */
    @Synchronized
    fun notePlace(id: String, place: String): Boolean {
        val a = get(id) ?: return false
        if (place in a.knownPlaces) return false
        agents = agents.map { if (it.id == id) it.copy(knownPlaces = (it.knownPlaces + place).takeLast(MAX_PLACES)) else it }
        persist(); changed()
        return a.knownPlaces.isNotEmpty()
    }

    /** Counts a request. Persisted at most every [FLUSH_MS] so a busy agent doesn't rewrite prefs per call. */
    @Synchronized
    fun recordUse(id: String, sourceIp: String) {
        val firstUse = get(id)?.takeIf { it.lastUsedAt == 0L }
        val now = clock()
        agents = agents.map {
            if (it.id == id) it.copy(lastUsedAt = now, lastSourceIp = sourceIp, requestCount = it.requestCount + 1) else it
        }
        // Persist first use immediately so reopening the app does not replay the cue.
        if (firstUse != null || now - lastFlush > FLUSH_MS) { persist(); lastFlush = now }
        changed()
        if (firstUse != null) runCatching { onFirstUse?.invoke(get(id) ?: return) }
    }

    /**
     * The model (and harness) an agent says it runs on, from POST /v1/me or the X-Bridgit-Model header on any call.
     * Agents switch models, so the latest report wins. Returns the updated agent only when something changed;
     * null when unchanged, unknown or not a valid name (a bad header never fails the call).
     */
    @Synchronized
    fun reportModel(id: String, model: String?, harness: String?): Agent? {
        val m = model?.trim()?.takeIf { validModel(it) } ?: return null
        val h = harness?.trim()?.takeIf { validHarness(it) }
        val old = get(id) ?: return null
        val newHarness = h ?: old.harness // a model-only report keeps the known harness
        if (old.model == m && old.harness == newHarness) {
            agents = agents.map { if (it.id == id) it.copy(modelReportedAt = clock()) else it }
            return null
        }
        return update(id) { it.copy(model = m, harness = newHarness, modelReportedAt = clock()) }
    }

    @Synchronized
    fun flush() = persist()

    private fun newId(): String {
        while (true) {
            val id = ByteArray(6).also { rnd.nextBytes(it) }.toHex()
            if (get(id) == null) return id
        }
    }

    private fun load() {
        val raw = storage.load() ?: return
        runCatching {
            val o = JSONObject(raw)
            val a = o.optJSONArray("agents") ?: JSONArray()
            agents = (0 until a.length()).map { Agent.fromJson(a.getJSONObject(it)) }
            val t = o.optJSONArray("tombstones") ?: JSONArray()
            tombstones = (0 until t.length()).map { Tombstone.fromJson(t.getJSONObject(it)) }
        }
    }

    private fun persist() {
        val cutoff = clock() - TOMBSTONE_MS
        tombstones = tombstones.filter { it.at >= cutoff }
        val o = JSONObject()
            .put("agents", JSONArray().apply { agents.forEach { put(it.toJson()) } })
            .put("tombstones", JSONArray().apply { tombstones.forEach { put(it.toJson()) } })
        storage.save(o.toString())
    }

    companion object {
        const val MAX_AGENTS = 16
        const val MAX_BOUND = 4
        const val MAX_PLACES = 8
        const val TOMBSTONE_MS = 7L * 24 * 3600 * 1000
        const val FLUSH_MS = 5_000L
        val NAME_RE = Regex("^[A-Za-z0-9][A-Za-z0-9 _-]{0,23}$")
        val SUGGESTIONS = listOf("Muse", "Grok", "Claude", "Cursor", "ChatGPT", "Gemini", "Instinct")
    }
}
