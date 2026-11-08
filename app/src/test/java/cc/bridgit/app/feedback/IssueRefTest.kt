package cc.bridgit.app.feedback

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone shows the team-wide issue number (M0023 / A0023 / C0023) and never its local row id:
 * a fresh install's first issue must not read "#0001" while the team has it as 23.
 */
class IssueRefTest {
    private fun issue(source: String, remoteId: Long?, upload: String = UploadState.SENT) = Issue(
        id = 1, kind = IssueKind.BUG, description = "x", noAutofix = false, status = IssueStatus.NEW,
        createdAt = 0, updatedAt = 0, captureSource = source, agentId = null, agent = null,
        context = JSONObject(), log = JSONArray(), images = emptyList(), lastBy = null,
        upload = upload, remoteId = remoteId)

    @Test fun lettersMatchTheBackend() {
        assertEquals("M0023", IssueRef.of("form", 23))
        assertEquals("A4032", IssueRef.of("agent", 4032))
        assertEquals("A0007", IssueRef.of("agent_api", 7))
        assertEquals("C0005", IssueRef.of("sentry", 5))
        assertEquals("M12345", IssueRef.of("form", 12345))
    }

    @Test fun sentReportShowsTheTeamNumber() {
        val r = issue("form", 23)
        assertEquals("M0023", r.ref)
        assertEquals("M0023", r.number)
        val json = r.summaryJson { "t" }
        assertEquals("M0023", json.getString("ref"))
        assertEquals("M0023", json.getString("number"))
        assertEquals(23L, json.getLong("team_id"))
    }

    @Test fun unsentReportNeverShowsTheLocalId() {
        val pending = issue("form", null, UploadState.PENDING)
        assertNull(pending.ref)
        assertEquals("(not sent yet)", pending.number)
        assertTrue(pending.summaryJson { "t" }.isNull("number"))
        assertEquals("(not sent)", issue("agent", null, UploadState.OFF).number)
        // The uploader stores -1 when the backend's reply had no id.
        assertNull(issue("form", -1).ref)
    }
}
