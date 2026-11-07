package cc.bridgit.app.judgment

import cc.bridgit.app.api.ApiError
import cc.bridgit.app.ownerapi.OwnerActions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelPreferencesTest {
 @Test fun stablePackageTaskAndExplicitClearAreConfigurable() {
  ModelPreferences.validate("chef-helper", "speech_speak", "speech-aura-1")
  ModelPreferences.validate("chef-helper", "speech_speak", null)
  assertTrue(OwnerActions.NAMES.contains("service.model.set"))
  OwnerActions.validate(JSONObject("""{"action":"service.model.set","options":{"applet_id":"chef-helper","task_key":"speech_speak","model_id":null}}"""))
  assertEquals("/v1/services/models",ServiceCatalog.links().getString("model_preferences"))
 }
 @Test fun invalidIdsUnknownFieldsAndImplicitClearsAreRefused() {
  val invalid=listOf(
   """{"applet_id":"chef-helper","task_key":"speech_speak"}""",
   """{"applet_id":"../other","task_key":"speech_speak","model_id":null}""",
   """{"applet_id":"chef-helper","task_key":"speech_speak","model_id":false}""",
   """{"applet_id":"chef-helper","task_key":"speech_speak","model_id":"speech-aura-1","account":"other"}"""
  )
  for(options in invalid) {
   try { OwnerActions.validate(JSONObject().put("action","service.model.set").put("options",JSONObject(options)));fail("Accepted $options") }
   catch(e:ApiError) { assertEquals("invalid_parameter",e.code) }
  }
 }
}
