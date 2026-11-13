package cc.bridgit.app.platform

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import cc.bridgit.app.*
import org.json.JSONArray
import org.json.JSONObject

data class DeviceCapability(val id: String, val label: String, val permission: String? = null, val privateOnly: Boolean = false, val risk: String = "read")

object Capabilities {
    val all = listOf(
        DeviceCapability("camera.snapshot","Camera snapshots",Manifest.permission.CAMERA),
        DeviceCapability("microphone.clip","Microphone clips",Manifest.permission.RECORD_AUDIO),
        DeviceCapability("gallery.read","Gallery images",Manifest.permission.READ_MEDIA_IMAGES),
        DeviceCapability("interactions","Questions and notices"), DeviceCapability("events.read","Agent events"),
        DeviceCapability("files","Files and shares"), DeviceCapability("audio.speak","Speech and audio",risk="action"),
        DeviceCapability("screen.observe","Screen observation"),
        DeviceCapability("screen.control","Private app control",privateOnly=true,risk="action"),
        DeviceCapability("notifications.read","Selected app notifications",privateOnly=true),
        DeviceCapability("notifications.reply","Notification replies",privateOnly=true,risk="commit"),
        DeviceCapability("intents","App and productivity drafts",risk="commit"),
        DeviceCapability("phone.control","Phone controls",risk="action"),
        DeviceCapability("location","Location fixes",Manifest.permission.ACCESS_COARSE_LOCATION),
        DeviceCapability("sensors","Sensor readings"), DeviceCapability("images.ocr","Local text recognition"),
        DeviceCapability("images.barcode","Barcode recognition"), DeviceCapability("translation","Local translation",risk="action"),
        DeviceCapability("speech.transcribe","Owner-started dictation",Manifest.permission.RECORD_AUDIO),
        DeviceCapability("alerts","Approved recipient alerts",risk="commit"),
        DeviceCapability("connections.send","Sends to your connections (webhooks)",risk="commit"),
        DeviceCapability("context.read","Read Automated Context"),
        DeviceCapability("modes.set","Change the phone's mode (home, away, night…)",risk="action"),
        DeviceCapability("workflows","Local workflows",risk="action"), DeviceCapability("decisions","Jev and Clef questions from workflows (paid)",risk="action"), DeviceCapability("monitoring","Camera monitoring",Manifest.permission.CAMERA),
        DeviceCapability("peripherals","Bluetooth, USB and NFC",privateOnly=true,risk="commit"),
        DeviceCapability("app.foreground","Bring Bridgit to the foreground",risk="action"),
        DeviceCapability("owner.configuration","App configuration and credentials",risk="commit"),
        DeviceCapability("diagnostics","Device diagnostics"), DeviceCapability("audit.read","Own activity history")
    )
    fun legacy(scope: String): Policy.Capability? = when(scope) { "camera.snapshot" -> Policy.Capability.CAMERA; "microphone.clip" -> Policy.Capability.MIC; "gallery.read" -> Policy.Capability.GALLERY; else -> null }
    fun visible(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return context.granted(Manifest.permission.POST_NOTIFICATIONS) && NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            nm.getNotificationChannel(BridgeApp.CAPTURE_CHANNEL)?.importance?.let { it > NotificationManager.IMPORTANCE_NONE } == true
    }
    /** Feature availability is broad by default; every call still requires its separate owner-issued grant.
     * Persisted false values remain authoritative, including after pairing or an app update. */
    val DEFAULT_ON = all.map { it.id }.toSet()
    fun enabled(app: BridgeApp, id: String): Boolean = legacy(id)?.let { app.policy.isEnabled(it) } ?: (app.platform.store.setting("enabled:$id",(id in DEFAULT_ON).toString()) == "true")
    fun setEnabled(app: BridgeApp, id: String, on: Boolean) {
        require(all.any { it.id == id }); val legacy = legacy(id)
        if (legacy != null) app.policy.setEnabled(legacy,on) else app.platform.store.setting("enabled:$id",on.toString(),true)
        if (!on) app.platform.invalidateScope(id)
        if (legacy == null) app.authority.switchChanged(id, on) // legacy switches report through Settings.onSwitch
    }
    fun blockers(app: BridgeApp, cap: DeviceCapability): List<String> = buildList {
        if (cap.privateOnly && !BuildConfig.PRIVATE_CONTROL) add("private_build_required")
        if (!enabled(app,cap.id)) add("feature_disabled")
        if (cap.permission != null && !app.granted(cap.permission) && !(cap.id == "gallery.read" && Gallery(app).accessMode() != null)) add("permission_required")
        if (cap.id in setOf("camera.snapshot","microphone.clip","screen.observe","screen.control","monitoring","speech.transcribe") && !visible(app)) add("notifications_required")
        if (cap.id in setOf("camera.snapshot","monitoring") && BridgeService.running?.cameraReady != true) add("open_app_for_camera")
        if (cap.id == "location" && BridgeService.running?.locationReady != true && !ControlCenterActivity.foreground) add("open_app_for_location")
        if (cap.id == "microphone.clip" && BridgeService.running?.micReady != true) add("open_app_for_microphone")
        if (cap.id == "screen.control" && DeviceControlService.instance == null) add("accessibility_required")
        if (cap.id == "screen.observe" && DeviceControlService.instance == null && ProjectionService.instance?.active != true) add("screen_share_required")
        if (cap.id.startsWith("notifications.") && NotificationBridge.instance == null) add("notification_access_required")
    }
    fun describe(app: BridgeApp, agent: Policy.Agent): JSONObject {
        val grants = app.platform.store.grants(agent.id)
        return JSONObject().put("device_id",app.platform.store.deviceId)
        .put("device_name",app.platform.store.setting("device_name",Build.MODEL)).put("bridgit_name",cc.bridgit.app.feedback.DeviceNames.hint(app,agent.id)).put("android_api",Build.VERSION.SDK_INT)
        .put("app_version",BuildConfigInfo.versionName(app)).put("mode",if (BuildConfig.PRIVATE_CONTROL) "private" else "play")
        .put("grant_mode", if (app.agents.get(agent.id)?.maximumControl == true) "maximum" else "limited")
        .put("services",cc.bridgit.app.judgment.ServiceCatalog.links()).put("epoch",app.platform.epoch).put("capabilities",JSONArray(all.map { cap ->
            JSONObject().put("id",cap.id).put("label",cap.label).put("version",1).put("supported",!cap.privateOnly || BuildConfig.PRIVATE_CONTROL)
                .put("enabled",enabled(app,cap.id)).put("granted",app.platform.hasGrant(agent.id,cap.id))
                .put("grant_lifetime", if (!grants.has(cap.id)) JSONObject.NULL else if (grants.optLong(cap.id) == Long.MAX_VALUE) "until_revoked" else "timed")
                .put("grant_expires_at", if (!grants.has(cap.id) || grants.optLong(cap.id) == Long.MAX_VALUE) JSONObject.NULL else grants.getLong(cap.id))
                .put("ready",blockers(app,cap).isEmpty() && app.platform.hasGrant(agent.id,cap.id)).put("blockers",JSONArray(blockers(app,cap)))
                .put("risk",cap.risk).put("private_only",cap.privateOnly)
        })).put("limits",JSONObject().put("body_bytes",1024*1024).put("ui_controllers_per_display",1).put("max_workflow_steps",32))
    }
}
