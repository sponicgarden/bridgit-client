package cc.bridgit.app.applets

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppletIconTest {
    @Test fun pngIconTravelsInAnOfflinePackage() {
        val doc = JSONObject(File("../docs/applet-authoring-examples/png-icon.json").readText())
        val checked = AppletPackage.check(doc)
        val asset = checked.definition.getJSONObject("assets").getJSONObject("blue.png")
        assertEquals(69, AppletPackage.pngBytes(asset).size)
        asset.put("size", 1)
        assertThrows(AppletPackage.Bad::class.java) { AppletPackage.check(doc) }
    }
}
