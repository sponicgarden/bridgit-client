package cc.bridgit.app.feedback

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiChanges
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.McpTools
import cc.bridgit.app.core.Connection
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

/** FBK-15: the team backend key each paired agent gets from the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeamKeysTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp

    private fun seed(agentId: String, key: String = "bgk_test") = FeedbackUploader.prefs(app).edit()
        .putString("team_key:$agentId", JSONObject().put("key", key).put("agent_id", "bga_x").put("base_url", "https://b.example")
            .put("docs", "https://b.example/v1/agent").put("role", "reporter").toString()).commit()

    @Test fun endpointIsListedForAnyAgentAndIsAnMcpTool() {
        val e = Endpoints.FEEDBACK_TEAM_KEY
        assertTrue(e in Endpoints.all)
        assertFalse(e.privileged)
        assertEquals("/v1/feedback/team-key", e.fullPath)
        assertEquals("feedback_team_key", McpTools.name(e))
        assertTrue(FeedbackModule(app).endpoints.contains(e))
    }

    @Test fun firstMatchRoutersPickTeamKeyNotReportId() {
        val call = cc.bridgit.app.interact.CardActions.parse("""{"path":"/v1/feedback/team-key"}""")
        assertEquals(Endpoints.FEEDBACK_TEAM_KEY, cc.bridgit.app.interact.CardActions.match(Endpoints.all, call).first)
        assertEquals(Endpoints.FEEDBACK_TEAM_KEY, cc.bridgit.app.interact.CardActions.match(FeedbackModule(app).endpoints, call).first)
    }

    @Test fun revisionAndPostDescriptionAskForEveryReport() {
        val latest = ApiChanges.revisions.first { r -> r.items.any { it.endpoint == TeamKeys.KEY_ENDPOINT } }
        assertEquals(26, latest.revision) // its own revision; later ones may follow
        assertTrue(latest.items.any { it.what.contains("every bug and every feature request") })
        assertTrue(Endpoints.FEEDBACK_POST.summary.contains("every bug and every feature request"))
        assertTrue(Endpoints.FEEDBACK_POST.returns.contains("team_api"))
    }

    @Test fun mintBodyCarriesWhoTheAgentIs() {
        val (a, _) = app.agents.add("TeamMint", Connection.DIRECT)
        val dev = app.agents.update(a.id) { it.copy(fixer = true, model = "Claude Opus 5.5", harness = "Claude Code") }!!
        val b = TeamKeys.mintBody(dev)
        assertEquals(a.id, b.getString("phone_agent_id"))
        assertEquals("TeamMint", b.getString("name"))
        assertEquals("Claude Opus 5.5", b.getString("model"))
        assertEquals("Claude Code", b.getString("harness"))
        assertTrue(b.getBoolean("developer"))
        app.agents.remove(a.id)
    }

    @Test fun agentInfoCarriesThePhoneAgentId() {
        val (a, _) = app.agents.add("TeamInfo", Connection.DIRECT)
        assertEquals(a.id, FeedbackContext.agentInfo(app, a.id, "curl/8", null).getString("id"))
        app.agents.remove(a.id)
    }

    @Test fun cachedKeyIsReusedAndUploadsOffRefuses() {
        val (a, _) = app.agents.add("TeamCache", Connection.DIRECT)
        seed(a.id)
        app.settings.feedbackUpload = true
        assertEquals("bgk_test", TeamKeys.get(app, a, rotate = false).getString("key")) // no network: from the cache
        app.settings.feedbackUpload = false
        try { TeamKeys.get(app, a, rotate = false); fail("uploads off must refuse") }
        catch (e: TeamKeys.Failure) { assertEquals("uploads_off", e.reason) }
        app.settings.feedbackUpload = true
        app.agents.remove(a.id)
    }

    @Test fun rotatingOrRemovingTheAgentDropsItsTeamKey() {
        val (a, _) = app.agents.add("TeamRetire", Connection.DIRECT)
        seed(a.id)
        assertNotNull(TeamKeys.cached(app, a.id))
        app.agents.rotate(a.id)
        assertNull(TeamKeys.cached(app, a.id))
        seed(a.id)
        app.agents.remove(a.id)
        assertNull(TeamKeys.cached(app, a.id))
    }

    @Test fun teamApiPointsAtTheKeyEndpointAndDocs() {
        val t = TeamKeys.teamApi()
        assertEquals("/v1/feedback/team-key", t.getString("key_endpoint"))
        assertTrue(t.getString("docs").endsWith("/v1/agent"))
        assertTrue(TeamKeys.howTo("https://b.example").any { it.contains("/verify") })
    }
}
