package cc.bridgit.app.applets

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** A0451 slice 1.1: the privacy.camera block, its checks and the effective retention window. */
class CameraPrivacyTest {
    private fun o(vararg p: Pair<String, Any?>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
    private fun a(vararg v: Any?) = JSONArray().apply { v.forEach { put(it) } }
    private fun recipe(id: String) = JSONObject(File("../site-content/public/recipes/$id/applet.json").readText())

    private val tables = AppletTables.parse(o("tables" to a(
        o("id" to "notices", "keep_days" to 30, "columns" to o("photo" to o("type" to "photo"), "note" to o("type" to "text"))),
        o("id" to "log", "keep_days" to 3, "columns" to o("photo" to o("type" to "photo"))))))

    private fun plan(steps: JSONArray? = null, watch: JSONObject? = null, card: AppletPackage.Card? = null) = AppletPackage.Plan(
        watches = listOfNotNull(watch?.let { "w" to it }), schedule = null,
        flows = listOfNotNull(steps?.let { "main" to o("trigger" to o("type" to "called"), "steps" to it) }),
        card = card, native = null, config = JSONObject(), tables = tables)

    private fun keptInto(table: String) = a(
        o("id" to "shot", "type" to "photo", "keep" to true),
        o("type" to "record", "table" to table, "row" to o("photo" to o("\$step" to "shot.kept"))))

    private fun block(tables: List<String> = listOf("notices"), days: Any = 7) = o("privacy" to o("camera" to o(
        "purpose" to "mess notices", "keep_when" to JSONArray(tables.map { o("table" to it) }), "retention_days" to days,
        "never_kept" to "Frames with no mess notice are dropped on the phone.")))

    private fun privacy(tables: List<String> = listOf("notices"), days: Int = 7) = CameraPrivacy.parse(block(tables, days))!!

    @Test fun parsesTheBlock() {
        val p = privacy()
        assertEquals("mess notices", p.purpose)
        assertEquals(setOf("notices"), p.keepWhenTables)
        assertEquals(7, p.retentionDays)
        assertNull(CameraPrivacy.parse(o("id" to "x")))
    }

    @Test fun malformedBlocksAreRefused() {
        fun refused(d: JSONObject, contains: String) {
            try { CameraPrivacy.parse(d); fail("expected refusal: $contains") }
            catch (e: AppletPackage.Bad) { assertTrue("${e.message} should mention $contains", e.message!!.contains(contains)) }
        }
        refused(block().also { it.getJSONObject("privacy").getJSONObject("camera").put("extra", 1) }, "privacy.camera.extra")
        refused(block().also { it.getJSONObject("privacy").put("audio", o()) }, "privacy.audio")
        refused(block().also { it.getJSONObject("privacy").getJSONObject("camera").remove("purpose") }, "purpose")
        refused(block().also { it.getJSONObject("privacy").getJSONObject("camera").put("keep_when", a("notices")) }, "keep_when[0]")
        refused(block(days = 7.5), "whole number")
        refused(block(days = "7"), "whole number")
    }

    @Test fun cameraAppletWithoutTheBlockIsAProblem() {
        val photoFlow = plan(a(o("id" to "shot", "type" to "photo")))
        assertTrue(CameraPrivacy.isCameraApplet(photoFlow))
        assertTrue(CameraPrivacy.problems(photoFlow, null).single().contains("needs privacy.camera"))
        assertTrue(CameraPrivacy.isCameraApplet(plan(watch = o("data" to a("camera")))))
        assertTrue(CameraPrivacy.isCameraApplet(plan(card = AppletPackage.Card("c", null, JSONObject(), usesCamera = true))))
    }

    @Test fun nonCameraAppletIsUnaffected() {
        val p = plan(a(o("type" to "notice", "title" to "hi")), watch = o("data" to a("sound")))
        assertFalse(CameraPrivacy.isCameraApplet(p))
        assertEquals(emptyList<String>(), CameraPrivacy.problems(p, null))
    }

    @Test fun keepWhenMustNameADeclaredTable() {
        val probs = CameraPrivacy.problems(plan(keptInto("notices")), privacy(listOf("notices", "missing")))
        assertEquals(1, probs.size)
        assertTrue(probs[0], probs[0].contains("missing isn't in processing.tables"))
    }

    @Test fun keptPhotoOutsideKeepWhenIsAProblem() {
        assertEquals(emptyList<String>(), CameraPrivacy.problems(plan(keptInto("notices")), privacy()))
        val probs = CameraPrivacy.problems(plan(keptInto("log")), privacy())
        assertTrue(probs.single(), probs.single().contains("table log"))
        // A photo that isn't kept writes nothing, so its table doesn't need to be in keep_when.
        val notKept = a(o("id" to "shot", "type" to "photo"), o("type" to "record", "table" to "log", "row" to o("note" to "x")))
        assertEquals(emptyList<String>(), CameraPrivacy.problems(plan(notKept), privacy()))
    }

    @Test fun cameraButtonOutsideKeepWhenIsAProblem() {
        val pieces = o("title" to "t", "pieces" to a(o("type" to "buttons", "items" to a(o("label" to "Snap", "do" to o("camera" to o("table" to "log")))))))
        val p = plan(card = AppletPackage.Card("t", null, JSONObject(), pieces, pieces, usesCamera = true))
        assertEquals(setOf("log"), CameraPrivacy.cameraButtonTables(p))
        assertTrue(CameraPrivacy.problems(p, privacy()).single().contains("camera button writes to table log"))
        assertEquals(emptyList<String>(), CameraPrivacy.problems(p, privacy(listOf("log"))))
    }

    @Test fun retentionOutOfRangeIsAProblem() {
        for (d in listOf(0, 91, -1)) assertTrue(CameraPrivacy.problems(plan(keptInto("notices")), privacy(days = d)).single().contains("1-90"))
        assertEquals(emptyList<String>(), CameraPrivacy.problems(plan(keptInto("notices")), privacy(days = 90)))
    }

    @Test fun effectiveWindowIsTheMinimumAndTheBackendCantLengthenIt() {
        val notices = tables.first { it.id == "notices" } // keep_days 30
        val log = tables.first { it.id == "log" } // keep_days 3
        assertEquals(7, CameraPrivacy.effectiveDays(privacy(days = 7), notices, null))
        assertEquals(3, CameraPrivacy.effectiveDays(privacy(days = 7), log, null))
        assertEquals(2, CameraPrivacy.effectiveDays(privacy(days = 7), notices, 2))
        // The backend asks for longer: the package's window stands.
        assertEquals(7, CameraPrivacy.effectiveDays(privacy(days = 7), notices, 60))
        // Never past the camera maximum, never below a day.
        assertEquals(90, CameraPrivacy.effectiveDays(CameraPrivacy("p", emptySet(), 400, "n"), null, 1000))
        assertEquals(1, CameraPrivacy.effectiveDays(privacy(days = 7), notices, 0))
    }

    // ---- AppletPackage.check / policyWarnings ----

    @Test fun existingCameraRecipesStayValidAndGetAWarning() {
        // Recipes and installed applets predate privacy.camera (D4): check accepts them, validate warns.
        for (id in listOf("pet-monitor", "room-change-watcher")) {
            val p = AppletPackage.check(recipe(id))
            val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
            assertTrue(id, CameraPrivacy.isCameraApplet(plan))
            assertTrue(id, AppletPackage.policyWarnings(p.definition, plan).any { it.contains("needs privacy.camera") })
        }
        val voice = AppletPackage.check(recipe("voice-notebook"))
        assertFalse(AppletPackage.policyWarnings(voice.definition, AppletPackage.compile(voice, AppletPackage.fill(voice, null))).any { it.contains("privacy.camera") })
    }

    @Test fun checkEnforcesADeclaredBlock() {
        // pet-monitor declares table "checks"; a block naming it is accepted and silences the warning.
        val ok = recipe("pet-monitor").put("privacy", block(listOf("checks")).getJSONObject("privacy"))
        val p = AppletPackage.check(ok)
        assertFalse(AppletPackage.policyWarnings(p.definition, AppletPackage.compile(p, AppletPackage.fill(p, null))).any { it.contains("privacy.camera") })
        try { AppletPackage.check(recipe("pet-monitor").put("privacy", block(listOf("nope")).getJSONObject("privacy"))); fail() }
        catch (e: AppletPackage.Bad) { assertTrue(e.message, e.message!!.contains("nope isn't in processing.tables")) }
        try { AppletPackage.check(recipe("pet-monitor").put("privacy", block(listOf("checks"), 120).getJSONObject("privacy"))); fail() }
        catch (e: AppletPackage.Bad) { assertTrue(e.message, e.message!!.contains("1-90")) }
    }
}
