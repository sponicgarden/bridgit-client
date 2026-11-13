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
