package cc.bridgit.app.capture

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Where captures go (SEC-13, owner decision D33). Pure Kotlin, unit-tested.
 *
 * A capture (a photo, a screenshot, a mic recording, a wake-word or sound clip) can go to any mix of
 * three places: **memory** (fetchable through the API for a while, lost on restart), **files** (a copy in
 * the phone's Downloads/Bridgit folder the owner can browse) and **upload** (POSTed to a URL).
 *
 * Who decides: each agent picks for its own captures, through `PUT /v1/captures/storage` or per request,
 * within what the owner allows on the phone ([OwnerPolicy]). The owner can also send every capture to
 * files or to their own upload URL, whatever the agent picked. Nothing changes until someone opts in:
 * clips stay in memory for 10 minutes and photos, screenshots and recordings are only returned.
 */
object CaptureRules {
    const val MEMORY = "memory"
    const val FILES = "files"
    const val UPLOAD = "upload"
    val DESTINATIONS = listOf(MEMORY, FILES, UPLOAD)

    /** Capture kinds, named after the capability that records them. */
    const val PHOTO = "camera"
    const val SCREEN = "screen"
    const val MIC = "mic"
    const val WAKE_CLIP = "wakewords"
    const val SOUND_CLIP = "sounds"
    val KINDS = listOf(PHOTO, SCREEN, MIC, WAKE_CLIP, SOUND_CLIP, "lan_camera", "lan_camera_audio")
    /** Clips are delivered as an event with a clip_url, so they are always kept in memory too. */
    val CLIP_KINDS = setOf(WAKE_CLIP, SOUND_CLIP)

    const val DEFAULT_KEEP_MINUTES = 10
    const val DEFAULT_MAX_KEEP_MINUTES = 24 * 60
    const val MAX_KEEP_MINUTES = 7 * 24 * 60
    /** Memory budget for kept capture bytes; the oldest go first when it's full. */
    const val MEMORY_BUDGET_BYTES = 96L * 1024 * 1024
    const val MAX_MEMORY_ITEMS = 200
    /** Entries listed by GET /v1/captures (memory, file or upload), oldest dropped first. */
    const val MAX_INDEX = 1000
    const val MAX_UPLOAD_HEADERS = 8
    const val MAX_HEADER_VALUE = 4096
    /** Headers an agent may not set on its upload (Bridgit sets them, or they'd break the request). */
    val RESERVED_HEADERS = setOf("host", "content-length", "content-type", "transfer-encoding", "connection")

    /** What the owner allows and adds, set on the phone (captures.* settings, owner only). */
    data class OwnerPolicy(
        val agentFiles: Boolean = true,
        val agentUpload: Boolean = true,
        val maxKeepMinutes: Int = DEFAULT_MAX_KEEP_MINUTES,
        val shareAll: Boolean = false,
        val ownerFiles: Boolean = false,
        val ownerUploadUrl: String? = null,
    ) {
        fun allowed(dest: String) = when (dest) { MEMORY -> true; FILES -> agentFiles; UPLOAD -> agentUpload; else -> false }
    }

    /** One agent's own choice (PUT /v1/captures/storage). [store] null = the defaults. */
    data class AgentPrefs(
        val store: Set<String>? = null,
        val kinds: Set<String>? = null,
        val uploadUrl: String? = null,
        val uploadHeaders: Map<String, String> = emptyMap(),
        val keepMinutes: Int? = null,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("store", store?.let { JSONArray(DESTINATIONS.filter { d -> d in it }) } ?: JSONObject.NULL)
            .put("kinds", kinds?.let { JSONArray(KINDS.filter { k -> k in it }) } ?: JSONObject.NULL)
            .put("upload_url", uploadUrl ?: JSONObject.NULL)
            .put("upload_headers", JSONObject(uploadHeaders))
            .put("keep_minutes", keepMinutes ?: JSONObject.NULL)

        /** What GET shows: header names only, never their values. */
        fun publicJson(): JSONObject = toJson().put("upload_headers", JSONArray(uploadHeaders.keys.sorted()))

        companion object {
            fun fromJson(o: JSONObject): AgentPrefs = AgentPrefs(
                store = o.optJSONArray("store")?.let { a -> (0 until a.length()).map { a.getString(it) }.filter { it in DESTINATIONS }.toSet() },
                kinds = o.optJSONArray("kinds")?.let { a -> (0 until a.length()).map { a.getString(it) }.filter { it in KINDS }.toSet() },
                uploadUrl = o.optString("upload_url").takeIf { it.isNotBlank() && it != "null" },
                uploadHeaders = o.optJSONObject("upload_headers")?.let { h -> h.keys().asSequence().associateWith { h.getString(it) } } ?: emptyMap(),
                keepMinutes = if (o.has("keep_minutes") && !o.isNull("keep_minutes")) o.optInt("keep_minutes") else null,
            )
        }
    }

    /** Where one capture goes. */
    data class Plan(
        val memory: Boolean,
        val files: Boolean,
        val agentUploadUrl: String?,
        val ownerUploadUrl: String?,
        val keepMs: Long,
    ) {
        val kept: Boolean get() = memory || files || agentUploadUrl != null || ownerUploadUrl != null
        fun destinations(): List<String> = listOfNotNull(MEMORY.takeIf { memory }, FILES.takeIf { files },
            UPLOAD.takeIf { agentUploadUrl != null || ownerUploadUrl != null })
    }

    class Refused(val reason: String, message: String) : Exception(message)

    /** Parses a `store` value (array of destinations, or one string, or "none"). Throws [Refused] on bad names. */
    fun parseStore(v: Any?): Set<String>? {
        if (v == null || v == JSONObject.NULL) return null
        val names = when (v) {
            is JSONArray -> (0 until v.length()).map { v.opt(it)?.toString().orEmpty() }
            is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> throw Refused("invalid_parameter", "store must be a list like [\"memory\",\"files\",\"upload\"].")
        }
        if (names == listOf("none")) return emptySet()
        val bad = names.filter { it !in DESTINATIONS }
        if (bad.isNotEmpty()) throw Refused("invalid_parameter", "store can hold memory, files and upload (or \"none\"), not ${bad.joinToString(", ")}.")
        return names.toSet()
    }

    /** Parses `kinds` the same way; null = every kind. */
    fun parseKinds(v: Any?): Set<String>? {
        if (v == null || v == JSONObject.NULL) return null
        val names = when (v) {
            is JSONArray -> (0 until v.length()).map { v.opt(it)?.toString().orEmpty() }
            is String -> v.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            else -> throw Refused("invalid_parameter", "kinds must be a list of ${KINDS.joinToString(", ")}.")
        }
        val bad = names.filter { it !in KINDS }
        if (bad.isNotEmpty()) throw Refused("invalid_parameter", "kinds can hold ${KINDS.joinToString(", ")}, not ${bad.joinToString(", ")}.")
        return names.toSet()
    }

    /** Checks upload headers: a few, plain names, no reserved ones. Returns them trimmed. */
    fun checkHeaders(h: Map<String, String>): Map<String, String> {
        if (h.size > MAX_UPLOAD_HEADERS) throw Refused("invalid_parameter", "At most $MAX_UPLOAD_HEADERS upload headers.")
        return h.entries.associate { (k, v) ->
            val name = k.trim()
            if (!name.matches(Regex("^[A-Za-z0-9-]{1,64}$"))) throw Refused("invalid_parameter", "Header name \"$name\" isn't allowed.")
            if (name.lowercase() in RESERVED_HEADERS || name.lowercase().startsWith("x-bridgit-"))
                throw Refused("invalid_parameter", "Bridgit sets $name itself.")
            if (v.length > MAX_HEADER_VALUE || v.any { it == '\r' || it == '\n' }) throw Refused("invalid_parameter", "The value of $name isn't allowed.")
            name to v
        }
    }

    /** Refuses destinations the owner turned off, and a keep time over the owner's limit. */
    fun checkAllowed(store: Set<String>?, keepMinutes: Int?, owner: OwnerPolicy) {
        store?.firstOrNull { !owner.allowed(it) }?.let {
            throw Refused("owner_disallowed", if (it == FILES) "The owner doesn't let agents save captures to the phone's files."
                else "The owner doesn't let agents upload captures.")
        }
        if (keepMinutes != null && keepMinutes !in 1..owner.maxKeepMinutes)
            throw Refused("invalid_parameter", "keep_minutes must be 1 to ${owner.maxKeepMinutes} (the owner's limit).")
    }

    /**
     * Where a capture of [kind] goes. [requested]: the request's own `store`, which wins over the agent's
     * saved choice. The owner's turned-off destinations are dropped here too, in case they changed after
     * the agent chose. Throws [Refused] only for an explicit request the owner doesn't allow.
     */
    fun plan(kind: String, owner: OwnerPolicy, prefs: AgentPrefs?, requested: Set<String>? = null): Plan {
        if (requested != null) checkAllowed(requested, null, owner)
        val saved = prefs?.store?.takeIf { prefs.kinds == null || kind in prefs.kinds }
        val chosen = (requested ?: saved ?: defaults(kind)).filter { owner.allowed(it) }.toMutableSet()
        if (kind in CLIP_KINDS) chosen += MEMORY
        val upload = if (UPLOAD in chosen) prefs?.uploadUrl else null
        val keep = (prefs?.keepMinutes ?: DEFAULT_KEEP_MINUTES).coerceIn(1, owner.maxKeepMinutes.coerceAtLeast(1))
        return Plan(MEMORY in chosen, FILES in chosen || owner.ownerFiles, upload, owner.ownerUploadUrl?.takeIf { it.isNotBlank() }, keep * 60_000L)
    }

    /** Before anyone opts in: clips in memory (for clip_url), everything else only returned. */
    fun defaults(kind: String): Set<String> = if (kind in CLIP_KINDS) setOf(MEMORY) else emptySet()

    /**
     * SEC-13's default restriction: a capture goes only to the agent that made it, through the capability
     * that recorded it, while that agent still has that capability. With the owner's "any agent can fetch"
     * on, any agent that has the capability may fetch it.
     */
    fun canFetch(ownerAgentId: String, kind: String, callerId: String, callerAllows: (String) -> Boolean, shareAll: Boolean): Boolean =
        (callerId == ownerAgentId || shareAll) && callerAllows(kind)

    /** Downloads/Bridgit/<agent>/ — one folder per agent so the owner can find things. */
    fun folder(agentName: String): String = "Download/Bridgit/${safeName(agentName).ifEmpty { "agent" }}/"

    /** e.g. 2026-11-02_14-03-22_camera_ab12cd.jpg */
    fun fileName(kind: String, at: Long, id: String, mime: String, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(zone).format(Instant.ofEpochMilli(at)) +
            "_${kind}_${id.takeLast(6)}.${extension(mime)}"

    fun extension(mime: String): String = when (mime) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/heic", "image/heif" -> "heic"
        "audio/wav", "audio/x-wav" -> "wav"; "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        else -> mime.substringAfter('/').filter { it.isLetterOrDigit() }.take(8).ifEmpty { "bin" }
    }

    fun safeName(s: String): String = s.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' }.trim().take(40)

    /** One capture in the index. [memory] says the bytes are still held in RAM. */
    data class Entry(
        val id: String, val agentId: String, val kind: String, val mime: String, val size: Int, val at: Long,
        val keepUntil: Long, val memory: Boolean, val fileUri: String? = null, val filePath: String? = null,
        val upload: Map<String, String> = emptyMap(), val meta: JSONObject = JSONObject(),
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("agent_id", agentId).put("kind", kind).put("mime", mime)
            .put("size", size).put("at", at).put("keep_until", keepUntil).put("memory", memory)
            .put("file_uri", fileUri ?: JSONObject.NULL).put("file_path", filePath ?: JSONObject.NULL)
            .put("upload", JSONObject(upload)).put("meta", meta)

        companion object {
            fun fromJson(o: JSONObject) = Entry(o.getString("id"), o.getString("agent_id"), o.getString("kind"), o.getString("mime"),
                o.getInt("size"), o.getLong("at"), o.getLong("keep_until"), false,
                o.optString("file_uri").takeIf { it.isNotEmpty() && it != "null" }, o.optString("file_path").takeIf { it.isNotEmpty() && it != "null" },
                o.optJSONObject("upload")?.let { u -> u.keys().asSequence().associateWith { u.getString(it) } } ?: emptyMap(),
                o.optJSONObject("meta") ?: JSONObject())
        }
    }

    /**
     * Which memory copies to drop at [now]: expired ones, then the oldest while over [maxItems] or [budget] bytes.
     * [entries] in capture order (oldest first). Returns the ids to drop from memory.
     */
    fun memoryToDrop(entries: List<Entry>, now: Long, maxItems: Int = MAX_MEMORY_ITEMS, budget: Long = MEMORY_BUDGET_BYTES): Set<String> {
        val held = entries.filter { it.memory }
        val drop = held.filter { it.keepUntil <= now }.map { it.id }.toMutableSet()
        val live = held.filter { it.id !in drop }
        var bytes = live.sumOf { it.size.toLong() }
        var count = live.size
        for (e in live) {
            if (count <= maxItems && bytes <= budget) break
            drop += e.id; count--; bytes -= e.size
        }
        return drop
    }

    /** True when an entry is worth listing: still in memory, in a file, or uploaded somewhere. */
    fun listed(e: Entry): Boolean = e.memory || e.fileUri != null || e.upload.isNotEmpty()

    /** The API view of one capture for [now]. */
    fun view(e: Entry, iso: (Long) -> String): JSONObject = JSONObject().put("id", e.id).put("kind", e.kind)
        .put("agent_id", e.agentId).put("mime", e.mime).put("size_bytes", e.size).put("captured_at", iso(e.at))
        .put("in_memory", e.memory).put("memory_until", if (e.memory) iso(e.keepUntil) else JSONObject.NULL)
        .put("file", e.filePath ?: JSONObject.NULL)
        .put("upload", JSONObject(e.upload))
        .put("url", "/v1/captures/${e.id}")
        .put("meta", e.meta)
}
