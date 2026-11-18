package cc.bridgit.app.watch

import cc.bridgit.app.judgment.Jev
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId

class CalibrationTest {
    private val zone = ZoneId.of("UTC")
    private val day = 86_400_000L
    private val now = 40 * day

    private fun choice(c: String, conf: Double) = Jev.Answer("clef", JSONObject("""{"activity":{"type":"choice","choice":"$c","confidence":$conf}}"""), 1, 0.0001, 900)

    private var n = 0
    private fun rec(at: Long, stage: String, decided: Boolean, answer: String? = "resting", conf: Double? = 0.75, camera: String = "rear", cost: Double = 0.0, dark: Boolean = false): JSONObject {
        n++
        val conditions = JSONObject().put("hour", java.time.Instant.ofEpochMilli(at).atZone(zone).hour).put("dark", dark)
        return Calibration.record("d$n", "r$n", at, "w1", "activity", stage, stage, camera, conditions, cost, 10L * n,
            Calibration.result(answer, conf, decided, "test"), final = true)
    }

    @Test fun auditShareHasAFloorAgentsCantGoUnder() {
        assertEquals(0.05, Calibration.parseAuditShare(0.05)!!, 1e-9)
        assertNull(Calibration.parseAuditShare(JSONObject.NULL))
        for (bad in listOf<Any>(0.0, 0.004, 0.6, "0.1", Double.NaN)) try { Calibration.parseAuditShare(bad); fail("accepted $bad") } catch (_: Calibration.Bad) {}
        // The default is 1 in 50; a stored value below the floor still audits at the floor.
        assertTrue(Calibration.auditDue(null, 0.019)); assertFalse(Calibration.auditDue(null, 0.021))
        assertTrue(Calibration.auditDue(0.0001, 0.004)); assertFalse(Calibration.auditDue(0.0001, 0.006))
    }

    @Test fun primaryReadsChoicesAndNouls() {
        assertEquals("eating" to 0.7, Calibration.primary(choice("eating", 0.7), "activity"))
        val noul = Jev.Answer("jev", JSONObject("""{"q":{"type":"noul","noul":0.2}}"""), 1, 0.0, 1)
        assertEquals("no" to 0.2, Calibration.primary(noul, "q"))
        assertEquals(null to null, Calibration.primary(noul, "missing"))
    }

    @Test fun wilsonIntervalIsSane() {
        assertNull(Calibration.wilson(0, 0))
        val i = Calibration.wilson(0, 40)!!
        assertEquals(0.0, i.getDouble(0), 1e-9); assertTrue(i.getDouble(1) in 0.05..0.1)
        val j = Calibration.wilson(5, 50)!!
        assertTrue(j.getDouble(0) < 0.1 && j.getDouble(1) > 0.1)
    }

    @Test fun reportMeasuresReuseAuditsAndBands() {
        val records = mutableListOf<JSONObject>()
        // Old record outside a 7-day window.
        records += rec(now - 10 * day, Calibration.CHANGE_GATE, true)
        // 40 free answers, 10 audited: 8 agreed, 2 missed (both in the dark on the front camera).
        repeat(40) { i ->
            val dark = i < 2
            val r = rec(now - day + i * 60_000L, Calibration.CHANGE_GATE, true, conf = if (i % 2 == 0) 0.95 else 0.55, camera = if (dark) "front" else "rear", dark = dark)
            if (i < 10) r.put("audit_due", true).put("audit", Calibration.audit(if (i < 2) "eating" else "resting", 0.8, agreed = i >= 2, at = now, service = "clef"))
            if (i == 39) r.put("audit_due", true).put("audit_skipped", "max_paid_per_hour")
            records += r
        }
        repeat(20) { records += rec(now - day, Calibration.CHANGE_GATE, false, answer = "ask", conf = null) }
        repeat(30) { records += rec(now - day, Calibration.MODEL, true, cost = 0.0001) }
        records += rec(now - day, Calibration.PAID_CAP, false, answer = null, conf = null)
        records += rec(now - day, Calibration.CAMERA, false, answer = null, conf = null)
        val r = Calibration.report(records, now, 7, 0.25, zone)
        assertEquals(92, r.getInt("records"))
        assertEquals(0.25, r.getJSONObject("settings").getDouble("audit_share"), 1e-9)
        val reuse = r.getJSONObject("reuse")
        assertEquals(60, reuse.getInt("free_checks")); assertEquals(40, reuse.getInt("answered_free"))
        val audit = r.getJSONObject("audit")
        assertEquals(11, audit.getInt("due")); assertEquals(10, audit.getInt("done")); assertEquals(1, audit.getInt("skipped"))
        assertEquals(2, audit.getInt("misses")); assertEquals(0.2, audit.getDouble("miss_rate"), 1e-9)
        assertEquals(1, audit.getJSONObject("skipped_because").getInt("max_paid_per_hour"))
        val model = (0 until r.getJSONArray("stages").length()).map { r.getJSONArray("stages").getJSONObject(it) }.first { it.getString("stage") == Calibration.MODEL }
        assertEquals(30, model.getInt("count")); assertEquals(0.003, model.getDouble("cost_usd"), 1e-9)
        val bands = r.getJSONArray("calibration")
        assertEquals(20, bands.getJSONObject(0).getInt("free_answers")) // 0.55
        assertEquals(20, bands.getJSONObject(4).getInt("free_answers")) // 0.95
        assertEquals(5, bands.getJSONObject(4).getInt("audited"))
        val by = r.getJSONObject("by")
        assertEquals(1.0, by.getJSONObject("camera").getJSONObject("front").getDouble("miss_rate"), 1e-9)
        assertEquals(0.0, by.getJSONObject("camera").getJSONObject("rear").getDouble("miss_rate"), 1e-9)
        assertEquals(2, by.getJSONObject("light").getJSONObject("dark").getInt("misses"))
        assertEquals(1, r.getJSONObject("health").getInt("no_photo")); assertEquals(1, r.getJSONObject("health").getInt("over_paid_cap"))
        assertTrue(r.getJSONArray("notes").getString(0).contains("Only 10 audited"))
    }

    @Test fun emptyReportSaysSo() {
        val r = Calibration.report(emptyList(), now, 7, null, zone)
        assertEquals(0, r.getInt("records"))
        assertEquals(JSONObject.NULL, r.getJSONObject("audit").get("miss_rate"))
        assertEquals(Calibration.DEFAULT_AUDIT_SHARE, r.getJSONObject("settings").getDouble("audit_share"), 1e-9)
        assertFalse(r.getJSONObject("settings").getBoolean("audit_share_set"))
    }

    @Test fun logKeepsBoundsSurvivesRestartAndHoldsAudits() {
        val dir = Files.createTempDirectory("decisions").toFile()
        try {
            val f = File(dir, "w1.jsonl")
            val t0 = System.currentTimeMillis()
            val log = Calibration.Log(f, keepMs = 10 * day, max = 50)
            repeat(80) { i -> log.add(rec(t0 - 60L * (80 - i), Calibration.CHANGE_GATE, true)) }
            assertEquals(50, log.all().size)
            val last = log.all().last().getString("id")
            log.attach(last, JSONObject().put("audit", Calibration.audit("eating", 0.9, false, t0, "clef")))
            val again = Calibration.Log(f, keepMs = 10 * day, max = 50)
            assertEquals(50, again.all().size)
            assertFalse(again.all().last().getJSONObject("audit").getBoolean("agreed"))
            assertTrue(f.readLines().size <= 50)
            // Records past the keep time are dropped on load.
            val old = Calibration.Log(f, keepMs = 1L, max = 50)
            assertEquals(0, old.all().size)
            old.delete(); assertFalse(f.exists())
        } finally { dir.deleteRecursively() }
    }
}
