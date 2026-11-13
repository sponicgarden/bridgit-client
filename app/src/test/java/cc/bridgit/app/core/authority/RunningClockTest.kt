package cc.bridgit.app.core.authority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Architecture §4.7 "Clocks": running time is counted from checkpoints, and the wall clock shortens nothing. */
class RunningClockTest {
    private class Memory(var state: ClockCheckpoints.State = ClockCheckpoints.State(0, 0, 0), var writes: Int = 0) : ClockCheckpoints {
        override fun read() = state
        override fun write(state: ClockCheckpoints.State) { this.state = state; writes++ }
    }

    private val time = FakeTime()
    private val disk = Memory()
    private fun process(n: Long) = RunningClock(time, disk, session = n)

    @Test fun countsOnlyTimeSinceBootDeltas() {
        val clock = process(1)
        assertEquals(0, clock.checkpoint())
        time.run(5 * HOUR)
        assertEquals(5 * HOUR, clock.checkpoint())
        time.wall += 500 * HOUR // someone sets the clock forward
        assertEquals(5 * HOUR, clock.checkpoint())
        time.wall -= 900 * HOUR // and far back
        time.run(HOUR)
        assertEquals(6 * HOUR, clock.checkpoint())
        assertEquals(6 * HOUR, disk.state.countedMs)
    }

    @Test fun aRestartResumesFromTheLastCheckpoint() {
        val first = process(1)
        first.checkpoint()
        time.run(10 * HOUR)
        first.checkpoint()
        time.run(3 * HOUR) // never checkpointed: lost
        time.off(40 * HOUR) // powered off: not counted; time-since-boot starts over
        val second = process(2)
        assertEquals(10 * HOUR, second.checkpoint())
        time.run(2 * HOUR)
        assertEquals(12 * HOUR, second.checkpoint())
    }

    @Test fun aProcessRestartWithoutARebootCountsNothingForTheGap() {
        process(1).apply { checkpoint(); time.run(HOUR); checkpoint() }
        time.run(7 * HOUR) // the app was not running; time-since-boot kept going
        val second = process(2)
        assertEquals(HOUR, second.checkpoint())
        time.run(HOUR)
        assertEquals(2 * HOUR, second.checkpoint())
    }

    @Test fun neverGoesDownAndNeverCountsABackwardStep() {
        val clock = process(1)
        clock.checkpoint()
        time.run(4 * HOUR)
        assertEquals(4 * HOUR, clock.checkpoint())
        time.elapsed -= 3 * HOUR // not possible within one boot; must count nothing
        assertEquals(4 * HOUR, clock.checkpoint())
        time.run(HOUR)
        assertEquals(5 * HOUR, clock.checkpoint())
    }

    @Test fun twoClocksOnOneStoreShareOneCounter() {
        val a = process(1)
        a.checkpoint()
        time.run(10 * HOUR)
        val b = process(1) // made late, before a stored anything new
        assertEquals("b sees the time a has not stored yet", 10 * HOUR, b.checkpoint())
        assertEquals("and a does not count it a second time", 10 * HOUR, a.checkpoint())
        time.run(HOUR)
        assertEquals(11 * HOUR, a.checkpoint())
        assertEquals(11 * HOUR, b.checkpoint())
    }

    @Test fun twoProcessesTakingTurnsNeverCountMoreThanTheTimeThatPassed() {
        val a = process(1)
        val b = process(2)
        a.checkpoint()
        repeat(10) {
            time.run(HOUR)
            a.checkpoint()
            time.run(HOUR)
            b.checkpoint()
        }
        assertTrue(disk.state.countedMs <= 20 * HOUR)
    }

    @Test fun aCheckpointWithNothingNewWritesNothing() {
        val clock = process(1)
        clock.checkpoint()
        time.run(HOUR)
        clock.checkpoint()
        val writes = disk.writes
        clock.checkpoint()
        assertEquals(writes, disk.writes)
    }

    @Test fun aWaitNeedsBothTheCountedTimeAndTheWallClock() {
        val wait = RunningClock.Wait.starting(countedMs = 2 * HOUR, wallMs = 1_000 * HOUR, durationMs = 24 * HOUR)
        assertFalse(wait.over(26 * HOUR - 1, 1_024 * HOUR))
        assertFalse(wait.over(26 * HOUR, 1_024 * HOUR - 1))
        assertFalse(wait.over(3 * HOUR, 5_000 * HOUR))
        assertTrue(wait.over(26 * HOUR, 1_024 * HOUR))
        assertTrue(RunningClock.Wait.NONE.over(0, 0))
    }
}
