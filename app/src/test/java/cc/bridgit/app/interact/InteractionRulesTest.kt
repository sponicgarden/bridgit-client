package cc.bridgit.app.interact

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ways interaction validation can fail (CAP-31):
 * a link that opens something dangerous (javascript:, intent:, file:, content:, http:, userinfo tricks);
 * a notice with buttons; a question with only links; duplicate or odd control ids; over-long title, text,
 * labels or options; expiry outside 60 s - 7 days; an answer naming a button or option that doesn't exist,
 * or a free-text answer that is too long.
 */
class InteractionRulesTest {
    private fun q(vararg controls: JSONObject, extra: JSONObject.() -> Unit = {}) = JSONObject()
        .put("kind", "question").put("title", "Pay?").put("controls", JSONArray(controls.toList())).apply(extra)
    private fun btn(id: String, label: String = id) = JSONObject().put("type", "button").put("id", id).put("label", label)
    private fun link(url: String) = JSONObject().put("type", "link").put("id", "l").put("label", "Open").put("url", url)
    private fun bad(b: JSONObject) = try { InteractionRules.parse(b); fail("accepted $b") } catch (_: InteractionError) {}

    @Test fun goodQuestionAndNotice() {
        val d = InteractionRules.parse(q(btn("yes"), btn("no"), JSONObject().put("type", "text").put("id", "note").put("label", "Reply"),
            JSONObject().put("type", "choice").put("id", "when").put("label", "When").put("options", JSONArray(listOf("Today", "This week"))),
            link("https://hostinger.com/invoice/1")))
        assertEquals(5, d.controls.size)
        assertEquals(86_400L, d.expiresInS)
        val n = InteractionRules.parse(JSONObject().put("kind", "notice").put("title", "Backup done").put("controls", JSONArray().put(link("https://x.com"))))
        assertEquals(IKind.NOTICE, n.kind)
    }

    @Test fun linkAllowlist() {
        for (ok in listOf("https://a.com/x", "geo:52.5,13.4", "tel:+491234", "mailto:a@b.c")) assertTrue(ok, InteractionRules.linkShownAs(ok) != null)
        for (no in listOf("http://a.com", "javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/x", "content://x/y",
            "https://user:pw@evil.com/", "https:///nohost", "data:text/html,<b>", "ftp://a.com", "market://details?id=x"))
            assertNull(no, InteractionRules.linkShownAs(no))
        assertEquals("hostinger.com", InteractionRules.linkShownAs("https://HOSTINGER.com/pay"))
        bad(q(btn("y"), link("javascript:alert(1)")))
        bad(q(btn("y"), JSONObject().put("type", "link").put("id", "p").put("label", "App").put("package", "not a package")))
        InteractionRules.parse(q(btn("y"), JSONObject().put("type", "link").put("id", "p").put("label", "WhatsApp").put("package", "com.whatsapp")))
    }

    @Test fun shapeRules() {
        bad(JSONObject().put("kind", "notice").put("title", "x").put("controls", JSONArray().put(btn("b"))))
        bad(q(link("https://a.com")))
        bad(q())
        bad(q(btn("a"), btn("a")))
        bad(q(btn("bad id!")))
        bad(q(btn("a", "x".repeat(41))))
        bad(q(*Array(7) { btn("b$it") }))
        bad(q(btn("a")) { put("title", "t".repeat(81)) })
        bad(q(btn("a")) { put("text", "t".repeat(2001)) })
        bad(q(btn("a")) { put("expires_in_seconds", 59) })
        bad(q(btn("a")) { put("expires_in_seconds", 604_801) })
        bad(q(btn("a")) { put("priority", "loud") })
        bad(q(btn("a")) { put("kind", "html") })
        bad(q(JSONObject().put("type", "choice").put("id", "c").put("label", "C").put("options", JSONArray(listOf("only one")))))
    }

    @Test fun answers() {
        val controls = InteractionRules.parse(q(btn("yes"), JSONObject().put("type", "text").put("id", "note").put("label", "Note"),
            JSONObject().put("type", "choice").put("id", "when").put("label", "When").put("options", JSONArray(listOf("Today", "Later"))))).controls
        val a = InteractionRules.checkAnswer(controls, "yes", mapOf("note" to "x".repeat(3000), "when" to "Later"))
        assertEquals("yes", a.getString("button"))
        assertEquals(2000, a.getJSONObject("values").getString("note").length)
        for ((b, v) in listOf("no" to emptyMap(), null to mapOf("when" to "Never"), null to mapOf("ghost" to "x"), null to emptyMap<String, String>()))
            try { InteractionRules.checkAnswer(controls, b, v); fail("accepted $b $v") } catch (_: InteractionError) {}
    }
}
