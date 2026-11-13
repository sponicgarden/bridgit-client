package cc.bridgit.app.core.media

import android.provider.MediaStore
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiDocs
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.capability.Gallery
import cc.bridgit.app.clientcore.FakeMediaStore
import cc.bridgit.app.core.Connection
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** MediaStore side of core.media: the column fallback, the completeness probe, and the captures store staying separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MediaLibraryTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private val noProvenance = setOf(MediaLibrary.OWNER_PACKAGE, MediaLibrary.IS_PENDING, MediaLibrary.RELATIVE_PATH)

    @Test fun `photo list keeps working on a device without the provenance columns`() {
        FakeMediaStore.install(listOf(FakeMediaStore.Row(5, video = false, ownerPackage = "com.android.chrome", relativePath = "Download/")),
            missing = setOf(MediaLibrary.OWNER_PACKAGE, MediaLibrary.IS_PENDING))
        val item = Gallery(app).recent(10).single()
        assertNull(item.ownerPackage)
        assertNull(item.pending)
        assertEquals("download", item.arrival.source.kind)
        assertTrue(item.arrival.complete)
    }

    @Test fun `photo list keeps is_pending when only owner_package_name is rejected`() {
        FakeMediaStore.install(listOf(FakeMediaStore.Row(6, video = false, relativePath = "Download/", pending = true)),
            missing = setOf(MediaLibrary.OWNER_PACKAGE))
        val item = Gallery(app).recent(10, includePending = true).single()
        assertNull(item.ownerPackage)
        assertEquals(true, item.pending)
        assertFalse(item.arrival.complete)
        assertEquals(false, MediaLibrary.complete(app, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 6))
    }

    @Test fun `a sync item still settling is incomplete in the list and the probe, and a device without date_modified skips the window`() {
        val now = System.currentTimeMillis() / 1000
        val seed = listOf(FakeMediaStore.Row(21, video = false, ownerPackage = "com.dropbox.android", relativePath = "Pictures/", modifiedAt = now - 2),
            FakeMediaStore.Row(22, video = true, name = "a.mp4", ownerPackage = "com.dropbox.android", relativePath = "Movies/", modifiedAt = now - 2))
        FakeMediaStore.install(seed)
        val photo = Gallery(app).recent(10, includePending = true).single()
        assertEquals(now - 2, photo.modifiedSeconds)
        assertFalse(photo.arrival.complete)
        assertEquals(false, MediaLibrary.complete(app, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 21))
        assertEquals(false, MediaLibrary.complete(app, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 22, checkFile = true))
        assertFalse(videoList(mapOf("include_pending" to "true")).getJSONObject(0).getBoolean("complete"))

        FakeMediaStore.install(seed, missing = setOf(MediaLibrary.DATE_MODIFIED))
        val old = Gallery(app).recent(10, includePending = true).single()
        assertNull(old.modifiedSeconds)
        assertEquals(false, old.pending)
        assertTrue(old.arrival.complete)
        assertEquals(true, MediaLibrary.complete(app, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 21))
        assertEquals(true, MediaLibrary.complete(app, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 22, checkFile = true))
        val video = videoList(mapOf("include_pending" to "true")).getJSONObject(0)
        assertTrue(video.getBoolean("complete"))
        assertEquals("sync", video.getJSONObject("source").getString("kind"))
    }

    private var listCalls = 0

    /** GET /v1/gallery/videos/recent as an agent with the library grants; the decoded items. */
    private fun videoList(query: Map<String, String> = emptyMap()): org.json.JSONArray = runBlocking {
        app.settings.listenerEnabled = true
        app.settings.emailVerifiedAt = System.currentTimeMillis()
        shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_VIDEO)
        app.settings.setOn("gallery_video", true)
        app.settings.setOn("gallery", true)
        val a = app.agents.add("media test ${++listCalls}", Connection.DIRECT).first
        val agent = app.agents.update(a.id) { it.copy(caps = setOf("gallery_video", "gallery")) }!!
        val svc = Robolectric.buildService(BridgeService::class.java).create().get()
        val api = ApiServer(svc, emptyList(), 0).also { it.prepareRelay() }
        try {
            val out = api.dispatch(ApiDocs.find("GET /v1/gallery/videos/recent")!!, agent, "100.64.0.2", query, emptyMap(), JSONObject(), null)
            assertEquals(String(out.bytes), 200, out.status)
            JSONObject(String(out.bytes)).getJSONArray("items")
        } finally { api.stop(); svc.onDestroy() }
    }

    @Test fun `video list never answers 500 because of a missing column`() {
        FakeMediaStore.install(listOf(FakeMediaStore.Row(3, video = true, ownerPackage = "com.whatsapp", relativePath = "Movies/WhatsApp/")), missing = noProvenance)
        val item = videoList().getJSONObject(0)
        assertEquals(3L, item.getLong("id"))
        assertTrue(item.getBoolean("complete"))
        assertEquals("unknown", item.getJSONObject("source").getString("kind"))
    }

    @Test fun `video list keeps pending and provenance when the sort args are rejected`() {
        FakeMediaStore.install(listOf(FakeMediaStore.Row(1, video = true, addedAt = 1_762_800_000),
            FakeMediaStore.Row(2, video = true, addedAt = 1_762_800_060, ownerPackage = "com.whatsapp", relativePath = "Movies/WhatsApp/", pending = true)),
            rejectSort = true)
        val all = videoList(mapOf("include_pending" to "true"))
        assertEquals(2, all.length())
        assertEquals(2L, all.getJSONObject(0).getLong("id"))
        assertFalse(all.getJSONObject(0).getBoolean("complete"))
        assertEquals("share", all.getJSONObject(0).getJSONObject("source").getString("kind"))
        assertEquals("com.whatsapp", all.getJSONObject(0).getJSONObject("source").getString("app"))
        assertTrue(all.getJSONObject(1).getBoolean("complete"))
        val default = videoList()
        assertEquals(1, default.length())
        assertEquals(1L, default.getJSONObject(0).getLong("id"))
    }

    @Test fun `the probe and the video list agree on a video without a name or size`() {
        val videos = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val seed = listOf(FakeMediaStore.Row(1, video = true), FakeMediaStore.Row(2, video = true, nullName = true),
            FakeMediaStore.Row(3, video = true, name = ""), FakeMediaStore.Row(4, video = true, size = 0))
        FakeMediaStore.install(seed)
        assertEquals(true, MediaLibrary.complete(app, videos, 1, checkFile = true))
        for (id in 2L..4L) assertEquals("video $id", false, MediaLibrary.complete(app, videos, id, checkFile = true))
        val listed = videoList(mapOf("include_pending" to "true"))
        val complete = (0 until listed.length()).associate { listed.getJSONObject(it).getLong("id") to listed.getJSONObject(it).getBoolean("complete") }
        assertEquals(mapOf(1L to true, 2L to false, 3L to false, 4L to false), complete)
        assertTrue(listed.getJSONObject((0 until listed.length()).first { listed.getJSONObject(it).getLong("id") == 2L }).isNull("name"))
        // Without is_pending the probe still checks the name and size; a good video is then "can't tell".
        FakeMediaStore.install(seed, missing = setOf(MediaLibrary.IS_PENDING))
        assertNull(MediaLibrary.complete(app, videos, 1, checkFile = true))
        for (id in 2L..4L) assertEquals("video $id", false, MediaLibrary.complete(app, videos, id, checkFile = true))
    }

    @Test fun `the completeness probe sees pending rows and says nothing when it can't tell`() {
        FakeMediaStore.install(listOf(FakeMediaStore.Row(1, video = false), FakeMediaStore.Row(2, video = false, pending = true),
            FakeMediaStore.Row(3, video = true, size = 0)))
        val images = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        assertEquals(true, MediaLibrary.complete(app, images, 1))
        assertEquals(false, MediaLibrary.complete(app, images, 2))
        assertNull(MediaLibrary.complete(app, images, 99))
        assertEquals(false, MediaLibrary.complete(app, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 3, checkFile = true))
        FakeMediaStore.install(listOf(FakeMediaStore.Row(2, video = false, pending = true)), missing = setOf(MediaLibrary.IS_PENDING))
        assertNull(MediaLibrary.complete(app, images, 2))
    }

    @Test fun `a library grant never reaches the captures store`() {
        app.settings.capturesShareAll = true
        val maker = app.agents.add("camera agent", Connection.DIRECT).first.let { a -> app.agents.update(a.id) { it.copy(caps = setOf("camera")) }!! }
        val library = app.agents.add("library agent", Connection.DIRECT).first.let { a -> app.agents.update(a.id) { it.copy(caps = setOf("gallery", "gallery_video")) }!! }
        val other = app.agents.add("other camera agent", Connection.DIRECT).first.let { a -> app.agents.update(a.id) { it.copy(caps = setOf("camera")) }!! }
        val kept = app.captures.keep(maker.id, "camera", byteArrayOf(1, 2, 3), "image/jpeg", System.currentTimeMillis(),
            app.captures.plan(maker.id, "camera", setOf("memory")))!!
        val id = kept.getString("capture_id")
        assertNull(app.captures.get(id, library.id))
        assertTrue(app.captures.list(library.id).isEmpty())
        assertNotNull(app.captures.get(id, other.id))
        assertFalse(MediaScopes.showsSource(app.settings.isOn("gallery")) { maker.allows(it) })
    }
}
