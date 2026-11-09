package cc.bridgit.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * A0093: test builds share a phone with the real app, and Android lists each by name (launcher, Apps,
 * Accessibility, Notification access, share sheet, Quick Settings). The real app keeps its plain names;
 * debug and test builds carry "(<tag>)" on every one, so the owner can't grant access to the wrong twin.
 *
 * Runs per variant: `:app:testPrivateDebugUnitTest` checks the tagged names, and
 * `:app:testPrivateReleaseUnitTest --tests '*BuildLabelTest*'` checks the release names stay exact.
 */
class BuildLabelTest {
    private val production = mapOf(
        "app_name" to "Bridgit",
        "tile_label" to "Bridgit",
        "screenshot_service_label" to "Bridgit screenshots",
        "device_control_service_label" to "Bridgit private app control",
        "notification_access_label" to "Bridgit notification access",
        "share_to_bridgit_label" to "Share to Bridgit",
        "share_to_agent_label" to "Share to a control agent",
    )

    private fun strings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    @Test fun releaseNamesAreThePlainProductNames() {
        val main = File("src/main/res/values").listFiles { f -> f.extension == "xml" }!!.flatMap { strings(it).entries }
            .associate { it.key to it.value }
        production.forEach { (name, text) -> assertEquals("main res $name", text, main[name]) }
        // The Gradle map that tags debug builds must name the same strings with the same release text.
        val gradle = File("build.gradle.kts").readText().substringAfter("fun systemLabels()").substringBefore(")\n\n")
        val listed = Regex(""""(\w+)" to "([^"]*)"""").findAll(gradle).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(production, listed)
        // Every manifest label Android lists must come from these strings, not fixed text.
        listOf("src/main/AndroidManifest.xml", "src/private/AndroidManifest.xml").forEach { path ->
            Regex("""android:label="([^"]*Bridgit[^"]*|Share to[^"]*)"""").find(File(path).readText())?.let {
                throw AssertionError("$path has fixed label ${it.value}; use a systemLabels string")
            }
        }
    }

    @Test fun thisVariantShowsItsTagOrThePlainName() {
        val variant = BuildConfig.FLAVOR.replaceFirstChar { it.uppercase() } + BuildConfig.BUILD_TYPE.replaceFirstChar { it.uppercase() }
        val merged = File("build/intermediates").walkTopDown()
            .filter { it.name == "values.xml" && it.path.contains("merge${variant}Resources") && it.parentFile.name == "values" }
            .firstOrNull() ?: throw AssertionError("no merged resources for $variant under build/intermediates")
        val values = strings(merged)
        if (BuildConfig.DEBUG) {
            assertTrue("debug tag", BuildConfig.BUILD_TAG.isNotBlank())
            assertNotEquals("cc.bridgit.app", BuildConfig.APPLICATION_ID)
            production.forEach { (name, text) -> assertEquals("$variant $name", "$text (${BuildConfig.BUILD_TAG})", values[name]) }
        } else {
            assertEquals("", BuildConfig.BUILD_TAG)
            production.forEach { (name, text) -> assertEquals("$variant $name", text, values[name]) }
        }
    }
}
