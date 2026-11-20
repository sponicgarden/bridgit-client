package cc.bridgit.app.platform

import android.Manifest
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.capability.SettingsController
import cc.bridgit.app.core.SettingsCatalog
import cc.bridgit.app.listen.WakeRules
import cc.bridgit.app.listen.WakeWord
import kotlinx.coroutines.*
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
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class WakeFlowTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private fun o(vararg p: Pair<String, Any>) = JSONObject().apply { p.forEach { (k,v) -> put(k,v) } }
    private fun a(vararg v: Any) = JSONArray().apply { v.forEach { put(it) } }
    private var service: org.robolectric.android.controller.ServiceController<BridgeService>? = null
    @Before fun setup() {
        app.settings.listenerEnabled = true
        app.settings.setOn("wakewords", true)
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        service = Robolectric.buildService(BridgeService::class.java).create()
        org.robolectric.util.ReflectionHelpers.setStaticField(BridgeService::class.java,"running",service!!.get())
        app.updateListener { it.copy(running = true, micReady = true) }
    }
    @After fun cleanup() { service?.destroy() }
    private fun active(name: String, phrase: String? = null, recipient: String? = null): Pair<String,String> {
        val agent = recipient ?: app.policy.addAgent(name).first.id
        app.agents.update(agent) { it.copy(caps = setOf("mic", "wakewords")) }
        val def = o("name" to name, "trigger" to o("type" to "wake_word").apply { if(phrase != null) put("phrase",phrase) }, "cooldown_s" to 0,
            "steps" to a(o("type" to "event", "text" to o("\$trigger" to "transcript"))))
        app.platform.workflows.validate(def).strings("required_scopes").forEach {
            Capabilities.setEnabled(app, it, true); app.platform.grant(agent,it,Long.MAX_VALUE)
        }
        val id = app.platform.workflows.propose(agent,def).getString("id")
        assertEquals("microphone readiness", emptyList<String>(), Capabilities.blockers(app, Capabilities.all.first { it.id == "microphone.clip" }))
        app.platform.workflows.activate(id)
        app.wake.saveWords(app.wake.words() + WakeWord(phrase ?: "hey $name",agent))
        return agent to id
    }
    private fun settle(id: String) = runBlocking { delay(60); withTimeout(10_000) { while(app.platform.workflows.busy(id)) delay(20) } }
    private fun runs(agent: String,id: String) = app.platform.workflows.runs(agent,id,50).getJSONArray("runs")
    @Test fun spokenModelNameComesFromActualModelIdentity() {
        assertEquals("GLM 5.3 Flash", FlowSteps.modelName("openrouter:z-ai/glm-5.3-flash"))
        assertEquals("other model", FlowSteps.modelName("openrouter:vendor/other-model"))
    }
    @Test fun nativeWakeStartsOnlyItsRecipientOnceAndCannotBeFabricatedThroughRun() {
        val (agent,id) = active("alpha"); val (other,otherId) = active("beta")
        val e = app.interactions.event(agent,"listen.heard",o("wake_word" to "hey alpha","transcript" to "what is two plus two"))
        app.platform.workflows.onWakeWord(e); settle(id)
        val r = runs(agent,id)
        assertEquals(r.toString(),1,r.length())
        assertEquals("what is two plus two",r.getJSONObject(0).getJSONObject("trigger").getString("transcript"))
        assertEquals(0,runs(other,otherId).length())
        app.platform.workflows.onWakeWord(e); settle(id); assertEquals(1,runs(agent,id).length())
        try { runBlocking { app.platform.workflows.runNow(agent,id,null) }; fail("A synthetic start was accepted") }
        catch(e: ApiError) { assertEquals("event_required",e.code) }
    }
    @Test fun pausedAndRemovedAssignmentsCannotStartEffects() {
        val (agent,id) = active("gamma")
        app.platform.workflows.pause(agent,id)
        app.platform.workflows.onWakeWord(app.interactions.event(agent,"listen.heard",o("wake_word" to "hey gamma","transcript" to "hello")))
        settle(id); assertEquals(0,runs(agent,id).length())
        assertEquals("microphone readiness", emptyList<String>(), Capabilities.blockers(app, Capabilities.all.first { it.id == "microphone.clip" }))
        app.platform.workflows.activate(id)
        app.wake.saveWords(emptyList())
        app.platform.workflows.onWakeWord(app.interactions.event(agent,"listen.heard",o("wake_word" to "hey gamma","transcript" to "hello")))
        settle(id); assertEquals(0,runs(agent,id).length())
    }
    @Test fun phrasesRouteIndependentlyBeyondOldListingAndCountLimits() {
        val (agent,first) = active("many", "model")
        repeat(110) { n ->
            val phrase = "word" + ('a' + n / 26) + ('a' + n % 26)
            active("flow$n", phrase, agent)
        }
        assertEquals(111,app.platform.store.workflows(agent).size)
        val event=app.interactions.event(agent,"listen.heard",o("wake_word" to "model","transcript" to "hello"))
        app.platform.workflows.onWakeWord(event); settle(first)
        assertEquals(1,runs(agent,first).length())
        assertEquals(1,app.platform.store.list(agent,"execution",1000).size)
        var cursor=""; var count=0
        do {
            val page=runBlocking { app.platform.read(agent,"workflow.list",o("after" to cursor,"limit" to 25)) }
            count+=page.getJSONArray("workflows").length()
            cursor=page.optString("next_cursor").takeUnless { page.isNull("next_cursor") } ?: ""
        } while(cursor.isNotEmpty())
        assertEquals(111,count)
    }
    @Test fun concurrentNativeEventsReserveOnlyOneRunAndWakeRevocationStopsLaterEffects() {
        val (agent,id)=active("race", "model")
        val record=app.platform.store.require(id,agent,"workflow")
        val definition=record.getJSONObject("definition").put("steps",a(o("type" to "wait","seconds" to 1),o("type" to "event","text" to "must not run")))
        val digest=app.platform.workflows.validate(definition).getString("digest")
        app.platform.store.save(agent,"workflow",record.put("definition",definition).put("digest",digest).put("approved_digest",digest))
        val one=app.interactions.event(agent,"listen.heard",o("wake_word" to "model","transcript" to "one"))
        val two=app.interactions.event(agent,"listen.heard",o("wake_word" to "model","transcript" to "two"))
        runBlocking { listOf(one,two).map { e -> async(Dispatchers.Default) { app.platform.workflows.onWakeWord(e) } }.awaitAll(); delay(200) }
        app.wake.saveWords(emptyList()); settle(id)
        val r=runs(agent,id); assertEquals(r.toString(),1,r.length())
        assertEquals("unknown_outcome",r.getJSONObject(0).getString("status"))
        val blocked=app.platform.store.require(id,agent,"workflow")
        assertEquals("blocked",blocked.getString("status"))
        assertEquals("agent_not_allowed",blocked.getString("blocked_reason"))
        assertEquals(1,r.getJSONObject(0).getJSONArray("steps").length())
    }
    @Test fun speakerPlaybackAndItsDrainCannotReenterTheWakeDetector() {
        val gate=WakeRules.PlaybackGate(300)
        assertFalse(gate.blocked(0)); gate.update(true,0); assertTrue(gate.blocked(10_000))
        gate.update(false,10_000); assertTrue(gate.blocked(10_299)); assertFalse(gate.blocked(10_300))
        gate.update(false,10_500); assertFalse(gate.blocked(10_500))
    }
    @Test fun localSpeechBindingAndEndpointTimingValidateWithoutChangingExistingDefaults() {
        FlowSteps.check(a(o("type" to "speak","engine" to "device","text" to "Hello")))
        for(engine in listOf("cloud", "")) try { FlowSteps.check(a(o("type" to "speak","engine" to engine,"text" to "Hello"))); fail() } catch(_: ApiError) {}
        try { FlowSteps.check(a(o("type" to "speak","engine" to "device","allow_cloud" to true,"text" to "Hello"))); fail() } catch(_: ApiError) {}
        val c=SettingsController(app); val def=c.def("listening.end_silence_ms")!!
        assertEquals(1500,c.read(def.key,null)); c.apply(def.key,650); assertEquals(650,c.read(def.key,null))
        for(bad in listOf<Any>(299,3001,"650",true)) assertTrue(SettingsCatalog.parse(def,bad) is SettingsCatalog.Parsed.Bad)
        val fast=WakeRules.PauseDetector(100,pauseMs=600); fast.feed(0.0); fast.feed(1000.0)
        repeat(5) { assertNull(fast.feed(0.0)) }; assertEquals(WakeRules.PauseDetector.End.PAUSE,fast.feed(0.0))
        val normal=WakeRules.PauseDetector(100); normal.feed(0.0); normal.feed(1000.0)
        repeat(14) { assertNull(normal.feed(0.0)) }; assertEquals(WakeRules.PauseDetector.End.PAUSE,normal.feed(0.0))
    }
}
