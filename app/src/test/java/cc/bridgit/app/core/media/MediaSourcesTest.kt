package cc.bridgit.app.core.media

import cc.bridgit.app.api.ApiError
import cc.bridgit.app.capture.CaptureRules
import cc.bridgit.app.core.contract.ContractVocab
import cc.bridgit.app.planned.Rules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** core.media provenance, arrival and scopes (architecture 6, A0270) against the contract pack's cases. */
class MediaSourcesTest {
    private val vocab = JSONObject(MediaSourcesTest::class.java.getResource("/client-core/vocab/media-sources.json")!!.readText())

    @Test fun `every pack case follows from the generated table`() {
        val cases = vocab.getJSONArray("cases")
        assertTrue(cases.length() >= 10)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val input = c.getJSONObject("in")
            fun text(k: String) = if (input.isNull(k)) null else input.getString(k)
            val ago = if (input.has("modified_seconds_ago")) input.getLong("modified_seconds_ago") else null
            val got = MediaSources.classify(text("owner_package"), text("relative_path"), if (input.isNull("pending")) null else input.getBoolean("pending"),
                ago?.let { NOW - it }, NOW)
            val want = c.getJSONObject("out")
            val src = want.getJSONObject("source")
            assertEquals("case $i kind", src.getString("kind"), got.source.kind)
            assertEquals("case $i app", src.optString("app").ifEmpty { null }, got.source.app)
            assertEquals("case $i folder", src.optString("folder").ifEmpty { null }, got.source.folder)
            assertEquals("case $i complete", want.getBoolean("complete"), got.complete)
            assertEquals("case $i json", src.keys().asSequence().toSet(), got.source.toJson().keys().asSequence().toSet())
        }
    }

    @Test fun `generated kinds match the pack`() {
        val kinds = vocab.getJSONObject("data").getJSONArray("kinds")
        assertEquals((0 until kinds.length()).map { kinds.getString(it) }, ContractVocab.MEDIA_SOURCE_KINDS)
        assertEquals(vocab.getJSONObject("data").getJSONArray("rules").length(), ContractVocab.MEDIA_SOURCE_RULES.size)
    }

    private val iso = { ms: Long -> "t$ms" }

    @Test fun `a video still arriving is left out by default and listed as incomplete on request`() {
        assertNull(Rules.galleryVideoItem(true, 7, "a.mp4", 1000, 99, null, 5, iso))
        val pending = Rules.galleryVideoItem(true, 7, "a.mp4", 1000, 99, null, 5, iso, includePending = true)!!
        assertFalse(pending.getBoolean("complete"))
        val empty = Rules.galleryVideoItem(false, 8, null, 0, 0, null, 5, iso, includePending = true)!!
        assertFalse(empty.getBoolean("complete"))
        assertTrue(empty.isNull("name"))
        assertTrue(Rules.galleryVideoItem(false, 9, "b.mp4", 1000, 99, null, 5, iso)!!.getBoolean("complete"))
    }

    @Test fun `source only with the library grant, and a device without the new columns still lists`() {
        val hidden = Rules.galleryVideoItem(false, 7, "a.mp4", 1000, 99, null, 5, iso, "com.whatsapp", "Movies/WhatsApp/")!!
        assertFalse(hidden.has("source"))
        val shown = Rules.galleryVideoItem(false, 7, "a.mp4", 1000, 99, null, 5, iso, "com.whatsapp", "Movies/WhatsApp/", showSource = true)!!
        assertEquals("share", shown.getJSONObject("source").getString("kind"))
        // An older device: no is_pending, owner_package_name or relative_path column.
        val old = Rules.galleryVideoItem(null, 7, "a.mp4", 1000, 99, null, 5, iso, null, null, showSource = true)!!
        assertTrue(old.getBoolean("complete"))
        assertEquals(JSONObject().put("kind", "unknown").toString(), old.getJSONObject("source").toString())
    }

    @Test fun `the library and captures scopes share no grant`() {
        assertTrue(MediaScopes.LIBRARY_GRANTS.intersect(MediaScopes.CAPTURE_GRANTS).isEmpty())
        MediaScopes.LIBRARY_GRANTS.forEach { assertEquals(MediaScopes.LIBRARY, MediaScopes.scopeOf(it)) }
        (CaptureRules.KINDS + "captures").forEach { assertEquals(MediaScopes.CAPTURES, MediaScopes.scopeOf(it)) }
        // A library grant never fetches a capture, even with the owner's "any agent can fetch" on.
        for (kind in CaptureRules.KINDS) assertFalse(CaptureRules.canFetch("b", kind, "a", { it in MediaScopes.LIBRARY_GRANTS }, shareAll = true))
        // A captures grant never reveals library provenance.
        assertFalse(MediaScopes.showsSource(true) { it in MediaScopes.CAPTURE_GRANTS || it == "gallery_video" })
        assertTrue(MediaScopes.showsSource(true) { it == "gallery" })
        assertFalse(MediaScopes.showsSource(false) { true })
    }

    @Test fun `extraction on an incomplete item answers 409 media_incomplete`() {
        MediaScopes.requireComplete(true)
        MediaScopes.requireComplete(null)
        try { MediaScopes.requireComplete(false); fail() } catch (e: ApiError) {
            assertEquals(409, e.http); assertEquals("media_incomplete", e.code)
        }
    }

    @Test fun `include_pending takes true or false`() {
        assertFalse(Rules.flag(null, "include_pending"))
        assertTrue(Rules.flag("true", "include_pending"))
        assertTrue(Rules.flag("1", "include_pending"))
        assertFalse(Rules.flag("false", "include_pending"))
        try { Rules.flag("yes", "include_pending"); fail() } catch (e: ApiError) { assertEquals("invalid_parameter", e.code) }
    }

    private fun arrived(pending: Boolean?, name: String?, size: Long?, checkFile: Boolean) =
        MediaSources.arrived(pending, name, size, checkFile, "camera", NOW - 2, NOW)

    @Test fun `one completeness rule for lists and the probe`() {
        assertEquals(false, arrived(true, "a.mp4", 99, checkFile = true))
        assertEquals(false, arrived(false, null, 99, checkFile = true))
        assertEquals(false, arrived(null, "", 99, checkFile = true))
        assertEquals(false, arrived(null, "a.mp4", 0, checkFile = true))
        assertEquals(false, arrived(false, "a.mp4", null, checkFile = true))
        assertEquals(true, arrived(false, "a.mp4", 99, checkFile = true))
        assertEquals(null, arrived(null, "a.mp4", 99, checkFile = true))
        assertEquals(true, arrived(false, null, 0, checkFile = false))
        assertEquals(null, arrived(null, null, null, checkFile = false))
    }

    @Test fun `a sync file modified within the stability window is still arriving`() {
        val window = MediaSources.SYNC_STABLE_MS / 1000
        fun sync(pending: Boolean?, ago: Long?) = MediaSources.arrived(pending, "a.jpg", 99, false, "sync", ago?.let { NOW - it }, NOW)
        assertEquals(false, sync(false, 2))
        assertEquals(false, sync(false, 0))
        assertEquals(false, sync(null, 2))
        assertEquals(false, sync(false, window - 1))
        assertEquals(true, sync(false, window))
        assertEquals(true, sync(false, 60))
        assertEquals(null, sync(null, 60))
        assertEquals(false, sync(true, 60))
        // A small clock skew still counts as recent; a modified time far ahead never holds the item back.
        assertEquals(false, sync(false, -3))
        assertEquals(true, sync(false, -3600))
        // date_modified not returned (or zero): the window isn't applied.
        assertEquals(true, sync(false, null))
        assertEquals(true, MediaSources.arrived(false, "a.jpg", 99, false, "sync", 0, NOW))
        // Videos: the file checks still come first.
        assertEquals(false, MediaSources.arrived(false, "a.mp4", 0, true, "sync", NOW - 60, NOW))
        assertEquals(false, MediaSources.arrived(false, "a.mp4", 99, true, "sync", NOW - 2, NOW))
        assertEquals(true, MediaSources.arrived(false, "a.mp4", 99, true, "sync", NOW - 60, NOW))
    }

    @Test fun `only sync items wait for the window`() {
        for (kind in listOf("camera", "screenshot", "download", "share", "other", "unknown", null)) {
            assertEquals("$kind", true, MediaSources.arrived(false, "a.jpg", 99, false, kind, NOW - 2, NOW))
        }
        assertFalse(MediaSources.classify(null, "DCIM/Syncthing/", false, NOW - 2, NOW).complete)
        assertTrue(MediaSources.classify(null, "DCIM/Syncthing/", false, NOW - 60, NOW).complete)
        assertTrue(MediaSources.classify("com.google.android.GoogleCamera", "DCIM/Camera/", false, NOW - 2, NOW).complete)
    }

    @Test fun `the video list leaves out a settling sync video unless include_pending`() {
        assertNull(Rules.galleryVideoItem(false, 7, "a.mp4", 1000, 99, null, 5, iso, "com.dropbox.android", "Movies/", modifiedSeconds = NOW - 2, nowSeconds = NOW))
        val settling = Rules.galleryVideoItem(false, 7, "a.mp4", 1000, 99, null, 5, iso, "com.dropbox.android", "Movies/", includePending = true, modifiedSeconds = NOW - 2, nowSeconds = NOW)!!
        assertFalse(settling.getBoolean("complete"))
        assertTrue(Rules.galleryVideoItem(false, 7, "a.mp4", 1000, 99, null, 5, iso, "com.dropbox.android", "Movies/", modifiedSeconds = NOW - 60, nowSeconds = NOW)!!.getBoolean("complete"))
        assertTrue(Rules.galleryVideoItem(false, 7, "a.mp4", 1000, 99, null, 5, iso, null, "DCIM/Camera/", modifiedSeconds = NOW - 2, nowSeconds = NOW)!!.getBoolean("complete"))
    }

    private companion object { const val NOW = 1_800_000_000L }
}
