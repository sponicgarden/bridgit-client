package cc.bridgit.app.planned

import cc.bridgit.app.api.ApiError
import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.api.McpTools
import cc.bridgit.app.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlannedRulesTest {
    @Test fun ocrSampleStartsAtOneAndBoundsDecodedDimensions() {
        assertEquals(1, Rules.imageSampleSize(512, 256))
        assertEquals(1, Rules.imageSampleSize(4096, 4096))
        assertEquals(2, Rules.imageSampleSize(4097, 100))
        assertEquals(4, Rules.imageSampleSize(8193, 100))
        for ((w, h) in listOf(1 to 100_000_000, 10000 to 10000, 8193 to 8193, 100 to 8193)) {
            val sample = Rules.imageSampleSize(w, h)
            assertTrue(sample >= 1 && sample and (sample - 1) == 0)
            assertTrue((w.toLong() + sample - 1) / sample <= 4096)
            assertTrue((h.toLong() + sample - 1) / sample <= 4096)
        }
    }
    @Test fun timedControlKeepsTheLatestOwnerBaseline() {
        assertEquals(1, Rules.leaseBaseline(3, 1, 3)) // unchanged agent value: keep original
        assertEquals(2, Rules.leaseBaseline(2, 1, 3)) // owner picked priority: preserve it
        assertEquals(2, Rules.leaseBaseline(4, 2, 4)) // subsequent agent request preserves priority
        assertEquals(7, Rules.leaseBaseline(7, null, null))
    }
    @Test fun agentUploadsReserveCapacityForOwnerShares() {
        val mb = 1024L * 1024
        assertTrue(Rules.transferFits(50*mb, 50, 50*mb, 50, 25*mb, 25, 25*mb, false))
        assertFalse(Rules.transferFits(25*mb, 1, 25*mb, 1, 25*mb, 1, 1, true))
        assertFalse(Rules.transferFits(50*mb, 2, 50*mb, 2, 0, 0, 1, true))
        assertFalse(Rules.transferFits(0, 25, 0, 25, 0, 25, 0, true))
        assertTrue(Rules.transferFits(0, 0, 0, 0, 0, 0, 25*mb, true))
    }
    @Test fun fileOpenRefusesInstallerPackagesRegardlessOfMime() {
        assertTrue(Rules.installable("update.APK", "text/plain"))
        assertTrue(Rules.installable("photo.jpg", "application/vnd.android.package-archive"))
        assertFalse(Rules.installable("manual.pdf", "application/pdf"))
    }
    @Test fun transcriptionFailurePreservesAudioButCancellationPropagates() = kotlinx.coroutines.runBlocking {
        val failure = transcribeKeepingAudio(true) { throw cc.bridgit.app.api.ErrorCatalog.error("not_supported", "No model") }
        assertNull(failure.text); assertEquals("not_supported", failure.error)
        assertEquals("Hello", transcribeKeepingAudio(true) { "Hello" }.text)
        try { transcribeKeepingAudio(false) { throw IllegalStateException("failed") }; fail() } catch (_: IllegalStateException) { }
        try { transcribeKeepingAudio(true) { throw kotlinx.coroutines.CancellationException("revoked") }; fail() } catch (_: kotlinx.coroutines.CancellationException) { }
        val timeout = transcribeKeepingAudio(true) { kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.delay(100); "late" } }
        assertEquals("capture_timeout", timeout.error)
    }
    private fun denied(block: () -> Unit) { try { block(); fail("Expected validation error") } catch (e: ApiError) { assertEquals("invalid_parameter", e.code) } }
    @Test fun screenConsentErrorIsAnActionableForbidden() { assertEquals(403, cc.bridgit.app.api.ErrorCatalog.http("consent_expired")) }
    @Test fun countsRejectFractionsNonfiniteAndOutOfRange() {
        listOf<Any?>(null, true, 1.5, Double.NaN, Double.POSITIVE_INFINITY, -1, 601, "bad").forEach { denied { Rules.integer(it, "seconds", 1, 600) } }
        assertEquals(60, Rules.integer(null, "seconds", 1, 600, 60)); assertEquals(1, Rules.integer("1", "limit", 1, 100))
    }
    @Test fun booleansAndBrightnessAreStrict() {
        denied { Rules.bool(JSONObject().put("on", "true"), "on", false) }
        denied { Rules.number(Double.NaN, "level", 0.0, 1.0) }
        denied { Rules.number(1.01, "level", 0.0, 1.0) }
        assertTrue(Rules.bool(JSONObject().put("on", true), "on", false))
    }
    @Test fun transferNamesCannotEscapeTheirDirectory() {
        listOf("../secret", "/tmp/file", "..", ".", "", "folder\\file", "x\u0000y", "a".repeat(121)).forEach { denied { Rules.name(it) } }
        assertEquals("receipt.pdf", Rules.name("receipt.pdf"))
    }
    private fun agent(caps: Set<String>?) = Agent("a", "Tester", "hash", "salt", "prefix", 1, caps = caps)
    @Test fun ownerSpeechSurvivesRegistryPollingWhileAgentSpeechRequiresAccess() {
        for (listener in listOf(false, true)) for (on in listOf(false, true)) for (grant in listOf(false, true)) {
            assertTrue(cc.bridgit.app.capability.SpeakerRules.allowed("owner", listener, on, grant))
            assertEquals(listener && on && grant, cc.bridgit.app.capability.SpeakerRules.allowed("agent", listener, on, grant))
        }
    }
    @Test fun eventDeliveryHonorsInteractionAndFamilyGrants() {
        fun allowed(caps: Set<String>?, type: String) = cc.bridgit.app.interact.EventAccess.allowed(agent(caps), type)
        assertFalse(allowed(setOf("tour", "location"), "tour.tick"))
        assertFalse(allowed(setOf("interactions", "tour"), "tour.tick"))
        assertTrue(allowed(setOf("interactions", "tour", "location"), "tour.tick"))
        for ((type, cap) in listOf("file.received" to "files", "share.created" to "shares", "clipboard.completed" to "clipboard", "grant.ended" to "screen_share")) {
            assertFalse(allowed(setOf("interactions"), type))
            assertTrue(allowed(setOf("interactions", cap), type))
        }
        assertTrue(allowed(null, "tour.tick"))
        assertFalse(cc.bridgit.app.interact.EventAccess.allowed(null, "interaction.answered"))
        // Wake-word transcripts and sound events stop the moment the owner takes those switches away.
        assertFalse(allowed(setOf("interactions"), "listen.heard"))
        assertTrue(allowed(setOf("interactions", "wakewords"), "listen.heard"))
        assertFalse(allowed(setOf("interactions", "wakewords"), "sound.heard"))
        assertTrue(allowed(setOf("interactions", "sounds"), "sound.heard"))
    }
    @Test fun explicitGrantsDenyUnlistedAndFutureCapabilities() {
        val a = agent(setOf("gallery"))
        assertTrue(a.allows("gallery")); assertTrue(a.allows("status")); assertFalse(a.allows("mic")); assertFalse(a.allows("future"))
        assertFalse(agent(emptySet()).allows("gallery")); assertTrue(agent(null).allows("gallery"))
    }
    @Test fun grantsSurvivePersistenceAndOldRecordsMigrate() {
        val a = agent(setOf("gallery", "files"))
        assertEquals(a.caps, Agent.fromJson(a.toJson()).caps)
        val legacy = a.toJson().apply { remove("caps") }
        assertNull(Agent.fromJson(legacy).caps)
        assertEquals(emptySet<String>(), Agent.fromJson(agent(emptySet()).toJson()).caps)
    }
    @Test fun endpointAndToolNamesAreUniqueAndPrivatePayloadsAreRedacted() {
        assertEquals(Endpoints.all.size, Endpoints.all.map { "${it.method} ${it.path}" }.toSet().size)
        assertEquals(McpTools.specs.size, McpTools.specs.map { McpTools.name(it) }.toSet().size)
        assertTrue("text" in PlannedEndpoints.CLIPBOARD.privateFields)
        assertTrue("data_base64" in PlannedEndpoints.OCR.privateFields)
        assertTrue(PlannedEndpoints.VIDEO.capture)
        assertTrue(PlannedEndpoints.SHARE_CAPTURE.capture)
    }
}
