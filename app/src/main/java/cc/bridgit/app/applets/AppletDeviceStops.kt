package cc.bridgit.app.applets

import android.content.Context
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.lancamera.CameraMotion
import cc.bridgit.app.lancamera.CameraViews
import cc.bridgit.app.lancamera.Onvif
import org.json.JSONObject

/** Retain exclusive motion ownership until the enrolled device acknowledges Stop, including after restart. */
object AppletDeviceStops {
    private val retained = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun prefs(app: Context) = app.getSharedPreferences("applet_device_stops", Context.MODE_PRIVATE)

    fun restore(app: Context) {
        for ((token, raw) in prefs(app).all) {
            val row = JSONObject(raw as String)
            retained.add(token)
            check(CameraMotion.owns(row.getString("camera"), token) || CameraMotion.acquire(listOf(row.getString("camera")), token)) { "Pending camera stop has conflicting ownership." }
        }
    }

    fun retained(token: String) = token in retained

    fun begin(app: BridgeApp, owner: String, camera: String, profile: String, token: String) {
        val row = JSONObject().put("owner", owner).put("camera", camera).put("profile", profile)
            .put("enrollment", CameraViews.fingerprint(app.lanCameras.get(camera)))
        check(prefs(app).edit().putString(token, row.toString()).commit()) { "Could not save device task." }
        retained.add(token)
    }

    fun stop(app: BridgeApp, token: String): Boolean {
        val raw = prefs(app).getString(token, null) ?: return true
        val row = JSONObject(raw)
        return runCatching {
            val camera = app.lanCameras.get(row.getString("camera"))
            check(CameraViews.fingerprint(camera) == row.getString("enrollment")) { "Camera enrollment changed." }
            check(CameraMotion.owns(camera.id, token)) { "Device task ownership changed." }
            Onvif(camera, app, token).stop(row.getString("profile"))
            check(prefs(app).edit().remove(token).commit()) { "Could not save stop acknowledgment." }
            retained.remove(token)
        }.isSuccess
    }

    fun stopOwner(app: BridgeApp, owner: String) {
        val pending = prefs(app).all.filterValues { JSONObject(it as String).optString("owner") == owner }
        val failed = pending.keys.filter { token ->
            if (stop(app, token)) { CameraMotion.release(token); false } else true
        }
        check(failed.isEmpty()) { "Waiting for device Stop confirmation." }
    }
}
