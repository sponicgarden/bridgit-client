package cc.bridgit.app.platform

import android.app.KeyguardManager
import cc.bridgit.app.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Applet event intake shares workflow approval, agent scopes and the signed callback outbox. */
class ApplicationEvents(private val runtime: AgentPlatform) {
    private val notificationSlots = Semaphore(16)
    private val overflowReported = java.util.concurrent.atomic.AtomicBoolean()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    fun validate(trigger: JSONObject) = validateTrigger(trigger)

    fun permitted(agent: String, trigger: JSONObject) {
        runtime.authorize(agent, "workflows")
        runtime.authorize(agent, "events.read")
        if (trigger.optString("transport") == "notification") {
            runtime.authorize(agent, "notifications.read")
            if (runtime.app.getSystemService(KeyguardManager::class.java).isDeviceLocked)
                throw ApiError(409, "device_locked", "Unlock to process notification content")
            if (trigger.getString("package") !in runtime.store.setting("notification_apps:$agent").split(',').map { it.trim() })
                throw ApiError(403, "notification_app_not_allowed", "Choose this notification app on the phone")
        }
    }

    /** Approval binds adapter, mapping, source, filters and action steps to one immutable digest. */
    private fun approved(agent: String, id: String, allowBlocked: Boolean = false): JSONObject {
        val record = runtime.store.require(id, agent, "workflow")
        if (record.optString("status") !in (if (allowBlocked) setOf("active", "blocked") else setOf("active")) || record.optString("approved_digest") != PlatformStore.digest(record.getJSONObject("definition")))
            throw ApiError(403, "application_inactive", "Activate this exact applet workflow on the phone")
        val trigger = record.getJSONObject("definition").getJSONObject("trigger")
        if (!isAppletEvent(trigger.optString("type"))) throw ApiError(422, "invalid_source", "This workflow has no applet event source")
        permitted(agent, trigger)
        return record
    }

    suspend fun receive(agent: String, args: JSONObject): JSONObject {
        args.only("workflow_id", "event_id", "payload")
        val id = args.text("workflow_id", 128)
        val externalId = args.text("event_id", 128)
        val input = args.optJSONObject("payload") ?: throw ApiError(422, "invalid_event", "payload must be a JSON object")
        if (input.toString().toByteArray().size > 16384) throw ApiError(413, "event_too_large", "Event payload exceeds 16 KiB")
        return ingest(agent, id, externalId, input, "webhook")
    }

    fun notification(pkg: String, key: String, postedAt: Long, title: String, text: String) {
        if (!notificationSlots.tryAcquire()) {
            if (overflowReported.compareAndSet(false, true)) runtime.store.list(kind="workflow").filter {
                val t=it.optJSONObject("definition")?.optJSONObject("trigger")
                it.optString("status")=="active" && isAppletEvent(t?.optString("type")) && t?.optString("transport")=="notification" && t.optString("package")==pkg
            }.forEach { runtime.store.emit(it.getString("agent_id"),if (it.getJSONObject("definition").getJSONObject("trigger").optString("type") == "applet.event") "applet.backpressure" else "application.backpressure",it.getString("id")) }
            return
        }
        runtime.scope.launch {
            try { processNotification(pkg,key,postedAt,title,text) }
            finally { notificationSlots.release(); overflowReported.set(false) }
        }
    }

    private suspend fun processNotification(pkg: String, key: String, postedAt: Long, title: String, text: String) {
        for (record in runtime.store.list(kind = "workflow").filter { it.optString("status") == "active" }) {
            val trigger = record.getJSONObject("definition").getJSONObject("trigger")
            if (!isAppletEvent(trigger.optString("type")) || trigger.optString("transport") != "notification" || trigger.optString("package") != pkg) continue
            val input = JSONObject().put("title", title.take(512)).put("text", text.take(4096)).put("occurred_at", postedAt)
            // Updates to a notification's text are distinct; reposting identical content at the same time is not.
            val externalId = PlatformStore.digest(JSONObject().put("key", key).put("input", input))
            currentCoroutineContext().ensureActive()
            try { ingest(record.getString("agent_id"), record.getString("id"), externalId, input, "notification") }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* Revoked, locked, filtered or unavailable sources do not read/backfill content. */ }
        }
    }

    private suspend fun ingest(agent: String, id: String, externalId: String, input: JSONObject, transport: String): JSONObject =
        locks.getOrPut(id) { Mutex() }.withLock {
            currentCoroutineContext().ensureActive()
            val record = approved(agent, id)
            val trigger = record.getJSONObject("definition").getJSONObject("trigger")
            if (trigger.getString("transport") != transport) throw ApiError(403, "wrong_transport", "This source uses a different transport")
            val now = System.currentTimeMillis()
            val event = normalize(trigger, input, runtime.store.deviceId, externalId, now).put("approval_generation",record.getString("event_generation"))
            val digest = PlatformStore.digest(input)
            val receipts = record.optJSONObject("event_receipts") ?: JSONObject()
            val receiptKey = PlatformStore.digest(JSONObject().put("id", externalId))
            receipts.optJSONObject(receiptKey)?.let { previous ->
                if (previous.getString("digest") != digest) throw ApiError(409, "event_id_conflict", "This source event ID was already used for another payload")
                return@withLock JSONObject().put("accepted", false).put("duplicate", true).put("event_id", previous.getString("event_id"))
            }
            val wanted = trigger.optString("event_type")
            val cooldownKey = PlatformStore.digest(JSONObject().put("device", event.getJSONObject("device").getString("id")).put("type", event.getString("type")))
            val cooldowns = record.optJSONObject("event_cooldowns") ?: JSONObject()
            val matches = wanted.isEmpty() || wanted == event.getString("type")
            val cooled = now - (cooldowns.optJSONObject(cooldownKey)?.optLong("at") ?: 0L) >= (record.optJSONObject("definition")?.takeIf { it.has("cooldown_s") }?.optInt("cooldown_s") ?: trigger.whole("cooldown_seconds", 30, 0, 86400)) * 1000L
            val fire = matches && cooled
            receipts.put(receiptKey, JSONObject().put("digest", digest).put("event_id", event.getString("id")).put("at", now))
            trim(receipts)
            if (fire) { cooldowns.put(cooldownKey, JSONObject().put("at", now)); trim(cooldowns) }
            if (fire) record.put("last_event_id", event.getString("id"))
            record.put("event_receipts", receipts).put("event_cooldowns", cooldowns)
            synchronized(runtime.store) {
                // An owner pause/reapproval must never be overwritten by this intake snapshot.
                val latest = approved(agent, id)
                if (latest.optString("event_generation") != record.optString("event_generation"))
                    throw ApiError(409, "source_changed", "Source was paused or reapproved during intake")
                val db = runtime.store.writableDatabase
                db.beginTransaction()
                try {
                    runtime.store.save(agent, "workflow", record)
                    if (fire) runtime.store.emit(agent, trigger.getString("type"), id, event)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            var actionError: String? = null
            if (fire) try { runtime.workflows.runOnce(agent, id, event.getString("id")) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { actionError = if (e is ApiError) e.code else "action_failed" }
            JSONObject().put("accepted", fire).put("duplicate", false).put("event_id", event.getString("id"))
                .put("reason", if (actionError == "workflow_skipped") "daily_cap" else if (actionError != null) "action_failed" else if (fire) "dispatched" else if (!matches) "filtered" else "cooldown").put("action_error", actionError)
        }

    /** Suppress saved content after access is revoked, the phone locks, or its source is paused. */
    fun visible(agent: String, event: JSONObject): Boolean {
        if (!isAppletEvent(event.optString("type"))) return true
        return runCatching { val source=approved(agent, event.getString("resource_id"), allowBlocked = true)
            source.optString("event_generation")==event.optJSONObject("data")?.optString("approval_generation") }.getOrDefault(false)
    }

    fun events(agent: String, after: Long): JSONObject = runtime.store.events(agent, after).also { page ->
        val rows = page.getJSONArray("events")
        for (i in 0 until rows.length()) if (!visible(agent, rows.getJSONObject(i))) {
            rows.getJSONObject(i).remove("data")
            rows.getJSONObject(i).put("redacted", true)
        }
    }

    companion object {
        fun validateTrigger(trigger: JSONObject) {
            trigger.only("type", "applet_id", "application_id", "source_id", "transport", "package", "adapter", "mapping", "event_type", "cooldown_seconds")
            if (trigger.has("applet_id") && trigger.has("application_id") && trigger.optString("applet_id") != trigger.optString("application_id"))
                throw ApiError(422, "invalid_source", "applet_id and legacy application_id must agree")
            for (key in listOf(if (trigger.has("applet_id")) "applet_id" else "application_id", "source_id")) {
                if (!trigger.text(key, 80).matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}")))
                    throw ApiError(422, "invalid_source", "Use a stable applet and source identifier")
            }
            val transport = trigger.text("transport", 16)
            if (transport !in setOf("webhook", "notification")) throw ApiError(422, "invalid_source", "Choose webhook or notification")
            if (transport == "notification") trigger.text("package", 256)
            else if (trigger.has("package")) throw ApiError(422, "invalid_source", "package applies only to notifications")
            if (trigger.text("adapter", 16, "generic") !in setOf("generic", "tapo")) throw ApiError(422, "invalid_adapter", "Choose generic or tapo")
            trigger.whole("cooldown_seconds", 30, 0, 86400)
            trigger.text("event_type", 80, "")
            trigger.optJSONObject("mapping")?.let { mapping ->
                mapping.only("type", "device_id", "device_name", "title", "text", "occurred_at")
                mapping.keys().forEach { key ->
                    if (!mapping.text(key, 256).matches(Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+){0,7}")))
                        throw ApiError(422, "invalid_mapping", "Mappings use bounded dotted object paths")
                }
            }
            if (trigger.has("mapping") && trigger.optJSONObject("mapping") == null) throw ApiError(422, "invalid_mapping", "mapping must be an object")
        }

        fun isAppletEvent(type: String?): Boolean = type == "applet.event" || type == "application.event"
        fun appletId(trigger: JSONObject): String = if (trigger.has("applet_id")) trigger.getString("applet_id") else trigger.getString("application_id")
        private fun trim(values: JSONObject) {
            values.keys().asSequence().toList().sortedBy { values.optJSONObject(it)?.optLong("at") ?: 0L }
                .dropLast(256).forEach(values::remove)
        }
        fun normalize(trigger: JSONObject, input: JSONObject, hostDevice: String, externalId: String, now: Long): JSONObject {
            fun field(key: String, max: Int, fallback: String = ""): String {
                val path = trigger.optJSONObject("mapping")?.optString(key)?.takeIf { it.isNotEmpty() } ?: key
                var value: Any? = input
                for (part in path.split('.')) value = (value as? JSONObject)?.opt(part)
                if (value == null || value == JSONObject.NULL) return fallback
                if (value !is String && value !is Number && value !is Boolean) throw ApiError(422, "invalid_event", "$key must map to a scalar")
                if (value.toString().length > max) throw ApiError(422, "invalid_event", "$key exceeds $max characters")
                return value.toString()
            }
            val title = field("title", 512)
            val text = field("text", 4096)
            val adapter = trigger.optString("adapter", "generic")
            val type = if (adapter == "tapo") when {
                Regex("^Person was detected at ", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "person.detected"
                Regex("^Motion was detected at ", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "motion.detected"
                else -> "notification.received"
            } else field("type", 80, "device.event")
            val suppliedAt = field("occurred_at", 32)
            val occurred = if (suppliedAt.isEmpty()) now else suppliedAt.toLongOrNull() ?: throw ApiError(422, "invalid_event", "occurred_at must be Unix milliseconds")
            if (occurred <= 0 || occurred > now + 300000) throw ApiError(422, "invalid_event", "occurred_at must be Unix milliseconds, at most five minutes ahead")
            return JSONObject().put("schema", if (trigger.optString("type") == "applet.event") "cc.bridgit.applet-event" else "cc.bridgit.application-event").put("version", 1).put("id", UUID.randomUUID().toString())
                .put(if (trigger.optString("type") == "applet.event") "applet_id" else "application_id", appletId(trigger)).put("type", type)
                .put("occurred_at", occurred).put("received_at", now).put("host_device_id", hostDevice)
                .put("source", JSONObject().put("id", trigger.getString("source_id")).put("transport", trigger.getString("transport"))
                    .put("adapter", adapter).put("event_id", externalId).put("package", trigger.optString("package")))
                .put("device", JSONObject().put("id", field("device_id", 128, if (trigger.optString("transport") == "notification" && title.isNotEmpty()) PlatformStore.digest(JSONObject().put("title", title)) else trigger.getString("source_id"))).put("name", field("device_name", 256, title)))
                .put("title", title).put("text", text).put("raw", PlatformStore.copy(input)).put("content_is_untrusted", true)
        }
    }
}
