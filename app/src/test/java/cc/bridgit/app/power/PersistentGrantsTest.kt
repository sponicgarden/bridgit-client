package cc.bridgit.app.power

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.core.Connection
import cc.bridgit.app.platform.Capabilities
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersistentGrantsTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp

    @Test fun maximumAndSuperpowersAreIndependentAndRevocable() {
        val agent = app.agents.add("Recommended", Connection.DIRECT).first
        val access = PowerAccess(app)
        access.grantAll(agent.id)
        assertTrue(access.isMaximum(agent.id))
        assertFalse(app.agents.get(agent.id)!!.superPowers)
        app.agents.update(agent.id) { it.copy(superPowers = true) }
        access.grantAll(agent.id)
        assertTrue(app.agents.get(agent.id)!!.superPowers)
        app.agents.update(agent.id) { it.copy(superPowers = false) }
        assertTrue(app.agents.get(agent.id)!!.maximumControl)
        assertTrue(access.isMaximum(agent.id))
        assertFalse(access.isMaximum(agent.id, requireSuper = true))
    }

    @Test fun maximumIncludesDeviceToolsEvenWhenRegularCapabilitiesAlreadyAllowAll() {
        val agent = app.agents.add("Maximum", Connection.DIRECT).first
        app.agents.update(agent.id) { it.copy(caps = null) }
        app.platform.revoke(agent.id)
        app.agents.update(agent.id) { it.copy(caps = null) }
        val undo = PowerAccess(app).grantAll(agent.id)
        val supported = Capabilities.all.filter { !it.privateOnly || cc.bridgit.app.BuildConfig.PRIVATE_CONTROL }
        supported.forEach {
            assertEquals(it.id, Long.MAX_VALUE, app.platform.store.grants(agent.id).getLong(it.id))
            assertTrue(app.platform.store.allowed(agent.id, it.id, System.currentTimeMillis() + 30L * 86400_000))
        }
        val discovered = Capabilities.describe(app, app.policy.agents().first { it.id == agent.id }).getJSONArray("capabilities")
        val owner = (0 until discovered.length()).map { discovered.getJSONObject(it) }.first { it.getString("id") == "owner.configuration" }
        assertEquals("until_revoked", owner.getString("grant_lifetime"))
        assertTrue(owner.isNull("grant_expires_at"))
        app.platform.revoke(agent.id, "owner.configuration")
        assertFalse(app.platform.hasGrant(agent.id, "owner.configuration"))
        assertTrue(app.platform.hasGrant(agent.id, "files"))
        undo()
        assertNull(app.agents.get(agent.id)!!.caps)
        assertEquals(0, app.platform.store.grants(agent.id).length())
    }

    @Test fun undoRestoresExactPreviousLimitsAndLeavesOtherAgentsAlone() {
        val agent = app.agents.add("Selected", Connection.DIRECT).first
        val other = app.agents.add("Other", Connection.DIRECT).first
        app.agents.update(agent.id) { it.copy(caps = setOf("camera")) }
        app.platform.grant(agent.id, "files", 9000)
        app.platform.grant(other.id, "files", 12000)
        val before = app.platform.store.grants(agent.id).toString()
        val undo = PowerAccess(app).grantAll(agent.id)
        assertEquals(12000, app.platform.store.grants(other.id).getLong("files"))
        undo()
        assertEquals(setOf("camera"), app.agents.get(agent.id)!!.caps)
        assertEquals(before, app.platform.store.grants(agent.id).toString())
    }

    @Test fun relayMaximumKeepsExplicitGrantsAndSurvivesReloadWithoutRestoringRevocations() {
        val a = app.agents.add("RelayMaximum", Connection.RELAY).first
        app.agents.update(a.id) { it.copy(caps = setOf("status")) }
        PowerAccess(app).grantAll(a.id)
        val live = app.agents.get(a.id)!!
        assertNotNull(live.caps)
        assertTrue(live.fixer && live.maximumControl)
        assertFalse(live.superPowers)
        assertTrue(cc.bridgit.app.core.Agent.fromJson(live.toJson()).maximumControl)
        assertTrue(PowerAccess(app).isMaximum(a.id))
        assertTrue(app.platform.hasGrant(a.id, "diagnostics"))
        // Simulate an added scope missing from the old snapshot.
        app.platform.store.revoke(a.id, "files")
        PowerAccess(app).reconcileMaximum()
        assertTrue(app.platform.hasGrant(a.id, "files"))
        app.platform.revoke(a.id, "diagnostics")
        PowerAccess(app).reconcileMaximum()
        assertFalse(app.platform.hasGrant(a.id, "diagnostics"))
        assertFalse(app.agents.get(a.id)!!.maximumControl)
        assertFalse(PowerAccess(app).isMaximum(a.id))
        assertTrue(app.platform.hasGrant(a.id, "files"))
    }

    @Test fun narrowingLegacyPermissionsStopsTheMatchingPlatformScopeAndMaximumInheritance() {
        val a = app.agents.add("NarrowMaximum", Connection.DIRECT).first
        PowerAccess(app).grantAll(a.id)
        app.agents.update(a.id) { it.copy(caps = setOf("status")) }
        assertFalse(app.platform.hasGrant(a.id, "camera.snapshot"))
        assertFalse(app.agents.get(a.id)!!.maximumControl)
        app.agents.update(a.id) { it.copy(paused = true) }
        assertFalse(app.platform.hasGrant(a.id, "files"))
    }

    @Test fun maximumDefaultsEnableUntouchedFeaturesAndRespectSavedOffSwitches() {
        app.settings.setOn("camera",false)
        Capabilities.setEnabled(app,"files",false)
        PowerAccess(app).enableUnconfiguredFeatures()
        assertFalse(app.settings.isOn("camera"))
        assertTrue(app.settings.isOn("settings_api"))
        assertFalse(Capabilities.enabled(app,"files"))
        assertTrue(Capabilities.enabled(app,"owner.configuration"))
    }

    @Test fun oldMaximumRelaySnapshotIsRepairedWithoutGrantingNewAuthority() {
        val a = app.agents.add("OldRelayMax", Connection.RELAY).first
        Capabilities.all.filter { !it.privateOnly || cc.bridgit.app.BuildConfig.PRIVATE_CONTROL }
            .forEach { app.platform.grant(a.id,it.id,Long.MAX_VALUE) }
        assertNull(app.agents.get(a.id)!!.caps)
        PowerAccess(app).reconcileMaximum()
        val live = app.agents.get(a.id)!!
        assertNotNull(live.caps)
        assertFalse(live.maximumControl || live.superPowers || live.fixer)
        assertTrue(app.platform.hasGrant(a.id,"diagnostics"))
    }

    @Test fun historicalEmptyGrantsAreNotAutomaticallyWidened() {
        val a = app.agents.add("Historical", Connection.RELAY).first
        app.agents.update(a.id) { it.copy(caps = setOf("status")) }
        PowerAccess(app).reconcileMaximum()
        assertEquals(0, app.platform.store.grants(a.id).length())
    }

    @Test fun portalPrincipalsCannotBecomeControlAgentsThroughMaximum() {
        val store = cc.bridgit.app.relay.RelayStore(app)
        store.enabled = true
        store.base = "https://relay.example/d/" + store.device
        val portal = cc.bridgit.app.portal.PortalPairing.create(app, store).agent
        PowerAccess(app).grantAll(portal.id)
        assertFalse(app.agents.get(portal.id)!!.maximumControl)
        assertEquals(0, app.platform.store.grants(portal.id).length())
    }

    @Test fun appletPrincipalsCannotBecomeControlAgentsThroughMaximum() {
        val applet = app.agents.add("Applet", Connection.APPLET).first
        val before = app.platform.store.grants(applet.id).toString()
        PowerAccess(app).grantAll(applet.id)
        assertEquals(before, app.platform.store.grants(applet.id).toString())
    }
}
