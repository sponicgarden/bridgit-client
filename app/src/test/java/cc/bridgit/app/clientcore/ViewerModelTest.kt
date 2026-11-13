package cc.bridgit.app.clientcore

import cc.bridgit.app.core.session.ViewerModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** core.session: Android builds the same viewer.model/1 as the pack (contracts/client-core/viewer/) from the same replies. */
class ViewerModelTest {
    private fun resource(p: String) = javaClass.getResource("/client-core/$p")!!.readText()

    @Test fun everyViewerCaseGivesItsModel() {
        val index = JSONArray(resource("viewer/index.json"))
        assertEquals(true, index.length() >= 2)
        for (i in 0 until index.length()) {
            val case = JSONObject(resource("viewer/${index.getString(i)}"))
            val r = case.getJSONObject("responses")
            val got = ViewerModel.from(r.optJSONObject("home"), r.optJSONObject("agents"), r.optJSONObject("applets"), r.optJSONObject("logs"))
            assertEquals(index.getString(i), canon(case.getJSONObject("model")), canon(got))
        }
    }

    private fun canon(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canon(v.get(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.get(it)) }
        is String -> JSONObject.quote(v)
        is Number -> if (v.toDouble() == Math.floor(v.toDouble()) && !v.toDouble().isInfinite()) v.toLong().toString() else v.toString()
        JSONObject.NULL, null -> "null"
        else -> v.toString()
    }
}
