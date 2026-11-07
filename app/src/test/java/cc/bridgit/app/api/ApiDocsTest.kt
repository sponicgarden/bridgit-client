package cc.bridgit.app.api

import cc.bridgit.app.ui.more.WhatCanData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The public API page: every card link and call resolves. Also writes build/apidocs/api.html for publish-apk.sh. */
class ApiDocsTest {
    private val titles = WhatCanData.all.map { it.title }

    @Test fun everyMappedCardAndCallExists() {
        for ((title, calls) in WhatCanData.endpoints) {
            assertTrue("No card titled \"$title\"", title in titles)
            calls.forEach { assertNotNull("$title: no endpoint $it", ApiDocs.find(it)) }
        }
    }

    @Test fun everyBuiltAbilityHasCalls() {
        val missing = WhatCanData.all.filter { it.stage == WhatCanData.Stage.NOW && it.kind == WhatCanData.Kind.ABILITY }
            .map { it.title }.filter { WhatCanData.endpoints[it].isNullOrEmpty() }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun anchorsAreUniqueAndStable() {
        val slugs = titles.map { ApiDocs.slug(it) }
        assertEquals(slugs.size, slugs.toSet().size)
        assertEquals("take-a-photo", ApiDocs.slug("Take a photo"))
        for ((old, renamed) in listOf(
            "Add an agent in a minute" to "Add a control agent in a minute",
            "Agents learn what's new" to "Control agents learn what's new",
            "Share to an agent" to "Share to a control agent",
            "Per-agent permissions" to "Control agent permissions"
        )) assertEquals(ApiDocs.slug(old), ApiDocs.slug(renamed))
        assertEquals("agents-learn-what-s-new", ApiDocs.slug("Agents learn what's new"))
        assertEquals("${ApiDocs.URL}#city-tour", ApiDocs.link("City tour"))
    }

    @Test fun pageHasEveryCardAndEndpointAndNoSecrets() {
        val html = ApiDocs.html(WhatCanData.apiCards(), "test")
        titles.forEach { assertTrue(it, html.contains("id=\"${ApiDocs.slug(it)}\"")) }
        Endpoints.all.forEach { assertTrue(it.fullPath, html.contains(ApiDocs.endpointKey(it))) }
        assertFalse(html.contains("brg_"))
        assertFalse(Regex("""100\.\d+\.\d+\.\d+""").containsMatchIn(html))
        val out = File("build/apidocs/api.html")
        out.parentFile.mkdirs()
        out.writeText(ApiDocs.html(WhatCanData.apiCards(), System.getProperty("bridgit.version") ?: "dev"))
    }
    @Test fun appletGuideIsTaskFirstAndComesFromOneCompactSource() {
        val fragment = AppletBuildGuide.HTML
        assertEquals(File("../site-content/content/builder/applet-guide.html").readText(), fragment)
        val html = ApiDocs.html(emptyList(), "test")
        assertTrue(html.contains("id=\"build-an-applet\""))
        assertTrue(html.contains("href=\"${ApiDocs.APPLET_URL}\""))
        assertEquals("https://bridgit.cc/recipes/build-an-applet/", ApiDocs.APPLET_URL)
        assertTrue(fragment.indexOf("learn/start.md") < fragment.indexOf("Optional: add native integration"))
        assertTrue(fragment.indexOf("Choose the parts") < fragment.indexOf("Optional: add native integration"))
        // Package-first (5 Nov 2026): the page teaches packages and card pieces; HTML is the escape hatch.
        for (text in listOf("catalog-index.json", "applet-packages.md", "/v1/applets/validate", "/v1/applets/install", "pieces-status",
            "need no native build", "controller is offline", "awaiting owner approval is not active", "paid-call cap",
            "reused result is not new model evidence", "Listing an enrolled device does not probe it", "html-connection-test",
            "Bridgit.act", "Stopping does not erase every record", "applet.installed")) {
            assertTrue("Missing applet teaching contract: $text", fragment.contains(text, ignoreCase = true))
        }
        assertTrue(fragment.indexOf("Show it with card pieces") < fragment.indexOf("Custom HTML is the escape hatch"))
        assertFalse("internal release process doesn't belong in an agent page", fragment.contains("allocate-apk-version"))
        assertTrue(cc.bridgit.app.watch.WatchRules.parse(org.json.JSONObject("""{"question":"Is the room bright?","model":"jev"}""")).model == "jev")
        assertFalse(fragment.contains("a timer you keep"))
        assertFalse(fragment.contains("every build is numbered from git"))
        assertFalse(fragment.contains("nothing is kept afterwards except log lines"))
        val site = File("../site-content/public/recipes/build-an-applet/index.html").readText()
        assertTrue(site.contains(fragment))
        assertTrue("Compact builder page must stay below 20 KiB", site.toByteArray().size < 20 * 1024)
    }

    @Test fun appletInstructionsUseNativeScreenAndUsefulHandoff() {
        val ui = File("src/main/java/cc/bridgit/app/ui/applets/AppletConfigFragment.kt").readText()
        assertTrue(ui.contains("BuildInstructionsActivity::class.java"))
        assertFalse(ui.contains("Intent.ACTION_VIEW"))
        val native = File("src/main/java/cc/bridgit/app/ui/applets/BuildInstructionsActivity.kt").readText()
        assertTrue(native.contains("getString(R.string.applets_build_share_text, ApiDocs.APPLET_URL)"))
        assertTrue(native.contains("ClipData.newPlainText"))
        assertTrue(native.contains("putExtra(Intent.EXTRA_TEXT, prompt)"))
        assertTrue(native.contains("\"applet.create_requested\""))
        val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings_tour.xml"))
        val nodes = xml.getElementsByTagName("string")
        val strings = (0 until nodes.length).associate { nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent }
        val share = strings.getValue("applets_build_share_text")
        assertEquals(1, Regex("%1\\${'$'}s").findAll(share).count())
        for (part in listOf("Ask what I want", "learn/start.md", "catalog-index.json", "phone-resident watches", "source freshness", "pause/stop", "retained data"))
            assertTrue("Share prompt lacks $part", share.contains(part, ignoreCase = true))
    }

    @Test fun linkedRichTemplatesAreCompleteAndSandboxValid() {
        for (id in listOf("room-change-watcher", "receipt-scanner")) {
            val request = org.json.JSONObject(File("../site-content/public/recipes/$id/request.json").readText())
            assertEquals("html", request.getString("kind"))
            val html = request.getString("html")
            assertEquals(html, cc.bridgit.app.interact.CardRules.checkHtml(html))
            assertTrue(html.contains("bridgit:answered"))
            assertTrue(html.contains("body[data-state=error]"))
            assertTrue(html.contains("class=\"panel\""))
        }
    }

}
