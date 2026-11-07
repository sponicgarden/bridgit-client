package cc.bridgit.app.judgment

import cc.bridgit.app.api.Endpoints
import cc.bridgit.app.capability.SettingsConfigurationApi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextServicesTest {
 @Test fun everyContextServiceHasAnInstalledAuthenticatedCallAndHonestKeyStatus() {
  val all=ServiceCatalog.json(true,false).getJSONArray("services")
  for(id in listOf("places","airquality","pollen","timezone","maps","streetview","weathergoogle","osm","wikipedia","openmeteo")) {
   val d=(0 until all.length()).map(all::getJSONObject).single { it.optString("id")==id }
   assertEquals("/v1/services/context",d.getJSONObject("call").getString("path"))
   assertEquals("judgment",d.getJSONObject("call").getString("required_capability"))
   assertEquals("not_checked",d.getJSONObject("availability").getString("provider_key_status"))
   assertFalse(d.getJSONObject("pricing").getBoolean("automatic_provider_cutoff"))
  }
  for(e in ContextServices.ALL) { assertTrue(e.auth);assertTrue(Endpoints.all.contains(e)) }
 }
 @Test fun stoppedPresentationCannotEnqueueAndIllustrationEscapesUntrustedTitle() {
  var called=false
  try { ContextServices.withPresentation("test-agent","expired") { called=true }; fail("Expected stopped") } catch(e:cc.bridgit.app.api.ApiError) { assertFalse(called) }
  val html=FairytaleImageCard.html("<script>bad()</script>","aW1hZ2U=")
  assertFalse(html.contains("<script>bad()"));assertTrue(html.contains("&lt;script&gt;"))
  cc.bridgit.app.interact.CardRules.checkHtml(html)
 }
 @Test fun contextCredentialsUseTheSameWriteOnlyValidatedApiAsOwnerUi() {
  for(id in listOf("googlemaps","openmeteo")) {
   val change=SettingsConfigurationApi.keyChange(JSONObject().put("provider",id).put("fields",org.json.JSONArray(listOf("test-provider-secret"))))
   assertEquals(id,change.first);assertEquals("test-provider-secret",change.second)
   assertNull(SettingsConfigurationApi.keyChange(JSONObject().put("provider",id).put("remove",true)).second)
  }
  assertEquals(setOf("*"),SettingsConfigurationApi.KEYS_SET.privateFields)
 }
}
