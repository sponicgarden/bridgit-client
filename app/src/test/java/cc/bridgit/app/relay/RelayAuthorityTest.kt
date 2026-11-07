package cc.bridgit.app.relay
import cc.bridgit.app.*
import cc.bridgit.app.core.Connection
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.platform.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RelayAuthorityTest {
    private val app get()=RuntimeEnvironment.getApplication() as BridgeApp
    @Test fun maximumRelayCanReadBatteryAndDiscoverConfigurationWithoutLosingExplicitCaps()=runBlocking {
        app.settings.listenerEnabled=true
        val store=RelayStore(app);store.enabled=true
        val a=app.agents.add("FullRelay",Connection.RELAY).first
        app.agents.update(a.id) { it.copy(caps=setOf("status")) }
        store.addAgent(a.id,"connector",null,setOf("GET /v1/health"),setOf("diagnostics"))
        cc.bridgit.app.power.PowerAccess(app).grantAll(a.id)
        val c=store.agents().single()
        assertNotNull(app.agents.get(a.id)!!.caps)
        val svc=Robolectric.buildService(BridgeService::class.java).create().get()
        val api=ApiServer(svc,emptyList(),0);api.prepareRelay()
        val d=RelayDispatcher(app,api);val now=System.currentTimeMillis()
        val reply=d.dispatch(c,JSONObject().put("method","POST").put("path","/v2/call").put("body",JSONObject().put("tool","phone.status")),now+10000,now,"job")
        assertEquals(200,reply.getInt("status"))
        val tools=d.dispatch(c,JSONObject().put("method","GET").put("path","/v2/tools"),now+10000,now,"job").getJSONObject("body").getJSONArray("tools")
        assertTrue(tools.toString().contains("owner.configure"))
        assertTrue(tools.toString().contains("workflow.propose"))
        assertFalse(RelayDispatcher.allowedTools(c).contains("owner.configure"))
        app.platform.revoke(a.id,"diagnostics")
        try { d.dispatch(c,JSONObject().put("method","POST").put("path","/v2/call").put("body",JSONObject().put("tool","diagnostics")),now+10000,now,"job");fail("revoked") }
        catch(e:ApiError) { assertEquals("agent_not_allowed",e.code) }
        api.stop();svc.onDestroy()
    }

    @Test fun replayHighWaterSurvivesStoreRecreationAndIsBoundToAgentAndGeneration() {
        val store=RelayStore(app);assertTrue(store.claim("a","g",3));assertFalse(RelayStore(app).claim("a","g",3));assertFalse(RelayStore(app).claim("a","g",2));assertTrue(RelayStore(app).claim("b","g",3));assertTrue(RelayStore(app).claim("a","g2",3));assertFalse(store.claim("a","g",0));assertTrue(store.claim("a","g",4,"requestid"));assertFalse(RelayStore(app).claim("a","g",5,"requestid"))
    }
    @Test fun connectorCredentialsCannotAuthenticateDirectAndRotationRemovesRelayAuthority() {
        app.platform.store // finish migration before adding a new relay agent
        val (a,key)=app.agents.add("RelayTest",Connection.RELAY);app.agents.update(a.id){it.copy(caps=setOf("interactions"))}
        val token=RelayStore(app).addAgent(a.id,"connector",null,setOf("GET /v1/health"),setOf("interaction.create"))
        assertNull(app.policy.authenticate(token));assertNull(app.policy.authenticate(key));assertEquals(1,RelayStore(app).agents().size)
        app.agents.rotate(a.id);assertTrue(RelayStore(app).agents().isEmpty())
    }
    @Test fun relayOperationCancellationClosesItsSessionAndExpiredApprovalFails() {
        val a=app.policy.addAgent("RelayCancel").first
        val session=app.platform.store.save(a.id,"session",JSONObject().put("status","awaiting_owner").put("request_expires_at",System.currentTimeMillis()+10000))
        val op=app.platform.store.save(a.id,"operation",JSONObject().put("relay",true).put("relay_job","job-a").put("command","session.request").put("status","completed").put("result",session))
        app.platform.cancelRelayOperation(a.id,op.getString("id"))
        assertEquals("cancelled",app.platform.store.require(session.getString("id"),a.id).getString("status"))
        val expired=app.platform.store.save(a.id,"operation",JSONObject().put("relay",true).put("status","awaiting_owner").put("expires_at",System.currentTimeMillis()-1))
        try { app.platform.approveOperation(expired.getString("id"));fail("must reject expired approval") }catch(e:ApiError){assertEquals("approval_expired",e.code)}
    }
    @Test fun realV1HandlerAndV2ScopePathAreUsedWithoutAnyLoopbackServer()=runBlocking {
        app.settings.listenerEnabled=true;val store=RelayStore(app);store.enabled=true
        val (a,_)=app.agents.add("RelayDispatch",Connection.RELAY);app.agents.update(a.id){it.copy(caps=setOf("status"))}
        store.addAgent(a.id,"connector",null,setOf("GET /v1/health"),setOf("interaction.create","diagnostics","feedback.create"))
        val c=store.agents().single();val svc=Robolectric.buildService(BridgeService::class.java).create().get();val api=ApiServer(svc,emptyList(),0);api.prepareRelay();val dispatcher=RelayDispatcher(app,api)
        val now=System.currentTimeMillis();val reply=dispatcher.dispatch(c,JSONObject().put("method","GET").put("path","/v1/health"),now+10000,now,"fixture-job")
        assertEquals(200,reply.getInt("status"));assertEquals(a.id,JSONObject(String(Sealed.un64(reply.getString("body_base64")))).getString("agent_id"))
        // The in-process relay path never reaches the device lock: a relay agent isn't bound to a "relay:…" label.
        assertTrue(app.agents.get(a.id)!!.boundIps.isEmpty())
        try { dispatcher.dispatch(c,JSONObject().put("method","POST").put("path","/v2/call").put("body",JSONObject().put("tool","interaction.create").put("arguments",JSONObject().put("kind","notice").put("title","fixture"))),now+10000,now,"job");fail("scope denied") }catch(e:ApiError){assertEquals("agent_not_allowed",e.code)}
        assertFalse("feedback.create" in RelayDispatcher.allowedTools(c));assertThrows(ApiError::class.java){RelayDispatcher.requireLive(now-6000)}
        api.stop();svc.onDestroy()
    }
    /** Single-use portal link: one claim swaps the token; a second claim, an expired link and signed-only behave. */
    @Test fun portalLinkClaimsOnceAndSignedOnlyExemptsPortals()=runBlocking {
        app.settings.listenerEnabled=true;val store=RelayStore(app);store.enabled=true;store.base="https://relay.example/d/"+store.device
        val p=cc.bridgit.app.portal.PortalPairing.create(app,store)
        assertEquals("1",cc.bridgit.app.portal.PortalData.parsePairLink(p.link)!!["claim"])
        val linkHash=store.agents().single { it.getString("id")==p.agent.id }.getString("token_hash")
        val svc=Robolectric.buildService(BridgeService::class.java).create().get();val api=ApiServer(svc,emptyList(),0);api.prepareRelay();val d=RelayDispatcher(app,api)
        val now=System.currentTimeMillis();val mine="a".repeat(64)
        suspend fun claim(h:String)=d.dispatch(store.agents().single { it.getString("id")==p.agent.id },JSONObject().put("method","POST").put("path","/v1/portal/claim").put("body",JSONObject().put("token_hash",h)),now+10000,now,"job")
        assertEquals(400,claim(linkHash).getInt("status"))
        assertEquals(200,claim(mine).getInt("status"))
        val c=store.agents().single { it.getString("id")==p.agent.id };assertEquals(mine,c.getString("token_hash"));assertFalse(c.has("claim_until"))
        assertEquals(409,claim("b".repeat(64)).getInt("status"))
        // Signed-only: the read-only portal still works; a connector agent that can control the phone doesn't.
        app.settings.securityRelaySignedOnly=true
        try {
            assertEquals(200,d.dispatch(c,JSONObject().put("method","GET").put("path","/v1/portal/theme"),now+10000,now,"job").getInt("status"))
            val (a,_)=app.agents.add("SignedOnly",Connection.RELAY);app.agents.update(a.id){it.copy(caps=setOf("status"))}
            store.addAgent(a.id,"connector",null,setOf("GET /v1/health"),emptySet())
            try { d.dispatch(store.agents().single { it.getString("id")==a.id },JSONObject().put("method","GET").put("path","/v1/health"),now+10000,now,"job");fail("signed only") }catch(e:ApiError){assertEquals("signed_requests_required",e.code)}
        } finally { app.settings.securityRelaySignedOnly=false }
        // An unclaimed link past its 24 h refuses everything but is swept away with its agent.
        val q=cc.bridgit.app.portal.PortalPairing.create(app,store)
        val all=JSONArray(app.getSharedPreferences("relay_v1",android.content.Context.MODE_PRIVATE).getString("agents","[]"))
        for(i in 0 until all.length()) if(all.getJSONObject(i).getString("id")==q.agent.id) all.getJSONObject(i).put("claim_until",now-1)
        app.getSharedPreferences("relay_v1",android.content.Context.MODE_PRIVATE).edit().putString("agents",all.toString()).commit()
        try { d.dispatch(store.agents().single { it.getString("id")==q.agent.id },JSONObject().put("method","GET").put("path","/v1/portal/theme"),now+10000,now,"job");fail("expired") }catch(e:ApiError){assertEquals("claim_required",e.code)}
        assertEquals(1,store.sweepExpiredClaims());assertNull(app.agents.get(q.agent.id))
        api.stop();svc.onDestroy()
    }
}
