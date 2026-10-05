package meko.agentic.goap.story;

import com.embabel.agent.api.event.ActionExecutionResultEvent;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent;
import com.embabel.agent.api.event.AgentProcessStuckEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.plan.Action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Captures what the Embabel planner actually did in one agent process: every plan it formulated (the
 * first one, and each replan after an action changed the world state) and every action it executed.
 */
public class PlanRecorder implements AgenticEventListener {

    public record PlannedStep(String action, double cost) {}

    public record Plan(List<PlannedStep> steps, double cost, int afterActions) {}

    public record Executed(String action, long millis, String status) {}

    private final RunEvents.Sink events;
    private final String phase;
    private final List<Plan> plans = Collections.synchronizedList(new ArrayList<>());
    private final List<Executed> executed = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean stuck;

    public PlanRecorder() {
        this("", (type, payload) -> { });
    }

    public PlanRecorder(String phase, RunEvents.Sink events) {
        this.phase = phase;
        this.events = events;
    }

    public record PlanEvent(String phase, Plan plan, boolean replan) {}

    public record ActionEvent(String phase, Executed action) {}

    @Override
    public void onProcessEvent(AgentProcessEvent event) {
        if (event instanceof AgentProcessPlanFormulatedEvent formulated) {
            List<PlannedStep> steps = new ArrayList<>();
            double total = 0;
            for (Action action : formulated.getPlan().getActions()) {
                double cost = action.getCost().invoke(formulated.getWorldState());
                total += cost;
                steps.add(new PlannedStep(shortName(action.getName()), cost));
            }
            Plan plan = new Plan(List.copyOf(steps), total, executed.size());
            // Embabel re-announces the remaining plan before every action; keep only genuine changes.
            if (plans.isEmpty() || !remainderOf(plans.getLast()).equals(plan.steps())) {
                boolean replan = !plans.isEmpty();
                plans.add(plan);
                events.send("plan", new PlanEvent(phase, plan, replan));
            }
        } else if (event instanceof ActionExecutionResultEvent result) {
            Executed done = new Executed(shortName(result.getAction().getName()),
                    result.getRunningTime().toMillis(), result.getActionStatus().getStatus().name());
            executed.add(done);
            events.send("action", new ActionEvent(phase, done));
        } else if (event instanceof AgentProcessStuckEvent) {
            stuck = true;
        }
    }

    /** The part of a recorded plan still ahead after the actions executed since it was made. */
    private List<PlannedStep> remainderOf(Plan plan) {
        int done = executed.size() - plan.afterActions();
        return done >= plan.steps().size() ? List.of() : plan.steps().subList(Math.max(0, done), plan.steps().size());
    }

    public List<Plan> plans() {
        synchronized (plans) {
            return List.copyOf(plans);
        }
    }

    public List<Executed> executed() {
        synchronized (executed) {
            return List.copyOf(executed);
        }
    }

    public boolean stuck() {
        return stuck;
    }

    static String shortName(String actionName) {
        int dot = actionName.lastIndexOf('.');
        return dot < 0 ? actionName : actionName.substring(dot + 1);
    }
}
