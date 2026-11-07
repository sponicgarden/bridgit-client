package cc.bridgit.app.capability

import io.ktor.server.routing.Route
import kotlinx.coroutines.sync.Mutex
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.api.CapInfo
import cc.bridgit.app.api.EndpointSpec
import cc.bridgit.app.api.ErrorCatalog
import org.json.JSONObject

/**
 * A capability module (ARC-05): its switch, the Android permissions it needs,
 * its endpoints, and a status() used by /v1/capabilities, Features and Access.
 * Adding a capability means adding one module to [Registry].
 */
abstract class Capability(protected val app: BridgeApp) {
    abstract val id: String
    abstract val titleRes: Int
    abstract val descriptionRes: Int
    /** Feature enablement screen group (UI-18). */
    abstract val group: String
    /** Master switch key, or null for always-on modules (status, log). */
    open val switchKey: String? = null
    open val permissions: List<String> = emptyList()
    abstract val endpoints: List<EndpointSpec>

    val title: String get() = app.getString(titleRes)
    val enabled: Boolean get() = switchKey?.let { app.settings.isOn(it) } ?: true

    /** Null when ready; otherwise a blocked_reason like "permission:CAMERA" or "open_app_once" (API-09). */
    open fun blockedReason(): String? = null
    open fun details(): JSONObject = JSONObject()

    fun status(): CapInfo {
        val blocked = blockedReason()
        return CapInfo(id, title, switchKey, enabled, blocked == null, blocked, details())
    }

    abstract fun Route.install(api: ApiServer)

    protected fun missingPermission(): String? =
        permissions.firstOrNull { !app.granted(it) }?.let { "permission:" + it.substringAfterLast('.') }

    /** Throws the right error unless the switch is on and the module is ready (PRD-05: fail closed). */
    protected fun requireUsable() {
        if (!enabled) throw ErrorCatalog.error("capability_disabled", "$title is switched off on the phone. Ask the owner to turn it on in Feature enablement.",
            mapOf("setting" to switchKey))
        val b = blockedReason() ?: return
        when {
            b.startsWith("permission:") -> throw ErrorCatalog.error("permission_denied",
                "Bridgit doesn't have the ${b.removePrefix("permission:")} permission. The owner can grant it on the Hardware access screen.",
                mapOf("permission" to "android.permission." + b.removePrefix("permission:")))
            b == "notifications_off" -> throw ErrorCatalog.error("notifications_required",
                "Bridgit's notifications are off, so captures are refused. Ask the owner to turn them on.")
            b.endsWith("_access_off") || b == "location_off" -> throw ErrorCatalog.error("access_needed",
                "$title needs Android access. Ask the owner to enable $b on the Hardware access or Software access screen.", mapOf("setting" to b))
            b == "android_version" -> throw ErrorCatalog.error("not_supported",
                "$title needs a newer Android version than this phone has.")
            b == "no_hardware" -> throw ErrorCatalog.error("not_supported", "This device has no ${title.lowercase()}.")
            b == "open_app_once" -> throw ErrorCatalog.error("service_start_blocked",
                "Android hasn't given Bridgit's listener the $title yet. Ask the owner to open Bridgit once.")
            else -> throw ErrorCatalog.error("service_start_blocked", "$title isn't ready: $b")
        }
    }
}

/** Every capability module in this build. Unbuilt capabilities are simply absent. */
class Registry(app: BridgeApp) {
    /** One capture per sensor at a time (NFR-11). */
    val cameraLock = Mutex()
    val micLock = Mutex()
    /** A camera warmed up ahead of a photo (POST /v1/camera/arm); holds [cameraLock] while armed. */
    val cameraArm = CameraArm(cameraLock)
    /** On-phone body tracking sessions (POST /v1/camera/pose/start); each holds [cameraLock] while it runs. */
    val pose = PoseSessions(cameraLock)

    val modules: List<Capability> = listOf(
        cc.bridgit.app.ssh.SshModule(app),
        cc.bridgit.app.ownerapi.OwnerFeaturesModule(app),
        cc.bridgit.app.appdevices.AppDeviceModule(app), cc.bridgit.app.lan.LanModule(app, "lan_discovery"), cc.bridgit.app.lan.LanModule(app, "lan_enrollment"), cc.bridgit.app.lan.LanModule(app, "lan_control"),
        cc.bridgit.app.lancamera.CameraAudioModule(app,"lan_camera_audio"), cc.bridgit.app.lancamera.CameraAudioModule(app,"lan_camera_speaker"),
        cc.bridgit.app.lancamera.LanCameraModule(app), StatusModule(app), CameraModule(app, this), MicModule(app, this), cc.bridgit.app.listen.WakeWordsModule(app), cc.bridgit.app.listen.SoundsModule(app), GalleryModule(app), ScreenModule(app), LogModule(app),
        cc.bridgit.app.feedback.FeedbackModule(app), cc.bridgit.app.judgment.JudgmentModule(app), cc.bridgit.app.watch.WatchModule(app), cc.bridgit.app.outbound.OutboundModule(app),
        cc.bridgit.app.interact.InteractionModule(app),
        LocationModule(app), cc.bridgit.app.weather.WeatherModule(app), SpeakerModule(app), cc.bridgit.app.tour.TourModule(app),
        cc.bridgit.app.planned.ScreenShareModule(app),
        cc.bridgit.app.planned.PhoneModule(app), cc.bridgit.app.planned.ControlsModule(app),
        cc.bridgit.app.planned.TorchModule(app), cc.bridgit.app.planned.RingModule(app),
        cc.bridgit.app.planned.SensorsModule(app), cc.bridgit.app.planned.UsageModule(app),
        cc.bridgit.app.planned.NotificationsModule(app), cc.bridgit.app.planned.HealthModule(app),
        cc.bridgit.app.planned.TransferModule(app, "files"), cc.bridgit.app.planned.TransferModule(app, "shares"),
        cc.bridgit.app.planned.TransferModule(app, "downloads"), cc.bridgit.app.planned.ClipboardModule(app),
        cc.bridgit.app.planned.VideoModule(app), cc.bridgit.app.planned.GalleryVideoModule(app), cc.bridgit.app.planned.OcrModule(app), cc.bridgit.app.vision.FindCropModule(app),
        AppSettingsModule(app),
        cc.bridgit.app.schedule.ScheduleModule(app),
        cc.bridgit.app.applets.AppletModule(app),
        cc.bridgit.app.applets.BudgetModule(app),
        cc.bridgit.app.applets.AppletPortalsModule(app),
        cc.bridgit.app.applets.AppletDataModule(app),
        cc.bridgit.app.platform.ConnectionsModule(app),
        cc.bridgit.app.platform.ModesModule(app), cc.bridgit.app.context.ContextModule(app),
        cc.bridgit.app.capture.CapturesModule(app),
        cc.bridgit.app.adb.AdbModule(app),
        cc.bridgit.app.portal.PortalModule(app),
    ).filterNot { cc.bridgit.app.BuildConfig.PLAY_STORE && it.id in PLAY_EXCLUDED }

    companion object {
        /** Left out of the Google Play build: Play policy restricts health data, app usage and other apps' notifications. */
        val PLAY_EXCLUDED = setOf("health_connect", "usage", "notifications")
    }

    fun caps(): List<cc.bridgit.app.api.CapInfo> = modules.map { it.status() }
    fun switchable(): List<Capability> = modules.filter { it.switchKey != null }
    fun byId(id: String) = modules.firstOrNull { it.id == id }
}
