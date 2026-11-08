package cc.bridgit.app.capture

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Intent
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.capability.LockedCameraApproval
import cc.bridgit.app.capability.LockedCameraApprovalReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Report A0049: agents' photos work while locked; captures.locked_camera_approval makes each one wait for Allow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LockedCameraApprovalTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    private fun setLocked(locked: Boolean) = shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(locked)

    @Before fun setup() { app.settings.capturesLockedCameraApproval = false; app.settings.securityBiometric = false; setLocked(false) }
    @After fun cleanup() { app.settings.capturesLockedCameraApproval = false; app.settings.securityBiometric = false; setLocked(false) }

    /** Its own scope, so an expected approval_declined doesn't cancel the test's runBlocking. */
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun start(waitMs: Long = 5_000): Deferred<Boolean> =
        requests.async { LockedCameraApproval.require(app, "Muse", waitMs) }

    /** The posted request notification, waiting up to 2 s for it to appear. */
    private fun posted(): android.app.Notification {
        val until = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < until) {
            shadowOf(nm).allNotifications.firstOrNull { it.actions?.size == 2 }?.let { return it }
            Thread.sleep(10)
        }
        fail("No approval notification was posted")
        error("unreachable")
    }

    private fun tap(n: android.app.Notification, title: String) {
        val action = n.actions.first { it.title.toString() == title }
        val intent: Intent = shadowOf(action.actionIntent).savedIntent
        LockedCameraApprovalReceiver().onReceive(app, intent)
    }

    private fun declined(d: Deferred<Boolean>): ApiError = runBlocking {
        try { d.await(); fail("Expected approval_declined"); error("unreachable") } catch (e: ApiError) { e }
    }

    @Test fun onlyALockedPhoneWithApprovalOnWaits() {
        assertFalse(LockedCameraApproval.needed(locked = false, approvalOn = false))
        assertFalse(LockedCameraApproval.needed(locked = false, approvalOn = true))
        assertFalse(LockedCameraApproval.needed(locked = true, approvalOn = false))
        assertTrue(LockedCameraApproval.needed(locked = true, approvalOn = true))
    }

    @Test fun defaultOffTakesTheLockedPhotoWithoutAsking() = runBlocking {
        assertFalse("off by default", app.settings.capturesLockedCameraApproval)
        setLocked(true)
        assertTrue("locked photo may go ahead", LockedCameraApproval.require(app, "Muse", waitMs = 50))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun unlockedPhoneNeverAsksEvenWithApprovalOn() = runBlocking {
        app.settings.capturesLockedCameraApproval = true
        // Not approved for a locked phone: if it locks while the photo waits for the camera, the camera refuses.
        assertFalse(LockedCameraApproval.require(app, "Muse", waitMs = 50))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun allowLetsThePhotoGoAheadAndClearsTheNotification() = runBlocking {
        app.settings.capturesLockedCameraApproval = true
        setLocked(true)
        val d = start()
        val n = posted()
        assertEquals(android.app.Notification.VISIBILITY_PUBLIC, n.visibility)
        tap(n, "Allow")
        assertTrue(d.await())
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun denyAndNoAnswerReturnApprovalDeclinedWithReason() = runBlocking {
        app.settings.capturesLockedCameraApproval = true
        setLocked(true)
        val denied = start()
        tap(posted(), "Deny")
        declined(denied).let { assertEquals("approval_declined", it.code); assertEquals("declined", it.extra["reason"]) }
        val ignored = start(waitMs = 600)
        posted()
        declined(ignored).let { assertEquals("approval_declined", it.code); assertEquals("no_answer", it.extra["reason"]) }
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun swipingTheNotificationAwayDenies() = runBlocking {
        app.settings.capturesLockedCameraApproval = true
        setLocked(true)
        val d = start()
        val n = posted()
        LockedCameraApprovalReceiver().onReceive(app, shadowOf(n.deleteIntent).savedIntent)
        assertEquals("declined", declined(d).extra["reason"])
    }

    @Test fun oneRequestWaitsAtATimeAndStaleAnswersAreIgnored() = runBlocking {
        app.settings.capturesLockedCameraApproval = true
        setLocked(true)
        val first = start()
        val n = posted()
        try { LockedCameraApproval.require(app, "Other", waitMs = 50); fail("Expected sensor_busy") }
        catch (e: ApiError) { assertEquals("sensor_busy", e.code) }
        assertFalse(LockedCameraApproval.answer("not-this-request", true))
        tap(n, "Allow")
        first.await()
        assertFalse("answered requests stop waiting", LockedCameraApproval.answer("not-this-request", false))
    }

    @Test fun withTheFingerprintSettingAllowNeedsTheOwnerToUnlock() = runBlocking {
        app.settings.capturesLockedCameraApproval = true
        app.settings.securityBiometric = true
        setLocked(true)
        val d = start(waitMs = 600)
        val n = posted()
        assertTrue(n.actions.first { it.title.toString() == "Allow" }.isAuthenticationRequired)
        tap(n, "Allow") // still locked: ignored
        assertEquals("no_answer", declined(d).extra["reason"])
    }
}
