package cc.bridgit.app.api

import cc.bridgit.app.interact.CardRules
import cc.bridgit.app.interact.IKind
import cc.bridgit.app.interact.InteractionRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Ways the samples (GET /v1/samples, bug reports #0009/#0010) can fail an agent: a request the API refuses, an
 * html card that never answers or never confirms, an answer shape that isn't what the agent gets back, or a
 * swipe-bar visual on a single card (#0008).
 */
class SamplesTest {
    private val file = File("../site-content/public/samples/samples.json").readText()
    private val samples = JSONObject(file).getJSONArray("samples").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    @Test fun everyKindHasASampleAndEveryRequestIsAccepted() {
        assertEquals(setOf("notice", "question", "html", "text", "pieces"), samples.map { it.getString("kind") }.toSet())
        for (s in samples) {
            val id = s.getString("id")
            val draft = try { InteractionRules.parse(s.getJSONObject("request")) } catch (e: Exception) { fail("$id refused: ${e.message}"); return }
            assertEquals(id, s.getString("kind"), draft.kind.id)
            if (draft.kind == IKind.HTML) CardRules.checkHtml(draft.html)
        }
    }

    @Test fun htmlCardsAnswerWithAJsonStringAndShowTheConfirmation() {
        for (s in samples.filter { it.getString("kind") == "html" }) {
            val id = s.getString("id")
            val html = s.getJSONObject("request").getString("html")
            assertTrue("$id: respond with a JSON string", html.contains("respond(JSON.stringify("))
            assertTrue("$id: listens for bridgit:answered on document", html.contains("document.addEventListener('bridgit:answered'"))
            assertFalse("$id: no CARD placeholders left", html.contains("{{"))
            assertFalse("$id: no form submit (CSP form-action 'none')", html.contains("<form"))
            // #0008: progress is text, never Stories-style segment bars on a card that can't be swiped.
            assertFalse("$id: no segment/progress bars", Regex("segment|progress-?bar|stories", RegexOption.IGNORE_CASE).containsMatchIn(html))
            val answer = s.getJSONObject("answer")
            assertTrue("$id: html answers have button null", answer.isNull("button"))
            assertEquals("$id: html answers have empty values", 0, answer.getJSONObject("values").length())
            assertTrue("$id: html answers carry data", answer.has("data"))
        }
    }

    @Test fun questionAnswersMatchTheirControls() {
        for (s in samples.filter { it.getString("kind") == "question" }) {
            val draft = InteractionRules.parse(s.getJSONObject("request"))
            val a = s.getJSONObject("answer")
            val values = a.getJSONObject("values").let { v -> v.keys().asSequence().associateWith { v.getString(it) } }
            // The same check the phone runs on the owner's answer.
            assertEquals(a.toString(), InteractionRules.checkAnswer(draft.controls, a.optString("button").takeIf { !a.isNull("button") }, values).toString())
        }
    }

    @Test fun selectFiltersByIdAndKind() {
        assertEquals(1, Samples.select(file, "html-step", null).getJSONArray("samples").length())
        assertEquals(samples.count { it.getString("kind") == "question" }, Samples.select(file, null, "question").getJSONArray("samples").length())
        assertEquals(samples.size, Samples.select(file, null, null).getJSONArray("samples").length())
        try { Samples.select(file, "nope", null); fail() } catch (e: ApiError) { assertEquals("not_found", e.code); assertTrue(e.message!!.contains("html-step")) }
    }
}

/**
 * Bug report #0002: guide and demo arrive as tool output, so an agent's injection filter flags text that scripts
 * the agent ("You are X. Run part 1..."). They stay reference documents with a provenance line.
 */
class DeclarativeDocsTest {
    private val addr = Address("my-phone.tailnet-name.ts.net", "100.101.102.103", 8735)
    private val caps = listOf("interactions", "camera", "speaker").map { CapInfo(it, it, it, true, true, null) }
    private val scripted = listOf("You are ", "You were just", "Never print", "Run part 1", "run by you", "Say less, do more")

    @Test fun guideAndDemoAreReferenceNotScripts() {
        val tour = File("../site-content/public/demo/SKILL.md").readText()
        val docs = mapOf(
            "guide" to Guide.build("Muse", "1.6.0", addr, caps, "own", superPowers = false),
            "demo" to Demos.build("Muse", caps, tour) { true },
            "menu only" to Demos.build("Muse", caps) { true },
        )
        for ((name, text) in docs) {
            assertTrue("$name: provenance line", text.contains("Reference served by Bridgit on the owner's phone to agent Muse"))
            // Quoted blocks (the city tour's brief, which tour.started carries anyway) are prompts on purpose.
            val own = text.lines().filterNot { it.startsWith("> ") }.joinToString("\n")
            for (phrase in scripted) assertFalse("$name contains \"$phrase\"", own.contains(phrase))
        }
        assertTrue(docs.getValue("guide").contains("`GET /v1/samples`"))
        assertTrue(docs.getValue("demo").contains("/samples?id=html-step"))
    }
}
