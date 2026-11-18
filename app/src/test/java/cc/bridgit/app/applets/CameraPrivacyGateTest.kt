package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.UUID

/** A0451 phases 1.2-1.3: the camera privacy gate (hold, keep or discard) and enforced deletion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraPrivacyGateTest {
    private fun o(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun a(vararg v: Any?) = JSONArray().apply { v.forEach { put(it) } }
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private fun agent() = "test-" + UUID.randomUUID().toString().take(8)
    private val day = 86_400_000L

    private val processing = o("tables" to a(
        o("id" to "notices", "keep_days" to 30, "columns" to o("photo" to o("type" to "photo"), "note" to o("type" to "text"),
            "status" to o("type" to "choice", "options" to a("new", "done")))),
        o("id" to "log", "keep_days" to 10, "columns" to o("photo" to o("type" to "photo"), "note" to o("type" to "text")))))
    private val tables = AppletTables.parse(processing)
    private val notices = tables.first { it.id == "notices" }
    private val log = tables.first { it.id == "log" }
    private val definition = o("id" to "nanny", "processing" to processing, "privacy" to o("camera" to o("purpose" to "mess notices",
        "keep_when" to a(o("table" to "notices")), "retention_days" to 7, "never_kept" to "Frames with no mess notice are dropped.")))
    private fun policy(id: String = "ap-" + UUID.randomUUID().toString().take(6)) =
        CameraPolicy.build(id, CameraPrivacy.parse(definition)!!, tables, null)
    private fun flow(run: String) = o("type" to "flow", "run_id" to run)
    private val jpeg = ByteArray(64) { (it * 7).toByte() }

    @Test fun aRunWithNoKeepWhenRowStoresNothingAndCountsOneDiscard() {
        val s = AppletTableStore.of(app); val ag = agent(); val p = policy()
        val ref = s.holdPhoto(p, ag, "run1", "shot", jpeg)
        assertTrue(AppletTables.PHOTO_REF.matches(ref))
        // Held only: nothing on disk or in the store, but the run's own later steps can read it.
        assertNull(s.photoFile(ag, ref)); assertTrue(s.photos(ag).isEmpty()); assertFalse(s.hasAny(ag))
        assertEquals(jpeg.toList(), s.photoBytes(ag, ref)!!.toList())
        // A row that doesn't name the photo isn't a reason to keep it.
        s.add(ag, notices, o("note" to "nothing here"), flow("run1"))
        assertEquals(1, s.endRun(ag, "run1"))
        assertNull(s.photoBytes(ag, ref)); assertTrue(s.photos(ag).isEmpty())
        val ev = s.privacy.events.forInstance(p.instanceId)
        assertEquals(1, ev.size)
        assertEquals("discard", ev[0].action); assertEquals(1, ev[0].count); assertEquals("no_keep_when_row", ev[0].reason); assertNull(ev[0].ref)
        // Ending it again changes nothing.
        assertEquals(0, s.endRun(ag, "run1")); assertEquals(1, s.privacy.events.forInstance(p.instanceId).size)
    }

    @Test fun aRunWithAReferencingKeepWhenRowKeepsThePhoto() {
        val s = AppletTableStore.of(app); val ag = agent(); val p = policy()
        val ref = s.holdPhoto(p, ag, "run2", "shot", jpeg)
        val row = s.add(ag, notices, o("photo" to ref, "note" to "spill"), flow("run2"))
        assertEquals(ref, row.getString("photo"))
        assertEquals(jpeg.toList(), s.photoFile(ag, ref)!!.readBytes().toList())
        assertEquals(0, s.endRun(ag, "run2"))
        assertNotNull(s.photoFile(ag, ref))
        val ev = s.privacy.events.forInstance(p.instanceId)
        assertEquals(listOf("keep"), ev.map { it.action }); assertEquals(ref, ev[0].ref); assertEquals("notices", ev[0].table)
    }

    @Test fun onlyAKeepWhenRowOfTheSameRunCanKeepAHeldPhoto() {
        val s = AppletTableStore.of(app); val ag = agent(); val p = policy()
        val ref = s.holdPhoto(p, ag, "run3", "shot", jpeg)
        try { s.add(ag, log, o("photo" to ref), flow("run3")); fail() } catch (e: AppletTables.Refused) { assertEquals("invalid_row", e.code); assertTrue(e.message!!.contains("keep_when")) }
        try { s.add(ag, notices, o("photo" to ref), flow("other")); fail() } catch (e: AppletTables.Refused) { assertEquals("invalid_row", e.code) }
        try { s.add(ag, notices, o("photo" to ref), o("type" to "agent")); fail() } catch (e: AppletTables.Refused) { assertEquals("invalid_row", e.code) }
        // A refused row (bad value) after the photo checks passed leaves no photo behind either.
        try { s.add(ag, notices, o("photo" to ref, "status" to "lost"), flow("run3")); fail() } catch (e: AppletTables.Refused) { assertEquals("invalid_row", e.code) }
        assertTrue(s.photos(ag).isEmpty()); assertEquals(0, s.count(ag, "log")); assertEquals(0, s.count(ag, "notices"))
        assertEquals(1, s.endRun(ag, "run3"))
        assertFalse(s.hasAny(ag))
    }

    @Test fun cameraButtonGoesThroughTheGate() {
        val s = AppletTableStore.of(app); val ag = agent(); val p = policy()
        val row = CameraShots.save(s, ag, notices, "photo", o("status" to "new"), jpeg, CameraShots.by(1L), policy = p)
        assertNotNull(s.photoFile(ag, row.getString("photo")))
        // A table outside keep_when is refused before anything is taken in.
        try { CameraShots.save(s, ag, log, "photo", o(), jpeg, CameraShots.by(1L), policy = p); fail() } catch (e: AppletTables.Refused) { assertEquals("not_kept", e.code) }
        // A refused row leaves no photo (no 7-day orphan) and counts one discard.
        try { CameraShots.save(s, ag, notices, "photo", o("status" to "lost"), jpeg, CameraShots.by(1L), policy = p); fail() } catch (e: AppletTables.Refused) { assertEquals("invalid_row", e.code) }
        assertEquals(1, s.photos(ag).size)
        assertEquals(listOf("keep", "discard"), s.privacy.events.forInstance(p.instanceId).map { it.action })
    }

    @Test fun anAppletWithoutPrivacyCameraIsUnchanged() {
        val s = AppletTableStore.of(app); val ag = agent()
        val plain = JSONObject(definition.toString()).apply { remove("privacy") }
        val record = o("id" to "ap-plain", "agent_id" to ag, "definition" to plain)
        assertNull(CameraPolicy.of(record)); assertFalse(CameraPolicy.stamp(record).has(CameraPolicy.FIELD))
        // keep: true stores at once, and a failed camera-button row leaves its photo for the 7-day sweep, as before.
        val ref = s.keepPhoto(ag, "shot", jpeg)
        assertNotNull(s.photoFile(ag, ref))
        try { CameraShots.save(s, ag, notices, "photo", o("status" to "lost"), jpeg, CameraShots.by(1L)); fail() } catch (_: AppletTables.Refused) {}
        assertEquals(2, s.photos(ag).size)
        s.add(ag, notices, o("note" to "old"), o("owner" to true))
        s.clean(ag, tables, now = System.currentTimeMillis() + 8 * day)
        assertEquals("rows keep the table's own 30 days", 1, s.count(ag, "notices"))
        assertEquals(0, s.photos(ag).size)
        s.clean(ag, tables, now = System.currentTimeMillis() + 31 * day)
        assertEquals(0, s.count(ag, "notices"))
        assertTrue(s.privacy.events.all().none { it.agentId == ag })
    }

    @Test fun deletionAtTheWindowEdgeTakesRowAndPhotoTogether() {
        val s = AppletTableStore.of(app); val ag = agent(); val p = policy()
        assertEquals(7, p.days("notices"))
        val ref = s.holdPhoto(p, ag, "run4", "shot", jpeg)
        val at = s.add(ag, notices, o("photo" to ref), flow("run4")).getLong("at")
        s.add(ag, log, o("note" to "not camera material"), flow("run4"))
        s.endRun(ag, "run4")
        s.clean(ag, tables, now = at + 7 * day, policy = p)
        assertEquals(1, s.count(ag, "notices")); assertNotNull(s.photoFile(ag, ref))
        s.clean(ag, tables, now = at + 7 * day + 1_000, policy = p)
        assertEquals(0, s.count(ag, "notices")); assertNull(s.photoFile(ag, ref)); assertTrue(s.photos(ag).isEmpty())
        // A table outside keep_when keeps its own keep_days.
        assertEquals(1, s.count(ag, "log"))
        val del = s.privacy.events.forInstance(p.instanceId).single { it.action == "delete" }
        assertEquals("notices", del.table); assertEquals(1, del.count); assertEquals(1, del.photos); assertEquals("retention_window", del.reason)
    }

    @Test fun aCameraAppletsUnusedPhotoGoesAtOnce() {
        val s = AppletTableStore.of(app); val ag = agent(); val p = policy()
        val ref = s.holdPhoto(p, ag, "run5", "shot", jpeg)
        val row = s.add(ag, notices, o("photo" to ref), flow("run5"))
        s.update(ag, notices, row.getString("id"), o("photo" to JSONObject.NULL), o("type" to "agent"))
        assertNotNull("an agent's edit frees it, the sweep deletes it", s.photoFile(ag, ref))
        s.clean(ag, tables, policy = p)
        assertNull(s.photoFile(ag, ref))
    }

    @Test fun theWindowIsStoredWithTheAppletAndSurvivesANewStore() {
        val ag = agent()
        val record = o("id" to "ap-keep", "agent_id" to ag, "definition" to definition)
        CameraPolicy.stamp(record)
        record.getJSONObject(CameraPolicy.FIELD).put("backend_override", 2)
        // Saved, restarted: a new record object and a new store.
        val restored = JSONObject(CameraPolicy.stamp(JSONObject(record.toString())).toString())
        val p = CameraPolicy.of(restored)!!
        assertEquals(2, p.backendOverride); assertEquals(2, p.days("notices")); assertEquals("ap-keep", p.instanceId)
        // The backend can't lengthen it.
        restored.getJSONObject(CameraPolicy.FIELD).put("backend_override", 60)
        assertEquals(7, CameraPolicy.of(CameraPolicy.stamp(restored))!!.days("notices"))
        // Two stores over the same phone data (AppletTableStore.of may hold an earlier test's app).
        val first = AppletTableStore(app)
        val ref = first.holdPhoto(p, ag, "run6", "shot", jpeg)
        val at = first.add(ag, notices, o("photo" to ref), flow("run6")).getLong("at")
        assertEquals(1, first.count(ag, "notices"))
        val fresh = AppletTableStore(app)
        // What a restart reads back: the record as saved (stamped), parsed from its stored text.
        val stored = CameraPolicy.of(JSONObject(CameraPolicy.stamp(JSONObject(record.toString())).toString()))!!
        assertEquals(2, stored.days("notices"))
        fresh.clean(ag, tables, now = at + 2 * day - 1_000, policy = stored)
        assertEquals(1, fresh.count(ag, "notices"))
        fresh.clean(ag, tables, now = at + 2 * day + 1_000, policy = stored)
        assertEquals(0, fresh.count(ag, "notices")); assertNull(fresh.photoFile(ag, ref))
    }

    @Test fun eventsCarryNoImageData() {
        val s = AppletTableStore.of(app); val ag = agent(); val p = policy()
        val r1 = s.holdPhoto(p, ag, "run7", "shot", jpeg)
        s.add(ag, notices, o("photo" to r1), flow("run7"))
        s.holdPhoto(p, ag, "run7", "other", jpeg)
        s.endRun(ag, "run7")
        s.clean(ag, tables, now = System.currentTimeMillis() + 8 * day, policy = p)
        val ev = s.privacy.events.forInstance(p.instanceId)
        assertEquals(setOf("keep", "discard", "delete"), ev.map { it.action }.toSet())
        val b64 = Base64.getEncoder().encodeToString(jpeg); val b64url = Base64.getUrlEncoder().encodeToString(jpeg)
        for (e in ev) {
            val j = e.json()
            assertEquals(setOf("ts", "applet_instance_id", "agent_id", "table", "ref", "action", "reason", "count", "photos"), j.keys().asSequence().toSet())
            val text = j.toString()
            assertFalse(text.contains(b64.take(16))); assertFalse(text.contains(b64url.take(16)))
            assertTrue("short entries only", text.length < 300)
            j.keys().forEach { k -> val v = j.opt(k); assertTrue(v == JSONObject.NULL || v is String || v is Number) }
        }
    }
}
