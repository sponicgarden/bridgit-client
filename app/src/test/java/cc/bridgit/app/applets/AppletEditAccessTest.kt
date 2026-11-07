package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.BridgeService
import cc.bridgit.app.api.ApiServer
import cc.bridgit.app.core.Connection
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppletEditAccessTest {
    @Test fun selectionGrantsOnlyThatAppletAndCanBeReplaced() {
        val context = (RuntimeEnvironment.getApplication() as BridgeApp)
        val access = AppletEditAccess(context)
        val first = JSONObject().put("id", "ai-first").put("requested_by", "installer")
        val second = JSONObject().put("id", "ai-second")
        assertTrue(access.canEdit(first, "installer"))
        assertFalse(access.canEdit(first, "editor"))
        access.grant("ai-first", "editor")
        assertTrue(AppletEditAccess(context).canEdit(first, "editor"))
        assertFalse(access.canEdit(second, "editor"))
        access.grant("ai-first", "replacement")
        assertFalse(access.canEdit(first, "editor"))
        assertTrue(access.canEdit(first, "replacement"))
        access.clear("ai-first")
        assertFalse(access.canEdit(first, "replacement"))
    }

    @Test fun selectedEditorGetsPackageAndCanUpdateButCannotDeleteAndStillNeedsPermissions() = runBlocking {
        val app = (RuntimeEnvironment.getApplication() as BridgeApp)
        app.settings.listenerEnabled = true
        app.registry.switchable().forEach { app.settings.setOn(it.switchKey!!, true) }
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA)
        val installer = app.agents.add("Installer", Connection.DIRECT).first
        val editor = app.agents.add("Claude", Connection.DIRECT).first
        app.agents.update(editor.id) { it.copy(caps = setOf("applet_install")) }
        val definition = JSONObject(File("../site-content/public/recipes/room-change-watcher/applet.json").readText())
        val pending = app.applets.propose(AppletPackage.check(definition), null, JSONObject().put("kind", "test"), installer, notify = false)
        val record = app.applets.approve(pending.getString("id"), null)
        val id = record.getString("id")
        val service = Robolectric.buildService(BridgeService::class.java).create()
        val api = ApiServer(service.get(), emptyList(), 0).also { it.prepareRelay() }
        suspend fun call(endpoint: cc.bridgit.app.api.EndpointSpec, body: JSONObject = JSONObject()) =
            api.dispatch(endpoint, app.agents.get(editor.id)!!, "100.64.0.2", emptyMap(), mapOf("id" to id), body, null)
        try {
            assertEquals(200, call(AppletEndpoints.DEVELOPMENT).status)
            AppletEditAccess(app).grant(id, editor.id)
            val read = call(AppletEndpoints.DEVELOPMENT)
            assertEquals(String(read.bytes), 200, read.status)
            assertEquals(definition.getString("id"), JSONObject(String(read.bytes)).getJSONObject("package").getString("id"))
            assertEquals(404, call(AppletEndpoints.REMOVE).status)
            definition.put("version", "99.0.0")
            val change = JSONObject().put("expected_revision", JSONObject(String(read.bytes)).getLong("revision"))
                .put("intent", "Change the package version").put("acceptance", org.json.JSONArray().put("Requested version is installed"))
                .put("evidence", "Validated the package and existing behavior in this isolated test")
            val update = call(AppletEndpoints.UPDATE, JSONObject().put("package", definition).put("change", change))
            assertEquals(String(update.bytes), 200, update.status)
            val proposed = JSONObject(String(update.bytes))
            assertEquals("awaiting_owner", proposed.getString("status"))
            assertNotEquals("99.0.0", app.applets.get(id)!!.getString("version"))
            app.applets.approve(proposed.getString("pending_id"), null)
            assertEquals("99.0.0", app.applets.get(id)!!.getString("version"))
            // A Modify tap targets the chosen app and preserves the full context even if it ignores share text.
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .setPackage("com.anthropic.claude")
            org.robolectric.Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(send,
                android.content.pm.ResolveInfo().apply {
                    isDefault = true
                    activityInfo = android.content.pm.ActivityInfo().apply {
                        packageName = "com.anthropic.claude"; name = "MainActivity"; exported = true
                    }
                })
            assertNotNull("Share target registered", send.resolveActivity(app.packageManager))
            val controller = Robolectric.buildActivity(androidx.fragment.app.FragmentActivity::class.java).setup()
            try {
                cc.bridgit.app.ui.interactions.AppletAgentHandoff.open(controller.get(), app, id) { fail("Expected the configured Claude app") }
                val sent = org.robolectric.Shadows.shadowOf(controller.get()).nextStartedActivity
                assertEquals("com.anthropic.claude", sent.`package`)
                assertTrue(sent.getStringExtra(android.content.Intent.EXTRA_TEXT)!!.contains(id))
                assertTrue(sent.getStringExtra(android.content.Intent.EXTRA_TEXT)!!.contains(AppletEvolution.GUIDE))
                val text = sent.getStringExtra(android.content.Intent.EXTRA_TEXT)!!
                assertTrue(text.contains("/v1/applets/installed/$id/development"))
                assertTrue(text.contains(record.getString("name")))
                val copied = requireNotNull(controller.get().getSystemService(android.content.ClipboardManager::class.java)).primaryClip!!
                assertEquals(text, copied.getItemAt(0).text.toString())
                val events = app.interactions.eventsFor(editor.id, null, setOf("applet.modify_requested"))
                assertEquals(1, events.size)
                assertEquals(setOf("instance_id", "instructions", "prompt"), events.single().data.keys().asSequence().toSet())
                assertTrue(app.interactions.eventsFor(installer.id, null, setOf("applet.modify_requested")).isEmpty())
            } finally { controller.pause().stop().destroy() }
            app.agents.update(editor.id) { it.copy(caps = emptySet()) }
            assertEquals(403, call(AppletEndpoints.DEVELOPMENT).status)
        } finally {
            AppletEditAccess(app).clear(id)
            app.applets.remove(id)
            service.destroy()
        }
    }
}
