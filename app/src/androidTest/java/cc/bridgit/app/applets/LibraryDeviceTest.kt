package cc.bridgit.app.applets

import androidx.test.platform.app.InstrumentationRegistry
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.platform.Capabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class LibraryDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val fixtures get() = instrumentation.context.assets
    private fun read(path: String) = fixtures.open(path).bufferedReader().use { it.readText() }

    @Before fun requireDedicatedTestInstall() {
        assumeTrue("Use the isolated applet-library test build",
            instrumentation.targetContext.packageName.endsWith(".test_claude_applet_library"))
    }

    @Test fun cachesRevisionBoundJpegOnDeviceAndDiscardsInterruptedDownloads() {
        val root = java.io.File(instrumentation.targetContext.cacheDir,java.util.UUID.randomUUID().toString())
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(4,4,android.graphics.Bitmap.Config.ARGB_8888)
            val buffer = java.io.ByteArrayOutputStream()
            try { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,buffer)) } finally { bitmap.recycle() }
            val jpeg = buffer.toByteArray()
            val comment = byteArrayOf(-1,-2,-1,-1) + ByteArray(65533)
            val bytes = jpeg.copyOfRange(0,2) + comment + comment + jpeg.copyOfRange(2,jpeg.size)
            val digest = AppletPackage.sha256(bytes)
            val descriptor = JSONObject().put("media_type","image/jpeg").put("storage","revision").put("size",bytes.size).put("sha256",digest)
            val definition = JSONObject().put("assets",JSONObject().put("photo.jpg",descriptor))
            val source = JSONObject().put("template","media-test").put("revision",3)
            val instance = "ai0123456789abcd"
            val record = JSONObject().put("id",instance).put("definition",definition)
            var full = false
            val cache = AppletMedia(root) { url,size,file ->
                assertEquals("https://${AppletPackage.REGISTRY_HOST}/v1/templates/media-test/3/assets/photo.jpg",url)
                assertEquals(bytes.size,size)
                file.writeBytes(if (full) bytes else jpeg)
            }
            try { cache.prepare(instance,definition,source); fail("Incomplete media accepted") } catch (_: AppletFetch.Failed) {}
            full = true
            cache.prepare(instance,definition,source)
            val offline = AppletMedia(root) { _,_,_ -> error("Unexpected network access") }
            offline.prepare(instance,definition,JSONObject())
            assertArrayEquals(bytes,offline.read(record,"photo.jpg").readBytes())
            offline.prune(instance,null)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun catalogPackagesCompileOutsideTheApplicationBundle() {
        val appAssets = instrumentation.targetContext.assets
        assertFalse(appAssets.list("").orEmpty().contains("catalog"))
        val entries = fixtures.list("catalog").orEmpty().toList()
        assertTrue(entries.containsAll(listOf("dog-monitor","chef-helper","pet-monitor","insurance-inventory")))
        for (directory in entries) {
            val definition = AppletPackage.parse(read("catalog/$directory/applet.json"))
            assertEquals(directory, definition.id)
            val defaults = definition.definition.getJSONObject("defaults")
            val app = instrumentation.targetContext.applicationContext as BridgeApp
            val review = app.applets.review(definition,defaults)
            assertTrue(directory,review.has("parts"))
        }
    }

    /** Optional release smoke against the deployed Worker, using this test install's normal device identity. */
    @Test fun verifyLiveCatalogThroughSignedInstallReview() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRegistry") == "read-public")
        val app = instrumentation.targetContext.applicationContext as BridgeApp
        val base = "https://" + AppletPackage.REGISTRY_HOST
        requireNotNull(cc.bridgit.app.feedback.InstallIdentity.ensure(app,base))
        val keys = fixtures.list("catalog").orEmpty().toSet()
        assertTrue(keys.isNotEmpty())
        val verified = mutableSetOf<String>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        do {
            val page = AppletLibrary.page(app,cursor)
            for (entry in page.entries.filter { it.key in keys }) {
                val link = requireNotNull(AppletPackage.registryLink(entry.key,entry.revision))
                val (pkg,source) = AppletFetch.packageAndSource(app,link,cc.bridgit.app.outbound.Outbound.Actor.OWNER,"template")
                assertEquals(entry.key,source.getString("template"))
                assertEquals(entry.revision,source.getInt("revision"))
                if (source.has("library_account")) assertTrue(source.getString("library_account").matches(Regex("[a-f0-9]{64}")))
                assertEquals(entry.id,pkg.id)
                assertTrue(entry.key,app.applets.review(pkg,pkg.definition.getJSONObject("defaults")).has("parts"))
                assertTrue("Duplicate catalog key " + entry.key,verified.add(entry.key))
            }
            cursor = page.next
            if (cursor != null) assertTrue("Repeated catalog cursor",seenCursors.add(cursor))
        } while (cursor != null && verified != keys)
        assertEquals(keys,verified)
    }

    /** Opt-in only. The runner must first verify that the AVD uses its synthetic rear camera. */
    @Test fun syntheticCameraWatchContinuesAfterLeavingTheApplet() = runBlocking {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish") &&
            InstrumentationRegistry.getArguments().getString("syntheticCamera") == "verified")
        val app = instrumentation.targetContext.applicationContext as BridgeApp
        val listener = app.settings.listenerEnabled
        val switches = listOf("camera","interactions").associateWith { app.settings.isOn(it) }
        val store = cc.bridgit.app.relay.RelayStore(app)
        val viewer = app.agents.add("Watch test viewer", cc.bridgit.app.core.Connection.RELAY).first
        var installed: String? = null
        var pending: String? = null
        try {
            app.settings.listenerEnabled = true
            switches.keys.forEach { app.settings.setOn(it,true) }
            val launcher = requireNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
            val activity = instrumentation.startActivitySync(launcher)
            instrumentation.runOnMainSync { cc.bridgit.app.BridgeService.start(activity) }
            withTimeout(15_000) { while (cc.bridgit.app.BridgeService.running?.cameraReady != true) delay(100) }
            activity.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val key = "room-change-watcher"
            val pkg = AppletPackage.parse(read("catalog/$key/applet.json"))
            val pin = JSONObject().put("kind","template").put("template",key).put("revision",1)
            val proposal = app.applets.propose(pkg,null,pin,null,notify=false)
            val proposalId = proposal.getString("id")
            pending = proposalId
            val record = app.applets.approve(proposalId,null)
            installed = record.getString("id")
            pending = null
            val granted = requireNotNull(app.agents.update(viewer.id) { it.copy(caps=setOf("portal"),superPowers=false) })
            store.addAgent(viewer.id,"connector",null,cc.bridgit.app.portal.PortalData.ROUTES.toSet(),emptySet())
            val api = cc.bridgit.app.api.ApiServer(requireNotNull(cc.bridgit.app.BridgeService.running),emptyList(),0).apply { prepareRelay() }
            suspend fun current(): JSONObject {
                val reply = api.dispatch(cc.bridgit.app.portal.PortalEndpoints.APPLET_WATCHES,granted,"relay:connector",
                    mapOf("template" to key,"revision" to "1"),emptyMap(),JSONObject(),null,"relay:connector")
                assertEquals(String(reply.bytes),200,reply.status)
                return JSONObject(String(reply.bytes)).getJSONArray("watches").getJSONObject(0)
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 105_000
            var history = current().getJSONArray("recent_checks")
            fun successful() = (0 until history.length()).count { history.getJSONObject(it).has("matched") }
            while (successful() < 2 && android.os.SystemClock.elapsedRealtime() < deadline) {
                delay(2000)
                history = current().getJSONArray("recent_checks")
            }
            assertTrue("Expected two completed synthetic image comparisons: $history", successful() >= 2)
            app.applets.setPaused(record.getString("id"),true)
            assertEquals("paused",current().getString("state"))
        } finally {
            installed?.let { app.applets.remove(it) }
            pending?.let { app.applets.decline(it) }
            store.remove(viewer.id)
            app.agents.remove(viewer.id)
            cc.bridgit.app.BridgeService.stop(app)
            switches.forEach { (id,on) -> app.settings.setOn(id,on) }
            app.settings.listenerEnabled = listener
        }
        Unit
    }

    @Test fun pairedInventoryReadsOnlyTheApprovedAppletEnrollments() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as BridgeApp
        val previous = listOf("lan_control", "interactions").associateWith { app.settings.isOn(it) }
        val listener = app.settings.listenerEnabled
        val startService = cc.bridgit.app.BridgeService.running == null
        val store = cc.bridgit.app.relay.RelayStore(app)
        val viewer = app.agents.add("Inventory test viewer", cc.bridgit.app.core.Connection.RELAY).first
        val granted = requireNotNull(app.agents.update(viewer.id) { it.copy(caps = setOf("portal"), superPowers = false) })
        var installed: String? = null
        var pending: String? = null
        val key = "external-device-dashboard"
        val revision = 1
        try {
            app.settings.listenerEnabled = true
            previous.keys.forEach { app.settings.setOn(it, true) }
            if (startService) cc.bridgit.app.BridgeService.startInBackground(app)
            val service = withTimeout(15_000) {
                while (cc.bridgit.app.BridgeService.running == null) delay(100)
                requireNotNull(cc.bridgit.app.BridgeService.running)
            }
            val api = cc.bridgit.app.api.ApiServer(service, emptyList(), app.settings.port).apply { prepareRelay() }
            suspend fun inventory(pin: Int = revision) = api.dispatch(cc.bridgit.app.portal.PortalEndpoints.APPLET_INVENTORY,
                granted, "relay:connector", mapOf("template" to key, "revision" to pin.toString()), emptyMap(), JSONObject(), null, "relay:connector")
            val proposal = app.applets.propose(AppletPackage.parse(read("catalog/$key/applet.json")), null,
                JSONObject().put("kind","template").put("template",key).put("revision",revision), null, notify=false)
            val proposalId = proposal.getString("id")
            pending = proposalId
            val record = app.applets.approve(proposalId, null)
            installed = record.getString("id")
            pending = null
            val owner = record.getString("agent_id")
            assertEquals(403, inventory().status)
            store.addAgent(viewer.id,"connector",null,cc.bridgit.app.portal.PortalData.ROUTES.toSet(),emptySet())
            fun save(id: String, agent: String) = app.lanDevices.save(cc.bridgit.app.lan.LanDevice(id, "Test lamp", "192.0.2.4",
                "shelly_gen1", agent, "synthetic-user", "synthetic-secret", false, 1234))
            save("test-owned", owner)
            save("test-other", viewer.id)
            val result = inventory()
            assertEquals(String(result.bytes), 200, result.status)
            val rows = JSONObject(String(result.bytes)).getJSONArray("devices")
            assertEquals(1, rows.length())
            val row = rows.getJSONObject(0)
            assertEquals("test-owned", row.getString("id"))
            assertEquals(setOf("id","name","adapter","availability","verified_at"), row.keys().asSequence().toSet())
            assertEquals(404, inventory(2).status)
            app.agents.update(owner) { it.copy(paused = true) }
            assertEquals(403, inventory().status)
            app.agents.update(owner) { it.copy(paused = false) }
            app.settings.setOn("lan_control", false)
            assertTrue(inventory().status != 200)
        } finally {
            app.lanDevices.remove("test-owned")
            app.lanDevices.remove("test-other")
            store.remove(viewer.id)
            app.agents.remove(viewer.id)
            installed?.let { app.applets.remove(it) }
            pending?.let { app.applets.decline(it) }
            if (startService) cc.bridgit.app.BridgeService.stop(app)
            previous.forEach { (id, on) -> app.settings.setOn(id, on) }
            app.settings.listenerEnabled = listener
        }
        Unit
    }

    @Test fun receiptCardRecognizesOwnerSelectedGalleryPhotoAsInstalledAgent() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as BridgeApp
        val switches = listOf("ocr","gallery","interactions").associateWith { app.settings.isOn(it) }
        val listener = app.settings.listenerEnabled
        val startService = cc.bridgit.app.BridgeService.running == null
        val media = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val photo = requireNotNull(app.contentResolver.insert(media,android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"Bridgit OCR test " + java.util.UUID.randomUUID() + ".png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
        }))
        var installed: String? = null
        var pending: String? = null
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(900,230,android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.BLACK
                    textSize = 62f
                }
                canvas.drawText("RECEIPT TOTAL 42.50",40f,140f,paint)
                requireNotNull(app.contentResolver.openOutputStream(photo)).use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
            } finally { bitmap.recycle() }
            app.settings.listenerEnabled = true
            switches.keys.forEach { app.settings.setOn(it,true) }
            if (startService) cc.bridgit.app.BridgeService.startInBackground(app)
            withTimeout(15_000) { while (cc.bridgit.app.BridgeService.running == null) delay(100) }
            val definition = AppletPackage.parse(read("catalog/receipt-scanner/applet.json"))
            val config = JSONObject().put("gallery_id",android.content.ContentUris.parseId(photo))
            val proposal = app.applets.propose(definition,config,JSONObject().put("kind","file"),null,notify=false)
            val proposalId = proposal.getString("id")
            pending = proposalId
            val installation = app.applets.approve(proposalId,null)
            pending = null
            installed = installation.getString("id")
            val agent = requireNotNull(app.agents.get(installation.getString("agent_id")))
            val plan = AppletPackage.compile(definition,config)
            val pieces = requireNotNull(plan.card?.piecesFilled).getJSONArray("pieces")
            val action = (0 until pieces.length()).mapNotNull { pieces.getJSONObject(it).optJSONArray("items") }
                .map { it.getJSONObject(0).getJSONObject("do") }.first { it.optString("path") == "/v1/ocr" }
            val result = cc.bridgit.app.interact.CardActionRunner.call(app,agent,cc.bridgit.app.interact.CardActions.parse(action.toString()),
                "card:test","card",JSONObject(),"OCR test")
            assertTrue(result.toString(),result.getBoolean("ok"))
            val body = result.getJSONObject("body")
            assertTrue(body.toString(),body.getBoolean("on_device"))
            assertTrue(body.toString(),body.getString("text").contains("42.50"))
        } finally {
            try {
                installed?.let { app.applets.remove(it) }
                pending?.let { app.applets.decline(it) }
            } finally {
                app.contentResolver.delete(photo,null,null)
                if (startService) cc.bridgit.app.BridgeService.stop(app)
                switches.forEach { (id,on) -> app.settings.setOn(id,on) }
                app.settings.listenerEnabled = listener
            }
        }
        Unit
    }

    /** Disposable emulator only. A synthetic enrollment never contacts a camera. */
    @Test fun migratesReviewedFeedingPositionOnTheMonitoringHost() = runBlocking {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val app = instrumentation.targetContext.applicationContext as BridgeApp
        assumeTrue(app.applets.all().isEmpty() && app.lanCameras.all().isEmpty())
        val prefs = app.getSharedPreferences("dog_feeding",android.content.Context.MODE_PRIVATE)
        assumeTrue(!prefs.contains("view") && !app.dog.state.value.running)
        val camera = cc.bridgit.app.lancamera.LanCamera("lan:fixture","Synthetic bowl","192.168.253.254","test","fixture")
        val view = JSONObject().put("camera",camera.id).put("x",0.25).put("y",-0.5).put("profile","fixture")
            .put("enrollment",cc.bridgit.app.lancamera.CameraViews.fingerprint(camera))
        val listener=app.settings.listenerEnabled
        val switches=app.registry.switchable().mapNotNull { it.switchKey }.associateWith { app.settings.isOn(it) }
        val startService=cc.bridgit.app.BridgeService.running==null
        var installed:String?=null
        try {
            app.settings.listenerEnabled=true
            switches.keys.forEach { app.settings.setOn(it,true) }
            val activity=instrumentation.startActivitySync(requireNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName)))
            if(startService) instrumentation.runOnMainSync { cc.bridgit.app.BridgeService.start(activity) }
            withTimeout(15000) { while(cc.bridgit.app.BridgeService.running?.cameraReady!=true) delay(100) }
            app.lanCameras.save(camera)
            app.appDevices.setApps(camera.id,setOf(cc.bridgit.app.appdevices.AppDeviceAccess.DOG))
            val source=JSONObject().put("kind","file")
            val old=AppletPackage.parse(read("previous-dog/applet.json"))
            val proposal=app.applets.propose(old,null,source,null,notify=false)
            val instance=app.applets.approve(proposal.getString("id"),null).getString("id")
            installed=instance
            app.dog.setMicrophones(listOf(camera.id))
            check(prefs.edit().putString("view",view.toString()).putBoolean("enabled",true).commit())
            val candidate=AppletPackage.parse(read("catalog/dog-monitor/applet.json"))
            val pending=app.applets.update(instance,candidate,null,source,null)
            val converted=app.applets.approve(pending.getString("id"),null)
            val principal=converted.getString("agent_id")
            assertEquals(camera.id,converted.getJSONObject("config").getString("bowl_camera"))
            val audio=app.watches.list(principal).single { it.spec.sound!=null }
            assertEquals(listOf(camera.id),audio.spec.sound!!.sources)
            assertTrue(app.agents.get(principal)!!.allows("lan_camera_audio"))
            app.settings.setOn("lan_camera_audio",false)
            assertEquals("capability_disabled",app.watches.accessProblem(principal,audio.spec)?.first)
            app.settings.setOn("lan_camera_audio",true)
            val position=app.cameraViews.status(principal,"bowl")
            assertEquals(camera.id,position.getString("camera"))
            assertEquals(0.25,position.getDouble("x"),0.0)
            assertFalse(position.has("enrollment"))
            assertTrue(app.watches.list(principal).all { it.userPaused })
            val replacement=cc.bridgit.app.lancamera.LanCamera(camera.id,"Replacement",camera.host,"test","rotated")
            app.lanCameras.save(replacement)
            assertNull(app.dog.feeding.transferableView())
            try { app.cameraViews.capture(app,principal,"bowl",camera.id) {}; fail("Stale enrollment accepted") }
            catch(e:cc.bridgit.app.api.ApiError) { assertEquals("camera_view_changed",e.code) }
            app.applets.remove(instance)
            installed=null
            assertFalse(app.cameraViews.status(principal,"bowl").getBoolean("saved"))
        } finally {
            installed?.let { app.applets.remove(it) }
            app.dog.feeding.clear()
            app.appDevices.remove(camera.id)
            app.lanCameras.remove(camera.id)
            if(startService) cc.bridgit.app.BridgeService.stop(app)
            switches.forEach { (id,on) -> app.settings.setOn(id,on) }
            app.settings.listenerEnabled=listener
        }
    }

    @Test fun freshSessionEditionsProduceOwnerReviewsBeforeInstallation() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as BridgeApp
        val before = app.applets.all().map { it.getString("id") }
        for (author in listOf("sol","terra","luna")) {
            val pkg = AppletPackage.parse(read("authors/$author/applet.json"))
            val proposal = app.applets.propose(pkg,null,JSONObject().put("kind","file"),null,notify=false)
            try {
                assertEquals(author,"awaiting_owner",proposal.getString("status"))
                assertTrue(proposal.getJSONObject("review").getJSONArray("permissions").length() > 0)
                assertEquals(before,app.applets.all().map { it.getString("id") })
            } finally { app.applets.decline(proposal.getString("id")) }
        }
    }

    @Test fun offlineAuthorPackageRunsAndRetainsItsAnswerAcrossPauseAndResume() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as BridgeApp
        val original = listOf("interactions","workflows").associateWith { Capabilities.enabled(app,it) }
        val interactions = app.settings.isOn("interactions")
        val listener = app.settings.listenerEnabled
        var pending: String? = null
        var installed: String? = null
        try {
            app.settings.listenerEnabled = true
            app.settings.setOn("interactions",true)
            original.keys.forEach { Capabilities.setEnabled(app,it,true) }
            val pkg = AppletPackage.parse(read("authors/sol/applet.json"))
            val choice = JSONObject().put("plant","Device test basil")
            val proposal = app.applets.propose(pkg,choice,JSONObject().put("kind","file"),null,notify=false)
            val proposalId = proposal.getString("id")
            pending = proposalId
            assertEquals("awaiting_owner",proposal.getString("status"))
            val record = app.applets.approve(proposalId,null)
            pending = null
            val id = record.getString("id")
            installed = id
            val agent = record.getString("agent_id")
            val start = app.platform.workflows.start(agent,"check",JSONObject().put("caller","button"),null)
            assertEquals(start.toString(),"started",start.getString("status"))
            val question = withTimeout(15_000) {
                while (app.platform.store.list(agent,"interaction").none { it.optString("status") == "pending" }) delay(100)
                app.platform.store.list(agent,"interaction").single { it.optString("status") == "pending" }
            }
            assertTrue(question.getString("text").contains("Device test basil"))
            app.platform.answer(question.getString("id"),"Damp")
            val table = AppletTableStore.of(app)
            withTimeout(15_000) { while (table.rows(agent,"observations",1).isEmpty()) delay(100) }
            app.applets.setPaused(id,true)
            assertEquals("flow_not_active",app.platform.workflows.start(agent,"check",JSONObject(),null).getString("status"))
            app.applets.setPaused(id,false)
            val row = table.rows(agent,"observations",10).single()
            assertEquals("Device test basil",row.getString("plant"))
            assertEquals("Damp",row.getString("moisture"))
            val edited = JSONObject(pkg.definition.toString()).apply {
                put("version","1.1.0")
                getJSONObject("defaults").put("plant","Different default")
            }
            app.applets.update(id,AppletPackage.check(edited),null,JSONObject().put("kind","file"),null)
            val updated = requireNotNull(app.applets.get(id))
            assertEquals("1.1.0",updated.getString("version"))
            assertEquals("Device test basil",updated.getJSONObject("config").getString("plant"))
            val csv = AppletExport.export(app,agent,JSONObject().put("table","observations").put("as","csv"))
            try {
                assertEquals(1,csv.rows)
                val contents = csv.file.readText()
                assertTrue(contents.contains("Device test basil") && contents.contains("Damp"))
            } finally { csv.file.delete() }
            app.applets.remove(id)
            assertNull(app.applets.get(id))
            installed = null
        } finally {
            try {
                installed?.let { app.applets.remove(it) }
                pending?.let { app.applets.decline(it) }
            } finally {
                original.forEach { (key,value) -> Capabilities.setEnabled(app,key,value) }
                app.settings.setOn("interactions",interactions)
                app.settings.listenerEnabled = listener
            }
        }
        Unit
    }
}
