package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** The camera button's save path (docs/applet-wave2.md B) and the owner's writes, without the camera or resizing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraShotsTest {
    private fun o(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun a(vararg v: Any?) = JSONArray().apply { v.forEach { put(it) } }
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private fun agent() = "test-" + UUID.randomUUID().toString().take(8)
    private val photos = AppletTables.parse(o("tables" to a(o("id" to "photos", "columns" to o("photo" to o("type" to "photo"),
        "status" to o("type" to "choice", "options" to a("new", "reading", "done")), "taken_at" to o("type" to "time")))))).single()

    @Test fun keepsThePhotoAndAddsItsRow() {
        val s = AppletTableStore.of(app); val ag = agent()
        val row = CameraShots.save(s, ag, photos, "photo", o("status" to "new"), byteArrayOf(1, 2, 3), CameraShots.by(7L), now = 1_762_300_000_000L)
        val ref = row.getString("photo")
        assertTrue(ref.startsWith("kept:")); assertNotNull(s.photoFile(ag, ref))
        assertEquals(listOf(ref), s.photos(ag, CameraShots.STEP, 10).map { it.getString("ref") })
        assertEquals("new", row.getString("status"))
        assertEquals(1_762_300_000_000L, java.time.OffsetDateTime.parse(row.getString("taken_at")).toInstant().toEpochMilli())
        assertEquals("camera", row.getJSONObject("by").getString("type")); assertEquals(7L, row.getJSONObject("by").getLong("interaction_id"))
        assertEquals(1, s.count(ag, "photos"))
        // A table without taken_at gets none.
        val plain = AppletTables.parse(o("tables" to a(o("id" to "shots", "columns" to o("photo" to o("type" to "photo"), "taken_at" to o("type" to "text")))))).single()
        assertTrue(CameraShots.save(s, ag, plain, "photo", o(), byteArrayOf(4), CameraShots.by(7L)).isNull("taken_at"))
    }

    @Test fun cameraAndOwnerWritesAreNotRateLimited() {
        val s = AppletTableStore.of(app); val ag = agent()
        repeat(AppletTables.MAX_WRITES_PER_MINUTE + 1) { CameraShots.save(s, ag, photos, "photo", o("status" to "new"), byteArrayOf(it.toByte()), CameraShots.by(1L)) }
        assertEquals(AppletTables.MAX_WRITES_PER_MINUTE + 1, s.count(ag, "photos"))
        // Those writes didn't use up the applet's own 60 a minute either.
        val flow = o("type" to "flow", "run_id" to "r")
        repeat(AppletTables.MAX_WRITES_PER_MINUTE) { s.add(ag, photos, o("status" to "done"), flow) }
        try { s.add(ag, photos, o("status" to "done"), flow); fail() } catch (e: AppletTables.Refused) { assertEquals("rate_limited", e.code) }
        // The owner's edits and own rows still go through.
        s.add(ag, photos, o("status" to "done"), o("owner" to true))
        val id = s.rows(ag, "photos", 1).first().getString("id")
        assertEquals("reading", s.update(ag, photos, id, o("status" to "Reading"), o("type" to "owner", "owner" to true)).getString("status"))
    }

    @Test fun aRefusedRowLeavesNoRowBehind() {
        val s = AppletTableStore.of(app); val ag = agent()
        try { CameraShots.save(s, ag, photos, "photo", o("status" to "lost"), byteArrayOf(9), CameraShots.by(1L)); fail() }
        catch (e: AppletTables.Refused) { assertEquals("invalid_row", e.code) }
        assertEquals(0, s.count(ag, "photos"))
        assertFalse(s.photos(ag, CameraShots.STEP, 10).isEmpty()) // kept until the daily clean-up drops the unused photo
    }

    @Test fun keptPhotoLimitsAreRaised() {
        assertEquals(1000, AppletTables.MAX_KEPT_PHOTOS)
        assertEquals(500L * 1024 * 1024, AppletTables.MAX_KEPT_PHOTO_BYTES)
    }
}
