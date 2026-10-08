package meko.agentic.goap.story;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business-level record of what one story run did, written by the agents' actions as they execute.
 * Each action carries a fixed effort estimate (minutes); whether the action ran at all is decided by
 * the planner, so the totals are measured from the executed plan rather than chosen per mode.
 */
public class StoryLedger {

    public enum Kind {
        /** Useful work. */
        WORK,
        /** Work that repeats something already done earlier in the case. */
        REWORK,
        /** The customer (or a colleague) had to be asked for something. */
        ASK,
        /** Context was read from or written to the context store. */
        CONTEXT
    }

    /**
     * @param waiting the minutes are elapsed time waiting on someone else, not hands-on work
     * @param basis   why the step costs that many minutes (from estimates.yml)
     */
    public record Step(String phase, String persona, String action, String text, int minutes, Kind kind, boolean waiting, String basis) {}

    /** @param dialogue spoken between people and agents, as opposed to a system notice, a brief or a draft */
    public record Line(String speaker, String text, boolean dialogue) {}

    /** Something an agent learned from the context store rather than from its own process. */
    public record Found(String source, String text, String writtenBy) {}

    /**
     * Something the context store returned that the agent deliberately did not use: an outdated review, a different
     * customer, a superseded decision, a look-alike policy, a pattern that does not fit. Retrieval is not reuse.
     */
    public record Passed(String source, String text, String writtenBy, String reason) {}

    /** A judgement made at a gate, by a model reading the agent's context or by the fallback rule. */
    public record Decision(String persona, String gate, String question, String answer, String decidedBy, String context, boolean byModel) {}

    private final List<Step> steps = Collections.synchronizedList(new ArrayList<>());
    private final List<Line> transcript = Collections.synchronizedList(new ArrayList<>());
    private final List<Found> found = Collections.synchronizedList(new ArrayList<>());
    private final List<Passed> passed = Collections.synchronizedList(new ArrayList<>());
    private final List<Decision> decisions = Collections.synchronizedList(new ArrayList<>());
    /** Decisions already written to a conversation as decision traces, by identity. */
    private final java.util.Set<Decision> traced = Collections.synchronizedSet(Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    private final Map<String, Integer> counters = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile String phase = "";
    private final RunEvents.Sink events;

    public StoryLedger() {
        this((type, payload) -> { });
    }

    public StoryLedger(RunEvents.Sink events) {
        this.events = events;
    }

    /** Sends a live event for this run (no-op when nobody is watching). */
    public void publish(String type, Object payload) {
        events.send(type, payload);
    }
    private volatile String outcome;
    private final List<Object> writingTasks = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void phase(String phase) {
        this.phase = phase;
    }

    /** Records an executed step. Minutes come from {@link EffortEstimates}, never from the call site. */
    public void step(String persona, String action, String text, EffortEstimates.Estimate effort, Kind kind) {
        Step step = new Step(phase, persona, action, text, effort.getMinutes(), kind, effort.waiting(), effort.getBasis());
        steps.add(step);
        events.send("step", step);
        if (kind == Kind.REWORK) count("repeatedSteps", 1);
    }

    /** Records something said between people and agents. */
    public void say(String speaker, String text) {
        line(new Line(speaker, text, true));
    }

    /** Records a system notice, a brief or a draft: part of the transcript, not dialogue. */
    public void note(String source, String text) {
        line(new Line(source, text, false));
    }

    private void line(Line line) {
        transcript.add(line);
        events.send("line", line);
    }

    public void found(String source, String text, String writtenBy) {
        Found item = new Found(source, text, writtenBy);
        found.add(item);
        events.send("found", item);
    }

    public void decision(Decision decision) {
        decisions.add(decision);
        events.send("decision", decision);
    }

    /** {@code persona}'s decisions not yet written to a conversation, in order; marks them as written. */
    public List<Decision> takeUntraced(String persona) {
        synchronized (decisions) {
            List<Decision> pending = decisions.stream().filter(d -> d.persona().equals(persona) && !traced.contains(d)).toList();
            traced.addAll(pending);
            return pending;
        }
    }

    public List<Decision> decisions() {
        synchronized (decisions) {
            return List.copyOf(decisions);
        }
    }

    public void count(String counter, int delta) {
        counters.merge(counter, delta, Integer::sum);
    }

    public void outcome(String outcome) {
        this.outcome = outcome;
    }

    public List<Step> steps() {
        synchronized (steps) {
            return List.copyOf(steps);
        }
    }

    public List<Line> transcript() {
        synchronized (transcript) {
            return List.copyOf(transcript);
        }
    }

    public List<Found> found() {
        synchronized (found) {
            return List.copyOf(found);
        }
    }

    public void passedOver(String source, String text, String writtenBy, String reason) {
        Passed item = new Passed(source, text, writtenBy, reason);
        passed.add(item);
        events.send("passed", item);
    }

    public List<Passed> passedOver() {
        synchronized (passed) {
            return List.copyOf(passed);
        }
    }

    public Map<String, Integer> counters() {
        synchronized (counters) {
            return Map.copyOf(counters);
        }
    }

    public String outcome() {
        return outcome;
    }

    /**
     * Text an agent wants written for a human, handed to the response writer phase. A run that handles several cases
     * (two loan applications) hands over one task each, written in order.
     */
    public void handToWriter(Object task) {
        writingTasks.add(task);
    }

    /**
     * One entry of a follow-up's audit trail: a turn of the record as read back, or the follow-up's own decision.
     *
     * @param kind   decision, record (an agent's record of something it did) or dialogue
     * @param how    how it was decided or why it was done, from the turn's reasoning
     * @param issue  what the audit flags about this entry, by rule over the record; empty when nothing
     * @param source where the entry came from: the case's conversation, or which party it was requested from
     */
    public record AuditEntry(int n, String who, String kind, String what, String how, String response, String issue, String source) {}

    private volatile List<AuditEntry> auditTrail = List.of();

    public void auditTrail(List<AuditEntry> trail) {
        this.auditTrail = List.copyOf(trail);
    }

    public List<AuditEntry> auditTrail() {
        return auditTrail;
    }

    /** The first task handed to the writer, or null. */
    public Object writingTask() {
        return writingTasks.isEmpty() ? null : writingTasks.getFirst();
    }

    public List<Object> writingTasks() {
        return List.copyOf(writingTasks);
    }

    public int minutes() {
        return steps().stream().mapToInt(Step::minutes).sum();
    }

    public int handsOnMinutes() {
        return steps().stream().filter(s -> !s.waiting()).mapToInt(Step::minutes).sum();
    }

    public int waitingMinutes() {
        return steps().stream().filter(Step::waiting).mapToInt(Step::minutes).sum();
    }
}
