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
    @Test fun hintUsesWhetherTheNotificationWasActuallyPosted() {
        val op=JSONObject().put("id","o2").put("status","awaiting_owner").put("expires_at",123L).put("notification_posted",false)
        val out=app.platform.publicOperation(op)
        assertFalse(out.getJSONObject("approval").getBoolean("notification_posted"))
        assertTrue(out.getJSONObject("approval").getBoolean("home_banner"))
        assertFalse(out.has("notification_posted"))
    }
    @Test fun pendingApprovalsLeaveOutExpiredAndDecidedRequests() {
        val now=1_000_000L
        assertTrue(isPendingApproval(JSONObject().put("kind","operation").put("status","awaiting_owner").put("expires_at",now+1),now))
        assertFalse(isPendingApproval(JSONObject().put("kind","operation").put("status","awaiting_owner").put("expires_at",now-1),now))
        assertFalse(isPendingApproval(JSONObject().put("kind","operation").put("status","accepted").put("expires_at",now+1),now))
        assertTrue(isPendingApproval(JSONObject().put("kind","session").put("status","awaiting_owner").put("request_expires_at",now+1),now))
        assertFalse(isPendingApproval(JSONObject().put("kind","session").put("status","awaiting_owner").put("request_expires_at",now-1),now))
        assertTrue(isPendingApproval(JSONObject().put("kind","workflow").put("status","awaiting_owner"),now))
        assertTrue(isPendingApproval(JSONObject().put("kind","interaction").put("status","pending").put("expires_at",now+1),now))
        assertFalse(isPendingApproval(JSONObject().put("kind","event").put("status","awaiting_owner"),now))
    }
    @Test fun waitingOperationsAndSessionsUseTheHeadsUpChannel() {
        val high=cc.bridgit.app.interact.InteractionNotifier.CHANNEL_QUESTIONS
        assertEquals(high,PlatformNotifications.channelFor(JSONObject().put("kind","operation")))
        assertEquals(high,PlatformNotifications.channelFor(JSONObject().put("kind","session")))
        assertEquals(BridgeApp.INTERACTION_CHANNEL,PlatformNotifications.channelFor(JSONObject().put("kind","workflow")))
        assertEquals("device.action open_app",PlatformNotifications.actionLabel(JSONObject().put("command","device.action").put("input",JSONObject().put("type","open_app"))))
    }
    @Test fun hintAdmitsWhenNoNotificationWasPosted() {
        assertFalse(app.platform.approvalHint(false,1).getBoolean("notification_posted"))
        assertTrue(app.platform.approvalHint(false,1).getString("where").contains("no notification"))
        assertTrue(app.platform.approvalHint(true,1).getBoolean("notification_posted"))
    }
}
