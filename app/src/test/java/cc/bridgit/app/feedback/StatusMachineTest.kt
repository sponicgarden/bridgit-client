package cc.bridgit.app.feedback

import cc.bridgit.app.feedback.IssueStatus.CONFIRMED
import cc.bridgit.app.feedback.IssueStatus.DUPLICATE
import cc.bridgit.app.feedback.IssueStatus.FIXED
import cc.bridgit.app.feedback.IssueStatus.IN_PROGRESS
import cc.bridgit.app.feedback.IssueStatus.NEW
import cc.bridgit.app.feedback.IssueStatus.REOPENED
import cc.bridgit.app.feedback.IssueStatus.SEEN
import cc.bridgit.app.feedback.IssueStatus.WONT_FIX
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ways the FBK-11a status machine can fail:
 * a developer agent closes an issue (confirmed) or reopens it; the owner's Confirm works on an issue nobody
 * fixed; a fixed issue jumps back to in_progress; `reopened` doesn't behave as `new`; the automatic
 * first-read move fires on an issue already in progress; a disallowed move is silently accepted.
 * The test enumerates every (from, to, actor) triple against the spec table.
 */
class StatusMachineTest {
    private val expected: Set<Triple<IssueStatus, IssueStatus, Actor>> = buildSet {
        add(Triple(NEW, SEEN, Actor.AUTO)); add(Triple(REOPENED, SEEN, Actor.AUTO))
        for (f in listOf(NEW, SEEN, REOPENED)) for (t in listOf(IN_PROGRESS, DUPLICATE, WONT_FIX)) add(Triple(f, t, Actor.DEVELOPER))
        for (t in listOf(FIXED, WONT_FIX)) add(Triple(IN_PROGRESS, t, Actor.DEVELOPER))
        for (f in listOf(FIXED, WONT_FIX, DUPLICATE)) for (t in listOf(CONFIRMED, REOPENED)) add(Triple(f, t, Actor.OWNER))
        // FBK-12a: the agent that filed an issue has the owner's Confirm / Still broken on it, nothing more.
        for (f in listOf(FIXED, WONT_FIX, DUPLICATE)) for (t in listOf(CONFIRMED, REOPENED)) add(Triple(f, t, Actor.REPORTER))
        // FBK-14: the team moves anything the owner hasn't closed; seen only from new.
        add(Triple(NEW, SEEN, Actor.TEAM)); add(Triple(REOPENED, SEEN, Actor.TEAM))
        for (f in IssueStatus.values()) if (f != CONFIRMED) for (t in listOf(IN_PROGRESS, FIXED, WONT_FIX, DUPLICATE)) if (f != t) add(Triple(f, t, Actor.TEAM))
    }

    @Test fun everyTransitionMatchesTheSpecTable() {
        var checked = 0
        for (f in IssueStatus.values()) for (t in IssueStatus.values()) for (a in Actor.values()) {
            assertEquals("$f -> $t by $a", Triple(f, t, a) in expected, StatusMachine.allowed(f, t, a))
            checked++
        }
        assertEquals(8 * 8 * 5, checked)
    }

    @Test fun teamStatusesMapOntoThePhone() {
        assertEquals(FIXED, StatusMachine.fromTeam(NEW, "fixed"))
        assertEquals(FIXED, StatusMachine.fromTeam(IN_PROGRESS, "fixed"))
        assertEquals(IN_PROGRESS, StatusMachine.fromTeam(REOPENED, "in_progress"))
        assertEquals(IN_PROGRESS, StatusMachine.fromTeam(FIXED, "in_progress")) // the team takes a fix back
        assertEquals(SEEN, StatusMachine.fromTeam(NEW, "seen"))
        assertEquals(null, StatusMachine.fromTeam(IN_PROGRESS, "seen"))   // never moves an issue backwards to seen
        assertEquals(null, StatusMachine.fromTeam(FIXED, "fixed"))        // already there
        assertEquals(null, StatusMachine.fromTeam(CONFIRMED, "wont_fix")) // the owner closed it
        assertEquals(null, StatusMachine.fromTeam(SEEN, "new"))
        assertEquals(null, StatusMachine.fromTeam(SEEN, "confirmed"))
        assertEquals(null, StatusMachine.fromTeam(SEEN, null))
    }

    @Test fun ownerIsToldOnlyWhenThereIsSomethingToCheck() {
        assertEquals(setOf(FIXED, WONT_FIX, DUPLICATE), IssueStatus.values().filter { StatusMachine.notifiesOwner(it) }.toSet())
        assertEquals(listOf(IN_PROGRESS, WONT_FIX, DUPLICATE), StatusMachine.nextForDeveloper(REOPENED))
    }

    @Test fun reporterFollowUpsMapOntoConfirmAndStillBroken() {
        assertEquals(CONFIRMED, StatusMachine.outcome("works"))
        assertEquals(REOPENED, StatusMachine.outcome("still_broken"))
        assertEquals(null, StatusMachine.outcome("fixed"))
        assertEquals(null, StatusMachine.outcome(null))
        assertEquals(listOf("works", "still_broken"), StatusMachine.outcomesForReporter(FIXED))
        assertEquals(listOf("works", "still_broken"), StatusMachine.outcomesForReporter(DUPLICATE))
        // Before anyone says it's fixed, and after it's closed, the filing agent can only add a note.
        for (s in listOf(NEW, SEEN, IN_PROGRESS, REOPENED, CONFIRMED)) assertEquals("$s", emptyList<String>(), StatusMachine.outcomesForReporter(s))
    }

    @Test fun actorIdsAreWhatTheBackendAccepts() {
        // bridgit-cloud/feedback/src/worker.mjs ACTORS; the team's own moves are never sent back.
        assertEquals(listOf("auto", "developer", "owner", "team", "reporter"), Actor.values().map { it.id })
    }
}
