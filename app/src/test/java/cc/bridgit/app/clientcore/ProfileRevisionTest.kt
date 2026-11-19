package cc.bridgit.app.clientcore

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.core.Connection
import cc.bridgit.app.core.profile.AndroidProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** profile_revision follows what the device reports, not who reads it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProfileRevisionTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp

    @Test fun agentsWithDifferentGrantsSeeTheSameRevision() {
        val a = app.agents.add("A", Connection.DIRECT).first.let { x -> app.agents.update(x.id) { it.copy(caps = setOf("camera")) }!! }
        val b = app.agents.add("B", Connection.DIRECT).first.let { x -> app.agents.update(x.id) { it.copy(caps = setOf("log")) }!! }
        val first = AndroidProfile.profile(app, a).getInt("profile_revision")
        repeat(3) {
            assertEquals(first, AndroidProfile.profile(app, b).getInt("profile_revision"))
            assertEquals(first, AndroidProfile.profile(app, a).getInt("profile_revision"))
        }
        // An agent with Fixer Mode sees more operations, but the revision is the same.
        val c = app.agents.add("C", Connection.DIRECT).first.let { x -> app.agents.update(x.id) { it.copy(caps = setOf("log"), fixer = true) }!! }
        assertEquals(first, AndroidProfile.profile(app, c).getInt("profile_revision"))
        assertEquals(first, AndroidProfile.profile(app, b).getInt("profile_revision"))
        // A real change on the device moves it on.
        AndroidProfile.diagnosticCompleted(app)
        assertTrue(AndroidProfile.profile(app, a).getInt("profile_revision") > first)
    }

    /** A0333: each operation reports its own scope and whether the caller holds it, without running it. */
    @Test fun operationsReportTheirOwnScopeAndBlockers() {
        app.settings.listenerEnabled = true
        val a = app.agents.add("A", Connection.DIRECT).first.let { x -> app.agents.update(x.id) { it.copy(caps = setOf("owner_features", "log")) }!! }
        val spec = cc.bridgit.app.api.Endpoints.all.first { it.controlScope == "owner.configuration" && it.power == null }
        fun op(agent: cc.bridgit.app.core.Agent?): org.json.JSONObject {
            val caps = AndroidProfile.profile(app, agent).getJSONObject("capabilities")
            val ops = caps.getJSONObject("v1.${spec.capability}").getJSONArray("operations")
            return (0 until ops.length()).map { ops.getJSONObject(it) }.single { it.getString("method") == spec.method && it.getString("path") == spec.fullPath }
        }
        val held = op(a)
        assertEquals("owner.configuration", held.getString("scope"))
        // No owner.configuration grant: the module may be allowed, but this operation isn't.
        assertEquals(false, held.getBoolean("agent_allowed"))
        assertEquals(false, held.getBoolean("callable"))
        assertTrue(held.toString(), held.getString("blocked_reason").isNotEmpty())
        assertEquals(false, op(null).getBoolean("agent_allowed"))
        // An operation with no scope of its own follows its capability.
        val log = AndroidProfile.profile(app, a).getJSONObject("capabilities").getJSONObject("v1.log")
        val first = log.getJSONArray("operations").getJSONObject(0)
        assertEquals(log.getBoolean("callable"), first.getBoolean("callable"))
        assertTrue(!first.has("scope"))
    }

    @Test fun setUpAsksForRealAndroidPermissionNames() {
        val camera = app.registry.byId("camera")!!
        app.settings.setOn(camera.switchKey!!, true)
        val missing = AndroidProfile.setupFacts(app).missingPermissions
        if (camera.blockedReason()?.startsWith("permission:") == true) {
            assertTrue(missing.toString(), "android.permission.CAMERA" in missing)
            assertTrue(missing.toString(), missing.all { it.startsWith("android.permission.") })
        }
    }
}
