package cc.bridgit.app.core

import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.api.McpTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** SET-01..05: the settings catalog, its checks and the locks agents can't get past. */
class SettingsCatalogTest {
    private val switches = listOf("camera" to "Camera", "controls" to "Sound and display", SettingsCatalog.GATE_SWITCH to "App settings")
    private val all = SettingsCatalog.all(switches, listOf("questions" to "Questions"), listOf("android.permission.CAMERA", "android.permission.CAMERA"))
    private fun def(key: String) = all.first { it.key == key }
    private fun ok(key: String, v: Any?) = (SettingsCatalog.parse(def(key), v) as SettingsCatalog.Parsed.Ok).value
    private fun bad(key: String, v: Any?) = assertTrue("$key accepted $v", SettingsCatalog.parse(def(key), v) is SettingsCatalog.Parsed.Bad)

    @Test fun keysAreUniqueAndInKnownSections() {
        assertEquals(all.size, all.map { it.key }.toSet().size)
        all.forEach { assertTrue(it.key, it.section in SettingsCatalog.SECTIONS) }
        assertEquals(1, all.count { it.key == "android.permission.CAMERA" })
    }

    @Test fun theGateAndAnythingThatCutsAgentsOffOrWidensAccessIsLocked() {
        assertEquals(SettingLock.GATE, def("features.settings_api").lock)
        assertNull(def("features.camera").lock)
        mapOf("alerts.email" to SettingLock.OWNER,
            "listening.wake_words" to SettingLock.OWNER, "listening.sounds" to SettingLock.OWNER,
            "agent.fixer_mode" to SettingLock.OWNER, "agent.super_powers" to SettingLock.OWNER, "captures.owner_upload_url" to SettingLock.OWNER, "captures.locked_camera_approval" to SettingLock.OWNER, "security.device_lock" to SettingLock.OWNER,
            "security.relay_signed_only" to SettingLock.OWNER, "security.biometric" to SettingLock.OWNER, "agent.capabilities" to SettingLock.OWNER, "agent.name" to SettingLock.OWNER,
            "alerts.state" to SettingLock.STATUS, "notifications.questions" to SettingLock.ANDROID,
            "android.permission.CAMERA" to SettingLock.ANDROID, "android.modify_system_settings" to SettingLock.ANDROID,
        ).forEach { (k, lock) -> assertEquals(k, lock, def(k).lock) }
        all.filter { it.section == "android" || it.section == "agent" || it.section == "notifications" }.forEach { assertFalse(it.key, it.writable) }
    }

    @Test fun everyWritableSettingHasACheckableType() {
        all.filter { it.writable }.forEach { assertTrue(it.key, it.type in setOf("boolean", "integer", "number", "string")) }
        all.filter { it.type == "integer" || it.type == "number" }.forEach { assertNotNull(it.key, it.min); assertNotNull(it.key, it.max) }
    }

    @Test fun valuesAreChecked() {
        assertEquals(true, ok("features.camera", true)); bad("features.camera", "true"); bad("features.camera", 1)
        assertEquals(90, ok("log.retention_days", 90)); assertEquals(90, ok("log.retention_days", 90.0))
        assertEquals(0, ok("log.retention_days", 0)); assertEquals(6, ok("log.retention_days", 6)); bad("log.retention_days", -1); bad("log.retention_days", 366); bad("log.retention_days", 90.5); bad("log.retention_days", "90")
        assertEquals("dark", ok("appearance.theme", "dark")); bad("appearance.theme", "blue"); bad("appearance.theme", null)
        assertEquals(0.5, ok("phone.brightness", 0.5)); assertEquals(1.0, ok("phone.brightness", 1)); bad("phone.brightness", 1.5)
        assertEquals(0, ok("phone.volume_media", 0)); bad("phone.volume_alarm", 101)
        bad("listening.wake_words", "[]")
    }

    @Test fun phoneConversionsRoundTrip() {
        SettingsCatalog.DND_MODES.forEach { assertEquals(it, SettingsCatalog.filterToDnd(SettingsCatalog.dndToFilter(it))) }
        SettingsCatalog.RINGER_MODES.forEach { assertEquals(it, SettingsCatalog.modeToRinger(SettingsCatalog.ringerToMode(it))) }
        assertEquals(15, SettingsCatalog.percentToStep(100, 15)); assertEquals(0, SettingsCatalog.percentToStep(0, 15))
        assertEquals(8, SettingsCatalog.percentToStep(50, 15)); assertEquals(53, SettingsCatalog.stepToPercent(8, 15))
        assertEquals(0, SettingsCatalog.stepToPercent(3, 0))
        assertEquals(1, SettingsCatalog.brightnessToLevel(0.0)); assertEquals(255, SettingsCatalog.brightnessToLevel(1.0))
        assertEquals(0.5, SettingsCatalog.levelToBrightness(128), 0.01)
        assertEquals("off", SettingsCatalog.importance(0)); assertEquals("high", SettingsCatalog.importance(4))
    }

    @Test fun endpointsErrorsAndMcpToolsExist() {
        assertTrue(Endpoints.SETTINGS_GET in Endpoints.all && Endpoints.SETTINGS_SET in Endpoints.all)
        (Endpoints.SETTINGS_GET.errors + Endpoints.SETTINGS_SET.errors).forEach { ErrorCatalog.http(it) }
        assertEquals(403, ErrorCatalog.http("setting_locked"))
        assertEquals("settings_get", McpTools.name(Endpoints.SETTINGS_GET))
        assertEquals("settings_post", McpTools.name(Endpoints.SETTINGS_SET))
        assertEquals("object", McpTools.inputSchema(Endpoints.SETTINGS_SET).getJSONObject("properties").getJSONObject("changes").getString("type"))
    }

    /** Every property of core.Settings must be in the catalog, or listed here as not a setting. */
    @Test fun everySettingsPropertyIsCatalogued() {
        val src = File("src/main/java/cc/bridgit/app/core/Settings.kt").readText()
        val props = Regex("""^\s*var (\w+)""", RegexOption.MULTILINE).findAll(src).map { it.groupValues[1] }.toSet()
        val mapped = mapOf("listenerEnabled" to "listener.enabled", "port" to "listener.port", "logVisibility" to "privacy.log_visibility",
            "retentionEntries" to "log.retention_entries", "retentionDays" to "log.retention_days", "appearance" to "appearance.theme",
            "speakerCeiling" to "speaker.volume_ceiling", "feedbackUpload" to "privacy.feedback_upload",
            "allowInternetCallbacks" to "listener.allow_internet_callbacks", "alertFrequency" to "alerts.frequency",
            "alertEmail" to "alerts.email", "alertState" to "alerts.state", "wakeWords" to "listening.wake_words",
            "soundsConfig" to "listening.sounds", "capturesAgentFiles" to "captures.agent_files",
            "capturesAgentUpload" to "captures.agent_upload", "capturesMaxKeepMinutes" to "captures.max_keep_minutes",
            "capturesShareAll" to "captures.any_agent_can_fetch", "capturesOwnerFiles" to "captures.owner_files",
            "capturesOwnerUploadUrl" to "captures.owner_upload_url", "capturesLockedCameraApproval" to "captures.locked_camera_approval",
            "securityDeviceLock" to "security.device_lock", "securityPlaceAlerts" to "security.new_place_alerts",
            "securityBiometric" to "security.biometric", "securityRelaySignedOnly" to "security.relay_signed_only",
            "externalCallsPaused" to "privacy.pause_external_calls",
            "resourceMonitor" to "device.resource_monitor", "resourceMonitorInterval" to "device.resource_monitor_interval")
        val notSettings = setOf("onSwitch", "firstRunDone", "emailGraceStartedAt", "emailVerifiedAt")
        assertEquals("New Settings property: add it to SettingsCatalog and this map", emptySet<String>(), props - mapped.keys - notSettings)
        mapped.values.forEach { k -> assertTrue(k, all.any { it.key == k }) }
    }
}
