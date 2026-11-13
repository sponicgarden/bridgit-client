package cc.bridgit.app.clientcore

import cc.bridgit.app.api.Address
import cc.bridgit.app.api.DeviceIdentity
import cc.bridgit.app.api.SetupPrompt
import cc.bridgit.app.api.CapInfo
import cc.bridgit.app.core.Connection
import cc.bridgit.app.core.contract.ContractVocab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The setup prompt and the Interface Guide as whole texts (contracts/client-core/setup-prompt/), for a named and an
 * unnamed device, direct and relay, with invented keys and addresses. Apple renders the same texts in phase 2.
 * After an intended change: ./gradlew :app:testPrivateDebugUnitTest --tests '*SetupPromptFixturesTest*' -PbridgitLearnRefresh=1,
 * then node tools/client-core/gen.mjs.
 */
class SetupPromptFixturesTest {
    private val key = "brg_fixtureNotARealKey0000"
    private val addr = Address("fixture-tablet.example.invalid", "100.64.0.9", 8734)
    private val named = DeviceIdentity("bright-otter", "Kitchen tablet", "Fixture Tab", "o***@example.invalid (verified)")
    private val unnamed = DeviceIdentity(null, "Kitchen tablet", "Fixture Tab", null)
    private val caps = listOf(CapInfo("camera", "Camera", "camera", true, true, null), CapInfo("gallery", "Gallery", "gallery", true, true, null))

    private fun cases(): Map<String, String> = mapOf(
        "short-named.txt" to SetupPrompt.short("claude-fixture", key, addr, named),
        "short-unnamed.txt" to SetupPrompt.short("claude-fixture", key, addr, unnamed),
        "briefing-direct-named.txt" to SetupPrompt.build("claude-fixture", SetupPrompt.KEY_ON_FILE, Connection.DIRECT, addr, "Fixture Tab", caps, null, device = named),
        "briefing-direct-unnamed.txt" to SetupPrompt.build("claude-fixture", SetupPrompt.KEY_ON_FILE, Connection.DIRECT, addr, "Fixture Tab", caps, null, device = unnamed),
        "briefing-relay-named.txt" to SetupPrompt.build("claude-fixture", SetupPrompt.KEY_ON_FILE, Connection.RELAY, addr, "Fixture Tab", caps, null, device = named),
    ).mapValues { it.value.trimEnd() + "\n" }

    @Test fun promptsMatchTheContractTexts() {
        val pack = listOf(File("../contracts/client-core/setup-prompt"), File("contracts/client-core/setup-prompt")).firstOrNull { it.parentFile.isDirectory }
        val refresh = System.getProperty("bridgit.learn.refresh") == "1"
        for ((name, text) in cases()) {
            if (refresh && pack != null) { pack.mkdirs(); File(pack, name).writeText(text); continue }
            val want = javaClass.getResource("/client-core/setup-prompt/$name")?.readText()
                ?: error("No fixture $name: run with -PbridgitLearnRefresh=1, then node tools/client-core/gen.mjs")
            assertEquals(name, want, text)
        }
    }

    @Test fun briefingKeepsTheContractStructure() {
        val text = cases().getValue("briefing-direct-named.txt")
        val at = ContractVocab.SETUP_PROMPT_SECTIONS.map { text.indexOf("\n$it\n").takeIf { i -> i >= 0 } ?: text.indexOf("$it\n") }
        assertTrue("every section present: $at", at.all { it >= 0 })
        assertEquals("sections in contract order", at.sorted(), at)
        val identity = text.substringBefore("\n\n").lines().drop(1).map { it.removePrefix("- ").substringBefore(":") }
        assertEquals(ContractVocab.SETUP_PROMPT_IDENTITY, identity)
        val calls = ContractVocab.SETUP_PROMPT_FIRST_CALLS.map { text.indexOf(it) }
        assertTrue("every first call present: $calls", calls.all { it >= 0 })
        assertEquals("first calls in contract order", calls.sorted(), calls)
        for (t in cases().values) assertTrue("no real-looking key", !Regex("brg_[A-Za-z0-9]{30,}").containsMatchIn(t))
    }
}
