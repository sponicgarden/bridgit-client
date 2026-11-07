package cc.bridgit.app.context

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.api.ApiError
import cc.bridgit.app.applets.AppletPackage
import cc.bridgit.app.platform.Capabilities
import cc.bridgit.app.platform.FlowSteps
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextVaultTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private val vault get() = app.platform.contextVault
    private val now get() = ZonedDateTime.parse("2026-11-05T23:00:00+01:00[Europe/Warsaw]")

    @Test fun temporaryExpiresButDurableSurvivesAndUnknownIsNull() {
        val fact = ContextFacts.fact("retail", "jev", .92, 100, 200)
        assertEquals("retail", ContextFacts.visible(fact, 199).getString("value"))
        assertTrue(ContextFacts.visible(fact, 200).isNull("value"))
        assertEquals("expired", ContextFacts.visible(fact, 200).getString("status"))
        assertTrue(ContextFacts.visible(null, 200).isNull("value"))
        vault.remember("preferred_language", "English")
        val later = vault.status(now.plusYears(1))
        assertEquals("English", later.getJSONObject("durable").getJSONObject("preferred_language").getString("value"))
        assertEquals("owner", later.getJSONObject("durable").getJSONObject("preferred_language").getString("source"))
        assertTrue(later.getJSONObject("temporary").getJSONObject("place").isNull("value"))
    }

    @Test fun nightAndPlaceAreIndependentAndClockIsExplicit() {
        val night = vault.status(now).getJSONObject("temporary")
        assertTrue(night.getJSONObject("night").getBoolean("value"))
        assertEquals("local_clock_22_07", night.getJSONObject("night").getString("source"))
        assertEquals("unknown", night.getJSONObject("place").getString("status"))
        assertFalse(vault.status(now.withHour(12)).getJSONObject("temporary").getJSONObject("night").getBoolean("value"))
        assertEquals("unknown", ContextFacts.choice("home" to .79).first)
        assertEquals("unknown", ContextFacts.choice("invented" to .99).first)
        assertEquals("retail", ContextFacts.choice("retail" to .9).first)
    }

    @Test fun ownerCanCorrectForgetAndClearWithoutLeavingCoordinates() {
        app.platform.store.setting("context:position", JSONObject().put("at", System.currentTimeMillis()).put("latitude", 52.2).put("longitude", 21.0).put("label", "Long address ".repeat(40)).toString(), true)
        assertTrue(vault.identifyPlace("home"))
        assertEquals(240, vault.durable().getJSONObject("home").getString("value").length)
        assertFalse(vault.status().toString().contains("latitude"))
        vault.remember("home", "A different home")
        assertFalse(JSONObject(app.platform.store.setting("context:anchors")).has("home"))
        vault.forget("home"); assertFalse(vault.durable().has("home"))
        repeat(32) { vault.remember("fact_$it", "value") }
        assertFalse(vault.identifyPlace("work"))
        vault.setEnabled(false); vault.clear()
        assertEquals(0, vault.durable().length())
        assertFalse(vault.enabled())
        assertFalse(JSONObject(app.platform.store.setting("context:position")).has("latitude"))
    }

    @Test fun deletingAutomaticItemsSurvivesReadsAndCanBeRestoredIndividually() {
        for (key in listOf("place", "night", "timezone")) vault.forgetTemporary(key)
        val temporary = vault.status(now).getJSONObject("temporary")
        for (key in listOf("place", "night", "timezone")) {
            assertTrue(vault.deletedTemporary(key))
            assertTrue(temporary.getJSONObject(key).isNull("value"))
            assertEquals("deleted", temporary.getJSONObject(key).getString("status"))
        }
        assertTrue(vault.enabled())
        val reopened = ContextVault(app)
        assertTrue(reopened.deletedTemporary("night"))
        assertTrue(reopened.status(now).getJSONObject("temporary").getJSONObject("night").isNull("value"))
        vault.restoreTemporary("night")
        assertTrue(vault.status(now).getJSONObject("temporary").getJSONObject("night").getBoolean("value"))
        assertTrue(vault.status(now).getJSONObject("temporary").getJSONObject("timezone").isNull("value"))
    }

    @Test fun pausePreservesFactsAndDeleteAllPreservesCollectionChoice() {
        vault.remember("language", "English")
        vault.setEnabled(false)
        assertEquals("English", vault.status(now).getJSONObject("durable").getJSONObject("language").getString("value"))
        vault.clear()
        assertFalse(vault.enabled())
        vault.setEnabled(true)
        vault.remember("language", "Polish")
        vault.clear()
        assertTrue(vault.enabled())
        assertEquals(0, vault.durable().length())
        for (key in listOf("place", "night", "timezone")) {
            assertTrue(vault.status(now).getJSONObject("temporary").getJSONObject(key).isNull("value"))
        }
    }

    @Test fun manualTemporaryFactsExpireAndCanMoveBetweenCategories() {
        vault.remember("trip", "Warsaw", temporary = true, lifetimeMillis = 60 * 60_000L)
        val raw = vault.ownerTemporary().getJSONObject("trip")
        val expires = raw.getLong("expires_at")
        val before = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(expires - 1), java.time.ZoneOffset.UTC)
        val after = before.plusNanos(1_000_000)
        assertEquals("Warsaw", vault.status(before).getJSONObject("temporary").getJSONObject("trip").getString("value"))
        assertEquals("temporary", vault.status(before).getJSONObject("temporary").getJSONObject("trip").getString("lifetime"))
        val expired = vault.status(after).getJSONObject("temporary").getJSONObject("trip")
        assertTrue(expired.isNull("value")); assertEquals("expired", expired.getString("status"))
        assertFalse(vault.durable().has("trip"))
        vault.setEnabled(false)
        assertEquals("Warsaw", vault.ownerTemporary().getJSONObject("trip").getString("value"))
        vault.remember("trip", "Berlin")
        assertFalse(vault.ownerTemporary().has("trip"))
        assertEquals("durable", vault.status(before).getJSONObject("durable").getJSONObject("trip").getString("lifetime"))
        vault.remember("trip", "Paris", temporary = true)
        assertFalse(vault.durable().has("trip"))
        vault.forgetOwnerTemporary("trip")
        assertFalse(vault.ownerTemporary().has("trip"))
        vault.remember("trip", "Rome", temporary = true)
        vault.clear()
        assertEquals(0, vault.ownerTemporary().length())
    }

    @Test fun lifetimeValidationAndCapacityDoNotLoseSavedFacts() {
        vault.remember("trip", "Warsaw")
        try { vault.remember("trip", "Berlin", temporary = true, lifetimeMillis = -1); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals("Warsaw", vault.durable().getJSONObject("trip").getString("value"))
        for (key in listOf("place", "night", "timezone")) {
            try { vault.remember(key, "override", temporary = true); fail() }
            catch (_: IllegalArgumentException) { }
        }
        repeat(31) { vault.remember("fact_$it", "value", temporary = it % 2 == 0) }
        try { vault.remember("overflow", "value", temporary = true); fail() }
        catch (_: IllegalArgumentException) { }
        vault.remember("trip", "Berlin", temporary = true)
        assertEquals("Berlin", vault.ownerTemporary().getJSONObject("trip").getString("value"))
        assertEquals(32, vault.durable().length() + vault.ownerTemporary().length())
    }

    @Test fun manualRefreshReportsBlockersAndDoesNotChangeSavedFacts() = runBlocking {
        vault.remember("language", "English")
        vault.setEnabled(false)
        assertEquals(ContextVault.RefreshResult.PAUSED, vault.refresh(force = true))
        vault.setEnabled(true)
        vault.forgetTemporary("place")
        assertEquals(ContextVault.RefreshResult.DELETED, vault.refresh(force = true))
        vault.restoreTemporary("place")
        app.settings.listenerEnabled = false
        assertEquals(ContextVault.RefreshResult.LISTENER_OFF, vault.refresh(force = true))
        app.settings.listenerEnabled = true
        app.settings.setOn("location", false)
        assertEquals(ContextVault.RefreshResult.FEATURE_OFF, vault.refresh(force = true))
        assertEquals("English", vault.durable().getJSONObject("language").getString("value"))
    }

    @Test fun manualRefreshWaitsForOngoingWorkAndRespectsOwnerChanges() = runBlocking {
        val field = ContextVault::class.java.getDeclaredField("lock").apply { isAccessible = true }
        val mutex = field.get(vault) as kotlinx.coroutines.sync.Mutex
        mutex.lock()
        val result = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { vault.refresh(force = true) }
        assertFalse(result.isCompleted)
        vault.setEnabled(false)
        mutex.unlock()
        assertEquals(ContextVault.RefreshResult.SUPERSEDED, result.await())
    }

    @Test @Config(sdk = [29], application = android.app.Application::class) fun explicitLocationRefreshIgnoresCachedFixAndCleansUpAfterFreshFix() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = context.getSystemService(android.location.LocationManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        shadow.setProviderEnabled("gps", true)
        val reader = cc.bridgit.app.capability.LocationReader(context)
        val start = android.os.SystemClock.elapsedRealtimeNanos()
        val result = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { reader.freshAfterRequest(true, 5_000) }
        assertFalse(shadow.getLocationUpdateListeners("gps").isEmpty())
        fun fix(at: Long) = android.location.Location("gps").apply {
            latitude = 52.2; longitude = 21.0; accuracy = 10f
            time = System.currentTimeMillis(); elapsedRealtimeNanos = at
        }
        shadow.simulateLocation(fix(start - 1))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(result.isCompleted)
        android.os.SystemClock.sleep(1)
        val fresh = fix(android.os.SystemClock.elapsedRealtimeNanos())
        shadow.simulateLocation(fresh)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(fresh.elapsedRealtimeNanos, result.await()!!.elapsedRealtimeNanos)
        assertTrue(shadow.requestLocationUpdateListeners.isEmpty())
    }

    @Test fun explicitLocationRefreshCleansUpAfterCancellationAndTimeout() = runBlocking {
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = app.getSystemService(android.location.LocationManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        shadow.setProviderEnabled("gps", true)
        val reader = cc.bridgit.app.capability.LocationReader(app)
        val result = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { reader.freshAfterRequest(true, 5_000) }
        assertFalse(shadow.getLocationUpdateListeners("gps").isEmpty())
        result.cancelAndJoin()
        assertTrue(shadow.requestLocationUpdateListeners.isEmpty())
        assertNull(reader.freshAfterRequest(true, 1))
        assertTrue(shadow.requestLocationUpdateListeners.isEmpty())
    }

    @Test fun categoryMoveAndClearRollbackOnWriteFailure() {
        vault.remember("trip", "Warsaw")
        val db = app.platform.store.writableDatabase
        db.execSQL("CREATE TEMP TRIGGER fail_temporary BEFORE INSERT ON settings WHEN NEW.key='context:temporary' BEGIN SELECT RAISE(ABORT, 'test write failure'); END")
        try {
            try { vault.remember("trip", "Berlin", temporary = true); fail() }
            catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals("Warsaw", vault.durable().getJSONObject("trip").getString("value"))
            assertFalse(vault.ownerTemporary().has("trip"))
        } finally { db.execSQL("DROP TRIGGER fail_temporary") }
        vault.remember("meeting", "Today", temporary = true)
        db.execSQL("CREATE TEMP TRIGGER fail_deleted BEFORE INSERT ON settings WHEN NEW.key='context:deleted' BEGIN SELECT RAISE(ABORT, 'test write failure'); END")
        try {
            try { vault.clear(); fail() } catch (_: android.database.sqlite.SQLiteException) { }
            assertTrue(vault.durable().has("trip"))
            assertTrue(vault.ownerTemporary().has("meeting"))
            assertFalse(vault.deletedTemporary("place"))
        } finally { db.execSQL("DROP TRIGGER fail_deleted") }
        vault.remember("home", "Hotel", temporary = true)
        app.platform.store.setting("context:position", JSONObject().put("at", System.currentTimeMillis()).put("latitude", 52.2).put("longitude", 21.0).toString(), true)
        db.execSQL("CREATE TEMP TRIGGER fail_anchors BEFORE INSERT ON settings WHEN NEW.key='context:anchors' BEGIN SELECT RAISE(ABORT, 'test write failure'); END")
        try {
            try { vault.identifyPlace("home"); fail() } catch (_: android.database.sqlite.SQLiteException) { }
            assertTrue(vault.ownerTemporary().has("home"))
            assertFalse(vault.durable().has("home"))
        } finally { db.execSQL("DROP TRIGGER fail_anchors") }
    }

    @Test fun approximatePermissionHasActionableManualRefreshResult() = runBlocking {
        app.settings.listenerEnabled = true
        app.settings.setOn("location", true)
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(ContextVault.RefreshResult.PRECISE_LOCATION_REQUIRED, vault.refresh(force = true))
    }

    @Test fun freshNetworkFixCanSucceedIndoorsWhileGpsIsStillWaiting() = runBlocking {
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val manager = app.getSystemService(android.location.LocationManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        shadow.setProviderEnabled("gps", true); shadow.setProviderEnabled("network", true)
        val reader = cc.bridgit.app.capability.LocationReader(app)
        val result = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { reader.freshAfterRequest(true, 5_000) }
        android.os.SystemClock.sleep(1)
        fun network(accuracyMeters: Float) = android.location.Location("network").apply {
            latitude = 52.2; longitude = 21.0; accuracy = accuracyMeters
            time = System.currentTimeMillis(); elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        }
        shadow.simulateLocation(network(1_500f))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(result.isCompleted)
        android.os.SystemClock.sleep(1)
        shadow.simulateLocation(network(25f))
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("network", result.await()!!.provider)
        assertTrue(shadow.requestLocationUpdateListeners.isEmpty())
    }

    @Test fun statusNeedsExplicitPermissionAndFlowStepDerivesIt() = runBlocking {
        app.settings.listenerEnabled = true
        val agent = app.policy.addAgent("Context reader").first.id
        Capabilities.setEnabled(app, "context.read", true)
        try { app.platform.read(agent, "context.status", JSONObject()); fail() }
        catch (e: ApiError) { assertEquals("agent_not_allowed", e.code) }
        app.platform.grant(agent, "context.read", Long.MAX_VALUE)
        assertEquals("this_device", app.platform.read(agent, "context.status", JSONObject()).getString("scope"))
        val steps = JSONArray().put(JSONObject().put("id", "ctx").put("type", "context"))
        assertEquals(setOf("context.read"), FlowSteps.check(steps).scopes)
        app.platform.revoke(agent, "context.read")
        try { app.platform.read(agent, "context.status", JSONObject()); fail() }
        catch (e: ApiError) { assertEquals("agent_not_allowed", e.code) }
    }

    @Test fun approvedAppletGetsOnlyItsDeclaredContextScopeAndCanRunContextStep() = runBlocking {
        app.settings.listenerEnabled = true
        Capabilities.setEnabled(app, "context.read", true)
        Capabilities.setEnabled(app, "workflows", true)
        app.registry.switchable().forEach { app.settings.setOn(it.switchKey!!, true) }
        runCatching { androidx.work.WorkManager.getInstance(app) }.onFailure { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        val d = JSONObject(File("../site-content/public/recipes/voice-notebook/applet.json").readText())
        d.put("id", "context-reader").put("name", "Context reader")
        d.put("processing", JSONObject().put("workflow", JSONObject().put("name", "Read context")
            .put("trigger", JSONObject().put("type", "button"))
            .put("steps", JSONArray().put(JSONObject().put("id", "ctx").put("type", "context")))))
        d.put("ui", JSONObject().put("card", JSONObject().put("title", "Context")
            .put("pieces", JSONArray().put(JSONObject().put("type", "text").put("text", "Context reader")))))
        d.put("permissions", JSONObject().put("required", JSONArray(listOf("interactions", "workflows", "context.read"))).put("optional", JSONArray()).put("remote", JSONArray()).put("automatic_grants", false))
        val p = AppletPackage.check(d)
        val pending = app.applets.propose(p, null, JSONObject().put("kind", "link").put("url", "https://bridgit.cc/test/context"), null, notify = false)
        val installed = app.applets.approve(pending.getString("id"), null)
        val agent = installed.getString("agent_id")
        assertTrue(app.platform.hasGrant(agent, "context.read"))
        assertFalse(app.platform.hasGrant(agent, "location"))
        val id = installed.getJSONObject("flows").getJSONObject("main").getString("workflow_id")
        val result = app.platform.workflows.runNow(agent, id, null)
        assertEquals("completed", result.getString("status"))
        assertEquals("this_device", app.platform.read(agent, "context.status", JSONObject()).getString("scope"))
        app.applets.remove(installed.getString("id"))
        assertFalse(app.platform.hasGrant(agent, "context.read"))
    }
    @Test fun cardOnlyReaderDeclaresBothPermissionsAndChecksScopeBeforeInstallation() = runBlocking {
        app.settings.listenerEnabled = true
        app.registry.switchable().forEach { app.settings.setOn(it.switchKey!!, true) }
        runCatching { androidx.work.WorkManager.getInstance(app) }.onFailure { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        val d = JSONObject(File("../site-content/public/recipes/voice-notebook/applet.json").readText())
        d.put("id", "context-card").put("processing", JSONObject())
        d.put("ui", JSONObject().put("card", JSONObject().put("title", "Context")
            .put("sources", JSONObject().put("ctx", JSONObject().put("call", JSONObject().put("method", "GET").put("path", "/v1/context"))))
            .put("pieces", JSONArray().put(JSONObject().put("type", "text").put("text", "Context")))))
        d.put("permissions", JSONObject().put("required", JSONArray(listOf("interactions", "context", "context.read"))).put("optional", JSONArray()).put("remote", JSONArray()).put("automatic_grants", false))
        val p = AppletPackage.check(d)
        Capabilities.setEnabled(app, "context.read", false)
        val pending = app.applets.propose(p, null, JSONObject().put("kind", "link").put("url", "https://bridgit.cc/test/context"), null, notify = false)
        assertTrue(pending.getJSONObject("review").getJSONArray("blockers").toString().contains("Read Automated Context"))
        Capabilities.setEnabled(app, "context.read", true)
        val installed = app.applets.approve(pending.getString("id"), null)
        assertTrue(app.platform.hasGrant(installed.getString("agent_id"), "context.read"))
        app.applets.remove(installed.getString("id"))
        Unit
    }

}
