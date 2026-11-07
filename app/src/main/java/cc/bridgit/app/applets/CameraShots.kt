package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.Sizing
import cc.bridgit.app.capability.ImageUtil
import cc.bridgit.app.capability.isoAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The photo camera button's saves (docs/applet-wave2.md B): each shot is resized, kept under the applet's agent and
 * written as one row of the button's table with the photo ref, the button's fixed values and `taken_at`. The saves
 * run one at a time on [queue], an app-level scope, so they finish after the camera screen closes and the shutter
 * never waits for them.
 */
object CameraShots {
    /** The kept photos' step id, so a `{"photos": "camera"}` source could list them. */
    const val STEP = "camera"
    val SIZES = mapOf("large" to Sizing.Spec(2048, null, 90), "medium" to Sizing.Spec(1280, null, 85))

    /** One shot after another, outliving the screen. */
    val queue = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { r -> Thread(r, "applet-camera-saves") }.asCoroutineDispatcher())

    /** What the button saves into: [row] holds its fixed values (references already filled). */
    data class Target(val agentId: String, val table: String, val photo: String, val row: JSONObject, val size: String, val interactionId: Long)

    /** Who wrote a camera row: the owner's own tap, so the 60-a-minute limit doesn't apply. */
    fun by(interactionId: Long): JSONObject = JSONObject().put("type", "camera").put("camera", true).put("interaction_id", interactionId)

    /** The camera's JPEG at the button's size, EXIF rotation applied. */
    fun resize(jpeg: ByteArray, size: String): ByteArray = ImageUtil.prepare(jpeg, "image/jpeg", SIZES[size] ?: SIZES.getValue("large"), forceJpeg = true).bytes

    /** The installed applet's table [tableId], or null when the applet or table is gone. */
    fun table(app: BridgeApp, agentId: String, tableId: String): AppletTables.Table? =
        app.applets.forAgent(agentId)?.optJSONObject("definition")?.optJSONObject("processing")
            ?.let { runCatching { AppletTables.parse(it) }.getOrNull() }?.firstOrNull { it.id == tableId }

    /** Resizes and saves one shot for [t]; returns the row. Throws [AppletTables.Refused] with words for the owner. */
    fun save(app: BridgeApp, t: Target, jpeg: ByteArray, now: Long = System.currentTimeMillis()): JSONObject {
        val table = table(app, t.agentId, t.table) ?: throw AppletTables.Refused("table_gone", "This applet's table ${t.table} is gone.")
        return save(AppletTableStore.of(app), t.agentId, table, t.photo, t.row, resize(jpeg, t.size), by(t.interactionId), now)
    }

    /** Keeps [bytes] (already sized) and adds its row: the fixed values, the photo in [photo], `taken_at` if [table] has that time column. */
    fun save(store: AppletTableStore, agentId: String, table: AppletTables.Table, photo: String, fixed: JSONObject, bytes: ByteArray, by: JSONObject,
             now: Long = System.currentTimeMillis()): JSONObject {
        val ref = store.keepPhoto(agentId, STEP, bytes)
        val row = JSONObject(fixed.toString()).put(photo, ref)
        if (table.columns["taken_at"]?.type == "time" && (!row.has("taken_at") || row.isNull("taken_at"))) row.put("taken_at", isoAt(now))
        // A row that fails leaves its kept photo unused; the daily clean-up deletes it after 7 days.
        return store.add(agentId, table, row, by)
    }
}
