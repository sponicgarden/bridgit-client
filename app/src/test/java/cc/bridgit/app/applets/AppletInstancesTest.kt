package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.core.Connection
import cc.bridgit.app.watch.WatchTally
import cc.bridgit.app.interact.IStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** One approval installs every part under the applet's own agent; pause, update and remove act on all of them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppletInstancesTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private fun pkg(id: String, edit: (JSONObject) -> Unit = {}) =
        AppletPackage.check(JSONObject(File("../site-content/public/recipes/$id/applet.json").readText()).also(edit))
    private val link = JSONObject().put("kind", "link").put("url", "https://bridgit.cc/recipes/room-change-watcher/applet.json")

    @Test fun namedCameraPositionsArePrivateAndRequireTheLastRevision() {
        val views=app.cameraViews
        val saved=JSONObject().put("camera","lan:test").put("profile","hidden-profile")
            .put("enrollment","secret").put("x",0.2).put("y",0.3)
        views.replace("one","bowl",null,saved)
        val status=views.status("one","bowl")
        assertFalse(status.has("profile"))
        assertFalse(status.has("enrollment"))
        assertFalse(views.status("two","bowl").getBoolean("saved"))
        val rev=status.getString("revision")
        views.replace("one","bowl",rev,saved)
        try { views.replace("one","bowl",rev,null); fail("Stale revision removed a newer position") }
        catch (_: cc.bridgit.app.api.ApiError) { }
        views.replace("two","bowl",null,saved)
        views.removeAgent("one")
        assertFalse(views.status("one","bowl").getBoolean("saved"))
        assertTrue(views.status("two","bowl").getBoolean("saved"))
    }

    @Before fun phoneReady() {
        app.settings.listenerEnabled = true
        for (m in app.registry.switchable()) app.settings.setOn(m.switchKey!!, true)
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        runCatching { androidx.work.WorkManager.getInstance(app) }.onFailure { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
    }

    @After fun cleanUp() = runBlocking { for (r in app.applets.all()) app.applets.remove(r.getString("id")); app.applets.pending().forEach { app.applets.pendingDrop(it.getString("id")) } }

    private fun install(p: AppletPackage.Package, config: JSONObject? = null): JSONObject = runBlocking {
        val pend = app.applets.propose(p, config, link, null, notify = false)
        app.applets.approve(pend.getString("id"), null)
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun detachedImagesAreVerifiedBeforeActivationAndReopenOffline() = runBlocking {
        val base = File("../site-content/public/samples/sample-photo.jpg").readBytes()
        val comment = byteArrayOf(-1,-2,-1,-1) + ByteArray(65533)
        val bytes = base.copyOfRange(0,2) + comment + comment + base.copyOfRange(2,base.size)
        assertTrue(bytes.size > AppletPackage.MAX_BYTES)
        val asset = JSONObject().put("storage","revision").put("media_type","image/jpeg")
            .put("size",bytes.size).put("sha256",AppletPackage.sha256(bytes))
        val def = pkg("room-change-watcher") { it.put("assets",JSONObject().put("manual.jpg",asset)) }
        val source = JSONObject().put("kind","template").put("template","room-change-watcher").put("revision",1)
        val root = File(app.cacheDir,"test-package-media")
        var payload = bytes.copyOf(100)
        app.applets.packageMedia = AppletMedia(root) { url, limit, out ->
            assertTrue(url.matches(Regex("https://[^/]+/v1/templates/room-change-watcher/[12]/assets/manual[.]jpg")))
            assertEquals(bytes.size,limit)
            out.writeBytes(payload)
        }
        val pending = app.applets.propose(def,null,source,null,notify=false)
        try { app.applets.approve(pending.getString("id"),null); fail("Truncated media activated") } catch (_: AppletFetch.Failed) {}
        assertNull(app.applets.byPackage(def.id))
        payload = bytes
        val installed = app.applets.approve(pending.getString("id"),null)
        val offline = AppletMedia(root) { _,_,_ -> error("Offline") }
        assertArrayEquals(bytes,offline.read(installed,"manual.jpg").readBytes())
        val next = JSONObject(def.definition.toString()).put("version","9.0.0")
        next.getJSONObject("assets").getJSONObject("manual.jpg").put("sha256","0".repeat(64))
        val pendingUpdate = app.applets.propose(AppletPackage.check(next),null,JSONObject(source.toString()).put("revision",2),null,installed.getString("id"),notify=false)
        try { app.applets.approve(pendingUpdate.getString("id"),null); fail("Corrupt update activated") } catch (_: AppletFetch.Failed) {}
        val current = app.applets.get(installed.getString("id"))!!
        assertEquals(installed.getString("sha256"),current.getString("sha256"))
        assertArrayEquals(bytes,offline.read(current,"manual.jpg").readBytes())
        app.applets.remove(current.getString("id"))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun legacyDogExportIncludesEpisodesOlderThanTheReadingBuffer() = runBlocking {
        val packageText = File("../applets/dog-monitor/editions/1.5.0/applet.json").readText()
        val installed = install(AppletPackage.parse(packageText))
        app.applets.setPaused(installed.getString("id"), true)
        val start = System.currentTimeMillis() - 2_000_000
        repeat(70) { i ->
            app.dog.tracker.add(cc.bridgit.app.dog.Reading(start + i * 10_000L, true, 1.0,
                if (i == 0) "eating" else "resting", 1.0, null, 0.0))
        }
        val before = app.dog.tracker.toJson().toString()
        val result = AppletJsonExport.legacyDog(app, installed.getString("id"))
        val archive = JSONObject(result.file.readText())
        val history = archive.getJSONObject("tracker")
        assertEquals("bridgit-dog-monitor-history/1", archive.getString("format"))
        assertEquals(installed.getString("id"), archive.getString("instance_id"))
        assertEquals(60, history.getJSONArray("readings").length())
        assertEquals(start, history.getJSONArray("episodes").getJSONObject(0).getLong("start"))
        assertEquals("eating", history.getJSONArray("episodes").getJSONObject(0).getString("kind"))
        assertEquals(before, history.toString())
        assertEquals(before, app.dog.tracker.toJson().toString())
        assertTrue(archive.getJSONObject("settings").has("feeding"))
        assertFalse(app.dog.state.value.running)

        // A private archive follows the destination instance, independently of its new package agent.
        val bytes = result.file.readBytes()
        app.applets.remove(installed.getString("id"))
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val service = org.robolectric.Robolectric.buildService(cc.bridgit.app.BridgeService::class.java,
            android.content.Intent(app, cc.bridgit.app.BridgeService::class.java)).create().startCommand(0,1)
        try {
            val candidate = File("../applets/dog-monitor/applet.json").readText()
            val portable = install(AppletPackage.parse(candidate))
            assertTrue(app.watches.list(portable.getString("agent_id")).all { it.userPaused })
            val target = portable.getString("id")
            app.applets.importDogHistory(target, bytes.inputStream())
            app.applets.importDogHistory(target, bytes.inputStream())
            assertEquals(before, app.applets.dogHistoryArchive(target)!!.getJSONObject("tracker").toString())
            val different = JSONObject(archive.toString()).put("exported_at", 1).toString().toByteArray()
            try { app.applets.importDogHistory(target, different.inputStream()); fail("Replaced imported history") }
            catch (_: IllegalArgumentException) {}
            AppletHistoryArchive(app).use { reopened ->
                assertEquals(archive.toString(), reopened.read(target)!!.toString())
            }
            app.applets.remove(target)
            AppletHistoryArchive(app).use { reopened -> assertNull(reopened.read(target)) }
        } finally { service.destroy() }
    }

    @Test fun dogConversionWaitsForReviewAndPreservesIdentityHistoryAndBudget() = runBlocking {
        val legacy = install(AppletPackage.parse(File("../applets/dog-monitor/editions/1.5.0/applet.json").readText()), JSONObject().put("interval_ms", 90100))
        val id = legacy.getString("id")
        app.dog.setCameras(listOf("front", "rear"))
        val at = System.currentTimeMillis() - 50_000
        app.dog.tracker.add(cc.bridgit.app.dog.Reading(at,true,1.0,"drinking",1.0,null,0.0))
        val previous = app.dog.tracker.toJson().toString()
        val candidate = AppletPackage.parse(File("../applets/dog-monitor/applet.json").readText())
        // An interrupted prior approval allocated a principal before replacing the installed record.
        val abandoned = app.agents.add("Interrupted migration",Connection.APPLET).first
        val marker = JSONObject().put("agent",abandoned.id).put("running",false)
        app.getSharedPreferences("applet_dog_conversions",android.content.Context.MODE_PRIVATE).edit()
            .putString(id,marker.toString()).commit()
        val proposal = app.applets.update(id,candidate,null,link,null)
        assertEquals("awaiting_owner",proposal.getString("status"))
        assertEquals("dog",app.applets.get(id)!!.getString("native"))
        assertNull(app.applets.dogHistoryArchive(id))
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val service = org.robolectric.Robolectric.buildService(cc.bridgit.app.BridgeService::class.java,
            android.content.Intent(app, cc.bridgit.app.BridgeService::class.java)).create().startCommand(0,1)
        try {
            val converted = app.applets.approve(proposal.getString("id"), null)
            assertNull(app.agents.get(abandoned.id))
            assertEquals(id,converted.getString("id"))
            assertEquals(candidate.sha256,converted.getString("sha256"))
            assertEquals("paused",converted.getString("status"))
            val agentId = converted.getString("agent_id")
            assertEquals(2,app.watches.list(agentId).size)
            assertEquals(91, converted.getJSONObject("config").getInt("interval_s"))
            val monitor = app.watches.list(agentId).single { it.spec.camera != null }
            assertEquals(91, monitor.spec.intervalS)
            assertEquals(listOf("front","rear"), monitor.spec.cameraSources)
            val audio = app.watches.list(agentId).single { it.spec.sound != null }
            val foodRules = audio.spec.tally!!.rules
            assertEquals(setOf("eating", "drinking"), foodRules.map { it.`when`.label }.toSet())
            assertTrue(foodRules.all { it.flow == "dinner" && it.cooldownS == 600 })
            assertTrue(app.platform.workflows.resolve(agentId, "dinner") != null)
            assertTrue(app.watches.list(agentId).all { it.userPaused })
            assertEquals(previous,app.applets.dogHistoryArchive(id)!!.getJSONObject("tracker").toString())
            assertEquals(AppletBudget.DOG,AppletBudget.appletOf(converted))
            assertEquals(AppletBudget.DOG,cc.bridgit.app.judgment.Jev.appletFor(app,null,agentId))
            assertEquals(AppletBudget.packageLimits(legacy), AppletBudget.packageLimits(converted))
            app.applets.setPaused(id,false)
            assertTrue(app.watches.list(agentId).all { it.userPaused })
            assertTrue(app.applets.legacyDogReplaced())
            assertEquals(1,app.applets.all().size)
        } finally { service.destroy() }
    }

    @Test fun nativeClassifierUpdateRequiresOwnerToReviewChangedInstructions() = runBlocking {
        val source = File("../applets/dog-monitor/editions/1.5.0/applet.json").readText()
        val installed = install(AppletPackage.parse(source))
        val variant = JSONObject(source).apply {
            put("version","1.5.1")
            getJSONObject("processing").getJSONObject("classifier").getJSONObject("sound").put("instructions","Listen for repeated barking.")
        }
        val proposal = app.applets.update(installed.getString("id"),AppletPackage.check(variant),null,link,null)
        assertTrue(proposal.getBoolean("classifier_changed"))
        assertTrue(proposal.getJSONObject("review").getJSONArray("items").toString().contains("Listen for repeated barking."))
        assertEquals("1.5.0",app.applets.get(installed.getString("id"))!!.getString("version"))
        app.applets.approve(proposal.getString("id"),null)
        assertEquals("1.5.1",app.applets.get(installed.getString("id"))!!.getString("version"))
    }

    @Test fun nothingRunsBeforeApproval() = runBlocking {
        val before = app.agents.all().size
        val pend = app.applets.propose(pkg("room-change-watcher"), null, link, null, notify = false)
        assertEquals("awaiting_owner", pend.getString("status"))
        assertEquals(before, app.agents.all().size)
        assertTrue(app.applets.all().isEmpty())
        val review = pend.getJSONObject("review")
        assertEquals(setOf("camera", "interactions", "watches"), AppletPackage.strings(JSONArray(review.getJSONArray("permissions").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } })).toSet())
        assertTrue(review.getJSONArray("parts").toString(), review.getJSONArray("parts").toString().contains("every 30 seconds"))
        app.applets.decline(pend.getString("id"))
        assertTrue(app.applets.pending().isEmpty())
        assertEquals(before, app.agents.all().size)
    }

    @Test fun approveCreatesEveryPartUnderOneApplet() {
        val r = install(pkg("room-change-watcher"), JSONObject().put("interval_s", 60))
        val agent = app.agents.get(r.getString("agent_id"))!!
        assertEquals(Connection.APPLET, agent.connection)
        assertEquals(setOf("camera", "interactions", "watches"), agent.caps)
        assertFalse(agent.fixer || agent.superPowers)
        val w = app.watches.list(agent.id).single()
        assertEquals(60, w.spec.intervalS)
        // Applet watches get the longest lifetime the rules allow, and are renewed while the applet is active.
        assertEquals(cc.bridgit.app.watch.WatchRules.MAX_EXPIRES_S, w.spec.expiresS)
        assertTrue(app.applets.renews(agent.id))
        val card = app.interactions.getFor(agent.id, r.getLong("card_id"))!!
        assertTrue(card.status.open)
        // Phase 2: the recipe card is drawn by the phone from pieces; its source names the real watch id.
        assertEquals(cc.bridgit.app.interact.IKind.PIECES, card.kind)
        assertTrue(card.html!!.contains("\"watch\":\"${w.id}\""))
        // An applet's agent can't be used with any key and doesn't take one of the owner's agent slots.
        val (probe, key) = app.agents.add("applet key probe", Connection.APPLET)
        assertTrue(app.agents.authenticate(key) !is cc.bridgit.app.core.AuthResult.Ok)
        app.agents.remove(probe.id)
        try { install(pkg("room-change-watcher")); fail("installed twice") } catch (e: AppletInstances.Refused) { assertEquals("conflict", e.code) }
    }

    @Test fun switchedOffCameraBlocksInstallAndNothingIsLeft() {
        app.settings.setOn("camera", false)
        val before = app.agents.all().size
        try { install(pkg("room-change-watcher")); fail("installed with the camera off") } catch (e: AppletInstances.Refused) { assertEquals("capability_disabled", e.code) }
        assertEquals(before, app.agents.all().size)
        assertTrue(app.applets.all().isEmpty())
    }

    @Test fun missingAndroidCameraPermissionIsNamedBeforeApprove() {
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.CAMERA)
        val review = app.applets.propose(pkg("room-change-watcher"), null, link, null, notify = false).getJSONObject("review")
        assertTrue(review.getJSONArray("blockers").toString().contains("Allow camera access"))
        try { install(pkg("room-change-watcher")); fail("installed without camera permission") } catch (e: AppletInstances.Refused) { assertEquals("capability_disabled", e.code) }
    }

    @Test fun insuranceRequirementsOfferActionsAndDisappearAfterSetup() {
        val p = AppletPackage.check(JSONObject(File("src/test/resources/applets/insurance-inventory-3.0.0.json").readText()))
        for (scope in listOf("workflows", "decisions")) cc.bridgit.app.platform.Capabilities.setEnabled(app, scope, false)
        app.settings.setOn("camera", false)
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.CAMERA)
        val config = AppletPackage.fill(p, null)
        fun requirements() = app.applets.requirements(p, AppletPackage.compile(p, config))
        val missing = requirements()
        assertTrue(missing.any { it.action == "module" && it.target == "camera" })
        assertTrue(missing.any { it.action == "permission" && it.target == android.Manifest.permission.CAMERA })
        assertTrue(missing.any { it.action == "capability" && it.target == "workflows" })
        assertTrue(missing.any { it.action == "capability" && it.target == "decisions" })
        assertTrue(missing.all { it.label.isNotBlank() })
        assertTrue(app.applets.all().isEmpty())
        app.settings.setOn("camera", true)
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.CAMERA)
        missing.filter { it.action == "capability" }.forEach { cc.bridgit.app.platform.Capabilities.setEnabled(app, it.target, true) }
        assertTrue(requirements().isEmpty())
        assertTrue(app.applets.all().isEmpty())
    }

    @Test fun photoAndUnsupportedRequirementsHaveSpecificActions() {
        val p = pkg("room-change-watcher")
        val plan = AppletPackage.compile(p, AppletPackage.fill(p, null))
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val photo = app.applets.requirements(setOf("gallery"), plan)
        assertTrue(photo.any { it.action == "module_permission" && it.target == "gallery" })
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertFalse(app.applets.requirements(setOf("gallery"), plan).any { it.action == "module_permission" })
        assertTrue(app.applets.requirements(setOf("screen"), plan).any { it.action == "accessibility" })
    }

    @Test fun pauseAndResumeEveryPart() = runBlocking {
        val r = install(pkg("room-change-watcher"))
        val id = r.getString("id"); val agent = r.getString("agent_id")
        app.applets.setPaused(id, true)
        assertTrue(app.watches.list(agent).single().userPaused)
        assertEquals("paused", AppletLifecycle.state(app.applets.get(id)!!))
        assertEquals(0, app.applets.get(id)!!.getJSONObject("lifecycle").getJSONArray("pending").length())
        assertFalse(app.applets.renews(agent))
        app.applets.setPaused(id, false)
        assertFalse(app.watches.list(agent).single().userPaused)
        assertEquals("running", AppletLifecycle.state(app.applets.get(id)!!))
        assertEquals(2L, app.applets.get(id)!!.getJSONObject("lifecycle").getLong("generation"))
        assertEquals("active", app.applets.get(id)!!.getString("status"))
    }

    @Test fun updateWithoutNewPermissionsSwapsInAtOnceKeepingUnchangedWatches() = runBlocking {
        val r = install(pkg("room-change-watcher"))
        val id = r.getString("id"); val agent = r.getString("agent_id")
        val watch = app.watches.list(agent).single().id
        // A new version that only rewords the summary keeps the same watch (and its history).
        val reworded = pkg("room-change-watcher") { it.put("version", "1.0.1").put("summary", "Reworded.") }
        assertEquals("updated", app.applets.update(id, reworded, null, link, null).getString("status"))
        assertEquals(watch, app.watches.list(agent).single().id)
        assertEquals("1.0.1", app.applets.get(id)!!.getString("version"))
        // A new interval is a new watch; the card follows the new watch id.
        val out = app.applets.update(id, pkg("room-change-watcher") { it.put("version", "1.1.0") }, JSONObject().put("interval_s", 120), link, null)
        assertEquals("updated", out.getString("status"))
        val now = app.watches.list(agent).single()
        assertNotEquals(watch, now.id)
        assertEquals(120, now.spec.intervalS)
        val card = app.interactions.getFor(agent, app.applets.get(id)!!.getLong("card_id"))!!
        assertTrue(card.html!!.contains(now.id))
    }

    @Test fun tallyHistorySurvivesCadenceEditsAndDestructiveEditsWaitForReview() = runBlocking {
        fun monitor(threshold: Double? = null) = pkg("pet-monitor") { d ->
            val processing = d.getJSONObject("processing")
            processing.remove("flows")
            val req = processing.getJSONArray("watches").getJSONObject(0).getJSONObject("request")
            req.remove("rules")
            req.put("start_paused", true)
            threshold?.let { req.getJSONObject("tally").put("min_confidence",it) }
            d.getJSONObject("permissions").put("required",JSONArray(listOf("watches","camera","interactions")))
            d.getJSONObject("ui").put("card",JSONObject("""{"title":"Monitor","pieces":[{"type":"header","text":"Pet observations"}]}"""))
        }
        val pet = monitor()
        val r = install(pet)
        val instance = r.getString("id")
        val agent = r.getString("agent_id")
        val watch = app.watches.list(agent).single()
        assertTrue(watch.userPaused)
        assertEquals(0, watch.checks)
        val start = System.currentTimeMillis()
        val state = watch.tally!!
        // Saved form from before per-episode frame lengths were recorded.
        state.open["eating"] = WatchTally.Episode("eating",start,start,1)
        state.spans.add(longArrayOf(start,start))
        val result = app.applets.update(instance,pet,JSONObject().put("interval_s",90),link,null)
        assertEquals(result.toString(),"updated",result.getString("status"))
        val kept = app.watches.list(agent).single()
        assertEquals(watch.id,kept.id)
        assertEquals(90,kept.intervalS)
        assertTrue(kept.userPaused)
        val saved = File(app.filesDir,"watch-tallies/${kept.id}.json")
        val restored = WatchTally.State.from(JSONObject(saved.readText()))
        assertEquals(30_000L,WatchTally.lengthMs(restored.open.getValue("eating"),90))
        assertEquals(30_000L,WatchTally.seenMs(restored,start,start+100_000,90))
        val different = monitor(0.91)
        val pending = app.applets.update(instance,different,null,link,null)
        assertEquals("awaiting_owner",pending.getString("status"))
        assertTrue(pending.getJSONArray("deletes").toString().contains("episode history"))
        assertEquals(watch.id,app.watches.list(agent).single().id)
        assertTrue(saved.exists())
    }

    @Test fun optionalPermissionsAreLimitedToTheResolvedPlan() = runBlocking {
        val candidate = pkg("room-change-watcher") { definition ->
            val permissions = definition.getJSONObject("permissions")
            permissions.put("required", JSONArray(AppletPackage.strings(permissions.getJSONArray("required")).filter { it != "camera" }))
            permissions.put("optional", JSONArray(listOf("camera", "lan_camera")))
        }
        val instance = install(candidate)
        val agent = app.agents.get(instance.getString("agent_id"))!!
        assertTrue(agent.allows("camera"))
        assertFalse(agent.allows("lan_camera"))
        val next = AppletPackage.check(JSONObject(candidate.definition.toString()).apply {
            put("version", "3.0.0")
            getJSONObject("processing").getJSONArray("watches").getJSONObject(0).getJSONObject("request")
                .put("additional_cameras",JSONArray(listOf("lan:missing")))
        })
        val proposed = app.applets.update(instance.getString("id"), next, null, link, null)
        assertEquals(listOf("lan_camera"), AppletPackage.strings(proposed.getJSONArray("added_permissions")))
        assertFalse(app.agents.get(agent.id)!!.allows("lan_camera"))
        try { app.applets.approve(proposed.getString("id"),null); fail("Activated a missing camera") }
        catch (_: AppletInstances.Refused) {}
        assertFalse(app.agents.get(agent.id)!!.allows("lan_camera"))
        assertEquals(candidate.version, app.applets.get(instance.getString("id"))!!.getString("version"))
        Unit
    }

    @Test fun updateThatAddsAPermissionWaitsForTheOwner() = runBlocking {
        val r = install(pkg("room-change-watcher"))
        val id = r.getString("id"); val agent = r.getString("agent_id")
        val bigger = pkg("room-change-watcher") { d -> d.put("version", "3.0.0"); d.getJSONObject("permissions").getJSONArray("required").put("weather") }
        val out = app.applets.update(id, bigger, null, link, null)
        assertEquals("awaiting_owner", out.getString("status"))
        assertEquals(listOf("weather"), AppletPackage.strings(out.getJSONArray("added_permissions")))
        assertEquals("2.0.0", app.applets.get(id)!!.getString("version"))
        assertFalse(app.agents.get(agent)!!.allows("weather"))
        // The owner approves: same instance, new permission, still one watch.
        app.applets.approve(out.getString("id"), null)
        assertEquals("3.0.0", app.applets.get(id)!!.getString("version"))
        assertTrue(app.agents.get(agent)!!.allows("weather"))
        assertEquals(1, app.watches.list(agent).size)
    }

    private fun eventTable() = JSONArray().put(JSONObject().put("id", "events").put("columns", JSONObject()
        .put("result", JSONObject().put("type", "json").put("description", "Room-change event: kind, at, label and numeric metrics."))))

    /** Owner, 5 Nov 2026: a maker may make the web portal part of the applet; an update that does so waits for the owner. */
    @Test fun updateThatMakesThePortalPartOfTheAppletWaitsForTheOwner() = runBlocking {
        val records = JSONObject("""{"records":{"kind":"room_change","title":"Room changes","table":"events","column":"result"}}""")
        val r = install(pkg("room-change-watcher") { d -> d.getJSONObject("processing").put("tables", eventTable()); d.getJSONObject("ui").put("portal", records) })
        val id = r.getString("id")
        val always = pkg("room-change-watcher") { d -> d.put("version", "3.0.0"); d.getJSONObject("processing").put("tables", eventTable()); d.getJSONObject("ui").put("portal", JSONObject(records.toString()).put("include", "always")) }
        val out = app.applets.update(id, always, null, link, null)
        assertEquals("awaiting_owner", out.getString("status"))
        assertTrue(out.getBoolean("portal_always"))
        assertTrue(out.getJSONObject("review").getJSONArray("parts").toString().contains("Web portal: Comes with this applet"))
        assertEquals("2.0.0", app.applets.get(id)!!.getString("version"))
        // Approved: the portal starts with it.
        app.applets.approve(out.getString("id"), null)
        assertEquals("3.0.0", app.applets.get(id)!!.getString("version"))
        assertTrue(app.remoteView.packages.status(r.getString("package_id")).optBoolean("personal_backend"))
        // A later update that keeps it "always" applies at once.
        val next = pkg("room-change-watcher") { d -> d.put("version", "3.0.1"); d.getJSONObject("processing").put("tables", eventTable()); d.getJSONObject("ui").put("portal", JSONObject(records.toString()).put("include", "always")) }
        assertEquals("updated", app.applets.update(id, next, null, link, null).getString("status"))
        app.remoteView.packages.configure(r.getString("package_id"), false, null)
    }

    @Test fun updateThatSharesATableWaitsForTheOwner() = runBlocking {
        val r = install(pkg("receipt-scanner"))
        val id = r.getString("id"); val version = r.getString("version")
        // An existing table turning share_with_installer on: new access, so the owner reviews it.
        val shared = pkg("receipt-scanner") { d -> d.put("version", "9.0.0"); d.getJSONObject("processing").getJSONArray("tables").getJSONObject(0).put("share_with_installer", true) }
        val out = app.applets.update(id, shared, null, link, null)
        assertEquals("awaiting_owner", out.getString("status"))
        assertEquals(listOf("The agent that installed this applet will be able to read the rows of Receipts."), AppletPackage.strings(out.getJSONArray("shares")))
        assertEquals(version, app.applets.get(id)!!.getString("version"))
        app.applets.pendingDrop(out.getString("id"))
        // A new table that is shared waits too.
        val added = pkg("receipt-scanner") { d -> d.put("version", "9.0.1"); d.getJSONObject("processing").getJSONArray("tables").put(JSONObject().put("id", "notes").put("label", "Notes")
            .put("columns", JSONObject().put("text", JSONObject().put("type", "text"))).put("share_with_installer", true)) }
        val out2 = app.applets.update(id, added, null, link, null)
        assertEquals("awaiting_owner", out2.getString("status"))
        assertTrue(out2.getJSONObject("review").getJSONObject("update").getJSONArray("shares").toString().contains("rows of Notes"))
        assertEquals(version, app.applets.get(id)!!.getString("version"))
        // The same new table without sharing applies at once.
        val plain = pkg("receipt-scanner") { d -> d.put("version", "9.0.2"); d.getJSONObject("processing").getJSONArray("tables").put(JSONObject().put("id", "notes")
            .put("columns", JSONObject().put("text", JSONObject().put("type", "text")))) }
        assertEquals("updated", app.applets.update(id, plain, null, link, null).getString("status"))
    }

    @Test fun dataWrittenAfterTheReviewKeepsTheUpdateWaiting() = runBlocking {
        val r = install(pkg("receipt-scanner") { d -> d.put("version", "9.0.0")
            d.getJSONObject("processing").getJSONArray("tables").getJSONObject(0).getJSONObject("columns").put("note", JSONObject().put("type", "text").put("label", "Note")) })
        val id = r.getString("id"); val agent = r.getString("agent_id"); val version = r.getString("version")
        // The owner opens an update that drops the Note column while no row uses it: nothing to lose yet.
        val smaller = pkg("receipt-scanner") { d -> d.put("version", "9.1.0") }
        val pend = app.applets.propose(smaller, null, link, null, replaces = id, notify = false)
        assertEquals(0, pend.getJSONObject("review").getJSONObject("update").getJSONArray("deletes").length())
        // A row with that column arrives before the owner taps Approve.
        val store = AppletTableStore.of(app)
        val table = AppletTables.parse(r.getJSONObject("definition").getJSONObject("processing")).single()
        val row = store.add(agent, table, JSONObject().put("text", "Coffee").put("note", "keep me"), JSONObject().put("type", "flow"))
        try { app.applets.approve(pend.getString("id"), null); fail("applied a loss the owner didn't see") }
        catch (e: AppletInstances.Refused) { assertEquals("data_changed", e.code); assertTrue(e.message!!, e.message!!.contains("column Note")) }
        assertEquals(version, app.applets.get(id)!!.getString("version"))
        assertEquals("keep me", store.get(agent, "receipts", row.getString("id"))!!.getString("note"))
        // It keeps waiting, now with the loss it would cause; approving what the owner now sees applies it.
        val waiting = app.applets.pendingGet(pend.getString("id"))!!
        assertEquals(listOf("This update deletes the column Note from 1 row."), AppletPackage.strings(waiting.getJSONArray("deletes")))
        app.applets.approve(pend.getString("id"), null, listOf("This update deletes the column Note from 1 row."))
        assertEquals("9.1.0", app.applets.get(id)!!.getString("version"))
        assertFalse(store.get(agent, "receipts", row.getString("id"))!!.has("note"))
    }

    @Test fun scheduleAppletRunsItsManifestAndRemoveLeavesNothing() = runBlocking {
        val r = install(pkg("daily-briefing-panel"), JSONObject().put("time", "07:30").put("timezone", "Europe/Warsaw"))
        val agent = r.getString("agent_id")
        val active = app.schedules.record(agent)!!.getJSONObject("active").getJSONObject("manifest")
        assertEquals("07:30", active.getJSONArray("actions").getJSONObject(0).getJSONObject("when").getJSONArray("daily").getString(0))
        val out = app.applets.remove(r.getString("id"))
        assertEquals(0, out.getJSONArray("left_behind").length())
        assertNull(app.agents.get(agent))
        assertNull(app.schedules.record(agent))
    }

    @Test fun pausedAppletAllowsExplicitFlowButBlocksAutomaticStarts() = runBlocking {
        val wf = JSONObject("""{"id":"check","name":"Check","trigger":{"type":"called"},"steps":[{"type":"notice","title":"Check","text":"Done"}]}""")
        val p = pkg("room-change-watcher") { d ->
            d.getJSONObject("processing").put("flows", JSONArray().put(wf))
            d.getJSONObject("permissions").getJSONArray("required").put("workflows")
        }
        for (scope in listOf("workflows", "interactions")) cc.bridgit.app.platform.Capabilities.setEnabled(app, scope, true)
        val r = install(p)
        val agent = r.getString("agent_id")
        app.applets.setPaused(r.getString("id"), true)
        assertEquals("flow_not_active", app.platform.workflows.start(agent, "check", JSONObject().put("caller", "rule"), null).getString("status"))
        val manual = app.platform.workflows.start(agent, "check", JSONObject().put("caller", "agent"), null)
        assertEquals("started", manual.getString("status"))
        assertEquals("paused", AppletLifecycle.state(app.applets.get(r.getString("id"))!!))
    }

    @Test fun workflowPartIsGrantedOnlyItsScopesAndRemovedWithTheApplet() = runBlocking {
        val wf = JSONObject().put("name", "Help panel").put("trigger", JSONObject().put("type", "button"))
            .put("steps", JSONArray().put(JSONObject().put("type", "notice").put("title", "Help requested").put("text", "Please check the panel")))
        val p = pkg("receipt-scanner") { d ->
            d.put("id", "help-panel"); d.getJSONObject("processing").put("workflow", wf)
            d.getJSONObject("permissions").getJSONArray("required").put("workflows")
        }
        // Explicitly disable it: platform defaults may enable owner-authorized tools.
        cc.bridgit.app.platform.Capabilities.setEnabled(app, "workflows", false)
        try { install(p); fail("installed with workflows off") } catch (e: AppletInstances.Refused) { assertEquals("capability_disabled", e.code) }
        for (s in listOf("workflows", "interactions")) cc.bridgit.app.platform.Capabilities.setEnabled(app, s, true)
        val r = install(p)
        val agent = r.getString("agent_id")
        val record = app.platform.store.find(r.getString("workflow_id"))!!
        assertEquals("active", record.getString("status"))
        assertTrue(app.platform.hasGrant(agent, "workflows"))
        assertFalse(app.platform.hasGrant(agent, "location"))
        val out = app.applets.remove(r.getString("id"))
        assertEquals(0, out.getJSONArray("left_behind").length())
        assertNull(app.platform.store.find(r.getString("workflow_id")))
        assertTrue(app.platform.store.grants(agent).length() == 0)
    }

    /** Phase 3 (docs/applet-vocabulary.md): flows by name, started by a rule and a schedule, kept apart from the watch on update. */
    @Test fun packageFlowsAreBoundByNameAndAnUpdateKeepsTheWatch() = runBlocking {
        fun dinner(title: String) = JSONObject().put("id", "dinner").put("name", "Dinner check").put("trigger", JSONObject().put("type", "called"))
            .put("steps", JSONArray().put(JSONObject().put("type", "notice").put("title", title).put("text", JSONArray().put(JSONObject().put("\$trigger", "message")))))
        fun pet(title: String, version: String) = AppletPackage.check(JSONObject(File("src/test/resources/applets/pet-monitor-2.0.0.json").readText()).also { d ->
            d.put("version", version)
            val proc = d.getJSONObject("processing")
            proc.put("flows", JSONArray().put(dinner(title)))
            proc.getJSONArray("watches").getJSONObject(0).getJSONObject("request").getJSONArray("rules").getJSONObject(0)
                .put("do", JSONObject().put("flow", JSONObject().put("\$flow", "dinner")))
            proc.put("schedule", JSONObject().put("manifest", JSONObject().put("actions", JSONArray()
                .put(JSONObject().put("id", "evening").put("name", "Evening check").put("when", JSONObject().put("daily", JSONArray().put("19:00")))
                    .put("do", JSONObject().put("flow", JSONObject().put("\$flow", "dinner"))))
                .put(JSONObject().put("id", "remind").put("when", JSONObject().put("daily", JSONArray().put("20:00")))
                    .put("do", JSONObject().put("step", JSONObject().put("type", "notice").put("title", "Bowl check")))))))
            d.getJSONObject("permissions").getJSONArray("required").put("workflows").put("schedule")
        })
        for (s in listOf("workflows", "interactions")) cc.bridgit.app.platform.Capabilities.setEnabled(app, s, true)
        val pend = app.applets.propose(pet("No meal yet", "3.0.0"), null, link, null, notify = false)
        val parts = pend.getJSONObject("review").getJSONArray("parts").toString()
        assertTrue(parts, parts.contains("If no eating is seen by 18:00, it runs Dinner check"))
        assertTrue(parts, parts.contains("It runs when the no-meal-by-6pm rule fires or the Evening check schedule runs."))
        assertTrue(parts, parts.contains("it shows you a notice \\\"Bowl check\\\""))
        val r = app.applets.approve(pend.getString("id"), null)
        val id = r.getString("id"); val agent = r.getString("agent_id")
        val first = r.getJSONObject("flows").getJSONObject("dinner").getString("workflow_id")
        assertEquals("dinner", app.platform.store.find(first)!!.getString("package_flow"))
        val watch = app.watches.list(agent).single()
        assertEquals("dinner", watch.spec.tally!!.rules.single().flow)
        // The rule starts it by name, as the applet's own principal.
        assertEquals("started", app.platform.workflows.start(agent, "dinner", JSONObject().put("caller", "rule").put("message", "No meal by 18:00"), null).getString("status"))
        // An update that changes only the flow: a new workflow record, the same watch (and its counts).
        assertEquals("updated", app.applets.update(id, pet("Still no meal", "3.0.1"), null, link, null).getString("status"))
        val second = app.applets.get(id)!!.getJSONObject("flows").getJSONObject("dinner").getString("workflow_id")
        assertNotEquals(first, second)
        assertNull(app.platform.store.find(first))
        assertEquals(watch.id, app.watches.list(agent).single().id)
        assertEquals(second, app.platform.workflows.resolve(agent, "dinner")!!.getString("id"))
        assertEquals(second, app.applets.get(id)!!.getString("workflow_id"))
        // Another agent can't reach this applet's flow by its name.
        assertNull(app.platform.workflows.resolve(app.policy.addAgent("Outsider").first.id, "dinner"))
        assertEquals(0, app.applets.remove(id).getJSONArray("left_behind").length())
        assertNull(app.platform.store.find(second))
    }

    @Test fun aWorkflowFromBeforeSeveralFlowsGetsItsNameOnUpdate() = runBlocking {
        val wf = JSONObject().put("name", "Help panel").put("trigger", JSONObject().put("type", "button"))
            .put("steps", JSONArray().put(JSONObject().put("type", "notice").put("title", "Help requested")))
        fun p(version: String, schedule: Boolean) = pkg("receipt-scanner") { d ->
            d.put("id", "help-panel").put("version", version); d.getJSONObject("processing").put("workflow", wf)
            d.getJSONObject("permissions").getJSONArray("required").put("workflows").put("schedule")
            if (schedule) d.getJSONObject("processing").put("schedule", JSONObject().put("manifest", JSONObject().put("actions", JSONArray()
                .put(JSONObject().put("id", "nightly").put("when", JSONObject().put("daily", JSONArray().put("21:00"))).put("do", JSONObject().put("flow", JSONObject().put("\$flow", "main")))))))
        }
        for (s in listOf("workflows", "interactions")) cc.bridgit.app.platform.Capabilities.setEnabled(app, s, true)
        val r = install(p("1.0.0", false))
        val agent = r.getString("agent_id"); val wid = r.getString("workflow_id")
        // As installed by an older Bridgit: no package name on the record.
        app.platform.store.save(agent, "workflow", app.platform.store.find(wid)!!.apply { remove("package_flow") })
        assertNull(app.platform.workflows.resolve(agent, "main"))
        // An update that keeps the workflow and adds a schedule naming it: same record, now findable by name.
        assertEquals("updated", app.applets.update(r.getString("id"), p("1.1.0", true), null, link, null).getString("status"))
        assertEquals(wid, app.platform.workflows.resolve(agent, "main")!!.getString("id"))
    }

    @Test fun removeEndsWatchesCardAndAgent() = runBlocking {
        val r = install(pkg("room-change-watcher"))
        val agent = r.getString("agent_id"); val card = r.getLong("card_id")
        val out = app.applets.remove(r.getString("id"))
        assertEquals(0, out.getJSONArray("left_behind").length())
        assertTrue(app.watches.list(agent).isEmpty())
        assertNull(app.agents.get(agent))
        assertEquals(IStatus.CANCELLED, app.interactions.get(card)!!.status)
        assertTrue(app.applets.all().isEmpty())
    }
}
