package cc.bridgit.app.ownerapi

import cc.bridgit.app.alerts.AlertRelay
import cc.bridgit.app.alerts.EmailEnrollment
import cc.bridgit.app.core.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailConfigurationLifecycleTest {
    @Test fun subscriptionCannotBeOverwrittenByAnEarlierInFlightUnsubscribe() {
        val settings = Settings(RuntimeEnvironment.getApplication())
        val enrollment = EmailEnrollment(settings, { AlertRelay.Subscribe.PENDING })
        enrollment.subscribe("first@example.com")
        val deleting = CountDownLatch(1)
        val release = CountDownLatch(1)
        val starting = CountDownLatch(1)
        val subscribed = CountDownLatch(1)
        val remove = Thread { enrollment.remove({ deleting.countDown(); release.await(5, TimeUnit.SECONDS); true }) }
        remove.start(); assertTrue(deleting.await(5, TimeUnit.SECONDS))
        val replace = Thread { starting.countDown(); enrollment.subscribe("second@example.com"); subscribed.countDown() }
        try {
            replace.start(); assertTrue(starting.await(5, TimeUnit.SECONDS))
            assertFalse(subscribed.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(subscribed.await(5, TimeUnit.SECONDS))
            remove.join(5000); replace.join(5000)
            assertEquals("second@example.com", settings.alertEmail)
            assertEquals("pending", settings.alertState)
        } finally { release.countDown(); remove.join(5000); replace.join(5000) }
    }

    @Test fun outOfBandAddressChangePreventsStaleRemovalCompletion() {
        val settings = Settings(RuntimeEnvironment.getApplication())
        settings.alertEmail = "first@example.com"
        val enrollment = EmailEnrollment(settings, { AlertRelay.Subscribe.PENDING })
        assertFalse(enrollment.remove({ settings.alertEmail = "second@example.com"; true }))
        assertEquals("second@example.com", settings.alertEmail)
    }
}
