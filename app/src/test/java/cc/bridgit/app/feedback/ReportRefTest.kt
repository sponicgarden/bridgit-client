package cc.bridgit.app.feedback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone shows the team-wide report number (M0023 / A0023 / C0023) and never its local row id:
 * a fresh install's first report must not read "#0001" while the team has it as 23.
 */
class ReportRefTest {
    private fun report(source: String, remoteId: Long?, upload: String = UploadState.SENT) = Report(
        id = 1, kind = ReportKind.BUG, description = "x", noAutofix = false, status = ReportStatus.NEW,
        createdAt = 0, updatedAt = 0, captureSource = source, agentId = null, agent = null,
        context = JSONObject(), log = JSONArray(), images = emptyList(), lastBy = null,
        upload = upload, remoteId = remoteId)

    @Test fun lettersMatchTheBackend() {
        assertEquals("M0023", ReportRef.of("form", 23))
        assertEquals("A4032", ReportRef.of("agent", 4032))
        assertEquals("A0007", ReportRef.of("agent_api", 7))
        assertEquals("C0005", ReportRef.of("sentry", 5))
        assertEquals("M12345", ReportRef.of("form", 12345))
    }

    @Test fun sentReportShowsTheTeamNumber() {
        val r = report("form", 23)
        assertEquals("M0023", r.ref)
        assertEquals("M0023", r.number)
        val json = r.summaryJson { "t" }
        assertEquals("M0023", json.getString("ref"))
        assertEquals("M0023", json.getString("number"))
        assertEquals(23L, json.getLong("team_id"))
    }

    @Test fun unsentReportNeverShowsTheLocalId() {
        val pending = report("form", null, UploadState.PENDING)
        assertNull(pending.ref)
        assertEquals("(not sent yet)", pending.number)
        assertTrue(pending.summaryJson { "t" }.isNull("number"))
        assertEquals("(not sent)", report("agent", null, UploadState.OFF).number)
        // The uploader stores -1 when the backend's reply had no id.
        assertNull(report("form", -1).ref)
    }
}
