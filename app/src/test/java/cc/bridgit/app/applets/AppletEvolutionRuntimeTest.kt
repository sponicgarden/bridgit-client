package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AppletEvolutionRuntimeTest {
    private val download = AppletFetch.download
    @org.junit.Before fun useRegistry() { AppletFetch.download = ::fixtureRegistry }
    @org.junit.After fun restoreRegistry() { AppletFetch.download = download }
    private val app get()=RuntimeEnvironment.getApplication() as BridgeApp
    private val source=JSONObject().put("kind","url").put("url","https://bridgit.cc/recipes/voice-notebook/applet.json")
    private fun pkg(edit: (JSONObject)->Unit = {})=AppletPackage.check(JSONObject(File("../site-content/public/recipes/voice-notebook/applet.json").readText()).also { it.getJSONObject("ui").put("practices",JSONObject("""{"contract":"bridgit-practices/1","catalog":"1.0.0","review":{"decision":"no-new-practice","reason":"Test the existing personal upgrade contract.","practice_ids":["UPGRADE-001","GOV-001"]}}""")) }.also(edit))
    private suspend fun install(from: JSONObject = source): JSONObject {
        app.settings.listenerEnabled=true
        for(m in app.registry.switchable()) app.settings.setOn(m.switchKey!!,true)
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        runCatching { androidx.work.WorkManager.getInstance(app) }.onFailure { androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build()) }
        return app.applets.approve(app.applets.propose(pkg(),null,from,null,notify=false).getString("id"),null)
    }
    @Test fun relinkingSameEditionRequiresFreshReviewAndPreservesTheInstance()=runBlocking {
        val pin = JSONObject().put("kind","template").put("template","voice-notebook").put("revision",1)
        val r = install(pin)
        val id = r.getString("id")
        try {
            fun linked(char: String) = JSONObject(pin.toString()).put("library_account",char.repeat(64))
            val first = app.applets.prepareTemplate(id,pkg(),linked("a"),r.getLong("revision"))
            val next = app.applets.prepareTemplate(id,pkg(),linked("b"),r.getLong("revision"))
            assertEquals("awaiting_owner",next.getString("status"))
            assertNotEquals(first.getString("id"),next.getString("id"))
            assertNull(app.applets.get(id)!!.optJSONObject("edition_source"))
            val changed = app.applets.approve(next.getString("id"),null)
            assertEquals(id,changed.getString("id"))
            assertEquals("b".repeat(64),changed.getJSONObject("edition_source").getString("library_account"))
        } finally { app.applets.remove(id) }
        Unit
    }

    @Test fun detachPreservesRunningCopyAndBlocksTemplateProposal()=runBlocking {
        val r=install();val id=r.getString("id")
        val detached=app.applets.setTemplatePolicy(id,"detached",r.getLong("revision"),"Repurpose")
        assertEquals("active",detached.getString("status"))
        assertEquals(r.getJSONObject("definition").toString(),detached.getJSONObject("definition").toString())
        try { app.applets.propose(pkg { it.put("version","9.0.0") },null,source,null,replaces=id,notify=false);fail("detached template proposed") }
        catch(e: AppletInstances.Refused) { assertEquals("conflict",e.code) }
        app.applets.remove(id); Unit
    }
    @Test fun staleApprovalCannotOverwritePersonalEdit()=runBlocking {
        val r=install();val id=r.getString("id")
        val update=pkg { it.put("version","9.0.0");it.getJSONObject("permissions").getJSONArray("required").put("weather") }
        val pend=app.applets.propose(update,null,source,null,replaces=id,notify=false)
        app.applets.setTemplatePolicy(id,"paused",r.getLong("revision"),"Wait")
        try { app.applets.approve(pend.getString("id"),null);fail("stale approval applied") }
        catch(e: AppletInstances.Refused) { assertTrue(e.code in setOf("conflict","not_found")) }
        assertEquals(r.getString("version"),app.applets.get(id)!!.getString("version"))
        app.applets.remove(id); Unit
    }
    @Test fun unchangedUpgradeOffersOwnerReviewWithoutAgentAndPreservesInstance()=runBlocking {
        val r=install();val id=r.getString("id")
        val task=app.applets.prepareTemplate(id,pkg { it.put("version","9.0.0").put("summary","Improved") },source,r.getLong("revision"))
        assertEquals("awaiting_owner",task.getString("status"))
        assertEquals(r.getString("version"),app.applets.get(id)!!.getString("version"))
        assertEquals(task.getString("id"),app.applets.templateUpdate(app.applets.get(id)!!)!!.getString("id"))
        val again=app.applets.prepareTemplate(id,pkg { it.put("version","9.0.0").put("summary","Improved") },source,r.getLong("revision"))
        assertEquals(task.getString("id"),again.getString("id"))
        assertTrue(AppletEvolution.same(task.getJSONObject("review"),again.getJSONObject("review")))
        val updated=app.applets.approve(task.getString("id"),null)
        assertEquals("9.0.0",updated.getString("version"))
        assertEquals(r.getString("id"),updated.getString("id"))
        assertEquals(r.getString("agent_id"),updated.getString("agent_id"))
        assertEquals(r.getJSONObject("config").toString(),updated.getJSONObject("config").toString())
        assertTrue(AppletEvolution.same(updated.getJSONObject("definition"),AppletEvolution.state(updated).getJSONObject("baseline")))
        assertNull(app.applets.templateUpdate(updated))
        app.applets.remove(id); Unit
    }
    @Test fun customizedUpgradeStillPreparesDurableTaskWithoutReplacingPersonalCopy()=runBlocking {
        val r=install();val id=r.getString("id")
        val change=JSONObject().put("expected_revision",r.getLong("revision")).put("intent","My title")
            .put("acceptance",JSONArray().put("Keep personal title")).put("evidence","Checked")
        val proposal=app.applets.update(id,pkg { it.put("name","My notebook") },null,JSONObject().put("kind","agent_inline"),null,change)
        val personal=app.applets.approve(proposal.getString("id"),null)
        val task=app.applets.prepareTemplate(id,pkg { it.put("version","9.0.0").put("name","New title") },source,personal.getLong("revision"))
        assertEquals("applet.template_upgrade",task.getString("type"))
        assertEquals("needs_agent",task.getString("status"))
        val current=app.applets.get(id)!!
        assertEquals("My notebook",current.getJSONObject("definition").getString("name"))
        assertEquals(task.getString("id"),app.applets.templateUpdate(current)!!.getString("id"))
        val paused=app.applets.setTemplatePolicy(id,"paused",current.getLong("revision"),"Wait")
        assertNull(app.applets.templateUpdate(paused))
        app.applets.remove(id);Unit
    }

    @Test fun bundledInventory201Offers300EvenAfterOldOfferMarkerAndKeepsRecords()=runBlocking {
        install().let { app.applets.remove(it.getString("id")) } // initialize the test phone
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA)
        cc.bridgit.app.platform.Capabilities.all.forEach { cc.bridgit.app.platform.Capabilities.setEnabled(app,it.id,true) }
        val old=AppletPackage.parse(File("src/test/resources/applets/insurance-inventory-2.0.1.json").readText())
        val bundled=JSONObject().put("kind","bundled").put("bundled","insurance-inventory")
        val r=app.applets.approve(app.applets.propose(old,null,bundled,null,notify=false).getString("id"),null)
        val id=r.getString("id");val agent=r.getString("agent_id")
        val table=AppletTables.parse(old.definition.getJSONObject("processing")).first { it.id=="items" }
        val store=AppletTableStore.of(app)
        val row=store.add(agent,table,JSONObject().put("label","Kettle").put("quantity",1),JSONObject().put("kind","owner"))
        app.getSharedPreferences("applets_bundled",android.content.Context.MODE_PRIVATE).edit().putString(old.id,"3.0.0").commit()
        app.applets.checkTemplate(id)
        val pending=app.applets.templateUpdate(app.applets.get(id)!!)!!
        assertEquals("awaiting_owner",pending.getString("status"))
        assertEquals("3.0.0",pending.getJSONObject("definition").getString("version"))
        assertEquals("2.0.1",app.applets.get(id)!!.getString("version"))
        val updated=app.applets.approve(pending.getString("id"),null)
        assertEquals("3.0.0",updated.getString("version"))
        assertEquals(agent,updated.getString("agent_id"))
        assertNotNull(store.get(agent,"items",row.getString("id")))
        assertNull(app.applets.templateUpdate(updated))
        app.applets.remove(id);Unit
    }

    @Test fun historicalInventoryEditionsReopenAndUpgradeToCsvOnlyWithoutChangingRows()=runBlocking {
        install().let { app.applets.remove(it.getString("id")) }
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA)
        cc.bridgit.app.platform.Capabilities.all.forEach { cc.bridgit.app.platform.Capabilities.setEnabled(app,it.id,true) }
        val current=AppletPackage.parse(File("../applets/insurance-inventory/applet.json").readText())
        val bundled=JSONObject().put("kind","bundled").put("bundled","insurance-inventory")
        for (version in listOf("2.0.1","3.0.0")) {
            val old=AppletPackage.parse(File("src/test/resources/applets/insurance-inventory-$version.json").readText())
            val original=old.definition.toString()
            val installed=app.applets.approve(app.applets.propose(old,null,bundled,null,notify=false).getString("id"),null)
            val id=installed.getString("id");val agent=installed.getString("agent_id")
            val store=AppletTableStore.of(app)
            val table=AppletTables.parse(old.definition.getJSONObject("processing")).first { it.id=="items" }
            val row=store.add(agent,table,JSONObject().put("label","Kettle").put("quantity",1),JSONObject().put("kind","owner"))
            app.applets.setPaused(id,true);app.applets.setPaused(id,false)
            assertTrue(AppletEvolution.same(old.definition,app.applets.get(id)!!.getJSONObject("definition")))
            val pending=app.applets.prepareTemplate(id,current,bundled,app.applets.get(id)!!.getLong("revision"))
            val updated=app.applets.approve(pending.getString("id"),null)
            assertEquals("3.0.3",updated.getString("version"))
            assertEquals(agent,updated.getString("agent_id"))
            assertNotNull(store.get(agent,"items",row.getString("id")))
            assertEquals(original,old.definition.toString())
            assertTrue(!updated.getJSONObject("definition").toString().contains("Share PDF"))
            app.applets.remove(id)
        }
    }

    @Test fun bundledSourceCanBeCheckedAndStaleReviewIsNotSuggested()=runBlocking {
        val r=install();val id=r.getString("id")
        val bundled=JSONObject().put("kind","bundled").put("bundled","insurance-inventory")
        val latest=AppletFetch.latest(app,bundled,cc.bridgit.app.outbound.Outbound.Actor.OWNER)
        assertEquals("insurance-inventory",latest.first.id)
        assertEquals("template",latest.second.getString("kind"))
        assertEquals(3,latest.second.getInt("revision"))
        val api=AppletFetch.fromBody(app,JSONObject().put("bundled","insurance-inventory"),cc.bridgit.app.outbound.Outbound.Actor.OWNER)
        assertEquals(latest.first.sha256,api.first.sha256)
        assertTrue(AppletEvolution.same(latest.second,api.second))
        for (body in listOf(JSONObject().put("bundled","unknown"),JSONObject().put("bundled",42),
            JSONObject().put("bundled","insurance-inventory").put("url","https://bridgit.cc/a.json"))) {
            try { AppletFetch.fromBody(app,body,cc.bridgit.app.outbound.Outbound.Actor.OWNER);fail("invalid source accepted") }
            catch (_: AppletPackage.Bad) { }
        }
        app.applets.prepareTemplate(id,pkg { it.put("version","9.0.0") },source,r.getLong("revision"))
        assertNotNull(app.applets.templateUpdate(app.applets.get(id)!!))
        val agent=app.agents.add("Next developer",cc.bridgit.app.core.Connection.DIRECT).first
        val assigned=app.applets.assignDeveloper(id,agent.id,r.getLong("revision"))
        assertNull(app.applets.templateUpdate(assigned))
        app.applets.remove(id);app.agents.remove(agent.id);Unit
    }

    @Test fun longLivedEditionsAcceptLargeExactPinsAndRejectOverflow() {
        val url = "bridgit://install?template=tpl_history&revision="
        for (rev in listOf(1461, Int.MAX_VALUE)) {
            assertEquals(rev, AppletPackage.link(url + rev)?.revision)
            assertEquals(rev, AppletFetch.registryRelease("""{"key":"tpl_history","revision":$rev}""","tpl_history",rev).getInt("revision"))
        }
        assertNull(AppletPackage.link(url + "2147483648"))
        try {
            AppletFetch.registryRelease("""{"key":"tpl_history","revision":4294967297}""","tpl_history",1)
            fail("Oversized revision aliased revision 1")
        } catch (_: AppletFetch.Failed) {}
    }

    @Test fun fingerprintedEditionRetainsAuthenticatedOwnerOutsideThePackage() {
        val body = JSONObject().put("key","voice").put("revision",3).put("definition",pkg().definition).toString().toByteArray()
        val pin = AppletPackage.registryLink("voice",3,AppletPackage.sha256(body))!!
        val actor = cc.bridgit.app.outbound.Outbound.Actor.OWNER
        for (account in listOf(null,"b".repeat(64))) {
            AppletFetch.download = { _,_,_ -> AppletFetch.Download(body,account) }
            val (release,source) = AppletFetch.packageAndSource(app,pin,actor,"link")
            assertEquals(pkg().sha256,release.sha256)
            assertEquals(account,source.opt("library_account"))
        }
        AppletFetch.download = { _,_,_ -> AppletFetch.Download(body + 32.toByte(),"b".repeat(64)) }
        assertThrows(AppletFetch.Failed::class.java) { AppletFetch.packageAndSource(app,pin,actor,"link") }
    }

    @Test fun pinnedReadsRejectWrongRevisionOrPublisherKey() {
        val body = JSONObject().put("template","tpl_chef").put("revision",2)
        for ((key, revision) in listOf("tpl_chef" to 3, "other" to 2)) {
            AppletFetch.download = { _, _, _ ->
                AppletFetch.Download(JSONObject().put("key",key).put("revision",revision).put("definition",pkg().definition).toString().toByteArray())
            }
            try {
                AppletFetch.fromBody(app,body,cc.bridgit.app.outbound.Outbound.Actor.OWNER)
                fail("Mismatched release accepted")
            } catch (e: AppletPackage.Bad) { assertTrue(e.message!!.contains("requested applet revision")) }
        }
    }

    @Test fun revisionGuardRejectsCompetingPolicyWrite()=runBlocking {
        val r=install();val id=r.getString("id")
        app.applets.setTemplatePolicy(id,"paused",r.getLong("revision"),"Wait")
        try { app.applets.setTemplatePolicy(id,"tracking",r.getLong("revision"),"Resume");fail("stale write") }
        catch(e: AppletInstances.Refused) { assertEquals("conflict",e.code) }
        app.applets.remove(id); Unit
    }
    @Test fun ownerAssignmentPreservesDataAndInvalidatesPendingWork()=runBlocking {
        val r=install();val id=r.getString("id")
        val agent=app.agents.add("Evolution developer",cc.bridgit.app.core.Connection.DIRECT).first
        val assigned=app.applets.assignDeveloper(id,agent.id,r.getLong("revision"))
        assertEquals(agent.id,assigned.getString("requested_by"))
        assertEquals(r.getJSONObject("config").toString(),assigned.getJSONObject("config").toString())
        assertEquals("active",assigned.getString("status"))
        app.applets.remove(id);app.agents.remove(agent.id);Unit
    }
    @Test fun samePackageFromDifferentOriginCannotReplaceBaseline()=runBlocking {
        val r=install();val id=r.getString("id")
        try {
            app.applets.update(id,pkg { it.put("version","9.0.0") },null,JSONObject().put("kind","url").put("url","https://bridgit.cc/other.json"),null)
            fail("foreign origin accepted")
        } catch(e: AppletInstances.Refused) { assertEquals("conflict",e.code) }
        app.applets.remove(id);Unit
    }
    @Test fun personalChangeRequiresReviewAndRetainsIntentAfterApproval()=runBlocking {
        val r=install();val id=r.getString("id")
        val change=JSONObject().put("expected_revision",r.getLong("revision")).put("intent","Keep my own notebook title")
            .put("acceptance",JSONArray().put("Title reads My notebook")).put("evidence","Validated package and rendered title")
        val out=app.applets.update(id,pkg { it.put("version","9.0.0").put("name","My notebook") },null,JSONObject().put("kind","agent_inline"),null,change)
        assertEquals("awaiting_owner",out.getString("status"))
        assertEquals(change.toString(),app.applets.pendingGet(out.getString("id"))!!.getJSONObject("change").toString())
        val applied=app.applets.approve(out.getString("id"),null)
        assertEquals("My notebook",applied.getJSONObject("definition").getString("name"))
        val e=AppletEvolution.state(applied)
        assertEquals("Keep my own notebook title",e.getJSONArray("journal").getJSONObject(0).getString("intent"))
        assertNotEquals("My notebook",e.getJSONObject("baseline").getString("name"))
        app.applets.remove(id);Unit
    }

    @Test fun previousDeveloperCannotMutateAfterOwnerReassignment()=runBlocking {
        val r=install();val id=r.getString("id")
        val old=app.agents.add("Previous developer",cc.bridgit.app.core.Connection.DIRECT).first
        val next=app.agents.add("New developer",cc.bridgit.app.core.Connection.DIRECT).first
        val first=app.applets.assignDeveloper(id,old.id,r.getLong("revision"))
        val current=app.applets.assignDeveloper(id,next.id,first.getLong("revision"))
        try {
            app.applets.setTemplatePolicy(id,"detached",current.getLong("revision"),"Old agent request",old.id)
            fail("Previous developer changed policy")
        } catch(e: AppletInstances.Refused) { assertEquals("not_found",e.code) }
        try {
            app.applets.prepareTemplate(id,pkg { it.put("version","9.0.0") },source,current.getLong("revision"),old.id)
            fail("Previous developer prepared upgrade")
        } catch(e: AppletInstances.Refused) { assertEquals("not_found",e.code) }
        assertEquals("tracking",AppletEvolution.state(app.applets.get(id)!!).getString("state"))
        app.applets.remove(id);app.agents.remove(old.id);app.agents.remove(next.id);Unit
    }

}
