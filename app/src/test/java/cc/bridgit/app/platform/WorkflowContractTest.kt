package cc.bridgit.app.platform

import cc.bridgit.app.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class WorkflowContractTest {
    @Before fun enableOwnerListener() { app.settings.listenerEnabled=true }
    private val app get()=(RuntimeEnvironment.getApplication() as BridgeApp)
    private fun definition()=JSONObject().put("name","Help panel").put("trigger",JSONObject().put("type","button")).put("steps",JSONArray().put(JSONObject().put("type","notice").put("title","Help requested").put("text","Please check the panel")))
    private fun rejects(block:()->Unit) { try { block(); fail("Expected rejection") } catch(e:ApiError) { assertTrue(e.http in listOf(403,409,422)) } }
    @Test fun validationDerivesAuthorityRatherThanTrustingTheAgent() { val result=app.platform.workflows.validate(definition()); assertTrue(result.getBoolean("valid")); assertEquals(setOf("workflows","interactions"),result.strings("required_scopes").toSet()); rejects { app.platform.workflows.validate(definition().put("required_scopes",JSONArray())) } }
    @Test fun arbitraryCodeNetworkingAndUnboundedWorkAreRejected() {
        for(type in listOf("shell","javascript","http","loop","download")) rejects { app.platform.workflows.validate(definition().put("steps",JSONArray().put(JSONObject().put("type",type)))) }
        rejects { app.platform.workflows.validate(definition().put("steps",JSONArray((1..33).map { JSONObject().put("type","event").put("text","test") }))) }
        rejects { app.platform.workflows.validate(definition().put("trigger",JSONObject().put("type","interval").put("seconds",1))) }
    }
    @Test fun geofenceAndNativeTemplatesAreValidated() {
        assertEquals(200, WorkflowDefinition.geofenceRadius(JSONObject()))
        assertEquals(140, WorkflowDefinition.geofenceRadius(JSONObject().put("radius_m",140)))
        val def=definition().put("trigger",JSONObject().put("type","geofence").put("latitude",52.0).put("longitude",21.0).put("radius_m",200)).put("ui",JSONObject().put("template","help").put("title","Help"))
        assertTrue(app.platform.workflows.validate(def).strings("required_scopes").contains("location"))
        rejects { app.platform.workflows.validate(def.put("ui",JSONObject().put("template","webview").put("html","<script/>"))) }
        rejects { app.platform.workflows.validate(definition().put("trigger",JSONObject().put("type","geofence").put("latitude",999).put("longitude",21))) }
    }
    @Test fun newAgentCannotEnableOrUseItsOwnAuthority() {
        val agent=app.policy.addAgent("ScopeTester").first
        assertFalse(app.platform.hasGrant(agent.id,"screen.control"))
        rejects { app.platform.mutate(agent.id,"device.action",JSONObject().put("type","open_link").put("url","https://example.com"),"request-one") }
        assertEquals(0,app.platform.store.list(agent.id,"operation").size)
    }
    @Test fun sensitiveActionsWaitForPhoneApprovalAndIdempotentRetryKeepsOneRequest() {
        val agent=app.policy.addAgent("ApprovalTester").first
        Capabilities.setEnabled(app,"intents",true); app.platform.grant(agent.id,"intents",System.currentTimeMillis()+60000)
        val args=JSONObject().put("type","open_link").put("url","https://example.com")
        val first=app.platform.mutate(agent.id,"device.action",args,"approval-key")
        assertEquals("awaiting_owner",first.getString("status")); assertFalse(first.has("input"))
        val retry=app.platform.mutate(agent.id,"device.action",args,"approval-key")
        assertEquals(first.getString("id"),retry.getString("id")); assertEquals(1,app.platform.store.list(agent.id,"operation").size)
        app.platform.revoke(agent.id,"intents")
        assertEquals("cancelled",app.platform.store.require(first.getString("id"),agent.id).getString("status"))
        rejects { app.platform.approveOperation(first.getString("id")) }
    }
    @Test fun grantedPhoneControlsRunWithoutAnOwnerTap() {
        val agent=app.policy.addAgent("UnattendedTester").first
        Capabilities.setEnabled(app,"phone.control",true); app.platform.grant(agent.id,"phone.control",System.currentTimeMillis()+60000)
        val op=app.platform.mutate(agent.id,"device.action",JSONObject().put("type","flashlight").put("on",false),"unattended-key")
        assertNotEquals("awaiting_owner",op.getString("status"))
        assertFalse(app.platform.store.events(agent.id,0).toString().contains("operation.awaiting_owner"))
    }
    @Test fun onlyPrivacyAndSecurityActionsWaitForTheOwner() {
        fun tap(command:String,type:String?=null)=OwnerApprovalPolicy.needsOwnerTap(command,JSONObject().apply { if(type!=null) put("type",type) })
        for(type in OwnerApprovalPolicy.unattendedDeviceActions) assertFalse(type,tap("device.action",type))
        assertFalse(tap("translation.download"))
        for(type in listOf("camera.snapshot","microphone.clip","clipboard","speak","open_link","open_app","message_draft","dial")) assertTrue(type,tap("device.action",type))
        assertTrue(tap("device.action"))
        for(command in listOf("ui.action","ui.sequence","notification.reply","peripheral.action","speech.transcribe","stream.start","alert.trigger","owner.configure")) assertTrue(command,tap(command))
    }
    @Test fun questionAnswersAreFirstWriterWinsAndIdentityIsSnapshotted() {
        val agent=app.policy.addAgent("QuestionTester").first
        val q=app.platform.interaction(agent.id,JSONObject().put("kind","question").put("title","Choose").put("choices",JSONArray().put("A").put("B")))
        app.policy.updateProfile(agent.id,JSONObject().put("name","RenamedTester"))
        app.platform.answer(q.getString("id"),"A")
        val answered=app.platform.store.require(q.getString("id"),agent.id,"interaction")
        assertEquals("QuestionTester",answered.getJSONObject("identity").getString("name")); assertEquals("A",answered.getString("answer"))
        rejects { app.platform.answer(q.getString("id"),"B") }
        assertFalse(app.platform.store.events(agent.id,0).toString().contains("RenamedTester"))
    }
    @Test fun toolSchemasDescribeAllToolsAndMutationsRequireAnIdempotencyKey() {
        for(name in Commands.reads+Commands.mutations) {
            val schema=ToolSchemas.forTool(name); assertEquals("object",schema.getString("type"))
            assertFalse(schema.getBoolean("additionalProperties"))
            if(name in Commands.mutations) assertEquals("idempotency_key",schema.getJSONArray("required").getString(0))
        }
    }
}
