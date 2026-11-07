package cc.bridgit.app.ownerapi

import cc.bridgit.app.api.*
import cc.bridgit.app.platform.Commands
import cc.bridgit.app.platform.ToolSchemas
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OwnerActionsTest {
    private fun valid(action:String, options:String) = OwnerActions.validate(JSONObject().put("action",action).put("options",JSONObject(options)))
    private fun refuses(action:String,options:String) {
        try { valid(action,options); fail("accepted $action $options") } catch(_:ApiError) { }
    }
    @Test fun serviceBudgetUsesExactOwnerApprovalAndValidatesLimits() {
        OwnerActions.validate(JSONObject("""{"action":"service.budget.set","options":{"service":"glm","limits":[]}}"""))
        val request=JSONObject("""{"action":"service.budget.set","options":{"service":"glm","limits":[{"id":"month","usd":2,"per":"month"}]}}""")
        OwnerActions.validate(request)
        assertTrue(OwnerActions.description(request).contains("glm"))
        try { OwnerActions.validate(JSONObject("""{"action":"service.budget.set","options":{"service":"bad","limits":[]}}""")); org.junit.Assert.fail() } catch(_:cc.bridgit.app.api.ApiError) {}
        try { OwnerActions.validate(JSONObject("""{"action":"service.budget.set","options":{"service":"glm","limits":[{"usd":0,"per":"month"}]}}""")); org.junit.Assert.fail() } catch(_:cc.bridgit.app.api.ApiError) {}
    }

    @Test fun downloadSourcesAreFixedAndSecretsRejectedBeforePersistence() {
        valid("resource.download","{resource:'wake-english'}")
        valid("resource.check","{resource:'app-update'}")
        refuses("resource.download","{resource:'https://attacker.invalid/file'}")
        refuses("resource.download","{resource:'wake-english',url:'https://attacker.invalid/file'}")
        refuses("devices.membership","{device:'lan:one',apps:['dog_monitor'],password:'secret'}")
        refuses("android.setup","{target:'shell'}")
        refuses("dog.start","{super_powers:true}")
    }
    @Test fun nativeConfigurationRejectsInvalidValuesAtomically() {
        valid("dog.configure","{camera:'lan:camera-one',models:['clef','jev']}")
        valid("dog.configure","{camera:'auto'}")
        valid("dog.configure","{personal_backend:true,backend_email:'owner@gmail.com',user_alias:'rahulio',photo_sharing:true}")
        valid("dog.configure","{personal_backend:false}")
        refuses("dog.configure","{personal_backend:true}")
        refuses("dog.configure","{personal_backend:true,backend_email:'not-an-email',user_alias:'rahulio'}")
        refuses("dog.configure","{personal_backend:true,backend_email:'owner@gmail.com',user_alias:'../admin'}")
        refuses("dog.configure","{camera:'rtsp://secret@host'}")
        refuses("dog.configure","{models:[]}")
        refuses("dog.configure","{models:['arbitrary-model']}")
        refuses("devices.membership","{device:'lan:one',apps:['unknown']}")
        refuses("tour.start","{kinds:['history'],interval_s:31}")
        refuses("tour.start","{kinds:['unsupported']}")
        refuses("sounds.configure","{send_clip:'yes'}")
        refuses("remote.configure","{enabled:'true'}")
    }
    @Test fun perCameraSearchDelaysAreBoundedWholeSecondsAndCanReset() {
        valid("dog.configure", "{camera_search_delays:{rear:60,front:120,'lan:one':null}}")
        valid("dog.configure", "{camera_search_delays:{rear:10,front:3600}}")
        for (value in listOf("9", "3601", "60.5", "'60'", "true", "[]"))
            refuses("dog.configure", "{camera_search_delays:{rear:$value}}")
        refuses("dog.configure", "{camera_search_delays:[]}")
        refuses("dog.configure", "{camera_search_delays:{auto:60}}")
        refuses("dog.configure", "{camera_search_delays:{'https://host':60}}")
        assertTrue(OwnerActions.description(JSONObject("{action:'dog.configure',options:{camera_search_delays:{rear:90}}}")).contains("rear"))
    }
    @Test fun theAllCamerasSamplingIntervalIsBoundedAndCanReset() {
        valid("dog.configure", "{sampling_all_s:20}")
        valid("dog.configure", "{sampling_all_s:null,camera_search_delays:{rear:null}}")
        for (value in listOf("9", "3601", "20.5", "'20'", "true", "{}"))
            refuses("dog.configure", "{sampling_all_s:$value}")
        assertTrue(OwnerActions.description(JSONObject("{action:'dog.configure',options:{sampling_all_s:30}}")).contains("30 seconds"))
    }

    @Test fun ownerChangesAndCancellationAreDiscoverableOnBothTransports() {
        assertTrue("owner.configure" in Commands.mutations)
        assertTrue("operation.cancel" in Commands.mutations)
        assertEquals("owner.configuration",Commands.scope("owner.configure"))
        val schema=ToolSchemas.forTool("owner.configure")
        assertTrue(schema.getJSONObject("properties").has("options"))
        assertTrue(schema.getJSONArray("required").toString().contains("idempotency_key"))
        OwnerFeatureEndpoints.ALL.forEach { e ->
            assertTrue(Endpoints.all.contains(e))
            assertNotNull(McpTools.byName(McpTools.name(e)))
        }
        assertTrue(McpTools.inputSchema(OwnerFeatureEndpoints.CONFIGURE).getJSONArray("required").toString().contains("idempotency_key"))
        assertNotNull(McpTools.byName("agent_profile_get"))
        assertNotNull(McpTools.byName("agent_profile_post"))
    }

    @org.junit.Test fun multipleSensorSourcesHaveAnExplicitBoundedContract() {
        valid("dog.configure","{cameras:['rear','front','lan:living-room'],microphones:['builtin','lan:living-room']}")
        valid("dog.configure","{microphones:[]}")
        valid("dog.configure","{pan_search:true}")
        refuses("dog.configure","{pan_search:1}")
        valid("dog.configure","{autocenter:false}")
        valid("dog.configure","{autocenter:true,pan_search:false}")
        refuses("dog.configure","{autocenter:'on'}")
        refuses("dog.configure","{autocenter:1}")
        refuses("dog.configure","{camera:'auto',cameras:['rear']}")
        refuses("dog.configure","{cameras:[]}")
        refuses("dog.configure","{cameras:['auto','rear']}")
        refuses("dog.configure","{cameras:['rear','rear']}")
        refuses("dog.configure","{microphones:['https://example.com/audio']}")
        refuses("dog.configure","{microphones:['builtin','builtin']}")
    }
}
