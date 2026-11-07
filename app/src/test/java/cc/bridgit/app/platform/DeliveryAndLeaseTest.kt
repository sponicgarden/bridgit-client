package cc.bridgit.app.platform

import cc.bridgit.app.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DeliveryAndLeaseTest {
    @Before fun enableOwnerListener() { app.settings.listenerEnabled=true }
    private val app get()=RuntimeEnvironment.getApplication() as BridgeApp
    private fun rejects(code:String,block:()->Unit) { try { block(); fail("Expected $code") } catch(e:ApiError) { assertEquals(code,e.code) } }
    @Test fun destinationAddressRulesRejectLocalMetadataAndPreserveExplicitPrivateScope() {
        for(ip in listOf("127.0.0.1","0.0.0.0","169.254.169.254","224.0.0.1","::1","fe80::1")) {
            val address=InetAddress.getByName(ip); assertFalse(ip,DeliveryManager.publicAddress(address)); assertFalse(ip,DeliveryManager.privateAddress(address))
        }
        for(ip in listOf("10.1.2.3","192.168.1.2","100.94.27.79","fc00::123")) { val address=InetAddress.getByName(ip); assertFalse(ip,DeliveryManager.publicAddress(address)); assertTrue(ip,DeliveryManager.privateAddress(address)) }
        for(ip in listOf("8.8.8.8","1.1.1.1","2001:4860:4860::8888")) assertTrue(ip,DeliveryManager.publicAddress(InetAddress.getByName(ip)))
    }
    @Test fun callbackSubscriptionStartsAtRealCursorRatherThanAnUnreachableSentinel() {
        val a=app.policy.addAgent("CallbackTester").first
        val destination=app.platform.delivery.destination("Test endpoint","https://example.com/events",false)
        app.platform.store.emit(a.id,"prior")
        app.platform.delivery.subscribe(a.id,destination.getString("id"))
        val cursor=app.platform.store.setting("callback_cursor:${a.id}").toLong()
        assertEquals(app.platform.store.latestCursor(),cursor)
        app.platform.store.emit(a.id,"next")
        assertEquals("next",app.platform.store.events(a.id,cursor).getJSONArray("events").getJSONObject(0).getString("type"))
    }
    @Test fun signedEventsBindBodyNonceTimestampAndEventIdentity() { val secret="test-only-secret"; val original=DeliveryManager.hmac(secret,"123.nonce.event.body"); assertEquals(original,DeliveryManager.hmac(secret,"123.nonce.event.body")); for(changed in listOf("124.nonce.event.body","123.other.event.body","123.nonce.other.body","123.nonce.event.tampered")) assertNotEquals(original,DeliveryManager.hmac(secret,changed)) }
    @Test fun fullOutboxRetainsCallbackCursorUntilSpaceIsAvailable() {
        val actor=app.policy.addAgent("BackpressureTester").first
        val destination=app.platform.delivery.destination("Test endpoint","https://example.com/events",false).getString("id")
        app.platform.delivery.subscribe(actor.id,destination)
        repeat(99) { app.platform.store.save(actor.id,"outbox",JSONObject().put("status","queued")) }
        val first=app.platform.store.emit(actor.id,"first"); val second=app.platform.store.emit(actor.id,"second")
        app.platform.delivery.queueCallbacks(actor.id,destination)
        assertEquals(first,app.platform.store.setting("callback_cursor:${actor.id}").toLong())
        assertEquals(100,app.platform.store.list(actor.id,"outbox",1000).size)
        val old=app.platform.store.list(actor.id,"outbox",1000).first { !it.has("payload") }
        app.platform.store.save(actor.id,"outbox",old.put("status","accepted"))
        app.platform.delivery.queueCallbacks(actor.id,destination)
        assertEquals(second,app.platform.store.setting("callback_cursor:${actor.id}").toLong())
        assertEquals(listOf("first","second"),app.platform.store.list(actor.id,"outbox",1000).filter { it.has("payload") }.map { it.getJSONObject("payload").getString("type") }.sorted())
    }
    @Test fun recipientCredentialCannotAcknowledgeADifferentIncidentOrDestination() {
        val actor=app.policy.addAgent("AlertTester").first; Capabilities.setEnabled(app,"alerts",true); app.platform.grant(actor.id,"alerts",System.currentTimeMillis()+60000)
        val dest=app.platform.delivery.destination("Consenting test recipient","https://example.com/events",false)
        val alert=app.platform.delivery.trigger(actor.id,JSONObject().put("destinations",JSONArray().put(dest.getString("id"))).put("text","Synthetic only"))
        rejects("unauthorized") { app.platform.delivery.ack(alert.getString("id"),dest.getString("id"),"wrong") }
        val saved=app.platform.store.require(alert.getString("id"),actor.id)
        val token=DeliveryManager.hmac(saved.getString("ack_secret"),dest.getString("id"))
        assertEquals("acknowledged",app.platform.delivery.ack(alert.getString("id"),dest.getString("id"),token).getString("status"))
        assertEquals("acknowledged",app.platform.delivery.ack(alert.getString("id"),dest.getString("id"),token).getString("status"))
        rejects("unauthorized") { app.platform.delivery.ack(alert.getString("id"),"different-recipient",token) }
    }
    @Test fun controllerLeaseIsExclusiveAndOldFenceCannotRegainAuthority() {
        if(!BuildConfig.PRIVATE_CONTROL) return
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        Capabilities.setEnabled(app,"screen.control",true)
        val service=Robolectric.buildService(DeviceControlService::class.java).create().get()
        val field=DeviceControlService::class.java.getDeclaredField("instance").apply { isAccessible=true }; field.set(null,service)
        try {
            fun pending(name:String):Pair<Policy.Agent,JSONObject> {
                val agent=app.policy.addAgent(name).first; app.platform.grant(agent.id,"screen.control",System.currentTimeMillis()+60000)
                val record=app.platform.store.save(agent.id,"session",JSONObject().put("status","awaiting_owner").put("apps",JSONArray().put("example.fixture")).put("duration_seconds",60).put("request_expires_at",System.currentTimeMillis()+60000).put("purpose","Fixture"))
                return agent to record
            }
            val (a,first)=pending("LeaseA");val (b,second)=pending("LeaseB")
            app.platform.approveSession(first.getString("id"));val fence=app.platform.session(a.id,first.getString("id")).getString("fence")
            rejects("controller_busy") { app.platform.approveSession(second.getString("id")) }
            app.platform.closeSession(app.platform.store.require(first.getString("id"),a.id),"stopped")
            app.platform.approveSession(second.getString("id"))
            assertNotEquals(fence,app.platform.session(b.id,second.getString("id")).getString("fence"))
            rejects("session_inactive") { app.platform.session(a.id,first.getString("id")) }
        } finally { field.set(null,null) }
    }
    @Test fun mcpMediaIsReturnedAsMediaContentRatherThanBase64Text() {
        val api=PlatformApi(app); val method=PlatformApi::class.java.getDeclaredMethod("mcpResult",JSONObject::class.java).apply { isAccessible=true }
        val input=JSONObject().put("image",JSONObject().put("mime","image/jpeg").put("data_base64","ZmFrZS1waXhlbHM="))
        val result=method.invoke(api,input) as JSONObject
        assertEquals("image",result.getJSONArray("content").getJSONObject(1).getString("type"))
        assertFalse(result.getJSONArray("content").getJSONObject(0).getString("text").contains("ZmFrZS1waXhlbHM="))
    }
}
