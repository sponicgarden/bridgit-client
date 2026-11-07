package cc.bridgit.app.power

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
class DefaultAccessTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("default_access", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun dismissingOneAlertDoesNotDismissTheOtherOrGrantAccess() {
        assertFalse(DefaultAccess.isDismissed(context, DefaultAccess.Ask.NOTIFICATIONS))
        assertFalse(DefaultAccess.isDismissed(context, DefaultAccess.Ask.BATTERY))
        val missing = DefaultAccess.notificationsMissing(context)
        DefaultAccess.dismiss(context, DefaultAccess.Ask.NOTIFICATIONS)
        assertTrue(DefaultAccess.isDismissed(context, DefaultAccess.Ask.NOTIFICATIONS))
        assertFalse(DefaultAccess.isDismissed(context, DefaultAccess.Ask.BATTERY))
        assertEquals(missing, DefaultAccess.notificationsMissing(context))
        assertTrue(context.getSharedPreferences("default_access", Context.MODE_PRIVATE).getBoolean("asked_notifications", false))
    }

    @Test fun dismissalsPersistWithAppDataAcrossReopeningAndUpdates() {
        DefaultAccess.Ask.entries.forEach { DefaultAccess.dismiss(context, it) }
        val reopened = context.createPackageContext(context.packageName, 0)
        DefaultAccess.Ask.entries.forEach { assertTrue(DefaultAccess.isDismissed(reopened, it)) }
        assertTrue(reopened.getSharedPreferences("default_access", Context.MODE_PRIVATE).getBoolean("asked_battery", false))
    }

    @Test fun previouslyAskedDialogsDoNotSilentlyDismissBanners() {
        DefaultAccess.Ask.entries.forEach {
            DefaultAccess.markAsked(context, it)
            assertFalse(DefaultAccess.isDismissed(context, it))
        }
    }
}
