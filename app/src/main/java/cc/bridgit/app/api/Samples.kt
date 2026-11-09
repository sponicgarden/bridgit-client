package cc.bridgit.app.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * `GET /v1/samples`: complete interaction samples (bug reports #0009, #0010), one or more per kind, each with
 * its POST body and the answer it produces. The file is site-content/public/samples/samples.json, built from
 * site-content/content/samples.json and the card pages by site-content/scripts/samples.mjs and copied into the APK.
 */
object Samples {
    const val ASSET = "samples.json"
    const val URL = "https://bridgit.cc/samples/samples.json"

    /**
     * [file] is the asset's text. [id] keeps one sample; [kind] keeps one kind. Unknown id: not_found.
     * api_revision is always the installed one (A0026), matching /v1/health and /v1/guide; the file's own
     * number is only when the samples were last regenerated.
     */
    fun select(file: String, id: String?, kind: String?): JSONObject {
        val all = JSONObject(file).put("api_revision", ApiChanges.CURRENT)
        if (id == null && kind == null) return all
        val src = all.getJSONArray("samples")
        val kept = JSONArray()
        for (i in 0 until src.length()) {
            val s = src.getJSONObject(i)
            if ((id == null || s.optString("id") == id) && (kind == null || s.optString("kind") == kind)) kept.put(s)
        }
        if (id != null && kept.length() == 0) {
            val ids = (0 until src.length()).joinToString(", ") { src.getJSONObject(it).optString("id") }
            throw ErrorCatalog.error("not_found", "No sample \"$id\". Samples: $ids.")
        }
        return JSONObject().put("api_revision", all.opt("api_revision")).put("conventions", all.opt("conventions")).put("samples", kept)
    }
}
