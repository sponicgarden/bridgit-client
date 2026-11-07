package cc.bridgit.app.ui.common

import cc.bridgit.app.log.LogEntry
import cc.bridgit.app.ui.common.ActivityRows.ChipStyle
import cc.bridgit.app.ui.common.ActivityRows.InteractionInfo
import cc.bridgit.app.ui.common.ActivityRows.Item
import cc.bridgit.app.ui.common.ActivityRows.Kind
import cc.bridgit.app.ui.common.ActivityRows.Tone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agent-page activity rows (#0003, #0004): real ids, collapsed long-polls, waits shown as waits. */
class ActivityRowsTest {
    private val agent = "Musey"
    private var nextId = 1L

    private fun req(
        ts: Long, method: String, endpoint: String, params: JSONObject = JSONObject(), http: Int = 200,
        result: String = "ok", durationMs: Long = 40, bytesOut: Long = 293, message: String = "", data: JSONObject? = null,
    ) = LogEntry(id = nextId++, ts = ts, channel = "audit", kind = "request", level = if (http >= 400) "warn" else "info",
        agentId = "a1", agent = agent, method = method, endpoint = endpoint, params = params, http = http, result = result,
        bytesOut = bytesOut, durationMs = durationMs, message = message, data = data)

    private fun poll(ts: Long, id: Long = 10, durationMs: Long = 55_000) =
        req(ts, "GET", "/v1/interactions/{id}", JSONObject().put("id", id.toString()).put("wait", "55"), durationMs = durationMs)

    private fun eventsPoll(ts: Long) =
        req(ts, "GET", "/v1/events", JSONObject().put("since", "0").put("wait", "55"), durationMs = 55_000, bytesOut = 40)

    private fun create(ts: Long, id: Long = 10) = req(ts, "POST", "/v1/interactions", http = 201,
        message = "$agent sent a card: Bridgit demo", data = JSONObject().put("interaction_id", id).put("kind", "html"))

    // ---- endpoint and poll detection ----

    @Test fun endpointShowsRealIdInsteadOfTemplate() =
        assertEquals("/v1/interactions/10", ActivityRows.endpoint(poll(0)))

    @Test fun endpointFillsAnyTemplatedParameter() = assertEquals("/v1/gallery/photo/77",
        ActivityRows.endpoint(req(0, "GET", "/v1/gallery/photo/{id}", JSONObject().put("id", "77"))))

    @Test fun endpointKeepsTemplateWhenParameterMissing() =
        assertEquals("/v1/interactions/{id}", ActivityRows.endpoint(req(0, "GET", "/v1/interactions/{id}")))

    @Test fun pollNeedsGetWithWaitAndSuccess() {
        assertTrue(ActivityRows.isPoll(poll(0)))
        assertTrue(ActivityRows.isPoll(eventsPoll(0)))
        assertFalse(ActivityRows.isPoll(req(0, "GET", "/v1/interactions/{id}", JSONObject().put("id", "10"))))
        assertFalse(ActivityRows.isPoll(req(0, "GET", "/v1/events", JSONObject().put("wait", "0"))))
        assertFalse(ActivityRows.isPoll(req(0, "GET", "/v1/interactions/{id}",
            JSONObject().put("id", "10").put("wait", "55"), http = 429, result = "too_many_waits")))
    }

    @Test fun interactionIdFromPathOrCreateData() {
        assertEquals(10L, ActivityRows.interactionId(poll(0)))
        assertEquals(12L, ActivityRows.interactionId(create(0, 12)))
        assertNull(ActivityRows.interactionId(eventsPoll(0)))
    }

    // ---- collapsing ----

    @Test fun repeatedPollsCollapseIntoOneItemAboveTheCreate() {
        // Newest first, as the log returns them: four polls of question 10, then the POST that made it.
        val items = ActivityRows.collapse(listOf(poll(400), poll(300), poll(200), poll(100), create(50)))
        assertEquals(2, items.size)
        val g = items[0] as Item.Polls
        assertEquals(4, g.entries.size)
        assertEquals(400L, g.newest.ts)
        assertEquals(100L, g.oldest.ts)
        assertTrue(items[1] is Item.Single)
    }

    @Test fun interleavedInteractionAndEventsPollsGroupSeparately() {
        val items = ActivityRows.collapse(listOf(poll(500), eventsPoll(450), poll(400), eventsPoll(350), poll(300), create(10)))
        assertEquals(3, items.size)
        assertEquals(3, (items[0] as Item.Polls).entries.size)
        assertEquals("/v1/events", (items[1] as Item.Polls).newest.endpoint)
        assertEquals(2, (items[1] as Item.Polls).entries.size)
    }

    @Test fun pollsForDifferentQuestionsDoNotMerge() {
        val items = ActivityRows.collapse(listOf(poll(300, id = 11), poll(200, id = 10), poll(100, id = 11)))
        assertEquals(2, items.size)
        assertEquals(2, (items[0] as Item.Polls).entries.size)
        assertEquals(1, (items[1] as Item.Polls).entries.size)
    }

    @Test fun aRealActionInBetweenSplitsTheRun() {
        val torch = req(250, "POST", "/v1/flashlight")
        val items = ActivityRows.collapse(listOf(poll(300), torch, poll(200), poll(100)))
        assertEquals(3, items.size)
        assertEquals(1, (items[0] as Item.Polls).entries.size)
        assertEquals(2, (items[2] as Item.Polls).entries.size)
    }

    @Test fun failedPollStaysItsOwnRow() {
        val failed = req(250, "GET", "/v1/interactions/{id}", JSONObject().put("id", "10").put("wait", "55"),
            http = 429, result = "too_many_waits")
        val items = ActivityRows.collapse(listOf(poll(300), failed, poll(200)))
        assertEquals(3, items.size)
        assertTrue(items[1] is Item.Single)
    }

    // ---- rows ----

    @Test fun openQuestionPollGroupSaysWaitingWithCountAndNoDuration() {
        val g = ActivityRows.collapse(listOf(poll(400), poll(300), poll(200), poll(100)))[0]
        val row = ActivityRows.row(g, agent) { InteractionInfo("Bridgit demo", "html", "pending") }
        assertEquals("Waiting for your answer to card 10", row.title)
        assertTrue(row.subtitle!!.startsWith("“Bridgit demo” · 4 checks since "))
        assertEquals(4, row.count)
        assertEquals(Tone.WAITING, row.tone)
        assertEquals(Kind.WAIT, row.kind)
        val chips = row.chips.map { it.text }
        assertEquals("GET /v1/interactions/10", chips[0])
        assertTrue("waiting" in chips)
        assertTrue("waits up to 55 s" in chips)
        assertFalse("no 55.0 s duration chip", chips.any { it.contains("55.0") })
    }

    @Test fun answeredQuestionPollGroupSaysWaitedAndAnswered() {
        val g = ActivityRows.collapse(listOf(poll(400, durationMs = 3_000), poll(300)))[0]
        val row = ActivityRows.row(g, agent) { InteractionInfo("Ready?", "question", "answered") }
        assertEquals("Waited for your answer to question 10", row.title)
        assertEquals(Tone.SUCCESS, row.tone)
        assertTrue(row.chips.any { it.text == "answered" && it.style == ChipStyle.OK })
    }

    @Test fun noticePollsWaitToBeSeenNotAnswered() {
        val g = Item.Polls(listOf(poll(200), poll(100)))
        assertEquals("Waiting for you to see notice 10",
            ActivityRows.row(g, agent) { InteractionInfo("FYI", "notice", "pending") }.title)
        val seen = ActivityRows.row(g, agent) { InteractionInfo("FYI", "notice", "seen") }
        assertEquals("Waited for you to see notice 10", seen.title)
        assertEquals(Tone.SUCCESS, seen.tone)
        assertEquals("waiting for you to see notice 10",
            ActivityRows.homeLine(poll(0)) { InteractionInfo("FYI", "notice", "pending") })
        assertEquals("notice 10 seen", ActivityRows.homeLine(poll(0)) { InteractionInfo("FYI", "notice", "seen") })
    }

    @Test fun unknownInteractionUsesHowThePollEnded() {
        val timedOut = ActivityRows.row(Item.Polls(listOf(poll(100, durationMs = 55_002))), agent)
        assertEquals("Waiting for your answer to question 10", timedOut.title)
        val endedEarly = ActivityRows.row(Item.Polls(listOf(poll(100, durationMs = 2_000))), agent)
        assertEquals("Waited for your answer to question 10", endedEarly.title)
    }

    @Test fun eventsPollGroupRow() {
        val row = ActivityRows.row(ActivityRows.collapse(listOf(eventsPoll(200), eventsPoll(100)))[0], agent)
        assertEquals("Listened for your answers and other events", row.title)
        assertTrue(row.subtitle!!.startsWith("2 checks since "))
        assertEquals(Kind.EVENTS, row.kind)
    }

    @Test fun createRowUsesTheLoggedTitleWithoutTheAgentName() {
        val row = ActivityRows.row(Item.Single(create(50)), agent)
        assertEquals("Sent a card: Bridgit demo", row.title)
        assertEquals(Tone.PRIMARY, row.tone)
        assertEquals(Kind.INTERACTION, row.kind)
        assertEquals(ChipStyle.POST, row.chips[0].style)
        assertEquals("201", row.chips[1].text)
        assertEquals(ChipStyle.OK, row.chips[1].style)
    }

    @Test fun errorRowStandsOut() {
        val e = req(10, "GET", "/v1/camera/snapshot", http = 403, result = "capability_disabled")
        val row = ActivityRows.row(Item.Single(e), agent)
        assertTrue(row.isError)
        assertEquals(Tone.ERROR, row.tone)
        assertEquals("Couldn't use the camera", row.title)
        assertEquals("It's switched off in Feature enablement.", row.subtitle)
        assertTrue(row.chips.any { it.text == "403 capability_disabled" && it.style == ChipStyle.ERROR })
    }

    @Test fun failedCreateSaysTheQuestionWasNotSent() {
        val e = req(10, "POST", "/v1/interactions", http = 403, result = "capability_disabled")
        assertEquals("Couldn't send you a question", ActivityRows.row(Item.Single(e), agent).title)
    }

    @Test fun otherTemplatedCallsShowTheRealId() {
        val row = ActivityRows.row(Item.Single(req(10, "DELETE", "/v1/interactions/{id}", JSONObject().put("id", "7"))), agent)
        assertEquals("Cancelled question 7", row.title)
        assertEquals("DELETE /v1/interactions/7", row.chips[0].text)
        assertEquals(ChipStyle.DELETE, row.chips[0].style)
    }

    @Test fun sentencesNoLongerShowRouteTemplates() {
        assertEquals("Musey waited for your answer to question 10", Sentences.of(poll(0), agent))
        assertEquals("Musey fetched /v1/shares/5",
            Sentences.of(req(0, "GET", "/v1/shares/{id}", JSONObject().put("id", "5")), agent))
    }

    // ---- Home line ----

    @Test fun homeLineSaysWhatTheAgentIsWaitingFor() {
        assertEquals("waiting for your answer · question 10", ActivityRows.homeLine(poll(0)))
        assertEquals("waiting for your answer · card 10",
            ActivityRows.homeLine(poll(0)) { InteractionInfo("x", "html", "seen") })
        assertEquals("question 10 answered",
            ActivityRows.homeLine(poll(0)) { InteractionInfo("x", "question", "answered") })
        assertEquals("listening for events", ActivityRows.homeLine(eventsPoll(0)))
        assertEquals("interactions", ActivityRows.homeLine(create(0)))
    }
}
