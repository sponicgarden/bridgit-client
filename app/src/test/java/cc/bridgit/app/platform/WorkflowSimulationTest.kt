package cc.bridgit.app.platform

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkflowSimulationTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private lateinit var agent: String

    @Before fun grantWorkflowAccess() {
        app.settings.listenerEnabled = true
        agent = app.policy.addAgent("Dinner builder").first.id
        Capabilities.setEnabled(app, "workflows", true)
        app.platform.grant(agent, "workflows", Long.MAX_VALUE)
    }

    private fun dinner() = JSONObject("""
        {
          "name": "Dinner check",
          "trigger": {"type": "button"},
          "steps": [
            {"id":"shot", "type":"photo", "camera":"rear"},
            {"id":"look", "type":"ask_model", "model":"clef", "photo":{"${'$'}step":"shot.photo"},
             "questions":{"eating":{"type":"choice","instructions":"Is the dog eating?",
                                    "criteria":{"yes":"Eating","no":"Not eating"}}}},
            {"id":"check", "type":"ask_owner", "only_if":{"step":"look.answers.eating","is":"no"},
             "title":"Remind the family?", "choices":["Remind them","Skip"], "wait_s":60},
            {"id":"reminder", "type":"notice", "only_if":{"step":"check.choice","is":"Remind them"},
             "title":["Dinner check: ",{"${'$'}step":"look.answers.eating"}]}
          ]
        }
    """.trimIndent())

    private fun fixtures(eating: String) = JSONObject()
        .put("photos", JSONObject().put("shot", JSONObject()))
        .put("ask_model", JSONObject().put("look", JSONObject()
            .put("answers", JSONObject().put("eating", eating))
            .put("cost_usd", 0.003)))

    private fun simulate(fixtures: JSONObject, flow: JSONObject = dinner()): JSONObject {
        val result = runBlocking {
            app.platform.read(agent, "workflow.simulate", JSONObject().put("flow", flow).put("fixtures", fixtures))
        }
        assertTrue(result.getBoolean("simulated"))
        assertEquals(0.0, result.getDouble("cost_usd"), 0.0)
        for (kind in listOf("interaction", "execution", "workflow")) {
            assertTrue("Simulation created a live $kind", app.platform.store.list(agent, kind).isEmpty())
        }
        return result
    }

    private fun JSONObject.entries(field: String): List<JSONObject> = getJSONArray(field).let { array ->
        (0 until array.length()).map(array::getJSONObject)
    }

    @Test fun dogAteSkipsTheOwnerQuestionAndReminder() {
        val result = simulate(fixtures("yes"))
        assertEquals("completed", result.getString("status"))
        assertEquals(listOf("ok", "ok", "skipped", "skipped"), result.entries("steps").map { it.getString("status") })
        val skipped = result.entries("steps")[2]
        assertEquals("look.answers.eating", skipped.getJSONObject("condition").getString("step"))
        assertTrue(skipped.getString("reason").isNotBlank())
        assertTrue(result.entries("would").none { it.getString("type") in setOf("ask_owner", "notice") })
    }

    @Test fun dogDidNotEatAsksOwnerAndResolvesTheReminderFromTheModelAnswer() {
        val fixtures = fixtures("no").put("ask_owner", JSONObject().put("check", JSONObject().put("choice","Remind them")))
        val result = simulate(fixtures)
        assertEquals("completed", result.getString("status"))
        val actions = result.entries("would").filter { it.getString("type") in setOf("ask_owner", "notice") }
        assertEquals(listOf("Remind the family?", "Dinner check: no"), actions.map { it.getString("title") })
    }

    @Test fun unavailableCameraStopsBeforeTheModelOrAnyQuestion() {
        val result = simulate(JSONObject("""{"photos":{"shot":{"error":"camera_unavailable"}}}"""))
        assertEquals("failed", result.getString("status"))
        val steps = result.entries("steps")
        assertEquals(listOf("failed","not_run","not_run","not_run"), steps.map { it.getString("status") })
        assertEquals("camera_unavailable", steps.first().getString("error"))
        assertEquals("rear", steps.first().getString("camera"))
        assertTrue(result.entries("would").none { it.getString("type") in setOf("ask_owner", "notice") })
    }

    @Test fun skippedMissingModelFixtureLeavesNoValueForTheOwnerCondition() {
        val flow = dinner()
        flow.getJSONArray("steps").getJSONObject(1).put("on_error", "skip")
        val result = simulate(JSONObject("""{"photos":{"shot":{}}}"""), flow)
        val steps = result.entries("steps")
        assertEquals("completed", result.getString("status"))
        assertEquals(listOf("ok", "failed", "skipped", "skipped"), steps.map { it.getString("status") })
        assertEquals("no_fixture", steps[1].getString("error"))
        assertTrue(steps[1].getString("message").contains("look"))
    }

    @Test fun simulationEnforcesTheValidationGrantAndPerAgentMinuteLimit() = runBlocking {
        val request = JSONObject().put("flow", dinner()).put("fixtures", fixtures("yes"))
        repeat(30) { app.platform.read(agent, "workflow.simulate", request) }
        try {
            app.platform.read(agent, "workflow.simulate", request)
            fail("Expected the per-agent simulation limit")
        } catch (e: ApiError) {
            assertEquals(429, e.http)
        }
        val other = app.policy.addAgent("Another builder").first.id
        app.platform.grant(other, "workflows", Long.MAX_VALUE)
        assertTrue(app.platform.read(other, "workflow.simulate", request).getBoolean("simulated"))
        app.platform.revoke(other, "workflows")
        try {
            app.platform.read(other, "workflow.simulate", request)
            fail("Expected owner revocation to block simulation")
        } catch (e: ApiError) {
            assertEquals("agent_not_allowed", e.code)
        }
    }

    @Test fun retryRecoversFromTransientReadAndNeverRepeatsAnAlertWhoseReplyWasLost() {
        val steps = JSONArray("""[
            {"id":"snapshot","type":"photo","on_error":"retry","retries":2},
            {"id":"notify","type":"alert","on_error":"retry","retries":2},
            {"id":"after","type":"notice"}
        ]""")
        val run = FlowRun(steps, JSONObject())
        val dispatched = mutableListOf<String>()
        var retries = 0
        run.run(
            beforeStep = {},
            facts = { FlowConditions.Clock(java.time.ZonedDateTime.now()) },
            checkpoint = { _, _ -> },
            retry = { retries++ },
            effect = { _, id ->
                dispatched.add(id)
                when {
                    id == "snapshot" && dispatched.size == 1 -> throw ApiError(503, "satellite_offline", "Camera is connecting")
                    id == "notify" -> throw ApiError(504, "timed_out", "Alert was delivered but its reply was lost")
                    else -> JSONObject()
                }
            }
        )
        assertEquals(listOf("snapshot", "snapshot", "notify"), dispatched)
        assertEquals(1, retries)
        assertEquals("unknown_outcome", run.status)
        assertEquals("unknown_outcome", run.log.getJSONObject(1).getString("status"))
        assertEquals("not_run", run.log.getJSONObject(2).getString("status"))
    }
}
