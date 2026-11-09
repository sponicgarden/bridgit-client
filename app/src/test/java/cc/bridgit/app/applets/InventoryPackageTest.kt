package cc.bridgit.app.applets

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The Insurance inventory applet in applets/insurance-inventory validates and reads well on the install review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InventoryPackageTest {
    private val p get() = AppletPackage.check(JSONObject(File("../applets/insurance-inventory/applet.json").readText()))

    @Test fun inventoryPackageValidates() { p }

    @Test fun currentInventoryKeepsMaintainedFeaturesAndOnlyCsvSharing() {
        assertEquals("3.0.3", p.version)
        val definition = p.definition
        val ui = definition.getJSONObject("ui")
        assertEquals(listOf("camera", "create", "csv"), (0 until ui.getJSONObject("portal").getJSONArray("actions").length()).map { ui.getJSONObject("portal").getJSONArray("actions").getString(it) })
        val text = definition.toString()
        assertTrue(!text.contains("Share PDF"))
        assertTrue(text.contains("Share CSV") && text.contains("appraise"))
        assertEquals(setOf("photos", "items"), AppletTables.parse(definition.getJSONObject("processing")).map { it.id }.toSet())
    }

    /** Owner, 5 Nov 2026: it showed "Every 10 minutes" under Schedule, but it runs only when used. */
    @Test fun inventoryIsUserDrivenWithNoScheduledActions() {
        val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
        assertTrue(AppletActions.userDriven(plan))
        assertEquals(emptyList<AppletActions.Action>(), AppletActions.of(p.definition, plan))
    }

    @Test fun portalIncludeIsTheMakersChoice() {
        fun pkg(portal: String) = JSONObject(File("../applets/insurance-inventory/applet.json").readText()).also {
            it.getJSONObject("ui").put("portal", JSONObject(portal))
            it.getJSONObject("processing").getJSONArray("tables").getJSONObject(0).getJSONObject("columns")
                .put("payload", JSONObject("""{"type":"json","label":"Observation","description":"Inventory observation"}"""))
        }
        // Optional (the default): the review says the owner turns it on.
        val opt = AppletPackage.check(pkg("""{"records":{"kind":"inventory","title":"Inventory","table":"photos","column":"payload"}}"""))
        assertTrue(AppletPackage.portalWords(opt.definition)!!.startsWith("Web portal: off until you turn it on"))
        // Always: part of the applet, and the review says so with what it sends.
        val always = AppletPackage.check(pkg("""{"include":"always","records":{"kind":"inventory","title":"Inventory","table":"photos","column":"payload"}}"""))
        val words = AppletPackage.portalWords(always.definition)!!
        assertTrue(words, words.startsWith("Web portal: comes with this applet") && words.contains("Removing the applet stops it"))
        assertTrue(cc.bridgit.app.remoteview.PortalRecords.always(always.definition))
        // The package itself (version 3): its portal is its screen, part of the applet.
        val own = AppletPackage.portalWords(p.definition)!!
        assertTrue(own, own.startsWith("Web portal: comes with this applet and is its screen") && own.contains("photos and items"))
        // Nothing to send: no portal line, and "always" is refused.
        assertEquals(null, AppletPackage.portalWords(pkg("""{"include":"optional"}""").let { AppletPackage.check(it) }.definition))
        for ((bad, part) in listOf("""{"include":"always"}""" to "sends nothing to a portal", """{"include":"sometimes"}""" to "ui.portal.include must be")) {
            try { AppletPackage.check(pkg(bad)); fail(part) } catch (e: AppletPackage.Bad) { assertTrue("${e.message} / $part", e.message!!.contains(part)) }
        }
    }

    @Test fun reviewSaysTheButtonsAreOnThePortal() {
        val page = AppletPackage.parts(AppletPackage.compile(p, AppletPackage.fill(p, null))).single { it.title.startsWith("Page · ") }
        assertTrue(page.toString(), page.lines.single().startsWith("Its screen is the applet's web portal"))
    }

    @Test fun reviewSaysTheCreateButtonStartsTheReadFlow() {
        val lines = AppletPackage.describe(AppletPackage.compile(p, AppletPackage.fill(p, null)))
        val flow = lines.single { it.startsWith("Workflow · Read one photo: ") }
        assertTrue(flow, flow.contains("It runs when you tap Create inventory, once for each entry in Photos."))
        // Design pass: labels, not ids, and the paid call said in words.
        assertTrue(flow, flow.contains("Saves a row in Items for each item (at most 50).") && flow.contains("off this device to Jev, a paid model"))
        assertTrue(lines.joinToString("\n"), lines.none { it.contains("\"items\"") || it.contains("\"photos\"") || it.contains("3650") })
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("3,650 days (about 10 years), at most 1,000 rows") })
        assertTrue(lines.joinToString("\n"), lines.none { it.contains("Nothing in the applet starts it") })
    }
}
