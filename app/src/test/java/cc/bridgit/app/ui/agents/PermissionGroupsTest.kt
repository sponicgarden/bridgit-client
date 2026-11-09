package cc.bridgit.app.ui.agents

import cc.bridgit.app.ui.agents.PermissionGroups.Item
import cc.bridgit.app.ui.agents.PermissionGroups.State
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionGroupsTest {
    private val items = listOf(
        Item("camera", "Camera", "capture"), Item("mic", "Microphone", "capture"),
        Item("speaker", "Speaker", "talk"), Item("torch", "Torch", "control"), Item("ring", "Ring", "controls"),
    )
    private val all = items.map { it.id }

    @Test fun groupsKeepFirstSeenOrderAndMergeControl() {
        val groups = PermissionGroups.of(items) { false }
        assertEquals(listOf("capture", "talk", "controls"), groups.map { it.key })
        assertEquals(listOf("torch", "ring"), groups[2].items.map { it.id })
    }

    @Test fun masterStatesAndCounts() {
        val groups = PermissionGroups.of(items) { it in setOf("camera", "speaker") }
        assertEquals(listOf(State.PARTIAL, State.ON, State.OFF), groups.map { it.state })
        assertEquals(listOf(1, 1, 0), groups.map { it.granted })
    }

    @Test fun groupMasterOnAddsTheWholeGroup() =
        assertEquals(setOf("speaker", "camera", "mic"), PermissionGroups.set(setOf("speaker"), all, listOf("camera", "mic"), on = true))

    @Test fun groupMasterOffRemovesOnlyThatGroup() =
        assertEquals(setOf("speaker"), PermissionGroups.set(setOf("speaker", "camera"), all, listOf("camera", "mic"), on = false))

    @Test fun unrestrictedAgentBecomesExplicitWhenAGroupIsTurnedOff() =
        assertEquals(setOf("speaker", "torch", "ring"), PermissionGroups.set(null, all, listOf("camera", "mic"), on = false))
}
