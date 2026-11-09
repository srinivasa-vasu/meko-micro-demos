package meko.agentic.release.run;

import com.embabel.agent.api.event.ActionExecutionResultEvent;
import com.embabel.agent.api.event.AgentProcessEvent;
import com.embabel.agent.api.event.AgentProcessPlanFormulatedEvent;
import com.embabel.agent.api.event.AgentProcessStuckEvent;
import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.plan.Action;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embabel 1.5.1 sends plan, goal and stuck events only to platform-level listeners (Spring beans), not to the
 * per-process listeners in ProcessOptions. This bean receives them and writes each to the job its process runs for:
 * every plan the planner formulates (and each replan), and every action it executes.
 */
@Component
public class PlanEvents implements AgenticEventListener {
    public record Step(String action, double cost) {}

    private final Map<String, Jobs.Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, List<String>> lastPlan = new ConcurrentHashMap<>();

    public void register(String processId, Jobs.Job job) {
        jobs.put(processId, job);
    }

    public void unregister(String processId) {
        jobs.remove(processId);
        lastPlan.remove(processId);
    }

    @Override
    public void onProcessEvent(AgentProcessEvent event) {
        Jobs.Job job = jobs.get(event.getProcessId());
        if (job == null) return;
        if (event instanceof AgentProcessPlanFormulatedEvent formulated) {
            List<Step> steps = new ArrayList<>();
            for (Action action : formulated.getPlan().getActions()) {
                steps.add(new Step(shortName(action.getName()), action.getCost().invoke(formulated.getWorldState())));
            }
            List<String> names = steps.stream().map(Step::action).toList();
            List<String> previous = lastPlan.get(event.getProcessId());
            // Embabel re-announces the remaining plan before every action; keep only genuine changes.
            boolean sameRemainder = previous != null && previous.size() >= names.size()
                    && previous.subList(previous.size() - names.size(), previous.size()).equals(names);
            if (!sameRemainder) {
                job.event("plan", previous == null ? "The planner's plan" : "The planner replanned", steps);
            }
            lastPlan.put(event.getProcessId(), names);
        } else if (event instanceof ActionExecutionResultEvent result) {
            job.event("action", shortName(result.getAction().getName()),
                    Map.of("millis", result.getRunningTime().toMillis(), "status", result.getActionStatus().getStatus().name()));
        } else if (event instanceof AgentProcessStuckEvent) {
            job.event("error", "The planner found no plan to reach the goal from here.", null);
        }
    }

    static String shortName(String actionName) {
        int dot = actionName.lastIndexOf('.');
        return dot < 0 ? actionName : actionName.substring(dot + 1);
    }
}
