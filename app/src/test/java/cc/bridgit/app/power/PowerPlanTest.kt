package cc.bridgit.app.power

import cc.bridgit.app.power.PowerPlan.Kind
import cc.bridgit.app.power.PowerPlan.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPlanTest {
    private val dangerousSet = setOf(
        "android.permission.POST_NOTIFICATIONS", "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
        "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACTIVITY_RECOGNITION",
        "android.permission.READ_CONTACTS",
    )
    private val sideloadManifest = dangerousSet + listOf(
        "android.permission.INTERNET", "android.permission.WRITE_SETTINGS", "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.ACCESS_NOTIFICATION_POLICY", "android.permission.health.READ_STEPS",
    )
    private val a11y = listOf("cc.bridgit.app/.capability.ScreenshotService")
    private val listener = listOf("cc.bridgit.app/.planned.NotificationReaderService")

    private fun full() = PowerPlan.steps(sideloadManifest, dangerousSet::contains, a11y, listener, sideloaded = true)

    @Test fun ordersRuntimeThenSpecialThenRestricted() {
        val ids = full().map { it.id }
        assertEquals(listOf("notifications", "camera", "mic", "photos", "location", "activity", "perm:READ_CONTACTS", "health",
            "battery", "usage", "write_settings", "dnd",
            PowerPlan.LISTENER + listener[0], PowerPlan.A11Y + a11y[0], "installs", PowerPlan.RESTRICTED), ids)
    }

    /**
     * Max Agentic Control covers "Display over other apps" (Open Bridgit when an agent sends something needs it).
     * It comes first so Bridgit can come back by itself after each later page; installs comes last because
     * Android stops Bridgit when that switch changes.
     */
    @Test fun overlayFirstAndInstallsLastAmongSpecialSteps() {
        val ids = PowerPlan.steps(sideloadManifest + "android.permission.SYSTEM_ALERT_WINDOW", dangerousSet::contains, a11y, listener, sideloaded = true)
            .filter { it.kind == Kind.SPECIAL }.map { it.id }
        assertEquals(listOf("overlay", "battery", "usage", "write_settings", "dnd",
            PowerPlan.LISTENER + listener[0], PowerPlan.A11Y + a11y[0], "installs"), ids)
    }

    @Test fun displayOverOtherAppsIsAStepWhenDeclared() {
        val s = PowerPlan.steps(sideloadManifest + "android.permission.SYSTEM_ALERT_WINDOW", dangerousSet::contains, a11y, listener, sideloaded = true)
        val ids = s.map { it.id }
        assertEquals(Kind.SPECIAL, s.single { it.id == "overlay" }.kind)
        // First special step: once on, Bridgit can come back by itself after each later page.
        assertEquals(ids.indexOf("health") + 1, ids.indexOf("overlay"))
    }

    @Test fun returnsOnGrantOnlyFromSettingsPages() {
        val s = full().associateBy { it.id }
        assertTrue(PowerPlan.returnsOnGrant(s.getValue("usage")))
        assertTrue(PowerPlan.returnsOnGrant(s.getValue(PowerPlan.A11Y + a11y[0])))
        assertFalse(PowerPlan.returnsOnGrant(s.getValue("battery")))
        assertFalse(PowerPlan.returnsOnGrant(s.getValue("installs")))
        assertFalse(PowerPlan.returnsOnGrant(s.getValue("camera")))
        assertFalse(PowerPlan.returnsOnGrant(s.getValue(PowerPlan.RESTRICTED)))
    }

    @Test fun normalPermissionsAreNotSteps() {
        assertFalse(full().any { "android.permission.INTERNET" in it.permissions })
    }

    @Test fun newDangerousPermissionJoinsByItself() {
        val s = full().single { it.id == "perm:READ_CONTACTS" }
        assertEquals(Kind.RUNTIME, s.kind)
        assertEquals(listOf("android.permission.READ_CONTACTS"), s.permissions)
    }

    @Test fun newAccessibilityServiceJoinsByItself() {
        val more = a11y + "cc.bridgit.app/.drive.DriveService"
        val steps = PowerPlan.steps(sideloadManifest, dangerousSet::contains, more, listener, sideloaded = true)
        assertEquals(2, steps.count { it.id.startsWith(PowerPlan.A11Y) })
        assertEquals(PowerPlan.RESTRICTED, steps.last().id)
    }

    /** GATE-03: a store build that declares less offers less, and needs no restricted-settings step. */
    @Test fun storeBuildOffersOnlyWhatItDeclares() {
        val play = setOf("android.permission.POST_NOTIFICATIONS", "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
        val ids = PowerPlan.steps(play, dangerousSet::contains, emptyList(), emptyList(), sideloaded = false).map { it.id }
        assertEquals(listOf("notifications", "camera", "mic", "battery"), ids)
    }

    @Test fun selectPhotosRidesWithImages() {
        assertEquals(listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"), full().single { it.id == "photos" }.permissions)
    }

    @Test fun storeInstallHasNoRestrictedStep() {
        val steps = PowerPlan.steps(sideloadManifest, dangerousSet::contains, a11y, listener, sideloaded = false)
        assertFalse(steps.any { it.kind == Kind.RESTRICTED })
    }

    @Test fun nextSkipsGrantedAndDeclined() {
        val steps = full()
        val st = PowerPlan.status(steps, { it.id == "notifications" }, declined = setOf("camera"))
        assertEquals(Status.ON, st["notifications"])
        assertEquals(Status.DECLINED, st["camera"])
        assertEquals("mic", PowerPlan.next(steps, st)?.id)
    }

    @Test fun restrictedHiddenUntilARestrictableStepIsDeclined() {
        val steps = full()
        assertNull(PowerPlan.status(steps, { false }, emptySet())[PowerPlan.RESTRICTED])
        val st = PowerPlan.status(steps, { false }, setOf(PowerPlan.A11Y + a11y[0]))
        assertEquals(Status.OFF, st[PowerPlan.RESTRICTED])
    }

    @Test fun restrictedOnWhenEveryRestrictableIsOn() {
        val steps = full()
        val st = PowerPlan.status(steps, PowerPlan::isRestrictable, emptySet())
        assertEquals(Status.ON, st[PowerPlan.RESTRICTED])
    }

    @Test fun doneWhenEverythingOnOrDeclined() {
        val steps = full()
        val all = steps.map { it.id }.toSet()
        val st = PowerPlan.status(steps, { it.kind == Kind.RUNTIME }, all)
        assertNull(PowerPlan.next(steps, st))
        assertTrue(st.values.none { it == Status.OFF })
    }

    @Test fun backgroundLocationWaitsForSeparateRequestAfterForegroundGrant() {
        val coarse = "android.permission.ACCESS_COARSE_LOCATION"
        val background = "android.permission.ACCESS_BACKGROUND_LOCATION"
        val steps = PowerPlan.steps(listOf(coarse, background), { true }, emptyList(), emptyList(), false)
        val off = PowerPlan.status(steps, { false }, emptySet())
        assertEquals(listOf(coarse), PowerPlan.pendingRuntime(steps, off) { false })
        val foregroundOn = PowerPlan.status(steps, { it.id == "location" }, emptySet())
        assertEquals(listOf(background), PowerPlan.pendingRuntime(steps, foregroundOn) { it == coarse })
        val declined = PowerPlan.status(steps, { false }, setOf("location"))
        assertTrue(PowerPlan.pendingRuntime(steps, declined) { false }.isEmpty())
    }

    @Test fun pendingRuntimeBatchesEverythingButHealth() {
        val steps = full()
        val st = PowerPlan.status(steps, { it.id == "camera" }, setOf("mic"))
        val perms = PowerPlan.pendingRuntime(steps, st) { it == "android.permission.ACCESS_COARSE_LOCATION" }
        assertTrue("android.permission.POST_NOTIFICATIONS" in perms)
        assertFalse("android.permission.CAMERA" in perms) // on
        assertFalse("android.permission.RECORD_AUDIO" in perms) // declined
        assertFalse("android.permission.ACCESS_COARSE_LOCATION" in perms) // already granted
        assertTrue("android.permission.ACCESS_FINE_LOCATION" in perms)
        assertFalse(perms.any { it.startsWith(PowerPlan.HEALTH_PREFIX) })
    }

    /** A skip doesn't stop Max Agentic Control: it moves on to the next step that is still off. */
    @Test fun autoNextKeepsGoingPastASkip() {
        val steps = full()
        val st = PowerPlan.status(steps, { it.kind == Kind.RUNTIME && it.id != "health" }, declined = setOf("health"))
        assertEquals(Status.DECLINED, st["health"])
        assertEquals("battery", PowerPlan.autoNext(steps, st, tried = setOf("health"))?.id)
    }

    /** A step that came back off but wasn't marked declined (Health Connect bouncing) isn't opened again in the same pass. */
    @Test fun autoNextNeverOpensAStepTwice() {
        val steps = full()
        val st = PowerPlan.status(steps, { it.kind == Kind.RUNTIME && it.id != "health" }, emptySet())
        assertEquals(Status.OFF, st["health"])
        assertEquals("health", PowerPlan.autoNext(steps, st, emptySet())?.id)
        assertEquals("battery", PowerPlan.autoNext(steps, st, setOf("health"))?.id)
    }

    /** Every step refused one after another: the pass still reaches the end, visiting each one once. */
    @Test fun autoPassVisitsEveryStepOnceWhenAllAreRefused() {
        val steps = full()
        val tried = mutableSetOf<String>()
        val declined = mutableSetOf<String>()
        val visited = mutableListOf<String>()
        while (true) {
            val st = PowerPlan.status(steps, { false }, declined)
            val next = PowerPlan.autoNext(steps, st, tried) ?: break
            visited += next.id
            tried += next.id
            declined += next.id
            assertTrue("looped on ${next.id}", visited.size <= steps.size)
        }
        // Every step but restricted settings, then restricted settings (shown once a service was refused).
        assertEquals(steps.map { it.id }, visited)
    }

    /** Every step turned on as it comes: the pass ends with nothing left. */
    @Test fun autoPassEndsWithEverythingOnWhenAllAreAllowed() {
        val steps = full()
        val on = mutableSetOf<String>()
        val tried = mutableSetOf<String>()
        while (true) {
            val st = PowerPlan.status(steps, { it.id in on }, emptySet())
            val next = PowerPlan.autoNext(steps, st, tried) ?: break
            tried += next.id
            on += next.id
        }
        val st = PowerPlan.status(steps, { it.id in on }, emptySet())
        assertTrue(st.values.all { it == Status.ON })
    }

    /** With ADB, Max Agentic Control runs only the grants still off; one Android won't report (null) runs again. */
    @Test fun adbPendingRunsWhatIsNotOn() {
        val checks = linkedMapOf<String, Boolean?>("restricted_settings" to null, "overlay" to true, "installs" to false, "battery" to true)
        assertEquals(setOf("restricted_settings", "installs"), PowerPlan.adbPending(checks))
        assertTrue(PowerPlan.adbPending(mapOf("installs" to true)).isEmpty())
    }
}
