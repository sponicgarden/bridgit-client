package cc.bridgit.app.platform

import android.content.Context
import cc.bridgit.app.ApiError
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PlatformStoreTest {
    private lateinit var store:PlatformStore
    private lateinit var name:String
    @Before fun setup() { name="store-${UUID.randomUUID()}.db"; store=PlatformStore(RuntimeEnvironment.getApplication(),name) }
    @After fun close() { store.close(); RuntimeEnvironment.getApplication().deleteDatabase(name) }
    private fun rejects(code:String,block:()->Unit) { try { block(); fail("Expected $code") } catch(e:ApiError) { assertEquals(code,e.code) } }
    @Test fun grantsAreScopedAndExpireWithoutChangingAnotherAgent() {
        store.grant("a","files",500); store.grant("b","files",900)
        assertTrue(store.allowed("a","files",499)); assertFalse(store.allowed("a","files",500)); assertFalse(store.allowed("a","screen.control",100))
        store.revoke("a"); assertTrue(store.allowed("b","files",500)); assertFalse(store.allowed("a","files",100))
    }
    @Test fun permanentGrantSurvivesRestartAndStillRevokes() {
        store.grant("a", "owner.configuration", Long.MAX_VALUE)
        store.close(); store = PlatformStore(RuntimeEnvironment.getApplication(), name)
        assertTrue(store.allowed("a", "owner.configuration", System.currentTimeMillis() + 30L * 86400_000))
        store.revoke("a", "owner.configuration")
        assertFalse(store.allowed("a", "owner.configuration"))
    }
    @Test fun resourcesCannotBeReadOrOverwrittenByAnotherActor() {
        val record=store.save("a","file",JSONObject().put("secret","owner-only")); val id=record.getString("id")
        assertNull(store.find(id,"b")); assertEquals(0,store.list("b","file").size)
        rejects("resource_not_found") { store.require(id,"b") }
        rejects("resource_conflict") { store.save("b","file",JSONObject().put("id",id)) }
        rejects("resource_conflict") { store.save("a","session",JSONObject().put("id",id)) }
        assertEquals("owner-only",store.require(id,"a").getString("secret"))
    }
    @Test fun idempotencyUsesCanonicalInputAndNeverRedispatches() {
        val first=store.reserve("a","request-1","test",JSONObject().put("a",1).put("nested",JSONObject().put("x",2).put("y",3)))
        assertTrue(first.second)
        val retry=store.reserve("a","request-1","test",JSONObject().put("nested",JSONObject().put("y",3).put("x",2)).put("a",1))
        assertFalse(retry.second); assertEquals(first.first.getString("id"),retry.first.getString("id"))
        rejects("idempotency_conflict") { store.reserve("a","request-1","test",JSONObject().put("a",2)) }
        rejects("idempotency_conflict") { store.reserve("a","request-1","different",JSONObject().put("a",1)) }
    }
    @Test fun identicalKeysFromDifferentAgentsDoNotCollide() { val a=store.reserve("a","request-1","test",JSONObject()); val b=store.reserve("b","request-1","test",JSONObject()); assertNotEquals(a.first.getString("id"),b.first.getString("id")) }
    @Test fun deletedHistoryDoesNotReplayAnExternalEffect() { val record=store.reserve("a","request-1","test",JSONObject()).first; store.delete(record.getString("id"),"a"); val retry=store.reserve("a","request-1","test",JSONObject()); assertFalse(retry.second); assertEquals("history_removed",retry.first.getString("status")) }
    @Test fun eventsAreCallerScopedAndPaginatedWithoutSkipping() {
        repeat(150) { store.emit("a","sample",it.toString()); store.emit("b","private","secret") }
        val first=store.events("a",0); assertEquals(100,first.getJSONArray("events").length())
        val second=store.events("a",first.getLong("cursor")); assertEquals(50,second.getJSONArray("events").length())
        assertFalse(first.toString().contains("secret")); assertFalse(second.toString().contains("secret"))
    }
    @Test fun eventGapsAreExplicitAfterRetentionTrim() { repeat(2010) { store.emit("a","tick") }; val page=store.events("a",1); assertTrue(page.getBoolean("gap")); assertEquals(100,page.getJSONArray("events").length()) }
    @Test fun stateAndDeviceIdentitySurviveReopening() { val id=store.deviceId; val record=store.save("a","operation",JSONObject().put("status","running")); store.close(); store=PlatformStore(RuntimeEnvironment.getApplication(),name); assertEquals(id,store.deviceId); assertEquals("running",store.require(record.getString("id"),"a").getString("status")) }
    @Test fun boundsRejectFractionsOverflowAndUnknownFields() {
        rejects("invalid_parameter") { JSONObject().put("seconds",1.2).whole("seconds",1,1,120) }
        rejects("invalid_parameter") { JSONObject().put("seconds",Long.MAX_VALUE).whole("seconds",1,1,120) }
        rejects("invalid_parameter") { JSONObject().put("authority",true).only("title") }
        rejects("invalid_parameter") { JSONObject().put("apps",JSONArray().put(123)).strings("apps") }
        rejects("idempotency_required") { store.reserve("a","","test",JSONObject()) }
    }
}
