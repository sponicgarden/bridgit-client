package cc.bridgit.app.applets

import cc.bridgit.app.BridgeApp
import cc.bridgit.app.platform.FlowSteps
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ChefHelperTest {
 @Test fun cameraChoiceMigratesWithoutChangingExistingDataOrPermissions() {
  val d=JSONObject(File("src/test/resources/applets/chef-helper-1.0.1.json").readText())
  val tables=d.getJSONObject("processing").getJSONArray("tables")
  val activity=(0 until tables.length()).map { tables.getJSONObject(it) }.first { it.getString("id")=="activity" }
  activity.getJSONObject("columns").remove("camera")
  val before=d.toString()
  val migrated=AppletStorageMigration.chefCamera(d)!!
  assertEquals(before,d.toString())
  assertEquals(d.getJSONObject("permissions").toString(),migrated.getJSONObject("permissions").toString())
  assertNull(AppletStorageMigration.chefCamera(migrated))
  val t=AppletTables.parse(migrated.getJSONObject("processing")).first { it.id=="activity" }
  assertEquals(listOf("rear","front"),t.columns.getValue("camera").options)
  assertEquals("rear",ChefHelper.camera(null))
  assertEquals("rear",ChefHelper.camera(JSONObject().put("camera","invalid")))
  assertEquals("front",ChefHelper.camera(JSONObject().put("camera","front")))
  for (camera in listOf("front","rear")) {
   val row=AppletTables.coerce(t,JSONObject().put("kind","settings").put("camera",camera))
   assertEquals(camera,row.getString("camera"))
  }
  try { AppletTables.coerce(t,JSONObject().put("camera","side")); fail("Invalid camera must be refused") } catch (_:AppletTables.Refused) {}
  AppletPackage.check(migrated)
 }
 @Test fun signedLibraryContextRequiresTheReviewedEditionAndInstalledFlow() {
  val source = JSONObject().put("kind","template").put("template","tpl_family").put("revision",4).put("library_account","a".repeat(64))
  val mapped = JSONObject().put("workflow_id","wf1").put("digest","reviewed")
  val instance = JSONObject().put("source",source).put("flows",JSONObject().put("read",mapped)).put("package_id","family-kitchen")
  val flow = JSONObject().put("id","wf1").put("package_flow","read").put("approved_digest","reviewed")
  fun refused() = assertThrows(cc.bridgit.app.api.ApiError::class.java) { LibraryVision.context(instance,flow) }
  val context = LibraryVision.context(instance,flow)!!
  assertEquals("read",context.getString("action"))
  assertEquals("tpl_family",context.getString("key"))
  assertEquals(4,context.getInt("revision"))
  flow.put("approved_digest","edited"); refused(); flow.put("approved_digest","reviewed")
  flow.put("id","another"); refused(); flow.put("id","wf1")
  instance.put("edition_source",JSONObject().put("kind","agent_inline")); refused(); instance.remove("edition_source")
  source.remove("library_account"); refused()
 }

 @Test fun kitchenServiceFollowsDeclaredStepsAcrossForks() {
  val recipe = JSONObject(File("src/test/resources/applets/chef-helper-1.0.1.json").readText())
  recipe.put("id", "family-kitchen")
  val fork = JSONObject().put("package_id", "family-kitchen").put("definition", recipe)
  assertTrue(ChefHelper.usesKitchen(fork))
  val processing = recipe.getJSONObject("processing")
  val watch = processing.getJSONArray("flows").getJSONObject(1)
  processing.remove("flows")
  processing.put("workflow", watch)
  assertTrue(ChefHelper.usesKitchen(fork))
  watch.getJSONArray("steps").getJSONObject(0).put("type", "notice")
  fork.put("package_id", "chef-helper")
  assertFalse(ChefHelper.usesKitchen(fork))
 }

 @Test fun chefNavigationOpensItsOwnPortal() {
  val app=RuntimeEnvironment.getApplication() as BridgeApp
  runCatching { androidx.work.WorkManager.getInstance(app) }.onFailure { androidx.work.WorkManager.initialize(app,androidx.work.Configuration.Builder().build()) }
  val d=JSONObject(File("src/test/resources/applets/chef-helper-1.0.1.json").readText())
  val inst=JSONObject().put("id","chef-instance").put("package_id","chef-helper").put("definition",d)
  app.remoteView.packages.configure("chef-helper",true,null)
  val intent=cc.bridgit.app.ui.applets.AppletNavigation.openIntent(app,inst)
  assertEquals("pkg.chef-helper",intent.getStringExtra(cc.bridgit.app.ui.MainActivity.EXTRA_OPEN_PORTAL))
  assertEquals(cc.bridgit.app.ui.MainActivity::class.java.name,intent.component!!.className)
  app.remoteView.packages.configure("chef-helper",false,null)
 }
 @Test fun standaloneScanUsesOnlyItsImageAndArea() {
  val scan = ChefHelper.requestBody("scan", "photo", "Spices") { error("A scan must never read observation context") }
  assertEquals(setOf("mode","image","area"), scan.keys().asSequence().toSet())
  assertEquals("Spices", scan.getString("area"))
  val observation = ChefHelper.requestBody("observe", "photo", "") { JSONObject().put("goals","less salt") }
  assertEquals("less salt", observation.getString("goals"))
 }
 @Test fun watchingRequiresAnExplicitUnexpiredSession() {
  val now=10_000_000L
  assertFalse(ChefHelper.active(null,now))
  val s=JSONObject().put("watching",true).put("started_at",now-1000)
  assertTrue(ChefHelper.active(s,now))
  assertFalse(ChefHelper.active(s,now+ChefHelper.SESSION_MS))
  assertFalse(ChefHelper.active(s.put("watching",false),now))
  assertFalse(ChefHelper.active(s.put("watching",true).put("started_at",now+1),now))
 }
 @Test fun packageDeclaresEveryDerivedPermissionAndStartsWithNoCapture() {
  val app=RuntimeEnvironment.getApplication() as BridgeApp
  val d=JSONObject(File("../applets/chef-helper/applet.json").readText())
  val p=AppletPackage.check(d);val plan=AppletPackage.compile(p,JSONObject())
  assertTrue(AppletPackage.undeclared(p,app.applets.derived(plan)).isEmpty())
  assertTrue(AppletPackage.paid(plan,p.declared))
  assertFalse(ChefHelper.watching(app,"absent-agent"))
  val watch=plan.flows.first { it.first=="watch" }.second
  assertEquals(10,watch.getJSONObject("trigger").getInt("seconds"))
  val check=FlowSteps.check(watch.getJSONArray("steps"))
  assertTrue(check.scopes.containsAll(listOf("decisions","camera.snapshot","audio.speak")))
 }
 @Test fun forkControlsItsObservationWindowAndTipFrequency() {
  val step = JSONObject().put("type","kitchen").put("mode","observe").put("session_s",60).put("tip_gap_s",20)
  FlowSteps.check(JSONArray().put(step))
  val policy = FlowSteps.cookingTiming(step)
  assertEquals(60,policy.sessionS)
  assertEquals(20,policy.tipGapS)
  val settings = JSONObject().put("watching",true).put("started_at",1000L)
  assertTrue(ChefHelper.active(settings,60_999L,policy.sessionS * 1000L))
  assertFalse(ChefHelper.active(settings,61_000L,policy.sessionS * 1000L))
  for (field in listOf("session_s","tip_gap_s")) {
   for (invalid in listOf(9,3601,10.5)) {
    val bad = JSONObject(step.toString()).put(field,invalid)
    try { FlowSteps.check(JSONArray().put(bad)); fail("Accepted $field=$invalid") } catch (_:cc.bridgit.app.api.ApiError) {}
   }
  }
 }

 @Test fun kitchenStepsArePaidAndRejectUnboundedModesAndRawUrls() {
  val observing=JSONObject().put("type","kitchen").put("mode","observe")
  assertEquals(1,FlowSteps.check(JSONArray().put(observing)).paidSteps)
  for(s in listOf(observing.put("mode","anything"),JSONObject().put("type","kitchen").put("mode","scan").put("photo","https://example.com/pic"))) {
   try { FlowSteps.check(JSONArray().put(s));fail("Expected validation failure") } catch(_:cc.bridgit.app.api.ApiError) {}
  }
 }
}
