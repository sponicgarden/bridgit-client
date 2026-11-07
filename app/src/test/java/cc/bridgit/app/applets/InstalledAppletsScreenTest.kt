package cc.bridgit.app.applets

import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import cc.bridgit.app.BridgeApp
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** The Installed applets card and the budget editor (design pass docs/design/installed-applets.md). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstalledAppletsScreenTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private val sent = mutableListOf<Pair<String, String>>()

    private val download = AppletFetch.download
    @Before fun phoneReady() {
        AppletFetch.download = ::fixtureRegistry
        app.settings.listenerEnabled = true
        for (m in app.registry.switchable()) app.settings.setOn(m.switchKey!!, true)
        shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
        runCatching { androidx.work.WorkManager.getInstance(app) }.onFailure { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        app.budgetSync.transport = { m, p, _ -> sent += m to p; if (p == BudgetSync.SYNC_PATH) 404 to JSONObject() else 200 to JSONObject() }
    }

    @After fun cleanUp() = runBlocking {
        AppletFetch.download = download
        app.budgetSync.transport = null
        for (r in app.applets.all()) app.applets.remove(r.getString("id"))
    }

    private fun install(id: String? = null, name: String? = null): JSONObject = runBlocking {
        val d = JSONObject(File("src/test/resources/applets/pet-monitor-2.0.0.json").readText())
        id?.let { d.put("id", it) }
        name?.let { d.put("name", it) }
        val link = JSONObject().put("kind", "link").put("url", "https://bridgit.cc/recipes/pet-monitor/applet.json")
        val pend = app.applets.propose(AppletPackage.check(d), null, link, null, notify = false)
        app.applets.approve(pend.getString("id"), null)
    }

    /** The save runs its network part off the main thread: idle the main looper until [ok] or 5 s. */
    private fun waitFor(ok: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); if (ok()) return true; Thread.sleep(10) }
        return ok()
    }

    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    private fun texts(root: View): List<String> = descendants(root).flatMap { v ->
        listOfNotNull((v as? TextView)?.text?.toString(), v.contentDescription?.toString()) }.filter { it.isNotBlank() }

    @Test fun managingOneAppletNeverShowsAnotherAppletsControls() {
        install("kitchen-cleanliness", "Kitchen cleanliness monitor")
        val chef = install("chef-helper", "Chef’s Helper")
        val a = Robolectric.buildActivity(AppletInstallActivity::class.java,
            Intent(app, AppletInstallActivity::class.java).putExtra(AppletInstallActivity.EXTRA_INSTANCE, chef.getString("id"))).setup().get()
        val all = texts(a.window.decorView)
        assertTrue(all.contains("Chef’s Helper"))
        assertFalse(all.contains("Kitchen cleanliness monitor"))
        assertFalse(all.contains("Available"))
    }

    @Test fun chefSetupCreatesItsOwnReviewAndKeepsTheKitchenMonitor() {
        val kitchen = install("kitchen-cleanliness", "Kitchen cleanliness monitor")
        val a = Robolectric.buildActivity(AppletInstallActivity::class.java).setup().get()
        descendants(a.window.decorView).filterIsInstance<MaterialButton>().single { it.text == "Browse applet library" }.performClick()
        assertTrue(waitFor { texts(a.window.decorView).contains("Chef’s Helper") })
        val setup = descendants(a.window.decorView).filterIsInstance<MaterialButton>()
            .single { it.contentDescription?.toString() == "Set up Chef’s Helper" }
        setup.performClick()
        assertTrue(waitFor { app.applets.pending().any { it.getJSONObject("definition").optString("id") == "chef-helper" } })
        val chef = app.applets.pending().single { it.getJSONObject("definition").optString("id") == "chef-helper" }
        assertTrue(chef.isNull("replaces"))
        assertEquals("tpl_chef", chef.getJSONObject("source").getString("template"))
        assertEquals(2, chef.getJSONObject("source").getInt("revision"))
        assertEquals("Kitchen cleanliness monitor", app.applets.get(kitchen.getString("id"))!!.getString("name"))
        assertEquals(listOf("kitchen-cleanliness"), app.applets.all().map { it.getString("package_id") })
        assertTrue(waitFor { texts(a.window.decorView).any { it.contains("Chef") } })
        app.applets.pendingDrop(chef.getString("id"))
    }

    @Test fun theCardReadsInWordsWithoutIds() {
        val r = install()
        AppletBudget.saveSynced(app, "pet-monitor", JSONObject().put("applet", "pet-monitor").put("state", "warning").put("synced_at", System.currentTimeMillis())
            .put("agents_may_change", true).put("ceiling", JSONObject().put("l2", 20))
            .put("limits", JSONArray().put(JSONObject().put("id", "l1").put("usd", 1).put("per", "day").put("used_usd", 0.2).put("forecast_usd", 0.9))
                .put(JSONObject().put("id", "l2").put("usd", 10).put("per", "month").put("used_usd", 3.0).put("forecast_usd", 12.0).put("estimate", true)))
            .put("actions", JSONArray().put(JSONObject().put("action", "main").put("every_s", 600).put("min_s", 300).put("max_s", 86_400).put("places", 2)
                .put("projected_usd", JSONObject().put("l1", 0.05).put("l2", 1.5))))
            .put("recent_changes", JSONArray()
                .put(JSONObject().put("id", 1).put("at", "2026-11-05T10:00:00+01:00").put("by_kind", "owner").put("by_name", "Owner").put("summary", "Raised the daily limit from $1 to $2"))
                .put(JSONObject().put("id", 2).put("at", "2026-11-05T11:00:00+01:00").put("by_kind", "agent").put("by_name", "Claude").put("summary", "Changed main from every 10 min to every 20 min"))))
        val activity = Robolectric.buildActivity(AppletInstallActivity::class.java, Intent(app, AppletInstallActivity::class.java)).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        val summary = texts(activity.window.decorView)
        assertTrue(summary.contains("Browse applet library"))
        assertFalse(summary.contains("Activity"))
        assertFalse(summary.contains("Forecast: $12.00 · estimate"))
        assertTrue(summary.contains("May run out before the period ends"))
        descendants(activity.window.decorView).filterIsInstance<MaterialButton>().single { it.text == "Details" }.performClick()
        val all = texts(activity.window.decorView)
        val joined = all.joinToString("\n")
        for (want in listOf("Installed applets", "Activity", "Budget", "Schedule", "Recent budget changes", "Web portal", "Includes all devices working for this applet",
                "Today", "This month", "$0.20 of $1.00 used", "$3.00 of $10.00 used", "Forecast: $12.00 · estimate", "May run out before the period ends",
                "Control agents can change this budget.", "Control agent maximum: $100.00 a day · $20.00 a month.", "Every 10 minutes", "2 places",
                "Allowed: 5 minutes–1 day · About $0.05 a day", "Pet activity samples", "Pause", "More options for Pet activity monitor",
                "Pause Pet activity monitor", "You", "Changed Pet activity samples from every 10 min to every 20 min")) {
            assertTrue("missing \"$want\" in:\n$joined", all.any { it == want || it.startsWith("$want ·") || it.startsWith(want) })
        }
        val watchId = r.getJSONObject("watches").getJSONObject("main").getString("watch_id")
        for (raw in listOf("\\bmain\\b", "\\bl1\\b", "\\bl2\\b", "\\bowner\\b", "\\bpackage\\b", Regex.escape(watchId), "(?i)phone")) {
            assertFalse("raw \"$raw\" shown in:\n$joined", Regex(raw).containsMatchIn(joined))
        }
        // The controls come right after the name and state, before the sections (no floating button rows).
        val order = all.filter { it in setOf("Pet activity monitor", "Pause", "Activity", "Budget") }.distinct()
        assertEquals(listOf("Pet activity monitor", "Pause", "Activity", "Budget"), order)
        descendants(activity.window.decorView).filterIsInstance<MaterialButton>().single { it.text == "Hide details" }.performClick()
        assertFalse(texts(activity.window.decorView).contains("Activity"))
    }

    @Test fun flowHistoryShowsAMissedCheckAndLastSuccessAlongsideConfirmedModelAndDeliveryResults() {
        val installed = install("dinner-check", "Dinner monitor")
        val agent = installed.getString("agent_id")
        val store = app.platform.store
        fun flow(name: String) = store.save(agent, "workflow",
            JSONObject().put("definition", JSONObject().put("name", name))).getString("id")
        val monitor = flow("Check the bowl")
        val report = flow("Report dinner")
        val recipient = store.save("owner", "destination",
            JSONObject().put("name", "Kitchen display").put("url", "https://private.example/auth-secret")).getString("id")
        val delivery = store.save(agent, "outbox", JSONObject().put("destination_id", recipient)
            .put("status", "accepted").put("payload", "private message")).getString("id")
        val alert = store.save(agent, "alert", JSONObject().put("from_workflow", true).put("status", "queued")
            .put("destinations", JSONArray().put(recipient))).getString("id")
        store.save(agent, "outbox", JSONObject()
            .put("id", cc.bridgit.app.platform.DeliveryManager.workflowAlertDeliveryId(alert, recipient))
            .put("status", "unknown_outcome").put("destination_id", recipient))
        fun run(flowId: String, at: Long, status: String, steps: JSONArray, error: String? = null) {
            store.save(agent, "execution", JSONObject().put("workflow_id", flowId).put("started_at", at)
                .put("status", status).put("steps", steps).put("error", error))
        }
        val now = System.currentTimeMillis()
        run(monitor, now - 600_000L, "completed", JSONArray())
        run(report, now - 300_000L, "completed", JSONArray()
            .put(JSONObject("""{"type":"ask_model","status":"ok","result":{"answers":{"eating":"yes"}}}"""))
            .put(JSONObject().put("type","send").put("status","ok").put("result",JSONObject().put("delivery_id",delivery)))
            .put(JSONObject().put("type","alert").put("status","ok").put("result",JSONObject().put("alert_id",alert))))
        // The list orders by record creation time, as it would between real scheduled checks.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        run(monitor, now, "failed", JSONArray()
            .put(JSONObject("""{"type":"photo","camera":"rear","status":"failed","error":"camera_unavailable"}""")),
            "camera_unavailable")
        val activity = Robolectric.buildActivity(AppletInstallActivity::class.java,
            Intent(app, AppletInstallActivity::class.java).putExtra(AppletInstallActivity.EXTRA_INSTANCE, installed.getString("id"))).setup().get()
        val summary = texts(activity.window.decorView)
        assertTrue(summary.any { it.startsWith("Latest run · Missed check · Camera unavailable · ") })
        assertFalse(summary.any { it.contains("Kitchen display") })
        descendants(activity.window.decorView).filterIsInstance<MaterialButton>().single { it.text == "Details" }.performClick()
        val detail = texts(activity.window.decorView)
        assertTrue(detail.any { it.startsWith("Last completed · ") })
        assertTrue(detail.contains("Rear camera · Camera unavailable"))
        assertTrue(detail.contains("Model check · eating: yes"))
        assertTrue(detail.contains("Send · Kitchen display · Received"))
        assertTrue(detail.contains("Alert · Kitchen display · Delivery unconfirmed"))
        assertFalse(detail.any { it.contains("private message") || it.contains("auth-secret") || it.contains(recipient) })
    }

    private fun editor(r: JSONObject) = Robolectric.buildActivity(BudgetEditorActivity::class.java,
        Intent(app, BudgetEditorActivity::class.java).putExtra(BudgetEditorActivity.EXTRA_INSTANCE, r.getString("id"))).setup()

    private fun fields(a: BudgetEditorActivity) = descendants(a.window.decorView).filterIsInstance<TextInputLayout>()
    private fun button(a: BudgetEditorActivity, label: String) = descendants(a.window.decorView).filterIsInstance<MaterialButton>().single { it.text.toString() == label }

    @Test fun invalidAmountsKeepTheEditorOpenWithAnInlineError() {
        val r = install()
        val a = editor(r).get()
        val all = texts(a.window.decorView)
        assertTrue(all.contains("Spending limit")); assertTrue(all.contains("Let control agents change this limit"))
        assertTrue(all.contains("Leave blank to allow up to $100.00."))
        assertFalse(all.joinToString().contains("phone", ignoreCase = true))
        val f = fields(a)
        assertEquals(listOf("Daily limit", "Monthly limit", "Daily control agent maximum", "Monthly control agent maximum"), f.map { it.hint.toString() })
        for (bad in listOf("", "0", "abc", "250")) {
            f[0].editText!!.setText(bad)
            button(a, "Save").performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("closed on \"$bad\"", a.isFinishing)
            assertEquals("Enter an amount from $0.01 to $100.00.", f[0].error?.toString())
        }
        // A bad agent maximum is an error too, never turned into a blank.
        f[0].editText!!.setText("2")
        f[2].editText!!.setText("x")
        button(a, "Save").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(a.isFinishing)
        assertEquals("Enter an amount from $0.01 to $100.00.", f[2].error?.toString())
        assertTrue(sent.none { it.second.contains("/limits") || it.second.contains("/ceiling") })
        // Fixed, it saves through the old paths and says so.
        f[2].editText!!.setText("")
        f[0].editText!!.setText("2,5")
        button(a, "Save").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(waitFor { a.isFinishing })
        assertEquals("Budget saved.", shadowOf(a).resultIntent.getStringExtra(BudgetEditorActivity.EXTRA_MESSAGE))
        assertTrue(sent.any { it == "PUT" to "/v1/budgets/pet-monitor/limits" })
        assertEquals(2.5, AppletBudget.limitsOf(null, app.applets.get(r.getString("id"))!!).first { it.per == "day" }.usd, 1e-9)
    }

    @Test fun resetAsksFirstAndFillsTheAppletsOwnLimits() {
        val r = install()
        runBlocking { app.applets.setBudget(r.getString("id"), listOf(AppletBudget.Limit("l1", 4.0, "day"), AppletBudget.Limit("l2", 30.0, "month"))) }
        val a = editor(r).get()
        assertEquals("4", fields(a)[0].editText!!.text.toString())
        button(a, "Reset to the applet's default").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val d = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("Reset to the applet's default?", d.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString())
        assertEquals("Reset limits", d.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
        d.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("the reset only fills the editor", a.isFinishing)
        assertEquals(listOf("1", "10"), fields(a).take(2).map { it.editText!!.text.toString() })
        // Back with the unsaved reset asks before discarding.
        a.onBackPressedDispatcher.onBackPressed()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(a.isFinishing)
        val discard = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("Discard changes?", discard.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text.toString())
        discard.dismiss()
        // Save takes the old "use the applet's own" path: the owner's override goes.
        button(a, "Save").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(waitFor { a.isFinishing })
        assertFalse(app.applets.get(r.getString("id"))!!.has("budget_owner"))
    }
}
