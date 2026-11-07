package cc.bridgit.app.applets

import android.util.Base64
import cc.bridgit.app.BridgeApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Complete applet-owned tables and records, including retained photographs. No account credentials. */
object AppletJsonExport {
    /** Owner-initiated migration copy. The portal bridge and agent export never call this method. */
    internal fun legacyDog(app: BridgeApp, instanceId: String): AppletExport.Built {
        val instance = requireNotNull(app.applets.get(instanceId)) { "This applet was removed." }
        require(instance.optString("native") == "dog") { "This edition has no legacy tracker." }
        val snapshot = dogDocument(app, instance)
        val dir = File(app.cacheDir, AppletExport.DIR).apply { mkdirs(); AppletExport.clean(this) }
        val file = File(dir, "dog-monitor-history-${UUID.randomUUID()}.json")
        try {
            file.bufferedWriter().use { it.write(snapshot.toString()) }
        } catch (e: Exception) { file.delete(); throw e }
        return AppletExport.Built(file, "application/json", "Dog Monitor history", 0, 0, if (snapshot.has("latest_photo")) 1 else 0)
    }

    internal fun dogDocument(app: BridgeApp, instance: JSONObject): JSONObject {
        val instanceId = instance.getString("id")
        val dog = app.dog
        val state = dog.state.value
        val settings = JSONObject().put("camera_mode", state.cameraMode).put("cameras", JSONArray(state.cameras))
            .put("models", JSONArray(state.models)).put("microphones", JSONArray(state.microphones))
            .put("pan_search", state.panSearchEnabled).put("sampling", dog.sampling()).put("feeding", dog.feeding.status())
        val clipResults = state.audioReadings.map { reading ->
            JSONObject().put("at", reading.at).put("source", reading.source).put("sound", reading.sound)
                .put("confidence", reading.confidence).put("on_device", reading.onDevice)
        }
        val snapshot = JSONObject().put("format", "bridgit-dog-monitor-history/1")
            .put("instance_id", instanceId).put("package_id", instance.getString("package_id"))
            .put("version", instance.getString("version")).put("package_sha256", instance.getString("sha256"))
            .put("exported_at", System.currentTimeMillis()).put("timezone", java.util.TimeZone.getDefault().id)
            .put("frame_interval_ms", dog.tracker.frameMs).put("tracker", dog.tracker.toJson())
            .put("sound_episodes", dog.barks.toJson()).put("sound_readings", JSONArray(clipResults))
            .put("settings", settings)
        state.frame?.let { bytes ->
            snapshot.put("latest_photo", JSONObject().put("at", state.frameAt)
                .put("camera", state.frameCamera ?: JSONObject.NULL).put("mime", "image/jpeg")
                .put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        }
        return snapshot
    }

    fun export(app: BridgeApp, instance: JSONObject?, portal: JSONObject? = null): AppletExport.Built {
        val agent = instance?.getString("agent_id")
        val store = AppletTableStore.of(app)
        val tables = JSONObject()
        AppletTables.parse(instance?.optJSONObject("definition")?.optJSONObject("processing") ?: JSONObject()).forEach { t ->
            if (agent != null) tables.put(t.id, JSONArray(AppletExport.readRows(store, agent, t.id)))
        }
        val records = if (agent == null) emptyList() else app.platform.store.keyed(agent, AppletData.KIND, AppletData.id(agent, ""), "", AppletData.MAX_RECORDS)
        val title = instance?.optString("name")?.takeIf { it.isNotBlank() } ?: portal?.optJSONObject("applet")?.optString("friendly_name")?.takeIf { it.isNotBlank() } ?: "Applet"
        val dir = File(app.cacheDir, AppletExport.DIR).apply { mkdirs(); AppletExport.clean(this) }
        val file = File(dir, "applet-${UUID.randomUUID()}.json")
        try {
            file.bufferedWriter().use { out ->
                val header = JSONObject().put("format", "bridgit-applet-data/1").put("exported_at", System.currentTimeMillis())
                    .put("applet", JSONObject().put("name", title).put("package_id", instance?.optString("package_id").orEmpty()))
                    .put("tables", tables).put("records", JSONArray(records.map(AppletData::view)))
                    .put("portal", portal ?: JSONObject.NULL)
                out.write(header.toString().dropLast(1)); out.write(",\"photos\":[")
                (if (agent == null) emptyList() else store.photos(agent, limit = 1000)).forEachIndexed { index, photo ->
                    val bytes = store.photoFile(requireNotNull(agent), photo.getString("ref"))
                        ?: throw IllegalStateException("A retained photo is unavailable. Try again after syncing.")
                    if (index > 0) out.write(",")
                    out.write(JSONObject(photo.toString()).put("mime", "image/jpeg")
                        .put("base64", Base64.encodeToString(bytes.readBytes(), Base64.NO_WRAP)).toString())
                }
                out.write("]}")
            }
        } catch (e: Exception) { file.delete(); throw e }
        return AppletExport.Built(file, "application/json", title, tables.length(), 0, 0)
    }
}
