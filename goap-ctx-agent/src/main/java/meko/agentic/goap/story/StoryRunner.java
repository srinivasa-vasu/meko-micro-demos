package meko.agentic.goap.story;

import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Budget;
import com.embabel.agent.core.ProcessOptions;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs one agent as its own Embabel agent process and records what the planner did. A story is one or
 * more phases; agents in different phases share nothing in memory, only what they pass explicitly
 * (a case id, a ticket note) and whatever the context store holds.
 */
@Component
public class StoryRunner {

    public record Phase(String label, String agent, String status, boolean stuck,
                        List<PlanRecorder.Plan> plans, List<PlanRecorder.Executed> executed,
                        double plannedCost, Object result) {}

    private final AgentPlatform platform;
    private final PlanEventRouter router;

    public StoryRunner(AgentPlatform platform, PlanEventRouter router) {
        this.platform = platform;
        this.router = router;
    }

    public Phase run(String label, String agentName, StoryLedger ledger, Object... inputs) {
        Agent agent = platform.agents().stream()
                .filter(a -> a.getName().equals(agentName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No agent deployed with name " + agentName));
        PlanRecorder recorder = new PlanRecorder(label, ledger::publish);
        // The default budget (cost 2.0) can end a long baseline plan early; the stories need the full plan.
        ProcessOptions options = new ProcessOptions()
                .withBudget(new Budget(20.0, 60, Budget.DEFAULT_TOKEN_LIMIT));
        ledger.phase(label);
        ledger.publish("phase", java.util.Map.of("label", label, "agent", agentName));
        AgentProcess process = platform.createAgentProcessFrom(agent, options, inputs);
        router.register(process.getId(), recorder);
        try {
            process.run();
        } finally {
            router.unregister(process.getId());
        }
        Object result = process.getFinished() ? process.lastResult() : null;
        List<PlanRecorder.Executed> executed = recorder.executed();
        List<PlanRecorder.Plan> plans = recorder.plans();
        double executedCost = executed.stream()
                .mapToDouble(e -> costOf(plans, e.action()))
                .sum();
        Phase phase = new Phase(label, agentName, process.getStatus().name(), recorder.stuck(),
                plans, executed, executedCost, null);
        ledger.publish("phaseEnd", phase);
        return new Phase(label, agentName, process.getStatus().name(), recorder.stuck(),
                plans, executed, executedCost, result);
    }

    /** Cost of an executed action, as the planner priced it in the plan that scheduled it. */
    private static double costOf(List<PlanRecorder.Plan> plans, String action) {
        for (int i = plans.size() - 1; i >= 0; i--) {
            for (PlanRecorder.PlannedStep step : plans.get(i).steps()) {
                if (step.action().equals(action)) return step.cost();
            }
        }
        return 0;
    }
}
