package cc.bridgit.app.platform

import cc.bridgit.app.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ApprovalHintTest {
    private val app get()=RuntimeEnvironment.getApplication() as BridgeApp
    @Test fun awaitingOwnerOperationTellsTheAgentWhereTheOwnerApproves() {
        val op=JSONObject().put("id","o1").put("status","awaiting_owner").put("expires_at",123L).put("input",JSONObject().put("x",1))
        val out=app.platform.publicOperation(op)
        assertFalse(out.has("input"))
        val hint=out.getJSONObject("approval")
        assertEquals("More > Agent permissions > Questions and approvals",hint.getString("screen"))
        assertEquals(123L,hint.getLong("expires_at"))
        assertTrue(hint.getString("where").contains("Agent permissions"))
    }
    @Test fun noApprovalHintOnceTheOperationIsNotWaiting() {
        for(status in listOf("accepted","completed","expired")) assertFalse(app.platform.publicOperation(JSONObject().put("id","o").put("status",status)).has("approval"))
    }
    @Test fun hintAdmitsWhenNoNotificationWasPosted() {
        assertFalse(app.platform.approvalHint(false,1).getBoolean("notification_posted"))
        assertTrue(app.platform.approvalHint(false,1).getString("where").contains("no notification"))
        assertTrue(app.platform.approvalHint(true,1).getBoolean("notification_posted"))
    }
}
