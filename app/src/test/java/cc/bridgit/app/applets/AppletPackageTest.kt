package cc.bridgit.app.applets

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class AppletPackageTest {
    private fun file(id: String) = File("../site-content/public/recipes/$id/applet.json").readText()
    private val room get() = JSONObject(file("room-change-watcher"))
    /** The 1.0.0 recipe with its custom HTML card: the escape hatch must keep working exactly as before. */
    private val legacyRoom get() = JSONObject(File("src/test/resources/applets/room-change-watcher-1.0.0-html.json").readText())

    private fun bad(d: JSONObject, contains: String) {
        try { AppletPackage.check(d); fail("expected refusal: $contains") }
        catch (e: AppletPackage.Bad) { assertTrue("${e.message} should mention $contains", e.message!!.contains(contains)) }
    }

    @Test fun everyRecipePackageIsValid() {
        for (id in listOf("pet-monitor", "receipt-scanner", "voice-notebook", "room-change-watcher", "daily-briefing-panel", "external-device-dashboard", "situation-counter")) {
            val p = AppletPackage.parse(file(id))
            assertEquals(id, p.id)
            assertEquals(AppletPackage.ADAPTER, AppletPackage.adapterFamily(p.adapter))
            val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
            assertTrue(id, plan.card != null)
        }
    }

    @Test fun portableAdapterPreservesValidationAndCompilation() {
        val d = room
        d.getJSONObject("runtime").put("adapter", AppletPackage.PORTABLE_ADAPTER)
        val p = AppletPackage.check(d)
        assertEquals(AppletPackage.PORTABLE_ADAPTER, p.adapter)
        assertTrue(AppletPackage.compile(p, AppletPackage.fill(p, null)).card != null)
        d.getJSONObject("processing").put("made_up", JSONObject())
        bad(d, "isn't a part")
    }

    @Test fun engineRequestsByName() {
        val d = room
        d.getJSONObject("runtime").put("engines", JSONArray(listOf("on_device_detector", "on_device_outline", "on_device_someday")))
        d.getJSONObject("permissions").getJSONArray("required").put("find_crop")
        val p = AppletPackage.check(d)
        assertEquals(listOf("on_device_detector", "on_device_outline", "on_device_someday"), p.engines)
        // What the phone's software manifest knows decides the words; an engine it can't run is said plainly.
        val info = mapOf("on_device_detector" to AppletPackage.EngineInfo("detector", "florence-2-base-ft-int8", 275, "7-16 s per photo on a mid-range phone"),
            "on_device_outline" to AppletPackage.EngineInfo("outline", "mobilesam", 45, "3-4 s per photo"))
        val words = AppletPackage.engineWords(p) { info[it] }
        assertEquals(3, words.size)
        assertTrue(words[0], words[0].contains("any-word detector") && words[0].contains("275 MB download on Wi-Fi") && words[0].contains("free and offline (7-16 s per photo)"))
        assertTrue(words[1], words[1].contains("outline maker") && words[1].contains("45 MB"))
        assertTrue(words[2], words[2].contains("on_device_someday") && words[2].contains("can't run"))
        // Malformed names, and engines without the find_crop permission, are refused.
        bad(JSONObject(room.toString()).also { it.getJSONObject("runtime").put("engines", JSONArray(listOf("Bad Name"))) }, "runtime.engines")
        bad(JSONObject(room.toString()).also { it.getJSONObject("runtime").put("engines", JSONArray(listOf("on_device_detector"))) }, "find_crop")
        assertEquals(emptyList<String>(), AppletPackage.check(room).engines)
    }

    @Test fun petWatchAcceptsTheEntirePublishedSamplingRange() {
        val pkg = AppletPackage.parse(file("pet-monitor"))
        for (seconds in listOf(30, 90, 91, 3600)) {
            val config = AppletPackage.fill(pkg, JSONObject().put("interval_s", seconds))
            val watch = AppletPackage.compile(pkg, config).watches.single().second
            val spec = cc.bridgit.app.watch.WatchRules.parse(watch)
            assertEquals(seconds, spec.intervalS)
            assertEquals(3 * seconds, spec.tally!!.gapS)
        }
    }

    @Test fun situationCounterCompilesToAWatchTheRulesAccept() {
        val p = AppletPackage.parse(file("situation-counter"))
        val w = AppletPackage.compile(p, AppletPackage.fill(p, null)).watches.single().second
        assertFalse(w.toString().contains("\$input"))
        assertFalse(w.has("model"))
        val spec = cc.bridgit.app.watch.WatchRules.parse(w)
        assertEquals("pot_boiling_over", spec.situation)
        assertEquals("clef", spec.model)
        assertEquals(20, spec.intervalS)
        assertEquals(listOf("camera"), spec.data)
        assertEquals("rear", spec.camera)
        assertEquals(30, spec.maxPaidPerHour)
        assertEquals("pot_boiling_over", spec.tally!!.track)
        // Own words count under the track id "situation"; the fastest allowed interval still parses.
        val own = AppletPackage.compile(p, AppletPackage.fill(p, JSONObject().put("situation", "the cat is sleeping on the armchair")
            .put("camera", "front").put("interval_s", 10))).watches.single().second
        val ownSpec = cc.bridgit.app.watch.WatchRules.parse(own)
        assertEquals("situation", ownSpec.situation)
        assertEquals("situation", ownSpec.tally!!.track)
        assertEquals(10, ownSpec.intervalS)
        assertEquals("front", ownSpec.camera)
        for (id in cc.bridgit.app.watch.Situations.LIBRARY.map { it.id })
            assertEquals(id, cc.bridgit.app.watch.WatchRules.parse(AppletPackage.compile(p, AppletPackage.fill(p, JSONObject().put("situation", id))).watches.single().second).situation)
        // The review says it is a paid Clef watch, not a free picture comparison, and names the camera.
        val lines = AppletPackage.describe(AppletPackage.compile(p, AppletPackage.fill(p, null)))
        assertTrue(lines.joinToString(), lines.any { it.contains("Pot boiling over checks the rear camera every 20 seconds") })
        assertTrue(lines.joinToString(), lines.any { it.contains("up to 30 photos an hour off this device to Clef") && it.contains("reuse the last answer") })
        assertTrue(lines.joinToString(), lines.any { it.contains("keeps a count of each time it sees Pot boiling over") })
        assertTrue(lines.joinToString(), lines.any { it.contains("If pot_boiling_over is seen 1 time within 2 minutes, it shows you a notice") })
        assertTrue(lines.none { it.contains("no paid model calls") })
        assertTrue(p.declared.containsAll(listOf("watches", "camera", "interactions")))
    }

    /** Only a do's flow (or a {"$flow"} value) is a flow ref: a watch question with the id "flow" is just a question. */
    @Test fun aFlowKeyOutsideADoIsData() {
        val d = room
        d.getJSONObject("processing").getJSONArray("watches").getJSONObject(0).getJSONObject("request")
            .put("questions", JSONObject("""{"flow":{"type":"choice","instructions":"Is water flowing?","criteria":{"yes":"y","no":"n"}}}"""))
        AppletPackage.check(d)
        // A {"$flow"} outside a do is still refused.
        d.getJSONObject("processing").getJSONArray("watches").getJSONObject(0).getJSONObject("request").put("note", JSONObject().put("flow", JSONObject().put("\$flow", "nope")))
        bad(d, "isn't a flow")
    }

    @Test fun fillUsesDefaultsThenPreviousThenGiven() {
        val p = AppletPackage.check(room)
        assertEquals(30, AppletPackage.fill(p, null).getInt("interval_s"))
        assertEquals(120, AppletPackage.fill(p, null, JSONObject().put("interval_s", 120)).getInt("interval_s"))
        assertEquals(60, AppletPackage.fill(p, JSONObject().put("interval_s", 60), JSONObject().put("interval_s", 120)).getInt("interval_s"))
    }

    @Test fun fillRefusesValuesOutsideTheBlanks() {
        val p = AppletPackage.check(room)
        for (given in listOf(JSONObject().put("interval_s", 5), JSONObject().put("camera", "side"), JSONObject().put("interval_s", "30"), JSONObject().put("extra", 1))) {
            try { AppletPackage.fill(p, given); fail("accepted $given") } catch (e: AppletPackage.Bad) { /* expected */ }
        }
        // An update whose new revision no longer accepts the old value is refused, not silently reset.
        try { AppletPackage.fill(p, null, JSONObject().put("sensitivity", "extreme")); fail("kept an invalid value") } catch (e: AppletPackage.Bad) { /* expected */ }
    }

    @Test fun compileSubstitutesWholeValuesOnly() {
        val p = AppletPackage.check(legacyRoom)
        val plan = AppletPackage.compile(p, AppletPackage.fill(p, JSONObject().put("camera", "front").put("interval_s", 90).put("sensitivity", "low")))
        val w = plan.watches.single().second
        assertEquals("front", w.getString("camera"))
        assertEquals(90, w.getInt("interval_s"))
        assertEquals("low", w.getJSONObject("local").getString("sensitivity"))
        assertFalse(w.toString().contains("\$input"))
        // The card gets the real watch id only once the watch exists; "<" in data can't close the script.
        val card = plan.card!!
        val html = card.copy(data = JSONObject(card.data.toString()).put("title", "</script><b>")).html(mapOf("main" to "w0123456789ab"))
        assertTrue(html.contains("\"resource_id\":\"w0123456789ab\""))
        assertFalse(html.contains("</script><b>"))
        assertTrue(html.contains("\\u003c"))
    }

    @Test fun refusesWhatIsntDeclarativeOrSafe() {
        bad(legacyRoom.put("format", "other"), "format")
        bad(JSONObject(legacyRoom.toString()).apply { getJSONObject("runtime").put("supports_dynamic_execution", true) }, "declarative")
        bad(JSONObject(legacyRoom.toString()).apply { getJSONObject("permissions").put("automatic_grants", true) }, "approves every grant")
        bad(JSONObject(legacyRoom.toString()).apply { getJSONObject("processing").put("script", "run()") }, "processing.script")
        bad(JSONObject(legacyRoom.toString()).apply { getJSONObject("processing").getJSONArray("watches").getJSONObject(0).getJSONObject("request").put("camera", JSONObject().put("\$input", "cam")) }, "name a blank")
        bad(JSONObject(legacyRoom.toString()).apply { getJSONObject("processing").getJSONArray("watches").getJSONObject(0).getJSONObject("request").put("label", JSONObject().put("\$input", "camera").put("x", 1)) }, "alone")
        bad(JSONObject(legacyRoom.toString()).apply { getJSONObject("assets").getJSONObject("card.html").put("text", "<p>changed</p>") }, "sha256")
        bad(JSONObject(legacyRoom.toString()).apply { getJSONObject("defaults").put("interval_s", 1) }, "defaults")
        bad(JSONObject(legacyRoom.toString()).apply { getJSONObject("config_schema").put("pattern", ".*") }, "unsupported schema keyword")
        bad(JSONObject(legacyRoom.toString()).apply { getJSONObject("ui").getJSONObject("card").getJSONObject("data").put("resource_id", JSONObject().put("\$watch", "other")) }, "name a watch")
        bad(JSONObject(legacyRoom.toString()).apply { put("processing", JSONObject()); getJSONObject("ui").remove("card") }, "at least one part")
    }

    @Test fun derivedPermissionsComeFromTheParts() {
        val p = AppletPackage.check(room)
        val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
        val derived = AppletPackage.derived(plan, { data, _ -> if ("camera" in data) setOf("camera") else emptySet() }, { null }) { emptySet() }
        assertEquals(setOf("watches", "camera", "interactions"), derived)
        assertTrue(AppletPackage.undeclared(p, derived).isEmpty())
        // A package that hides its camera use is refused.
        val hidden = AppletPackage.check(JSONObject(room.toString()).apply { getJSONObject("permissions").put("required", JSONArray(listOf("watches", "interactions"))) })
        assertEquals(setOf("camera"), AppletPackage.undeclared(hidden, derived))
        assertEquals(setOf("camera"), AppletPackage.added(setOf("watches", "interactions"), setOf("watches", "interactions", "camera")))
        assertTrue(AppletPackage.added(setOf("watches", "camera", "interactions"), setOf("watches", "interactions")).isEmpty())
    }

    @Test fun reviewSaysHowOftenAndWhatItCosts() {
        val room = AppletPackage.check(room)
        val lines = AppletPackage.describe(AppletPackage.compile(room, AppletPackage.fill(room, JSONObject().put("interval_s", 60))))
        assertTrue(lines.joinToString(), lines.any { it.contains("rear camera every minute") })
        assertTrue(lines.any { it.contains("no paid model calls") })
        val pet = AppletPackage.parse(file("pet-monitor"))
        val petLines = AppletPackage.describe(AppletPackage.compile(pet, AppletPackage.fill(pet, null)))
        assertTrue(petLines.joinToString(), petLines.any { it.contains("up to 10 photos an hour") && it.contains("$0.76 a month") })
        val brief = AppletPackage.parse(file("daily-briefing-panel"))
        assertTrue(AppletPackage.describe(AppletPackage.compile(brief, AppletPackage.fill(brief, JSONObject().put("time", "07:30")))).any { it.contains("every day at 07:30") })
    }

    @Test fun importLinksOnlyReachBridgit() {
        val l = AppletPackage.link("bridgit://install?src=%2Frecipes%2Froom-change-watcher%2Fapplet.json&sha256=" + "a".repeat(64))!!
        assertEquals("https://bridgit.cc/recipes/room-change-watcher/applet.json", l.url)
        assertEquals("a".repeat(64), l.sha256)
        assertEquals("https://bridgit.cc/recipes/x/applet.json", AppletPackage.link("https://bridgit.cc/install/?src=/recipes/x/applet.json")!!.url)
        val t = AppletPackage.link("bridgit://install?template=tpl_abc&revision=3")!!
        assertEquals("https://${AppletPackage.REGISTRY_HOST}/v1/templates/tpl_abc/3", t.url)
        assertNull(AppletPackage.link("bridgit://install?src=https%3A%2F%2Fevil.example%2Fa.json"))
        assertNull(AppletPackage.link("bridgit://install?src=http%3A%2F%2Fbridgit.cc%2Fa.json"))
        assertNull(AppletPackage.link("bridgit://install?src=https%3A%2F%2Fbridgit.cc%3A8443%2Fa.json"))
        assertNull(AppletPackage.link("bridgit://install?src=https%3A%2F%2Fbridgit.cc.evil.example%2Fa.json"))
        assertNull(AppletPackage.link("bridgit://install?template=..%2Fx&revision=1"))
        assertNull(AppletPackage.link("https://example.com/install/?src=/recipes/x/applet.json"))
    }

    @Test fun registryReadsUnwrap() {
        val wrapped = JSONObject().put("key", "tpl_x").put("revision", 1).put("definition", room).toString()
        assertEquals("room-change-watcher", AppletPackage.parse(AppletPackage.unwrap(wrapped)).id)
        assertEquals("room-change-watcher", AppletPackage.parse(AppletPackage.unwrap(file("room-change-watcher"))).id)
    }

    @Test fun petMonitorTemplateInstallsAsTheBuiltInMonitor() {
        // The template itself lives in the private backend repo. This fixture keeps only the install
        // contract the phone checks (format, runtime, settings, permissions), with no prompts or prose.
        val d = JSONObject(File("src/test/resources/applets/dog-monitor-1.3.0.json").readText())
        val p = AppletPackage.check(d)
        assertEquals(AppletPackage.DOG_ADAPTER, p.adapter)
        val plan = AppletPackage.compile(p, AppletPackage.fill(p, JSONObject().put("camera", "front")))
        assertEquals("front", plan.native!!.getString("camera"))
        assertTrue(plan.watches.isEmpty())
        assertEquals(setOf("camera"), AppletPackage.derived(plan, { _, _ -> emptySet() }, { null }) { emptySet() })
    }

    // ---- phase 2: pieces cards, tables, validate filling in -----------------------------------------------------

    private val recipes = listOf("pet-monitor", "receipt-scanner", "voice-notebook", "room-change-watcher", "daily-briefing-panel", "external-device-dashboard", "situation-counter")
    private val allCaps: (String) -> String? = { c -> when {
        c.contains("/weather") -> "weather"; c.contains("/lan/") -> "lan_control"; c.contains("/ocr") -> "ocr"; c.contains("/mic/") -> "mic"; c.contains("/watches") -> "watches"; else -> null } }

    @Test fun recipeCardsArePiecesWithTheirWatchFilledInAtInstall() {
        val p = AppletPackage.parse(file("pet-monitor"))
        val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
        val card = plan.card!!
        assertTrue(card.isPieces)
        val filled = card.pieces(mapOf("main" to "w0123456789ab"))
        assertEquals("w0123456789ab", filled.getJSONObject("sources").getJSONObject("activity").getString("watch"))
        assertFalse(filled.toString().contains("\$watch"))
        assertTrue(AppletPackage.describe(plan).any { it.startsWith("Page · Pet activity monitor: Shows Pet activity monitor as its own page") })
    }

    @Test fun piecesAndCustomHtmlDontMix() {
        val d = JSONObject(file("pet-monitor"))
        d.getJSONObject("ui").getJSONObject("card").put("asset", "card.html")
        bad(d, "either pieces or custom HTML")
    }

    @Test fun tablesAndRecordStepsMustMatch() {
        val d = JSONObject(file("voice-notebook"))
        assertTrue(AppletPackage.compile(AppletPackage.check(d), AppletPackage.fill(AppletPackage.check(d), null)).tables.any { it.id == "notes" })
        val wf = JSONObject().put("name", "Save").put("trigger", JSONObject().put("type", "button"))
            .put("steps", JSONArray().put(JSONObject().put("type", "record").put("table", "receipts").put("row", JSONObject().put("text", "x"))))
        bad(JSONObject(d.toString()).apply { getJSONObject("processing").put("workflow", wf) }, "isn't in processing.tables")
        val wf2 = JSONObject(wf.toString()).apply { getJSONArray("steps").getJSONObject(0).put("table", "notes").put("row", JSONObject().put("total", 1)) }
        bad(JSONObject(d.toString()).apply { getJSONObject("processing").put("workflow", wf2) }, "isn't a column")
        // A card button can only write a declared table's columns.
        val badCard = JSONObject(d.toString())
        badCard.getJSONObject("ui").getJSONObject("card").getJSONArray("pieces").put(JSONObject().put("type", "buttons").put("items", JSONArray()
            .put(JSONObject().put("label", "X").put("do", JSONObject().put("record", "nope").put("row", JSONObject().put("text", "x"))))))
        bad(badCard, "processing.tables")
    }

    @Test fun validateFillsInTheRegistrySectionsForEveryRecipe() {
        for (id in recipes) {
            val (pre, needs) = AppletPackage.prefill(JSONObject(file(id)))
            assertFalse(id, needs) // the recipes still list their permissions
            val p = AppletPackage.check(pre)
            val supplied = JSONObject(file(id))
            for (k in AppletPackage.REGISTRY_SECTIONS) assertEquals("$id: $k", supplied.has(k), p.definition.has(k))
            val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
            val derived = AppletPackage.derived(plan, { _, _ -> setOf("camera") }, allCaps) { setOf("workflows") }
            val full = AppletPackage.completed(p.definition, plan, derived, false)
            for (k in AppletPackage.REGISTRY_SECTIONS) assertTrue("$id: $k", full.has(k))
            for (k in AppletPackage.REGISTRY_SECTIONS.filter(supplied::has))
                assertEquals("$id retains $k", supplied.get(k).toString(), full.get(k).toString())
            AppletPackage.check(full)
            // The data policy is worked out from the parts: Pet monitor asks Clef, room changes asks nobody.
            val remote = full.getJSONObject("data_policy").getJSONObject("device_only").getBoolean("remote_models")
            if (id == "pet-monitor" || id == "situation-counter") assertTrue(id, remote)
            if (id == "room-change-watcher" || id == "daily-briefing-panel") assertFalse(id, remote)
        }
        // Written sections are kept; a missing permissions.required is filled with the derived ones.
        val d = JSONObject(file("room-change-watcher")).put("metrics", JSONArray().put("mine"))
        d.getJSONObject("permissions").remove("required")
        val (pre, needs) = AppletPackage.prefill(d)
        assertTrue(needs)
        val p = AppletPackage.check(pre)
        val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
        val full = AppletPackage.completed(p.definition, plan, setOf("camera", "interactions", "watches"), needs)
        assertEquals("mine", full.getJSONArray("metrics").getString(0))
        assertEquals(listOf("camera", "interactions", "watches"), AppletPackage.strings(full.getJSONObject("permissions").getJSONArray("required")))
    }

    @Test fun legacyHtmlRecipeStillInstallsUnchanged() {
        val p = AppletPackage.check(legacyRoom)
        val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
        assertFalse(plan.card!!.isPieces)
        assertTrue(plan.card!!.html(mapOf("main" to "w1")).contains("\"resource_id\":\"w1\""))
        assertTrue(AppletPackage.describe(plan).any { it.contains("Open in Installed applets shows it") })
    }
}
