package meko.agentic.goap.agent;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.RunSubagent;
import meko.agentic.goap.api.CaseModels.CaseOpened;
import meko.agentic.goap.api.CaseModels.CaseResolution;
import meko.agentic.goap.api.CaseModels.ContextReady;
import meko.agentic.goap.api.CaseModels.InvestigationComplete;
import meko.agentic.goap.api.CaseModels.InvestigationNeedsTakeover;
import meko.agentic.goap.api.CaseModels.InvestigationResult;
import meko.agentic.goap.api.CaseModels.PolicyAssessment;
import meko.agentic.goap.api.CaseModels.ResolutionOutput;
import meko.agentic.goap.api.CaseModels.TimelineEvent;
import meko.agentic.goap.api.CaseRequest;
import meko.agentic.goap.model.ModelRoute;
import meko.agentic.goap.context.ContextPack;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Agent(description = "Context-aware GOAP commander for a BFSI payment-dispute case")
public class CaseCommanderAgent {
    private final IntakeAgent intakeAgent;
    private final InvestigationAgent investigationAgent;
    private final InvestigationTakeoverAgent takeoverAgent;
    private final PolicyAgent policyAgent;
    private final ResolutionAgent resolutionAgent;
    private final CaseRunRepository runs;

    public CaseCommanderAgent(
            IntakeAgent intakeAgent,
            InvestigationAgent investigationAgent,
            InvestigationTakeoverAgent takeoverAgent,
            PolicyAgent policyAgent,
            ResolutionAgent resolutionAgent,
            CaseRunRepository runs) {
        this.intakeAgent = intakeAgent;
        this.investigationAgent = investigationAgent;
        this.takeoverAgent = takeoverAgent;
        this.policyAgent = policyAgent;
        this.resolutionAgent = resolutionAgent;
        this.runs = runs;
    }

    @Action(description = "Open a synthetic BFSI payment-dispute case", cost = 0.03)
    public CaseOpened openCase(CaseRequest request) {
        String caseId = "CASE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        boolean meko = request.useMeko();
        runs.create(caseId).add(new TimelineEvent(
                Instant.now(), "case-commander", "CASE_OPENED",
                "Customer disputes a card payment: charged but payment failed.", 0.03,
                "n/a", "starting state"));
        return new CaseOpened(
                caseId,
                "CUST-1042",
                "TX-48291",
                "I was charged, but the payment failed.",
                meko,
                request.simulateTakeoverRequested());
    }

    // RunSubagent works by throwing: nothing after it in an action ever runs. So every delegating action
    // ends with `return RunSubagent...`, and each step's timeline event is recorded by the action that
    // receives that step's result.

    @Action(description = "Delegate intake to a specialist and build the first case context", cost = 0.08)
    public ContextReady intake(CaseOpened opened) {
        return RunSubagent.fromAnnotatedInstance(intakeAgent, ContextReady.class);
    }

    @Action(description = "Delegate the investigation; a simulated failure produces a recoverable branch", cost = 0.24)
    public InvestigationResult investigate(ContextReady context) {
        event(context.caseId(), "intake-agent", "INTAKE",
                "Initial context assembled: " + context.completeness() + "%. Sources=" + context.sources(), 0.08, null);
        return RunSubagent.fromAnnotatedInstance(investigationAgent, InvestigationResult.class);
    }

    @Action(description = "Take over an incomplete investigation using persisted agent context", cost = 0.18)
    public InvestigationComplete takeover(InvestigationNeedsTakeover input) {
        event(input.caseId(), "investigation-agent", "AGENT_LOST",
                "Investigator stopped after checkpoint " + input.checkpointHash() + ". GOAP must select takeover.",
                0.24, input.modelRoute());
        return RunSubagent.fromAnnotatedInstance(takeoverAgent, InvestigationComplete.class);
    }

    @Action(description = "Delegate policy interpretation to a specialist", cost = 0.16)
    public PolicyAssessment policy(InvestigationComplete investigation) {
        if ("investigation-takeover-agent".equals(investigation.agent())) {
            event(investigation.caseId(), "investigation-takeover-agent", "TAKEOVER",
                    investigation.resumed()
                            ? "Recovered checkpoint and resumed without repeating completed work."
                            : "No durable checkpoint was available; takeover reconstructed the unfinished path.",
                    0.18, investigation.modelRoute());
            if (!investigation.resumed()) runs.get(investigation.caseId()).addRework(2);
        } else {
            event(investigation.caseId(), "investigation-agent", "INVESTIGATION_COMPLETE",
                    investigation.summary(), 0.24, investigation.modelRoute());
        }
        return RunSubagent.fromAnnotatedInstance(policyAgent, PolicyAssessment.class);
    }

    @Action(description = "Delegate the customer-facing resolution to a specialist", cost = 0.07)
    public ResolutionOutput resolution(PolicyAssessment policy) {
        event(policy.caseId(), "policy-agent", "POLICY",
                policy.policy() + ": refundEligible=" + policy.refundEligible(), 0.16, policy.modelRoute());
        return RunSubagent.fromAnnotatedInstance(resolutionAgent, ResolutionOutput.class);
    }

    @Action(description = "Close the case and record the outcome", cost = 0.01)
    @AchievesGoal(description = "Case is resolved")
    public CaseResolution close(PolicyAssessment policy, ResolutionOutput resolution) {
        CaseRunState state = runs.get(policy.caseId());
        event(policy.caseId(), "resolution-agent", "RESOLVED", resolution.outcome(), 0.07, resolution.modelRoute());

        boolean resumed = state.timeline().stream().anyMatch(e -> "TAKEOVER".equals(e.event()));
        ContextPack finalPack = resolution.contextPack().withRoute(resolution.modelRoute());

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("contextCompleteness", finalPack.completeness());
        comparison.put("contextSources", finalPack.contextSources());
        comparison.put("handoffCount", state.timeline().stream().filter(e -> "TAKEOVER".equals(e.event())).count());
        comparison.put("reworkActions", state.reworkActions());
        comparison.put("persistentContext", policy.mekoEnabled());
        comparison.put("priorLearningReused", finalPack.priorLearningReused());
        comparison.put("investigationModel", findModel(state, "AGENT_LOST", "INVESTIGATION_COMPLETE"));
        comparison.put("resolutionModel", resolution.modelRoute().model());
        comparison.put("nextContextOwner", finalPack.ownerAgent());
        comparison.put("remainingContextGap", String.join("; ", finalPack.missingContext()));

        return new CaseResolution(
                policy.caseId(),
                policy.refundEligible() ? "RESOLVED" : "ESCALATED",
                resolution.outcome(),
                policy.refundEligible() ? "resolution-agent" : "human-payments-specialist",
                finalPack.completeness(),
                state.reworkActions(),
                resumed && state.reworkActions() == 0,
                state.timeline(),
                comparison,
                finalPack,
                resolution.learnings(),
                finalPack.priorLearningReused(),
                policy.mekoEnabled());
    }

    private String findModel(CaseRunState state, String... eventNames) {
        return state.timeline().stream()
                .filter(e -> List.of(eventNames).contains(e.event()))
                .map(TimelineEvent::model)
                .filter(m -> m != null && !m.equals("n/a"))
                .findFirst()
                .orElse("n/a");
    }

    private void event(String caseId, String agent, String event, String detail, double cost, ModelRoute route) {
        CaseRunState state = runs.get(caseId);
        if (state != null) {
            state.add(new TimelineEvent(
                    Instant.now(), agent, event, detail, cost,
                    route == null ? "n/a" : route.model(),
                    route == null ? "" : route.rationale()));
        }
    }

}
