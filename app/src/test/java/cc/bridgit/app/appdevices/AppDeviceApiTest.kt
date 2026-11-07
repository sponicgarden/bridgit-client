package cc.bridgit.app.appdevices

import cc.bridgit.app.api.*
import cc.bridgit.app.core.SettingsCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppDeviceApiTest {
    @Test fun exposureRequiresExistingDeviceGrantsAndEnrollmentOwnership() {
        val cameras = setOf("lan:dog"); val own = setOf("net:mine")
        assertFalse(AppDeviceRules.visible("lan:dog", cameras, own, false, true))
        assertTrue(AppDeviceRules.visible("lan:dog", cameras, own, true, false))
        assertTrue(AppDeviceRules.visible("net:mine", cameras, own, false, true))
        assertFalse(AppDeviceRules.visible("net:other-agent", cameras, own, true, true))
        assertFalse(AppDeviceRules.visible("missing", cameras, own, true, true))
    }
    @Test fun filteringOnlyShowsAssignedDevicesAndRemovalStaysAbsent() {
        fun d(id: String) = JSONObject().put("id", id).put("name", "Safe label")
        val devices = listOf(d("lan:dog"), d("lan:unassigned"))
        val assignments = mutableMapOf("lan:dog" to setOf(AppDeviceAccess.DOG))
        val list = AppDeviceRules.filter(devices, AppDeviceAccess.DOG) { assignments[it] ?: emptySet() }
        assertEquals(1, list.length()); assertEquals("lan:dog", list.getJSONObject(0).getString("id"))
        assignments.clear()
        assertEquals(0, AppDeviceRules.filter(devices, AppDeviceAccess.DOG) { assignments[it] ?: emptySet() }.length())
        assertEquals(0, AppDeviceRules.filter(emptyList(), null) { setOf(AppDeviceAccess.DOG) }.length())
        assertThrows(IllegalArgumentException::class.java) { AppDeviceRules.filter(devices, "arbitrary_app") { emptySet() } }
    }
    @Test fun assignmentChangesAreExplicitAndCannotNameUnknownApplets() {
        assertEquals(emptySet<String>(), AppDeviceRules.assignments(JSONArray()))
        assertEquals(setOf(AppDeviceAccess.DOG), AppDeviceRules.assignments(JSONArray().put(AppDeviceAccess.DOG)))
        assertThrows(IllegalArgumentException::class.java) { AppDeviceRules.assignments(JSONArray().put("unknown")) }
    }
    @Test fun revocationStopsActiveAndAutomaticCameraUseWithoutStoppingUnrelatedPhoneCamera() {
        assertTrue(AppDeviceRules.shouldStopMonitor(true, false, listOf("lan:dog"), setOf("lan:dog")) { false })
        assertTrue(AppDeviceRules.shouldStopMonitor(true, true, listOf("rear"), setOf("lan:scanning")) { true })
        assertFalse(AppDeviceRules.shouldStopMonitor(true, false, listOf("rear"), setOf("lan:other")) { true })
        assertFalse(AppDeviceRules.shouldStopMonitor(false, true, listOf("lan:dog"), setOf("lan:dog")) { false })
        assertFalse(AppDeviceRules.shouldStopMonitor(true, true, listOf("rear"), setOf("net:relay")) { true })
    }
    @Test fun httpMcpAndOpenApiHaveTheSameOwnerControlledEndpoints() {
        val specs = AppDeviceModule.ALL + cc.bridgit.app.lan.LanModule.ALL
        specs.forEach { s -> assertTrue(Endpoints.all.contains(s)); assertTrue(McpTools.specs.contains(s)) }
        for (id in listOf("lan_discovery", "lan_enrollment", "lan_control", "app_device_access")) {
            org.junit.Assert.assertNull("Maximum-control configuration supports this feature switch", SettingsCatalog.features(listOf(id to id)).single().lock)
        }
        val enroll = cc.bridgit.app.lan.LanModule.ENROLL
        assertTrue(enroll.privateFields.containsAll(listOf("username", "password")))
        assertTrue(AppDeviceModule.SET.idempotent)
        for ((spec, field) in listOf(AppDeviceModule.SET to "applets", cc.bridgit.app.lan.LanModule.DISCOVER to "hosts")) {
            val item = McpTools.inputSchema(spec).getJSONObject("properties").getJSONObject(field).getJSONObject("items")
            assertEquals("string", item.getString("type"))
        }
    }
}
