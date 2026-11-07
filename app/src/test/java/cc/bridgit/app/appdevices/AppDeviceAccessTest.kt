package cc.bridgit.app.appdevices

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppDeviceAccessTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("app_device_access", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun existingCameraAccessMigratesOnceAndNewDevicesAreUnassigned() {
        val first = AppDeviceAccess(context, listOf("lan:old"))
        assertEquals(setOf(AppDeviceAccess.DOG), first.appsFor("lan:old"))
        assertTrue(first.appsFor("lan:new").isEmpty())
        first.setApps("lan:old", emptySet())
        val reopened = AppDeviceAccess(context, listOf("lan:old", "lan:new"))
        assertTrue(reopened.appsFor("lan:old").isEmpty())
        assertTrue(reopened.appsFor("lan:new").isEmpty())
    }
    @Test fun bothEntryPointsUseTheSameMembershipAndPreserveOtherApps() {
        val access = AppDeviceAccess(context, emptyList())
        access.setApps("lan:a", setOf(AppDeviceAccess.DOG, AppDeviceAccess.TOUR))
        access.setDevices(AppDeviceAccess.DOG, setOf("lan:a", "lan:b"), setOf("lan:b"))
        assertEquals(setOf(AppDeviceAccess.TOUR), access.appsFor("lan:a"))
        assertTrue(access.allows(AppDeviceAccess.DOG, "lan:b"))
        val reopened = AppDeviceAccess(context, emptyList())
        assertTrue(reopened.allows(AppDeviceAccess.DOG, "lan:b"))
        assertFalse(reopened.allows(AppDeviceAccess.DOG, "lan:a"))
    }
    @Test fun revocationAndDeletionDoNotRegrantOnRestart() {
        val access = AppDeviceAccess(context, listOf("lan:a"))
        access.setApps("lan:a", emptySet())
        assertFalse(access.allows(AppDeviceAccess.DOG, "lan:a"))
        access.setApps("lan:a", setOf(AppDeviceAccess.DOG))
        access.remove("lan:a")
        assertTrue(AppDeviceAccess(context, listOf("lan:a")).appsFor("lan:a").isEmpty())
    }
    @Test fun appSettingsOnlyChangeDevicesShownInThatEditor() {
        val access = AppDeviceAccess(context, emptyList())
        access.setApps("lan:a", setOf(AppDeviceAccess.DOG))
        access.setDevices(AppDeviceAccess.DOG, setOf("lan:b"), emptySet())
        assertTrue(access.allows(AppDeviceAccess.DOG, "lan:a"))
    }
    @Test fun everyMutationNotifiesRevocationAfterPersistingAndNeverGrantsNewDevices() {
        val access = AppDeviceAccess(context, emptyList())
        val removed = mutableListOf<Set<String>>()
        access.onChanged = { revoked ->
            revoked.forEach { assertFalse(access.allows(AppDeviceAccess.DOG, it)) }
            removed += revoked
        }
        access.setApps("lan:a", setOf(AppDeviceAccess.DOG))
        access.setApps("lan:a", setOf(AppDeviceAccess.TOUR))
        access.setApps("lan:b", setOf(AppDeviceAccess.DOG))
        access.setDevices(AppDeviceAccess.DOG, setOf("lan:b"), emptySet())
        access.setApps("lan:c", setOf(AppDeviceAccess.DOG))
        access.remove("lan:c")
        assertEquals(listOf(emptySet<String>(), setOf("lan:a"), emptySet(), setOf("lan:b"), emptySet(), setOf("lan:c")), removed)
        assertTrue(access.appsFor("lan:new").isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun unknownAppsCannotReceiveAccess() {
        AppDeviceAccess(context, emptyList()).setApps("lan:a", setOf("not-installed"))
    }
}
