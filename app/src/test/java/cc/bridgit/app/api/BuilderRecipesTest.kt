package cc.bridgit.app.api

import cc.bridgit.app.interact.CardRules
import cc.bridgit.app.schedule.ScheduleRules
import cc.bridgit.app.watch.WatchRules
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId

/** Publication fixtures exercise canonical parsers, without a device or physical actions. */
class BuilderRecipesTest {
    private val recipes = JSONObject(File("../site-content/content/builder/recipes.json").readText()).getJSONArray("recipes")

    @Test fun watchAndScheduleExamplesPassActualParsers() {
        for (i in 0 until recipes.length()) {
            val r = recipes.getJSONObject(i)
            if (r.getJSONObject("card").getString("mode") == "watch") {
                val body = r.getJSONArray("examples").getJSONObject(0).getJSONObject("request").getJSONObject("body")
                val spec = WatchRules.parse(body)
                // The published reply must be what the phone really echoes, not a copy of the request.
                val reply = r.getJSONArray("examples").getJSONObject(0).getJSONObject("response").getJSONObject("body")
                assertTrue(r.getString("id"), spec.json().similar(reply.getJSONObject("spec")))
                if (spec.tally != null) assertTrue(reply.has("tally_today") && reply.has("rules_fired"))
                assertEquals(if (r.getString("id") == "situation-counter") 20 else 30, spec.intervalS)
                assertTrue(spec.local.enabled)
                assertEquals(listOf("camera"), spec.data)
                if (r.getString("id") == "situation-counter") {
                    // The model is left out: a situation on a camera gets Clef, a tally and (pot boiling over) an alert rule.
                    assertEquals("pot_boiling_over", spec.situation)
                    assertEquals("clef", spec.model)
                    assertEquals(30, spec.maxPaidPerHour)
                    assertEquals("pot_boiling_over", spec.tally!!.track)
                    assertTrue(spec.expiresS <= 90 * 86400)
                } else if (r.getString("id") == "pet-monitor") {
                    assertNotNull(spec.tally)
                    // Always-on worst case must fit the $1.50 monthly per-phone model budget (decisions.md).
                    assertEquals(10, spec.maxPaidPerHour)
                    assertTrue(spec.expiresS <= 7 * 86400)
                } else {
                    assertEquals("none", spec.model)
                    assertTrue(spec.expiresS <= 7200)
                }
            }
            if (r.has("start_request")) {
                val manifest = r.getJSONObject("start_request").getJSONObject("body").getJSONObject("manifest")
                val spec = ScheduleRules.parse(manifest, ZoneId.of("Europe/Warsaw"), 1793786400000L,
                    { false }, { method, path -> if (Endpoints.all.any { it.method == method && it.fullPath == path }) null else "Unknown route" })
                assertEquals(1, spec.actions.size)
            }
        }
    }

    @Test fun allPublishedCardsPassCanonicalSandbox() {
        for (i in 0 until recipes.length()) {
            val r = recipes.getJSONObject(i)
            val id = r.getString("id")
            val body = JSONObject(File("../site-content/public/recipes/$id/request.json").readText())
            val html = body.getString("html")
            assertEquals(html, CardRules.checkHtml(html))
            assertEquals("html", body.getString("kind"))
            assertTrue(body.getBoolean("no_takeover"))
            assertTrue(html.contains("bridgit:answered"))
            assertFalse(html.contains("/v1/audio/record"))
        }
    }
}
