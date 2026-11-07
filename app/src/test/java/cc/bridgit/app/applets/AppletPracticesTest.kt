package cc.bridgit.app.applets
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class AppletPracticesTest {
 @Test fun sharedContractFixtures() {
  val cases=JSONObject(File("../site-content/content/builder/practice-fixtures.json").readText()).getJSONArray("cases")
  for(i in 0 until cases.length()) {
   val fixture=cases.getJSONObject(i);val candidate=fixture.getJSONObject("candidate");val previous=fixture.optJSONObject("previous")
   if(fixture.has("error")) {
    val error=runCatching { AppletPractices.requireChange(candidate,previous) }.exceptionOrNull()
    assertTrue("${fixture.getString("name")}: $error",error?.message?.contains(fixture.getString("error"))==true)
   } else {
    val report=AppletPractices.assess(candidate,previous)
    assertEquals(fixture.getString("name"),fixture.getBoolean("valid"),report.getBoolean("valid"))
    val expected=fixture.optJSONObject("statuses") ?: JSONObject();val results=report.getJSONArray("results")
    for(id in expected.keys()) {
     val row=(0 until results.length()).map(results::getJSONObject).first { it.getString("practice")==id }
     assertEquals("$id: ${fixture.getString("name")}",expected.getString(id),row.getString("status"))
    }
   }
  }
 }
 @Test fun parserKeepsOldRecipesReadableAndRejectsUnknownContract() {
  val d=JSONObject(File("../site-content/public/recipes/voice-notebook/applet.json").readText())
  AppletPackage.check(d)
  d.getJSONObject("ui").put("practices",JSONObject().put("contract","unsupported"))
  assertTrue(runCatching { AppletPackage.check(d) }.exceptionOrNull() is AppletPackage.Bad)
 }
}
