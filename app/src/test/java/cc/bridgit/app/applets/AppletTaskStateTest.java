package cc.bridgit.app.applets;

import static cc.bridgit.app.applets.AppletTaskState.Phase.*;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class AppletTaskStateTest {
    private AppletTaskState running() {
        return new AppletTaskState("monitor", 0, RUNNING, Arrays.asList("tablet", "camera"), Collections.emptySet());
    }

    @Test public void pauseWaitsForBothDevicesAndRejectsOtherTaskAndStaleReplies() {
        AppletTaskState waiting = running().pause();
        assertSame(waiting, waiting.pause());
        assertThrows(IllegalStateException.class, waiting::resume);
        assertSame(waiting, waiting.acknowledge("check-now", 1, "camera", PAUSING));
        assertSame(waiting, waiting.acknowledge("monitor", 0, "camera", PAUSING));
        assertSame(waiting, waiting.acknowledge("monitor", 1, "camera", STARTING));
        AppletTaskState one = waiting.acknowledge("monitor", 1, "tablet", PAUSING);
        assertEquals(PAUSING, one.phase);
        assertEquals(Collections.singleton("camera"), one.pending);
        assertSame(one, one.acknowledge("monitor", 1, "tablet", PAUSING));
        AppletTaskState stopped = one.acknowledge("monitor", 1, "camera", PAUSING);
        assertEquals(PAUSED, stopped.phase);
        AppletTaskState starting = stopped.resume();
        assertEquals(2, starting.generation);
        assertEquals(RUNNING, starting.acknowledge("monitor", 2, "camera", STARTING)
                .acknowledge("monitor", 2, "tablet", STARTING).phase);
    }

    @Test public void interruptingStartupRequiresAllParticipantsToStop() {
        AppletTaskState starting = new AppletTaskState("monitor", 2, STARTING,
                Arrays.asList("tablet", "camera"), Collections.singleton("camera"));
        AppletTaskState stopping = starting.pause();
        assertEquals(3, stopping.generation);
        assertEquals(starting.participants, stopping.pending);
        assertSame(stopping, stopping.acknowledge("monitor", 2, "camera", STARTING));
    }
}
