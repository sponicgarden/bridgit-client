package cc.bridgit.app.watch

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one-line results the Camera tab and the Portal show for an owner photo. */
class OwnerPhotosTest {
    @Test fun `answers read as short lines`() {
        val c = JSONObject().put("answers", JSONObject().put("state", JSONObject().put("answer", "messy").put("confidence", 0.8)).put("visible", true))
        assertEquals("answered", OwnerPhotos.status(c))
        val line = OwnerPhotos.summary(c)
        assert("state: messy" in line && "visible: yes" in line) { line }
    }

    @Test fun `model answer shapes read as values`() {
        val c = JSONObject().put("answers", JSONObject()
            .put("visible", JSONObject().put("type", "noul").put("noul", 0.0134))
            .put("state", JSONObject().put("type", "choice").put("choice", "messy").put("confidence", 0.59)))
        val line = OwnerPhotos.summary(c)
        assert("visible: no" in line && "state: messy" in line) { line }
    }

    @Test fun `skips, people checks and errors say why`() {
        val skipped = JSONObject().put("skipped", "max_paid_per_hour").put("message", "6 paid checks in the last hour already; this photo wasn't checked.")
        assertEquals("skipped", OwnerPhotos.status(skipped))
        assertEquals("6 paid checks in the last hour already; this photo wasn't checked.", OwnerPhotos.summary(skipped))
        assertEquals("people_checked", OwnerPhotos.status(JSONObject().put("people", "checked")))
        assertEquals("Looked for people.", OwnerPhotos.summary(JSONObject().put("people", "checked")))
        val err = JSONObject().put("error", "rate_limited")
        assertEquals("error", OwnerPhotos.status(err))
        assertEquals("Couldn't check it (rate_limited).", OwnerPhotos.summary(err))
    }
}
