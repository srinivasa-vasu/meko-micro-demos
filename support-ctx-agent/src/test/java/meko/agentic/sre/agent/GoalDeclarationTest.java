package meko.agentic.sre.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import meko.agentic.sre.domain.IncidentReport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two goals have to be simultaneously reachable, or @Action.value still means nothing.
 *
 * Embabel ranks plans by netValue = goal.value + sum(action.value) - sum(action.cost), and that
 * ranking only bites when more than one goal can be reached from the same state. Two goals with
 * mutually exclusive preconditions — one needing confidenceSufficient, the other
 * confidenceInsufficient — would leave exactly one plan again and change nothing.
 */
class GoalDeclarationTest {

    private List<Method> goals() {
        return Arrays.stream(IncidentCommanderAgent.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(AchievesGoal.class))
                .toList();
    }

    @Test
    void thereIsMoreThanOneGoalToRank() {
        assertEquals(2, goals().size(),
                "with one goal the planner has nothing to rank and value is decoration");
    }

    @Test
    void bothGoalsProduceAnIncidentReport() {
        // AgentInvocation is built for IncidentReport. A goal returning anything else makes
        // last(IncidentReport) null, and the caller NPEs in TypedInvocation.invoke — which is
        // exactly how this whole codebase first failed.
        for (var goal : goals()) {
            assertEquals(IncidentReport.class, goal.getReturnType(), goal.getName());
        }
    }

    @Test
    void theGoalsAreSimultaneouslyReachable() {
        var finalize = goals().stream().filter(m -> m.getName().equals("finalizeIncident"))
                .findFirst().orElseThrow();
        var handoff = goals().stream().filter(m -> m.getName().equals("handOffToHuman"))
                .findFirst().orElseThrow();

        var finalizePre = List.of(finalize.getAnnotation(Action.class).pre());
        var handoffPre = List.of(handoff.getAnnotation(Action.class).pre());

        assertTrue(handoffPre.contains("diagnosisAttemptAvailable"));
        assertTrue(finalizePre.contains("confidenceSufficient"));
        assertTrue(finalizePre.containsAll(handoffPre),
                "handoff must be reachable everywhere finalize is, so the two compete rather "
                        + "than partition the state space: " + handoffPre + " vs " + finalizePre);
        assertTrue(handoffPre.stream().noneMatch(p -> p.equals("confidenceInsufficient")),
                "gating handoff on low confidence makes the goals mutually exclusive and the "
                        + "ranking trivial again");
    }

    @Test
    void diagnosingIsWorthMoreThanHandingOff() {
        var finalize = goals().stream().filter(m -> m.getName().equals("finalizeIncident"))
                .findFirst().orElseThrow();
        var handoff = goals().stream().filter(m -> m.getName().equals("handOffToHuman"))
                .findFirst().orElseThrow();

        assertTrue(finalize.getAnnotation(AchievesGoal.class).value()
                        > handoff.getAnnotation(AchievesGoal.class).value(),
                "otherwise the planner hands off even when a cheap validated answer is available");
        assertTrue(finalize.getAnnotation(Action.class).value()
                > handoff.getAnnotation(Action.class).value());
    }
}
