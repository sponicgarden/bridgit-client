package cc.bridgit.app.ui.agents

import cc.bridgit.app.core.Agent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A0115-A0118: counts and layers on the Agents and Linked Devices tab, the same ones the API reads. */
class PermissionHomeTest {
    private val items = listOf("camera", "mic", "settings", "portal", "location").map { PermissionGroups.Item(it, it, "capture") }
    private fun agent(caps: Set<String>?, superPowers: Boolean = false) =
        Agent("a1", "Claude", "h", "s", "brg_", 0, caps = caps, superPowers = superPowers)
    private fun layer(perms: List<PermissionHome.Perm>, id: String) = perms.first { it.id == id }.layer

    @Test fun everyPermissionIncludingNewOnesIsAnInheritedDefault() {
        val perms = PermissionHome.agent(items, agent(null))
        assertEquals(PermissionHome.Summary(5, 5), PermissionHome.summary(perms))
        assertTrue(perms.all { it.layer == PermissionHome.Layer.DEFAULT })
    }

    @Test fun chosenGrantsAreSpecificOnAndOffWithTheCount() {
        val perms = PermissionHome.agent(items, agent(setOf("camera", "mic")))
        assertEquals(PermissionHome.Summary(2, 5), PermissionHome.summary(perms))
        assertEquals(PermissionHome.Layer.SPECIFIC, layer(perms, "camera"))
        assertEquals(PermissionHome.Layer.SPECIFIC, layer(perms, "location"))
        assertFalse(perms.first { it.id == "location" }.on)
    }

    @Test fun superpowersReadsDifferentlyFromASpecificGrant() {
        val perms = PermissionHome.agent(items, agent(setOf("camera", "settings"), superPowers = true))
        assertEquals(PermissionHome.Layer.SUPERPOWERS, layer(perms, "settings"))
        assertEquals(PermissionHome.Layer.SPECIFIC, layer(perms, "camera"))
        // Without Superpowers the same grant is just specific.
        assertEquals(PermissionHome.Layer.SPECIFIC, layer(PermissionHome.agent(items, agent(setOf("settings"))), "settings"))
    }

    @Test fun aLinkedDeviceCantBeGivenMoreThanItsPairingButKeepsTheSameList() {
        val perms = PermissionHome.linked(items, agent(setOf("portal")), ceiling = setOf("portal", "location"))
        assertEquals(5, perms.size)
        assertEquals(PermissionHome.Layer.UNAVAILABLE, layer(perms, "camera"))
        assertFalse(perms.first { it.id == "camera" }.canChange)
        assertTrue(perms.first { it.id == "location" }.canChange)
        assertTrue(perms.first { it.id == "portal" }.canChange)
        assertEquals(PermissionHome.Summary(1, 5), PermissionHome.summary(perms))
    }

    @Test fun aDeviceSwitchIsSpecificInheritedOrBaseline() {
        val perms = PermissionHome.device(items, isOn = { it == "camera" || it == "location" }, isSpecific = { it == "camera" },
            inherited = { if (it == "location") true else null })
        assertEquals(PermissionHome.Layer.SPECIFIC, layer(perms, "camera"))
        assertEquals(PermissionHome.Layer.DEFAULT, layer(perms, "location"))
        assertEquals(PermissionHome.Layer.BASELINE, layer(perms, "mic"))
        assertEquals(PermissionHome.Summary(2, 5), PermissionHome.summary(perms))
    }
}
