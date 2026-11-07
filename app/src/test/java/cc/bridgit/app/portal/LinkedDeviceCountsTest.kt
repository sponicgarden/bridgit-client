package cc.bridgit.app.portal

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkedDeviceCountsTest {
    private fun link(id: String, name: String, enabled: Boolean = true) = LinkedDeviceCounts.Link(id, name, enabled)

    @Test fun noConnectionsDoesNotCountThisPhone() {
        assertEquals(0 to 0, LinkedDeviceCounts.count(emptyList(), emptyList(), emptyList(), emptyList()))
    }
    @Test fun aPhoneLinkedAsHubAndPortalCountsOnceEvenWithCaseAndSpacingDifferences() {
        assertEquals(1 to 0, LinkedDeviceCounts.count(listOf(link("agent", "Kitchen")), listOf(link("device", " kitchen ")), emptyList(), emptyList()))
    }
    @Test fun aDualLinkedPhoneIsEnabledWhileEitherConnectionIsEnabled() {
        assertEquals(1 to 0, LinkedDeviceCounts.count(listOf(link("agent", "Kitchen", false)), listOf(link("device", "Kitchen")), emptyList(), emptyList()))
        assertEquals(0 to 1, LinkedDeviceCounts.count(listOf(link("agent", "Kitchen", false)), listOf(link("device", "Kitchen", false)), emptyList(), emptyList()))
    }
    @Test fun separateDeviceRolesRetainTheirOwnEnablement() {
        assertEquals(2 to 2, LinkedDeviceCounts.count(listOf(link("hub", "Kitchen", false)), listOf(link("portal", "Office")), listOf(link("sat", "Camera", false)), listOf(link("viewer", "iPhone"))))
    }
}
