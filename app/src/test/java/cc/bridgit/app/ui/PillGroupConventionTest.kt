package cc.bridgit.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * A0405 (docs/ui-spec.md "Pill groups"): a pill group holds one type only. Every layout ChipGroup declares
 * its one category with android:tag="pills:<category>". Groups not yet split or decided are listed in
 * [KNOWN_MIXED] with the reason; that list may only shrink.
 */
class PillGroupConventionTest {
    private val layoutDir = listOf("src/main/res/layout", "app/src/main/res/layout").map(::File).first { it.isDirectory }

    private fun groups(): List<Pair<String, Element>> = layoutDir.listFiles { f -> f.extension == "xml" }!!.sorted().flatMap { f ->
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(f)
        val nodes = doc.getElementsByTagName("com.google.android.material.chip.ChipGroup")
        (0 until nodes.length).map { i ->
            val e = nodes.item(i) as Element
            val id = e.getAttributeNS(ANDROID, "id").substringAfter("/").ifEmpty { "#${i + 1}" }
            "${f.nameWithoutExtension}/$id" to e
        }
    }

    @Test fun everyChipGroupDeclaresOneCategory() {
        val untagged = groups().filter { (name, e) -> !e.getAttributeNS(ANDROID, "tag").matches(TAG) && name !in KNOWN_MIXED.keys }
            .map { it.first }
        assertEquals("ChipGroups without android:tag=\"pills:<category>\" (one type per group, ui-spec Pill groups)", emptyList<String>(), untagged)
    }

    @Test fun knownMixedListOnlyNamesRealUntaggedGroups() {
        val byName = groups().toMap()
        for (name in KNOWN_MIXED.keys) {
            val e = byName[name]
            assertTrue("$name is in KNOWN_MIXED but no longer exists: remove it", e != null)
            assertTrue("$name is tagged now: remove it from KNOWN_MIXED", !e!!.getAttributeNS(ANDROID, "tag").matches(TAG))
        }
    }

    @Test fun splitScreensKeepSeparateGroups() {
        val tags = groups().associate { (n, e) -> n to e.getAttributeNS(ANDROID, "tag") }
        assertEquals("pills:kind", tags["fragment_my_issues/kind_filters"])
        assertEquals("pills:status", tags["fragment_my_issues/status_filters"])
        assertEquals("pills:kind", tags["fragment_interactions/chips"])
        assertEquals("pills:status", tags["fragment_interactions/status_chips"])
        assertEquals("pills:filter", tags["fragment_activity/chips"])
        assertEquals("pills:mode", tags["fragment_activity/mode_chips"])
    }

    @Test fun statusPillsNeverShareAKindGroup() {
        // The regression A0405 names: "Open" (a status) sat in the same group as Bugs/Features or Questions/Notices.
        for ((name, e) in groups()) {
            val chips = e.getElementsByTagName("com.google.android.material.chip.Chip")
            val ids = (0 until chips.length).map { (chips.item(it) as Element).getAttributeNS(ANDROID, "id").substringAfter("/") }
            if (ids.any { it.endsWith("_open") }) assertEquals("$name mixes Open with other pills", "pills:status", e.getAttributeNS(ANDROID, "tag"))
        }
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        val TAG = Regex("pills:[a-z][a-z-]*")

        /** Display rows of mixed facts, pending the owner's decisions D5/D6 in docs/plans/a0405-one-type-per-pill-group.md. */
        val KNOWN_MIXED = mapOf(
            "listener_card/heroChips" to "Tailscale + listener state + uptime (D5)",
            "row_log_entry/#1" to "endpoint + HTTP result + size + duration on one log row (D6: display rows)",
            "row_agent_activity/chips" to "per-entry facts from ActivityRows (D6: display rows)",
        )
    }
}
