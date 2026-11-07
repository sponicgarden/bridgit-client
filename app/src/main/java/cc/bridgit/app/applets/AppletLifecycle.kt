package cc.bridgit.app.applets

import org.json.JSONArray
import org.json.JSONObject

/** The installed record owns the durable monitoring task, separately from one-time executions. */
object AppletLifecycle {
    fun read(record: JSONObject, participants: Collection<String>): AppletTaskState {
        val saved = record.optJSONObject("lifecycle")
        if (saved == null) return AppletTaskState(record.getString("id"), 0,
            if (record.optString("status") == "paused") AppletTaskState.Phase.PAUSED else AppletTaskState.Phase.RUNNING,
            participants, emptyList())
        if (saved.optString("state") in setOf("running", "paused")) return AppletTaskState(
            record.getString("id"), saved.getLong("generation"),
            AppletTaskState.Phase.valueOf(saved.getString("state").uppercase()), participants, emptyList())
        return AppletTaskState(saved.getString("task_id"), saved.getLong("generation"),
            AppletTaskState.Phase.valueOf(saved.getString("state").uppercase()),
            strings(saved.getJSONArray("participants")), strings(saved.getJSONArray("pending")))
    }

    fun json(state: AppletTaskState) = JSONObject().put("task_id", state.taskId)
        .put("generation", state.generation).put("state", state.phase.name.lowercase())
        .put("participants", JSONArray(state.participants)).put("pending", JSONArray(state.pending))

    fun state(record: JSONObject): String = record.optJSONObject("lifecycle")?.optString("state")
        ?: if (record.optString("status") == "paused") "paused" else "running"

    private fun strings(array: JSONArray) = (0 until array.length()).map(array::getString)
}
