package cc.bridgit.app.interact

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CardPiecesTest {
    private val watchRef = JSONObject().put("\$watch", "main")
    private val steps = mapOf(
        "shot" to JSONObject().put("id", "shot").put("type", "photo").put("keep", true),
        "look" to JSONObject().put("id", "look").put("type", "ask_model"),
    )
    private val scope = CardPieces.Scope(
        watch = { it is JSONObject && it.optString("\$watch") == "main" },
        tables = mapOf("notes" to setOf("text", "source_at")),
        steps = steps, buttonWorkflow = true,
        callCap = { m, p -> if (p.startsWith("/v1/weather")) "weather" else if (p.startsWith("/v1/mic")) "mic" else if (p.startsWith("/v1/watches")) "watches" else null },
        packaged = true,
        columnTypes = mapOf("notes" to mapOf("text" to "text", "source_at" to "time")))

    private fun src(s: String) = JSONObject().put("\$source", s)

    private fun card(vararg pieces: JSONObject, sources: JSONObject = JSONObject().put("activity", JSONObject().put("watch", watchRef).put("read", "tally"))) =
        JSONObject().put("title", "Pet").put("sources", sources).put("pieces", JSONArray(pieces.toList()))

    private fun bad(c: JSONObject, contains: String, s: CardPieces.Scope = scope) {
        try { CardPieces.check(c, s); fail("accepted: $c") } catch (e: CardPieces.Bad) { assertTrue("${e.message} should say $contains", e.message!!.contains(contains)) }
    }

    @Test fun acceptsEveryPieceAndCollectsWhatItCalls() {
        val c = card(
            JSONObject().put("type", "header").put("text", "A window"),
            JSONObject().put("type", "status").put("text", JSONArray().put("Seen ").put(src("activity.today.checks"))).put("tone", "ok"),
            JSONObject().put("type", "metrics").put("items", JSONArray().put(JSONObject().put("label", "Readings").put("value", src("activity.today.checks")).put("format", "number"))),
            JSONObject().put("type", "table").put("rows", src("activity.today.labels")).put("columns", JSONArray().put(JSONObject().put("key", "\$key").put("label", "Activity"))),
            JSONObject().put("type", "timeline").put("items", src("activity.episodes")).put("time", "start").put("label", "label"),
            JSONObject().put("type", "gallery").put("photos", src("kept.items")),
            JSONObject().put("type", "draft").put("id", "note").put("value", src("clip.transcript")),
            JSONObject().put("type", "buttons").put("items", JSONArray()
                .put(JSONObject().put("label", "Record").put("into", "clip").put("do", JSONObject().put("method", "POST").put("path", "/v1/mic/record").put("body", JSONObject())))
                .put(JSONObject().put("label", "Save").put("do", JSONObject().put("record", "notes").put("row", JSONObject().put("text", JSONObject().put("\$draft", "note")))))
                .put(JSONObject().put("label", "Pause").put("do", JSONObject().put("pause_applet", true)))
                .put(JSONObject().put("label", "Stop").put("do", JSONObject().put("method", "POST").put("path", JSONArray().put("/v1/watches/").put(watchRef).put("/pause"))))),
            sources = JSONObject().put("activity", JSONObject().put("watch", watchRef).put("read", "tally"))
                .put("kept", JSONObject().put("photos", "shot")).put("weather", JSONObject().put("call", JSONObject().put("path", "/v1/weather")).put("every_s", 900)))
        val checked = CardPieces.check(c, scope)
        assertEquals(setOf("weather", "mic", "watches"), checked.capabilities)
        assertEquals(setOf("notes"), checked.records)
        assertTrue("POST /v1/watches/x0/pause" in checked.calls)
    }

    @Test fun refusesWhatACardMayNotSay() {
        bad(card(JSONObject().put("type", "chart")), "unknown piece")
        bad(card(JSONObject().put("type", "text").put("text", "x").put("html", "<b>")), "isn't a field")
        bad(card(JSONObject().put("type", "text").put("text", src("nope.x"))), "names no source")
        bad(card(JSONObject().put("type", "text").put("text", "x")).put("sources", JSONObject().put("w", JSONObject().put("watch", JSONObject().put("\$watch", "other")).put("read", "tally"))), "\$watch")
        bad(card(JSONObject().put("type", "text").put("text", "x")).put("sources", JSONObject().put("w", JSONObject().put("call", JSONObject().put("method", "POST").put("path", "/v1/flashlight")))), "reads only")
        bad(card(JSONObject().put("type", "text").put("text", "x")).put("sources", JSONObject().put("t", JSONObject().put("table", "other"))), "processing.tables")
        bad(card(JSONObject().put("type", "text").put("text", "x")).put("sources", JSONObject().put("p", JSONObject().put("photos", "look"))), "keep: true")
        bad(card(JSONObject().put("type", "buttons").put("items", JSONArray().put(JSONObject().put("label", "Save").put("do", JSONObject().put("record", "notes").put("row", JSONObject().put("total", 1)))))), "isn't a column")
        bad(card(JSONObject().put("type", "buttons").put("items", JSONArray().put(JSONObject().put("label", "Go").put("do", JSONObject().put("shell", "rm"))))), "do must be")
        bad(card(JSONObject().put("type", "text").put("text", "x".repeat(1100))), "longer than")
        bad(card(*Array(25) { JSONObject().put("type", "text").put("text", "x") }), "1-24")
        // An agent's own card can't pause an applet or splice its path.
        val agent = CardPieces.Scope(watch = { it is String })
        bad(JSONObject().put("title", "x").put("pieces", JSONArray().put(JSONObject().put("type", "buttons").put("items", JSONArray().put(JSONObject().put("label", "P").put("do", JSONObject().put("pause_applet", true)))))), "installed applet", agent)
    }

    @Test fun replySourcesMayBeDeclaredForTheirButton() {
        val c = card(JSONObject().put("type", "text").put("text", src("scan.text")),
            JSONObject().put("type", "buttons").put("items", JSONArray().put(JSONObject().put("label", "Read").put("into", "scan").put("do", JSONObject().put("method", "POST").put("path", "/v1/ocr")))),
            sources = JSONObject().put("scan", JSONObject().put("reply", "scan")))
        CardPieces.check(c, scope)
        bad(card(JSONObject().put("type", "buttons").put("items", JSONArray().put(JSONObject().put("label", "Read").put("into", "activity").put("do", JSONObject().put("path", "/v1/ocr"))))), "names another source")
    }

    @Test fun aPathReferenceFillsAWholeSegment() {
        fun btn(vararg parts: Any) = card(JSONObject().put("type", "buttons").put("items", JSONArray().put(JSONObject().put("label", "P")
            .put("do", JSONObject().put("method", "POST").put("path", JSONArray(parts.toList()))))))
        bad(btn("/v1/watches", watchRef, "/pause"), "ends with /")
        bad(btn("/v1/watches/", watchRef, "pause"), "start with /")
        CardPieces.check(btn("/v1/watches/", watchRef), scope)
    }

    @Test fun sealJoinsPathPartsAndRefusesSplicedValues() {
        val raw = card(JSONObject().put("type", "buttons").put("items", JSONArray().put(JSONObject().put("label", "P").put("do", JSONObject().put("method", "POST").put("path", JSONArray().put("/v1/watches/").put(watchRef).put("/pause"))))))
        val filled = JSONObject(raw.toString().replace("{\"\$watch\":\"main\"}", "\"w123\""))
        CardPieces.seal(raw, filled)
        assertEquals("/v1/watches/w123/pause", filled.getJSONArray("pieces").getJSONObject(0).getJSONArray("items").getJSONObject(0).getJSONObject("do").getString("path"))
        val evil = JSONObject(raw.toString().replace("{\"\$watch\":\"main\"}", "\"../flashlight\""))
        try { CardPieces.seal(raw, evil); fail("spliced") } catch (e: CardPieces.Bad) { assertTrue(e.message!!.contains("plain id")) }
    }

    @Test fun referencesAreWholeValuesAndTextIsPlain() {
        val v = CardPieces.Values(mapOf("a" to JSONObject().put("today", JSONObject().put("checks", 4.0)).put("x", "<b>hi</b>")), mapOf("note" to "typed"))
        assertEquals(4.0, CardPieces.resolve(src("a.today.checks"), v))
        assertNull(CardPieces.resolve(src("a.missing.path"), v))
        assertEquals("typed", CardPieces.resolve(JSONObject().put("\$draft", "note"), v))
        assertEquals("Seen 4 times <b>hi</b>", CardPieces.textOf(JSONArray().put("Seen ").put(src("a.today.checks")).put(" times ").put(src("a.x")), v))
        assertTrue(CardPieces.holds(JSONObject().put("source", "a.today.checks").put("at_least", 3), v))
        assertFalse(CardPieces.holds(JSONObject().put("source", "a.nope").put("exists", true), v))
    }

    @Test fun formats() {
        val utc = java.time.ZoneId.of("UTC"); val en = java.util.Locale.US
        assertEquals("1,234", CardPieces.format(1234, "number", locale = en))
        assertEquals("1 h 20 min", CardPieces.format(80, "minutes"))
        assertEquals("45 min", CardPieces.format(45, "minutes"))
        assertEquals("40%", CardPieces.format(0.4, "percent"))
        assertEquals("5 Nov, 14:05", CardPieces.format("2026-11-05T14:05:00Z", "time", zone = utc, locale = en))
        assertEquals("3 min ago", CardPieces.format(1_800_000_000_000L - 180_000L, "ago", now = 1_800_000_000_000L))
        assertEquals("Yes", CardPieces.format(true, "yes_no"))
        assertEquals("Image changed", CardPieces.format(true, "words", JSONObject().put("words", JSONObject().put("true", "Image changed"))))
        assertEquals("€12.50", CardPieces.format(12.5, "money", JSONObject().put("currency", "EUR"), locale = en))
        assertNull(CardPieces.format(null, "number"))
        assertNull(CardPieces.format("abc", "number"))
    }

    @Test fun cardStateFollowsTheSources() {
        val c = card(JSONObject().put("type", "text").put("text", "x")).put("fresh_s", 120).put("states", JSONObject().put("empty", "No samples yet."))
        val now = 10_000_000L
        assertEquals("loading", CardPieces.state(c, emptyMap(), emptyMap(), false, now).id)
        assertEquals("paused", CardPieces.state(c, emptyMap(), emptyMap(), true, now).id)
        assertEquals("error", CardPieces.state(c, emptyMap(), mapOf("activity" to "Watch removed"), false, now).id)
        val empty = CardPieces.state(c, mapOf("activity" to JSONObject().put("today", JSONObject().put("checks", 0)).put("episodes", JSONArray())), emptyMap(), false, now)
        assertEquals("empty", empty.id); assertEquals("No samples yet.", empty.words)
        val fresh = JSONObject().put("today", JSONObject().put("checks", 3)).put("last_seen_at", java.time.Instant.ofEpochMilli(now - 60_000).toString())
        assertEquals("ready", CardPieces.state(c, mapOf("activity" to fresh), emptyMap(), false, now).id)
        val old = JSONObject(fresh.toString()).put("last_seen_at", java.time.Instant.ofEpochMilli(now - 3_600_000).toString())
        assertEquals("stale", CardPieces.state(c, mapOf("activity" to old), emptyMap(), false, now).id)
        val failed = JSONObject().put("last_check", JSONObject().put("error", true).put("message", "Camera busy"))
        assertEquals("Camera busy", CardPieces.state(c, mapOf("activity" to failed), emptyMap(), false, now).words)
    }

    @Test fun describesTheCardInPlainWords() {
        val c = card(JSONObject().put("type", "metrics").put("items", JSONArray().put(JSONObject().put("label", "Observed minutes").put("value", 1)).put(JSONObject().put("label", "Readings").put("value", 2))),
            JSONObject().put("type", "buttons").put("items", JSONArray().put(JSONObject().put("label", "Pause").put("do", JSONObject().put("pause_applet", true)))))
        assertEquals("It has its own page, \"Pet\", with observed minutes and readings, and the button \"Pause\" (pauses the applet).", CardPieces.describe(c, "Pet"))
    }

    @Test fun agentPostedPiecesCardsParse() {
        val body = JSONObject().put("kind", "pieces").put("title", "Weather").put("sources", JSONObject().put("w", JSONObject().put("call", JSONObject().put("path", "/v1/weather"))))
            .put("pieces", JSONArray().put(JSONObject().put("type", "metrics").put("items", JSONArray().put(JSONObject().put("label", "Now").put("value", src("w.now.temperature_c")).put("format", "celsius")))))
        val d = InteractionRules.parse(body)
        assertEquals(IKind.PIECES, d.kind)
        assertTrue(JSONObject(d.html!!).has("pieces"))
        try { InteractionRules.parse(JSONObject(body.toString()).put("html", "<p>")); fail("html on pieces") } catch (e: InteractionError) { assertTrue(e.message!!.contains("isn't a field")) }
    }
}
