package cc.bridgit.app.platform

import cc.bridgit.app.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/**
 * Card-button batches (docs/applet-dev-experience.md R2, docs/applet-vocabulary.md "Batches"): one run of a flow per
 * element, one after another, never dropped. A batch is a platform-store record of kind `batch` under the applet's
 * agent: the flow, every element's `with` values (worked out when the batch starts), a cursor and a status. It
 * survives the card closing and the phone restarting ([resume]). An element whose run was cut off is marked
 * `unknown_outcome` and skipped, never run again silently. Marking an element done is the flow's own job.
 *
 * Record: {id, button, label, flow, workflow_id, status: running|done|cancelled|stopped, cursor, total, done, failed,
 * unknown, cancel?, stop_reason?, elements: [{key, with, status: pending|running|done|failed|unknown_outcome|not_run,
 * run_id?, error?}], started_at, finished_at?}.
 */
class FlowBatches(private val runtime: AgentPlatform) {
    companion object {
        const val KIND = "batch"
        const val MAX = 200
        const val DEFAULT_MAX = 50
        const val KEEP = 20
        val FINISHED = setOf("done", "cancelled", "stopped")
        /** Run errors that stop the whole batch: the rest would fail the same way. */
        val STOPPING = setOf("budget_reached", "applet_budget", "paid_cap")
    }

    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun addListener(l: (agentId: String) -> Unit) { listeners += l }
    fun removeListener(l: (agentId: String) -> Unit) { listeners -= l }
    private fun changed(agent: String) = listeners.forEach { runCatching { it(agent) } }

    /** The batch of [agent] that is running now, if any (one per applet). */
    fun running(agent: String): JSONObject? = runtime.store.list(agent, KIND, 50).firstOrNull { it.optString("status") == "running" }

    /** The newest batch of [agent] started by the button with id or label [button]. */
    fun latest(agent: String, button: String): JSONObject? =
        runtime.store.list(agent, KIND, 50).filter { it.optString("button") == button || it.optString("label") == button }.maxByOrNull { it.optLong("started_at") }

    /** What a card's `{"batch": "<button>"}` source reads: {status, done, total, failed, unknown, current, ...}. */
    fun summary(agent: String, button: String): JSONObject = latest(agent, button)?.let(::summaryOf)
        ?: JSONObject().put("status", "none").put("done", 0).put("total", 0).put("failed", 0).put("unknown", 0).put("current", JSONObject.NULL)

    /**
     * A batch as pieces read it: {id, status (running, done, cancelled, stopped), label, done, total, failed, unknown,
     * current (1-based, while running), cancelling, failed_items[{index, key, error}], unknown_items[{index, key}],
     * stop_reason, started_at, finished_at}.
     */
    fun summaryOf(b: JSONObject): JSONObject {
        val els = b.optJSONArray("elements") ?: JSONArray()
        fun listed(status: String) = JSONArray().also { out ->
            for (i in 0 until els.length()) els.optJSONObject(i)?.takeIf { it.optString("status") == status }?.let { e ->
                out.put(JSONObject().put("index", i + 1).put("key", e.optString("key")).apply { e.optString("error").takeIf { it.isNotEmpty() }?.let { put("error", it) } })
            }
        }
        val running = b.optString("status") == "running"
        return JSONObject().put("id", b.optString("id")).put("status", b.optString("status")).put("label", b.optString("label"))
            .put("done", b.optInt("done")).put("total", b.optInt("total")).put("failed", b.optInt("failed")).put("unknown", b.optInt("unknown"))
            .put("current", if (running && b.optInt("cursor") < b.optInt("total")) b.optInt("cursor") + 1 else JSONObject.NULL)
            .put("cancelling", running && b.optBoolean("cancel"))
            .put("failed_items", listed("failed")).put("unknown_items", listed("unknown_outcome"))
            .put("stop_reason", b.optString("stop_reason").ifEmpty { null } ?: JSONObject.NULL)
            .put("started_at", b.optLong("started_at")).put("finished_at", b.optLong("finished_at").takeIf { it > 0 } ?: JSONObject.NULL)
    }

    /**
     * Starts a batch of flow [flowRef] for [agent], one run per element of [elements] (key → `with` values). Returns
     * {status: started|busy|empty|flow_not_found|flow_not_active|bad_with, batch_id?, total?, message?}. Nothing runs
     * unless every element's `with` fits the flow's called trigger.
     */
    fun start(agent: String, button: String, label: String, flowRef: String, elements: List<Pair<String, JSONObject?>>): JSONObject {
        val out = JSONObject().put("flow", flowRef)
        val id = synchronized(runtime.store) {
            running(agent)?.let { return out.put("status", "busy").put("batch_id", it.optString("id")).put("message", "${it.optString("label")} is still going") }
            val record = runtime.workflows.resolve(agent, flowRef) ?: return out.put("status", "flow_not_found").put("message", "No workflow $flowRef")
            val trigger = record.optJSONObject("definition")?.optJSONObject("trigger")
            val type = trigger?.optString("type")
            if (type != "called" && type != "button") return out.put("status", "flow_not_active").put("message", "Flow $flowRef has a $type trigger, so a button can't start it")
            // A share step opens the share sheet for the owner: once per tap, never once per element.
            if (runtime.workflows.shares(record.optJSONObject("definition"))) return out.put("status", "flow_not_active").put("message", "Flow $flowRef has a share step, so it can't run as a batch")
            if (record.optString("status") != "active" || record.optString("approved_digest") != record.optString("digest"))
                return out.put("status", "flow_not_active").put("message", "Flow $flowRef is ${record.optString("status")}")
            if (elements.isEmpty()) return out.put("status", "empty").put("total", 0).put("message", "Nothing to do")
            if (elements.size > MAX) return out.put("status", "bad_with").put("message", "A batch runs at most $MAX elements")
            val list = JSONArray()
            for ((key, with) in elements) {
                val values = try { FlowSteps.withValues(trigger.optJSONObject("with"), with) } catch (e: ApiError) { return out.put("status", "bad_with").put("message", "Element $key: ${e.message}") }
                list.put(JSONObject().put("key", key).put("with", values).put("status", "pending"))
            }
            runtime.store.save(agent, KIND, JSONObject().put("id", "batch-" + java.util.UUID.randomUUID().toString().take(12)).put("button", button).put("label", label)
                .put("flow", flowRef).put("workflow_id", record.getString("id")).put("status", "running").put("cursor", 0).put("total", list.length())
                .put("done", 0).put("failed", 0).put("unknown", 0).put("elements", list).put("started_at", System.currentTimeMillis())).getString("id")
        }
        changed(agent)
        launch(agent, id)
        return out.put("status", "started").put("batch_id", id).put("total", elements.size)
    }

    /** Cancel stops [agent]'s running batch after the run that is going now. Returns whether one was running. */
    fun cancel(agent: String): Boolean {
        synchronized(runtime.store) {
            val b = running(agent) ?: return false
            runtime.store.save(agent, KIND, b.put("cancel", true))
        }
        changed(agent)
        return true
    }

    /**
     * After a restart: every running batch goes on at its cursor. The element that was running when the phone stopped
     * keeps its run's outcome when that run had finished, else it is marked unknown_outcome and skipped.
     */
    fun resume() {
        for (b in runtime.store.list(kind = KIND, limit = 1000).filter { it.optString("status") == "running" }) {
            val agent = b.getString("agent_id"); val id = b.getString("id")
            if (jobs[id]?.isActive == true) continue
            val sharing = synchronized(runtime.store) {
                val latest = runtime.store.find(id, agent, KIND) ?: return@synchronized false
                val i = latest.optInt("cursor")
                val el = latest.optJSONArray("elements")?.optJSONObject(i)
                if (el != null && el.optString("status") == "running") {
                    val run = el.optString("run_id").takeIf { it.isNotEmpty() }?.let { runtime.store.find(it, agent, "execution") }
                        ?: runtime.store.list(agent, "execution", 1000).firstOrNull { it.optJSONObject("trigger")?.optJSONObject("batch")?.let { t -> t.optString("id") == id && t.optInt("index") == i } == true }
                    recordOutcome(latest, el, run)
                    run?.let { el.put("run_id", it.getString("id")) }
                    runtime.store.save(agent, KIND, latest.put("cursor", i + 1))
                }
                // Never resumed: a flow that shares (its definition may have changed since) needs the owner's tap.
                if (runtime.workflows.shares(runtime.store.find(latest.optString("workflow_id"), agent, "workflow")?.optJSONObject("definition"))) {
                    finish(agent, latest, "stopped", "share_needs_owner"); true
                } else false
            }
            changed(agent)
            if (!sharing) launch(agent, id)
        }
    }

    private fun recordOutcome(batch: JSONObject, element: JSONObject, run: JSONObject?) {
        val (status, count) = when (run?.optString("status")) {
            "completed" -> "done" to "done"
            "failed" -> "failed" to "failed"
            else -> "unknown_outcome" to "unknown"
        }
        element.put("status", status)
        run?.optString("error")?.takeIf { it.isNotEmpty() }?.let { element.put("error", it) }
        batch.put(count, batch.optInt(count) + 1)
    }

    private fun launch(agent: String, id: String) {
        synchronized(jobs) {
            if (jobs[id]?.isActive == true) return
            jobs[id] = runtime.scope.launch { try { loop(agent, id) } finally { coroutineContext[Job]?.let { jobs.remove(id, it) } } }
        }
    }

    private fun finish(agent: String, b: JSONObject, status: String, reason: String? = null) {
        val els = b.optJSONArray("elements") ?: JSONArray()
        for (j in 0 until els.length()) els.optJSONObject(j)?.takeIf { it.optString("status") == "pending" }?.put("status", "not_run")
        b.remove("cancel")
        if (reason != null) b.put("stop_reason", reason)
        runtime.store.save(agent, KIND, b.put("status", status).put("finished_at", System.currentTimeMillis()))
        runCatching { runtime.store.trimFinished(agent, KIND, KEEP, FINISHED) }
    }

    private class Next(val index: Int, val workflow: String, val trigger: JSONObject)

    /** Runs the elements one by one from the cursor. Saved before and after each run, so a restart knows where it was. */
    private suspend fun loop(agent: String, id: String) {
        while (true) {
            val next = synchronized(runtime.store) {
                val b = runtime.store.find(id, agent, KIND)?.takeIf { it.optString("status") == "running" } ?: return
                val i = b.optInt("cursor")
                when {
                    i >= b.optInt("total") -> { finish(agent, b, "done"); null }
                    b.optBoolean("cancel") -> { finish(agent, b, "cancelled"); null }
                    else -> {
                        val el = b.getJSONArray("elements").getJSONObject(i)
                        el.put("status", "running").put("started_at", System.currentTimeMillis())
                        runtime.store.save(agent, KIND, b)
                        Next(i, b.getString("workflow_id"), JSONObject().put("caller", "batch")
                            .put("batch", JSONObject().put("id", id).put("index", i).put("total", b.optInt("total")))
                            .put("with", el.optJSONObject("with") ?: JSONObject()))
                    }
                }
            }
            changed(agent)
            if (next == null) return
            val i = next.index
            // Never dropped: wait while the flow is busy with another start, then run. Cancel still counts meanwhile.
            while (runtime.workflows.busy(next.workflow)) { if (cancelledWaiting(agent, id, i)) return; delay(500) }
            var stop: String? = null
            val result: JSONObject = try {
                var r: JSONObject? = null
                while (r == null) {
                    if (cancelledWaiting(agent, id, i)) return
                    try { r = runtime.workflows.runOnce(agent, next.workflow, null, next.trigger) { runId -> saveRunId(agent, id, i, runId) } }
                    catch (e: ApiError) { if (e.code == "workflow_busy") delay(500) else throw e }
                }
                r
            } catch (e: CancellationException) { throw e }
            catch (e: ApiError) { stop = e.code; JSONObject().put("status", "failed").put("error", e.code) }
            catch (e: Exception) { stop = "internal_error"; JSONObject().put("status", "failed").put("error", "internal_error") }
            synchronized(runtime.store) {
                val latest = runtime.store.find(id, agent, KIND) ?: return
                val el = latest.getJSONArray("elements").getJSONObject(i)
                result.optString("execution_id").takeIf { it.isNotEmpty() }?.let { el.put("run_id", it) }
                recordOutcome(latest, el, result)
                latest.put("cursor", i + 1)
                if (stop == null && result.optString("error") in STOPPING) stop = result.optString("error")
                if (stop != null) finish(agent, latest, "stopped", stop) else runtime.store.save(agent, KIND, latest)
            }
            changed(agent)
            if (stop != null) return
        }
    }

    /**
     * While element [i] waits for its flow: re-reads the batch, and when Cancel was pressed (or the batch is gone or
     * finished) ends it as cancelled with the waiting element not run. Returns whether the loop should stop.
     */
    private fun cancelledWaiting(agent: String, id: String, i: Int): Boolean {
        val stop = synchronized(runtime.store) {
            val b = runtime.store.find(id, agent, KIND)?.takeIf { it.optString("status") == "running" } ?: return true
            if (!b.optBoolean("cancel")) return false
            b.getJSONArray("elements").optJSONObject(i)?.takeIf { it.optString("status") == "running" }?.apply { put("status", "pending"); remove("started_at") }
            finish(agent, b, "cancelled"); true
        }
        changed(agent)
        return stop
    }

    private fun saveRunId(agent: String, id: String, i: Int, runId: String) = synchronized(runtime.store) {
        val b = runtime.store.find(id, agent, KIND) ?: return@synchronized
        b.getJSONArray("elements").optJSONObject(i)?.put("run_id", runId)
        runtime.store.save(agent, KIND, b)
    }
}
