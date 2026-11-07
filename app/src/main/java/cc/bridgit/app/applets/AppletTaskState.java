package cc.bridgit.app.applets;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** An immutable snapshot for one task. Persist each transition before dispatching its commands. */
public final class AppletTaskState {
    public enum Phase { STARTING, RUNNING, PAUSING, PAUSED }

    public final String taskId;
    public final long generation;
    public final Phase phase;
    public final Set<String> participants;
    public final Set<String> pending;

    public AppletTaskState(String taskId, long generation, Phase phase,
                           Collection<String> participants, Collection<String> pending) {
        if (taskId.isEmpty() || generation < 0) throw new IllegalArgumentException("Invalid task identity");
        this.taskId = taskId;
        this.generation = generation;
        this.phase = Objects.requireNonNull(phase);
        this.participants = snapshot(participants);
        this.pending = snapshot(pending);
        if (!this.participants.containsAll(this.pending)) throw new IllegalArgumentException("Unknown participant");
        boolean inTransition = phase == Phase.STARTING || phase == Phase.PAUSING;
        if (inTransition == this.pending.isEmpty()) throw new IllegalArgumentException("Invalid acknowledgment set");
    }

    private static Set<String> snapshot(Collection<String> ids) {
        Set<String> copy = new LinkedHashSet<>(ids);
        if (copy.contains(null) || copy.contains(""))
            throw new IllegalArgumentException("Invalid participant");
        return Collections.unmodifiableSet(copy);
    }

    public AppletTaskState pause() {
        if (phase == Phase.PAUSING || phase == Phase.PAUSED) return this;
        return transition(Phase.PAUSING, Phase.PAUSED);
    }

    public AppletTaskState resume() {
        if (phase == Phase.PAUSING) throw new IllegalStateException("Waiting for stop confirmations");
        if (phase != Phase.PAUSED) return this;
        return transition(Phase.STARTING, Phase.RUNNING);
    }

    private AppletTaskState transition(Phase waiting, Phase finished) {
        return new AppletTaskState(taskId, Math.addExact(generation, 1),
                participants.isEmpty() ? finished : waiting, participants, participants);
    }

    /** The caller supplies the authenticated participant identity. A timeout alone never acknowledges a stop. */
    public AppletTaskState acknowledge(String task, long epoch, String participant, Phase command) {
        if (!taskId.equals(task) || epoch != generation || command != phase || !pending.contains(participant))
            return this;
        Set<String> remaining = new LinkedHashSet<>(pending);
        remaining.remove(participant);
        Phase next = remaining.isEmpty() ? (phase == Phase.STARTING ? Phase.RUNNING : Phase.PAUSED) : phase;
        return new AppletTaskState(taskId, generation, next, participants, remaining);
    }
}
