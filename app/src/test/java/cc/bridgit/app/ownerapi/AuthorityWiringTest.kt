package cc.bridgit.app.ownerapi

import android.app.NotificationManager
import cc.bridgit.app.BridgeApp
import cc.bridgit.app.core.Connection
import cc.bridgit.app.core.authority.AuthorityDb
import cc.bridgit.app.core.authority.AuthorityFixture
import cc.bridgit.app.core.authority.AuthorityOutbox
import cc.bridgit.app.core.authority.AuthorityStore
import cc.bridgit.app.core.authority.RecoveryMachine
import cc.bridgit.app.platform.Capabilities
import cc.bridgit.app.security.SecurityAlerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The app wiring of approver devices: the generation hooks, the checkpoint and the alert outbox. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthorityWiringTest {
    private val app get() = RuntimeEnvironment.getApplication() as BridgeApp
    private lateinit var kit: ApproverTestKit
    private fun reasons() = app.authority.store.generation.log().map { it.second }

    @Before fun setup() {
        app.settings.listenerEnabled = true
        kit = ApproverTestKit(app, "authority-wiring-test.db")
        kit.emailVerified()
    }

    @Test fun everyChangeToWhoMayDoWhatRaisesTheGenerationOnceTheSwitchIsOn() {
        val early = app.agents.add("Early", Connection.DIRECT).first // before the switch: not counted
        kit.idle()
        assertFalse(app.authority.isOpen)
        kit.enable()
        assertEquals(listOf("master_switch"), reasons())
        val a = app.agents.add("Agent", Connection.DIRECT).first
        kit.idle()
        assertEquals("grant", reasons().last())
        var n = reasons().size
        // Use counters change on every call and are not "who may do what".
        app.agents.recordUse(a.id, "100.64.0.9")
        app.agents.reportModel(a.id, "model-x", "harness-y")
        kit.idle()
        assertEquals(n, reasons().size)
        app.agents.update(a.id) { it.copy(paused = true) }
        kit.idle()
        assertEquals("pause", reasons().last())
        app.agents.update(a.id) { it.copy(paused = false, caps = setOf("status")) }
        kit.idle()
        n = reasons().size
        app.agents.rotate(a.id)
        kit.idle()
        assertTrue(reasons().drop(n).contains("rekey"))
        n = reasons().size
        app.platform.grant(a.id, "owner.configuration", Long.MAX_VALUE)
        kit.idle()
        assertTrue(reasons().drop(n).contains("grant"))
        n = reasons().size
        app.platform.revoke(a.id, "owner.configuration")
        kit.idle()
        assertTrue(reasons().drop(n).contains("revoke"))
        n = reasons().size
        app.settings.setOn("camera", !app.settings.isOn("camera"))
        Capabilities.setEnabled(app, "owner.configuration", true)
        kit.idle()
        assertEquals(listOf("master_switch", "master_switch"), reasons().drop(n))
        n = reasons().size
        app.agents.remove(early.id)
        kit.idle()
        assertTrue(reasons().drop(n).contains("revoke"))
        // The counter only rises, by one per reason.
        assertEquals(reasons().size.toLong(), app.authority.store.generation())
        // Off again: one last raise, then silence.
        kit.enable(false)
        n = reasons().size
        assertEquals("master_switch", reasons().last())
        app.agents.add("Late", Connection.DIRECT)
        kit.idle()
        assertEquals(n, reasons().size)
    }

    @Test fun theCheckpointCountsRunningTimeAndDoesADueClear() {
        kit.enable()
        app.authority.startSelfEnrol("test"); kit.pass()
        app.authority.recovery.start("agent-1", emailVerified = true)
        kit.time.run(73 * 3_600_000L)
        app.authority.checkpoint()
        assertTrue(app.authority.store.approvers().isEmpty())
        assertEquals(RecoveryMachine.CLEARED, app.authority.recovery.status().state)
    }

    @Test fun anAlertIsStoredWithItsChangeAndShownOnTheSecurityChannel() {
        kit.enable()
        app.authority.startSelfEnrol("test"); kit.pass()
        app.authority.recovery.start("agent-1", emailVerified = true)
        kit.idle()
        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        val posted = nm.allNotifications.filter { it.channelId == SecurityAlerts.CHANNEL }
        assertEquals(2, posted.size)
        assertTrue(app.authority.store.undelivered(AuthorityOutbox.Way.LOCAL).isEmpty())
        // bridgit.cc can't be reached, and approver delivery has no sender yet: their rows wait, in order.
        assertEquals(listOf("approvers_on", "approver_enrolled", "recovery_started"), app.authority.store.undelivered(AuthorityOutbox.Way.EMAIL).map { it.kind })
        assertEquals(listOf("approvers_on", "approver_enrolled", "recovery_started"), app.authority.store.undelivered(AuthorityOutbox.Way.APPROVERS).map { it.kind })
        val started = app.authority.store.undelivered(AuthorityOutbox.Way.EMAIL).last()
        assertEquals("agent-1", started.body.getString("by"))
        assertTrue(started.body.getLong("clear_at") > kit.time.wall)
        // Never a key in an alert.
        assertFalse(started.body.toString().contains("key"))
    }

    @Test fun anAlertSurvivesAStopBetweenTheChangeAndItsDelivery() {
        // A store with no delivery at all: the process stopped right after the change was kept.
        val f = AuthorityFixture(app, "authority-outbox-test.db")
        f.enrolFirst("phone")
        f.recovery.start("agent-1", emailVerified = true)
        f.close()
        // The next start finds both alerts waiting.
        val again = AuthorityStore(AuthorityDb(app, "authority-outbox-test.db"), f.time)
        assertEquals(listOf("approver_enrolled", "recovery_started"), again.undelivered(AuthorityOutbox.Way.LOCAL).map { it.kind })
        again.delivered(again.undelivered(AuthorityOutbox.Way.LOCAL).first().id, AuthorityOutbox.Way.LOCAL)
        assertEquals(listOf("recovery_started"), again.undelivered(AuthorityOutbox.Way.LOCAL).map { it.kind })
        // A change that is rolled back leaves no alert.
        runCatching { again.tx { tx -> RecoveryMachine.cancelPending(tx, "x"); error("stop") } }
        assertEquals(listOf("recovery_started"), again.undelivered(AuthorityOutbox.Way.LOCAL).map { it.kind })
    }

    private fun mailedKinds() = kit.mailed.map { it.getString("kind") }
    /** The checkpoint, then whatever it left for the authority and mail threads. */
    private fun checkpoint() { app.authority.checkpoint(); kit.idle() }
    private fun emailWaiting() = app.authority.store.undelivered(AuthorityOutbox.Way.EMAIL).map { it.kind }

    @Test fun everyApproverChangeIsMailedWithOnlyItsKindAndTime() {
        kit.mailOnline()
        kit.enable()
        app.authority.startSelfEnrol("test"); kit.pass()
        app.authority.recovery.start("agent-1", emailVerified = true)
        kit.idle()
        assertEquals(listOf("approvers_on", "approver_enrolled", "recovery_started"), mailedKinds())
        assertTrue(emailWaiting().isEmpty())
        for (m in kit.mailed) assertTrue(m.toString(), (m.keys().asSequence().toSet() - setOf("event_id", "kind", "at_ms", "clear_at_ms")).isEmpty())
        assertTrue(kit.mailed.none { it.toString().contains("agent-1") })
        // Still waiting a day later: the checkpoint sends the reminder, once.
        kit.time.run(RecoveryMachine.DAILY_MS)
        checkpoint()
        checkpoint()
        assertEquals(listOf("approvers_on", "approver_enrolled", "recovery_started", "recovery_daily"), mailedKinds())
        assertEquals(kit.mailed[2].getLong("clear_at_ms"), kit.mailed[3].getLong("clear_at_ms"))
        // Approver delivery is untouched by the email sender.
        assertEquals(4, app.authority.store.undelivered(AuthorityOutbox.Way.APPROVERS).size)
    }

    @Test fun askingToTurnApproversOffAndTheOwnerDoingItAreBothMailedThoughTheSwitchIsThenOff() {
        kit.mailOnline()
        kit.enable()
        app.authority.startSelfEnrol("test"); kit.pass()
        val p = app.authority.requestOff("agent-1")
        kit.idle()
        assertEquals("asking again for the waiting request sends nothing new", p.id, app.authority.requestOff("agent-1").id)
        kit.idle()
        assertEquals(listOf("approvers_on", "approver_enrolled", "switch_off_requested"), mailedKinds())
        kit.pass(p.id)
        assertFalse(app.settings.approversEnabled)
        assertEquals("approvers_off", mailedKinds().last())
        assertEquals(1, mailedKinds().count { it == "switch_off_requested" })
        assertTrue(emailWaiting().isEmpty())
        // Off: the checkpoint sends nothing more.
        val n = kit.mailed.size
        checkpoint()
        assertEquals(n, kit.mailed.size)
    }

    @Test fun theMailAboutTurningApproversOffIsTriedAgainWhileBridgitCantBeReached() {
        kit.enable()
        kit.idle()
        assertEquals(listOf("approvers_on"), mailedKinds()) // tried, offline
        kit.enable(false)
        assertEquals(listOf("approvers_on", "approvers_off"), emailWaiting())
        kit.mailOnline()
        kit.time.run(2 * 3_600_000L)
        checkpoint()
        assertTrue(emailWaiting().isEmpty())
        assertEquals(listOf("approvers_on", "approvers_off"), mailedKinds().takeLast(2))
        val n = kit.mailed.size
        checkpoint()
        assertEquals(n, kit.mailed.size)
    }

    @Test fun withTheSwitchOffTheCheckpointNeitherOpensTheStoreNorMails() {
        kit.mailOnline()
        checkpoint()
        kit.idle()
        assertFalse(app.authority.isOpen)
        assertTrue(kit.mailed.isEmpty())
    }

    @Test fun withoutAConfirmedAddressTheRowsWaitAndGoOnceItIsConfirmed() {
        kit.mailOnline()
        kit.emailVerified(false)
        kit.enable()
        checkpoint()
        assertTrue(kit.mailed.isEmpty())
        assertEquals(listOf("approvers_on"), emailWaiting())
        kit.emailVerified()
        checkpoint()
        assertEquals(listOf("approvers_on"), mailedKinds())
        assertTrue(emailWaiting().isEmpty())
    }

    /** The app process stopped and started again: a new [cc.bridgit.app.core.authority.Authority] on the same files. */
    private fun restarted() = cc.bridgit.app.core.authority.Authority(app).also {
        it.dbName = "authority-wiring-test.db"
        it.time = kit.time
        it.mailTransport = app.authority.mailTransport
    }

    private fun wait(a: cc.bridgit.app.core.authority.Authority) { kit.wait(a.post { }); kit.wait(a.mailIdle()) }

    @Test fun mailQueuedWhenTheSwitchWentOffOfflineIsSentAfterARestartWithTheSwitchStillOff() {
        kit.enable()
        kit.enable(false) // offline
        assertEquals(listOf("approvers_on", "approvers_off"), emailWaiting())
        assertTrue(app.authority.mailOwed)
        val again = restarted()
        assertFalse(again.isOpen)
        assertTrue("the obligation is stored, not remembered", again.mailOwed)
        again.checkpoint(); wait(again)
        assertEquals("still offline: the rows wait", listOf("approvers_on", "approvers_off"), emailWaiting())
        assertTrue(again.mailOwed)
        // Another restart, and bridgit.cc can be reached again. The switch is off the whole time.
        kit.mailOnline()
        val third = restarted()
        third.checkpoint(); wait(third)
        assertFalse(app.settings.approversEnabled)
        assertTrue(emailWaiting().isEmpty())
        assertEquals(listOf("approvers_on", "approvers_off"), mailedKinds().takeLast(2))
        assertFalse(third.mailOwed)
        // Nothing owed: the next start leaves authority.db closed.
        val fourth = restarted()
        fourth.checkpoint(); wait(fourth)
        assertFalse(fourth.isOpen)
    }

    @Test fun aSwitchChangeStoppedBeforeItsAlertWasStoredIsFinishedOnceOnTheNextStart() {
        kit.mailOnline()
        kit.enable()
        val before = reasons().size
        // The app stops right after the setting moved: nothing reached authority.db.
        app.authority.beginSwitch(false, "agent-1")
        assertFalse(app.settings.approversEnabled)
        assertFalse(emailWaiting().contains("approvers_off"))
        val again = restarted()
        again.attach(); wait(again)
        again.checkpoint(); wait(again)
        again.reconcile(); wait(again)
        assertEquals("the alert is stored and mailed exactly once", 1, mailedKinds().count { it == "approvers_off" })
        assertEquals(before + 1, reasons().size)
        assertEquals("master_switch", reasons().last())
        assertFalse(app.settings.approversEnabled)
        assertFalse(again.mailOwed)
        // The same for turning on, and when the app stopped before the setting itself was written.
        again.beginSwitch(true, "agent-1")
        app.settings.approversEnabled = false
        val third = restarted()
        third.attach(); wait(third)
        third.reconcile(); wait(third)
        assertTrue(app.settings.approversEnabled)
        assertEquals(2, mailedKinds().count { it == "approvers_on" })
        assertEquals(before + 2, reasons().size)
    }

    @Test fun theOwnersSwitchOffIsKeptInTheStoreFirstAndTheSettingFollowsEvenAfterAStopInBetween() {
        kit.mailOnline()
        kit.enable()
        app.authority.startSelfEnrol("test"); kit.pass()
        val p = app.authority.requestOff("agent-1")
        kit.pass(p.id)
        assertFalse(app.settings.approversEnabled)
        assertEquals(1, mailedKinds().count { it == "approvers_off" })
        // As if the app had stopped after authority.db kept the change and before the setting was written.
        app.settings.approversEnabled = true
        val again = restarted()
        again.attach(); wait(again)
        assertFalse(app.settings.approversEnabled)
        assertEquals("no second alert", 1, mailedKinds().count { it == "approvers_off" })
        // A check that fails changes neither the store nor the setting.
        kit.enable()
        val q = app.authority.requestOff("agent-1")
        kit.pass(q.id, sign = cc.bridgit.app.core.authority.SoftwareApproverKeys().apply { create(ByteArray(32)) })
        assertTrue(app.settings.approversEnabled)
        assertEquals(1, mailedKinds().count { it == "approvers_off" })
    }
}
